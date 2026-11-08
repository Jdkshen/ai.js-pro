/* dtprobe: minimal arm64 shared library used to exercise in-place DT_NEEDED injection. */
int dtprobe_ping(void) { return 42; }
__attribute__((constructor)) static void dtprobe_ctor(void) { }