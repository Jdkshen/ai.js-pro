#!/usr/bin/env node
/**
 * 把插件发布资产上传到 GitHub Release（最新版）。
 *
 * 用法（PowerShell）：$env:GITHUB_TOKEN='<token>'; node tools/upload-plugin-release.mjs
 * 可选：$env:RELEASE_TAG='latest'（默认 latest）、$env:UPLOAD_DIR='.artifacts/updates'
 *
 * 说明：只上传资产，**不推代码**；token 只需 repo（或 public_repo）权限，用完可撤销。
 * 顺序照约定：先三个 APK，最后 plugins.json（客户端按 file 字段名下载）。
 */
import { readFileSync, existsSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { basename, join } from 'node:path';

const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';
if (!token) { console.error('缺少 GITHUB_TOKEN（或 GH_TOKEN）'); process.exit(2); }
const owner = 'Jdkshen';
const repoName = 'ai.js-pro';
const tag = process.env.RELEASE_TAG || 'latest';
const dir = process.env.UPLOAD_DIR || '.artifacts/updates';
const files = ['ocr-plugin.apk', 'xiangqi-plugin.apk', 'apkbuilder-plugin.apk', 'plugins.json'];
const api = `https://api.github.com/repos/${owner}/${repoName}`;
const headers = { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'User-Agent': 'aijspro-plugin-upload' };

async function apiFetch(url, init = {}) {
  const res = await fetch(url, { ...init, headers: { ...headers, ...(init.headers || {}) } });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { }
  return { status: res.status, json, text };
}

async function main() {
  let rel = await apiFetch(`${api}/releases/tags/${tag}`);
  if (rel.status === 404) {
    console.log(`未找到 tag=${tag} 的 Release，创建一个（不推代码，仅创建 Release）…`);
    rel = await apiFetch(`${api}/releases`, { method: 'POST', body: JSON.stringify({ tag_name: tag, name: tag, body: 'AI.js Pro 插件（OCR / 象棋 / 打包）', draft: false, prerelease: false }) });
    if (rel.status >= 300) { console.error('创建 Release 失败：', rel.status, rel.text.slice(0, 300)); process.exit(1); }
  }
  if (rel.status >= 300) { console.error('读取 Release 失败：', rel.status, rel.text.slice(0, 300)); process.exit(1); }
  const uploadUrl = rel.json.upload_url.replace('{?name,label}', '');
  console.log('Release id=' + rel.json.id + ' tag=' + rel.json.tag_name);
  for (const name of files) {
    const path = join(dir, name);
    if (!existsSync(path)) { console.error('缺文件：' + path); process.exit(1); }
    const bytes = readFileSync(path);
    const sha = createHash('sha256').update(bytes).digest('hex');
    const existing = await apiFetch(`${api}/releases/${rel.json.id}/assets?per_page=100`);
    const dup = (existing.json || []).find(a => a.name === name);
    if (dup) { await apiFetch(`${api}/releases/assets/${dup.id}`, { method: 'DELETE' }); console.log('删除旧资产 ' + name); }
    const res = await fetch(`${uploadUrl}?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/octet-stream', 'Content-Length': String(bytes.length) },
      body: bytes,
    });
    const text = await res.text();
    if (res.status >= 300) { console.error(`上传 ${name} 失败：${res.status} ${text.slice(0, 200)}`); process.exit(1); }
    console.log(`上传成功 ${name}  ${bytes.length} B  sha256=${sha.slice(0, 16)}…`);
  }
  console.log('全部完成 ✓  校验：');
  for (const name of files) {
    const url = `https://github.com/${owner}/${repoName}/releases/latest/download/${name}`;
    const res = await fetch(url, { method: 'HEAD', redirect: 'follow' });
    console.log(`  ${res.status}  ${url}`);
  }
}
main().catch(e => { console.error('失败：', e); process.exit(1); });