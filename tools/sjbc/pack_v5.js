const crypto = require('crypto');
const fs = require('fs');
const NL = String.fromCharCode(10);

/**
 * M8 容器 v5（生产版）
 *  头: magic SJBE | ver=5 | kdf | cipher | flags | iv(12) | tag(16) | body
 *  密钥: 主密钥 -> HKDF-SHA256 派生两把子密钥（pool / code），各自独立 AEAD
 *  明文 body:
 *     行1: SJBC5
 *     行2: ENTRIES n o1 o2 ...
 *     然后: POOL 行（N idx num / S idx str）——整体作为一层 AEAD 载荷
 *     然后: CODE hex —— 另一层 AEAD 载荷
 *  ⇒ 两层各自认证：改了池或改了码都会在对应层被拒
 */
function hkdf(master, info, len) {
  return crypto.hkdfSync('sha256', master, Buffer.from('sjbc-v5-salt'), Buffer.from(info), len);
}
function buildV5({ bytes, pool, entries }, masterKey) {
  const kPool = Buffer.from(hkdf(masterKey, 'pool', 32));
  const kCode = Buffer.from(hkdf(masterKey, 'code', 32));
  const poolLines = ['SJBC5', 'ENTRIES ' + entries.length + ' ' + entries.join(' ')];
  const esc = t => String(t).split('\\').join('\\\\').split('\n').join('\\n');
  pool.forEach((v, i) => poolLines.push(typeof v === 'number' ? 'N ' + i + ' ' + v : 'S ' + i + ' ' + esc(v)));
  const poolPlain = Buffer.from(poolLines.join(NL), 'utf8');
  const codePlain = Buffer.from(Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(''), 'hex');
  const ivP = crypto.randomBytes(12), ivC = crypto.randomBytes(12);
  const cp = crypto.createCipheriv('aes-256-gcm', kPool, ivP);
  const poolEnc = Buffer.concat([cp.update(poolPlain), cp.final()]); const tagP = cp.getAuthTag();
  const cc = crypto.createCipheriv('aes-256-gcm', kCode, ivC);
  const codeEnc = Buffer.concat([cc.update(codePlain), cc.final()]); const tagC = cc.getAuthTag();
  const h = Buffer.alloc(8); h.write('SJBE', 0, 'ascii'); h[4] = 5; h[5] = 1; h[6] = 1; h[7] = 0;
  const parts = [h, ivP, tagP, Buffer.from([poolEnc.length >> 24, (poolEnc.length >> 16) & 255, (poolEnc.length >> 8) & 255, poolEnc.length & 255]), poolEnc, ivC, tagC, codeEnc];
  return Buffer.concat(parts);
}
function openV5(blob, masterKey) {
  if (blob.slice(0, 4).toString() !== 'SJBE') throw new Error('bad magic');
  const ver = blob[4];
  if (ver !== 5) throw new Error('未知容器版本 ' + ver);
  const kPool = Buffer.from(hkdf(masterKey, 'pool', 32)), kCode = Buffer.from(hkdf(masterKey, 'code', 32));
  let o = 8;
  const ivP = blob.slice(o, o+12); o += 12; const tagP = blob.slice(o, o+16); o += 16;
  const poolLen = (blob[o] << 24 | blob[o+1] << 16 | blob[o+2] << 8 | blob[o+3]) >>> 0; o += 4;
  const poolEnc = blob.slice(o, o + poolLen); o += poolLen;
  const ivC = blob.slice(o, o+12); o += 12; const tagC = blob.slice(o, o+16); o += 16;
  const codeEnc = blob.slice(o);
  const dp = crypto.createDecipheriv('aes-256-gcm', kPool, ivP); dp.setAuthTag(tagP);
  const poolPlain = Buffer.concat([dp.update(poolEnc), dp.final()]);
  const dc = crypto.createDecipheriv('aes-256-gcm', kCode, ivC); dc.setAuthTag(tagC);
  const code = Buffer.concat([dc.update(codeEnc), dc.final()]);
  return { poolText: poolPlain.toString('utf8'), code };
}
module.exports = { buildV5, openV5, hkdf };