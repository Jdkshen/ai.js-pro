/* 极小 arm64-v8a 共享库：为 ToolkitElfNeeded 的「就地加 DT_NEEDED」成功路径做夹具。
 * 刻意不链接 libc（-nostdlib），这样 dynamic 表里没有 DT_NEEDED，补丁结果一眼可辨。 */
__attribute__((visibility("default"))) int dtprobe_ping(void) { return 42; }
__attribute__((visibility("default"))) const char *dtprobe_tag(void) { return "dtprobe-ok"; }
