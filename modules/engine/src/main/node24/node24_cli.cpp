// Optional child-process CLI, using the same pinned libnode as the Node engine.
#include <cstdarg>
#include <cstdio>
#include <unistd.h>
// Android libnode sends native exceptions to logcat. A shell child must deliver
// those errors through its own stderr so callers receive the actual failure.
extern "C" int __android_log_print(int, const char*, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int written = vdprintf(STDERR_FILENO, format, args);
    va_end(args);
    write(STDERR_FILENO, "\n", 1);
    return written;
}
namespace node { int Start(int argc, char** argv); }
int main(int argc, char** argv) { return node::Start(argc, argv); }
