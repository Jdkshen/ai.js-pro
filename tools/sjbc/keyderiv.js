const crypto = require('crypto');
/**
 * M9：主密钥不再硬编码，而是从**签名证书指纹**派生
 *   master = HKDF-SHA256(ikm = certSha256(32B), salt = 'sjbc-m9-cert', info = 'master', 32)
 * ⇒ 只有"由该证书签名的进程"能算出同一把密钥；重签（换证书）⇒ 指纹变 ⇒ 解不开。
 */
function masterFromCert(certSha256Hex) {
  const ikm = Buffer.from(certSha256Hex.replace(/[^0-9a-fA-F]/g, ''), 'hex');
  if (ikm.length !== 32) throw new Error('证书指纹应为 32 字节');
  return Buffer.from(crypto.hkdfSync('sha256', ikm, Buffer.from('sjbc-m9-cert'), Buffer.from('master'), 32));
}
module.exports = { masterFromCert };
