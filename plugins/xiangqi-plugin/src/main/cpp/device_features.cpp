#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jdkshen_xiangqi_plugin_NativeEngine_nativeSupportsDotprod(JNIEnv*, jclass) {
    // The kernel HWCAP is safe for the process across all cores.
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0 ? JNI_TRUE : JNI_FALSE;
}
