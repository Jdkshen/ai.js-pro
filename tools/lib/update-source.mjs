// tools/lib/update-source.mjs —— P2-4：更新源 URL 的唯一实现（客户端与脚本必须同一口径）。
//
// 与 App 侧 apps/app/src/main/java/com/jdkshen/aijspro/network/PluginDownloads.java 的候选链一一对应：
//   custom（用户自建源）/ builtin-release（加速前缀 + releases/latest/download）
//   / builtin-raw（加速前缀 + plugin-release 分支）/ builtin-direct（直连 raw）

export const CATALOG_FILE = 'plugins.json';
export const BUILTIN_RELEASE_BASE =
  'https://gh-proxy.com/https://github.com/Jdkshen/ai.js-pro/releases/latest/download/';
export const BUILTIN_RAW_PROXIED_BASE =
  'https://gh-proxy.com/https://raw.githubusercontent.com/Jdkshen/ai.js-pro/plugin-release/plugin-release/';
export const BUILTIN_RAW_DIRECT_BASE =
  'https://raw.githubusercontent.com/Jdkshen/ai.js-pro/plugin-release/plugin-release/';

/** 前缀 + 文件名（前缀无尾斜杠时补一个；与客户端 resolveUrl 的语义一致）。 */
export function joinUrl(prefix, file) {
  return prefix.endsWith('/') ? prefix + file : prefix + '/' + file;
}

/** 零配置回退链（顺序固定：release → raw(加速) → raw(直连)）。 */
export function builtinCandidates(file) {
  return [
    { source: 'builtin-release', url: joinUrl(BUILTIN_RELEASE_BASE, file) },
    { source: 'builtin-raw', url: joinUrl(BUILTIN_RAW_PROXIED_BASE, file) },
    { source: 'builtin-direct', url: joinUrl(BUILTIN_RAW_DIRECT_BASE, file) },
  ];
}

/** 自建源优先（脚本侧：显式给了就只用它）。 */
export function candidatesFor(file, customSource = '') {
  if (customSource && customSource.trim()) {
    return [{ source: 'custom', url: joinUrl(customSource.trim(), file) }];
  }
  return builtinCandidates(file);
}
/** 发布 base（owner/repo 参数化：脚本侧原本各自手写一遍）。 */
export function releaseBase(owner, repo) {
  return 'https://github.com/' + owner + '/' + repo + '/releases/latest/download/';
}

/** raw base（owner/repo/branch 参数化；direct=直连，proxied=走加速前缀）。 */
export function rawBase(owner, repo, branch, proxied = false) {
  const url = 'https://raw.githubusercontent.com/' + owner + '/' + repo + '/' + branch + '/plugin-release/';
  return proxied ? 'https://gh-proxy.com/' + url : url;
}