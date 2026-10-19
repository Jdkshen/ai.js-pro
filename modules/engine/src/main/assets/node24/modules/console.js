// Keep Node's formatting while carrying console levels through the stdout bridge.
(function () {
  'use strict';
  if (globalThis.__ajsConsoleLevelsInstalled || !globalThis.console) return;
  var output = globalThis.console;
  var write = output.log.bind(output);
  var format = require('node:util').format;
  var prefix = '\u001eAJSLOG1:';
  function emit(level, args) {
    var text = format.apply(null, args);
    var lines = text.split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) write(prefix + level + ':' + lines[i]);
  }
  var levels = { verbose: 'V', debug: 'D', log: 'D', info: 'I', warn: 'W', error: 'E' };
  Object.keys(levels).forEach(function (name) {
    output[name] = function () { emit(levels[name], Array.prototype.slice.call(arguments)); };
  });
  output.assert = function (condition) {
    if (condition) return;
    var args = Array.prototype.slice.call(arguments, 1);
    emit('A', ['Assertion failed' + (args.length ? ': ' + format.apply(null, args) : '')]);
  };
  globalThis.__ajsConsoleLevelsInstalled = true;
})();
