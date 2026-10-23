// SPDX-License-Identifier: MIT
"use strict";
// Pure UCI output parser; no dependency on host Java or native JNI.
function parseInfoLine(line) {
  var info = { multipv: 1, pv: [] };
  var m = /\bmultipv (\d+)/.exec(line); if (m) { info.multipv = parseInt(m[1], 10); }
  m = /\bdepth (\d+)/.exec(line); if (m) { info.depth = parseInt(m[1], 10); }
  m = /\bseldepth (\d+)/.exec(line); if (m) { info.seldepth = parseInt(m[1], 10); }
  m = /\bnodes (\d+)/.exec(line); if (m) { info.nodes = parseInt(m[1], 10); }
  m = /\btime (\d+)/.exec(line); if (m) { info.timeMs = parseInt(m[1], 10); }
  m = /\bscore (cp|mate) (-?\d+)/.exec(line);
  if (m) { if (m[1] === 'cp') { info.scoreCp = parseInt(m[2], 10); } else { info.mate = parseInt(m[2], 10); } }
  m = /\bpv ([a-i][0-9][a-i][0-9](?: [a-i][0-9][a-i][0-9])*)/.exec(line);
  if (m) { info.pv = m[1].split(' '); info.move = info.pv[0]; }
  if (!info.move) { return null; }
  return info;
}

function mapCandidate(info) {
  var out = { multipv: info.multipv, move: info.move, pv: info.pv || [] };
  if (info.scoreCp !== undefined) { out.scoreCp = info.scoreCp; }
  if (info.mate !== undefined) { out.mate = info.mate; }
  if (info.depth !== undefined) { out.depth = info.depth; }
  if (info.seldepth !== undefined) { out.seldepth = info.seldepth; }
  if (info.nodes !== undefined) { out.nodes = info.nodes; }
  if (info.timeMs !== undefined) { out.timeMs = info.timeMs; }
  return out;
}

/** UCI 原始输出 -> 与 QuickJsHostBridge.pikafishAnalyzeInternal 同形状的结果。 */
function parseUci(raw) {
  raw = raw == null ? '' : String(raw);
  var bestmove = null, byPv = {};
  var lines = raw.split('\n');
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line.indexOf('bestmove') === 0) {
      var parts = line.split(/\s+/);
      if (parts.length >= 2) { bestmove = parts[1]; }
      continue;
    }
    if (line.indexOf('info ') !== 0) { continue; }
    var info = parseInfoLine(line);
    if (!info) { continue; }
    byPv[info.multipv] = info; // 同路取最后一条（深度更大）
  }
  var keys = Object.keys(byPv).map(Number).sort(function (a, b) { return a - b; });
  if (keys.length === 0 && (!bestmove || bestmove === '(none)')) {
    return { ok: false, error: raw.length ? raw : '引擎没有输出' };
  }
  var candidates = keys.length ? keys.map(function (k) { return mapCandidate(byPv[k]); }) : [{ multipv: 1, move: bestmove, pv: [] }];
  var top = candidates[0];
  var result = { ok: true, move: (bestmove && bestmove !== '(none)') ? bestmove : top.move, candidates: candidates, raw: raw };
  if (top.scoreCp !== undefined) { result.scoreCp = top.scoreCp; }
  if (top.mate !== undefined) { result.mate = top.mate; }
  if (top.depth !== undefined) { result.depth = top.depth; }
  if (top.seldepth !== undefined) { result.seldepth = top.seldepth; }
  if (top.nodes !== undefined) { result.nodes = top.nodes; }
  if (top.timeMs !== undefined) { result.timeMs = top.timeMs; }
  result.pv = top.pv;
  return result;
}

module.exports = { parseUci };
