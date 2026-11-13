// task-135：排除清单的**合并 + 生存断言**（纯函数 + CLI）。
//
// 背景（真 bug）：build-slim-template.ps1 每次重建模板都用 :inrt release APK 的 lib/* 重算
// general/opencv/v8 ⇒ 只存在于宿主 APK 或其它 ABI 的条目（v7a 组、arm64 的 mlkit/ppocr）
// 会在每次重建后被**覆盖丢失**。根治 = 用一份持久化的 extra 清单做并集，并**断言它必然存活**。
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

/** 按叶子名分类（与 build-slim-template.ps1 的既有规则逐字一致）。 */
export function classify(fullName) {
  const leaf = fullName.split('/').pop();
  if (/^libopencv/.test(leaf) || leaf === 'libtbb.so') return 'opencv';
  if (/^libv8/.test(leaf) || /^libnode/.test(leaf)) return 'v8';
  return 'general';
}

/** 并集：generated（APK 推导）∪ extra（持久清单）；去重 + 排序。返回 {general, opencv, v8, mergedFromExtra}。 */
export function merge(generated, extra) {
  const out = { general: [], opencv: [], v8: [] };
  const seen = new Set();
  const add = (name, bucket) => {
    if (typeof name !== 'string' || name.length === 0) return;
    if (seen.has(name)) return;
    seen.add(name);
    out[bucket].push(name);
  };
  for (const bucket of ['general', 'opencv', 'v8']) {
    for (const name of generated[bucket] || []) add(name, bucket);
  }
  const mergedFromExtra = [];
  for (const bucket of ['general', 'opencv', 'v8']) {
    for (const name of extra[bucket] || []) {
      if (!seen.has(name)) { add(name, bucket); mergedFromExtra.push(name); }
    }
  }
  for (const bucket of ['general', 'opencv', 'v8']) out[bucket].sort();
  return { ...out, mergedFromExtra };
}

/** 硬断言：extra 里每一条都必须出现在合并结果里（重建后不许丢）。 */
export function assertExtraSurvives(merged, extra) {
  const all = new Set([...merged.general, ...merged.opencv, ...merged.v8]);
  const missing = [];
  for (const bucket of ['general', 'opencv', 'v8']) {
    for (const name of extra[bucket] || []) if (!all.has(name)) missing.push(bucket + ':' + name);
  }
  if (missing.length) throw new Error('extra 条目在合并后丢失：' + missing.join('、'));
  return true;
}

/** 从「APK 推导的 lib 清单」构造 generated 三桶（PS 侧用同一规则，这里是等价实现）。 */
export function generatedFromLibNames(libNames) {
  const buckets = { general: [], opencv: [], v8: [] };
  for (const name of libNames) buckets[classify(name)].push(name);
  for (const k of Object.keys(buckets)) buckets[k].sort();
  return buckets;
}

function argOf(argv, flag, fallback = '') {
  const i = argv.indexOf(flag);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const argv = process.argv.slice(2);
  const extraPath = argOf(argv, '--extra', 'tools/excluded-zip-files.extra.json');
  const outPath = argOf(argv, '--out');
  const extra = JSON.parse(fs.readFileSync(extraPath, 'utf8'));
  let generated;
  const libsArg = argOf(argv, '--libs');            // 逗号分隔的 lib/* 清单（PS 从 inrt APK 取）
  const generatedPath = argOf(argv, '--generated'); // 或者直接给一份 generated JSON
  if (generatedPath) generated = JSON.parse(fs.readFileSync(generatedPath, 'utf8'));
  else if (libsArg) generated = generatedFromLibNames(libsArg.split(',').filter(Boolean));
  else generated = { general: [], opencv: [], v8: [] };
  const merged = merge(generated, extra);
  assertExtraSurvives(merged, extra);
  const text = JSON.stringify({ general: merged.general, opencv: merged.opencv, v8: merged.v8 }, null, 4) + '\n';
  if (outPath) fs.writeFileSync(outPath, text);
  const fromExtra = merged.mergedFromExtra.length;
  console.log('[C-SLIM-MERGE] general=' + merged.general.length + ' opencv=' + merged.opencv.length +
    ' v8=' + merged.v8.length + ' 其中来自 extra=' + fromExtra + ' ⇒ extra 全部存活 ✓');
  if (!outPath) console.log(text.trim().slice(0, 400));
}