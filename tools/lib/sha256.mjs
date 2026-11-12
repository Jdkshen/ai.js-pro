// tools/lib/sha256.mjs —— P2-4：sha256 纯函数库（被 15 个脚本各自实现过的那一段）。
import crypto from 'node:crypto';
import fs from 'node:fs';

/** Buffer/Uint8Array/string ⇒ hex（字符串按 utf8）。 */
export function sha256Hex(input) {
  return crypto.createHash('sha256').update(input).digest('hex');
}

/** 文件内容 ⇒ hex（读不到会抛，交由调用方决定报错口径）。 */
export function sha256File(file) {
  return sha256Hex(fs.readFileSync(file));
}

/** 展示用短哈希（默认 16 位）；保持与既有脚本的 slice(0,16) 一致的观感。 */
export function shortHex(hex, length = 16) {
  return String(hex).slice(0, length);
}

/** 对账：返回 {ok, expected, actual}（不抛，便于脚本自己决定措辞）。 */
export function verifyFileSha256(file, expected) {
  const actual = sha256File(file);
  return { ok: actual === expected, expected: String(expected || ''), actual };
}
