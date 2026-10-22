import * as fs from 'fs';
import * as path from 'path';

/**
 * 找出要推送/运行的 AI.js Pro 工程根目录。
 *
 * 常见情况：用户打开的是「包含一个工程文件夹的父目录」（例如 Downloads/xiangqi-draw，
 * 里面才是含 project.json 的 xiangqi-draw）。此时如果直接推父目录，手机会把工程再套一层，
 * project.json 不在解压根目录，设备端 ProjectLauncher 会报
 * 「工程无法运行：Attempt to invoke ... on a null object reference」。
 *
 * 规则：根目录有 project.json → 根目录；否则仅当恰好一个子目录有 project.json 时使用该子目录。
 */
export function findProjectRoot(workspaceRoot: string): string | undefined {
  if (fs.existsSync(path.join(workspaceRoot, 'project.json'))) {
    return workspaceRoot;
  }
  const candidates: string[] = [];
  try {
    for (const entry of fs.readdirSync(workspaceRoot, { withFileTypes: true })) {
      if (!entry.isDirectory() || entry.name.startsWith('.') || entry.name === 'node_modules') {
        continue;
      }
      const candidate = path.join(workspaceRoot, entry.name);
      if (fs.existsSync(path.join(candidate, 'project.json'))) {
        candidates.push(candidate);
      }
    }
  } catch {
    return undefined;
  }
  return candidates.length === 1 ? candidates[0] : undefined;
}
