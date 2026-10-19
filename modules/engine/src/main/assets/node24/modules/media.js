// SPDX-License-Identifier: MIT
/*
 * Node24 media 模块（task-158）：真实 MediaPlayer 播放 / AudioManager 音量 / 本地音频扫描。
 * 宿主协议：kind:"media"，action = play|pause|resume|stop|isPlaying|duration|position|seek|
 * getVolume|setVolume|scanAudio（见 Node24HostBridge.handleMedia）。
 *
 * 兼容 Pro 语义的常用入口：
 *   media.playMusic(path[, volume]) / stopMusic / pauseMusic / resumeMusic / isMusicPlaying
 *   media.getMusicDuration() / seekToMusic(ms) / getMusicPosition()
 *   media.playSound(path[, volume, loop]) / stopSound()
 *   media.getVolume([stream]) -> {stream, volume, max}；media.setVolume(volume[, stream])
 *   media.scanAudio([{dir, limit}]) -> [{path,name,size}]
 *   media.music = {play,pause,resume,stop,isPlaying,getVolume,setVolume,scan,...}
 */
(function () {
  'use strict';

  if (typeof globalThis.__ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] media 模块必须在 Node24 prelude 之后加载（缺少 __ajsRegisterModule）');
  }

  globalThis.__ajsRegisterModule('media', function (api) {
    var nodePath = require('path');

    function apiError(message) {
      var error = new Error('[Auto.js] media: ' + message);
      error.name = 'AutoJsMediaError';
      return error;
    }

    function hostCall(action, fields) {
      var request = { kind: 'media', action: action };
      if (fields) {
        for (var key in fields) {
          if (Object.prototype.hasOwnProperty.call(fields, key)) {
            request[key] = fields[key];
          }
        }
      }
      var raw;
      try {
        raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      } catch (error) {
        throw apiError('宿主桥不可用：' + String(error && error.message || error));
      }
      var response;
      try {
        response = JSON.parse(raw);
      } catch (error) {
        throw apiError('宿主返回非法 JSON：' + raw);
      }
      if (!response || response.ok !== true) {
        throw apiError((response && response.error) || (action + ' 失败'));
      }
      return response.value;
    }

    function absolutePath(value, label) {
      if (typeof value !== 'string' || value.trim() === '') {
        throw new TypeError('media.' + label + ' 需要非空文件路径');
      }
      return nodePath.resolve(value);
    }

    function play(path, options) {
      var opts = typeof options === 'number' ? { volume: options } : (options || {});
      return hostCall('play', {
        path: absolutePath(path, 'playMusic'),
        volume: opts.volume,
        looping: opts.looping === true || opts.loop === true
      });
    }

    var media = {
      playMusic: function (path, options) { return play(path, options); },
      stopMusic: function () { return hostCall('stop'); },
      pauseMusic: function () { return hostCall('pause'); },
      resumeMusic: function () { return hostCall('resume'); },
      isMusicPlaying: function () { return hostCall('isPlaying'); },
      getMusicDuration: function () { return hostCall('duration'); },
      getMusicPosition: function () { return hostCall('position'); },
      seekToMusic: function (position) { return hostCall('seek', { position: Math.max(0, Number(position) || 0) }); },
      playSound: function (path, volume, loop) { return play(path, { volume: volume, looping: loop }); },
      stopSound: function () { return hostCall('stop'); },
      getVolume: function (stream) { return hostCall('getVolume', { stream: stream || 'music' }); },
      setVolume: function (volume, stream) {
        return hostCall('setVolume', { volume: Number(volume) || 0, stream: stream || 'music' });
      },
      getMaxVolume: function (stream) {
        return hostCall('getVolume', { stream: stream || 'music' }).max;
      },
      scanAudio: function (options) {
        var opts = options || {};
        return hostCall('scanAudio', { dir: opts.dir || '', limit: opts.limit });
      }
    };

    media.music = {
      play: media.playMusic,
      pause: media.pauseMusic,
      resume: media.resumeMusic,
      stop: media.stopMusic,
      isPlaying: media.isMusicPlaying,
      getDuration: media.getMusicDuration,
      position: media.getMusicPosition,
      seek: media.seekToMusic,
      getVolume: media.getVolume,
      setVolume: media.setVolume,
      scan: media.scanAudio
    };
    media.scanMusic = media.scanAudio;
    return media;
  });
})();
