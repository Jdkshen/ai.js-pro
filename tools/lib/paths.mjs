// tools/lib/paths.mjs —— P2-4：路径解析纯函数库（仓库根 / 相对路径 / 读 JSON）。
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/** 仓库根＝脚本运行目录（既有脚本都这么用；显式参数便于测试注入）。 */
export function repoRoot(cwd = process.cwd()) {
  return cwd;
}

/** root 下拼若干段（统一用 path.join，避免手写斜杠）。 */
export function under(root, ...parts) {
  return path.join(root, ...parts);
}

/** 相对 root 的展示路径（日志用；统一正斜杠便于跨平台对比）。 */
export function rel(root, target) {
  return path.relative(root, target).split(path.sep).join('/');
}

/** 读 JSON：容忍 UTF-8 BOM（原来各脚本各写一次 .replace(/^\uFEFF/)）。 */
export function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}

/** 确保某文件的父目录存在（原来各脚本各写一次 mkdirSync(path.dirname(...), {recursive:true})）。 */
export function ensureDirFor(file) {
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
}
