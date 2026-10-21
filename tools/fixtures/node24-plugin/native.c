#include <jni.h>
JNIEXPORT jint JNICALL Java_org_aijsprobe_nodeplugin_FixturePlugin_nativeAdd(JNIEnv* env, jclass type, jint a, jint b) {
    return a + b;
}
