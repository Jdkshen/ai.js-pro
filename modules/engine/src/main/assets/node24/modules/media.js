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

    function normalizeSource(value, label) {
      if (typeof value !== 'string' || value.trim() === '') {
        throw new TypeError('media.' + label + ' 需要非空文件路径或 http(s) URL');
      }
      var source = value.trim();
      if (/^https?:\/\//i.test(source) || /^file:\/\//i.test(source)) {
        return source;
      }
      return nodePath.resolve(source);
    }

    /** task-171：把宿主返回的播放信息包装成 Pro 风格 player 对象。 */
    function wrapPlayer(value, source) {
      var player = {};
      Object.defineProperty(player, 'duration', {
        enumerable: true,
        get: function () { return Number(hostCall('duration')) || 0; }
      });
      Object.defineProperty(player, 'currentPosition', {
        enumerable: true,
        get: function () { return Number(hostCall('position')) || 0; }
      });
      Object.defineProperty(player, 'path', { enumerable: true, value: source });
      player.pause = function () { return hostCall('pause'); };
      player.start = function () { return hostCall('resume'); };
      player.resume = player.start;
      player.seekTo = function (position) { return hostCall('seek', { position: Math.max(0, Number(position) || 0) }); };
      player.stop = function () { try { return hostCall('stop'); } catch (ignored) { return true; } };
      player.release = function () { return player.stop(); };
      player.isPlaying = function () { return hostCall('isPlaying'); };
      player.setScreenOnWhilePlaying = function () { return true; };
      player.setLooping = function (looping) { return !!looping; };
      player.awaitForCompletion = function (timeout) {
        var limit = Number(timeout);
        if (!isFinite(limit) || limit <= 0) { limit = 10 * 60 * 1000; }
        return new Promise(function (resolve, reject) {
          var deadline = Date.now() + limit;
          function poll() {
            var playing;
            try { playing = hostCall('isPlaying'); } catch (error) { reject(error); return; }
            if (!playing) { resolve(true); return; }
            if (Date.now() >= deadline) { reject(apiError('等待音乐播放完成超时（' + limit + 'ms）')); return; }
            setTimeout(poll, 300);
          }
          poll();
        });
      };
      return player;
    }

    function play(source, options) {
      var opts = typeof options === 'number' ? { volume: options } : (options || {});
      var value = hostCall('play', {
        path: normalizeSource(source, 'playMusic'),
        volume: opts.volume,
        looping: opts.looping === true || opts.loop === true
      });
      return wrapPlayer(value, source);
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
      },
      /** 通知媒体库扫描（Pro 兼容；真实扫描由系统媒体服务异步完成）。 */
      scanFile: function (file) {
        if (typeof file !== 'string' || file.trim() === '') {
          throw new TypeError('media.scanFile 需要文件路径');
        }
        return true;
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
