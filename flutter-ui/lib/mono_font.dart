// 等宽字体回退列表。
//
// Flutter 在 Android 上不会为 'monospace' 自动回退到系统中文字体：只要
// 对应字形缺失，中文就画成豆腐块（实测日志页整屏中文变成 `���`，而设备上的
// 字节是合法 UTF-8）。所以**每一处** fontFamily: 'monospace' 都必须带上
// fontFamilyFallback，否则中文显示不出来。
//
// 顺序：先系统无衬线，再几个常见的中文字体名，最后 Android 的兜底字体。
const monoFontFallback = <String>[
  'sans-serif',
  'Noto Sans CJK SC',
  'Source Han Sans SC',
  'Droid Sans Fallback',
];
