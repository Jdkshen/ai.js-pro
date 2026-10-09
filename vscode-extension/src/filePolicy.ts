import * as path from 'path';

// Keep the built-in text editor extensions aligned with ExplorerFileItem on Android.
const TEXT_EXTENSIONS = new Set([
  '.js', '.auto', '.mjs', '.cjs', '.jsx', '.ts', '.tsx', '.java', '.kt', '.kts',
  '.xml', '.json', '.jsonc', '.txt', '.log', '.md', '.markdown', '.yaml', '.yml',
  '.toml', '.ini', '.properties', '.cfg', '.conf', '.gradle', '.sh', '.py', '.csv',
  '.css', '.scss', '.html', '.htm', '.svg',
]);
const TEXT_NAMES = new Set([
  '.gitignore', '.gitattributes', '.editorconfig', '.env',
  'dockerfile', 'makefile', 'license', 'readme', 'gradlew',
]);
const IMAGE_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.ico',
]);
const SCRIPT_EXTENSIONS = new Set(['.js', '.auto']);

export type DeviceFileKind = 'text' | 'image' | 'download';

export function deviceFileKind(devicePath: string): DeviceFileKind {
  const name = path.posix.basename(devicePath).toLowerCase();
  const extension = path.posix.extname(name);
  if (TEXT_NAMES.has(name) || TEXT_EXTENSIONS.has(extension)) return 'text';
  if (IMAGE_EXTENSIONS.has(extension)) return 'image';
  return 'download';
}

export function isRunnableDeviceFile(devicePath: string): boolean {
  return SCRIPT_EXTENSIONS.has(path.posix.extname(devicePath).toLowerCase());
}
