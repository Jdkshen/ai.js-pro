#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "native_frame_store.h"

extern "C" {
#include "quickjs.h"
}

namespace {

constexpr const char *kQuickJsExceptionClass = "com/stardust/autojs/engine/QuickJsException";

int64_t nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

struct TimerEntry {
    int64_t id = 0;
    JSValue callback = JS_UNDEFINED;
    bool repeat = false;
    int64_t intervalMs = 0;
    int64_t deadlineMs = 0;
    bool canceled = false;
};

struct EngineState {
    JavaVM *vm = nullptr;
    jobject host = nullptr;
    JSRuntime *runtime = nullptr;
    JSContext *context = nullptr;
    std::atomic<bool> interrupted{false};
    std::atomic<bool> exitRequested{false};
    NativeFrameStore frames;
    std::mutex timersMutex;
    std::condition_variable timersCv;
    std::unordered_map<int64_t, std::shared_ptr<TimerEntry>> timers;
    std::atomic<int64_t> nextTimerId{1};
};

std::string jsString(JSContext *context, JSValueConst value);

std::string scriptDirectory(const std::string &filename) {
    const size_t separator = filename.find_last_of("/\\");
    if (separator == std::string::npos) {
        return ".";
    }
    if (separator == 0) {
        return filename.substr(0, 1);
    }
    return filename.substr(0, separator);
}

bool installMainModuleGlobals(JSContext *context, const std::string &filename) {
    JSValue global = JS_GetGlobalObject(context);
    JSValue module = JS_NewObject(context);
    JSValue exports = JS_NewObject(context);
    if (JS_IsException(global) || JS_IsException(module) || JS_IsException(exports)) {
        JS_FreeValue(context, global);
        JS_FreeValue(context, module);
        JS_FreeValue(context, exports);
        return false;
    }

    int status = 0;
    status |= JS_SetPropertyStr(context, module, "id", JS_NewString(context, filename.c_str()));
    status |= JS_SetPropertyStr(context, module, "filename", JS_NewString(context, filename.c_str()));
    status |= JS_SetPropertyStr(context, module, "loaded", JS_NewBool(context, false));
    status |= JS_SetPropertyStr(context, module, "exports", JS_DupValue(context, exports));
    if (status < 0) {
        JS_FreeValue(context, global);
        JS_FreeValue(context, module);
        JS_FreeValue(context, exports);
        return false;
    }

    JSValue require = JS_GetPropertyStr(context, global, "require");
    if (JS_IsFunction(context, require)) {
        status |= JS_SetPropertyStr(context, require, "main", JS_DupValue(context, module));
        status |= JS_SetPropertyStr(context, module, "require", JS_DupValue(context, require));
    }
    JS_FreeValue(context, require);

    status |= JS_SetPropertyStr(context, global, "module", module);
    status |= JS_SetPropertyStr(context, global, "exports", exports);
    status |= JS_SetPropertyStr(context, global, "__filename",
                                JS_NewString(context, filename.c_str()));
    const std::string dirname = scriptDirectory(filename);
    status |= JS_SetPropertyStr(context, global, "__dirname",
                                JS_NewString(context, dirname.c_str()));
    JS_FreeValue(context, global);
    return status >= 0;
}

void markMainModuleLoaded(JSContext *context) {
    JSValue global = JS_GetGlobalObject(context);
    JSValue module = JS_GetPropertyStr(context, global, "module");
    if (JS_IsObject(module)) {
        JS_SetPropertyStr(context, module, "loaded", JS_NewBool(context, true));
    }
    JS_FreeValue(context, module);
    JS_FreeValue(context, global);
}

int64_t pendingTimerCount(EngineState *state) {
    std::lock_guard<std::mutex> lock(state->timersMutex);
    int64_t count = 0;
    for (const auto &entry : state->timers) {
        if (!entry.second->canceled) {
            ++count;
        }
    }
    return count;
}

JSValue registerTimer(JSContext *context, EngineState *state, int argc, JSValueConst *argv,
                      bool repeat) {
    if (argc < 2 || !JS_IsFunction(context, argv[0])) {
        return JS_ThrowTypeError(context, "setTimeout/setInterval requires a callback function");
    }
    int64_t millis = 0;
    if (JS_ToInt64(context, &millis, argv[1]) < 0) {
        return JS_EXCEPTION;
    }
    millis = std::max<int64_t>(0, millis);
    auto timer = std::make_shared<TimerEntry>();
    timer->id = state->nextTimerId.fetch_add(1);
    timer->callback = JS_DupValue(context, argv[0]);
    timer->repeat = repeat;
    timer->intervalMs = millis;
    timer->deadlineMs = nowMillis() + millis;
    {
        std::lock_guard<std::mutex> lock(state->timersMutex);
        state->timers.emplace(timer->id, timer);
    }
    state->timersCv.notify_all();
    return JS_NewInt64(context, timer->id);
}

// Dispatches all due timers on the current (JS) thread. Returns false when a
// timer callback throws, filling *error with the exception text.
bool dispatchDueTimers(JSContext *context, EngineState *state, std::string *error) {
    std::vector<std::shared_ptr<TimerEntry>> due;
    {
        std::lock_guard<std::mutex> lock(state->timersMutex);
        const int64_t now = nowMillis();
        for (const auto &entry : state->timers) {
            if (!entry.second->canceled && entry.second->deadlineMs <= now) {
                due.push_back(entry.second);
            }
        }
    }
    std::sort(due.begin(), due.end(), [](const auto &a, const auto &b) { return a->id < b->id; });
    for (const auto &timer : due) {
        {
            std::lock_guard<std::mutex> lock(state->timersMutex);
            if (timer->canceled) {
                continue;
            }
            if (timer->repeat) {
                timer->deadlineMs = nowMillis() + timer->intervalMs;
            } else {
                timer->canceled = true;
                state->timers.erase(timer->id);
            }
        }
        JSValue result = JS_Call(context, timer->callback, JS_UNDEFINED, 0, nullptr);
        if (JS_IsException(result)) {
            JSValue exception = JS_GetException(context);
            *error = jsString(context, exception);
            JS_FreeValue(context, exception);
            JS_FreeValue(context, result);
            if (timer->repeat) {
                std::lock_guard<std::mutex> lock(state->timersMutex);
                timer->canceled = true;
                state->timers.erase(timer->id);
            }
            JS_FreeValue(context, timer->callback);
            return false;
        }
        JS_FreeValue(context, result);
        JSContext *pendingContext = nullptr;
        int pendingResult;
        while ((pendingResult = JS_ExecutePendingJob(state->runtime, &pendingContext)) > 0) {
        }
        if (pendingResult < 0) {
            JSContext *errorContext = pendingContext == nullptr ? context : pendingContext;
            JSValue exception = JS_GetException(errorContext);
            *error = jsString(errorContext, exception);
            JS_FreeValue(errorContext, exception);
            if (timer->repeat) {
                std::lock_guard<std::mutex> lock(state->timersMutex);
                timer->canceled = true;
                state->timers.erase(timer->id);
            }
            JS_FreeValue(context, timer->callback);
            return false;
        }
        if (!timer->repeat) {
            JS_FreeValue(context, timer->callback);
        }
    }
    return true;
}

// Blocks until at least one timer is due or the engine is interrupted.
void waitForNextTimer(EngineState *state) {
    std::unique_lock<std::mutex> lock(state->timersMutex);
    while (!state->interrupted.load(std::memory_order_relaxed)) {
        const int64_t now = nowMillis();
        int64_t next = std::numeric_limits<int64_t>::max();
        for (const auto &entry : state->timers) {
            if (!entry.second->canceled) {
                next = std::min(next, entry.second->deadlineMs - now);
            }
        }
        if (next <= 0) {
            return;
        }
        const int64_t slice = std::min<int64_t>(next, 25);
        state->timersCv.wait_for(lock, std::chrono::milliseconds(slice));
    }
}

std::mutex gEnginesMutex;
std::unordered_map<jlong, std::shared_ptr<EngineState>> gEngines;
std::atomic<jlong> gNextHandle{1};

std::shared_ptr<EngineState> findEngine(jlong handle) {
    std::lock_guard<std::mutex> lock(gEnginesMutex);
    const auto it = gEngines.find(handle);
    return it == gEngines.end() ? nullptr : it->second;
}

std::string fromJavaString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codePoint = chars[i];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (codePoint <= 0x7F) {
            result.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else if (codePoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring toJavaString(JNIEnv *env, const std::string &value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) {
        return nullptr;
    }
    if (!value.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<const jbyte *>(value.data()));
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring utf8 = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, utf8));
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

JNIEnv *currentEnv(EngineState *state) {
    JNIEnv *env = nullptr;
    if (state->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

std::string jsString(JSContext *context, JSValueConst value) {
    const char *chars = JS_ToCString(context, value);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    JS_FreeCString(context, chars);
    return result;
}

std::string takeJavaException(JNIEnv *env) {
    jthrowable throwable = env->ExceptionOccurred();
    if (throwable == nullptr) {
        return {};
    }
    env->ExceptionClear();
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    jmethodID toStringMethod = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
    auto message = static_cast<jstring>(env->CallObjectMethod(throwable, toStringMethod));
    std::string result = fromJavaString(env, message);
    env->DeleteLocalRef(message);
    env->DeleteLocalRef(throwableClass);
    env->DeleteLocalRef(throwable);
    return result;
}

JSValue throwJavaException(JSContext *context, JNIEnv *env) {
    const std::string message = takeJavaException(env);
    return JS_ThrowInternalError(context, "%s", message.empty() ? "Java API bridge failed" : message.c_str());
}

std::string quickJsException(JSContext *context) {
    JSValue exception = JS_GetException(context);
    std::string message = jsString(context, exception);
    JSValue stack = JS_GetPropertyStr(context, exception, "stack");
    if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
        const std::string stackText = jsString(context, stack);
        if (!stackText.empty() && stackText != message) {
            message += "\n" + stackText;
        }
    }
    JS_FreeValue(context, stack);
    JS_FreeValue(context, exception);
    return message.empty() ? "QuickJS execution failed" : message;
}

void throwQuickJs(JNIEnv *env, const std::string &message) {
    jclass exceptionClass = env->FindClass(kQuickJsExceptionClass);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

int interruptHandler(JSRuntime *, void *opaque) {
    auto *state = static_cast<EngineState *>(opaque);
    return state->interrupted.load(std::memory_order_relaxed) ? 1 : 0;
}

JSValue nativeLog(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (env == nullptr) {
        return JS_ThrowInternalError(context, "JNI environment is unavailable");
    }
    int32_t level = 3;
    if (argc > 0) {
        JS_ToInt32(context, &level, argv[0]);
    }
    const std::string message = argc > 1 ? jsString(context, argv[1]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "console", "(ILjava/lang/String;)V");
    jstring javaMessage = toJavaString(env, message);
    env->CallVoidMethod(state->host, method, static_cast<jint>(level), javaMessage);
    env->DeleteLocalRef(javaMessage);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativePerformanceNow(JSContext *context, JSValueConst, int, JSValueConst *) {
    const double millis = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    return JS_NewFloat64(context, millis);
}

JSValue nativeToast(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string message = argc > 0 ? jsString(context, argv[0]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "toast", "(Ljava/lang/String;)V");
    jstring javaMessage = toJavaString(env, message);
    env->CallVoidMethod(state->host, method, javaMessage);
    env->DeleteLocalRef(javaMessage);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeSleep(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    int64_t millis = 0;
    if (argc > 0 && JS_ToInt64(context, &millis, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    millis = std::max<int64_t>(0, millis);
    while (millis > 0 && !state->interrupted.load(std::memory_order_relaxed)) {
        // Dispatch due timers while sleeping so setTimeout/setInterval (e.g. the
        // sensors polling loop) keep firing during synchronous sleep() calls.
        const int64_t slice = std::min<int64_t>(millis, 25);
        std::string timerError;
        if (!dispatchDueTimers(context, state, &timerError)) {
            const std::string message = timerError.empty() ? "Timer callback failed" : timerError;
            return JS_ThrowInternalError(context, "%s", message.c_str());
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(slice));
        millis -= slice;
    }
    if (state->interrupted.load(std::memory_order_relaxed)) {
        return JS_ThrowInternalError(context, "Script execution interrupted");
    }
    return JS_UNDEFINED;
}

bool readInt(JSContext *context, int argc, JSValueConst *argv, int index, int32_t *value) {
    return index < argc && JS_ToInt32(context, value, argv[index]) == 0;
}

JSValue callBooleanHost(JSContext *context, const char *methodName, const char *signature,
                        const jint *values, int count) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, signature);
    jboolean result = JNI_FALSE;
    switch (count) {
        case 2:
            result = env->CallBooleanMethod(state->host, method, values[0], values[1]);
            break;
        case 3:
            result = env->CallBooleanMethod(state->host, method, values[0], values[1], values[2]);
            break;
        case 5:
            result = env->CallBooleanMethod(state->host, method, values[0], values[1], values[2], values[3], values[4]);
            break;
        default:
            break;
    }
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeClick(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t values[2];
    if (!readInt(context, argc, argv, 0, &values[0]) || !readInt(context, argc, argv, 1, &values[1])) {
        return JS_ThrowTypeError(context, "click(x, y) requires two integers");
    }
    return callBooleanHost(context, "click", "(II)Z", values, 2);
}

JSValue nativePress(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t values[3];
    for (int i = 0; i < 3; ++i) {
        if (!readInt(context, argc, argv, i, &values[i])) {
            return JS_ThrowTypeError(context, "press(x, y, duration) requires three integers");
        }
    }
    return callBooleanHost(context, "press", "(III)Z", values, 3);
}

JSValue nativeLongClick(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t values[2];
    if (!readInt(context, argc, argv, 0, &values[0]) || !readInt(context, argc, argv, 1, &values[1])) {
        return JS_ThrowTypeError(context, "longClick(x, y) requires two integers");
    }
    return callBooleanHost(context, "longClick", "(II)Z", values, 2);
}

JSValue nativeSwipe(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t values[5];
    for (int i = 0; i < 5; ++i) {
        if (!readInt(context, argc, argv, i, &values[i])) {
            return JS_ThrowTypeError(context, "swipe(x1, y1, x2, y2, duration) requires five integers");
        }
    }
    return callBooleanHost(context, "swipe", "(IIIII)Z", values, 5);
}

JSValue nativeGlobalAction(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string action = argc > 0 ? jsString(context, argv[0]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "globalAction", "(Ljava/lang/String;)Z");
    jstring javaAction = toJavaString(env, action);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaAction);
    env->DeleteLocalRef(javaAction);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeSetClip(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string text = argc > 0 ? jsString(context, argv[0]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "setClip", "(Ljava/lang/String;)V");
    jstring javaText = toJavaString(env, text);
    env->CallVoidMethod(state->host, method, javaText);
    env->DeleteLocalRef(javaText);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callStringHost(JSContext *context, const char *methodName, const char *argument) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method;
    jstring result;
    if (argument == nullptr) {
        method = env->GetMethodID(hostClass, methodName, "()Ljava/lang/String;");
        result = static_cast<jstring>(env->CallObjectMethod(state->host, method));
    } else {
        method = env->GetMethodID(hostClass, methodName, "(Ljava/lang/String;)Ljava/lang/String;");
        jstring javaArgument = toJavaString(env, argument);
        result = static_cast<jstring>(env->CallObjectMethod(state->host, method, javaArgument));
        env->DeleteLocalRef(javaArgument);
    }
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue callIntHostNoArgs(JSContext *context, const char *methodName) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "()I");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jint result = env->CallIntMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt32(context, result);
}

JSValue callHostVoidNoArgs(JSContext *context, const char *methodName) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "()V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    env->CallVoidMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callHostVoidInt(JSContext *context, const char *methodName, int32_t value) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(I)V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    env->CallVoidMethod(state->host, method, static_cast<jint>(value));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callHostIntInt(JSContext *context, const char *methodName, int32_t value) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(I)I");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jint result = env->CallIntMethod(state->host, method, static_cast<jint>(value));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt32(context, result);
}

JSValue callHostBoolNoArgs(JSContext *context, const char *methodName) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "()Z");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue callHostBoolInt(JSContext *context, const char *methodName, int32_t value) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(I)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jint>(value));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue callHostStringInt(JSContext *context, const char *methodName, int32_t value) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(I)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jint>(value)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue callHostIntStringInt(JSContext *context, const char *methodName,
                             const std::string &first, int32_t second) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(Ljava/lang/String;I)I");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaFirst = toJavaString(env, first);
    const jint result = env->CallIntMethod(state->host, method, javaFirst,
            static_cast<jint>(second));
    env->DeleteLocalRef(javaFirst);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt32(context, result);
}

JSValue callHostBoolStringFloatBool(JSContext *context, const char *methodName,
                                    const std::string &path, float volume, bool looping) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(Ljava/lang/String;FFZ)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaPath = toJavaString(env, path);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaPath,
            static_cast<jfloat>(volume), looping ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue callHostLongIntStringStringStringString(JSContext *context, const char *methodName,
        int32_t type, const std::string &title, const std::string &content,
        const std::string &itemsJson, const std::string &extrasJson) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring jTitle = toJavaString(env, title);
    jstring jContent = toJavaString(env, content);
    jstring jItems = toJavaString(env, itemsJson);
    jstring jExtras = toJavaString(env, extrasJson);
    const jlong result = env->CallLongMethod(state->host, method, static_cast<jint>(type),
            jTitle, jContent, jItems, jExtras);
    env->DeleteLocalRef(jExtras);
    env->DeleteLocalRef(jItems);
    env->DeleteLocalRef(jContent);
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt64(context, static_cast<int64_t>(result));
}

JSValue callHostStringLong(JSContext *context, const char *methodName, int64_t value) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(J)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jlong>(value)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue callHostVoidIntString(JSContext *context, const char *methodName,
                              int32_t id, const std::string &configJson) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(ILjava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring json = toJavaString(env, configJson);
    env->CallVoidMethod(state->host, method, static_cast<jint>(id), json);
    env->DeleteLocalRef(json);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callHostVoidIntStringString(JSContext *context, const char *methodName,
                                    int32_t id, const std::string &first, const std::string &second) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(ILjava/lang/String;Ljava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring jFirst = toJavaString(env, first);
    jstring jSecond = toJavaString(env, second);
    env->CallVoidMethod(state->host, method, static_cast<jint>(id), jFirst, jSecond);
    env->DeleteLocalRef(jSecond);
    env->DeleteLocalRef(jFirst);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callHostStringIntString(JSContext *context, const char *methodName,
                                int32_t id, const std::string &name) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(ILjava/lang/String;)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring jName = toJavaString(env, name);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jint>(id), jName));
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue callHostStringIntStringString(JSContext *context, const char *methodName,
                                      int32_t id, const std::string &first, const std::string &second) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring jFirst = toJavaString(env, first);
    jstring jSecond = toJavaString(env, second);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jint>(id), jFirst, jSecond));
    env->DeleteLocalRef(jSecond);
    env->DeleteLocalRef(jFirst);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeGetClip(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "getClip", nullptr);
}

JSValue callHostBooleanString(JSContext *context, const char *methodName, const std::string &argument) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(Ljava/lang/String;)Z");
    jstring javaArgument = toJavaString(env, argument);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaArgument);
    env->DeleteLocalRef(javaArgument);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue callHostBooleanStringString(JSContext *context, const char *methodName,
                                    const std::string &first, const std::string &second) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
                                        "(Ljava/lang/String;Ljava/lang/String;)Z");
    jstring javaFirst = toJavaString(env, first);
    jstring javaSecond = toJavaString(env, second);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaFirst, javaSecond);
    env->DeleteLocalRef(javaSecond);
    env->DeleteLocalRef(javaFirst);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue callHostVoidStringString(JSContext *context, const char *methodName,
                                 const std::string &first, const std::string &second) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
                                        "(Ljava/lang/String;Ljava/lang/String;)V");
    jstring javaFirst = toJavaString(env, first);
    jstring javaSecond = toJavaString(env, second);
    env->CallVoidMethod(state->host, method, javaFirst, javaSecond);
    env->DeleteLocalRef(javaSecond);
    env->DeleteLocalRef(javaFirst);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

std::string requireStringArg(JSContext *context, int argc, JSValueConst *argv, int index) {
    return index < argc ? jsString(context, argv[index]) : std::string();
}

JSValue nativeFilesExists(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesExists", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesIsFile(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesIsFile", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesIsDir(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesIsDir", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesRead(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesRead", requireStringArg(context, argc, argv, 0).c_str());
}

JSValue nativeFilesWrite(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostVoidStringString(context, "filesWrite",
                                    requireStringArg(context, argc, argv, 0),
                                    requireStringArg(context, argc, argv, 1));
}

JSValue nativeFilesAppend(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostVoidStringString(context, "filesAppend",
                                    requireStringArg(context, argc, argv, 0),
                                    requireStringArg(context, argc, argv, 1));
}

JSValue nativeFilesCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesCreate", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesEnsureDir(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesEnsureDir", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesListDir(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesListDir", requireStringArg(context, argc, argv, 0).c_str());
}

JSValue nativeFilesRemove(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesRemove", requireStringArg(context, argc, argv, 0));
}

JSValue nativeFilesRename(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanStringString(context, "filesRename",
                                       requireStringArg(context, argc, argv, 0),
                                       requireStringArg(context, argc, argv, 1));
}

JSValue nativeFilesCopy(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanStringString(context, "filesCopy",
                                       requireStringArg(context, argc, argv, 0),
                                       requireStringArg(context, argc, argv, 1));
}

JSValue nativeFilesMove(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanStringString(context, "filesMove",
                                       requireStringArg(context, argc, argv, 0),
                                       requireStringArg(context, argc, argv, 1));
}

JSValue nativeFilesCwd(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "filesCwd", nullptr);
}

JSValue nativeMediaGetVolume(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "mediaGetVolume");
}

JSValue nativeMediaGetMaxVolume(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "mediaGetMaxVolume");
}

JSValue nativeMediaSetVolume(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t volume = 0;
    if (argc < 1 || JS_ToInt64(context, &volume, argv[0]) < 0) {
        volume = 0;
    }
    return callHostIntInt(context, "mediaSetVolume", static_cast<int32_t>(volume));
}

JSValue nativeMediaPlayMusic(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string path = requireStringArg(context, argc, argv, 0);
    double volume = 1.0;
    if (argc > 1) {
        JS_ToFloat64(context, &volume, argv[1]);
    }
    bool looping = false;
    if (argc > 2) {
        looping = JS_ToBool(context, argv[2]) != 0;
    }
    return callHostBoolStringFloatBool(context, "mediaPlayMusic", path,
            static_cast<float>(volume), looping);
}

JSValue nativeMediaStopMusic(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "mediaStopMusic");
}

JSValue nativeMediaPauseMusic(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "mediaPauseMusic");
}

JSValue nativeMediaResumeMusic(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "mediaResumeMusic");
}

JSValue nativeMediaIsMusicPlaying(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostBoolNoArgs(context, "mediaIsMusicPlaying");
}

JSValue nativeMediaMusicSeekTo(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t position = 0;
    if (argc < 1 || JS_ToInt64(context, &position, argv[0]) < 0) {
        position = 0;
    }
    return callHostVoidInt(context, "mediaMusicSeekTo", static_cast<int32_t>(position));
}

JSValue nativeMediaGetMusicDuration(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "mediaGetMusicDuration");
}

JSValue nativeMediaGetMusicCurrentPosition(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "mediaGetMusicCurrentPosition");
}

JSValue nativeMediaScanFile(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string path = requireStringArg(context, argc, argv, 0);
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "mediaScanFile", "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaPath = toJavaString(env, path);
    env->CallVoidMethod(state->host, method, javaPath);
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeSensorsRegister(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string name = requireStringArg(context, argc, argv, 0);
    int64_t delayMicros = 200000;
    if (argc > 1) {
        JS_ToInt64(context, &delayMicros, argv[1]);
    }
    return callHostIntStringInt(context, "sensorsRegister", name,
            static_cast<int32_t>(delayMicros));
}

JSValue nativeSensorsRead(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_NewStringLen(context, "", 0);
    }
    return callHostStringInt(context, "sensorsRead", static_cast<int32_t>(handle));
}

JSValue nativeSensorsUnregister(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_NewBool(context, false);
    }
    return callHostBoolInt(context, "sensorsUnregister", static_cast<int32_t>(handle));
}

JSValue nativeSensorsUnregisterAll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "sensorsUnregisterAll");
}

JSValue nativeSensorsList(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "sensorsList", nullptr);
}

JSValue nativeEventsObserve(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "eventsObserve",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeEventsPoll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "eventsPoll", nullptr);
}

JSValue nativeEventsSetTouchTimeout(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t timeout = 10;
    if (argc > 0) JS_ToInt32(context, &timeout, argv[0]);
    return callHostVoidInt(context, "eventsSetTouchTimeout", timeout);
}

JSValue nativeEventsStopAll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "eventsStopAll");
}

JSValue nativeDialogsShow(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t type = 0;
    if (argc < 1 || JS_ToInt64(context, &type, argv[0]) < 0) {
        type = 0;
    }
    const std::string title = requireStringArg(context, argc, argv, 1);
    const std::string content = requireStringArg(context, argc, argv, 2);
    const std::string itemsJson = requireStringArg(context, argc, argv, 3);
    const std::string extrasJson = requireStringArg(context, argc, argv, 4);
    return callHostLongIntStringStringStringString(context, "dialogsShow",
            static_cast<int32_t>(type), title, content, itemsJson, extrasJson);
}

JSValue nativeDialogsPoll(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 1 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_NewStringLen(context, "", 0);
    }
    return callHostStringLong(context, "dialogsPoll", id);
}

JSValue nativeFloatyCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string configJson = requireStringArg(context, argc, argv, 0);
    return callStringHost(context, "floatyCreate", configJson.c_str());
}

JSValue nativeFloatyUpdate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 2 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    const std::string configJson = requireStringArg(context, argc, argv, 1);
    return callHostVoidIntString(context, "floatyUpdate", static_cast<int32_t>(id), configJson);
}

JSValue nativeFloatyClose(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 1 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    return callHostVoidInt(context, "floatyClose", static_cast<int32_t>(id));
}

JSValue nativeFloatyCloseAll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "floatyCloseAll");
}

JSValue nativeFloatyViewGetText(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewGetText requires windowId, id");
    }
    const std::string id = jsString(context, argv[1]);
    return callHostStringIntString(context, "floatyViewGetText",
            static_cast<int32_t>(windowId), id);
}

JSValue nativeFloatyViewSetText(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 3 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewSetText requires windowId, id, text");
    }
    const std::string id = jsString(context, argv[1]);
    const std::string text = jsString(context, argv[2]);
    return callHostVoidIntStringString(context, "floatyViewSetText",
            static_cast<int32_t>(windowId), id, text);
}

JSValue nativeFloatyViewSetVisibility(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 3 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewSetVisibility requires windowId, id, visibility");
    }
    const std::string id = jsString(context, argv[1]);
    const std::string visibility = jsString(context, argv[2]);
    return callHostVoidIntStringString(context, "floatyViewSetVisibility",
            static_cast<int32_t>(windowId), id, visibility);
}

JSValue nativeFloatyViewClick(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 3 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewClick requires windowId, id, mode");
    }
    const std::string id = jsString(context, argv[1]);
    const std::string mode = jsString(context, argv[2]);
    return callHostVoidIntStringString(context, "floatyViewClick",
            static_cast<int32_t>(windowId), id, mode);
}

JSValue nativeFloatyViewPoll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "floatyViewPoll", nullptr);
}

JSValue nativeFloatySetAdjustable(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatySetAdjustable requires windowId, enabled");
    }
    const bool enabled = JS_ToBool(context, argv[1]) > 0;
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetAdjustable", "(IZ)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(windowId),
                        enabled ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeFloatyIsAdjustable(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyIsAdjustable requires windowId");
    }
    return callHostBoolInt(context, "floatyIsAdjustable", static_cast<int32_t>(windowId));
}

JSValue nativeFloatyGetX(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetX requires windowId");
    }
    return callHostIntInt(context, "floatyGetX", static_cast<int32_t>(windowId));
}

JSValue nativeFloatyGetY(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetY requires windowId");
    }
    return callHostIntInt(context, "floatyGetY", static_cast<int32_t>(windowId));
}

JSValue nativeFloatyViewTouch(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewTouch requires windowId, id");
    }
    const std::string id = jsString(context, argv[1]);
    return callHostVoidIntString(context, "floatyViewTouch",
            static_cast<int32_t>(windowId), id);
}

JSValue nativeFloatyViewKey(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewKey requires windowId, id");
    }
    const std::string id = jsString(context, argv[1]);
    return callHostVoidIntString(context, "floatyViewKey",
            static_cast<int32_t>(windowId), id);
}

JSValue nativeFloatySetWindowFocusable(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatySetWindowFocusable requires windowId, focusable");
    }
    const bool focusable = JS_ToBool(context, argv[1]) > 0;
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetWindowFocusable", "(IZ)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(windowId),
                        focusable ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeFloatyViewRequestFocus(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewRequestFocus requires windowId, id");
    }
    const std::string id = jsString(context, argv[1]);
    return callHostVoidIntString(context, "floatyViewRequestFocus",
            static_cast<int32_t>(windowId), id);
}

// Rhino-style exit(): records an exit request; the JS layer then throws a
// sentinel error that evaluate() converts into a normal completion.
JSValue nativeExitSelf(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    state->exitRequested.store(true, std::memory_order_relaxed);
    return JS_UNDEFINED;
}

JSValue nativeUiInflate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string xml = requireStringArg(context, argc, argv, 0);
    return callStringHost(context, "uiInflate", xml.c_str());
}

JSValue nativeUiClose(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "uiClose");
}

JSValue nativeUiSetConfig(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t viewId = 0;
    if (argc < 3 || JS_ToInt64(context, &viewId, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    const std::string id = requireStringArg(context, argc, argv, 1);
    const std::string configJson = requireStringArg(context, argc, argv, 2);
    return callHostVoidIntStringString(context, "uiSetConfig",
            static_cast<int32_t>(viewId), id, configJson);
}

JSValue nativeUiGetText(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t viewId = 0;
    if (argc < 2 || JS_ToInt64(context, &viewId, argv[0]) < 0) {
        return JS_NewStringLen(context, "", 0);
    }
    const std::string id = requireStringArg(context, argc, argv, 1);
    return callHostStringIntString(context, "uiGetText", static_cast<int32_t>(viewId), id);
}

JSValue nativeUiSetClickListener(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t viewId = 0;
    if (argc < 2 || JS_ToInt64(context, &viewId, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    const std::string id = requireStringArg(context, argc, argv, 1);
    return callHostVoidIntString(context, "uiSetClickListener", static_cast<int32_t>(viewId), id);
}

JSValue nativeUiSetDataSource(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t viewId = 0;
    if (argc < 3 || JS_ToInt64(context, &viewId, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    const std::string id = requireStringArg(context, argc, argv, 1);
    const std::string dataJson = requireStringArg(context, argc, argv, 2);
    return callHostVoidIntStringString(context, "uiSetDataSource",
            static_cast<int32_t>(viewId), id, dataJson);
}

JSValue nativeUiPollEvent(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "uiPollEvent", nullptr);
}

JSValue nativeUiGetAttr(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t viewId = 0;
    if (argc < 3 || JS_ToInt64(context, &viewId, argv[0]) < 0) {
        return JS_NewStringLen(context, "", 0);
    }
    const std::string id = requireStringArg(context, argc, argv, 1);
    const std::string name = requireStringArg(context, argc, argv, 2);
    return callHostStringIntStringString(context, "uiGetAttr",
            static_cast<int32_t>(viewId), id, name);
}

JSValue nativeFilesGetSdcardPath(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "filesGetSdcardPath", nullptr);
}

JSValue nativeFilesPath(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "resolvePath", requireStringArg(context, argc, argv, 0).c_str());
}

JSValue nativeSetTimeout(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    return registerTimer(context, state, argc, argv, false);
}

JSValue nativeSetInterval(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    return registerTimer(context, state, argc, argv, true);
}

JSValue nativeClearTimer(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 1 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "clearTimeout/clearInterval requires a timer id");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::lock_guard<std::mutex> lock(state->timersMutex);
    const auto it = state->timers.find(id);
    if (it == state->timers.end() || it->second->canceled) {
        return JS_NewBool(context, false);
    }
    it->second->canceled = true;
    JSValue callback = it->second->callback;
    state->timers.erase(it);
    JS_FreeValue(context, callback);
    return JS_NewBool(context, true);
}

JSValue nativeHttpRequest(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 2) {
        return JS_ThrowTypeError(context, "http request requires method and url");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string method = jsString(context, argv[0]);
    const std::string url = jsString(context, argv[1]);
    const std::string headersJson = requireStringArg(context, argc, argv, 2);
    const std::string body = requireStringArg(context, argc, argv, 3);
    const std::string contentType = requireStringArg(context, argc, argv, 4);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "httpRequest",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)"
            "Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    jstring javaUrl = toJavaString(env, url);
    jstring javaHeaders = toJavaString(env, headersJson);
    jstring javaBody = toJavaString(env, body);
    jstring javaContentType = toJavaString(env, contentType);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId,
            javaMethod, javaUrl, javaHeaders, javaBody, javaContentType));
    env->DeleteLocalRef(javaContentType);
    env->DeleteLocalRef(javaBody);
    env->DeleteLocalRef(javaHeaders);
    env->DeleteLocalRef(javaUrl);
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeDrawCreate(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "drawCreate", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeDrawUpdate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string detections = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string stats = argc > 1 ? jsString(context, argv[1]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "drawUpdate",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    jstring javaDetections = toJavaString(env, detections);
    jstring javaStats = toJavaString(env, stats);
    env->CallVoidMethod(state->host, method, javaDetections, javaStats);
    env->DeleteLocalRef(javaStats);
    env->DeleteLocalRef(javaDetections);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeDrawClose(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "drawClose", "()V");
    env->CallVoidMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeForegroundInfo(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string kind = argc > 0 ? jsString(context, argv[0]) : "package";
    return callStringHost(context, "getForegroundInfo", kind == "activity" ? "activity" : "package");
}

JSValue nativeAutoCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string method = argc > 0 ? jsString(context, argv[0]) : std::string();
    int32_t value = 0;
    if (argc > 1 && JS_ToInt32(context, &value, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "auto value must be an integer");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "autoCall", "(Ljava/lang/String;I)Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId, javaMethod, value));
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeSetScreenMetrics(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t width = 0;
    int32_t height = 0;
    if (argc < 2 || JS_ToInt32(context, &width, argv[0]) < 0 || JS_ToInt32(context, &height, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "setScreenMetrics(width, height) requires two integers");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "setScreenMetrics", "(II)V");
    env->CallVoidMethod(state->host, method, width, height);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_UNDEFINED;
}

// 控件选择器：Java 侧持句柄，JS 侧只拿到 long，不暴露对象。
JSValue nativeSelectorCreate(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "selectorCreate", "()J");
    const jlong handle = env->CallLongMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt64(context, handle);
}

JSValue nativeAutomatorCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 2) {
        return JS_ThrowTypeError(context, "\u63a7\u4ef6\u8c03\u7528\u9700\u8981\u53e5\u67c4\u548c\u65b9\u6cd5\u540d");
    }
    int64_t handle = 0;
    if (JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "\u63a7\u4ef6\u53e5\u67c4\u5fc5\u987b\u662f\u6570\u5b57");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string name = jsString(context, argv[1]);
    const std::string arguments = argc > 2 ? jsString(context, argv[2]) : std::string("[]");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "automatorCall",
                                        "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaName = toJavaString(env, name);
    jstring javaArguments = toJavaString(env, arguments);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
                                                             static_cast<jlong>(handle), javaName, javaArguments));
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaArguments);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue frameInfo(JSContext *context, EngineState *state, int64_t handle) {
    const auto frame = state->frames.get(handle);
    NativeFrameInfo nativeInfo;
    if (frame == nullptr) {
        return JS_ThrowInternalError(context, "NativeFrame is unavailable");
    }
    if (!state->frames.getInfo(handle, &nativeInfo)) {
        nativeInfo = {frame->cols, frame->rows, frame->cols, frame->rows};
    }
    JSValue info = JS_NewObject(context);
    JS_SetPropertyStr(context, info, "id", JS_NewInt64(context, handle));
    JS_SetPropertyStr(context, info, "width", JS_NewInt32(context, nativeInfo.logicalWidth));
    JS_SetPropertyStr(context, info, "height", JS_NewInt32(context, nativeInfo.logicalHeight));
    JS_SetPropertyStr(context, info, "pixelWidth", JS_NewInt32(context, nativeInfo.pixelWidth));
    JS_SetPropertyStr(context, info, "pixelHeight", JS_NewInt32(context, nativeInfo.pixelHeight));
    return info;
}

JSValue pointValue(JSContext *context, const NativeFramePoint &point, bool includeSimilarity) {
    JSValue value = JS_NewObject(context);
    JS_SetPropertyStr(context, value, "x", JS_NewInt32(context, point.x));
    JS_SetPropertyStr(context, value, "y", JS_NewInt32(context, point.y));
    if (includeSimilarity) {
        JS_SetPropertyStr(context, value, "similarity", JS_NewFloat64(context, point.similarity));
    }
    return value;
}

JSValue nativeRequestScreenCapture(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t orientation = 0;
    if (argc > 0 && JS_ToInt32(context, &orientation, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "requestScreenCapture", "(I)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, orientation);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeCaptureFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t targetShortEdge = 0;
    if (argc > 0 && JS_ToInt32(context, &targetShortEdge, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const bool fresh = argc > 1 && JS_ToBool(context, argv[1]) > 0;
    int32_t timeoutMillis = 100;
    if (argc > 2 && JS_ToInt32(context, &timeoutMillis, argv[2]) < 0) {
        return JS_EXCEPTION;
    }
    targetShortEdge = std::max(0, std::min(4096, targetShortEdge));
    timeoutMillis = std::max(0, std::min(5000, timeoutMillis));
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "captureScreenNative", "(IZI)J");
    const jlong handle = env->CallLongMethod(state->host, method, targetShortEdge,
                                             fresh ? JNI_TRUE : JNI_FALSE, timeoutMillis);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return frameInfo(context, state, handle);
}

bool base64Decode(const std::string &input, std::vector<uint8_t> *output) {
    static const char table[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::vector<uint8_t> decoded;
    decoded.reserve(input.size() * 3 / 4);
    int value = 0;
    int bits = 0;
    for (char c : input) {
        if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') {
            continue;
        }
        const char *found = strchr(table, c);
        if (found == nullptr) {
            return false;
        }
        value = (value << 6) | static_cast<int>(found - table);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            decoded.push_back(static_cast<uint8_t>((value >> bits) & 0xFF));
        }
    }
    *output = std::move(decoded);
    return !output->empty();
}

JSValue nativeFrameFromBase64(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string encoded = requireStringArg(context, argc, argv, 0);
    std::vector<uint8_t> bytes;
    if (!base64Decode(encoded, &bytes)) {
        return JS_ThrowInternalError(context, "Unable to decode base64 image data");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t handle = state->frames.fromEncoded(bytes, &error);
    return handle == 0 ? JS_ThrowInternalError(context, "%s", error.c_str())
                       : frameInfo(context, state, handle);
}

JSValue nativeFrameConcat(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t firstHandle = 0;
    int64_t secondHandle = 0;
    int32_t direction = 0;
    if (argc < 2 || JS_ToInt64(context, &firstHandle, argv[0]) < 0 ||
        JS_ToInt64(context, &secondHandle, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "images.concat(frame1, frame2, direction) has invalid arguments");
    }
    if (argc > 2) {
        JS_ToInt32(context, &direction, argv[2]);
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.concat(firstHandle, secondHandle, direction, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeReadFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "images.read(path) requires a path");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string path = jsString(context, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    if (path.rfind("asset://", 0) == 0) {
        jmethodID method = env->GetMethodID(hostClass, "readImageAsset", "(Ljava/lang/String;)[B");
        jstring javaPath = toJavaString(env, path);
        auto encodedArray = static_cast<jbyteArray>(
                env->CallObjectMethod(state->host, method, javaPath));
        env->DeleteLocalRef(javaPath);
        env->DeleteLocalRef(hostClass);
        if (env->ExceptionCheck()) {
            return throwJavaException(context, env);
        }
        if (encodedArray == nullptr) {
            return JS_ThrowInternalError(context, "Unable to read bundled image: %s", path.c_str());
        }
        const jsize length = env->GetArrayLength(encodedArray);
        std::vector<uint8_t> encoded(static_cast<size_t>(length));
        if (length > 0) {
            env->GetByteArrayRegion(encodedArray, 0, length,
                                    reinterpret_cast<jbyte *>(encoded.data()));
        }
        env->DeleteLocalRef(encodedArray);
        std::string error;
        const int64_t handle = state->frames.fromEncoded(encoded, &error);
        if (handle == 0) {
            return JS_ThrowInternalError(context, "%s: %s", error.c_str(), path.c_str());
        }
        return frameInfo(context, state, handle);
    }
    jmethodID method = env->GetMethodID(hostClass, "resolvePath", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring javaPath = toJavaString(env, path);
    auto resolvedPath = static_cast<jstring>(env->CallObjectMethod(state->host, method, javaPath));
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string resolved = fromJavaString(env, resolvedPath);
    env->DeleteLocalRef(resolvedPath);
    std::string error;
    const int64_t handle = state->frames.load(resolved, &error);
    if (handle == 0) {
        return JS_ThrowInternalError(context, "%s", error.c_str());
    }
    return frameInfo(context, state, handle);
}

JSValue nativeCopyFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.copy(frame) requires a NativeFrame");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.copy(handle, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeClipFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t x = 0, y = 0, width = 0, height = 0;
    if (argc < 5 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &x, argv[1]) < 0 || JS_ToInt32(context, &y, argv[2]) < 0 ||
        JS_ToInt32(context, &width, argv[3]) < 0 || JS_ToInt32(context, &height, argv[4]) < 0) {
        return JS_ThrowTypeError(context, "images.clip(frame, x, y, width, height) has invalid arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.clip(handle, x, y, width, height, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeResizeFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t width = 0, height = 0, interpolation = 1;
    if (argc < 4 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &width, argv[1]) < 0 ||
        JS_ToInt32(context, &height, argv[2]) < 0 ||
        JS_ToInt32(context, &interpolation, argv[3]) < 0) {
        return JS_ThrowTypeError(context, "images.resize(frame, size, interpolation) has invalid arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.resize(handle, width, height, interpolation, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeGrayscaleFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.grayscale(frame) requires a NativeFrame");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.grayscale(handle, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeCvtColorFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.cvtColor(frame, code) requires a NativeFrame and code");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.cvtColor(handle, jsString(context, argv[1]), &error);
    return result == 0 ? JS_ThrowTypeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeRotateFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t angle = 0;
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &angle, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "images.rotate(frame, angle) requires a NativeFrame and angle (90/180/270)");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.rotate(handle, angle, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeThresholdFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    double thresh = 128.0, maxValue = 255.0;
    int32_t type = 0; // THRESH_BINARY
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.threshold(frame, thresh, maxValue, type) requires a NativeFrame");
    }
    if (argc > 1) JS_ToFloat64(context, &thresh, argv[1]);
    if (argc > 2) JS_ToFloat64(context, &maxValue, argv[2]);
    if (argc > 3) JS_ToInt32(context, &type, argv[3]);
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.threshold(handle, thresh, maxValue, type, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeBlurFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t ksize = 5;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.blur(frame, ksize) requires a NativeFrame");
    }
    if (argc > 1) JS_ToInt32(context, &ksize, argv[1]);
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::string error;
    const int64_t result = state->frames.blur(handle, ksize, &error);
    return result == 0 ? JS_ThrowRangeError(context, "%s", error.c_str())
                       : frameInfo(context, state, result);
}

JSValue nativeSaveFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t quality = 100;
    if (argc < 4 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &quality, argv[3]) < 0) {
        return JS_ThrowTypeError(context, "images.save(frame, path, format, quality) has invalid arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string path = jsString(context, argv[1]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "resolvePath", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring javaPath = toJavaString(env, path);
    auto resolvedPath = static_cast<jstring>(env->CallObjectMethod(state->host, method, javaPath));
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string resolved = fromJavaString(env, resolvedPath);
    env->DeleteLocalRef(resolvedPath);
    std::string error;
    if (!state->frames.save(handle, resolved, jsString(context, argv[2]), quality, &error)) {
        return JS_ThrowInternalError(context, "%s", error.c_str());
    }
    return JS_TRUE;
}

JSValue nativeCompressFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t quality = 100;
    if (argc < 3 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &quality, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "images.compress(frame, format, quality) has invalid arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::vector<uint8_t> bytes;
    std::string error;
    if (!state->frames.compress(handle, jsString(context, argv[1]), quality, &bytes, &error)) {
        return JS_ThrowInternalError(context, "%s", error.c_str());
    }
    return JS_NewArrayBufferCopy(context, bytes.data(), bytes.size());
}

JSValue nativeReleaseFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "NativeFrame handle is required");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    return JS_NewBool(context, state->frames.release(handle));
}

JSValue nativeFrameStats(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    NativeFrameStore::Stats s = state->frames.stats();
    JSValue obj = JS_NewObject(context);
    JS_SetPropertyStr(context, obj, "activeHandles", JS_NewInt64(context, s.activeHandles));
    JS_SetPropertyStr(context, obj, "activeFrames", JS_NewInt32(context, s.activeFrames));
    JS_SetPropertyStr(context, obj, "poolCount", JS_NewInt32(context, s.poolCount));
    JS_SetPropertyStr(context, obj, "poolBytes", JS_NewInt64(context, s.poolBytes));
    JS_SetPropertyStr(context, obj, "createCount", JS_NewInt64(context, s.createCount));
    JS_SetPropertyStr(context, obj, "reuseCount", JS_NewInt64(context, s.reuseCount));
    JS_SetPropertyStr(context, obj, "rejectCount", JS_NewInt64(context, s.rejectCount));
    return obj;
}

JSValue nativeFramePixel(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t x = 0;
    int32_t y = 0;
    if (argc < 3 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &x, argv[1]) < 0 || JS_ToInt32(context, &y, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "images.pixel(frame, x, y) requires integer coordinates");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    uint32_t color = 0;
    if (!state->frames.pixel(handle, x, y, &color)) {
        return JS_ThrowRangeError(context, "Pixel is outside the frame or the frame was recycled");
    }
    return JS_NewUint32(context, color);
}

JSValue nativeFindColor(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t color = 0;
    int32_t threshold = 4;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    if (argc < 7 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &color, argv[1]) < 0 || JS_ToInt32(context, &threshold, argv[2]) < 0 ||
        JS_ToInt32(context, &x, argv[3]) < 0 || JS_ToInt32(context, &y, argv[4]) < 0 ||
        JS_ToInt32(context, &width, argv[5]) < 0 || JS_ToInt32(context, &height, argv[6]) < 0) {
        return JS_ThrowTypeError(context, "Invalid images.findColor arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    NativeFramePoint point;
    if (!state->frames.findColor(handle, static_cast<uint32_t>(color), threshold,
                                 x, y, width, height, &point)) {
        return JS_NULL;
    }
    return pointValue(context, point, false);
}

JSValue nativeFindMultiColors(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t firstColor = 0, threshold = 4, x = 0, y = 0, width = 0, height = 0;
    if (argc < 8 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &firstColor, argv[1]) < 0 ||
        !JS_IsArray(context, argv[2]) || JS_ToInt32(context, &threshold, argv[3]) < 0 ||
        JS_ToInt32(context, &x, argv[4]) < 0 || JS_ToInt32(context, &y, argv[5]) < 0 ||
        JS_ToInt32(context, &width, argv[6]) < 0 || JS_ToInt32(context, &height, argv[7]) < 0) {
        return JS_ThrowTypeError(context, "images.findMultiColors has invalid arguments");
    }
    JSValue lengthValue = JS_GetPropertyStr(context, argv[2], "length");
    uint32_t length = 0;
    if (JS_ToUint32(context, &length, lengthValue) < 0) {
        JS_FreeValue(context, lengthValue);
        return JS_EXCEPTION;
    }
    JS_FreeValue(context, lengthValue);
    if (length > 4096) {
        return JS_ThrowRangeError(context, "findMultiColors supports at most 4096 offset colors");
    }
    std::vector<NativeFrameColorOffset> offsets;
    offsets.reserve(length);
    for (uint32_t index = 0; index < length; ++index) {
        JSValue item = JS_GetPropertyUint32(context, argv[2], index);
        if (!JS_IsArray(context, item)) {
            JS_FreeValue(context, item);
            return JS_ThrowTypeError(context, "Each multi-color entry must be [dx, dy, color]");
        }
        JSValue dxValue = JS_GetPropertyUint32(context, item, 0);
        JSValue dyValue = JS_GetPropertyUint32(context, item, 1);
        JSValue colorValue = JS_GetPropertyUint32(context, item, 2);
        NativeFrameColorOffset offset;
        int32_t color = 0;
        const bool valid = JS_ToInt32(context, &offset.x, dxValue) >= 0 &&
                           JS_ToInt32(context, &offset.y, dyValue) >= 0 &&
                           JS_ToInt32(context, &color, colorValue) >= 0;
        JS_FreeValue(context, dxValue);
        JS_FreeValue(context, dyValue);
        JS_FreeValue(context, colorValue);
        JS_FreeValue(context, item);
        if (!valid) return JS_EXCEPTION;
        offset.argb = static_cast<uint32_t>(color);
        offsets.push_back(offset);
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    NativeFramePoint point;
    if (!state->frames.findMultiColors(handle, static_cast<uint32_t>(firstColor), offsets,
                                       threshold, x, y, width, height, &point)) {
        return JS_NULL;
    }
    return pointValue(context, point, false);
}

JSValue nativeFindImage(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t source = 0;
    int64_t templ = 0;
    double threshold = 0.9;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    if (argc < 7 || JS_ToInt64(context, &source, argv[0]) < 0 ||
        JS_ToInt64(context, &templ, argv[1]) < 0 || JS_ToFloat64(context, &threshold, argv[2]) < 0 ||
        JS_ToInt32(context, &x, argv[3]) < 0 || JS_ToInt32(context, &y, argv[4]) < 0 ||
        JS_ToInt32(context, &width, argv[5]) < 0 || JS_ToInt32(context, &height, argv[6]) < 0) {
        return JS_ThrowTypeError(context, "Invalid images.findImage arguments");
    }
    if (!std::isfinite(threshold)) {
        return JS_ThrowTypeError(context, "threshold must be a finite number");
    }
    if (threshold < 0.0 || threshold > 1.0) {
        return JS_ThrowRangeError(context, "threshold must be between 0 and 1");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    NativeFramePoint point;
    std::string error;
    if (!state->frames.findImage(source, templ, threshold, x, y, width, height, &point, &error)) {
        if (!error.empty()) {
            return JS_ThrowRangeError(context, "%s", error.c_str());
        }
        return JS_NULL;
    }
    return pointValue(context, point, true);
}

JSValue nativeMatchTemplate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t source = 0, templ = 0;
    double threshold = 0.9;
    int32_t maxMatches = 5, x = 0, y = 0, width = 0, height = 0;
    if (argc < 8 || JS_ToInt64(context, &source, argv[0]) < 0 ||
        JS_ToInt64(context, &templ, argv[1]) < 0 ||
        JS_ToFloat64(context, &threshold, argv[2]) < 0 ||
        JS_ToInt32(context, &maxMatches, argv[3]) < 0 ||
        JS_ToInt32(context, &x, argv[4]) < 0 || JS_ToInt32(context, &y, argv[5]) < 0 ||
        JS_ToInt32(context, &width, argv[6]) < 0 || JS_ToInt32(context, &height, argv[7]) < 0) {
        return JS_ThrowTypeError(context, "images.matchTemplate has invalid arguments");
    }
    if (!std::isfinite(threshold)) {
        return JS_ThrowTypeError(context, "threshold must be a finite number");
    }
    if (threshold < 0.0 || threshold > 1.0) {
        return JS_ThrowRangeError(context, "threshold must be between 0 and 1");
    }
    if (maxMatches < 1 || maxMatches > 1000) {
        return JS_ThrowRangeError(context, "max must be between 1 and 1000");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::vector<NativeFramePoint> matches;
    std::string error;
    if (!state->frames.matchTemplate(source, templ, threshold, maxMatches,
                                     x, y, width, height, &matches, &error)) {
        return JS_ThrowRangeError(context, "%s", error.c_str());
    }
    JSValue values = JS_NewArray(context);
    for (uint32_t index = 0; index < matches.size(); ++index) {
        JS_SetPropertyUint32(context, values, index, pointValue(context, matches[index], true));
    }
    return values;
}

JSValue nativeYoloIsAvailable(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string backend = argc > 0 ? jsString(context, argv[0]) : "opencv";
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "isYoloAvailable", "(Ljava/lang/String;)Z");
    jstring javaBackend = toJavaString(env, backend);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaBackend);
    env->DeleteLocalRef(javaBackend);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeYoloVersion(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string backend = argc > 0 ? jsString(context, argv[0]) : "opencv";
    return callStringHost(context, "getYoloVersion", backend.c_str());
}

JSValue nativeYoloUnavailableReason(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string backend = argc > 0 ? jsString(context, argv[0]) : "opencv";
    return callStringHost(context, "getYoloUnavailableReason", backend.c_str());
}

JSValue nativeYoloLoad(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 7) {
        return JS_ThrowTypeError(context, "yolo.load requires backend, model, param, bin, inputWidth, inputHeight, and threads");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string backend = jsString(context, argv[0]);
    const std::string model = jsString(context, argv[1]);
    const std::string param = jsString(context, argv[2]);
    const std::string bin = jsString(context, argv[3]);
    int32_t inputWidth = 320;
    int32_t inputHeight = 320;
    int32_t threads = 4;
    if (JS_ToInt32(context, &inputWidth, argv[4]) < 0 ||
        JS_ToInt32(context, &inputHeight, argv[5]) < 0 ||
        JS_ToInt32(context, &threads, argv[6]) < 0) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "loadYolo",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;III)J");
    jstring javaBackend = toJavaString(env, backend);
    jstring javaModel = toJavaString(env, model);
    jstring javaParam = toJavaString(env, param);
    jstring javaBin = toJavaString(env, bin);
    const jlong handle = env->CallLongMethod(state->host, method, javaBackend, javaModel,
            javaParam, javaBin, inputWidth, inputHeight, threads);
    env->DeleteLocalRef(javaBin);
    env->DeleteLocalRef(javaParam);
    env->DeleteLocalRef(javaModel);
    env->DeleteLocalRef(javaBackend);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, handle);
}

JSValue nativeYoloReadLabels(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "YOLO labels path is required");
    }
    const std::string path = jsString(context, argv[0]);
    return callStringHost(context, "readYoloLabels", path.c_str());
}

JSValue nativeYoloClose(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t detectorHandle = 0;
    if (argc < 1 || JS_ToInt64(context, &detectorHandle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "YOLO detector handle is required");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "closeYolo", "(J)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, detectorHandle);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeYoloDetect(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t detectorHandle = 0;
    int64_t frameHandle = 0;
    double confidence = 0.25;
    double nmsThreshold = 0.45;
    int32_t regionX = 0;
    int32_t regionY = 0;
    int32_t regionWidth = 0;
    int32_t regionHeight = 0;
    if (argc < 8 || JS_ToInt64(context, &detectorHandle, argv[0]) < 0 ||
        JS_ToInt64(context, &frameHandle, argv[1]) < 0 ||
        JS_ToFloat64(context, &confidence, argv[2]) < 0 ||
        JS_ToFloat64(context, &nmsThreshold, argv[3]) < 0 ||
        JS_ToInt32(context, &regionX, argv[4]) < 0 ||
        JS_ToInt32(context, &regionY, argv[5]) < 0 ||
        JS_ToInt32(context, &regionWidth, argv[6]) < 0 ||
        JS_ToInt32(context, &regionHeight, argv[7]) < 0) {
        return JS_ThrowTypeError(context, "Invalid YOLO detect arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    const auto frame = state->frames.get(frameHandle);
    if (frame == nullptr || frame->empty() || frame->type() != CV_8UC4) {
        return JS_ThrowTypeError(context, "YOLO requires a live RGBA NativeFrame");
    }

    JNIEnv *env = currentEnv(state);
    const jlong capacity = static_cast<jlong>(frame->dataend - frame->data);
    jobject buffer = env->NewDirectByteBuffer(frame->data, capacity);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "detectYolo",
            "(JLjava/nio/ByteBuffer;IIIIIIIFF)[F");
    auto packed = static_cast<jfloatArray>(env->CallObjectMethod(state->host, method,
            detectorHandle, buffer, frame->cols, frame->rows,
            static_cast<jint>(frame->step[0]),
            regionX, regionY, regionWidth, regionHeight,
            static_cast<jfloat>(confidence), static_cast<jfloat>(nmsThreshold)));
    env->DeleteLocalRef(hostClass);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        std::string message = takeJavaException(env);
        __android_log_print(ANDROID_LOG_ERROR, "QuickJsYolo", "detectYolo host exception: %s",
                message.c_str());
        return JS_ThrowInternalError(context, "%s",
                message.empty() ? "Java API bridge failed" : message.c_str());
    }
    if (packed == nullptr) {
        return JS_ThrowInternalError(context, "YOLO inference returned no result");
    }
    const jsize length = env->GetArrayLength(packed);
    std::vector<jfloat> values(static_cast<size_t>(length));
    if (length > 0) {
        env->GetFloatArrayRegion(packed, 0, length, values.data());
    }
    env->DeleteLocalRef(packed);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    JSValue array = JS_NewArray(context);
    for (jsize index = 0; index < length; ++index) {
        JS_SetPropertyUint32(context, array, static_cast<uint32_t>(index),
                             JS_NewFloat64(context, values[static_cast<size_t>(index)]));
    }
    return array;
}

// ---- app module JNI ----

JSValue nativeAppLaunch(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appLaunch",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppOpenUrl(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appOpenUrl",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppGetInstalledApps(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "appGetInstalledApps", nullptr);
}

JSValue nativeAppGetAppInfo(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "appGetAppInfo",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

// ---- storages module JNI ----

JSValue nativeStorageCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string name = argc > 0 ? jsString(context, argv[0]) : std::string("default");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storageCreate", "(Ljava/lang/String;)J");
    jstring javaName = toJavaString(env, name);
    const jlong result = env->CallLongMethod(state->host, method, javaName);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, result);
}

JSValue nativeStoragePut(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 3 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "storagePut requires handle, key, value");
    }
    const std::string key = jsString(context, argv[1]);
    const std::string value = jsString(context, argv[2]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storagePut",
            "(JLjava/lang/String;Ljava/lang/String;)Z");
    jstring javaKey = toJavaString(env, key);
    jstring javaValue = toJavaString(env, value);
    const jboolean result = env->CallBooleanMethod(state->host, method,
            static_cast<jlong>(handle), javaKey, javaValue);
    env->DeleteLocalRef(javaValue);
    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeStorageGet(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "storageGet requires handle, key");
    }
    const std::string key = jsString(context, argv[1]);
    const std::string defaultValue = argc > 2 ? jsString(context, argv[2]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storageGet",
            "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaKey = toJavaString(env, key);
    jstring javaDefault = toJavaString(env, defaultValue);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jlong>(handle), javaKey, javaDefault));
    env->DeleteLocalRef(javaDefault);
    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeStorageRemove(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "storageRemove requires handle, key");
    }
    const std::string key = jsString(context, argv[1]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storageRemove", "(JLjava/lang/String;)Z");
    jstring javaKey = toJavaString(env, key);
    const jboolean result = env->CallBooleanMethod(state->host, method,
            static_cast<jlong>(handle), javaKey);
    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeStorageContains(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 2 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "storageContains requires handle, key");
    }
    const std::string key = jsString(context, argv[1]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storageContains", "(JLjava/lang/String;)Z");
    jstring javaKey = toJavaString(env, key);
    const jboolean result = env->CallBooleanMethod(state->host, method,
            static_cast<jlong>(handle), javaKey);
    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeStorageClear(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "storageClear requires handle");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "storageClear", "(J)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jlong>(handle));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

// ---- device module JNI ----

JSValue nativeDeviceInfo(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "deviceGetInfo",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeDeviceIsScreenOn(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceIsScreenOn", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeDeviceVibrate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t millis = 200;
    if (argc > 0) JS_ToInt32(context, &millis, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceVibrate", "(I)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(millis));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeDeviceGetBattery(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceGetBattery", "()F");
    const jfloat result = env->CallFloatMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewFloat64(context, result);
}

JSValue nativeDeviceGetAvailMem(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceGetAvailMem", "()J");
    const jlong result = env->CallLongMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, result);
}

JSValue nativeDeviceGetTotalMem(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceGetTotalMem", "()J");
    const jlong result = env->CallLongMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, result);
}

// ---- app module: additional JNI ----

JSValue nativeAppGetPackageName(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "appGetPackageName", argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeAppGetAppName(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "appGetAppName", argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeAppOpenAppSetting(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appOpenAppSetting", argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppViewFile(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appViewFile", argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppEditFile(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appEditFile", argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppUninstall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "appUninstall", argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeAppStartActivity(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 7) return JS_ThrowTypeError(context, "appStartActivity requires 7 args");
    const std::string action = jsString(context, argv[0]);
    const std::string pkg = jsString(context, argv[1]);
    const std::string cls = jsString(context, argv[2]);
    const std::string data = jsString(context, argv[3]);
    const std::string type = jsString(context, argv[4]);
    const std::string extras = jsString(context, argv[5]);
    int32_t flags = 0;
    JS_ToInt32(context, &flags, argv[6]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "appStartActivity",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z");
    jstring jAction = toJavaString(env, action);
    jstring jPkg = toJavaString(env, pkg);
    jstring jCls = toJavaString(env, cls);
    jstring jData = toJavaString(env, data);
    jstring jType = toJavaString(env, type);
    jstring jExtras = toJavaString(env, extras);
    const jboolean result = env->CallBooleanMethod(state->host, method,
            jAction, jPkg, jCls, jData, jType, jExtras, static_cast<jint>(flags));
    env->DeleteLocalRef(jExtras); env->DeleteLocalRef(jType); env->DeleteLocalRef(jData);
    env->DeleteLocalRef(jCls); env->DeleteLocalRef(jPkg); env->DeleteLocalRef(jAction);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

// ---- device module: additional JNI ----

JSValue nativeDeviceIsCharging(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceIsCharging", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeDeviceGetBrightness(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "deviceGetBrightness");
}

JSValue nativeDeviceGetBrightnessMode(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "deviceGetBrightnessMode");
}

JSValue nativeDeviceCancelVibration(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "deviceCancelVibration", "()V");
    env->CallVoidMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

// ---- shell module JNI ----

JSValue nativeShellIsRootAvailable(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "shellIsRootAvailable", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeShellIsShizukuAvailable(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "shellIsShizukuAvailable", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeShellHasShizukuPermission(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "shellHasShizukuPermission", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeShellRequestShizukuPermission(JSContext *context, JSValueConst, int argc,
                                            JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t timeout = 60000;
    if (argc > 0) JS_ToInt32(context, &timeout, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "shellRequestShizukuPermission", "(I)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jint>(timeout));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeShellExecute(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 5) return JS_ThrowTypeError(context,
            "shellExecute requires cmd, root, shizuku, timeout, maxOutput");
    const std::string cmd = jsString(context, argv[0]);
    int32_t root = 0; JS_ToInt32(context, &root, argv[1]);
    int32_t shizuku = 0; JS_ToInt32(context, &shizuku, argv[2]);
    int32_t timeout = 10000; JS_ToInt32(context, &timeout, argv[3]);
    int32_t maxOutput = 1048576; JS_ToInt32(context, &maxOutput, argv[4]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "shellExecute",
            "(Ljava/lang/String;ZZII)Ljava/lang/String;");
    jstring jCmd = toJavaString(env, cmd);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            jCmd, root != 0, shizuku != 0,
            static_cast<jint>(timeout), static_cast<jint>(maxOutput)));
    env->DeleteLocalRef(jCmd);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

// ---- dialogs module JNI ----

JSValue nativeDialogAlert(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string title = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string content = argc > 1 ? jsString(context, argv[1]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogAlert",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jTitle = toJavaString(env, title);
    jstring jContent = toJavaString(env, content);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jTitle, jContent));
    env->DeleteLocalRef(jContent); env->DeleteLocalRef(jTitle); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeDialogConfirm(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string title = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string content = argc > 1 ? jsString(context, argv[1]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogConfirm",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jTitle = toJavaString(env, title);
    jstring jContent = toJavaString(env, content);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jTitle, jContent));
    env->DeleteLocalRef(jContent); env->DeleteLocalRef(jTitle); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeDialogPrompt(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string title = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string prefill = argc > 1 ? jsString(context, argv[1]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogPrompt",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jTitle = toJavaString(env, title);
    jstring jPrefill = toJavaString(env, prefill);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jTitle, jPrefill));
    env->DeleteLocalRef(jPrefill); env->DeleteLocalRef(jTitle); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeDialogSelect(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string title = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string items = argc > 1 ? jsString(context, argv[1]) : std::string();
    int32_t selectedIndex = -1;
    if (argc > 2 && JS_ToInt32(context, &selectedIndex, argv[2]) < 0) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogSingleChoice",
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
    jstring jTitle = toJavaString(env, title);
    jstring jItems = toJavaString(env, items);
    auto result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, jTitle, jItems, static_cast<jint>(selectedIndex)));
    env->DeleteLocalRef(jItems); env->DeleteLocalRef(jTitle); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeDialogMultiChoice(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string title = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string items = argc > 1 ? jsString(context, argv[1]) : std::string();
    const std::string indices = argc > 2 ? jsString(context, argv[2]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogMultiChoice",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jTitle = toJavaString(env, title);
    jstring jItems = toJavaString(env, items);
    jstring jIndices = toJavaString(env, indices);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jTitle, jItems, jIndices));
    env->DeleteLocalRef(jIndices); env->DeleteLocalRef(jItems);
    env->DeleteLocalRef(jTitle); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

// ---- dialogs.build JNI ----

JSValue nativeDialogBuild(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string propsJson = argc > 0 ? jsString(context, argv[0]) : std::string("{}");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "dialogBuild",
            "(Ljava/lang/String;)Ljava/lang/String;");
    jstring jProps = toJavaString(env, propsJson);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jProps));
    env->DeleteLocalRef(jProps); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

// ---- threads module JNI ----

JSValue nativeThreadsExec(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 3) return JS_ThrowTypeError(context, "threadsExec requires name, source, argsJson");
    const std::string name = jsString(context, argv[0]);
    const std::string source = jsString(context, argv[1]);
    const std::string argsJson = jsString(context, argv[2]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "threadsExec",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jName = toJavaString(env, name);
    jstring jSource = toJavaString(env, source);
    jstring jArgs = toJavaString(env, argsJson);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jName, jSource, jArgs));
    env->DeleteLocalRef(jArgs); env->DeleteLocalRef(jSource);
    env->DeleteLocalRef(jName); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeThreadsStop(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0)
        return JS_ThrowTypeError(context, "threadsStop requires handle");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "threadsStop", "(J)I");
    const jint result = env->CallIntMethod(state->host, method, static_cast<jlong>(handle));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt32(context, result);
}

// ---- shared event bus JNI (cross-engine, incl. workers) ----

JSValue nativeSharedBusOn(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "sharedBusOn requires event name");
    const std::string name = jsString(context, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "sharedBusOn", "(Ljava/lang/String;)Z");
    jstring jName = toJavaString(env, name);
    const jboolean result = env->CallBooleanMethod(state->host, method, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeSharedBusOff(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "sharedBusOff requires event name");
    const std::string name = jsString(context, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "sharedBusOff", "(Ljava/lang/String;)Z");
    jstring jName = toJavaString(env, name);
    const jboolean result = env->CallBooleanMethod(state->host, method, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeSharedBusEmit(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 2) return JS_ThrowTypeError(context, "sharedBusEmit requires name, itemJson");
    const std::string name = jsString(context, argv[0]);
    const std::string item = jsString(context, argv[1]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "sharedBusEmit",
            "(Ljava/lang/String;Ljava/lang/String;)Z");
    jstring jName = toJavaString(env, name);
    jstring jItem = toJavaString(env, item);
    const jboolean result = env->CallBooleanMethod(state->host, method, jName, jItem);
    env->DeleteLocalRef(jItem);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeSharedBusPoll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "sharedBusPoll", nullptr);
}

// ---- engines module JNI ----

JSValue nativeEnginesExecScript(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 3) return JS_ThrowTypeError(context, "enginesExecScript requires name, source, configJson");
    const std::string name = jsString(context, argv[0]);
    const std::string source = jsString(context, argv[1]);
    const std::string config = jsString(context, argv[2]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "enginesExecScript",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jName = toJavaString(env, name);
    jstring jSource = toJavaString(env, source);
    jstring jConfig = toJavaString(env, config);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jName, jSource, jConfig));
    env->DeleteLocalRef(jConfig); env->DeleteLocalRef(jSource);
    env->DeleteLocalRef(jName); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeEnginesExecScriptFile(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 2) return JS_ThrowTypeError(context, "enginesExecScriptFile requires path, configJson");
    const std::string path = jsString(context, argv[0]);
    const std::string config = jsString(context, argv[1]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "enginesExecScriptFile",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring jPath = toJavaString(env, path);
    jstring jConfig = toJavaString(env, config);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, jPath, jConfig));
    env->DeleteLocalRef(jConfig); env->DeleteLocalRef(jPath); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeEnginesMyEngineId(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "enginesMyEngineId", nullptr);
}

JSValue nativeEnginesAll(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "enginesAll", nullptr);
}

JSValue nativeEnginesStopAll(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "enginesStopAll", "()I");
    const jint result = env->CallIntMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt32(context, result);
}

JSValue nativeEnginesStopAllAndToast(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "enginesStopAllAndToast", "()V");
    env->CallVoidMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeEngineForceStop(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0)
        return JS_ThrowTypeError(context, "engineForceStop requires handle");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "engineForceStop", "(J)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jlong>(handle));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeEngineIsDestroyed(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0)
        return JS_ThrowTypeError(context, "engineIsDestroyed requires handle");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "engineIsDestroyed", "(J)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jlong>(handle));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeEngineResult(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    int32_t timeout = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0)
        return JS_ThrowTypeError(context, "engineResult requires handle");
    if (argc > 1 && JS_ToInt32(context, &timeout, argv[1]) < 0)
        return JS_ThrowTypeError(context, "engineResult timeout must be a number");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "engineResult", "(JI)Ljava/lang/String;");
    auto result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), static_cast<jint>(timeout)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

void installNativeFunction(JSContext *context, JSValue global, const char *name,
                           JSCFunction *function, int length) {
    JS_SetPropertyStr(context, global, name, JS_NewCFunction(context, function, name, length));
}

const char kBootstrapScript[] = R"JS(
(function (global) {
    'use strict';
    function format(value) {
        if (typeof value === 'string') return value;
        if (value instanceof Error) return value.stack || value.message;
        try {
            const json = JSON.stringify(value);
            return json === undefined ? String(value) : json;
        } catch (_) {
            return String(value);
        }
    }
    function write(level, args) {
        __aiNativeLog(level, Array.prototype.map.call(args, format).join(' '));
    }
    global.console = Object.freeze({
        verbose: function () { write(2, arguments); },
        log: function () { write(3, arguments); },
        info: function () { write(4, arguments); },
        warn: function () { write(5, arguments); },
        error: function () { write(6, arguments); }
    });
    global.log = global.console.log;
    global.performance = Object.freeze({ now: __aiNativePerformanceNow });
    global.toast = function (value) { return __aiNativeToast(format(value)); };
    global.toastLog = function (value) { global.toast(value); global.log(value); };
    global.sleep = function (millis) { return __aiNativeSleep(Number(millis)); };
    global.click = __aiNativeClick;
    global.press = __aiNativePress;
    global.longClick = __aiNativeLongClick;
    global.swipe = __aiNativeSwipe;
    global.back = function () { return __aiNativeGlobalAction('back'); };
    global.home = function () { return __aiNativeGlobalAction('home'); };
    global.recents = function () { return __aiNativeGlobalAction('recents'); };
    global.notifications = function () { return __aiNativeGlobalAction('notifications'); };
    global.quickSettings = function () { return __aiNativeGlobalAction('quickSettings'); };
    global.powerDialog = function () { return __aiNativeGlobalAction('powerDialog'); };
    global.splitScreen = function () { return __aiNativeGlobalAction('splitScreen'); };
    global.setClip = function (value) { return __aiNativeSetClip(String(value)); };
    global.getClip = __aiNativeGetClip;
    global.currentPackage = function () { return __aiNativeForegroundInfo('package'); };
    global.currentActivity = function () { return __aiNativeForegroundInfo('activity'); };
    // 多分辨率适配：设了坐标系后，click/swipe/图色/找图都会按实际屏幕缩放（与 Rhino 同一份 ScreenMetrics）。
    global.setScreenMetrics = function (width, height) {
        __aiNativeSetScreenMetrics(Math.round(Number(width)), Math.round(Number(height)));
    };
    // Rhino 里 SetScreenMetrics 是 shell 模块的同名写法，这里统一到同一套坐标系。
    global.SetScreenMetrics = global.setScreenMetrics;

    const frameState = new WeakMap();
    function wrapFrame(info, logicalSize) {
        const frame = Object.create(NativeFrame.prototype);
        const logicalWidth = logicalSize && logicalSize.width !== undefined
            ? Number(logicalSize.width) : Number(info.width);
        const logicalHeight = logicalSize && logicalSize.height !== undefined
            ? Number(logicalSize.height) : Number(info.height);
        const pixelWidth = Number(info.pixelWidth === undefined ? info.width : info.pixelWidth);
        const pixelHeight = Number(info.pixelHeight === undefined ? info.height : info.pixelHeight);
        frameState.set(frame, {
            id: info.id,
            recycled: false,
            pixelWidth: pixelWidth,
            pixelHeight: pixelHeight,
            scaleX: pixelWidth / logicalWidth,
            scaleY: pixelHeight / logicalHeight
        });
        Object.defineProperties(frame, {
            width: { value: logicalWidth, enumerable: true },
            height: { value: logicalHeight, enumerable: true },
            pixelWidth: { value: pixelWidth, enumerable: true },
            pixelHeight: { value: pixelHeight, enumerable: true },
            captureMode: { value: pixelWidth === logicalWidth && pixelHeight === logicalHeight
                ? 'full' : 'fast', enumerable: true }
        });
        return frame;
    }
    function requireFrame(frame) {
        const state = frameState.get(frame);
        if (!state) throw new TypeError('Expected a NativeFrame');
        if (state.recycled) throw new Error('NativeFrame has been recycled');
        return state;
    }
    function NativeFrame() {
        throw new TypeError('NativeFrame objects are created by images.captureScreen() or images.read()');
    }
    NativeFrame.prototype.recycle = function () {
        const state = frameState.get(this);
        if (!state || state.recycled) return false;
        state.recycled = true;
        return __aiNativeReleaseFrame(state.id);
    };
    NativeFrame.prototype.pixel = function (x, y) {
        return images.pixel(this, x, y);
    };
    NativeFrame.prototype.copy = function () {
        return images.copy(this);
    };
    NativeFrame.prototype.saveTo = function (path, format, quality) {
        return images.save(this, path, format, quality);
    };
    NativeFrame.prototype.toString = function () {
        const state = frameState.get(this);
        return state && !state.recycled
            ? '[NativeFrame ' + this.width + 'x' + this.height +
              (this.captureMode === 'fast' ? ', pixels=' + this.pixelWidth + 'x' + this.pixelHeight : '') + ']'
            : '[NativeFrame recycled]';
    };
    Object.defineProperty(NativeFrame.prototype, 'recycled', {
        get: function () {
            const state = frameState.get(this);
            return !state || state.recycled;
        }
    });

    function parseColor(value) {
        if (typeof value === 'number') return value >>> 0;
        let text = String(value).trim();
        if (text.charAt(0) === '#') text = text.slice(1);
        if (/^[0-9a-fA-F]{6}$/.test(text)) text = 'ff' + text;
        if (!/^[0-9a-fA-F]{8}$/.test(text)) {
            throw new TypeError('Color must be #RRGGBB, #AARRGGBB, or an integer');
        }
        return parseInt(text, 16) >>> 0;
    }
    function regionOf(frame, options) {
        const region = options && options.region;
        if (!region) return [0, 0, frame.width, frame.height];
        if (!Array.isArray(region) || region.length > 4) {
            throw new TypeError('region must be [x, y, width, height]');
        }
        const x = region[0] === undefined ? 0 : Number(region[0]);
        const y = region[1] === undefined ? 0 : Number(region[1]);
        const width = region[2] === undefined ? frame.width - x : Number(region[2]);
        const height = region[3] === undefined ? frame.height - y : Number(region[3]);
        if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x + width > frame.width || y + height > frame.height) {
            throw new RangeError('region is outside the NativeFrame');
        }
        return [x, y, width, height];
    }
    function pixelRegionOf(frame, logicalRegion) {
        const state = requireFrame(frame);
        const left = Math.max(0, Math.floor(logicalRegion[0] * state.scaleX));
        const top = Math.max(0, Math.floor(logicalRegion[1] * state.scaleY));
        const right = Math.min(state.pixelWidth,
            Math.ceil((logicalRegion[0] + logicalRegion[2]) * state.scaleX));
        const bottom = Math.min(state.pixelHeight,
            Math.ceil((logicalRegion[1] + logicalRegion[3]) * state.scaleY));
        return [left, top, Math.max(1, right - left), Math.max(1, bottom - top)];
    }
    function logicalPointOf(frame, point) {
        if (point === null || point === undefined) return point;
        const state = requireFrame(frame);
        const mapped = {
            x: Math.round(Number(point.x) / state.scaleX),
            y: Math.round(Number(point.y) / state.scaleY)
        };
        if (point.similarity !== undefined) mapped.similarity = Number(point.similarity);
        return mapped;
    }
    function prepareTemplate(frame, template) {
        const sourceState = requireFrame(frame);
        const templateState = requireFrame(template);
        const targetWidth = Math.max(1, Math.round(template.width * sourceState.scaleX));
        const targetHeight = Math.max(1, Math.round(template.height * sourceState.scaleY));
        if (templateState.pixelWidth === targetWidth && templateState.pixelHeight === targetHeight) {
            return { frame: template, owned: false };
        }
        return { frame: images.resize(template, [targetWidth, targetHeight], 'AREA'), owned: true };
    }
    function colorThreshold(options, fallback) {
        const value = options && options.threshold !== undefined
            ? Number(options.threshold) : fallback;
        if (value !== value || value === Infinity || value === -Infinity) {
            throw new TypeError('threshold must be a finite number');
        }
        return Math.max(0, Math.min(255, value));
    }
    function templateThreshold(options, fallback) {
        const value = options && options.threshold !== undefined
            ? Number(options.threshold) : fallback;
        if (value !== value || value === Infinity || value === -Infinity) {
            throw new TypeError('threshold must be a finite number');
        }
        if (value < 0 || value > 1) throw new RangeError('threshold must be between 0 and 1');
        return value;
    }
    function interpolationOf(value) {
        if (value === undefined || value === null) return 1;
        if (typeof value === 'number') return Math.max(0, Math.min(4, Math.trunc(value)));
        const name = String(value).toUpperCase().replace(/^INTER_/, '');
        const modes = { NEAREST: 0, LINEAR: 1, CUBIC: 2, AREA: 3, LANCZOS4: 4 };
        if (modes[name] === undefined) throw new TypeError('Unknown interpolation: ' + value);
        return modes[name];
    }
    function sizeOf(value, height) {
        if (Array.isArray(value) && value.length === 2) return [Number(value[0]), Number(value[1])];
        if (value && typeof value === 'object') return [Number(value.width), Number(value.height)];
        return [Number(value), Number(height)];
    }
    function formatOf(path, format) {
        if (format !== undefined && format !== null && String(format)) return String(format);
        const match = /\.([^.\\/]+)$/.exec(String(path));
        return match ? match[1] : 'png';
    }
    function channel(value, shift) {
        return (parseColor(value) >>> shift) & 0xff;
    }
    const colors = {
        parseColor: parseColor,
        argb: function (a, r, g, b) {
            return (((Number(a) & 255) << 24) | ((Number(r) & 255) << 16) |
                ((Number(g) & 255) << 8) | (Number(b) & 255)) >>> 0;
        },
        rgb: function (r, g, b) { return colors.argb(255, r, g, b); },
        alpha: function (color) { return channel(color, 24); },
        red: function (color) { return channel(color, 16); },
        green: function (color) { return channel(color, 8); },
        blue: function (color) { return channel(color, 0); },
        toString: function (color) {
            return '#' + parseColor(color).toString(16).padStart(8, '0').toUpperCase();
        },
        isSimilar: function (left, right, threshold) {
            threshold = threshold === undefined ? 4 : Math.max(0, Math.min(255, Number(threshold)));
            left = parseColor(left); right = parseColor(right);
            return Math.abs(channel(left, 16) - channel(right, 16)) <= threshold &&
                Math.abs(channel(left, 8) - channel(right, 8)) <= threshold &&
                Math.abs(channel(left, 0) - channel(right, 0)) <= threshold;
        }
    };
    function orientationOf(value) {
        if (value === 'portrait') return 1;
        if (value === 'landscape') return 2;
        return Number(value) === 1 || Number(value) === 2 ? Number(value) : 0;
    }

    const images = {
        requestScreenCapture: function (orientation) {
            return __aiNativeRequestScreenCapture(orientationOf(orientation));
        },
        captureScreen: function (options) {
            let targetShortEdge = 0;
            let fresh = false;
            let timeout = 100;
            if (typeof options === 'number') {
                targetShortEdge = Number(options);
            } else if (typeof options === 'string') {
                targetShortEdge = String(options).toLowerCase() === 'fast' ? 720 : 0;
            } else if (options && typeof options === 'object') {
                const mode = String(options.mode || 'full').toLowerCase();
                if (mode === 'fast' || mode === 'visual' || mode === 'vision') {
                    targetShortEdge = options.size === undefined ? 720 : Number(options.size);
                }
                fresh = options.fresh === true;
                timeout = options.timeout === undefined ? 100 : Number(options.timeout);
            }
            if (!Number.isFinite(targetShortEdge) || targetShortEdge < 0 || targetShortEdge > 4096) {
                throw new RangeError('captureScreen fast size must be between 0 and 4096');
            }
            if (!Number.isFinite(timeout) || timeout < 0 || timeout > 5000) {
                throw new RangeError('captureScreen timeout must be between 0 and 5000 ms');
            }
            return wrapFrame(__aiNativeCaptureFrame(
                Math.round(targetShortEdge), fresh, Math.round(timeout)));
        },
        read: function (path) {
            return wrapFrame(__aiNativeReadFrame(String(path)));
        },
        copy: function (frame) {
            return wrapFrame(__aiNativeCopyFrame(requireFrame(frame).id),
                { width: frame.width, height: frame.height });
        },
        clip: function (frame, x, y, width, height) {
            const logical = regionOf(frame, { region: [Number(x), Number(y), Number(width), Number(height)] });
            const region = pixelRegionOf(frame, logical);
            return wrapFrame(__aiNativeClipFrame(requireFrame(frame).id,
                region[0], region[1], region[2], region[3]),
                { width: logical[2], height: logical[3] });
        },
        resize: function (frame, size, heightOrInterpolation, interpolation) {
            let height = heightOrInterpolation;
            let mode = interpolation;
            if (Array.isArray(size) || (size && typeof size === 'object')) {
                mode = heightOrInterpolation;
                height = undefined;
            } else if (heightOrInterpolation === undefined || typeof heightOrInterpolation === 'string') {
                mode = heightOrInterpolation;
                height = size;
            }
            const dimensions = sizeOf(size, height);
            return wrapFrame(__aiNativeResizeFrame(requireFrame(frame).id,
                dimensions[0], dimensions[1], interpolationOf(mode)));
        },
        scale: function (frame, fx, fy, interpolation) {
            fy = fy === undefined ? fx : fy;
            return images.resize(frame,
                [Math.max(1, Math.round(frame.pixelWidth * Number(fx))),
                 Math.max(1, Math.round(frame.pixelHeight * Number(fy)))], interpolation);
        },
        grayscale: function (frame) {
            return wrapFrame(__aiNativeGrayscaleFrame(requireFrame(frame).id),
                { width: frame.width, height: frame.height });
        },
        gray: function (frame) {
            return images.grayscale(frame);
        },
        cvtColor: function (frame, code) {
            return wrapFrame(__aiNativeCvtColorFrame(requireFrame(frame).id, String(code)),
                { width: frame.width, height: frame.height });
        },
        rotate: function (frame, angle) {
            angle = Number(angle || 0);
            var rotated = wrapFrame(__aiNativeRotateFrame(requireFrame(frame).id, Math.round(angle)));
            // 90/270 swap width/height
            if (angle === 90 || angle === 270) {
                rotated.width = frame.height;
                rotated.height = frame.width;
            } else {
                rotated.width = frame.width;
                rotated.height = frame.height;
            }
            return rotated;
        },
        threshold: function (frame, thresh, maxValue, type) {
            thresh = thresh === undefined ? 128 : Number(thresh);
            maxValue = maxValue === undefined ? 255 : Number(maxValue);
            type = type === undefined ? 0 : Number(type);
            return wrapFrame(__aiNativeThresholdFrame(requireFrame(frame).id,
                thresh, maxValue, type),
                { width: frame.width, height: frame.height });
        },
        blur: function (frame, ksize) {
            ksize = ksize === undefined ? 5 : Number(ksize);
            return wrapFrame(__aiNativeBlurFrame(requireFrame(frame).id, Math.round(ksize)),
                { width: frame.width, height: frame.height });
        },
        save: function (frame, path, format, quality) {
            quality = quality === undefined ? 100 : Number(quality);
            return __aiNativeSaveFrame(requireFrame(frame).id, String(path),
                formatOf(path, format), quality);
        },
        compress: function (frame, format, quality) {
            format = format === undefined ? 'jpg' : String(format);
            quality = quality === undefined ? 80 : Number(quality);
            return new Uint8Array(__aiNativeCompressFrame(requireFrame(frame).id, format, quality));
        },
        pixel: function (frame, x, y) {
            const state = requireFrame(frame);
            x = Number(x);
            y = Number(y);
            if (!Number.isFinite(x) || !Number.isFinite(y) ||
                    x < 0 || y < 0 || x >= frame.width || y >= frame.height) {
                throw new RangeError('pixel coordinate is outside the logical image bounds');
            }
            const pixelX = Math.max(0, Math.min(state.pixelWidth - 1,
                Math.floor(x * state.scaleX)));
            const pixelY = Math.max(0, Math.min(state.pixelHeight - 1,
                Math.floor(y * state.scaleY)));
            return __aiNativeFramePixel(state.id, pixelX, pixelY);
        },
        detectsColor: function (frame, color, x, y, threshold) {
            return colors.isSimilar(images.pixel(frame, x, y), color,
                threshold === undefined ? 4 : threshold);
        },
        findColor: function (frame, color, options) {
            const region = pixelRegionOf(frame, regionOf(frame, options));
            const threshold = options && options.similarity !== undefined
                ? Math.trunc(255 * (1 - Number(options.similarity)))
                : colorThreshold(options, 4);
            return logicalPointOf(frame,
                __aiNativeFindColor(requireFrame(frame).id, parseColor(color), threshold,
                    region[0], region[1], region[2], region[3]));
        },
        findColorInRegion: function (frame, color, x, y, width, height, threshold) {
            return images.findColor(frame, color, {
                region: [Number(x), Number(y), Number(width), Number(height)],
                threshold: threshold === undefined ? 4 : threshold
            });
        },
        findMultiColors: function (frame, firstColor, paths, options) {
            if (!Array.isArray(paths)) throw new TypeError('paths must be [[dx, dy, color], ...]');
            const state = requireFrame(frame);
            const normalized = paths.map(function (entry) {
                if (!Array.isArray(entry) || entry.length < 3) {
                    throw new TypeError('Each path entry must be [dx, dy, color]');
                }
                return [Math.round(Number(entry[0]) * state.scaleX),
                    Math.round(Number(entry[1]) * state.scaleY), parseColor(entry[2])];
            });
            const region = pixelRegionOf(frame, regionOf(frame, options));
            return logicalPointOf(frame,
                __aiNativeFindMultiColors(state.id, parseColor(firstColor),
                    normalized, colorThreshold(options, 4),
                    region[0], region[1], region[2], region[3]));
        },
        findImage: function (frame, template, options) {
            const region = pixelRegionOf(frame, regionOf(frame, options));
            const threshold = templateThreshold(options, 0.9);
            const prepared = prepareTemplate(frame, template);
            try {
                return logicalPointOf(frame,
                    __aiNativeFindImage(requireFrame(frame).id, requireFrame(prepared.frame).id, threshold,
                        region[0], region[1], region[2], region[3]));
            } finally {
                if (prepared.owned) prepared.frame.recycle();
            }
        },
        matchTemplate: function (frame, template, options) {
            options = options || {};
            const region = pixelRegionOf(frame, regionOf(frame, options));
            const threshold = templateThreshold(options, 0.9);
            const max = options.max === undefined ? 5 : Number(options.max);
            if (max !== max || max === Infinity || max === -Infinity || max < 1 || max > 1000) {
                throw new RangeError('max must be between 1 and 1000');
            }
            const prepared = prepareTemplate(frame, template);
            let raw;
            try {
                raw = __aiNativeMatchTemplate(requireFrame(frame).id, requireFrame(prepared.frame).id,
                    threshold, max, region[0], region[1], region[2], region[3]);
            } finally {
                if (prepared.owned) prepared.frame.recycle();
            }
            const matches = raw.map(function (item) {
                const point = logicalPointOf(frame, item);
                return { point: { x: point.x, y: point.y }, similarity: item.similarity };
            });
            const result = {
                matches: matches,
                first: function () { return matches.length ? matches[0] : null; },
                last: function () { return matches.length ? matches[matches.length - 1] : null; },
                best: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.similarity >= b.similarity ? a : b;
                }) : null; },
                worst: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.similarity <= b.similarity ? a : b;
                }) : null; },
                leftmost: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.point.x <= b.point.x ? a : b;
                }) : null; },
                topmost: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.point.y <= b.point.y ? a : b;
                }) : null; },
                rightmost: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.point.x >= b.point.x ? a : b;
                }) : null; },
                bottommost: function () { return matches.length ? matches.reduce(function (a, b) {
                    return a.point.y >= b.point.y ? a : b;
                }) : null; },
                sortBy: function (comparator) {
                    const clone = matches.slice();
                    const builtins = {
                        left: function (a, b) { return a.point.x - b.point.x; },
                        top: function (a, b) { return a.point.y - b.point.y; },
                        right: function (a, b) { return b.point.x - a.point.x; },
                        bottom: function (a, b) { return b.point.y - a.point.y; }
                    };
                    if (typeof comparator === 'string') {
                        const directions = comparator.split('-');
                        clone.sort(function (a, b) {
                            for (let i = 0; i < directions.length; i++) {
                                const fn = builtins[directions[i]];
                                if (!fn) throw new Error('Unknown match sort direction: ' + directions[i]);
                                const compared = fn(a, b);
                                if (compared) return compared;
                            }
                            return 0;
                        });
                    } else {
                        clone.sort(comparator);
                    }
                    return { matches: clone };
                }
            };
            Object.defineProperty(result, 'points', {
                enumerable: true,
                get: function () { return matches.map(function (match) { return match.point; }); }
            });
            return result;
        }
    };
    images.saveImage = images.save;
    images.fromBase64 = function (data) {
        return wrapFrame(__aiNativeFrameFromBase64(String(data)));
    };
    images.toBase64 = function (frame, format, quality) {
        var bytes = images.compress(frame, format, quality);
        var table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var out = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i];
            var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
            var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            out += table[b0 >> 2];
            out += table[((b0 & 3) << 4) | (b1 >> 4)];
            out += i + 1 < bytes.length ? table[((b1 & 15) << 2) | (b2 >> 6)] : '=';
            out += i + 2 < bytes.length ? table[b2 & 63] : '=';
        }
        return out;
    };
    images.concat = function (frame1, frame2, direction) {
        var dir = String(direction || 'horizontal').toLowerCase() === 'vertical' ? 1 : 0;
        return wrapFrame(__aiNativeFrameConcat(
            requireFrame(frame1).id, requireFrame(frame2).id, dir));
    };
    images.load = function (src) {
        src = String(src == null ? '' : src);
        var match = /^data:image\/[a-z0-9.+-]+;base64,/i.exec(src);
        if (match) {
            return images.fromBase64(src.substring(match[0].length));
        }
        return images.read(src);
    };
    images.findColorEquals = function (frame, color, x, y, width, height) {
        return images.findColorInRegion(frame, color, x, y, width, height, 0);
    };
    images.findImageInRegion = function (frame, template, x, y, width, height, threshold) {
        return images.findImage(frame, template, {
            region: [x, y, width, height],
            threshold: threshold === undefined ? 0.9 : threshold
        });
    };
    global.NativeFrame = NativeFrame;
    global.colors = Object.freeze(colors);
    global.images = Object.freeze(images);
    global.requestScreenCapture = images.requestScreenCapture;
    global.captureScreen = images.captureScreen;
    global.findColor = images.findColor;
    global.findColorInRegion = images.findColorInRegion;
    global.findColorEquals = images.findColorEquals;
    global.findMultiColors = images.findMultiColors;
    global.findImage = images.findImage;
    global.findImageInRegion = images.findImageInRegion;

    const detectorState = new WeakMap();
    function requireDetector(detector) {
        const state = detectorState.get(detector);
        if (!state) throw new TypeError('Expected a YOLO detector');
        if (state.closed) throw new Error('YOLO detector has been closed');
        return state;
    }
    function YoloDetector() {
        throw new TypeError('YOLO detectors are created by yolo.load()');
    }
    YoloDetector.prototype.detect = function (frame, options) {
        const detector = requireDetector(this);
        const nativeFrame = requireFrame(frame);
        options = options || {};
        const confidence = options.confidence === undefined ? 0.25 : Number(options.confidence);
        const nms = options.nms === undefined ? 0.45 : Number(options.nms);
        let region = [0, 0, frame.width, frame.height];
        if (options.region !== undefined) {
            if (!Array.isArray(options.region) || options.region.length !== 4) {
                throw new TypeError('region must be [x, y, width, height]');
            }
            region = options.region.map(Number);
        }
        region = pixelRegionOf(frame, region);
        const packed = __aiNativeYoloDetect(detector.id, nativeFrame.id, confidence, nms,
            region[0], region[1], region[2], region[3]);
        const detections = [];
        for (let i = 2; i + 5 < packed.length; i += 6) {
            const left = Number(packed[i]) / nativeFrame.scaleX;
            const top = Number(packed[i + 1]) / nativeFrame.scaleY;
            const right = Number(packed[i + 2]) / nativeFrame.scaleX;
            const bottom = Number(packed[i + 3]) / nativeFrame.scaleY;
            const classId = Math.round(Number(packed[i + 5]));
            detections.push({
                classId: classId,
                label: detector.labels[classId] === undefined
                    ? String(classId) : detector.labels[classId],
                score: Number(packed[i + 4]),
                bounds: {
                    left: left,
                    top: top,
                    right: right,
                    bottom: bottom,
                    width: right - left,
                    height: bottom - top,
                    centerX: (left + right) / 2,
                    centerY: (top + bottom) / 2
                }
            });
        }
        detections.preprocessMs = packed.length >= 2 ? Number(packed[0]) : 0;
        detections.inferenceMs = packed.length >= 2 ? Number(packed[1]) : 0;
        detections.totalMs = detections.preprocessMs + detections.inferenceMs;
        return detections;
    };
    YoloDetector.prototype.close = function () {
        const state = detectorState.get(this);
        if (!state || state.closed) return false;
        state.closed = true;
        return __aiNativeYoloClose(state.id);
    };
    YoloDetector.prototype.isClosed = function () {
        const state = detectorState.get(this);
        return !state || state.closed;
    };

    const yolo = {
        isAvailable: function (backend) {
            return __aiNativeYoloIsAvailable(String(backend || 'opencv').toLowerCase());
        },
        getUnavailableReason: function (backend) {
            backend = String(backend || 'opencv').toLowerCase();
            return this.isAvailable(backend) ? '' : __aiNativeYoloUnavailableReason(backend);
        },
        getVersion: function (backend) {
            backend = String(backend || 'opencv').toLowerCase();
            return this.isAvailable(backend) ? __aiNativeYoloVersion(backend) : 'unavailable';
        },
        load: function (options) {
            options = options || {};
            const backend = String(options.backend || 'opencv').toLowerCase();
            const supported = { opencv: 1, dnn: 1, opencv5: 1, 'opencv-dnn': 1, cpu: 1 };
            if (!supported[backend]) {
                throw new Error('QuickJS YOLO 不支持的 backend: ' + backend + '（仅支持 opencv）');
            }
            if (!__aiNativeYoloIsAvailable('opencv')) {
                throw new Error(__aiNativeYoloUnavailableReason('opencv'));
            }
            const model = options.model ? String(options.model) : '';
            if (!model) {
                throw new TypeError('yolo.load opencv 需要 model 路径');
            }
            let labels = options.labels || [];
            if (typeof labels === 'string') {
                labels = __aiNativeYoloReadLabels(labels).trim().split(/\r?\n/);
            }
            if (!Array.isArray(labels)) throw new TypeError('labels must be an array or path');
            const detector = Object.create(YoloDetector.prototype);
            const inputSize = options.inputSize === undefined ? 320 : Number(options.inputSize);
            const inputWidth = options.inputWidth === undefined ? inputSize : Number(options.inputWidth);
            const inputHeight = options.inputHeight === undefined ? inputSize : Number(options.inputHeight);
            const id = __aiNativeYoloLoad('opencv', model, '', '',
                inputWidth, inputHeight,
                options.threads === undefined ? 4 : Number(options.threads));
            detectorState.set(detector, { id: id, labels: labels.slice(), closed: false });
            return detector;
        }
    };
    global.YoloDetector = YoloDetector;
    global.yolo = Object.freeze(yolo);

    const timerIds = new Set();
    function makeTimer(repeat, callback, millis) {
        if (typeof callback !== 'function') {
            throw new TypeError('Timer callback must be a function');
        }
        const delay = Math.max(0, Number(millis) || 0);
        const id = repeat
            ? __aiNativeSetInterval(callback, delay)
            : __aiNativeSetTimeout(callback, delay);
        timerIds.add(id);
        return id;
    }
    global.setTimeout = function (callback, millis) { return makeTimer(false, callback, millis); };
    global.setInterval = function (callback, millis) { return makeTimer(true, callback, millis); };
    function clearTimer(id) {
        id = Number(id);
        if (!timerIds.has(id)) return false;
        timerIds.delete(id);
        return __aiNativeClearTimer(id);
    }
    global.clearTimeout = clearTimer;
    global.clearInterval = clearTimer;

    function filePath(path) { return String(path); }
    const files = {
        path: function (path) { return __aiNativeFilesPath(filePath(path)); },
        cwd: function () { return __aiNativeFilesCwd(); },
        getSdcardPath: function () { return __aiNativeFilesGetSdcardPath(); },
        exists: function (path) { return __aiNativeFilesExists(filePath(path)); },
        isFile: function (path) { return __aiNativeFilesIsFile(filePath(path)); },
        isDir: function (path) { return __aiNativeFilesIsDir(filePath(path)); },
        read: function (path) { return __aiNativeFilesRead(filePath(path)); },
        write: function (path, text) { __aiNativeFilesWrite(filePath(path), String(text)); },
        append: function (path, text) { __aiNativeFilesAppend(filePath(path), String(text)); },
        create: function (path) { return __aiNativeFilesCreate(filePath(path)); },
        createWithDirs: function (path) { return __aiNativeFilesCreate(filePath(path)); },
        ensureDir: function (path) { return __aiNativeFilesEnsureDir(filePath(path)); },
        listDir: function (path) { return JSON.parse(__aiNativeFilesListDir(filePath(path))); },
        remove: function (path) { return __aiNativeFilesRemove(filePath(path)); },
        rename: function (path, newName) { return __aiNativeFilesRename(filePath(path), String(newName)); },
        copy: function (source, target) { return __aiNativeFilesCopy(filePath(source), filePath(target)); },
        move: function (source, target) { return __aiNativeFilesMove(filePath(source), filePath(target)); }
    };
    global.files = Object.freeze(files);

    function formEncode(data) {
        if (data === null || data === undefined) return '';
        if (typeof data !== 'object') return encodeURIComponent(String(data));
        return Object.keys(data)
            .map(function (key) {
                return encodeURIComponent(key) + '=' + encodeURIComponent(String(data[key]));
            })
            .join('&');
    }
    function httpExecute(method, url, options, payload, asJson) {
        options = options || {};
        const headers = options.headers || {};
        let body = payload === undefined || payload === null ? null : payload;
        let contentType = options.contentType || '';
        if (body !== null && typeof body === 'object') {
            if (asJson) {
                contentType = 'application/json';
                body = JSON.stringify(body);
            } else {
                contentType = 'application/x-www-form-urlencoded';
                body = formEncode(body);
            }
        } else {
            body = body === null ? null : String(body);
        }
        const raw = __aiNativeHttpRequest(String(method), String(url),
            JSON.stringify(headers), body || '', contentType);
        const parsed = JSON.parse(raw);
        const text = parsed.body;
        parsed.body = {
            string: text,
            contentType: parsed.contentType,
            json: function () { return JSON.parse(text); }
        };
        return parsed;
    }
    const http = {
        get: function (url, options) { return httpExecute('GET', url, options); },
        post: function (url, data, options) { return httpExecute('POST', url, options, data, false); },
        postJson: function (url, data, options) { return httpExecute('POST', url, options, data, true); },
        request: function (url, options) {
            options = options || {};
            return httpExecute(options.method || 'GET', url, options, options.body, options.json);
        }
    };
    global.http = Object.freeze(http);

    const drawing = {
        show: function () { return __aiNativeDrawCreate(); },
        hide: function () { __aiNativeDrawClose(); },
        update: function (detections, stats) {
            const packed = [];
            if (Array.isArray(detections)) {
                detections.forEach(function (item) {
                    const bounds = item.bounds || item;
                    packed.push({
                        x1: Number(bounds.left !== undefined ? bounds.left : bounds.x1),
                        y1: Number(bounds.top !== undefined ? bounds.top : bounds.y1),
                        x2: Number(bounds.right !== undefined ? bounds.right : bounds.x2),
                        y2: Number(bounds.bottom !== undefined ? bounds.bottom : bounds.y2),
                        label: String(item.label === undefined ? '' : item.label),
                        score: Number(item.score || 0)
                    });
                });
            }
            return __aiNativeDrawUpdate(JSON.stringify(packed),
                stats === undefined || stats === null ? '' : String(stats));
        }
    };
    global.drawing = Object.freeze(drawing);

    // ---- app module ----
    const app = {
        launch: function (pkg) { return __aiNativeAppLaunch(String(pkg)); },
        openUrl: function (url) { return __aiNativeAppOpenUrl(String(url)); },
        getInstalledApps: function () { return JSON.parse(__aiNativeAppGetInstalledApps()); },
        getAppInfo: function (pkg) { return JSON.parse(__aiNativeAppGetAppInfo(String(pkg))); },
        launchPackage: function (pkg) { return __aiNativeAppLaunch(String(pkg)); }
    };
    global.app = Object.freeze(app);

    // ---- storages module ----
    const storageState = new WeakMap();
    function LocalStorage(name) {
        var h = __aiNativeStorageCreate(String(name || 'default'));
        var s = Object.create(LocalStorage.prototype);
        storageState.set(s, { handle: h, closed: false });
        return s;
    }
    LocalStorage.prototype = {
        put: function (key, value) {
            if (value === undefined) throw new TypeError('value cannot be undefined');
            var st = storageState.get(this);
            if (!st || st.closed) throw new Error('Storage is closed');
            var serialized = JSON.stringify(value);
            if (serialized === undefined) throw new TypeError('value is not JSON serializable');
            return __aiNativeStoragePut(st.handle, String(key), serialized);
        },
        get: function (key, defaultValue) {
            var st = storageState.get(this);
            if (!st || st.closed) throw new Error('Storage is closed');
            var raw = __aiNativeStorageGet(st.handle, String(key),
                    defaultValue === undefined ? '' : JSON.stringify(defaultValue));
            if (raw === '') return defaultValue;
            try { return JSON.parse(raw); } catch (_) { return raw; }
        },
        remove: function (key) {
            var st = storageState.get(this);
            if (!st || st.closed) throw new Error('Storage is closed');
            return __aiNativeStorageRemove(st.handle, String(key));
        },
        contains: function (key) {
            var st = storageState.get(this);
            if (!st || st.closed) throw new Error('Storage is closed');
            return __aiNativeStorageContains(st.handle, String(key));
        },
        clear: function () {
            var st = storageState.get(this);
            if (!st || st.closed) throw new Error('Storage is closed');
            return __aiNativeStorageClear(st.handle);
        }
    };
    global.storages = Object.freeze({
        create: function (name) { return new LocalStorage(name); },
        remove: function (name) { var s = new LocalStorage(name); s.clear(); }
    });
    global.LocalStorage = LocalStorage;

    // ---- device module ----
    var _devCache = {};
    function devInfo(key) { return _devCache[key] || (_devCache[key] = __aiNativeDeviceInfo(key)); }
    global.device = Object.freeze({
        get width() { return Number(devInfo('width')); },
        get height() { return Number(devInfo('height')); },
        get model() { return devInfo('model'); },
        get brand() { return devInfo('brand'); },
        get board() { return devInfo('board'); },
        get hardware() { return devInfo('hardware'); },
        get sdkInt() { return Number(devInfo('sdkInt')); },
        get release() { return devInfo('release'); },
        get buildId() { return devInfo('buildId'); },
        get display() { return devInfo('display'); },
        get product() { return devInfo('product'); },
        get manufacturer() { return devInfo('manufacturer'); },
        isScreenOn: function () { return __aiNativeDeviceIsScreenOn(); },
        vibrate: function (ms) { __aiNativeDeviceVibrate(ms === undefined ? 200 : Number(ms)); },
        getBattery: function () { return __aiNativeDeviceGetBattery(); }
    });

    // ---- device module (additional) ----
    var _devInfo = global.device;
    global.device = Object.freeze({
        get width() { return _devInfo.width; },
        get height() { return _devInfo.height; },
        get model() { return _devInfo.model; },
        get brand() { return _devInfo.brand; },
        get board() { return _devInfo.board; },
        get hardware() { return _devInfo.hardware; },
        get sdkInt() { return _devInfo.sdkInt; },
        get release() { return _devInfo.release; },
        get buildId() { return _devInfo.buildId; },
        get display() { return _devInfo.display; },
        get product() { return _devInfo.product; },
        get manufacturer() { return _devInfo.manufacturer; },
        isScreenOn: function () { return _devInfo.isScreenOn(); },
        vibrate: function (ms) { _devInfo.vibrate(ms); },
        getBattery: function () { return _devInfo.getBattery(); },
        isCharging: function () { return __aiNativeDeviceIsCharging(); },
        getBrightness: function () { return Number(__aiNativeDeviceGetBrightness()); },
        getBrightnessMode: function () { return Number(__aiNativeDeviceGetBrightnessMode()); },
        cancelVibration: function () { __aiNativeDeviceCancelVibration(); },
        getAvailMem: function () { return Number(__aiNativeDeviceGetAvailMem()); },
        getTotalMem: function () { return Number(__aiNativeDeviceGetTotalMem()); }
    });

    // ---- app module (additional) ----
    var _app = global.app;
    global.app = Object.freeze({
        launch: _app.launch,
        openUrl: _app.openUrl,
        getInstalledApps: _app.getInstalledApps,
        getAppInfo: _app.getAppInfo,
        launchPackage: _app.launchPackage,
        // Auto.js 的 app.launchApp(name) 按应用名启动：先查包名再启动。
        launchApp: function (name) {
            var pkg = __aiNativeAppGetPackageName(String(name));
            return pkg ? __aiNativeAppLaunch(String(pkg)) : false;
        },
        getPackageName: function (name) { return __aiNativeAppGetPackageName(String(name)); },
        getAppName: function (pkg) { return __aiNativeAppGetAppName(String(pkg)); },
        openAppSetting: function (pkg) { return __aiNativeAppOpenAppSetting(String(pkg)); },
        viewFile: function (path) { return __aiNativeAppViewFile(String(path)); },
        editFile: function (path) { return __aiNativeAppEditFile(String(path)); },
        uninstall: function (pkg) { return __aiNativeAppUninstall(String(pkg)); },
        startActivity: function (opts) {
            opts = opts || {};
            return __aiNativeAppStartActivity(
                opts.action || '', opts.packageName || opts.package || '',
                opts.className || opts.class || '', opts.data || '',
                opts.type || '', JSON.stringify(opts.extras || {}),
                opts.flags || 0);
        }
    });

    // ---- shell module ----
    global.shell = function (cmd, opts) {
        var root = false, useShizuku = false, timeout = 10000, maxOutput = 1048576;
        if (opts === true) { root = true; }
        else if (opts && typeof opts === 'object') {
            root = !!opts.root;
            useShizuku = !!opts.shizuku;
            timeout = opts.timeout || 10000;
            maxOutput = opts.maxOutput || 1048576;
        }
        var raw = __aiNativeShellExecute(
            String(cmd), root ? 1 : 0, useShizuku ? 1 : 0, timeout, maxOutput);
        var parsed = JSON.parse(raw);
        return { code: parsed.code, result: parsed.result || '', error: parsed.error || '' };
    };
    shell.isRootAvailable = function () { return __aiNativeShellIsRootAvailable(); };
    global.shizuku = Object.freeze({
        isAvailable: function () { return __aiNativeShellIsShizukuAvailable(); },
        hasPermission: function () { return __aiNativeShellHasShizukuPermission(); },
        requestPermission: function (timeout) {
            return __aiNativeShellRequestShizukuPermission(timeout || 60000);
        },
        shell: function (cmd, opts) {
            opts = opts || {};
            return global.shell(cmd, {
                shizuku: true,
                timeout: opts.timeout || 10000,
                maxOutput: opts.maxOutput || 1048576
            });
        }
    });

    // ---- engines module ----
    function makeEngineHandle(info) {
        info = info || {};
        var handle = Number(info.handle === undefined ? info : info.handle);
        function restoreResultValue(value) {
            // Object/array results cross the JNI boundary as JSON text; restore
            // them so waitForResult()/promise() hand back real objects.
            if (typeof value !== 'string' || value.length === 0) return value;
            var first = value.charAt(0);
            if (first !== '{' && first !== '[') return value;
            try { return JSON.parse(value); } catch (error) { return value; }
        }
        function resultState(timeout) {
            if (handle === 0) return { status: 'running' };
            var state = JSON.parse(__aiNativeEngineResult(handle, Math.max(0, Number(timeout) || 0)));
            if (state && state.status === 'success') {
                state.value = restoreResultValue(state.value);
            }
            return state;
        }
        return Object.freeze({
            id: Number(info.id === undefined ? -1 : info.id),
            handle: handle,
            source: String(info.source || ''),
            engineName: String(info.engineName || 'QuickJsJavaScriptEngine'),
            getEngine: function () { return engine; },
            forceStop: function () { return __aiNativeEngineForceStop(handle); },
            isDestroyed: function () { return __aiNativeEngineIsDestroyed(handle); },
            getResult: function () { return resultState(0); },
            waitForResult: function (timeout) {
                var result = resultState(timeout === undefined ? 0x7fffffff : timeout);
                if (result.status === 'error') throw new Error(result.error || 'Worker failed');
                return result.status === 'success' ? result.value : undefined;
            }
        });
    }

    function quickJsChildSource(source, config) {
        source = String(source || '');
        if (config && String(config.engine || '').toLowerCase() === 'rhino') return source;
        if (/^\s*(?:\uFEFF)?\s*\/\/\s*@engine\s+quickjs\s*(?:\r?\n|$)/i.test(source)) return source;
        return '// @engine quickjs\n' + source;
    }

    var _engines = {
        execScript: function (name, source, config) {
            config = config || {};
            var handle = Number(__aiNativeEnginesExecScript(
                    String(name || ''), quickJsChildSource(source, config), JSON.stringify(config)));
            if (handle < 0) throw new Error('Unable to start child script');
            return makeEngineHandle({ handle: handle, source: String(name || '') + '.js' });
        },
        execScriptFile: function (path, config) {
            var handle = Number(__aiNativeEnginesExecScriptFile(
                    String(path), JSON.stringify(config || {})));
            if (handle < 0) throw new Error('Unable to start script file: ' + path);
            return makeEngineHandle({ handle: handle, source: String(path) });
        },
        myEngine: function () { return makeEngineHandle(JSON.parse(__aiNativeEnginesMyEngineId())); },
        all: function () { return JSON.parse(__aiNativeEnginesAll()).map(makeEngineHandle); },
        stopAll: function () { return __aiNativeEnginesStopAll(); },
        stopAllAndToast: function () { __aiNativeEnginesStopAllAndToast(); }
    };
    global.engines = Object.freeze(_engines);

    // ---- local events module ----
    var eventListeners = new Map();
    var maxListeners = 10;
    var systemEventTimer = null;
    function dispatchSystemEvents() {
        for (;;) {
            var raw = __aiNativeEventsPoll();
            if (!raw) break;
            var event = JSON.parse(raw);
            if (event.type === 'key_down' || event.type === 'key_up') {
                events.emit(event.type, event.keyCode, event);
                events.emit(event.keyName, event);
                events.emit('__' + event.type + '__#' + event.keyName, event);
                events.emit('key', event.keyCode, event);
            } else if (event.type === 'touch') {
                events.emit('touch', event.x, event.y, event);
            } else if (event.type === 'gesture') {
                events.emit('gesture', event.gesture, event);
            } else {
                events.emit(event.type, event);
            }
        }
    }
    function observeSystemEvent(kind) {
        var result = !!__aiNativeEventsObserve(String(kind));
        if (result && systemEventTimer === null) {
            systemEventTimer = setInterval(dispatchSystemEvents, 40);
        }
        return result;
    }
    function listenersFor(name, create) {
        name = String(name);
        var listeners = eventListeners.get(name);
        if (!listeners && create) {
            listeners = [];
            eventListeners.set(name, listeners);
        }
        return listeners;
    }
    var events = {
        on: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            listenersFor(name, true).push(listener);
            return events;
        },
        once: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            function onceListener() {
                events.removeListener(name, onceListener);
                return listener.apply(undefined, arguments);
            }
            onceListener.listener = listener;
            return events.on(name, onceListener);
        },
        emit: function (name) {
            var listeners = listenersFor(name, false);
            if (!listeners || listeners.length === 0) return false;
            var args = Array.prototype.slice.call(arguments, 1);
            listeners.slice().forEach(function (listener) { listener.apply(undefined, args); });
            return true;
        },
        removeListener: function (name, listener) {
            var listeners = listenersFor(name, false);
            if (!listeners) return events;
            for (var i = listeners.length - 1; i >= 0; i--) {
                if (listeners[i] === listener || listeners[i].listener === listener) listeners.splice(i, 1);
            }
            if (listeners.length === 0) eventListeners.delete(String(name));
            return events;
        },
        removeAllListeners: function (name) {
            if (name === undefined) eventListeners.clear();
            else eventListeners.delete(String(name));
            return events;
        },
        listenerCount: function (name) {
            var listeners = listenersFor(name, false);
            return listeners ? listeners.length : 0;
        },
        eventNames: function () {
            var names = [];
            var it = eventListeners.keys();
            for (;;) {
                var next = it.next();
                if (next.done) break;
                names.push(next.value);
            }
            return names;
        },
        listeners: function (name) {
            return (listenersFor(name, false) || []).slice();
        },
        addListener: function (name, listener) {
            return events.on(name, listener);
        },
        prependListener: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            listenersFor(name, true).unshift(listener);
            return events;
        },
        prependOnceListener: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            function onceListener() {
                events.removeListener(name, onceListener);
                return listener.apply(undefined, arguments);
            }
            onceListener.listener = listener;
            listenersFor(name, true).unshift(onceListener);
            return events;
        },
        setMaxListeners: function (n) {
            maxListeners = Math.max(0, Number(n) || 0);
            return events;
        },
        getMaxListeners: function () { return maxListeners; },
        observeKey: function () { return observeSystemEvent('key'); },
        observeTouch: function () { return observeSystemEvent('touch'); },
        observeNotification: function () { return observeSystemEvent('notification'); },
        observeToast: function () { return observeSystemEvent('toast'); },
        observeGesture: function () { return observeSystemEvent('gesture'); },
        onKeyDown: function (keyName, listener) {
            return events.on('__key_down__#' + String(keyName).toLowerCase(), listener);
        },
        onceKeyDown: function (keyName, listener) {
            return events.once('__key_down__#' + String(keyName).toLowerCase(), listener);
        },
        removeAllKeyDownListeners: function (keyName) {
            return events.removeAllListeners('__key_down__#' + String(keyName).toLowerCase());
        },
        onKeyUp: function (keyName, listener) {
            return events.on('__key_up__#' + String(keyName).toLowerCase(), listener);
        },
        onceKeyUp: function (keyName, listener) {
            return events.once('__key_up__#' + String(keyName).toLowerCase(), listener);
        },
        removeAllKeyUpListeners: function (keyName) {
            return events.removeAllListeners('__key_up__#' + String(keyName).toLowerCase());
        },
        onTouch: function (listener) { return events.on('touch', listener); },
        onNotification: function (listener) { return events.on('notification', listener); },
        onToast: function (listener) { return events.on('toast', listener); },
        setTouchEventTimeout: function (timeout) {
            __aiNativeEventsSetTouchTimeout(Math.max(0, Math.floor(Number(timeout) || 0)));
            return events;
        },
        stopObserving: function () {
            if (systemEventTimer !== null) {
                clearInterval(systemEventTimer);
                systemEventTimer = null;
            }
            __aiNativeEventsStopAll();
            return events;
        },
        broadcast: function (name) {
            return events.emit.apply(events, arguments);
        }
    };

    // ---- shared event bus (cross-engine, incl. workers) ----
    // Payloads travel as JSON strings through the Java bridge; listeners receive
    // the emitted arguments on their own engine thread via a 40ms poll.
    var busListeners = new Map();
    var sharedBusTimer = null;
    function busListenersFor(name, create) {
        name = String(name);
        var listeners = busListeners.get(name);
        if (!listeners && create) {
            listeners = [];
            busListeners.set(name, listeners);
        }
        return listeners;
    }
    function dispatchSharedBus() {
        for (;;) {
            var raw = __aiNativeSharedBusPoll();
            if (!raw) break;
            var item = JSON.parse(raw);
            var listeners = busListenersFor(item.name, false);
            if (listeners && listeners.length) {
                var args = item.args || [];
                listeners.slice().forEach(function (listener) { listener.apply(undefined, args); });
            }
        }
    }
    function ensureSharedBusPolling() {
        if (sharedBusTimer === null) {
            sharedBusTimer = setInterval(dispatchSharedBus, 40);
        }
    }
    var bus = {
        on: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            var listeners = busListenersFor(name, true);
            listeners.push(listener);
            if (listeners.length === 1) __aiNativeSharedBusOn(String(name));
            ensureSharedBusPolling();
            return bus;
        },
        once: function (name, listener) {
            function onceListener() {
                bus.off(name, onceListener);
                return listener.apply(undefined, arguments);
            }
            onceListener.listener = listener;
            return bus.on(name, onceListener);
        },
        off: function (name, listener) {
            var listeners = busListenersFor(name, false);
            if (!listeners) return bus;
            for (var i = listeners.length - 1; i >= 0; i--) {
                if (listeners[i] === listener || listeners[i].listener === listener) {
                    listeners.splice(i, 1);
                }
            }
            if (listeners.length === 0) {
                busListeners.delete(String(name));
                __aiNativeSharedBusOff(String(name));
            }
            return bus;
        },
        removeListener: function (name, listener) { return bus.off(name, listener); },
        removeAllListeners: function (name) {
            if (name === undefined) {
                busListeners.forEach(function (_, n) { __aiNativeSharedBusOff(String(n)); });
                busListeners.clear();
            } else {
                busListeners.delete(String(name));
                __aiNativeSharedBusOff(String(name));
            }
            return bus;
        },
        emit: function (name) {
            var args = Array.prototype.slice.call(arguments, 1);
            var item;
            try {
                item = JSON.stringify({ name: String(name), args: args });
            } catch (error) {
                throw new TypeError('Shared bus payload must be JSON-serializable: ' + error.message);
            }
            return __aiNativeSharedBusEmit(String(name), item);
        },
        listenerCount: function (name) {
            var listeners = busListenersFor(name, false);
            return listeners ? listeners.length : 0;
        }
    };
    events.bus = Object.freeze(bus);

    global.events = Object.freeze(events);

    // ---- media module ----
    var media = {
        getVolume: function () { return Number(__aiNativeMediaGetVolume()); },
        getMaxVolume: function () { return Number(__aiNativeMediaGetMaxVolume()); },
        setVolume: function (volume) {
            return Number(__aiNativeMediaSetVolume(Math.max(0, Math.floor(Number(volume) || 0))));
        },
        playMusic: function (path, volume, looping) {
            return !!__aiNativeMediaPlayMusic(String(path),
                volume === undefined ? 1 : Number(volume), !!looping);
        },
        stopMusic: function () { __aiNativeMediaStopMusic(); },
        pauseMusic: function () { __aiNativeMediaPauseMusic(); },
        resumeMusic: function () { __aiNativeMediaResumeMusic(); },
        isMusicPlaying: function () { return !!__aiNativeMediaIsMusicPlaying(); },
        musicSeekTo: function (positionMs) { __aiNativeMediaMusicSeekTo(Math.max(0, Number(positionMs) || 0)); },
        getMusicDuration: function () { return Number(__aiNativeMediaGetMusicDuration()); },
        getMusicCurrentPosition: function () { return Number(__aiNativeMediaGetMusicCurrentPosition()); },
        scanFile: function (path) { __aiNativeMediaScanFile(String(path)); }
    };
    global.media = Object.freeze(media);

    // ---- sensors module (poll-based event emitter) ----
    function makeSensor(handle, delayMicros) {
        var listeners = { change: [], accuracy: [], accuracy_change: [] };
        var pollMs = delayMicros === 0 ? 10 : Math.max(10, Math.round(delayMicros / 1000));
        var pollTimerId = null;
        var sensor = {
            handle: handle,
            read: function () {
                var json = __aiNativeSensorsRead(handle);
                return json ? JSON.parse(json) : null;
            },
            on: function (name, listener) {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                (listeners[name] = listeners[name] || []).push(listener);
                return sensor;
            },
            once: function (name, listener) {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                function onceListener() {
                    sensor.off(name, onceListener);
                    return listener.apply(undefined, arguments);
                }
                onceListener.listener = listener;
                return sensor.on(name, onceListener);
            },
            off: function (name, listener) {
                var list = listeners[name];
                if (!list) return sensor;
                for (var i = list.length - 1; i >= 0; i--) {
                    if (list[i] === listener || list[i].listener === listener) list.splice(i, 1);
                }
                return sensor;
            },
            unregister: function () {
                if (pollTimerId !== null) {
                    clearInterval(pollTimerId);
                    pollTimerId = null;
                }
                return __aiNativeSensorsUnregister(handle);
            }
        };
        pollTimerId = setInterval(function () {
            var data = sensor.read();
            if (!data) return;
            (listeners.change || []).slice().forEach(function (listener) {
                listener(data);
            });
            var accuracyListeners = (listeners.accuracy || []).concat(listeners.accuracy_change || []);
            accuracyListeners.slice().forEach(function (listener) {
                listener(data.accuracy, data);
            });
        }, pollMs);
        return Object.freeze(sensor);
    }
    var noopSensor = Object.freeze({
        handle: -1,
        read: function () { return null; },
        on: function () { return noopSensor; },
        once: function () { return noopSensor; },
        off: function () { return noopSensor; },
        unregister: function () { return false; }
    });
    global.sensors = Object.freeze({
        ignoresUnsupportedSensor: false,
        register: function (name, delay) {
            var micros = Number(delay);
            if (isNaN(micros)) micros = 200000;
            var handle = Number(__aiNativeSensorsRegister(String(name), Math.floor(micros)));
            if (handle < 0) {
                if (global.sensors.ignoresUnsupportedSensor) return noopSensor;
                return null;
            }
            return makeSensor(handle, micros);
        },
        unregister: function (sensor) {
            if (sensor && typeof sensor.unregister === 'function') return sensor.unregister();
            return false;
        },
        unregisterAll: function () { __aiNativeSensorsUnregisterAll(); },
        list: function () { return JSON.parse(__aiNativeSensorsList()); },
        Delay: Object.freeze({ normal: 200000, ui: 60000, game: 20000, fastest: 0 })
    });

    // ---- dialogs module (blocking; UI shown on the Java main looper) ----
    function dialogWait(id) {
        if (id < 0) throw new Error('Unable to show dialog');
        for (;;) {
            var json = __aiNativeDialogsPoll(id);
            if (json) return JSON.parse(json);
            sleep(60);
        }
    }
    var dialogs = {
        alert: function (title, content) {
            dialogWait(Number(__aiNativeDialogsShow(0,
                String(title == null ? '' : title), String(content == null ? '' : content), '', '')));
        },
        confirm: function (title, content) {
            var r = dialogWait(Number(__aiNativeDialogsShow(1,
                String(title == null ? '' : title), String(content == null ? '' : content), '', '')));
            return r === true;
        },
        rawInput: function (title, prefill) {
            var r = dialogWait(Number(__aiNativeDialogsShow(2,
                String(title == null ? '' : title), String(prefill == null ? '' : prefill), '', '')));
            return r === null ? null : (r && r.value !== undefined ? r.value : '');
        },
        prompt: function (title, prefill) {
            return dialogs.rawInput(title, prefill);
        },
        select: function (title, items) {
            var list = items || [];
            var r = dialogWait(Number(__aiNativeDialogsShow(3,
                String(title == null ? '' : title), '', JSON.stringify(list), '0')));
            return r && r.index !== undefined ? r.index : -1;
        },
        singleChoice: function (title, selectedIndex, items) {
            var list = items || [];
            var idx = Number(selectedIndex) || 0;
            var r = dialogWait(Number(__aiNativeDialogsShow(4,
                String(title == null ? '' : title), '', JSON.stringify(list), String(idx))));
            return r && r.index !== undefined ? r.index : -1;
        },
        multiChoice: function (title, selectedIndices, items) {
            var list = items || [];
            var def = JSON.stringify(selectedIndices || []);
            var r = dialogWait(Number(__aiNativeDialogsShow(5,
                String(title == null ? '' : title), '', JSON.stringify(list), def)));
            return r && r.indices ? r.indices : [];
        },
        build: function (props) {
            var result = JSON.parse(__aiNativeDialogBuild(JSON.stringify(props || {})));
            if (result.action === 'error') throw new Error(result.error || 'dialog error');
            return result;
        }
    };
    global.dialogs = Object.freeze(dialogs);

    // ---- floaty module (overlay windows: xml layout or text + drag + geometry) ----
    var floatyClickHandlers = new Map();
    var floatyPollTimer = null;
    function floatyHandlerKey(windowId, viewId, mode) {
        return windowId + '#' + viewId + '#' + mode;
    }
    function makeFloatyTouchEvent(item) {
        return {
            action: item.action,
            rawX: item.rawX,
            rawY: item.rawY,
            ACTION_DOWN: 0,
            ACTION_UP: 1,
            ACTION_MOVE: 2,
            ACTION_CANCEL: 3,
            getAction: function () { return item.action; },
            getRawX: function () { return item.rawX; },
            getRawY: function () { return item.rawY; }
        };
    }
    function makeFloatyKeyEvent(item) {
        return {
            action: item.action,
            keyCode: item.keyCode,
            keyName: item.keyName,
            consumed: false,
            ACTION_DOWN: 0,
            ACTION_UP: 1,
            getAction: function () { return item.action; },
            getKeyCode: function () { return item.keyCode; },
            getKeyName: function () { return item.keyName; }
        };
    }
    function dispatchFloatyEvents() {
        for (;;) {
            var raw = __aiNativeFloatyViewPoll();
            if (!raw) break;
            var item = JSON.parse(raw);
            var handlers = floatyClickHandlers.get(
                floatyHandlerKey(item.window, item.id, item.event));
            if (!handlers || !handlers.length) continue;
            if (item.event === 'touch') {
                var touchEvent = makeFloatyTouchEvent(item);
                handlers.slice().forEach(function (fn) { fn(touchEvent); });
            } else if (item.event === 'key') {
                var keyEvent = makeFloatyKeyEvent(item);
                handlers.slice().forEach(function (fn) { fn(keyEvent.keyCode, keyEvent); });
            } else {
                handlers.slice().forEach(function (fn) { fn(); });
            }
        }
    }
    function ensureFloatyPolling() {
        if (floatyPollTimer === null) {
            floatyPollTimer = setInterval(dispatchFloatyEvents, 40);
        }
    }
    function addFloatyHandler(windowId, viewId, mode, fn) {
        if (typeof fn !== 'function') throw new TypeError('listener must be a function');
        var key = floatyHandlerKey(windowId, viewId, mode);
        var handlers = floatyClickHandlers.get(key);
        if (!handlers) {
            handlers = [];
            floatyClickHandlers.set(key, handlers);
        }
        handlers.push(fn);
        if (mode === 'touch') {
            __aiNativeFloatyViewTouch(windowId, viewId);
        } else if (mode === 'key') {
            __aiNativeFloatyViewKey(windowId, viewId);
        } else {
            __aiNativeFloatyViewClick(windowId, viewId, mode);
        }
        ensureFloatyPolling();
    }
    function makeFloatyView(windowId, viewId) {
        var view = {
            click: function (fn) {
                addFloatyHandler(windowId, viewId, 'click', fn);
                return view;
            },
            longClick: function (fn) {
                addFloatyHandler(windowId, viewId, 'long_click', fn);
                return view;
            },
            on: function (event, fn) {
                event = String(event);
                if (event === 'click') return view.click(fn);
                if (event === 'long_click' || event === 'longClick') return view.longClick(fn);
                if (event === 'key') return view.onKey(fn);
                throw new Error('Unsupported floaty view event: ' + event);
            },
            onKey: function (fn) {
                addFloatyHandler(windowId, viewId, 'key', fn);
                return view;
            },
            getText: function () { return __aiNativeFloatyViewGetText(windowId, viewId); },
            setText: function (text) {
                __aiNativeFloatyViewSetText(windowId, viewId, String(text == null ? '' : text));
                return view;
            },
            setVisibility: function (visibility) {
                __aiNativeFloatyViewSetVisibility(
                    windowId, viewId, String(Math.round(Number(visibility) || 0)));
                return view;
            },
            setOnTouchListener: function (fn) {
                addFloatyHandler(windowId, viewId, 'touch', fn);
                return view;
            },
            requestFocus: function () {
                __aiNativeFloatyViewRequestFocus(windowId, viewId);
                return view;
            }
        };
        return Object.freeze(view);
    }
    var floaty = {
        window: function (xmlOrConfig, extra) {
            var cfg = {};
            if (typeof xmlOrConfig === 'string') {
                cfg.xml = xmlOrConfig;
            } else if (xmlOrConfig && typeof xmlOrConfig === 'object') {
                for (var k in xmlOrConfig) cfg[k] = xmlOrConfig[k];
            }
            if (extra && typeof extra === 'object') {
                for (var k in extra) cfg[k] = extra[k];
            }
            var id = Number(__aiNativeFloatyCreate(JSON.stringify(cfg)));
            if (id < 0) throw new Error('Unable to create floaty window');
            var onClose = null;
            var exitOnClose = false;
            var viewCache = new Map();
            var win = {
                id: id,
                setSize: function (width, height) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ width: Math.max(0, Number(width) || 0), height: Math.max(0, Number(height) || 0) }));
                },
                setPosition: function (x, y) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ x: Math.round(Number(x) || 0), y: Math.round(Number(y) || 0) }));
                },
                getX: function () { return Number(__aiNativeFloatyGetX(id)); },
                getY: function () { return Number(__aiNativeFloatyGetY(id)); },
                setText: function (text) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ text: String(text == null ? '' : text) }));
                },
                setBackgroundColor: function (color) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ backgroundColor: String(color) }));
                },
                setTouchable: function (touchable) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ touchable: !!touchable }));
                },
                setAdjustEnabled: function (enabled) {
                    __aiNativeFloatySetAdjustable(id, !!enabled);
                    return win;
                },
                isAdjustEnabled: function () { return !!__aiNativeFloatyIsAdjustable(id); },
                requestFocus: function () {
                    __aiNativeFloatySetWindowFocusable(id, true);
                    return win;
                },
                disableFocus: function () {
                    __aiNativeFloatySetWindowFocusable(id, false);
                    return win;
                },
                resize: function (width, height) {
                    win.setSize(width, height);
                },
                onClose: function (fn) {
                    if (typeof fn !== 'function') throw new TypeError('listener must be a function');
                    onClose = fn;
                    return win;
                },
                exitOnClose: function () {
                    exitOnClose = true;
                    return win;
                },
                close: function () {
                    if (onClose) onClose(win);
                    __aiNativeFloatyClose(id);
                    if (exitOnClose && typeof global.exit === 'function') global.exit();
                }
            };
            // window.<id> resolves to a control proxy (click/getText/setText/...).
            return new Proxy(win, {
                get: function (target, prop) {
                    if (typeof prop !== 'string') return undefined;
                    if (prop in target) return target[prop];
                    if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                            || prop === 'toString' || prop === 'constructor') {
                        return undefined;
                    }
                    var cached = viewCache.get(prop);
                    if (!cached) {
                        cached = makeFloatyView(id, prop);
                        viewCache.set(prop, cached);
                    }
                    return cached;
                }
            });
        },
        rawWindow: function (config) {
            return floaty.window(config);
        },
        closeAll: function () {
            __aiNativeFloatyCloseAll();
        }
    };
    global.floaty = Object.freeze(floaty);

    // Key codes for floaty on("key") listeners, mirroring Rhino's keys table.
    global.keys = Object.freeze({
        back: 4,
        home: 3,
        menu: 82,
        enter: 66,
        dpad_center: 23,
        volume_up: 24,
        volume_down: 25,
        power: 26
    });

    // ---- ui module (minimal: DynamicLayoutInflater + fullscreen overlay) ----
    var uiViewId = 0;
    function uiSet(id, config) {
        if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
        __aiNativeUiSetConfig(uiViewId, String(id), JSON.stringify(config || {}));
    }
    function uiReadText(id) {
        if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
        return __aiNativeUiGetText(uiViewId, String(id));
    }
    var uiEventListeners = new Map();
    var uiPollTimer = null;
    function uiDispatchEvents() {
        for (;;) {
            var raw = __aiNativeUiPollEvent();
            if (!raw) break;
            var ev = JSON.parse(raw);
            var entry = uiEventListeners.get(String(ev.id));
            if (entry && entry[ev.event]) {
                var view = uiView(String(ev.id));
                entry[ev.event].slice().forEach(function (fn) {
                    if (ev.event === 'item_click' || ev.event === 'item_long_click') {
                        fn(Number(ev.index), view);
                    } else {
                        fn(view);
                    }
                });
            }
        }
    }
    function uiListen(id, name, fn) {
        if (typeof fn !== 'function') throw new TypeError('listener must be a function');
        id = String(id);
        var entry = uiEventListeners.get(id);
        if (!entry) {
            entry = {};
            uiEventListeners.set(id, entry);
        }
        (entry[name] = entry[name] || []).push(fn);
        if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
        __aiNativeUiSetClickListener(uiViewId, id);
        if (uiPollTimer === null) {
            uiPollTimer = setInterval(uiDispatchEvents, 60);
        }
    }
    function uiView(id) {
        id = String(id);
        return Object.freeze({
            setText: function (text) {
                uiSet(id, { text: String(text == null ? '' : text) });
            },
            getText: function () {
                return uiReadText(id);
            },
            setVisibility: function (visibility) {
                uiSet(id, { visibility: Number(visibility) || 0 });
            },
            setBackgroundColor: function (color) {
                uiSet(id, { backgroundColor: String(color) });
            },
            setDataSource: function (data) {
                if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
                __aiNativeUiSetDataSource(uiViewId, id, JSON.stringify(Array.isArray(data) ? data : []));
            },
            click: function (fn) {
                uiListen(id, 'click', fn);
            },
            on: function (name, fn) {
                uiListen(id, name, fn);
            },
            attr: function (name, value) {
                if (value === undefined) {
                    if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
                    return __aiNativeUiGetAttr(uiViewId, id, String(name));
                }
                var config = {};
                config[String(name)] = value;
                uiSet(id, config);
            }
        });
    }
    var ui = {
        layout: function (xml) {
            var id = Number(__aiNativeUiInflate(String(xml)));
            if (id < 0) throw new Error('Unable to inflate UI layout');
            uiViewId = id;
            return id;
        },
        close: function () {
            __aiNativeUiClose();
            uiViewId = 0;
            uiEventListeners.clear();
            if (uiPollTimer !== null) {
                clearInterval(uiPollTimer);
                uiPollTimer = null;
            }
        },
        setText: function (id, text) { uiSet(id, { text: String(text == null ? '' : text) }); },
        getText: function (id) { return uiReadText(id); },
        setVisibility: function (id, visibility) { uiSet(id, { visibility: Number(visibility) || 0 }); },
        setBackgroundColor: function (id, color) { uiSet(id, { backgroundColor: String(color) }); },
        getAttr: function (id, name) {
            if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
            return __aiNativeUiGetAttr(uiViewId, String(id), String(name));
        },
        run: function (fn) {
            // View updates already hop to the Java main thread internally, so a
            // synchronous call keeps Rhino's ui.run(fn) semantics.
            if (typeof fn === 'function') return fn();
        }
    };
    global.ui = Object.freeze(ui);
    global.$ui = new Proxy({}, {
        get: function (target, name) {
            if (name === 'layout') return ui.layout;
            return uiView(String(name));
        }
    });

    // ---- threads module (one QuickJS engine per worker) ----
    var workerHandles = new Set();
    function makeThread(engine) {
        var thread = {
            getEngine: function () { return engine; },
            interrupt: function () { workerHandles.delete(thread); return engine.forceStop(); },
            isAlive: function () { return !engine.isDestroyed(); },
            join: function (timeout) {
                var deadline = Date.now() + (timeout === undefined ? 0x7fffffff : Math.max(0, Number(timeout)));
                while (!engine.isDestroyed() && Date.now() < deadline) sleep(10);
                if (engine.isDestroyed()) workerHandles.delete(thread);
                return engine.isDestroyed();
            },
            getResult: function () { return engine.getResult(); },
            waitForResult: function (timeout) { return engine.waitForResult(timeout); },
            /**
             * Asynchronous result promise. Resolves with the worker's return
             * value (or undefined), rejects on worker error or timeout.
             */
            promise: function (timeout) {
                var deadline = Date.now()
                    + (timeout === undefined ? 0x7fffffff : Math.max(0, Number(timeout)));
                return new Promise(function (resolve, reject) {
                    function poll() {
                        var result;
                        try { result = engine.getResult(); } catch (error) { reject(error); return; }
                        if (result.status === 'success') { resolve(result.value); return; }
                        if (result.status === 'error') { reject(new Error(result.error || 'Worker failed')); return; }
                        if (Date.now() >= deadline) { reject(new Error('Worker result timeout')); return; }
                        setTimeout(poll, 20);
                    }
                    poll();
                });
            },
            then: function (onFulfilled, onRejected) {
                return this.promise().then(onFulfilled, onRejected);
            }
        };
        return Object.freeze(thread);
    }
    global.threads = Object.freeze({
        /**
         * Start a worker thread. task can be a function (serialized to source)
         * or a script string. args is an optional JSON-serializable object
         * that becomes __args in the child script.
         *
         * Example:
         *   threads.start(function(){ console.log(__args.name); }, { name: 'test' });
         */
        start: function (task, args) {
            var source;
            if (typeof task === 'function') source = '(' + String(task) + ')();';
            else if (typeof task === 'string') source = task;
            else throw new TypeError('threads.start requires a function or script string');
            var argsJson = args !== undefined ? JSON.stringify(args) : '';
            var handle = Number(__aiNativeThreadsExec('QuickJS-Thread', source, argsJson));
            if (handle < 0) throw new Error('Unable to start worker thread');
            var engine = makeEngineHandle({ handle: handle, source: 'QuickJS-Thread' });
            var thread = makeThread(engine);
            workerHandles.add(thread);
            return thread;
        },
        /**
         * Explicitly named worker with JSON args. Returns a thread object.
         *
         * Example:
         *   var t = threads.exec('worker1', 'console.log(__args)', { count: 5 });
         *   t.join(5000);
         */
        exec: function (name, source, args) {
            if (typeof source !== 'string') throw new TypeError('threads.exec requires a script string');
            var argsJson = args !== undefined ? JSON.stringify(args) : '';
            var handle = Number(__aiNativeThreadsExec(
                    String(name || 'worker'), source, argsJson));
            if (handle < 0) throw new Error('Unable to start worker thread');
            var engine = makeEngineHandle({ handle: handle, source: String(name || 'worker') });
            var thread = makeThread(engine);
            workerHandles.add(thread);
            return thread;
        },
        currentThread: function () { return makeThread(global.engines.myEngine()); },
        shutDownAll: function () {
            workerHandles.forEach(function (thread) { thread.interrupt(); });
            workerHandles.clear();
        }
    });

    // ---- Selector / UiObject (Auto.js 4.x compatible, backed by the Java UiSelector) ----
    // Everything below is a thin wrapper over __aiNativeAutomatorCall: the Java side owns the
    // selector/UiObject/UiObjectCollection instances behind long handles and only exposes the
    // whitelisted method names, so no Java object ever reaches the script.
    function automatorCall(handle, method, args) {
        return __aiNativeAutomatorCall(handle, method, JSON.stringify(args === undefined ? [] : args));
    }

    function wrapAutomatorRect(value) {
        var rect = { left: value.left, top: value.top, right: value.right, bottom: value.bottom };
        rect.width = function () { return rect.right - rect.left; };
        rect.height = function () { return rect.bottom - rect.top; };
        rect.centerX = function () { return Math.floor((rect.left + rect.right) / 2); };
        rect.centerY = function () { return Math.floor((rect.top + rect.bottom) / 2); };
        rect.toString = function () {
            return 'Rect(' + rect.left + ', ' + rect.top + ' - ' + rect.right + ', ' + rect.bottom + ')';
        };
        return rect;
    }

    function automatorHandleOf(value) {
        if (value === null || value === undefined) return null;
        if (typeof value === 'number') return value;
        if (value.__handle !== undefined) return value.__handle;
        throw new TypeError('expects a selector or UiObject');
    }

    function decodeAutomatorResult(text) {
        var result = JSON.parse(text);
        switch (result.t) {
            case 'b': return result.v === true;
            case 'i': return result.v;
            case 's': return result.v;
            case 'n': return null;
            case 'r': return wrapAutomatorRect(result.v);
            case 'h':
                return result.k === 'c' ? wrapAutomatorCollection(result.v) : wrapAutomatorObject(result.v);
            default: return undefined;
        }
    }

    var AUTOMATOR_STRING_FILTERS = ['text', 'textContains', 'textStartsWith', 'textEndsWith', 'textMatches',
        'id', 'idContains', 'idStartsWith', 'idEndsWith', 'idMatches',
        'desc', 'descContains', 'descStartsWith', 'descEndsWith', 'descMatches',
        'className', 'classNameContains', 'classNameStartsWith', 'classNameEndsWith', 'classNameMatches',
        'packageName', 'packageNameContains', 'packageNameStartsWith', 'packageNameEndsWith',
        'packageNameMatches', 'algorithm'];
    var AUTOMATOR_INT_FILTERS = ['drawingOrder', 'depth', 'row', 'rowCount', 'rowSpan', 'column',
        'columnCount', 'columnSpan', 'indexInParent'];
    var AUTOMATOR_BOOL_FILTERS = ['checkable', 'checked', 'focusable', 'focused', 'visibleToUser',
        'accessibilityFocused', 'selected', 'clickable', 'longClickable', 'enabled', 'password', 'scrollable',
        'editable', 'contentInvalid', 'contextClickable', 'multiLine', 'dismissable'];
    var AUTOMATOR_BOUNDS_FILTERS = ['bounds', 'boundsInside', 'boundsContains'];
    var AUTOMATOR_NODE_ACTIONS = ['click', 'longClick', 'accessibilityFocus', 'clearAccessibilityFocus',
        'focus', 'clearFocus', 'copy', 'paste', 'select', 'cut', 'collapse', 'expand', 'dismiss', 'show',
        'scrollForward', 'scrollBackward', 'scrollUp', 'scrollDown', 'scrollLeft', 'scrollRight', 'contextClick'];
    var AUTOMATOR_STRING_PROPERTIES = ['text', 'desc', 'id', 'className', 'packageName'];
    var AUTOMATOR_INT_PROPERTIES = ['depth', 'drawingOrder', 'indexInParent', 'childCount', 'row', 'column',
        'rowSpan', 'columnSpan', 'rowCount', 'columnCount'];
    var AUTOMATOR_BOOL_PROPERTIES = ['checkable', 'checked', 'focusable', 'focused', 'visibleToUser',
        'accessibilityFocused', 'selected', 'clickable', 'longClickable', 'enabled', 'password', 'scrollable'];

    function wrapAutomatorSelector(handle) {
        var selector = { __handle: handle };
        AUTOMATOR_STRING_FILTERS.forEach(function (name) {
            selector[name] = function (value) {
                automatorCall(handle, name, [String(value)]);
                return selector;
            };
        });
        AUTOMATOR_INT_FILTERS.forEach(function (name) {
            selector[name] = function (value) {
                automatorCall(handle, name, [Number(value)]);
                return selector;
            };
        });
        AUTOMATOR_BOOL_FILTERS.forEach(function (name) {
            // Both call styles are supported: clickable() means "nodes that are clickable",
            // clickable(true) filters on the flag value.
            selector[name] = function (value) {
                automatorCall(handle, name, arguments.length === 0 ? [] : [value === true]);
                return selector;
            };
        });
        AUTOMATOR_BOUNDS_FILTERS.forEach(function (name) {
            selector[name] = function (left, top, right, bottom) {
                automatorCall(handle, name, [left, top, right, bottom]);
                return selector;
            };
        });
        // A selector can run actions directly (it waits for the first match first), just like
        // the Rhino UiSelector does.
        AUTOMATOR_NODE_ACTIONS.forEach(function (name) {
            selector[name] = function () { return decodeAutomatorResult(automatorCall(handle, name)); };
        });
        selector.setText = function (text) {
            return decodeAutomatorResult(automatorCall(handle, 'setText', [String(text)]));
        };
        selector.setSelection = function (start, end) {
            return decodeAutomatorResult(automatorCall(handle, 'setSelection', [start, end]));
        };
        selector.setProgress = function (value) {
            return decodeAutomatorResult(automatorCall(handle, 'setProgress', [Number(value)]));
        };
        selector.scrollTo = function (row, column) {
            return decodeAutomatorResult(automatorCall(handle, 'scrollTo', [row, column]));
        };
        selector.find = function () { return decodeAutomatorResult(automatorCall(handle, 'find')); };
        selector.untilFind = function () { return decodeAutomatorResult(automatorCall(handle, 'untilFind')); };
        selector.findOnce = function (index) {
            return decodeAutomatorResult(automatorCall(handle, 'findOnce',
                index === undefined ? [] : [index]));
        };
        selector.findOne = function (timeout) {
            return decodeAutomatorResult(automatorCall(handle, 'findOne',
                timeout === undefined ? [] : [timeout]));
        };
        selector.untilFindOne = function () { return decodeAutomatorResult(automatorCall(handle, 'untilFindOne')); };
        selector.exists = function () { return decodeAutomatorResult(automatorCall(handle, 'exists')); };
        selector.waitFor = function () { automatorCall(handle, 'waitFor'); };
        selector.findOf = function (node, max) {
            var target = automatorHandleOf(node);
            if (target === null) throw new TypeError('findOf(node) expects a UiObject');
            return decodeAutomatorResult(automatorCall(handle, 'findOf',
                max === undefined ? [target] : [target, max]));
        };
        selector.findOneOf = function (node) {
            var target = automatorHandleOf(node);
            if (target === null) throw new TypeError('findOneOf(node) expects a UiObject');
            return decodeAutomatorResult(automatorCall(handle, 'findOneOf', [target]));
        };
        selector.toString = function () {
            var text = decodeAutomatorResult(automatorCall(handle, 'toString'));
            return text === undefined || text === null ? 'Selector' : String(text);
        };
        return selector;
    }

    function wrapAutomatorObject(handle) {
        var object = { __handle: handle };
        AUTOMATOR_STRING_PROPERTIES.concat(AUTOMATOR_INT_PROPERTIES, AUTOMATOR_BOOL_PROPERTIES,
            AUTOMATOR_NODE_ACTIONS).forEach(function (name) {
            object[name] = function () { return decodeAutomatorResult(automatorCall(handle, name)); };
        });
        object.bounds = function () { return decodeAutomatorResult(automatorCall(handle, 'bounds')); };
        object.boundsInParent = function () {
            return decodeAutomatorResult(automatorCall(handle, 'boundsInParent'));
        };
        object.setText = function (text) {
            return decodeAutomatorResult(automatorCall(handle, 'setText', [String(text)]));
        };
        object.setSelection = function (start, end) {
            return decodeAutomatorResult(automatorCall(handle, 'setSelection', [start, end]));
        };
        object.setProgress = function (value) {
            return decodeAutomatorResult(automatorCall(handle, 'setProgress', [Number(value)]));
        };
        object.scrollTo = function (row, column) {
            return decodeAutomatorResult(automatorCall(handle, 'scrollTo', [row, column]));
        };
        object.parent = function () { return decodeAutomatorResult(automatorCall(handle, 'parent')); };
        object.child = function (index) {
            return decodeAutomatorResult(automatorCall(handle, 'child', [index]));
        };
        object.children = function () { return decodeAutomatorResult(automatorCall(handle, 'children')); };
        object.find = function (selector) {
            return decodeAutomatorResult(automatorCall(handle, 'find', [automatorHandleOf(selector)]));
        };
        object.findOne = function (selector) {
            return decodeAutomatorResult(automatorCall(handle, 'findOne', [automatorHandleOf(selector)]));
        };
        object.recycle = function () { /* handles are released with the engine */ };
        object.toString = function () {
            var text = decodeAutomatorResult(automatorCall(handle, 'toString'));
            return text === undefined || text === null ? 'UiObject' : String(text);
        };
        return object;
    }

    function wrapAutomatorCollection(handle) {
        var collection = { __handle: handle };
        collection.size = function () { return decodeAutomatorResult(automatorCall(handle, 'size')); };
        collection.get = function (index) {
            return decodeAutomatorResult(automatorCall(handle, 'get', [index]));
        };
        collection.empty = function () { return decodeAutomatorResult(automatorCall(handle, 'empty')); };
        collection.nonEmpty = function () { return decodeAutomatorResult(automatorCall(handle, 'nonEmpty')); };
        AUTOMATOR_NODE_ACTIONS.forEach(function (name) {
            collection[name] = function () { return decodeAutomatorResult(automatorCall(handle, name)); };
        });
        collection.setText = function (text) {
            return decodeAutomatorResult(automatorCall(handle, 'setText', [String(text)]));
        };
        collection.setSelection = function (start, end) {
            return decodeAutomatorResult(automatorCall(handle, 'setSelection', [start, end]));
        };
        collection.setProgress = function (value) {
            return decodeAutomatorResult(automatorCall(handle, 'setProgress', [Number(value)]));
        };
        collection.scrollTo = function (row, column) {
            return decodeAutomatorResult(automatorCall(handle, 'scrollTo', [row, column]));
        };
        collection.find = function (selector) {
            return decodeAutomatorResult(automatorCall(handle, 'find', [automatorHandleOf(selector)]));
        };
        collection.findOne = function (selector) {
            return decodeAutomatorResult(automatorCall(handle, 'findOne', [automatorHandleOf(selector)]));
        };
        collection.each = function (callback) {
            var count = collection.size();
            for (var i = 0; i < count; i++) {
                var item = collection.get(i);
                if (item !== null && item !== undefined) callback(item, i);
            }
            return collection;
        };
        collection.forEach = function (callback) {
            var count = collection.size();
            for (var i = 0; i < count; i++) {
                callback(collection.get(i), i);
            }
            return collection;
        };
        collection.toArray = function () {
            var count = collection.size();
            var items = [];
            for (var i = 0; i < count; i++) items.push(collection.get(i));
            return items;
        };
        collection.toString = function () {
            var text = decodeAutomatorResult(automatorCall(handle, 'toString'));
            return text === undefined || text === null ? 'UiObjectCollection' : String(text);
        };
        return collection;
    }

    global.selector = function () { return wrapAutomatorSelector(Number(__aiNativeSelectorCreate())); };
    AUTOMATOR_STRING_FILTERS.concat(AUTOMATOR_INT_FILTERS, AUTOMATOR_BOOL_FILTERS,
        AUTOMATOR_BOUNDS_FILTERS).forEach(function (name) {
        global[name] = function () {
            var selector = global.selector();
            return selector[name].apply(selector, arguments);
        };
    });
    // Rhino copies every selector method into the global scope as well; mirror the ones that
    // are not already taken by gesture/clipboard globals so older scripts keep working.
    // `select` is deliberately NOT mirrored: Rhino's global `select` is the selector action,
    // but the Auto.js 4.x documented meaning is the dialog, which is what QuickJS exposes.
    ['find', 'findOnce', 'findOne', 'untilFind', 'untilFindOne', 'exists', 'waitFor', 'findOf',
        'findOneOf', 'accessibilityFocus', 'clearAccessibilityFocus', 'focus', 'clearFocus', 'copy',
        'cut', 'paste', 'collapse', 'expand', 'dismiss', 'show', 'contextClick', 'scrollForward',
        'scrollBackward', 'scrollUp', 'scrollDown', 'scrollLeft', 'scrollRight', 'scrollTo',
        'setText', 'setSelection', 'setProgress'].forEach(function (name) {
        global[name] = function () {
            var selector = global.selector();
            return selector[name].apply(selector, arguments);
        };
    });

    // ---- app / 文件快捷别名（Auto.js 4.x 顶层函数）----
    ['launch', 'launchApp', 'launchPackage', 'openAppSetting', 'getAppName', 'getPackageName']
        .forEach(function (name) {
            if (typeof global.app[name] !== 'function') return;
            global[name] = function () { return global.app[name].apply(global.app, arguments); };
        });
    global.open = function (path) { return global.app.viewFile(path); };

    // ---- Top level Auto.js 4.x aliases ----
    global.print = global.console.log;
    global.err = global.console.error;

    ['alert', 'confirm', 'prompt', 'select', 'singleChoice', 'multiChoice'].forEach(function (name) {
        if (typeof global.dialogs[name] !== 'function') return;
        global[name] = function () { return global.dialogs[name].apply(global.dialogs, arguments); };
    });

    global.random = function (min, max) {
        if (arguments.length === 0) return Math.random();
        return Math.floor(Math.random() * (max - min + 1)) + min;
    };

    // Rhino wraps the function in a Rhino Synchronizer; QuickJS engines are single threaded and
    // workers do not share JS objects, so the lock degrades to a plain wrapper that stays callable.
    global.sync = function (func) {
        if (typeof func !== 'function') throw new TypeError('sync(func) requires a function');
        return function () { return func.apply(this, arguments); };
    };

    global.setImmediate = function (callback) { return setTimeout(callback, 0); };
    global.clearImmediate = function (id) { return clearTimeout(id); };
    global.timers = Object.freeze({
        setTimeout: global.setTimeout,
        setInterval: global.setInterval,
        clearTimeout: global.clearTimeout,
        clearInterval: global.clearInterval,
        setImmediate: global.setImmediate,
        clearImmediate: global.clearImmediate
    });

    global.waitForActivity = function (activity, period) {
        period = period || 200;
        while (global.currentActivity() !== activity) sleep(period);
    };
    global.waitForPackage = function (packageName, period) {
        period = period || 200;
        while (global.currentPackage() !== packageName) sleep(period);
    };

    var AUTO_MODES = { normal: 0, fast: 1 };
    var AUTO_FLAGS = { findOnUiThread: 1, useUsageStats: 2, useShell: 4 };
    var AUTO_SERVICE = Object.freeze({});

    function autoCall(method, value) {
        return JSON.parse(__aiNativeAutoCall(method, value === undefined ? 0 : value));
    }

    var auto = function (mode) {
        if (mode) global.auto.setMode(mode);
        autoCall('ensure');
    };
    auto.waitFor = function () { autoCall('waitFor'); };
    auto.setMode = function (modeStr) {
        if (typeof modeStr !== 'string') throw new TypeError('mode should be a string');
        var mode = AUTO_MODES[modeStr];
        if (mode === undefined) throw new Error('unknown mode for auto.setMode(): ' + modeStr);
        autoCall('setMode', mode);
    };
    auto.setFlags = function (flags) {
        var names = Array.isArray(flags) ? flags : Array.prototype.slice.call(arguments);
        var value = 0;
        names.forEach(function (name) {
            var flag = AUTO_FLAGS[name];
            if (flag === undefined) throw new Error('unknown flag for auto.setFlags(): ' + name);
            value |= flag;
        });
        autoCall('setFlags', value);
    };
    Object.defineProperty(auto, 'service', {
        // Rhino 返回 AccessibilityService 对象；白名单桥不给脚本 Java 对象，
        // 这里用空对象 / null 保留「判空」语义。
        get: function () { return autoCall('serviceReady').v === true ? AUTO_SERVICE : null; }
    });
    Object.defineProperty(auto, 'root', {
        get: function () { return decodeAutomatorResult(__aiNativeAutoCall('rootCurrent', 0)); }
    });
    Object.defineProperty(auto, 'rootInActiveWindow', {
        get: function () { return decodeAutomatorResult(__aiNativeAutoCall('rootActive', 0)); }
    });
    global.auto = auto;

    var engineInfo = { name: 'QuickJS', version: '2026-06-04', native: true };
    global.__engine__ = Object.freeze(engineInfo);

    // Node.js compatible global alias
    global.global = global;

    // Rhino-style exit(): ends the script as a normal completion.
    global.exit = function () {
        __aiNativeExitSelf();
        throw new Error('__AIJS_EXIT__');
    };

    // ---- CommonJS module system (require) ----
    var moduleCache = new Map();

    function moduleFilename(request, fromDir) {
        request = String(request);
        if (request === '') throw new Error('Cannot require an empty module');
        var p;
        if (request.charAt(0) === '/') {
            p = request;
        } else if (fromDir && request.charAt(0) === '.') {
            p = fromDir + '/' + request;
        } else {
            p = files.path(request);
        }
        if (!/\.js$/.test(p)) {
            var alt = p + '.js';
            if (files.exists(alt)) p = alt;
        }
        if (!files.isFile(p)) throw new Error('Cannot find module: ' + request + ' (resolved: ' + p + ')');
        return p;
    }

    function loadModule(filename, fromDir) {
        if (moduleCache.has(filename)) return moduleCache.get(filename).exports;
        var source = files.read(filename);
        var module = { id: filename, filename: filename, exports: {}, loaded: false };
        var dirname = filename.lastIndexOf('/') >= 0
            ? filename.substring(0, filename.lastIndexOf('/'))
            : '.';
        moduleCache.set(filename, module);
        var localRequire = function (request) {
            return loadModule(moduleFilename(request, dirname), dirname);
        };
        localRequire.resolve = function (request) { return moduleFilename(request, dirname); };
        localRequire.cache = moduleCache;
        var factory = new Function('module', 'exports', 'require', '__filename', '__dirname',
            source + '\n//# sourceURL=' + filename);
        factory(module, module.exports, localRequire, filename, dirname);
        module.loaded = true;
        return module.exports;
    }

    global.require = function (request) { return loadModule(moduleFilename(request, null), null); };
    global.__moduleCache = moduleCache;

    // ---- runtime info (Auto.js compatible surface) ----
    global.runtime = Object.freeze({
        global: globalThis,
        engine: engineInfo,
        engines: global.engines,
        platform: 'android',
        cwd: function () { return files.cwd(); },
        sdcard: function () { return files.getSdcardPath(); }
    });
})(globalThis);
)JS";

jobject resultToJava(JNIEnv *env, JSContext *context, JSValueConst result) {
    if (JS_IsUndefined(result) || JS_IsNull(result)) {
        return nullptr;
    }
    if (JS_IsBool(result)) {
        jclass booleanClass = env->FindClass("java/lang/Boolean");
        jmethodID valueOf = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
        jobject value = env->CallStaticObjectMethod(booleanClass, valueOf, JS_ToBool(context, result) ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(booleanClass);
        return value;
    }
    if (JS_IsNumber(result)) {
        double number = 0;
        JS_ToFloat64(context, &number, result);
        jclass doubleClass = env->FindClass("java/lang/Double");
        jmethodID valueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
        jobject value = env->CallStaticObjectMethod(doubleClass, valueOf, number);
        env->DeleteLocalRef(doubleClass);
        return value;
    }

    JSValue converted = JS_DupValue(context, result);
    if (JS_IsObject(result)) {
        JSValue json = JS_JSONStringify(context, result, JS_UNDEFINED, JS_UNDEFINED);
        const bool jsonFailed = JS_IsException(json);
        if (!jsonFailed && !JS_IsUndefined(json)) {
            JS_FreeValue(context, converted);
            converted = json;
        } else {
            JS_FreeValue(context, json);
            if (jsonFailed) {
                JSValue ignored = JS_GetException(context);
                JS_FreeValue(context, ignored);
            }
        }
    }
    const std::string text = jsString(context, converted);
    JS_FreeValue(context, converted);
    return toJavaString(env, text);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_create(
        JNIEnv *env, jclass, jobject hostBridge, jlong memoryLimitBytes, jlong stackLimitBytes) {
    auto state = std::make_shared<EngineState>();
    env->GetJavaVM(&state->vm);
    state->host = env->NewGlobalRef(hostBridge);
    state->runtime = JS_NewRuntime();
    if (state->runtime == nullptr) {
        env->DeleteGlobalRef(state->host);
        throwQuickJs(env, "Unable to allocate QuickJS runtime");
        return 0;
    }
    JS_SetMemoryLimit(state->runtime, static_cast<size_t>(std::max<jlong>(memoryLimitBytes, 1024 * 1024)));
    JS_SetMaxStackSize(state->runtime, static_cast<size_t>(std::max<jlong>(stackLimitBytes, 256 * 1024)));
    JS_SetInterruptHandler(state->runtime, interruptHandler, state.get());
    state->context = JS_NewContext(state->runtime);
    if (state->context == nullptr) {
        JS_FreeRuntime(state->runtime);
        env->DeleteGlobalRef(state->host);
        throwQuickJs(env, "Unable to allocate QuickJS context");
        return 0;
    }
    JS_SetContextOpaque(state->context, state.get());

    JSValue global = JS_GetGlobalObject(state->context);
    installNativeFunction(state->context, global, "__aiNativeLog", nativeLog, 2);
    installNativeFunction(state->context, global, "__aiNativePerformanceNow", nativePerformanceNow, 0);
    installNativeFunction(state->context, global, "__aiNativeToast", nativeToast, 1);
    installNativeFunction(state->context, global, "__aiNativeSleep", nativeSleep, 1);
    installNativeFunction(state->context, global, "__aiNativeClick", nativeClick, 2);
    installNativeFunction(state->context, global, "__aiNativePress", nativePress, 3);
    installNativeFunction(state->context, global, "__aiNativeLongClick", nativeLongClick, 2);
    installNativeFunction(state->context, global, "__aiNativeSwipe", nativeSwipe, 5);
    installNativeFunction(state->context, global, "__aiNativeGlobalAction", nativeGlobalAction, 1);
    installNativeFunction(state->context, global, "__aiNativeSetClip", nativeSetClip, 1);
    installNativeFunction(state->context, global, "__aiNativeGetClip", nativeGetClip, 0);
    installNativeFunction(state->context, global, "__aiNativeForegroundInfo", nativeForegroundInfo, 1);
    installNativeFunction(state->context, global, "__aiNativeSetScreenMetrics", nativeSetScreenMetrics, 2);
    installNativeFunction(state->context, global, "__aiNativeAutoCall", nativeAutoCall, 2);
    installNativeFunction(state->context, global, "__aiNativeSelectorCreate", nativeSelectorCreate, 0);
    installNativeFunction(state->context, global, "__aiNativeAutomatorCall", nativeAutomatorCall, 3);
    installNativeFunction(state->context, global, "__aiNativeRequestScreenCapture", nativeRequestScreenCapture, 1);
    installNativeFunction(state->context, global, "__aiNativeCaptureFrame", nativeCaptureFrame, 3);
    installNativeFunction(state->context, global, "__aiNativeReadFrame", nativeReadFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeFrameFromBase64", nativeFrameFromBase64, 1);
    installNativeFunction(state->context, global, "__aiNativeFrameConcat", nativeFrameConcat, 3);
    installNativeFunction(state->context, global, "__aiNativeCopyFrame", nativeCopyFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeClipFrame", nativeClipFrame, 5);
    installNativeFunction(state->context, global, "__aiNativeResizeFrame", nativeResizeFrame, 4);
    installNativeFunction(state->context, global, "__aiNativeGrayscaleFrame", nativeGrayscaleFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeCvtColorFrame", nativeCvtColorFrame, 2);
    installNativeFunction(state->context, global, "__aiNativeRotateFrame", nativeRotateFrame, 2);
    installNativeFunction(state->context, global, "__aiNativeThresholdFrame", nativeThresholdFrame, 4);
    installNativeFunction(state->context, global, "__aiNativeBlurFrame", nativeBlurFrame, 2);
    installNativeFunction(state->context, global, "__aiNativeSaveFrame", nativeSaveFrame, 4);
    installNativeFunction(state->context, global, "__aiNativeCompressFrame", nativeCompressFrame, 3);
    installNativeFunction(state->context, global, "__aiNativeReleaseFrame", nativeReleaseFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeFrameStats", nativeFrameStats, 0);
    installNativeFunction(state->context, global, "__aiNativeFramePixel", nativeFramePixel, 3);
    installNativeFunction(state->context, global, "__aiNativeFindColor", nativeFindColor, 7);
    installNativeFunction(state->context, global, "__aiNativeFindMultiColors", nativeFindMultiColors, 8);
    installNativeFunction(state->context, global, "__aiNativeFindImage", nativeFindImage, 7);
    installNativeFunction(state->context, global, "__aiNativeMatchTemplate", nativeMatchTemplate, 8);
    installNativeFunction(state->context, global, "__aiNativeYoloIsAvailable", nativeYoloIsAvailable, 0);
    installNativeFunction(state->context, global, "__aiNativeYoloVersion", nativeYoloVersion, 0);
    installNativeFunction(state->context, global, "__aiNativeYoloUnavailableReason", nativeYoloUnavailableReason, 0);
    installNativeFunction(state->context, global, "__aiNativeYoloLoad", nativeYoloLoad, 4);
    installNativeFunction(state->context, global, "__aiNativeYoloReadLabels", nativeYoloReadLabels, 1);
    installNativeFunction(state->context, global, "__aiNativeYoloDetect", nativeYoloDetect, 4);
    installNativeFunction(state->context, global, "__aiNativeYoloClose", nativeYoloClose, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesExists", nativeFilesExists, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesIsFile", nativeFilesIsFile, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesIsDir", nativeFilesIsDir, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesRead", nativeFilesRead, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesWrite", nativeFilesWrite, 2);
    installNativeFunction(state->context, global, "__aiNativeFilesAppend", nativeFilesAppend, 2);
    installNativeFunction(state->context, global, "__aiNativeFilesCreate", nativeFilesCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesEnsureDir", nativeFilesEnsureDir, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesListDir", nativeFilesListDir, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesRemove", nativeFilesRemove, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesRename", nativeFilesRename, 2);
    installNativeFunction(state->context, global, "__aiNativeFilesCopy", nativeFilesCopy, 2);
    installNativeFunction(state->context, global, "__aiNativeFilesMove", nativeFilesMove, 2);
    installNativeFunction(state->context, global, "__aiNativeFilesCwd", nativeFilesCwd, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaGetVolume", nativeMediaGetVolume, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaGetMaxVolume", nativeMediaGetMaxVolume, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaSetVolume", nativeMediaSetVolume, 1);
    installNativeFunction(state->context, global, "__aiNativeMediaPlayMusic", nativeMediaPlayMusic, 3);
    installNativeFunction(state->context, global, "__aiNativeMediaStopMusic", nativeMediaStopMusic, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaPauseMusic", nativeMediaPauseMusic, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaResumeMusic", nativeMediaResumeMusic, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaIsMusicPlaying", nativeMediaIsMusicPlaying, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaMusicSeekTo", nativeMediaMusicSeekTo, 1);
    installNativeFunction(state->context, global, "__aiNativeMediaGetMusicDuration", nativeMediaGetMusicDuration, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaGetMusicCurrentPosition", nativeMediaGetMusicCurrentPosition, 0);
    installNativeFunction(state->context, global, "__aiNativeMediaScanFile", nativeMediaScanFile, 1);
    installNativeFunction(state->context, global, "__aiNativeSensorsRegister", nativeSensorsRegister, 2);
    installNativeFunction(state->context, global, "__aiNativeSensorsRead", nativeSensorsRead, 1);
    installNativeFunction(state->context, global, "__aiNativeSensorsUnregister", nativeSensorsUnregister, 1);
    installNativeFunction(state->context, global, "__aiNativeSensorsUnregisterAll", nativeSensorsUnregisterAll, 0);
    installNativeFunction(state->context, global, "__aiNativeSensorsList", nativeSensorsList, 0);
    installNativeFunction(state->context, global, "__aiNativeEventsObserve", nativeEventsObserve, 1);
    installNativeFunction(state->context, global, "__aiNativeEventsPoll", nativeEventsPoll, 0);
    installNativeFunction(state->context, global, "__aiNativeEventsSetTouchTimeout", nativeEventsSetTouchTimeout, 1);
    installNativeFunction(state->context, global, "__aiNativeEventsStopAll", nativeEventsStopAll, 0);
    installNativeFunction(state->context, global, "__aiNativeDialogsShow", nativeDialogsShow, 5);
    installNativeFunction(state->context, global, "__aiNativeDialogsPoll", nativeDialogsPoll, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyCreate", nativeFloatyCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyUpdate", nativeFloatyUpdate, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyClose", nativeFloatyClose, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyCloseAll", nativeFloatyCloseAll, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewGetText", nativeFloatyViewGetText, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewSetText", nativeFloatyViewSetText, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewSetVisibility", nativeFloatyViewSetVisibility, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewClick", nativeFloatyViewClick, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewPoll", nativeFloatyViewPoll, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatySetAdjustable", nativeFloatySetAdjustable, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyIsAdjustable", nativeFloatyIsAdjustable, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetX", nativeFloatyGetX, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetY", nativeFloatyGetY, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewTouch", nativeFloatyViewTouch, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewKey", nativeFloatyViewKey, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatySetWindowFocusable", nativeFloatySetWindowFocusable, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewRequestFocus", nativeFloatyViewRequestFocus, 2);
    installNativeFunction(state->context, global, "__aiNativeExitSelf", nativeExitSelf, 0);
    installNativeFunction(state->context, global, "__aiNativeUiInflate", nativeUiInflate, 1);
    installNativeFunction(state->context, global, "__aiNativeUiClose", nativeUiClose, 0);
    installNativeFunction(state->context, global, "__aiNativeUiSetConfig", nativeUiSetConfig, 3);
    installNativeFunction(state->context, global, "__aiNativeUiGetText", nativeUiGetText, 2);
    installNativeFunction(state->context, global, "__aiNativeUiSetClickListener", nativeUiSetClickListener, 2);
    installNativeFunction(state->context, global, "__aiNativeUiSetDataSource", nativeUiSetDataSource, 3);
    installNativeFunction(state->context, global, "__aiNativeUiPollEvent", nativeUiPollEvent, 0);
    installNativeFunction(state->context, global, "__aiNativeUiGetAttr", nativeUiGetAttr, 3);
    installNativeFunction(state->context, global, "__aiNativeFilesGetSdcardPath", nativeFilesGetSdcardPath, 0);
    installNativeFunction(state->context, global, "__aiNativeFilesPath", nativeFilesPath, 1);
    installNativeFunction(state->context, global, "__aiNativeSetTimeout", nativeSetTimeout, 2);
    installNativeFunction(state->context, global, "__aiNativeSetInterval", nativeSetInterval, 2);
    installNativeFunction(state->context, global, "__aiNativeClearTimer", nativeClearTimer, 1);
    installNativeFunction(state->context, global, "__aiNativeHttpRequest", nativeHttpRequest, 5);
    installNativeFunction(state->context, global, "__aiNativeDrawCreate", nativeDrawCreate, 0);
    installNativeFunction(state->context, global, "__aiNativeDrawUpdate", nativeDrawUpdate, 2);
    installNativeFunction(state->context, global, "__aiNativeDrawClose", nativeDrawClose, 0);
    installNativeFunction(state->context, global, "__aiNativeAppLaunch", nativeAppLaunch, 1);
    installNativeFunction(state->context, global, "__aiNativeAppOpenUrl", nativeAppOpenUrl, 1);
    installNativeFunction(state->context, global, "__aiNativeAppGetInstalledApps", nativeAppGetInstalledApps, 0);
    installNativeFunction(state->context, global, "__aiNativeAppGetAppInfo", nativeAppGetAppInfo, 1);
    installNativeFunction(state->context, global, "__aiNativeStorageCreate", nativeStorageCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeStoragePut", nativeStoragePut, 3);
    installNativeFunction(state->context, global, "__aiNativeStorageGet", nativeStorageGet, 3);
    installNativeFunction(state->context, global, "__aiNativeStorageRemove", nativeStorageRemove, 2);
    installNativeFunction(state->context, global, "__aiNativeStorageContains", nativeStorageContains, 2);
    installNativeFunction(state->context, global, "__aiNativeStorageClear", nativeStorageClear, 1);
    installNativeFunction(state->context, global, "__aiNativeDeviceInfo", nativeDeviceInfo, 1);
    installNativeFunction(state->context, global, "__aiNativeDeviceIsScreenOn", nativeDeviceIsScreenOn, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceVibrate", nativeDeviceVibrate, 1);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetBattery", nativeDeviceGetBattery, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetAvailMem", nativeDeviceGetAvailMem, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetTotalMem", nativeDeviceGetTotalMem, 0);
    installNativeFunction(state->context, global, "__aiNativeAppGetPackageName", nativeAppGetPackageName, 1);
    installNativeFunction(state->context, global, "__aiNativeAppGetAppName", nativeAppGetAppName, 1);
    installNativeFunction(state->context, global, "__aiNativeAppOpenAppSetting", nativeAppOpenAppSetting, 1);
    installNativeFunction(state->context, global, "__aiNativeAppViewFile", nativeAppViewFile, 1);
    installNativeFunction(state->context, global, "__aiNativeAppEditFile", nativeAppEditFile, 1);
    installNativeFunction(state->context, global, "__aiNativeAppUninstall", nativeAppUninstall, 1);
    installNativeFunction(state->context, global, "__aiNativeAppStartActivity", nativeAppStartActivity, 7);
    installNativeFunction(state->context, global, "__aiNativeDeviceIsCharging", nativeDeviceIsCharging, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetBrightness", nativeDeviceGetBrightness, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetBrightnessMode", nativeDeviceGetBrightnessMode, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceCancelVibration", nativeDeviceCancelVibration, 0);
    installNativeFunction(state->context, global, "__aiNativeShellExecute", nativeShellExecute, 5);
    installNativeFunction(state->context, global, "__aiNativeShellIsRootAvailable", nativeShellIsRootAvailable, 0);
    installNativeFunction(state->context, global, "__aiNativeShellIsShizukuAvailable", nativeShellIsShizukuAvailable, 0);
    installNativeFunction(state->context, global, "__aiNativeShellHasShizukuPermission", nativeShellHasShizukuPermission, 0);
    installNativeFunction(state->context, global, "__aiNativeShellRequestShizukuPermission", nativeShellRequestShizukuPermission, 1);
    installNativeFunction(state->context, global, "__aiNativeDialogAlert", nativeDialogAlert, 2);
    installNativeFunction(state->context, global, "__aiNativeDialogConfirm", nativeDialogConfirm, 2);
    installNativeFunction(state->context, global, "__aiNativeDialogPrompt", nativeDialogPrompt, 2);
    installNativeFunction(state->context, global, "__aiNativeDialogSelect", nativeDialogSelect, 3);
    installNativeFunction(state->context, global, "__aiNativeDialogMultiChoice", nativeDialogMultiChoice, 3);
    installNativeFunction(state->context, global, "__aiNativeDialogBuild", nativeDialogBuild, 1);
    installNativeFunction(state->context, global, "__aiNativeThreadsExec", nativeThreadsExec, 3);
    installNativeFunction(state->context, global, "__aiNativeThreadsStop", nativeThreadsStop, 1);
    installNativeFunction(state->context, global, "__aiNativeSharedBusOn", nativeSharedBusOn, 1);
    installNativeFunction(state->context, global, "__aiNativeSharedBusOff", nativeSharedBusOff, 1);
    installNativeFunction(state->context, global, "__aiNativeSharedBusEmit", nativeSharedBusEmit, 2);
    installNativeFunction(state->context, global, "__aiNativeSharedBusPoll", nativeSharedBusPoll, 0);
    installNativeFunction(state->context, global, "__aiNativeEnginesExecScript", nativeEnginesExecScript, 3);
    installNativeFunction(state->context, global, "__aiNativeEnginesExecScriptFile", nativeEnginesExecScriptFile, 2);
    installNativeFunction(state->context, global, "__aiNativeEnginesMyEngineId", nativeEnginesMyEngineId, 0);
    installNativeFunction(state->context, global, "__aiNativeEnginesAll", nativeEnginesAll, 0);
    installNativeFunction(state->context, global, "__aiNativeEnginesStopAll", nativeEnginesStopAll, 0);
    installNativeFunction(state->context, global, "__aiNativeEnginesStopAllAndToast", nativeEnginesStopAllAndToast, 0);
    installNativeFunction(state->context, global, "__aiNativeEngineForceStop", nativeEngineForceStop, 1);
    installNativeFunction(state->context, global, "__aiNativeEngineIsDestroyed", nativeEngineIsDestroyed, 1);
    installNativeFunction(state->context, global, "__aiNativeEngineResult", nativeEngineResult, 2);
    JS_FreeValue(state->context, global);

    JSValue bootstrap = JS_Eval(state->context, kBootstrapScript, sizeof(kBootstrapScript) - 1,
                                "<ai.js-pro-quickjs-init>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(bootstrap)) {
        const std::string message = quickJsException(state->context);
        JS_FreeValue(state->context, bootstrap);
        JS_FreeContext(state->context);
        JS_FreeRuntime(state->runtime);
        env->DeleteGlobalRef(state->host);
        throwQuickJs(env, message);
        return 0;
    }
    JS_FreeValue(state->context, bootstrap);

    const jlong handle = gNextHandle.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(gEnginesMutex);
        gEngines.emplace(handle, state);
    }
    return handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_createNativeFrame(
        JNIEnv *env, jclass, jlong engineHandle, jobject rgbaBuffer,
        jint width, jint height, jint rowStride, jint pixelStride, jint targetShortEdge) {
    const auto state = findEngine(engineHandle);
    if (state == nullptr) {
        throwQuickJs(env, "QuickJS runtime is not available");
        return 0;
    }
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgbaBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(rgbaBuffer);
    if (data == nullptr || capacity < 0) {
        throwQuickJs(env, "Screen capture plane is not a direct ByteBuffer");
        return 0;
    }
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride != 4) {
        throwQuickJs(env, "Unsupported RGBA screen capture plane layout");
        return 0;
    }
    const int64_t requiredBytes = static_cast<int64_t>(height - 1) * rowStride +
                                  static_cast<int64_t>(width - 1) * pixelStride + 4;
    if (capacity < requiredBytes) {
        throwQuickJs(env, "Screen capture plane buffer is smaller than its declared layout");
        return 0;
    }
    const int64_t frameHandle = state->frames.createFromRgba(
            data, width, height, rowStride, pixelStride, targetShortEdge);
    if (frameHandle == 0) {
        throwQuickJs(env, "Unable to copy screen capture into a NativeFrame");
    }
    return frameHandle;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_evaluate(
        JNIEnv *env, jclass, jlong handle, jstring source, jstring sourceName) {
    const auto state = findEngine(handle);
    if (state == nullptr) {
        throwQuickJs(env, "QuickJS runtime is not available");
        return nullptr;
    }
    state->interrupted.store(false, std::memory_order_relaxed);
    const std::string script = fromJavaString(env, source);
    const std::string filename = fromJavaString(env, sourceName);
    const std::string displayName = filename.empty() ? "<script>" : filename;
    if (!installMainModuleGlobals(state->context, displayName)) {
        const std::string message = quickJsException(state->context);
        throwQuickJs(env, message.empty() ? "Unable to create the main CommonJS module" : message);
        return nullptr;
    }
    JSValue result = JS_Eval(state->context, script.data(), script.size(),
                             displayName.c_str(), JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(result)) {
        if (state->exitRequested.load(std::memory_order_relaxed)) {
            // exit() was called: swallow the sentinel error and finish normally.
            state->exitRequested.store(false, std::memory_order_relaxed);
            JSValue ignored = JS_GetException(state->context);
            JS_FreeValue(state->context, ignored);
            JS_FreeValue(state->context, result);
            return nullptr;
        }
        const std::string message = quickJsException(state->context);
        JS_FreeValue(state->context, result);
        throwQuickJs(env, message);
        return nullptr;
    }
    markMainModuleLoaded(state->context);

    JSContext *pendingContext = nullptr;
    int pendingResult;
    while ((pendingResult = JS_ExecutePendingJob(state->runtime, &pendingContext)) > 0) {
    }
    if (pendingResult < 0) {
        const std::string message = quickJsException(pendingContext == nullptr ? state->context : pendingContext);
        JS_FreeValue(state->context, result);
        throwQuickJs(env, message);
        return nullptr;
    }

    // Native timer event loop: keeps the engine thread alive while timers are pending.
    while (pendingTimerCount(state.get()) > 0) {
        if (state->interrupted.load(std::memory_order_relaxed)) {
            JS_FreeValue(state->context, result);
            throwQuickJs(env, "Script execution interrupted");
            return nullptr;
        }
        std::string timerError;
        if (!dispatchDueTimers(state->context, state.get(), &timerError)) {
            if (state->exitRequested.load(std::memory_order_relaxed)) {
                state->exitRequested.store(false, std::memory_order_relaxed);
                JS_FreeValue(state->context, result);
                return nullptr;
            }
            JS_FreeValue(state->context, result);
            throwQuickJs(env, timerError.empty() ? "Timer callback failed" : timerError);
            return nullptr;
        }
        waitForNextTimer(state.get());
    }

    jobject javaResult = resultToJava(env, state->context, result);
    JS_FreeValue(state->context, result);
    return javaResult;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_setGlobal(
        JNIEnv *env, jclass, jlong handle, jstring name, jobject value) {
    const auto state = findEngine(handle);
    if (state == nullptr) {
        return;
    }
    const std::string propertyName = fromJavaString(env, name);
    JSValue jsValue = JS_NULL;
    if (value != nullptr) {
        jclass stringClass = env->FindClass("java/lang/String");
        jclass booleanClass = env->FindClass("java/lang/Boolean");
        jclass numberClass = env->FindClass("java/lang/Number");
        if (env->IsInstanceOf(value, stringClass)) {
            const std::string text = fromJavaString(env, static_cast<jstring>(value));
            jsValue = JS_NewStringLen(state->context, text.data(), text.size());
        } else if (env->IsInstanceOf(value, booleanClass)) {
            jmethodID booleanValue = env->GetMethodID(booleanClass, "booleanValue", "()Z");
            jsValue = JS_NewBool(state->context, env->CallBooleanMethod(value, booleanValue) == JNI_TRUE);
        } else if (env->IsInstanceOf(value, numberClass)) {
            jmethodID doubleValue = env->GetMethodID(numberClass, "doubleValue", "()D");
            jsValue = JS_NewFloat64(state->context, env->CallDoubleMethod(value, doubleValue));
        }
        env->DeleteLocalRef(numberClass);
        env->DeleteLocalRef(booleanClass);
        env->DeleteLocalRef(stringClass);
    }
    JSValue global = JS_GetGlobalObject(state->context);
    JS_SetPropertyStr(state->context, global, propertyName.c_str(), jsValue);
    JS_FreeValue(state->context, global);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_requestInterrupt(
        JNIEnv *, jclass, jlong handle) {
    const auto state = findEngine(handle);
    if (state != nullptr) {
        state->interrupted.store(true, std::memory_order_relaxed);
        state->timersCv.notify_all();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_destroy(
        JNIEnv *env, jclass, jlong handle) {
    std::shared_ptr<EngineState> state;
    {
        std::lock_guard<std::mutex> lock(gEnginesMutex);
        const auto it = gEngines.find(handle);
        if (it == gEngines.end()) {
            return;
        }
        state = it->second;
        gEngines.erase(it);
    }
    state->interrupted.store(true, std::memory_order_relaxed);
    state->timersCv.notify_all();
    state->frames.clear();
    JS_FreeContext(state->context);
    JS_FreeRuntime(state->runtime);
    env->DeleteGlobalRef(state->host);
    state->context = nullptr;
    state->runtime = nullptr;
    state->host = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_version(JNIEnv *env, jclass) {
    return env->NewStringUTF(CONFIG_VERSION);
}
