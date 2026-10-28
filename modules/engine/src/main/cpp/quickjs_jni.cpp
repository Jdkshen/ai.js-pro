#include <jni.h>
#include "sift_matcher.h"
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// $ocr 要把非 RGBA 的图像（images.read 出来的 BGR / 灰度）转成 RGBA 再交给 ML Kit，
// 需要 imgproc（CMakeLists 里 opencv_imgproc 是可选的：AAR 里有就会链上）。
#include <opencv2/imgproc.hpp>

#include "native_frame_store.h"
#include "xiangqi_board_detector.h"

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
    jlong handle = 0;
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

/**
 * 释放定时器持有的回调引用（**幂等**：释放后槽位置 JS_UNDEFINED，重复调用是无害空操作）。
 *
 * ★ 2026-09-27（独立复核 P1）：`clearInterval(自己的 id)` 会在**回调正在执行时**把
 *   `timer->callback` 释放掉；而 dispatchDueTimers 的收尾（成功/异常两条路）还会
 *   再碰一次这个槽位 —— 不置空就是「释放后使用 + 二次释放」，真机可复现 SIGSEGV。
 *   约定：所有释放回调的路径都必须走这里（或等价地先置空再释放）。
 * ⚠️ 只能在 JS 线程调用（JS_FreeValue 的约定）。
 */
void freeTimerCallback(JSContext *context, EngineState *state,
                       const std::shared_ptr<TimerEntry> &timer) {
    JSValue callback = JS_UNDEFINED;
    {
        std::lock_guard<std::mutex> lock(state->timersMutex);
        if (JS_IsUndefined(timer->callback)) {
            return;
        }
        callback = timer->callback;
        timer->callback = JS_UNDEFINED;
    }
    JS_FreeValue(context, callback);
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
        // ★ 2026-09-27（独立复核 P1）：本次调用单独拿住一份回调引用 ——
        //   回调里 clearInterval(自己)（或取消同批到期定时器）会释放 timer->callback，
        //   没有这份引用就是「正在执行的函数对象被释放」（真机复现：interval 自取消 ⇒ 139）。
        JSValue callback = JS_UNDEFINED;
        {
            std::lock_guard<std::mutex> lock(state->timersMutex);
            callback = JS_DupValue(context, timer->callback);
        }
        JSValue result = JS_Call(context, callback, JS_UNDEFINED, 0, nullptr);
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
            JS_FreeValue(context, callback);
            freeTimerCallback(context, state, timer);
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
            JS_FreeValue(context, callback);
            freeTimerCallback(context, state, timer);
            return false;
        }
        JS_FreeValue(context, callback);
        if (!timer->repeat) {
            freeTimerCallback(context, state, timer);
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
        // The last callback can cancel the final repeating timer. Return to the
        // pending-count check instead of waiting forever for a nonexistent timer.
        if (next == std::numeric_limits<int64_t>::max()) return;
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

// 异常文本长度上限：消息会进 Java 字符串、日志与界面，超大对象不能把整条管道刷爆。
constexpr size_t kMaxExceptionTextLength = 2048;

std::string truncateExceptionText(const std::string &text) {
    if (text.size() <= kMaxExceptionTextLength) {
        return text;
    }
    size_t length = kMaxExceptionTextLength;
    // 不要把一个多字节 UTF-8 字符切成两半。
    while (length > 0 && (static_cast<unsigned char>(text[length]) & 0xC0) == 0x80) {
        length--;
    }
    return text.substr(0, length) + "…(已截断)";
}

// 把异常值转成可读文本，逐级兜底：
// 1) Error（含各原生错误类）：直接取 name/message —— 不经过 Error.prototype.toString，
//    避免原型链异常时退化成 “[object Object]”；
// 2) 普通对象：先试 JSON.stringify —— 脚本 throw 的对象（如 {code:500,msg:'...'}）
//    保留全部字段，而不是在 UI 上变成 “[object Object]”；
// 3) 其余值：回退 JS_ToCString（字符串/数字等原始值本就正确）。
// 注意：JSON 化失败产生的次生异常会在函数内清掉，不影响调用方消费的原始异常。
std::string exceptionValueText(JSContext *context, JSValueConst exception) {
    if (JS_IsError(exception)) {   // 2026-09 quickjs-ng：JS_IsError 不再要 context
        JSValue nameValue = JS_GetPropertyStr(context, exception, "name");
        JSValue messageValue = JS_GetPropertyStr(context, exception, "message");
        const std::string name = jsString(context, nameValue);
        const std::string text = jsString(context, messageValue);
        JS_FreeValue(context, nameValue);
        JS_FreeValue(context, messageValue);
        if (!name.empty() && !text.empty()) {
            return name + ": " + text;
        }
        if (!text.empty()) {
            return text;
        }
        if (!name.empty()) {
            return name;
        }
        return {};
    }
    if (JS_IsObject(exception)) {
        JSValue json = JS_JSONStringify(context, exception, JS_UNDEFINED, JS_UNDEFINED);
        if (JS_IsException(json)) {
            // 循环引用等让 JSON 化失败：清掉次生异常，避免盖掉原始异常状态。
            JS_FreeValue(context, JS_GetException(context));
        } else {
            const std::string text = jsString(context, json);
            JS_FreeValue(context, json);
            if (!text.empty()) {
                return truncateExceptionText(text);
            }
        }
    }
    return jsString(context, exception);
}

std::string quickJsException(JSContext *context) {
    JSValue exception = JS_GetException(context);
    std::string message = exceptionValueText(context, exception);
    if (JS_IsObject(exception)) {
        // stack 只对对象有意义：对 null/undefined 取属性会再产生一个次生异常。
        JSValue stack = JS_GetPropertyStr(context, exception, "stack");
        if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
            const std::string stackText = jsString(context, stack);
            if (!stackText.empty() && stackText != message) {
                message += "\n" + stackText;
            }
        }
        JS_FreeValue(context, stack);
    }
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

// 前向声明：native 参数读取工具在文件后面定义（nativeSleep 之后）。
bool readInt(JSContext *context, int argc, JSValueConst *argv, int index, int32_t *value);

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

/**
 * 执行 Java 侧排队的 JS 任务（跨线程回调：WebView 页面回调、注入结果回调等）。
 *
 * Java 线程只把任务放进队列，真正的 JS 调用都在引擎线程上通过
 * `__aiRunJsTask(taskJson)` 执行，避免多线程同时进 QuickJS 上下文。
 * 在定时器循环与 sleep 的切片里调用，所以脚本跑在事件循环或 sleep 时都能收到回调。
 */
void pumpJavaJsTasks(const std::shared_ptr<EngineState> &state, JSContext *context) {
    if (state == nullptr || state->host == nullptr || context == nullptr) {
        return;
    }
    JNIEnv *env = currentEnv(state.get());
    if (env == nullptr) {
        return;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID countMethod = env->GetMethodID(hostClass, "pendingJsTaskCount", "()I");
    jmethodID takeMethod = env->GetMethodID(hostClass, "takePendingJsTask", "()Ljava/lang/String;");
    jmethodID reportMethod = env->GetMethodID(hostClass, "reportJsTaskResult", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(hostClass);
    if (countMethod == nullptr || takeMethod == nullptr) {
        env->ExceptionClear();
        return;
    }
    for (int processed = 0; processed < 64; ++processed) {
        if (state->exitRequested.load(std::memory_order_relaxed)
                || state->interrupted.load(std::memory_order_relaxed)) break;
        if (env->CallIntMethod(state->host, countMethod) <= 0) {
            break;
        }
        jstring task = static_cast<jstring>(env->CallObjectMethod(state->host, takeMethod));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }
        if (task == nullptr) {
            break;
        }
        const std::string taskJson = fromJavaString(env, task);
        env->DeleteLocalRef(task);
        std::string report;
        JSValue global = JS_GetGlobalObject(context);
        JSValue runner = JS_GetPropertyStr(context, global, "__aiRunJsTask");
        JS_FreeValue(context, global);
        if (JS_IsFunction(context, runner)) {
            JSValue argument = JS_NewStringLen(context, taskJson.data(), taskJson.size());
            JSValue result = JS_Call(context, runner, JS_UNDEFINED, 1, &argument);
            JS_FreeValue(context, argument);
            if (JS_IsException(result)) {
                report = quickJsException(context);
            } else if (!JS_IsUndefined(result) && !JS_IsNull(result)) {
                report = jsString(context, result);
            }
            JS_FreeValue(context, result);
        } else {
            report = "QuickJS 未安装任务分发函数 __aiRunJsTask";
        }
        JS_FreeValue(context, runner);
        // runJsCallback encodes thrown errors as JSON. An exit request must
        // still end this batch, rather than being logged and running more JS.
        if (state->exitRequested.load(std::memory_order_relaxed)) break;
        if (!report.empty() && reportMethod != nullptr) {
            jstring javaReport = toJavaString(env, report);
            env->CallVoidMethod(state->host, reportMethod, javaReport);
            env->DeleteLocalRef(javaReport);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }
        JSContext *pendingContext = nullptr;
        while (JS_ExecutePendingJob(state->runtime, &pendingContext) > 0) {
        }
    }
}

JSValue nativePerformanceNow(JSContext *context, JSValueConst, int, JSValueConst *) {
    const double millis = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    return JS_NewFloat64(context, millis);
}

/** web 模块：在 UI 线程创建 InjectableWebView / InjectableWebClient，返回句柄。 */
JSValue nativeWebCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const bool withView = argc > 0 && JS_ToBool(context, argv[0]) > 0;
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webCreate", "(Z)J");
    jlong handle = env->CallLongMethod(state->host, method, withView ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt64(context, static_cast<int64_t>(handle));
}

/** web 模块：WebView / WebViewClient 上的方法（loadUrl/loadData/reload/inject/...）。 */
JSValue nativeWebCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string method = argc > 1 ? jsString(context, argv[1]) : std::string();
    const std::string args = argc > 2 ? jsString(context, argv[2]) : std::string("[]");
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID call = env->GetMethodID(hostClass, "webCall",
                                      "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    jstring javaArgs = toJavaString(env, args);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, call, static_cast<jlong>(handle), javaMethod, javaArgs));
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(javaArgs);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_UNDEFINED;
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

// ---- 控件通用属性 / 动作 / 尺寸（对齐 Rhino 的 NativeView.attr 与窗口 getWidth/getHeight）----

/** 统一的 jstring 参数读取。 */
static std::string stringArg(JSContext *context, int argc, JSValueConst *argv, int index) {
    return index < argc ? jsString(context, argv[index]) : std::string();
}

JSValue nativeFloatyViewExists(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewExists", "(ILjava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jboolean result = env->CallBooleanMethod(state->host, method, windowId, javaId);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeFloatyViewGetAttr(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string name = stringArg(context, argc, argv, 2);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewGetAttr",
                                        "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaId = toJavaString(env, id);
    jstring javaName = toJavaString(env, name);
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, method, windowId,
                                                                javaId, javaName));
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string() : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativeFloatyViewSetAttr(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string name = stringArg(context, argc, argv, 2);
    const std::string value = stringArg(context, argc, argv, 3);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewSetAttr",
                                        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jstring javaName = toJavaString(env, name);
    jstring javaValue = toJavaString(env, value);
    jboolean result = env->CallBooleanMethod(state->host, method, windowId, javaId, javaName,
                                             javaValue);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaValue);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeFloatyViewAction(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string action = stringArg(context, argc, argv, 2);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewAction",
                                        "(ILjava/lang/String;Ljava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jstring javaAction = toJavaString(env, action);
    jboolean result = env->CallBooleanMethod(state->host, method, windowId, javaId, javaAction);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaAction);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeFloatyGetWidth(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyGetWidth", "(I)I");
    jint result = env->CallIntMethod(state->host, method, windowId);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt32(context, result);
}

JSValue nativeFloatyGetHeight(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (!readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyGetHeight", "(I)I");
    jint result = env->CallIntMethod(state->host, method, windowId);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt32(context, result);
}

/** UI 视图：是否存在 / 执行动作（ui.<id> 与 ui.findView 用）。 */
JSValue nativeUiViewExists(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t viewId = 0;
    if (!readInt(context, argc, argv, 0, &viewId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiViewExists", "(ILjava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jboolean result = env->CallBooleanMethod(state->host, method, viewId, javaId);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeUiViewAction(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t viewId = 0;
    if (!readInt(context, argc, argv, 0, &viewId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string action = stringArg(context, argc, argv, 2);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiViewAction",
                                        "(ILjava/lang/String;Ljava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jstring javaAction = toJavaString(env, action);
    jboolean result = env->CallBooleanMethod(state->host, method, viewId, javaId, javaAction);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaAction);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

/** UI 视图 Java 成员探测（ui.wv.loadUrl 这类）：返回 "m"/"f"/"p:getter"/空。 */
JSValue nativeUiViewProbe(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t viewId = 0;
    if (!readInt(context, argc, argv, 0, &viewId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string name = stringArg(context, argc, argv, 2);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiViewProbe",
                                        "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaId = toJavaString(env, id);
    jstring javaName = toJavaString(env, name);
    auto result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, viewId, javaId, javaName));
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

/** UI 视图 Java 成员调用（ui.wv.loadUrl(...) 这类）；返回 Java 值编码。 */
JSValue nativeUiViewInvoke(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t viewId = 0;
    if (!readInt(context, argc, argv, 0, &viewId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string name = stringArg(context, argc, argv, 2);
    const std::string args = stringArg(context, argc, argv, 3);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiViewInvoke",
                                        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaId = toJavaString(env, id);
    jstring javaName = toJavaString(env, name);
    jstring javaArgs = toJavaString(env, args);
    auto result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, viewId, javaId, javaName, javaArgs));
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaArgs);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

/** UI 视图页面桥注册：JS 对象回调 + @JavascriptInterface 桥（参数 callbackId 为 JS 回调）。 */
JSValue nativeUiAddJsInterface(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t viewId = 0;
    if (!readInt(context, argc, argv, 0, &viewId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    int64_t callbackId = 0;
    if (argc > 2) {
        JS_ToInt64(context, &callbackId, argv[2]);
    }
    const std::string name = stringArg(context, argc, argv, 3);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiAddJsInterface",
                                        "(ILjava/lang/String;JLjava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jstring javaName = toJavaString(env, name);
    jboolean result = env->CallBooleanMethod(
            state->host, method, viewId, javaId, static_cast<jlong>(callbackId), javaName);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

/** `new JavaAdapter(Base, overrides)`：宿主预置子类，返回 `{"__ref":handle}` 或空串。 */
JSValue nativeJavaAdapterCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t baseHandle = 0;
    if (argc > 0) {
        JS_ToInt64(context, &baseHandle, argv[0]);
    }
    int64_t callbackId = 0;
    if (argc > 1) {
        JS_ToInt64(context, &callbackId, argv[1]);
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaAdapterCreate", "(JJ)Ljava/lang/String;");
    auto *result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(baseHandle), static_cast<jlong>(callbackId)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_NewStringLen(context, "", 0);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

/**
 * `"ui"` 模式的宿主 Activity（ScriptExecuteActivity 注入）的脚本侧句柄；
 * 普通脚本/悬浮窗模式没有宿主，返回空串（脚本侧 `activity` 即为 undefined）。
 */
JSValue nativeHostActivity(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "hostActivityRef", "()Ljava/lang/String;");
    auto *result = static_cast<jstring>(env->CallObjectMethod(state->host, method));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_NewStringLen(context, "", 0);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

/** `ui.isUiThread()`。 */
JSValue nativeIsMainThread(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "uiIsMainThread", "()Z");
    jboolean result = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

/** `ui.statusBarColor(color)`。 */
JSValue nativeStatusBarColor(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string color = stringArg(context, argc, argv, 0);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "statusBarColor", "(Ljava/lang/String;)V");
    jstring javaColor = toJavaString(env, color);
    env->CallVoidMethod(state->host, method, javaColor);
    env->DeleteLocalRef(javaColor);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_UNDEFINED;
}

// ---- Java 互操作（Packages / importClass / Java.type）----
//
// 类与实例统一用 long 句柄跨边界：JS 侧是 Proxy，参数/返回值用「原生 JSON +
// {"__ref":handle}」协议；重载解析、字段访问、异常转译都在 Java 侧完成。

/** 解析类名，返回类句柄（0 = 找不到）。 */
JSValue nativeJavaResolveClass(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string name = stringArg(context, argc, argv, 0);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaResolveClass", "(Ljava/lang/String;)J");
    jstring javaName = toJavaString(env, name);
    jlong handle = env->CallLongMethod(state->host, method, javaName);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt64(context, static_cast<int64_t>(handle));
}

/** `'m'` / `'f'` / `''`：这个名字在类或实例上是方法、字段还是不存在。 */
JSValue nativeJavaProbe(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string name = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaProbe",
                                        "(JLjava/lang/String;)Ljava/lang/String;");
    jstring javaName = toJavaString(env, name);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), javaName));
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string() : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 嵌套类全限定名（`android.os.Build.VERSION` → `android.os.Build$VERSION`），找不到返回空串。 */
JSValue nativeJavaNestedClassName(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string name = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaNestedClassName",
                                        "(JLjava/lang/String;)Ljava/lang/String;");
    jstring javaName = toJavaString(env, name);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), javaName));
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string() : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 方法调用（receiver 是类句柄时按静态方法解析），返回 JSON 编码的结果。 */
JSValue nativeJavaCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string name = stringArg(context, argc, argv, 1);
    const std::string args = argc > 2 ? stringArg(context, argc, argv, 2) : std::string("[]");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaCall",
                                        "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaName = toJavaString(env, name);
    jstring javaArgs = toJavaString(env, args);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), javaName, javaArgs));
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaArgs);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string("null") : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 字段读取。 */
JSValue nativeJavaGetField(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string name = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaGetField",
                                        "(JLjava/lang/String;)Ljava/lang/String;");
    jstring javaName = toJavaString(env, name);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), javaName));
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string("null") : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 字段写入。 */
JSValue nativeJavaSetField(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string name = stringArg(context, argc, argv, 1);
    const std::string value = argc > 2 ? stringArg(context, argc, argv, 2) : std::string("null");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaSetField",
                                        "(JLjava/lang/String;Ljava/lang/String;)Z");
    jstring javaName = toJavaString(env, name);
    jstring javaValue = toJavaString(env, value);
    jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jlong>(handle),
                                             javaName, javaValue);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaValue);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

/** `new Class(...)`：选构造器并实例化。 */
JSValue nativeJavaNew(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    const std::string args = argc > 1 ? stringArg(context, argc, argv, 1) : std::string("[]");
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaNew",
                                        "(JLjava/lang/String;)Ljava/lang/String;");
    jstring javaArgs = toJavaString(env, args);
    jstring result = static_cast<jstring>(env->CallObjectMethod(
            state->host, method, static_cast<jlong>(handle), javaArgs));
    env->DeleteLocalRef(javaArgs);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string("null") : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 把 Android Context 交给脚本（Rhino 的 context 就是它）。 */
JSValue nativeJavaContextHandle(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaContextHandle", "()J");
    jlong handle = env->CallLongMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt64(context, static_cast<int64_t>(handle));
}

// ---- 帧 ↔ OpenCV Mat 桥（对齐 Rhino 的 img.mat：让 Imgproc/Core 能直接处理脚本帧）----

/** NativeFrame → org.opencv.core.Mat（CV_8UC4 拷贝），返回 Java 对象句柄。 */
// frameInfo 定义在后面，这里先声明（Mat↔帧 转换需要拼装标准帧信息）
JSValue frameInfo(JSContext *context, EngineState *state, int64_t handle);

JSValue nativeFrameToMat(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    std::shared_ptr<const cv::Mat> frame = state->frames.get(handle);
    if (!frame || frame->empty()) {
        return JS_ThrowTypeError(context, "帧已失效");
    }
    // 统一成 8 位 BGRA：NativeFrameStore 只保证深度/通道可用，不保证就是 4 通道。
    cv::Mat bgra(frame->rows, frame->cols, CV_8UC4);
    for (int y = 0; y < frame->rows; ++y) {
        const uint8_t *src = frame->ptr<uint8_t>(y);
        uint8_t *dst = bgra.ptr<uint8_t>(y);
        for (int x = 0; x < frame->cols; ++x) {
            dst[x * 4 + 0] = src[x * (frame->channels()) + 0];
            if (frame->channels() == 4) {
                dst[x * 4 + 1] = src[x * 4 + 1];
                dst[x * 4 + 2] = src[x * 4 + 2];
                dst[x * 4 + 3] = src[x * 4 + 3];
            } else if (frame->channels() == 3) {
                dst[x * 4 + 1] = src[x * 3 + 1];
                dst[x * 4 + 2] = src[x * 3 + 2];
                dst[x * 4 + 3] = 255;
            } else {
                dst[x * 4 + 1] = src[x];
                dst[x * 4 + 2] = src[x];
                dst[x * 4 + 3] = 255;
            }
        }
    }
    jclass matClass = env->FindClass("org/opencv/core/Mat");
    if (matClass == nullptr) {
        return JS_ThrowInternalError(context, "OpenCV Java 接口不可用");
    }
    jmethodID constructor = env->GetMethodID(matClass, "<init>", "(III)V");
    jmethodID nativeObj = env->GetMethodID(matClass, "getNativeObjAddr", "()J");
    jobject mat = env->NewObject(matClass, constructor, bgra.rows, bgra.cols, CV_8UC4);
    if (mat == nullptr || nativeObj == nullptr) {
        if (mat != nullptr) env->DeleteLocalRef(mat);
        env->DeleteLocalRef(matClass);
        return JS_ThrowInternalError(context, "无法创建 OpenCV Mat");
    }
    // 直接把像素写进 Java Mat 的原生缓冲区：Java 侧 put() 只支持单通道 byte[]。
    const jlong address = env->CallLongMethod(mat, nativeObj);
    env->DeleteLocalRef(matClass);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(mat);
        return throwJavaException(context, env);
    }
    if (address == 0) {
        env->DeleteLocalRef(mat);
        return JS_ThrowInternalError(context, "无法创建 OpenCV Mat");
    }
    auto *target = reinterpret_cast<cv::Mat *>(address);
    if (target->empty() || target->cols != bgra.cols || target->rows != bgra.rows) {
        env->DeleteLocalRef(mat);
        return JS_ThrowInternalError(context, "无法创建 OpenCV Mat");
    }
    for (int y = 0; y < bgra.rows; ++y) {
        std::memcpy(target->ptr<uint8_t>(y), bgra.ptr<uint8_t>(y),
                    static_cast<size_t>(bgra.cols) * 4);
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID registerMethod = env->GetMethodID(hostClass, "javaRegisterObject", "(Ljava/lang/Object;)J");
    jlong javaHandle = env->CallLongMethod(state->host, registerMethod, mat);
    env->DeleteLocalRef(hostClass);
    env->DeleteLocalRef(mat);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewInt64(context, static_cast<int64_t>(javaHandle));
}

// A small Java token carries engine/frame identity, never a naked pixel pointer.
JSValue nativeSiftFrameInput(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) return JS_EXCEPTION;
    if (!state->frames.get(handle)) return JS_ThrowTypeError(context, "SIFT frame has been recycled");
    jclass type = env->FindClass("com/stardust/autojs/core/image/SiftMatcher$NativeFrameInput");
    if (!type) return throwJavaException(context, env);
    jmethodID constructor = env->GetMethodID(type, "<init>", "(JJ)V");
    if (!constructor) { env->DeleteLocalRef(type); return throwJavaException(context, env); }
    jobject input = env->NewObject(type, constructor, state->handle, static_cast<jlong>(handle));
    env->DeleteLocalRef(type);
    if (!input) return throwJavaException(context, env);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID registerMethod = env->GetMethodID(hostClass, "javaRegisterObject", "(Ljava/lang/Object;)J");
    jlong id = registerMethod ? env->CallLongMethod(state->host, registerMethod, input) : 0;
    env->DeleteLocalRef(hostClass); env->DeleteLocalRef(input);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    return JS_NewInt64(context, id);
}

JSValue nativeReleaseSiftInput(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) return JS_EXCEPTION;
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "javaReleaseSiftInput", "(J)V");
    if (method) env->CallVoidMethod(state->host, method, static_cast<jlong>(handle));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

/** org.opencv.core.Mat（Java 对象句柄）→ NativeFrame；返回帧句柄。 */
JSValue nativeMatToFrame(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t javaHandle = 0;
    if (argc < 1 || JS_ToInt64(context, &javaHandle, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID lookup = env->GetMethodID(hostClass, "javaObjectForHandle", "(J)Ljava/lang/Object;");
    jobject mat = env->CallObjectMethod(state->host, lookup, static_cast<jlong>(javaHandle));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    if (mat == nullptr) {
        env->DeleteLocalRef(hostClass);
        return JS_ThrowTypeError(context, "Mat 句柄已失效");
    }
    jclass matClass = env->GetObjectClass(mat);
    jmethodID nativeObj = env->GetMethodID(matClass, "getNativeObjAddr", "()J");
    if (nativeObj == nullptr) {
        env->DeleteLocalRef(matClass);
        env->DeleteLocalRef(mat);
        env->DeleteLocalRef(hostClass);
        return JS_ThrowTypeError(context, "不是 OpenCV Mat 对象");
    }
    const jlong address = env->CallLongMethod(mat, nativeObj);
    env->DeleteLocalRef(matClass);
    env->DeleteLocalRef(mat);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (address == 0) {
        return JS_ThrowTypeError(context, "Mat 已被释放");
    }
    auto *source = reinterpret_cast<cv::Mat *>(address);
    if (source->empty()) {
        return JS_ThrowTypeError(context, "Mat 为空");
    }
    // clone：脚本随时可以 release 原始 Mat，帧需要自己持有数据；统一转成 4 通道 BGRA。
    const cv::Mat cloned = source->clone();
    cv::Mat bgra(cloned.rows, cloned.cols, CV_8UC4);
    for (int y = 0; y < cloned.rows; ++y) {
        const uint8_t *src = cloned.ptr<uint8_t>(y);
        uint8_t *dst = bgra.ptr<uint8_t>(y);
        for (int x = 0; x < cloned.cols; ++x) {
            const int channels = cloned.channels();
            if (channels == 4) {
                dst[x * 4 + 0] = src[x * 4 + 0];
                dst[x * 4 + 1] = src[x * 4 + 1];
                dst[x * 4 + 2] = src[x * 4 + 2];
                dst[x * 4 + 3] = src[x * 4 + 3];
            } else if (channels == 3) {
                dst[x * 4 + 0] = src[x * 3 + 0];
                dst[x * 4 + 1] = src[x * 3 + 1];
                dst[x * 4 + 2] = src[x * 3 + 2];
                dst[x * 4 + 3] = 255;
            } else {
                const uint8_t value = src[x * channels];
                dst[x * 4 + 0] = value;
                dst[x * 4 + 1] = value;
                dst[x * 4 + 2] = value;
                dst[x * 4 + 3] = 255;
            }
        }
    }
    const int64_t frameHandle = state->frames.createFromRgba(bgra.data, bgra.cols, bgra.rows,
                                                            bgra.step, 4);
    if (frameHandle == 0) {
        return JS_ThrowInternalError(context, "无法创建帧");
    }
    return frameInfo(context, state, frameHandle);
}

/** 从非引擎线程安排一次脚本回调（Web 桥与回归测试使用，验证跨线程回调通路）。 */
JSValue nativePostJsCallbackAsync(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    int64_t callbackId = 0;
    int64_t delayMillis = 0;
    if (argc < 1 || JS_ToInt64(context, &callbackId, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    if (argc > 1 && JS_ToInt64(context, &delayMillis, argv[1]) < 0) {
        return JS_EXCEPTION;
    }
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "postJsCallbackDelayed", "(JJ)V");
    env->CallVoidMethod(state->host, method, static_cast<jlong>(callbackId),
                        static_cast<jlong>(std::max<int64_t>(0, delayMillis)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_UNDEFINED;
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
        // 跨线程回调（如 WebView 注入完成）也在 sleep 期间落地。
        pumpJavaJsTasks(findEngine(state->handle), context);
        if (state->exitRequested.load(std::memory_order_relaxed)) {
            // Unwind the JS that called sleep(), too. evaluate() owns consuming
            // this signal as normal completion after the entire stack unwinds.
            return JS_ThrowInternalError(context, "__AIJS_EXIT__");
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

JSValue callHostVoidString(JSContext *context, const char *methodName, const std::string &argument) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaArgument = toJavaString(env, argument);
    env->CallVoidMethod(state->host, method, javaArgument);
    env->DeleteLocalRef(javaArgument);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue callHostIntString(JSContext *context, const char *methodName, const std::string &argument) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName, "(Ljava/lang/String;)I");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaArgument = toJavaString(env, argument);
    const jint result = env->CallIntMethod(state->host, method, javaArgument);
    env->DeleteLocalRef(javaArgument);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt32(context, result);
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

JSValue nativeEventsStopObserving(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostVoidString(context, "eventsStopObserving",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeFileObserverNew(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostIntString(context, "fileObserverNew",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeFileObserverStart(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 1 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    return callHostVoidInt(context, "fileObserverStart", static_cast<int32_t>(id));
}

JSValue nativeFileObserverStop(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t id = 0;
    if (argc < 1 || JS_ToInt64(context, &id, argv[0]) < 0) {
        return JS_UNDEFINED;
    }
    return callHostVoidInt(context, "fileObserverStop", static_cast<int32_t>(id));
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

/**
 * floaty.checkPermission() / floaty.requestPermission()。
 *
 * QuickJS 的 floaty 以前漏了这两个（Rhino 的 __floaty__.js 里有），而悬浮窗脚本开头通常就是
 * `if (!floaty.checkPermission()) { floaty.requestPermission(); exit(); }` —— 在 QuickJS 下
 * 会直接报 "TypeError: not a function"。转给宿主，宿主复用 Rhino 同一份实现
 * （ScriptRuntime.floaty → FloatingPermission），两个引擎语义一致。
 */
JSValue nativeFloatyCheckPermission(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostBoolNoArgs(context, "floatyCheckPermission");
}

JSValue nativeFloatyRequestPermission(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "floatyRequestPermission");
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

JSValue nativeFloatySetDragHandle(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatySetDragHandle requires windowId, viewId");
    }
    return callHostVoidIntString(context, "floatySetDragHandle",
            static_cast<int32_t>(windowId), jsString(context, argv[1]));
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

/** 窗口（整个悬浮窗）显隐：原子生效，返回操作后是否可见。 */
JSValue nativeFloatySetVisibility(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    int32_t visibility = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0 ||
        JS_ToInt32(context, &visibility, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "floatySetVisibility requires windowId, visibility");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetVisibility", "(II)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method,
            static_cast<jint>(windowId), static_cast<jint>(visibility));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeFloatyIsVisible(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyIsVisible requires windowId");
    }
    return callHostBoolInt(context, "floatyIsVisible", static_cast<int32_t>(windowId));
}

/** 只切根 View 可见性（最快路径）。 */
JSValue nativeFloatySetContentVisibility(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    int32_t visibility = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0 ||
        JS_ToInt32(context, &visibility, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "floatySetContentVisibility requires windowId, visibility");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetContentVisibility", "(II)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(windowId),
                        static_cast<jint>(visibility));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeFloatyGetContentVisibility(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetContentVisibility requires windowId");
    }
    return callHostIntInt(context, "floatyGetContentVisibility", static_cast<int32_t>(windowId));
}

/** 在主线程同步执行脚本回调（等价 Rhino 的 ui.run(fn)），返回脚本回调的 JSON 结果。 */
JSValue nativeRunOnMain(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t callbackId = 0;
    if (argc < 1 || JS_ToInt64(context, &callbackId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "runOnMainThread requires a callback id");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "runJsCallbackOnMain", "(J)Ljava/lang/String;");
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            static_cast<jlong>(callbackId)));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string("{}") : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
}

/** 控件对应的真实 android.view.View 句柄（Java 对象句柄，可交给 ObjectAnimator 等系统 API）。 */
JSValue nativeFloatyViewHandle(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyViewHandle requires windowId, id");
    }
    const std::string id = stringArg(context, argc, argv, 1);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewHandle",
                                        "(ILjava/lang/String;)J");
    jstring javaId = toJavaString(env, id);
    const jlong handle = env->CallLongMethod(state->host, method,
            static_cast<jint>(windowId), javaId);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt64(context, static_cast<int64_t>(handle));
}

JSValue nativeFloatyRootViewHandle(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyRootViewHandle requires windowId");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyRootViewHandle", "(I)J");
    const jlong handle = env->CallLongMethod(state->host, method, static_cast<jint>(windowId));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewInt64(context, static_cast<int64_t>(handle));
}

JSValue nativeFloatySetAlpha(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    double alpha = 1.0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0 ||
        JS_ToFloat64(context, &alpha, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "floatySetAlpha requires windowId, alpha");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetAlpha", "(IF)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(windowId),
                        static_cast<jfloat>(alpha));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeFloatySetScale(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    double scaleX = 1.0;
    double scaleY = 1.0;
    if (argc < 2 || JS_ToInt64(context, &windowId, argv[0]) < 0 ||
        JS_ToFloat64(context, &scaleX, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "floatySetScale requires windowId, scaleX[, scaleY]");
    }
    if (argc > 2 && JS_ToFloat64(context, &scaleY, argv[2]) < 0) {
        return JS_EXCEPTION;
    } else if (argc < 3) {
        scaleY = scaleX;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatySetScale", "(IFF)V");
    env->CallVoidMethod(state->host, method, static_cast<jint>(windowId),
                        static_cast<jfloat>(scaleX), static_cast<jfloat>(scaleY));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeFloatyGetAlpha(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetAlpha requires windowId");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyGetAlpha", "(I)F");
    const jfloat result = env->CallFloatMethod(state->host, method, static_cast<jint>(windowId));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewFloat64(context, static_cast<double>(result));
}

/** 主线程 flush 后再读的真实坐标（setPosition 之后想确认已生效时用）。 */
JSValue nativeFloatyGetRealX(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetRealX requires windowId");
    }
    return callHostIntInt(context, "floatyGetRealX", static_cast<int32_t>(windowId));
}

JSValue nativeFloatyGetRealY(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyGetRealY requires windowId");
    }
    return callHostIntInt(context, "floatyGetRealY", static_cast<int32_t>(windowId));
}

/** 窗口是否仍存在（跳引擎按 id 检查）。 */
JSValue nativeFloatyExists(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int64_t windowId = 0;
    if (argc < 1 || JS_ToInt64(context, &windowId, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "floatyExists requires windowId");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyWindowExists", "(I)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jint>(windowId));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env)
                                 : JS_NewBool(context, result == JNI_TRUE);
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

/**
 * 控件级轮廓/裁剪（圆形触摸穿透 P0-2）：
 * configJson = { clipToOutline, outlineShape, outlineRadius }，返回是否应用成功。
 */
JSValue nativeFloatyViewShape(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (argc < 2 || !readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    const std::string id = stringArg(context, argc, argv, 1);
    const std::string configJson = stringArg(context, argc, argv, 2);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyViewShape",
                                        "(ILjava/lang/String;Ljava/lang/String;)Z");
    jstring javaId = toJavaString(env, id);
    jstring javaConfig = toJavaString(env, configJson);
    jboolean result = env->CallBooleanMethod(state->host, method, windowId, javaId, javaConfig);
    env->DeleteLocalRef(javaId);
    env->DeleteLocalRef(javaConfig);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    return JS_NewBool(context, result == JNI_TRUE);
}

/**
 * 窗口触摸区域（圆形穿透）能力探测：返回 JSON 描述，脚本用 floaty.touchRegionInfo() 读取。
 */
JSValue nativeFloatyTouchRegionInfo(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "floatyTouchRegionInfo", nullptr);
}

/** 当前窗口触摸区域快照（win.getTouchRegion()）。 */
JSValue nativeFloatyGetTouchRegion(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t windowId = 0;
    if (argc < 1 || !readInt(context, argc, argv, 0, &windowId)) {
        return JS_EXCEPTION;
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "floatyGetTouchRegion",
                                        "(I)Ljava/lang/String;");
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, method, windowId));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = result == nullptr ? std::string("{}") : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, text.data(), text.size());
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

JSValue nativeFilesGetName(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesGetName",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeFilesGetNameWithoutExtension(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesGetNameWithoutExtension",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeFilesGetExtension(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesGetExtension",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeFilesIsEmptyDir(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesIsEmptyDir",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeFilesCreateIfNotExists(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesCreateIfNotExists",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeFilesRemoveDir(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callHostBooleanString(context, "filesRemoveDir",
            argc > 0 ? jsString(context, argv[0]) : std::string());
}

JSValue nativeFilesDirname(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesDirname",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeFilesStat(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "filesStat",
            argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

JSValue nativeFilesReadBytes(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) {
        return JS_ThrowTypeError(context, "files.readBytes(path) requires a path");
    }
    const std::string path = jsString(context, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "filesReadBytes", "(Ljava/lang/String;)[B");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring javaPath = toJavaString(env, path);
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(state->host, method, javaPath));
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (bytes == nullptr) {
        return JS_NULL;
    }
    const jsize length = env->GetArrayLength(bytes);
    std::vector<uint8_t> data(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(data.data()));
    }
    env->DeleteLocalRef(bytes);
    return JS_NewArrayBufferCopy(context, data.data(), data.size());
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
    JSValue callback = JS_UNDEFINED;
    {
        std::lock_guard<std::mutex> lock(state->timersMutex);
        const auto it = state->timers.find(id);
        if (it == state->timers.end() || it->second->canceled) {
            return JS_NewBool(context, false);
        }
        it->second->canceled = true;
        // ★ 2026-09-27（独立复核 P1）：先把槽位置空、再释放 ——
        //   这一格可能正是「当前正在执行的回调」：dispatchDueTimers 为本次调用单独拿了一份引用
        //  （见那里的说明），槽位置空后它收尾时就不会二次释放。
        callback = it->second->callback;
        it->second->callback = JS_UNDEFINED;
        state->timers.erase(it);
    }
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

JSValue nativeDrawCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "drawCreateWithRenderer", "(Ljava/lang/String;)Z");
    const std::string renderer = argc > 0 ? jsString(context, argv[0]) : "canvas";
    jstring name = toJavaString(env, renderer);
    const jboolean result = env->CallBooleanMethod(state->host, method, name);
    env->DeleteLocalRef(name);
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

JSValue nativeImGuiPanel(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "imguiPanel", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring operation = toJavaString(env, argc > 0 ? jsString(context, argv[0]) : "poll");
    jstring json = toJavaString(env, argc > 1 ? jsString(context, argv[1]) : "{}");
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, operation, json));
    env->DeleteLocalRef(operation); env->DeleteLocalRef(json); env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    std::string text = fromJavaString(env, result);
    if (result) env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
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

JSValue nativeGesture(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 3) {
        return JS_ThrowTypeError(context, "gesture(start, duration, points, async) requires three arguments");
    }
    int32_t start = 0;
    int32_t duration = 0;
    if (JS_ToInt32(context, &start, argv[0]) < 0 || JS_ToInt32(context, &duration, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "gesture start/duration must be numbers");
    }
    const std::string points = jsString(context, argv[2]);
    const bool async = argc > 3 && JS_ToBool(context, argv[3]) == 1;
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "gesture", "(IILjava/lang/String;Z)Z");
    jstring javaPoints = toJavaString(env, points);
    const jboolean result = env->CallBooleanMethod(state->host, method, start, duration, javaPoints,
                                                  async ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(javaPoints);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeGestures(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string strokes = argc > 0 ? jsString(context, argv[0]) : std::string("[]");
    const bool async = argc > 1 && JS_ToBool(context, argv[1]) == 1;
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "gestures", "(Ljava/lang/String;Z)Z");
    jstring javaStrokes = toJavaString(env, strokes);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaStrokes,
                                                  async ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(javaStrokes);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeInputText(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string text = argc > 0 ? jsString(context, argv[0]) : std::string();
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "inputText", "(Ljava/lang/String;)Z");
    jstring javaText = toJavaString(env, text);
    const jboolean result = env->CallBooleanMethod(state->host, method, javaText);
    env->DeleteLocalRef(javaText);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeCryptoCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "crypto call requires a method name");
    }
    const std::string method = jsString(context, argv[0]);
    const std::string arg1 = argc > 1 ? jsString(context, argv[1]) : std::string();
    const std::string arg2 = argc > 2 ? jsString(context, argv[2]) : std::string();
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "cryptoCall",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    jstring javaArg1 = toJavaString(env, arg1);
    jstring javaArg2 = toJavaString(env, arg2);
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId, javaMethod, javaArg1, javaArg2));
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(javaArg1);
    env->DeleteLocalRef(javaArg2);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_NULL;
    }
    const char *chars = env->GetStringUTFChars(result, nullptr);
    JSValue value = JS_NewString(context, chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(result, chars);
    }
    env->DeleteLocalRef(result);
    return value;
}

JSValue nativeZipsCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "zips call requires a method name");
    }
    const std::string method = jsString(context, argv[0]);
    const std::string arg1 = argc > 1 ? jsString(context, argv[1]) : std::string();
    const std::string arg2 = argc > 2 ? jsString(context, argv[2]) : std::string();
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "zipsCall",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    jstring javaArg1 = toJavaString(env, arg1);
    jstring javaArg2 = toJavaString(env, arg2);
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId, javaMethod, javaArg1, javaArg2));
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(javaArg1);
    env->DeleteLocalRef(javaArg2);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const char *chars = env->GetStringUTFChars(result, nullptr);
    JSValue value = JS_NewString(context, chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(result, chars);
    }
    env->DeleteLocalRef(result);
    return value;
}

JSValue nativeScriptStopped(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "scriptStopped", "()Z");
    const jboolean result = env->CallBooleanMethod(state->host, methodId);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeRequiresApi(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int32_t api = 0;
    if (argc > 0 && JS_ToInt32(context, &api, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "requiresApi(api) requires a number");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "requiresApi", "(I)V");
    env->CallVoidMethod(state->host, methodId, api);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeAppVersion(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "appVersionInfo", "()Ljava/lang/String;");
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const char *chars = result == nullptr ? nullptr : env->GetStringUTFChars(result, nullptr);
    JSValue value = JS_NewString(context, chars == nullptr ? "0|" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(result, chars);
        env->DeleteLocalRef(result);
    }
    return value;
}

JSValue nativeContextInfo(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string key = argc > 0 ? jsString(context, argv[0]) : std::string();
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "contextInfo", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring javaKey = toJavaString(env, key);
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId, javaKey));
    env->DeleteLocalRef(javaKey);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_NULL;
    }
    const char *chars = env->GetStringUTFChars(result, nullptr);
    JSValue value = JS_NewString(context, chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(result, chars);
    }
    env->DeleteLocalRef(result);
    return value;
}

JSValue nativeSqliteOpen(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string name = argc > 0 ? jsString(context, argv[0]) : std::string();
    const std::string options = argc > 1 ? jsString(context, argv[1]) : std::string("{}");
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "sqliteOpen", "(Ljava/lang/String;Ljava/lang/String;)J");
    jstring javaName = toJavaString(env, name);
    jstring javaOptions = toJavaString(env, options);
    const jlong handle = env->CallLongMethod(state->host, methodId, javaName, javaOptions);
    env->DeleteLocalRef(javaName);
    env->DeleteLocalRef(javaOptions);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, handle);
}

JSValue nativeSqliteCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 2) {
        return JS_ThrowTypeError(context, "sqlite call requires a handle and a method");
    }
    int64_t handle = 0;
    if (JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "sqlite handle must be a number");
    }
    const std::string method = jsString(context, argv[1]);
    const std::string args = argc > 2 ? jsString(context, argv[2]) : std::string("[]");
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "sqliteCall",
                                          "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring javaMethod = toJavaString(env, method);
    jstring javaArgs = toJavaString(env, args);
    jstring result = static_cast<jstring>(env->CallObjectMethod(state->host, methodId,
                                                               static_cast<jlong>(handle), javaMethod, javaArgs));
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(javaArgs);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (result == nullptr) {
        return JS_NULL;
    }
    const char *chars = env->GetStringUTFChars(result, nullptr);
    JSValue value = JS_NewString(context, chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(result, chars);
    }
    env->DeleteLocalRef(result);
    return value;
}

JSValue nativeConsoleCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "console call requires a method name");
    }
    const std::string method = jsString(context, argv[0]);
    const std::string arg = argc > 1 ? jsString(context, argv[1]) : std::string();
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID methodId = env->GetMethodID(hostClass, "consoleCall",
                                          "(Ljava/lang/String;Ljava/lang/String;)V");
    jstring javaMethod = toJavaString(env, method);
    jstring javaArg = toJavaString(env, arg);
    env->CallVoidMethod(state->host, methodId, javaMethod, javaArg);
    env->DeleteLocalRef(javaMethod);
    env->DeleteLocalRef(javaArg);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
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

/**
 * `String(point)` / `'' + point` / 模板字符串的形状对齐 Auto.js（OpenCV `Point.toString()` → `{100.0, 575.0}`；
 * Rhino 引擎返回的 Java Point 原生就是这个形状）。`JSON.stringify` 仍是 `{"x":100,"y":575}`——toString 为非枚举属性。
 */
JSValue pointToString(JSContext *context, JSValueConst thisVal, int, JSValueConst *) {
    JSValue xValue = JS_GetPropertyStr(context, thisVal, "x");
    JSValue yValue = JS_GetPropertyStr(context, thisVal, "y");
    int32_t x = 0;
    int32_t y = 0;
    const bool valid = JS_ToInt32(context, &x, xValue) >= 0 && JS_ToInt32(context, &y, yValue) >= 0;
    JS_FreeValue(context, xValue);
    JS_FreeValue(context, yValue);
    if (!valid) {
        return JS_ThrowTypeError(context, "point.toString requires numeric x/y");
    }
    char buffer[48];
    std::snprintf(buffer, sizeof(buffer), "{%d.0, %d.0}", x, y);
    return JS_NewString(context, buffer);
}

JSValue pointValue(JSContext *context, const NativeFramePoint &point, bool includeSimilarity) {
    JSValue value = JS_NewObject(context);
    JS_SetPropertyStr(context, value, "x", JS_NewInt32(context, point.x));
    JS_SetPropertyStr(context, value, "y", JS_NewInt32(context, point.y));
    if (includeSimilarity) {
        JS_SetPropertyStr(context, value, "similarity", JS_NewFloat64(context, point.similarity));
    }
    JS_DefinePropertyValueStr(context, value, "toString",
                              JS_NewCFunction(context, pointToString, "toString", 0),
                              JS_PROP_CONFIGURABLE | JS_PROP_WRITABLE);
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
    std::string mode = argc > 1 ? requireStringArg(context, argc, argv, 1) : "projection";
    jstring javaMode = env->NewStringUTF(mode.c_str());
    jmethodID method = env->GetMethodID(hostClass, "requestScreenCapture", "(ILjava/lang/String;)Z");
    const jboolean result = env->CallBooleanMethod(state->host, method, orientation, javaMode);
    env->DeleteLocalRef(javaMode);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeRequestScreenCaptureEx(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    int32_t orientation = 0, width = -1, height = -1;
    if (argc > 0 && JS_ToInt32(context, &orientation, argv[0]) < 0) {
        return JS_EXCEPTION;
    }
    if (argc > 1 && JS_ToInt32(context, &width, argv[1]) < 0) {
        return JS_EXCEPTION;
    }
    if (argc > 2 && JS_ToInt32(context, &height, argv[2]) < 0) {
        return JS_EXCEPTION;
    }
    const bool async = argc > 3 && JS_ToBool(context, argv[3]) > 0;
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "requestScreenCaptureEx", "(IIIZ)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jboolean result = env->CallBooleanMethod(state->host, method, orientation, width, height,
                                                    async ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeScreenCaptureTake(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "takeAsyncScreenCaptureFrame", "()J");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jlong handle = env->CallLongMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    if (handle == 0) return JS_NULL;
    if (handle < 0) return JS_FALSE;
    return frameInfo(context, state, handle);
}

JSValue nativeImagesStopScreenCapture(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "imagesStopScreenCapture");
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

JSValue nativeWindowCaptureAvailable(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass cls = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(cls, "isWindowCaptureAvailable", "()Z");
    jboolean available = env->CallBooleanMethod(state->host, method);
    env->DeleteLocalRef(cls);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, available);
}

JSValue nativeListCaptureWindows(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass cls = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(cls, "listCaptureWindows", "()Ljava/lang/String;");
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method));
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string json = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_ParseJSON(context, json.data(), json.size(), "capture-windows");
}

JSValue nativeCaptureWindow(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string json = requireStringArg(context, argc, argv, 0);
    jclass cls = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(cls, "captureWindowNative", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring options = toJavaString(env, json);
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, options));
    env->DeleteLocalRef(options);
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    const std::string metadata = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    JSValue frame = JS_ParseJSON(context, metadata.data(), metadata.size(), "window-frame");
    if (JS_IsException(frame)) return frame;
    JSValue handleValue = JS_GetPropertyStr(context, frame, "handle");
    int64_t handle = 0;
    const int converted = JS_ToInt64(context, &handle, handleValue);
    JS_FreeValue(context, handleValue);
    if (converted < 0) { JS_FreeValue(context, frame); return JS_EXCEPTION; }
    JSValue info = frameInfo(context, state, handle);
    if (JS_IsException(info)) { state->frames.release(handle); JS_FreeValue(context, frame); return info; }
    JS_SetPropertyStr(context, frame, "image", info);
    return frame;
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

/**
 * images.readRaw(path, width, height)：读 `screencap`（**不加 -p**）写出的原始 RGBA 文件建帧。
 *
 * 为什么要有它（K40 实测 2026-09-20）：`screencap -p` 的 893ms 里 ~700ms 是设备端 PNG 编码，
 * raw 写盘 + 建帧只要 ~200ms —— 高频抓帧的自动走棋全指望这条快通道。
 * ⚠️ 这条通道 09-20 做过、但改动没落进 git，后来重编 .so 时丢了（面板抓帧又回到 ~900ms 的 PNG）；
 *    2026-09-24 补回来。
 *
 * 文件两种形态都能读（按大小自动识别）：
 *   · `16 字节头（u32 宽｜高｜格式｜颜色空间）+ RGBA`（Android 11+ 的 screencap 输出）；
 *   · 纯 RGBA（老版本/别的工具）。
 */
JSValue nativeReadFrameRaw(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 3) {
        return JS_ThrowTypeError(context, "images.readRaw(path, width, height) requires path, width, height");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    const std::string path = jsString(context, argv[0]);
    int32_t width = 0;
    int32_t height = 0;
    if (JS_ToInt32(context, &width, argv[1]) < 0 || JS_ToInt32(context, &height, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "images.readRaw(path, width, height) has invalid arguments");
    }
    if (width <= 0 || height <= 0) {
        return JS_ThrowRangeError(context, "images.readRaw: width/height must be positive");
    }
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID resolveMethod = env->GetMethodID(hostClass, "resolvePath",
                                               "(Ljava/lang/String;)Ljava/lang/String;");
    jstring javaPath = toJavaString(env, path);
    auto resolvedPath = static_cast<jstring>(env->CallObjectMethod(state->host, resolveMethod, javaPath));
    env->DeleteLocalRef(javaPath);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string resolved = fromJavaString(env, resolvedPath);
    env->DeleteLocalRef(resolvedPath);

    std::FILE *file = std::fopen(resolved.c_str(), "rb");
    if (file == nullptr) {
        return JS_ThrowInternalError(context, "images.readRaw: cannot open %s", resolved.c_str());
    }
    std::fseek(file, 0, SEEK_END);
    const long fileSize = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (fileSize <= 0) {
        std::fclose(file);
        return JS_ThrowInternalError(context, "images.readRaw: empty file %s", resolved.c_str());
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(fileSize));
    const size_t got = std::fread(bytes.data(), 1, bytes.size(), file);
    std::fclose(file);
    if (got != bytes.size()) {
        return JS_ThrowInternalError(context, "images.readRaw: short read of %s", resolved.c_str());
    }
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    size_t offset = 0;
    if (bytes.size() != pixels && bytes.size() >= 16) {
        uint32_t headerWidth = 0;
        uint32_t headerHeight = 0;
        std::memcpy(&headerWidth, bytes.data(), 4);
        std::memcpy(&headerHeight, bytes.data() + 4, 4);
        if (bytes.size() == 16 + pixels ||
            (headerWidth == static_cast<uint32_t>(width) &&
             headerHeight == static_cast<uint32_t>(height) &&
             bytes.size() == 16 + static_cast<size_t>(headerWidth) * headerHeight * 4)) {
            offset = 16;
        }
    }
    if (offset == 0 && bytes.size() != pixels) {
        return JS_ThrowInternalError(context,
                "images.readRaw: unexpected size %d for %dx%d (expect %d)",
                static_cast<int>(bytes.size()), width, height, static_cast<int>(pixels));
    }
    const int64_t handle = state->frames.createFromRgba(bytes.data() + offset, width, height, width * 4, 4);
    if (handle == 0) {
        return JS_ThrowInternalError(context, "images.readRaw: unable to create frame");
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

JSValue nativeAnalyzeXiangqiBoard(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    if (argc < 1 || JS_ToInt64(context, &handle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "images.analyzeXiangqiBoard requires a frame");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    const auto frame = state->frames.get(handle);
    if (!frame || frame->empty()) return JS_ThrowTypeError(context, "帧已失效");
    XiangqiBoardAnalysis analysis;
    try {
        analysis = analyzeXiangqiBoard(*frame);
    } catch (const cv::Exception &error) {
        return JS_ThrowInternalError(context, "棋盘分析失败：%s", error.what());
    }
    JSValue result = JS_NewObject(context);
    JS_SetPropertyStr(context, result, "ok", JS_NewBool(context, analysis.ok));
    JS_SetPropertyStr(context, result, "reason", JS_NewString(context, analysis.reason.c_str()));
    if (!analysis.ok) return result;
    JSValue outer = JS_NewObject(context);
    JS_SetPropertyStr(context, outer, "x", JS_NewInt32(context, analysis.outer.x));
    JS_SetPropertyStr(context, outer, "y", JS_NewInt32(context, analysis.outer.y));
    JS_SetPropertyStr(context, outer, "width", JS_NewInt32(context, analysis.outer.width));
    JS_SetPropertyStr(context, outer, "height", JS_NewInt32(context, analysis.outer.height));
    JS_SetPropertyStr(context, result, "outer", outer);
    JSValue grid = JS_NewObject(context);
    JS_SetPropertyStr(context, grid, "originX", JS_NewFloat64(context, analysis.originX));
    JS_SetPropertyStr(context, grid, "originY", JS_NewFloat64(context, analysis.originY));
    JS_SetPropertyStr(context, grid, "stepX", JS_NewFloat64(context, analysis.stepX));
    JS_SetPropertyStr(context, grid, "stepY", JS_NewFloat64(context, analysis.stepY));
    JS_SetPropertyStr(context, result, "grid", grid);
    JS_SetPropertyStr(context, result, "lineScoreX", JS_NewFloat64(context, analysis.lineScoreX));
    JS_SetPropertyStr(context, result, "lineScoreY", JS_NewFloat64(context, analysis.lineScoreY));
    JS_SetPropertyStr(context, result, "verticalSegments", JS_NewInt32(context, analysis.verticalSegments));
    JS_SetPropertyStr(context, result, "horizontalSegments", JS_NewInt32(context, analysis.horizontalSegments));
    JSValue circles = JS_NewArray(context);
    for (uint32_t i = 0; i < analysis.circles.size(); ++i) {
        const auto &circle = analysis.circles[i];
        JSValue item = JS_NewObject(context);
        JS_SetPropertyStr(context, item, "x", JS_NewFloat64(context, circle.x));
        JS_SetPropertyStr(context, item, "y", JS_NewFloat64(context, circle.y));
        JS_SetPropertyStr(context, item, "radius", JS_NewFloat64(context, circle.radius));
        JS_SetPropertyStr(context, item, "col", JS_NewInt32(context, circle.col));
        JS_SetPropertyStr(context, item, "row", JS_NewInt32(context, circle.row));
        JS_SetPropertyStr(context, item, "color", JS_NewString(context, circle.color.c_str()));
        JS_SetPropertyStr(context, item, "redFraction", JS_NewFloat64(context, circle.redFraction));
        JS_SetPropertyStr(context, item, "error", JS_NewFloat64(context, circle.error));
        JS_SetPropertyUint32(context, circles, i, item);
    }
    JS_SetPropertyStr(context, result, "circles", circles);
    return result;
}

JSValue nativeDetectXiangqiCircles(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    double ox = 0, oy = 0, sx = 0, sy = 0;
    if (argc < 5 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToFloat64(context, &ox, argv[1]) < 0 || JS_ToFloat64(context, &oy, argv[2]) < 0 ||
        JS_ToFloat64(context, &sx, argv[3]) < 0 || JS_ToFloat64(context, &sy, argv[4]) < 0 ||
        !std::isfinite(ox) || !std::isfinite(oy) || !std::isfinite(sx) || !std::isfinite(sy)) {
        return JS_ThrowTypeError(context, "images.detectXiangqiCircles requires a frame and finite grid");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    const auto frame = state->frames.get(handle);
    if (!frame || frame->empty()) return JS_ThrowTypeError(context, "帧已失效");
    std::vector<XiangqiCircle> circles;
    try {
        circles = detectXiangqiCircles(*frame, ox, oy, sx, sy);
    } catch (const cv::Exception &error) {
        return JS_ThrowInternalError(context, "棋子圆检测失败：%s", error.what());
    }
    JSValue result = JS_NewArray(context);
    for (uint32_t i = 0; i < circles.size(); ++i) {
        const auto &circle = circles[i];
        JSValue item = JS_NewObject(context);
        JS_SetPropertyStr(context, item, "x", JS_NewFloat64(context, circle.x));
        JS_SetPropertyStr(context, item, "y", JS_NewFloat64(context, circle.y));
        JS_SetPropertyStr(context, item, "radius", JS_NewFloat64(context, circle.radius));
        JS_SetPropertyStr(context, item, "col", JS_NewInt32(context, circle.col));
        JS_SetPropertyStr(context, item, "row", JS_NewInt32(context, circle.row));
        JS_SetPropertyStr(context, item, "color", JS_NewString(context, circle.color.c_str()));
        JS_SetPropertyStr(context, item, "redFraction", JS_NewFloat64(context, circle.redFraction));
        JS_SetPropertyUint32(context, result, i, item);
    }
    return result;
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

/** 扫描区域内所有匹配颜色的像素（Rhino `images.findAllPointsForColor`）；返回 int32 x,y 对数组。 */
JSValue nativeFindAllColorPoints(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
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
        return JS_ThrowTypeError(context, "Invalid images.findAllPointsForColor arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    std::vector<NativeFramePoint> points;
    // 上限防止全屏纯色把结果数组撑爆（2 万个点 ≈ 160 KB）。
    constexpr size_t maxPoints = 20000;
    if (!state->frames.findAllPointsForColor(handle, static_cast<uint32_t>(color), threshold,
                                             x, y, width, height, maxPoints, &points)) {
        return JS_NewArrayBufferCopy(context, nullptr, 0);
    }
    std::vector<int32_t> flat;
    flat.reserve(points.size() * 2);
    for (const auto &point : points) {
        flat.push_back(point.x);
        flat.push_back(point.y);
    }
    return JS_NewArrayBufferCopy(context, reinterpret_cast<const uint8_t *>(flat.data()),
                                 flat.size() * sizeof(int32_t));
}

/** 数区域内匹配颜色的像素个数（无对象/数组分配；`images.countColor` 用）。 */
JSValue nativeCountColor(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
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
        return JS_ThrowTypeError(context, "Invalid images.countColor arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    size_t count = 0;
    if (!state->frames.countColor(handle, static_cast<uint32_t>(color), threshold,
                                  x, y, width, height, &count)) {
        return JS_NewFloat64(context, 0);
    }
    return JS_NewFloat64(context, static_cast<double>(count));
}

JSValue nativeFindMultiColors(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t handle = 0;
    int32_t firstColor = 0, threshold = 4, x = 0, y = 0, width = 0, height = 0;
    if (argc < 8 || JS_ToInt64(context, &handle, argv[0]) < 0 ||
        JS_ToInt32(context, &firstColor, argv[1]) < 0 ||
        !JS_IsArray(argv[2]) || JS_ToInt32(context, &threshold, argv[3]) < 0 ||
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
        if (!JS_IsArray(item)) {   // 2026-09 quickjs-ng：JS_IsArray 不再要 context
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
    const bool useTransparentMask = argc > 8 && JS_ToBool(context, argv[8]) > 0;
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
                                     x, y, width, height, useTransparentMask, &matches, &error)) {
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
    int32_t inputWidth = 640;
    int32_t inputHeight = 640;
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

// ---- OCR（$ocr）JNI ----
//
// 接口面与 Rhino 的 modules/engine/src/main/assets/modules/__ocr__.js 一致：
//   $ocr.create() -> session.detect(image[, options]) / release() / isReleased()
// 图像走「native 帧 → RGBA 直接缓冲区 → Java」这条路，和 YOLO 一样：
// 屏幕帧本来就是 CV_8UC4；images.read() 出来的图可能是 BGR / 灰度，这里统一转成 RGBA。

JSValue nativeOcrCreate(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    // 参数（均可省略）：backend(0=ML Kit / 1=PP-OCR)、targetSize、cpuPower、threads、useFp16、recMaxWidth。
    // 0 表示「用默认值」，由 Java 侧 PPOcr 自己兑默认。
    int32_t backend = 0;
    int32_t targetSize = 0;
    int32_t cpuPower = 0;
    int32_t threads = 0;
    int32_t useFp16 = 0;
    int32_t recMaxWidth = 0;
    if (argc >= 1 && JS_ToInt32(context, &backend, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR backend");
    }
    if (argc >= 2 && JS_ToInt32(context, &targetSize, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR targetSize");
    }
    if (argc >= 3 && JS_ToInt32(context, &cpuPower, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR cpuPower");
    }
    if (argc >= 4 && JS_ToInt32(context, &threads, argv[3]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR threads");
    }
    if (argc >= 5 && JS_ToInt32(context, &useFp16, argv[4]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR useFp16");
    }
    if (argc >= 6 && JS_ToInt32(context, &recMaxWidth, argv[5]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR recMaxWidth");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "ocrCreate", "(IIIIII)J");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jlong handle = env->CallLongMethod(state->host, method,
            static_cast<jint>(backend), static_cast<jint>(targetSize),
            static_cast<jint>(cpuPower), static_cast<jint>(threads),
            static_cast<jint>(useFp16), static_cast<jint>(recMaxWidth));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt64(context, handle);
}

JSValue nativeOcrDetect(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t sessionHandle = 0;
    int64_t frameHandle = 0;
    int32_t max = 1000;
    int32_t detectRotation = 0;
    int32_t regionX = 0;
    int32_t regionY = 0;
    int32_t regionWidth = 0;
    int32_t regionHeight = 0;
    if (argc < 8 || JS_ToInt64(context, &sessionHandle, argv[0]) < 0 ||
        JS_ToInt64(context, &frameHandle, argv[1]) < 0 ||
        JS_ToInt32(context, &max, argv[2]) < 0 ||
        JS_ToInt32(context, &detectRotation, argv[3]) < 0 ||
        JS_ToInt32(context, &regionX, argv[4]) < 0 ||
        JS_ToInt32(context, &regionY, argv[5]) < 0 ||
        JS_ToInt32(context, &regionWidth, argv[6]) < 0 ||
        JS_ToInt32(context, &regionHeight, argv[7]) < 0) {
        return JS_ThrowTypeError(context, "Invalid OCR detect arguments");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    const auto frame = state->frames.get(frameHandle);
    if (frame == nullptr || frame->empty()) {
        return JS_ThrowTypeError(context, "OCR requires a live image or frame");
    }
    cv::Mat rgba;
    if (frame->type() == CV_8UC4) {
        rgba = *frame;
    } else if (frame->type() == CV_8UC3) {
        cv::cvtColor(*frame, rgba, cv::COLOR_BGR2RGBA);
    } else if (frame->type() == CV_8UC1) {
        cv::cvtColor(*frame, rgba, cv::COLOR_GRAY2RGBA);
    } else {
        return JS_ThrowTypeError(context, "OCR does not support this image type");
    }
    if (!rgba.isContinuous()) {
        rgba = rgba.clone();   // clip 出来的子矩阵可能不连续，直接缓冲区必须连续
    }
    JNIEnv *env = currentEnv(state);
    const jlong capacity = static_cast<jlong>(rgba.total() * rgba.elemSize());
    jobject buffer = env->NewDirectByteBuffer(rgba.data, capacity);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "ocrDetect",
            "(JLjava/nio/ByteBuffer;IIIIZIIII)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(buffer);
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method,
            sessionHandle, buffer, rgba.cols, rgba.rows,
            static_cast<jint>(rgba.step[0]), max,
            detectRotation != 0 ? JNI_TRUE : JNI_FALSE,
            regionX, regionY, regionWidth, regionHeight));
    env->DeleteLocalRef(hostClass);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string json = result == nullptr ? "[]" : fromJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return JS_NewStringLen(context, json.data(), json.size());
}

JSValue nativeOcrRelease(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    int64_t sessionHandle = 0;
    if (argc < 1 || JS_ToInt64(context, &sessionHandle, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "OCR session handle is required");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "ocrRelease", "(J)Z");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jboolean result = env->CallBooleanMethod(state->host, method, sessionHandle);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeOcrLiveCount(JSContext *context, JSValueConst, int, JSValueConst *) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "ocrLiveCount", "()I");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    const jint result = env->CallIntMethod(state->host, method);
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_NewInt32(context, result);
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

// ---- Pikafish（$pikafish：中国象棋 NNUE 原生引擎）----
//
// 引擎本体在 libpikafish.so（third-party/Pikafish；UCI 会话线程 + std::cin/std::cout 重定向，
// 见 modules/engine/src/main/cpp/pikafish/pikafish_jni.cpp）。Java 侧 PikafishNativeBridge 是
// JNI 门面，QuickJsHostBridge 负责权重路径解析与 UCI 输出解析（返回 JSON 字符串），
// 这里只做参数搬运。analyze 是阻塞调用（时长≈movetime），别在 UI 线程上跑。

JSValue nativePikafishStart(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    std::string evalFile;
    if (argc >= 1 && !JS_IsUndefined(argv[0]) && !JS_IsNull(argv[0])) {
        evalFile = jsString(context, argv[0]);
    }
    int32_t threads = 2;
    int32_t hashMb = 64;
    int32_t contempt = 0;
    if (argc >= 2 && !JS_IsUndefined(argv[1]) && JS_ToInt32(context, &threads, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish threads");
    }
    if (argc >= 3 && !JS_IsUndefined(argv[2]) && JS_ToInt32(context, &hashMb, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish hashMb");
    }
    if (argc >= 4 && !JS_IsUndefined(argv[3]) && JS_ToInt32(context, &contempt, argv[3]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish contempt");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "pikafishStart",
            "(Ljava/lang/String;III)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring evalFileString = evalFile.empty() ? nullptr : env->NewStringUTF(evalFile.c_str());
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, evalFileString,
            static_cast<jint>(threads), static_cast<jint>(hashMb), static_cast<jint>(contempt)));
    if (evalFileString != nullptr) {
        env->DeleteLocalRef(evalFileString);
    }
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue callPikafishAnalyze(JSContext *context, int argc, JSValueConst *argv, const char *methodName) {
    if (argc < 1) {
        return JS_ThrowTypeError(context, "Invalid Pikafish analyze arguments");
    }
    const std::string fen = jsString(context, argv[0]);
    int64_t movetime = 1000;
    int64_t timeout = 0;
    int32_t multiPv = 1;
    int32_t depth = 0;
    if (argc >= 2 && !JS_IsUndefined(argv[1]) && JS_ToInt64(context, &movetime, argv[1]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish movetime");
    }
    if (argc >= 3 && !JS_IsUndefined(argv[2]) && JS_ToInt64(context, &timeout, argv[2]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish timeout");
    }
    if (argc >= 4 && !JS_IsUndefined(argv[3]) && JS_ToInt32(context, &multiPv, argv[3]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish multiPv");
    }
    if (argc >= 5 && !JS_IsUndefined(argv[4]) && JS_ToInt32(context, &depth, argv[4]) < 0) {
        return JS_ThrowTypeError(context, "Invalid Pikafish depth");
    }
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, methodName,
            "(Ljava/lang/String;JJII)Ljava/lang/String;");
    if (method == nullptr) {
        env->DeleteLocalRef(hostClass);
        return throwJavaException(context, env);
    }
    jstring fenString = env->NewStringUTF(fen.c_str());
    auto result = static_cast<jstring>(env->CallObjectMethod(state->host, method, fenString,
            static_cast<jlong>(movetime), static_cast<jlong>(timeout),
            static_cast<jint>(multiPv), static_cast<jint>(depth)));
    env->DeleteLocalRef(fenString);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) {
        return throwJavaException(context, env);
    }
    const std::string text = fromJavaString(env, result);
    env->DeleteLocalRef(result);
    return JS_NewStringLen(context, text.data(), text.size());
}

JSValue nativePikafishAnalyze(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callPikafishAnalyze(context, argc, argv, "pikafishAnalyze");
}

JSValue nativePikafishAnalyzePosition(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callPikafishAnalyze(context, argc, argv, "pikafishAnalyzePosition");
}

JSValue nativePikafishStop(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "pikafishStop");
}

JSValue nativePikafishQuit(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "pikafishQuit");
}

JSValue nativePikafishIsRunning(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostBoolNoArgs(context, "pikafishIsRunning");
}

JSValue nativePikafishAvailable(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostBoolNoArgs(context, "pikafishAvailable");
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

JSValue nativeDeviceGetIMEI(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "deviceGetIMEI", nullptr);
}

JSValue nativeDeviceGetMusicVolume(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "deviceGetMusicVolume");
}

JSValue nativeDeviceGetNotificationVolume(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "deviceGetNotificationVolume");
}

JSValue nativeDeviceKeepScreenOn(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    if (argc < 1 || JS_IsUndefined(argv[0]) || JS_IsNull(argv[0])) {
        return callHostVoidNoArgs(context, "deviceKeepScreenOn");
    }
    int32_t timeout = 0;
    if (JS_ToInt32(context, &timeout, argv[0]) < 0) {
        return JS_ThrowTypeError(context, "device.keepScreenOn(timeout) requires a number");
    }
    return callHostVoidInt(context, "deviceKeepScreenOnMs", timeout);
}

JSValue nativeDeviceWakeUp(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callHostVoidNoArgs(context, "deviceWakeUp");
}

JSValue nativeDeviceGetScreenOrientation(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "deviceGetScreenOrientation", nullptr);
}

JSValue nativeDeviceGetScreenRotation(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callIntHostNoArgs(context, "deviceGetScreenRotation");
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

JSValue nativeAppGetTopActivityInfo(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "appGetTopActivityInfo", nullptr);
}

JSValue nativeAppGetPathFromUri(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    return callStringHost(context, "appGetPathFromUri",
                          argc > 0 ? jsString(context, argv[0]).c_str() : nullptr);
}

// ---- web.newWebSocket JNI ----

JSValue nativeWebSocketNew(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "webSocketNew requires url");
    const std::string url = jsString(context, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webSocketNew", "(Ljava/lang/String;)J");
    jstring jUrl = toJavaString(env, url);
    jlong id = env->CallLongMethod(state->host, method, jUrl);
    env->DeleteLocalRef(jUrl);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    return JS_NewInt64(context, static_cast<int64_t>(id));
}

JSValue nativeWebSocketSend(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 3) return JS_ThrowTypeError(context, "webSocketSend requires id, type, data");
    int64_t id = 0;
    JS_ToInt64(context, &id, argv[0]);
    const std::string type = jsString(context, argv[1]);
    const std::string data = jsString(context, argv[2]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webSocketSend",
            "(JLjava/lang/String;Ljava/lang/String;)Z");
    jstring jType = toJavaString(env, type);
    jstring jData = toJavaString(env, data);
    jboolean result = env->CallBooleanMethod(state->host, method, static_cast<jlong>(id), jType, jData);
    env->DeleteLocalRef(jData);
    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeWebSocketClose(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "webSocketClose requires id");
    int64_t id = 0;
    JS_ToInt64(context, &id, argv[0]);
    int32_t code = 1000;
    if (argc > 1) JS_ToInt32(context, &code, argv[1]);
    const std::string reason = argc > 2 ? jsString(context, argv[2]) : std::string();
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webSocketClose",
            "(JILjava/lang/String;)Z");
    jstring jReason = toJavaString(env, reason);
    jboolean result = env->CallBooleanMethod(state->host, method,
            static_cast<jlong>(id), static_cast<jint>(code), jReason);
    env->DeleteLocalRef(jReason);
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    return JS_NewBool(context, result == JNI_TRUE);
}

JSValue nativeWebSocketCancel(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "webSocketCancel requires id");
    int64_t id = 0;
    JS_ToInt64(context, &id, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webSocketCancel", "(J)V");
    env->CallVoidMethod(state->host, method, static_cast<jlong>(id));
    env->DeleteLocalRef(hostClass);
    return env->ExceptionCheck() ? throwJavaException(context, env) : JS_UNDEFINED;
}

JSValue nativeWebSocketQueueSize(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    auto *state = static_cast<EngineState *>(JS_GetContextOpaque(context));
    JNIEnv *env = currentEnv(state);
    if (argc < 1) return JS_ThrowTypeError(context, "webSocketQueueSize requires id");
    int64_t id = 0;
    JS_ToInt64(context, &id, argv[0]);
    jclass hostClass = env->GetObjectClass(state->host);
    jmethodID method = env->GetMethodID(hostClass, "webSocketQueueSize", "(J)J");
    jlong size = env->CallLongMethod(state->host, method, static_cast<jlong>(id));
    env->DeleteLocalRef(hostClass);
    if (env->ExceptionCheck()) return throwJavaException(context, env);
    return JS_NewInt64(context, static_cast<int64_t>(size));
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

JSValue nativeDialogCall(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string request = argc > 0 ? jsString(context, argv[0]) : std::string("{}");
    return callStringHost(context, "dialogCall", request.c_str());
}

JSValue nativeRequestScreenCaptureAsync(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
    const std::string request = argc > 0 ? jsString(context, argv[0]) : std::string("{}");
    return callStringHost(context, "requestScreenCaptureAsync", request.c_str());
}

JSValue nativeGetScreenCaptureOptions(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "getScreenCaptureOptions", nullptr);
}

JSValue nativeModernModulesScript(JSContext *context, JSValueConst, int, JSValueConst *) {
    return callStringHost(context, "modernModulesScript", nullptr);
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
        print: function () { write(3, arguments); },
        info: function () { write(4, arguments); },
        warn: function () { write(5, arguments); },
        error: function () { write(6, arguments); },
        // 控制台浮窗（与 Rhino console.show/hide/clear/setTitle 同名同义）。
        show: function () { __aiNativeConsoleCall('show', ''); },
        hide: function () { __aiNativeConsoleCall('hide', ''); },
        clear: function () { __aiNativeConsoleCall('clear', ''); },
        setTitle: function (title) { __aiNativeConsoleCall('setTitle', String(title)); },
        setSize: function (width, height) {
            __aiNativeConsoleCall('setSize',
                JSON.stringify([Math.round(Number(width)), Math.round(Number(height))]));
        },
        setPosition: function (x, y) {
            __aiNativeConsoleCall('setPosition',
                JSON.stringify([Math.round(Number(x)), Math.round(Number(y))]));
        }
    });
    // Rhino 顶层写法
    global.openConsole = global.console.show;
    global.clearConsole = global.console.clear;
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
    /**
     * 字符串拼接形状对齐 Auto.js（OpenCV `Point.toString()` → `{100.0, 575.0}`；Rhino 引擎返回的
     * Java Point 原生即该形状）。toString 为非枚举属性：`JSON.stringify` / `Object.keys` 不变。
     */
    function attachPointToString(point) {
        Object.defineProperty(point, 'toString', {
            value: function () { return '{' + point.x + '.0, ' + point.y + '.0}'; },
            configurable: true,
            writable: true
        });
        return point;
    }
    function logicalPointOf(frame, point) {
        if (point === null || point === undefined) return point;
        const state = requireFrame(frame);
        const mapped = {
            x: Math.round(Number(point.x) / state.scaleX),
            y: Math.round(Number(point.y) / state.scaleY)
        };
        if (point.similarity !== undefined) mapped.similarity = Number(point.similarity);
        return attachPointToString(mapped);
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
        if (value === true || value === 'landscape') return 2;
        if (value === false || value === 'portrait') return 1;
        return Number(value) === 1 || Number(value) === 2 ? Number(value) : 0;
    }

    // ---- images 的事件派发（对齐 Pro：images.on("screen_capture", ...)）与 async 截图流 ----
    const imageEventListeners = new Map();
    function imageListenersFor(name, create) {
        name = String(name);
        let list = imageEventListeners.get(name);
        if (!list && create) {
            list = [];
            imageEventListeners.set(name, list);
        }
        return list;
    }
    let screenCaptureStreamTimer = null;
    function stopScreenCaptureStream() {
        if (screenCaptureStreamTimer !== null) {
            clearInterval(screenCaptureStreamTimer);
            screenCaptureStreamTimer = null;
        }
    }
    function startScreenCaptureStream() {
        stopScreenCaptureStream();
        // 40ms 一拍取最新帧（与系统事件队列同一节奏）；会话结束后自动停
        screenCaptureStreamTimer = setInterval(function () {
            let info;
            try {
                info = __aiNativeScreenCaptureTake();
            } catch (error) {
                stopScreenCaptureStream();
                throw error;
            }
            if (info === false) {
                stopScreenCaptureStream();
                return;
            }
            if (info === null || info === undefined) return;
            images.emit('screen_capture', wrapFrame(info));
        }, 40);
    }

    let accessibilityCapture = false;
    const images = {
        on: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            imageListenersFor(name, true).push(listener);
            return images;
        },
        once: function (name, listener) {
            if (typeof listener !== 'function') throw new TypeError('listener must be a function');
            function onceListener() {
                images.removeListener(name, onceListener);
                return listener.apply(undefined, arguments);
            }
            onceListener.listener = listener;
            return images.on(name, onceListener);
        },
        emit: function (name) {
            const list = imageListenersFor(name, false);
            if (!list || list.length === 0) return false;
            const args = Array.prototype.slice.call(arguments, 1);
            list.slice().forEach(function (listener) { listener.apply(undefined, args); });
            return true;
        },
        removeListener: function (name, listener) {
            const list = imageListenersFor(name, false);
            if (!list) return images;
            for (let i = list.length - 1; i >= 0; i--) {
                if (list[i] === listener || list[i].listener === listener) list.splice(i, 1);
            }
            if (list.length === 0) imageEventListeners.delete(String(name));
            return images;
        },
        removeAllListeners: function (name) {
            if (name === undefined) imageEventListeners.clear();
            else imageEventListeners.delete(String(name));
            return images;
        },
        listenerCount: function (name) {
            const list = imageListenersFor(name, false);
            return list ? list.length : 0;
        },
        eventNames: function () {
            const names = [];
            const it = imageEventListeners.keys();
            for (;;) {
                const next = it.next();
                if (next.done) break;
                names.push(next.value);
            }
            return names;
        },
        /**
         * 与 Pro 同形：支持 (orientation) / (options) / (width, height)；
         * options 支持 {orientation, width, height, async, mode}。
         */
        requestScreenCapture: function (first, second) {
            let options;
            if (arguments.length === 2) {
                options = { orientation: -1, width: Number(first), height: Number(second) };
            } else if (first && typeof first === 'object') {
                options = first;
            } else {
                options = { orientation: first === undefined ? 0 : first };
            }
            const mode = String(options.mode || 'projection').toLowerCase();
            const async = options.async === true;
            if ((mode === 'accessibility' || mode === 'auto') && async) {
                throw new Error('async 截图流暂不支持无障碍/自动授权通道');
            }
            if (mode === 'accessibility' || mode === 'auto') {
                const granted = __aiNativeRequestScreenCapture(orientationOf(options.orientation), mode);
                if (granted) accessibilityCapture = true;
                return granted;
            }
            const width = options.width === undefined ? -1 : Math.round(Number(options.width));
            const height = options.height === undefined ? -1 : Math.round(Number(options.height));
            const granted = __aiNativeRequestScreenCaptureEx(orientationOf(options.orientation), width, height, async);
            if (granted) {
                accessibilityCapture = false;
                if (async) startScreenCaptureStream();
                else stopScreenCaptureStream();
            }
            return granted;
        },
        requestScreenCaptureAsync: function (first, second) {
            const args = Array.prototype.slice.call(arguments);
            let options;
            if (args.length === 2) options = { orientation: -1, width: Number(first), height: Number(second) };
            else if (first && typeof first === 'object') options = Object.assign({}, first);
            else options = { orientation: first === undefined ? 0 : first };
            const mode = String(options.mode || 'projection').toLowerCase();
            if (mode !== 'projection') {
                return new Promise(function (resolve, reject) {
                    setTimeout(function () { try { resolve(images.requestScreenCapture.apply(images, args)); } catch (error) { reject(error); } }, 0);
                });
            }
            return new Promise(function (resolve, reject) {
                const keepAlive = setInterval(function () {}, 40);
                let callbackId;
                callbackId = global.__aiRegisterCallback(function (result) {
                    clearInterval(keepAlive); global.__aiReleaseCallback(callbackId);
                    if (result && result.error !== undefined) { reject(new Error(String(result.error))); return; }
                    if (result) {
                        accessibilityCapture = false;
                        if (options.async === true) startScreenCaptureStream(); else stopScreenCaptureStream();
                    }
                    resolve(result === true);
                });
                try {
                    __aiNativeRequestScreenCaptureAsync(JSON.stringify({
                        orientation: orientationOf(options.orientation),
                        width: options.width === undefined ? -1 : Math.round(Number(options.width)),
                        height: options.height === undefined ? -1 : Math.round(Number(options.height)),
                        async: options.async === true, callbackId: callbackId
                    }));
                } catch (error) { clearInterval(keepAlive); global.__aiReleaseCallback(callbackId); reject(error); }
            });
        },
        getScreenCaptureOptions: function () { return JSON.parse(__aiNativeGetScreenCaptureOptions()); },
        stopScreenCapture: function () {
            stopScreenCaptureStream();
            __aiNativeImagesStopScreenCapture();
        },
        captureScreen: function (options) {
            let targetShortEdge = 0;
            let fresh = false;
            let timeout = accessibilityCapture ? 2000 : 100;
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
                timeout = options.timeout === undefined ? (accessibilityCapture ? 2000 : 100) : Number(options.timeout);
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
        isWindowCaptureAvailable: function () { return __aiNativeWindowCaptureAvailable(); },
        listCaptureWindows: function () { return __aiNativeListCaptureWindows(); },
        captureWindow: function (options) {
            options = options || {};
            const timeout = options.timeout === undefined ? 2000 : Number(options.timeout);
            const windowId = options.windowId === undefined ? -1 : Number(options.windowId);
            const size = options.size === undefined ? 0 : Number(options.size);
            if (!Number.isInteger(timeout) || timeout < 1 || timeout > 5000)
                throw new RangeError('captureWindow timeout must be an integer in 1..5000 ms');
            if (!Number.isInteger(windowId) || windowId < -1 || windowId > 2147483647)
                throw new RangeError('captureWindow windowId must be a nonnegative integer');
            if (!Number.isInteger(size) || size < 0 || size > 4096)
                throw new RangeError('captureWindow size must be an integer in 0..4096');
            const result = __aiNativeCaptureWindow(JSON.stringify({ windowId: windowId,
                packageName: String(options.packageName || ''), timeout: timeout, size: size,
                screenCoordinates: options.screenCoordinates === true }));
            result.image = wrapFrame(result.image);
            delete result.handle;
            return result;
        },
        read: function (path) {
            return wrapFrame(__aiNativeReadFrame(String(path)));
        },
        /**
         * 读 `screencap`（不加 -p）写出的原始 RGBA 文件建帧（16 字节头自动识别）。
         * 这是 Shizuku 抓帧的快通道：PNG 那条路 893ms、这条 ~200ms（K40 实测）。
         */
        readRaw: function (path, width, height) {
            return wrapFrame(__aiNativeReadRawFrame(String(path), width | 0, height | 0));
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
            // native 侧 insert() 已带旋转后的 logical 尺寸（90/270 自动交换宽高），不要再给
            // 帧对象赋 width/height——wrapFrame 的属性是只读的（defineProperty 默认 writable:false），
            // 赋值会抛 "'width' is read-only" 并中断整个脚本（2026-09-30 冒烟实测，见样例高级图色测试）。
            return wrapFrame(__aiNativeRotateFrame(requireFrame(frame).id, Math.round(angle)));
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
        findAllPointsForColor: function (frame, color, options) {
            const state = requireFrame(frame);
            const region = pixelRegionOf(frame, regionOf(frame, options));
            const threshold = options && options.similarity !== undefined
                ? Math.trunc(255 * (1 - Number(options.similarity)))
                : colorThreshold(options, 4);
            const flat = new Int32Array(__aiNativeFindAllColorPoints(state.id, parseColor(color),
                threshold, region[0], region[1], region[2], region[3]));
            const points = [];
            for (let index = 0; index + 1 < flat.length; index += 2) {
                points.push(attachPointToString({
                    x: Math.round(flat[index] / state.scaleX),
                    y: Math.round(flat[index + 1] / state.scaleY)
                }));
            }
            return points;
        },
        countColor: function (frame, color, options) {
            const state = requireFrame(frame);
            const region = pixelRegionOf(frame, regionOf(frame, options));
            const threshold = options && options.similarity !== undefined
                ? Math.trunc(255 * (1 - Number(options.similarity)))
                : colorThreshold(options, 4);
            return __aiNativeCountColor(state.id, parseColor(color), threshold,
                region[0], region[1], region[2], region[3]);
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
                    threshold, max, region[0], region[1], region[2], region[3],
                    !!options.transparentMask);
            } finally {
                if (prepared.owned) prepared.frame.recycle();
            }
            const matches = raw.map(function (item) {
                const point = logicalPointOf(frame, item);
                return { point: attachPointToString({ x: point.x, y: point.y }), similarity: item.similarity };
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
    // ---- OpenCV 直连：帧 ↔ Mat + Rhino 同名包装（adaptiveThreshold/gaussianBlur/medianBlur/inRange/interval/findCircles）----
    // Rhino 的 __images__.js 用 img.mat 直接调 OpenCV Java API；QuickJS 通过帧↔Mat 桥提供同样的写法。
    // 注意：这里必须惰性解析（Java 互操作的名字缓存晚于本段初始化）。
    var opencvClassMap = null;
    function opencvClassesOf() {
        if (opencvClassMap === null) {
            var names = {
                Mat: 'org.opencv.core.Mat',
                Core: 'org.opencv.core.Core',
                Imgproc: 'org.opencv.imgproc.Imgproc',
                CvType: 'org.opencv.core.CvType',
                Scalar: 'org.opencv.core.Scalar',
                Size: 'org.opencv.core.Size',
                Point: 'org.opencv.core.Point',
                Rect: 'org.opencv.core.Rect',
                Bitmap: 'android.graphics.Bitmap',
                BitmapFactory: 'android.graphics.BitmapFactory'
            };
            var resolved = {};
            for (var key in names) {
                var cls = resolveJavaClass(names[key]);
                if (cls !== null) resolved[key] = cls;
            }
            opencvClassMap = resolved;
        }
        return opencvClassMap;
    }
    Object.defineProperty(images, 'opencv', {
        get: function () { return opencvClassesOf(); },
        enumerable: true
    });

    /** 帧 → OpenCV Mat（CV_8UC4）。 */
    images.toMat = function (frame) {
        var id = Number(__aiNativeFrameToMat(requireFrame(frame).id));
        return javaObjectOf(id, 'org.opencv.core.Mat');
    };
    // Used by the shared SIFT API instead of a full-frame Java Mat copy.
    images.__siftNativeInput = function (frame) {
        var id = Number(__aiNativeSiftFrameInput(requireFrame(frame).id));
        // This temporary argument needs no Proxy or permanent Java-object cache entry.
        return {__javaHandle: id};
    };
    images.__releaseSiftInput = function (input) {
        __aiNativeReleaseSiftInput(Number(input.__javaHandle));
    };
    /** OpenCV Mat → 帧（clone，Mat 之后 release 也不影响）。 */
    images.matToImage = function (mat) {
        var handle = mat && mat.__javaHandle !== undefined ? Number(mat.__javaHandle) : 0;
        if (!handle) throw new TypeError('matToImage 需要 OpenCV Mat 对象');
        return wrapFrame(__aiNativeMatToFrame(handle));
    };

    function openCvOp(frame, build) {
        var opencv = opencvClassesOf();
        if (!opencv.Mat || !opencv.Imgproc) {
            throw new Error('OpenCV Java 接口不可用');
        }
        var source = images.toMat(frame);
        var target = new opencv.Mat();
        try {
            build(source, target, opencv);
            return images.matToImage(target);
        } finally {
            try { source.release(); } catch (e) { }
            try { target.release(); } catch (e) { }
        }
    }

    function colorScalar(opencv, color, delta) {
        var parsed = parseColor(color);
        var alpha = (parsed >>> 24) & 0xFF;
        var red = (parsed >> 16) & 0xFF;
        var green = (parsed >> 8) & 0xFF;
        var blue = parsed & 0xFF;
        if (delta === undefined) {
            return new opencv.Scalar(red, green, blue, alpha);
        }
        var step = Number(delta);
        return new opencv.Scalar(
            Math.min(255, Math.max(0, red + step)),
            Math.min(255, Math.max(0, green + step)),
            Math.min(255, Math.max(0, blue + step)),
            alpha);
    }

    images.inRange = function (frame, lowerBound, upperBound) {
        return openCvOp(frame, function (source, target, opencv) {
            opencv.Core.inRange(source, colorScalar(opencv, lowerBound),
                colorScalar(opencv, upperBound), target);
        });
    };
    images.interval = function (frame, color, threshold) {
        var delta = threshold === undefined ? 4 : Number(threshold);
        return openCvOp(frame, function (source, target, opencv) {
            opencv.Core.inRange(source, colorScalar(opencv, color, -delta),
                colorScalar(opencv, color, delta), target);
        });
    };
    images.adaptiveThreshold = function (frame, maxValue, adaptiveMethod, thresholdType, blockSize, c) {
        return openCvOp(frame, function (source, target, opencv) {
            opencv.Imgproc.adaptiveThreshold(source, target, maxValue,
                opencv.Imgproc['ADAPTIVE_THRESH_' + (adaptiveMethod || 'MEAN_C')],
                opencv.Imgproc['THRESH_' + (thresholdType || 'BINARY')],
                blockSize, c);
        });
    };
    images.gaussianBlur = function (frame, size, sigmaX, sigmaY, type) {
        return openCvOp(frame, function (source, target, opencv) {
            var values = Array.isArray(size) ? size : [size, size];
            opencv.Imgproc.GaussianBlur(source, target,
                new opencv.Size(Number(values[0]), Number(values[1])),
                sigmaX === undefined ? 0 : sigmaX, sigmaY === undefined ? 0 : sigmaY,
                opencv.Core['BORDER_' + (type || 'DEFAULT')]);
        });
    };
    images.medianBlur = function (frame, size) {
        return openCvOp(frame, function (source, target, opencv) {
            opencv.Imgproc.medianBlur(source, target, Number(size));
        });
    };
    images.findCircles = function (frame, options) {
        options = options || {};
        var opencv = opencvClassesOf();
        var found = [];
        if (!opencv.Imgproc) throw new Error('OpenCV Java 接口不可用');
        var source = images.toMat(frame);
        var gray = new opencv.Mat();
        var target = new opencv.Mat();
        try {
            // NativeFrameStore exposes CV_8UC4, while HoughCircles requires CV_8UC1.
            // OpenCV 5 also names this constant HOUGH_GRADIENT (without CV_).
            opencv.Imgproc.cvtColor(source, gray, opencv.Imgproc.COLOR_RGBA2GRAY);
            opencv.Imgproc.HoughCircles(gray, target, opencv.Imgproc.HOUGH_GRADIENT,
                options.dp === undefined ? 1 : options.dp,
                options.minDst === undefined ? frame.height / 8 : options.minDst,
                options.param1 === undefined ? 100 : options.param1,
                options.param2 === undefined ? 100 : options.param2,
                options.minRadius === undefined ? 0 : options.minRadius,
                options.maxRadius === undefined ? 0 : options.maxRadius);
            var columns = Number(target.cols());
            for (var i = 0; i < columns; i++) {
                var data = target.get(0, i);
                found.push({ x: Number(data[0]), y: Number(data[1]), radius: Number(data[2]) });
            }
        } finally {
            try { source.release(); } catch (e) { }
            try { gray.release(); } catch (e) { }
            try { target.release(); } catch (e) { }
        }
        return found;
    };

    /** 木色区域 → Canny/Hough 格线 → 9x10 交点 → 交点圆和红黑色。坐标均为逻辑像素。 */
    images.analyzeXiangqiBoard = function (frame) {
        var state = requireFrame(frame);
        var result = __aiNativeAnalyzeXiangqiBoard(state.id);
        if (!result.ok) return result;
        result.outer.x /= state.scaleX;
        result.outer.y /= state.scaleY;
        result.outer.width /= state.scaleX;
        result.outer.height /= state.scaleY;
        result.grid.originX /= state.scaleX;
        result.grid.originY /= state.scaleY;
        result.grid.stepX /= state.scaleX;
        result.grid.stepY /= state.scaleY;
        for (var i = 0; i < result.circles.length; i++) {
            result.circles[i].x /= state.scaleX;
            result.circles[i].y /= state.scaleY;
            result.circles[i].radius /= (state.scaleX + state.scaleY) / 2;
        }
        return result;
    };
    images.detectXiangqiCircles = function (frame, grid) {
        var state = requireFrame(frame);
        if (!grid) throw new TypeError('detectXiangqiCircles 需要已校准的 grid');
        var circles = __aiNativeDetectXiangqiCircles(state.id,
            Number(grid.originX) * state.scaleX, Number(grid.originY) * state.scaleY,
            Number(grid.stepX) * state.scaleX, Number(grid.stepY) * state.scaleY);
        for (var i = 0; i < circles.length; i++) {
            circles[i].x /= state.scaleX;
            circles[i].y /= state.scaleY;
            circles[i].radius /= (state.scaleX + state.scaleY) / 2;
        }
        return circles;
    };

    /** 编码后的字节（JS 数组；Rhino 返回 Java byte[]，两边都能直接喂给 BitmapFactory.decodeByteArray）。 */
    images.toBytes = function (frame, format, quality) {
        return images.compress(frame, format === undefined ? 'png' : format,
            quality === undefined ? 100 : quality);
    };
    /** 解码字节数组为帧（经 base64 中转）。 */
    images.fromBytes = function (bytes) {
        if (bytes === null || bytes === undefined) throw new TypeError('fromBytes 需要字节数组');
        var table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var text = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var b0 = Number(bytes[i]) & 0xFF;
            var b1 = i + 1 < bytes.length ? Number(bytes[i + 1]) & 0xFF : 0;
            var b2 = i + 2 < bytes.length ? Number(bytes[i + 2]) & 0xFF : 0;
            text += table[b0 >> 2];
            text += table[((b0 & 3) << 4) | (b1 >> 4)];
            text += i + 1 < bytes.length ? table[((b1 & 15) << 2) | (b2 >> 6)] : '=';
            text += i + 2 < bytes.length ? table[b2 & 63] : '=';
        }
        return images.fromBase64(text);
    };
    /** 逐像素读取图片文件（{data,width,height}，data 为 ARGB int 数组）。 */
    images.readPixels = function (path) {
        var opencv = opencvClassesOf();
        if (!opencv.BitmapFactory) throw new Error('BitmapFactory 不可用');
        var bitmap = opencv.BitmapFactory.decodeFile(String(path));
        if (bitmap === null || bitmap === undefined) throw new Error('无法读取图片：' + path);
        var width = Number(bitmap.getWidth());
        var height = Number(bitmap.getHeight());
        var data = [];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                data.push(Number(bitmap.getPixel(x, y)));
            }
        }
        try { bitmap.recycle(); } catch (e) { }
        return { data: data, width: width, height: height };
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
        // 打包 v2：[preMs, infMs, cols, 每行 cols 个]；cols=6 为检测，6+3K 为姿态（每行带 K 个关键点）。
        // 兼容旧 v1（[preMs, infMs, 每行 6 个]）：第三个字段不满足「合法行宽 + 长度整除」时按 v1 解。
        let stride = 6, start = 2;
        if (packed.length >= 9) {
            const cols = Math.round(Number(packed[2]));
            if (cols >= 6 && cols <= 306 && cols % 3 === 0 && Number(packed[2]) === cols &&
                (packed.length - 3) % cols === 0) {
                stride = cols;
                start = 3;
            }
        }
        const detections = [];
        for (let i = start; i + stride - 1 < packed.length; i += stride) {
            const left = Number(packed[i]) / nativeFrame.scaleX;
            const top = Number(packed[i + 1]) / nativeFrame.scaleY;
            const right = Number(packed[i + 2]) / nativeFrame.scaleX;
            const bottom = Number(packed[i + 3]) / nativeFrame.scaleY;
            const classId = Math.round(Number(packed[i + 5]));
            const item = {
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
            };
            if (stride > 6) {
                // 姿态：每行尾部是 K 个 (x, y, score)；坐标与框一样除以帧缩放
                const keypoints = [];
                for (let k = i + 6; k + 2 < i + stride; k += 3) {
                    keypoints.push({
                        x: Number(packed[k]) / nativeFrame.scaleX,
                        y: Number(packed[k + 1]) / nativeFrame.scaleY,
                        score: Number(packed[k + 2])
                    });
                }
                item.keypoints = keypoints;
            }
            detections.push(item);
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
            const inputSize = options.inputSize === undefined ? 640 : Number(options.inputSize);
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

    // ---- $ocr（默认 ML Kit 中文文字识别；backend:'ppocr' 用 ncnn 版 PP-OCRv6）----
    //
    // 为什么要有：Pro 的 $ocr 是脚本里最常见的文字识别入口，而 QuickJS 侧以前完全没挂，
    // 脚本一跑就是 "cannot read property 'create' of undefined"（报错只看得到 create 找不到，
    // 完全看不出是「这个引擎没实现 OCR」）。接口面与 Rhino 的 __ocr__.js 保持一致。
    // 两个后端同形（都是 {text, confidence, bounds, rotation, rotationConfidence}）；
    // ppocr 后端下 cpuPowerMode / parallelThreads / targetSize 这些参数**真的生效**。
    // 会话用「闭包 + 普通对象」实现：与 Rhino 的 __ocr__.js 一致 —— Object.keys(session)
    // 能看到 detect / release / isReleased 三个成员，按名字取方法的脚本才不会踩空。
    const ocr = {
        create: function (options) {
            options = options || {};
            // backend:'ppocr' 跑 ncnn 版 PP-OCRv6（更准；只随包 arm64-v8a），默认仍是 ML Kit。
            const backend = String(options.backend || '').toLowerCase() === 'ppocr' ? 1 : 0;
            const targetSize = options.targetSize === undefined ? 0 : Math.round(Number(options.targetSize));
            const cpuPower = options.cpuPowerMode === undefined ? 0 : Math.round(Number(options.cpuPowerMode));
            const threadValue = options.threads === undefined ? options.parallelThreads : options.threads;
            const threads = threadValue === undefined ? 0 : Math.round(Number(threadValue));
            const useFp16 = options.useFp16 === true ? 1 : 0;
            const recMaxWidth = options.recMaxWidth === undefined ? 0 : Math.round(Number(options.recMaxWidth));
            const id = __aiNativeOcrCreate(backend, targetSize, cpuPower, threads, useFp16, recMaxWidth);
            let released = false;
            return {
                detect: function (image, options) {
                    if (released) throw new Error('OCR session has been released');
                    const frame = requireFrame(image);
                    options = options || {};
                    const max = options.max === undefined ? 1000
                        : Math.max(1, Math.round(Number(options.max)));
                    const detectRotation = options.detectRotation === true;
                    let region = [0, 0, 0, 0];
                    if (options.region !== undefined) {
                        if (!Array.isArray(options.region) || options.region.length !== 4) {
                            throw new TypeError('region must be [x, y, width, height]');
                        }
                        region = options.region.map(function (value) {
                            return Math.round(Number(value));
                        });
                    }
                    // 原生侧返回 JSON：文本结果字段多且变长，走 JSON 比打包成 float 数组清楚
                    const packed = __aiNativeOcrDetect(id, frame.id, max, detectRotation ? 1 : 0,
                        region[0], region[1], region[2], region[3]);
                    const items = JSON.parse(String(packed || '[]'));
                    const results = [];
                    for (let i = 0; i < items.length; i++) {
                        const item = items[i];
                        results.push({
                            text: item.text,
                            confidence: item.confidence,
                            bounds: item.bounds,
                            rotation: item.rotation,
                            rotationConfidence: item.rotationConfidence
                        });
                    }
                    return results;
                },
                release: function () {
                    if (released) return false;
                    released = true;
                    return __aiNativeOcrRelease(id);
                },
                isReleased: function () {
                    return released;
                }
            };
        },
        liveCount: function () {
            return __aiNativeOcrLiveCount();
        }
    };
    global.$ocr = ocr;
    global.ocr = ocr;   // Rhino 侧 $ocr 与 ocr 两个名字都有，这里保持一致

    // ---- $pikafish（中国象棋 NNUE 原生引擎：libpikafish.so + pikafish.nnue）----
    //
    // 与内置 JS 引擎（项目里的 src/engine/engine.js）互补：这个是 **native** 中国象棋引擎，
    // UCI 会话跑在自己的线程上，棋力远高于 JS 搜索（K40 上 movetime 1s 已有省市队水平）。
    // 用法：
    //   $pikafish.start({evalFile:'/sdcard/xiangqi-p4/pikafish.nnue', threads:2, hashMb:128, contempt:40});
    //   var r = $pikafish.analyze(fen, 1000);   // {ok, move:'h2e2', scoreCp|mate, depth, pv, ...}
    //   $pikafish.stop() / $pikafish.quit()
    // ⚠️ analyze 是**阻塞**调用（时长 ≈ movetime），主线程要保响应就放进 threads worker。
    function parsePikafishResult(text) {
        const raw = String(text === undefined || text === null ? '' : text);
        if (raw === '') {
            return { ok: false, error: 'Pikafish 宿主没有返回内容' };
        }
        try {
            return JSON.parse(raw);
        } catch (error) {
            return { ok: false, error: 'Pikafish 宿主返回的不是 JSON: ' + raw };
        }
    }
    function runPikafishAnalysis(nativeCall, input, movetimeMs, timeoutMs, multiPv, depth) {
        const movetimeValue = movetimeMs === undefined ? 1000 : Number(movetimeMs);
        const movetime = isFinite(movetimeValue) ? Math.max(1, Math.round(movetimeValue)) : 1000;
        const timeoutValue = timeoutMs === undefined ? 0 : Number(timeoutMs);
        const timeout = isFinite(timeoutValue) ? Math.max(0, Math.round(timeoutValue)) : 0;
        const pvValue = multiPv === undefined ? 1 : Number(multiPv);
        const pv = isFinite(pvValue) ? Math.max(1, Math.min(8, Math.round(pvValue))) : 1;
        const depthValue = depth === undefined ? 0 : Number(depth);
        const searchDepth = isFinite(depthValue) ? Math.max(0, Math.min(40, Math.round(depthValue))) : 0;
        return parsePikafishResult(nativeCall(String(input), movetime, timeout, pv, searchDepth));
    }
    const pikafish = {
        available: function () {
            return __aiNativePikafishAvailable() === true;
        },
        start: function (options) {
            options = options || {};
            const evalFile = (options.evalFile === undefined || options.evalFile === null)
                ? null : String(options.evalFile);
            const threadsValue = options.threads === undefined ? 2 : Number(options.threads);
            const threads = isFinite(threadsValue) ? Math.max(1, Math.round(threadsValue)) : 2;
            const hashValue = options.hashMb === undefined ? 64 : Number(options.hashMb);
            const hashMb = isFinite(hashValue) ? Math.max(16, Math.round(hashValue)) : 64;
            const contemptValue = options.contempt === undefined ? 0 : Number(options.contempt);
            const contempt = isFinite(contemptValue)
                ? Math.max(-200, Math.min(200, Math.round(contemptValue))) : 0;
            return parsePikafishResult(__aiNativePikafishStart(evalFile, threads, hashMb, contempt));
        },
        analyze: function (fen, movetimeMs, timeoutMs, multiPv, depth) {
            return runPikafishAnalysis(__aiNativePikafishAnalyze, fen, movetimeMs, timeoutMs, multiPv, depth);
        },
        analyzePosition: function (positionCommand, movetimeMs, timeoutMs, multiPv, depth) {
            return runPikafishAnalysis(__aiNativePikafishAnalyzePosition,
                    positionCommand, movetimeMs, timeoutMs, multiPv, depth);
        },
        stop: function () {
            __aiNativePikafishStop();
        },
        quit: function () {
            __aiNativePikafishQuit();
        },
        isRunning: function () {
            return __aiNativePikafishIsRunning() === true;
        }
    };
    global.$pikafish = pikafish;
    global.pikafish = pikafish;   // 与 $ocr/ocr 一样保留两个名字

    const timerIds = new Set();
    function makeTimer(repeat, callback, millis, args) {
        if (typeof callback !== 'function') {
            throw new TypeError('Timer callback must be a function');
        }
        const delay = Math.max(0, Number(millis) || 0);
        // Rhino's Timer.callFunction passes the extra setTimeout/setInterval
        // arguments to the callback. Native timers only own (callback, delay),
        // so keep that storage unchanged and close over the extra arguments.
        const target = args && args.length > 0
            ? function () { return callback.apply(null, args); }
            : callback;
        const id = repeat
            ? __aiNativeSetInterval(target, delay)
            : __aiNativeSetTimeout(target, delay);
        timerIds.add(id);
        return id;
    }
    global.setTimeout = function (callback, millis) {
        return makeTimer(false, callback, millis, Array.prototype.slice.call(arguments, 2));
    };
    global.setInterval = function (callback, millis) {
        return makeTimer(true, callback, millis, Array.prototype.slice.call(arguments, 2));
    };
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
        join: function () {
            var joined = Array.prototype.slice.call(arguments).map(filePath).join('/');
            return joined.replace(/\/{2,}/g, '/');
        },
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
        listDir: function (path, filter) {
            var names = JSON.parse(__aiNativeFilesListDir(filePath(path)));
            if (filter === undefined || filter === null) return names;
            if (typeof filter !== 'function') {
                throw new TypeError('files.listDir filter must be a function');
            }
            // Rhino's PFiles.listDir(path, filter) filters bare entry names,
            // not absolute paths; keep the same contract here.
            return names.filter(function (name) { return filter(name); });
        },
        remove: function (path) { return __aiNativeFilesRemove(filePath(path)); },
        rename: function (path, newName) { return __aiNativeFilesRename(filePath(path), String(newName)); },
        copy: function (source, target) { return __aiNativeFilesCopy(filePath(source), filePath(target)); },
        move: function (source, target) { return __aiNativeFilesMove(filePath(source), filePath(target)); },
        getName: function (path) { return __aiNativeFilesGetName(filePath(path)); },
        getNameWithoutExtension: function (path) { return __aiNativeFilesGetNameWithoutExtension(filePath(path)); },
        getExtension: function (path) { return __aiNativeFilesGetExtension(filePath(path)); },
        isEmptyDir: function (path) { return __aiNativeFilesIsEmptyDir(filePath(path)); },
        createIfNotExists: function (path) { return __aiNativeFilesCreateIfNotExists(filePath(path)); },
        removeDir: function (path) { return __aiNativeFilesRemoveDir(filePath(path)); },
        readBytes: function (path) { return new Uint8Array(__aiNativeFilesReadBytes(filePath(path))); },
        dirname: function (path) {
            var d = __aiNativeFilesDirname(String(path));
            return d === '' ? null : d;
        },
        stat: function (path) {
            var raw = JSON.parse(__aiNativeFilesStat(filePath(path)));
            return {
                path: raw.path === undefined ? null : raw.path,
                size: raw.size,
                lastModified: raw.lastModified,
                isReadable: function () { return raw.readable; },
                isWritable: function () { return raw.writable; },
                isExecutable: function () { return raw.executable; },
                isHidden: function () { return raw.hidden; }
            };
        },
        /**
         * 与 Rhino 的 `$files.observe(path)` 同语义：FileObserver 监听目录/文件变化，
         * 事件名与 Pro 一致（create/delete/modify/moved_from/...），且监听期间脚本保持运行。
         * 返回的对象支持 on/once/removeListener/removeAllListeners/startWatching/stopWatching/
         * isWatching/getPath。
         */
        observe: function (path) {
            var resolved = files.path(path);
            var id = Number(__aiNativeFileObserverNew(String(resolved)));
            if (!(id > 0)) throw new Error('无法监听路径: ' + resolved);
            __aiFileWatcherBags.set(id, { path: resolved, watching: true });
            ensureSystemEventTimer('files');
            return __aiCreateFileWatcher(id, resolved);
        }
    };
    /**
     * Rhino 的 `files.open(path[, mode[, encoding[, bufferSize]]])` 返回文本文件对象。
     * QuickJS 用无缓冲的文本包装实现同样的读写语义：'r' 可 read/read(size)/readline/readlines，
     * 'w' 打开即清空（与 PFiles.open 一致）后 write/writeline/writelines，'a' 追加；
     * 其它 mode 与 Rhino 一样返回 null。
     */
    function openTextFile(path, mode, encoding, bufferSize) {
        var resolved = files.path(path);
        var textMode = mode === undefined || mode === null ? 'r' : String(mode);
        if (textMode !== 'r' && textMode !== 'w' && textMode !== 'a') return null;
        var closed = false;
        var file = {
            path: resolved,
            mode: textMode,
            encoding: encoding === undefined || encoding === null ? '' : String(encoding),
            bufferSize: bufferSize === undefined || bufferSize === null ? 0 : Number(bufferSize),
            getPath: function () { return resolved; },
            isClosed: function () { return closed; },
            close: function () { closed = true; }
        };
        if (textMode === 'r') {
            // Rhino's PFiles.open('r') constructs a FileInputStream: a missing
            // path or a directory throws instead of producing a fake empty file.
            if (!files.exists(resolved)) {
                throw new Error('File not found: ' + resolved);
            }
            if (!files.isFile(resolved)) {
                throw new Error('Not a file: ' + resolved);
            }
            var content = files.read(resolved);
            var offset = 0;
            // read 与 readline 共用同一游标：readline 读进来的半个缓冲区记在 pendingLine 里，
            // 之后 read() 会先吐出这部分，混用两种读法也不会丢内容（与文本流一致）。
            var pendingLine = '';
            file.read = function (size) {
                if (size === undefined) {
                    var rest = pendingLine + content.slice(offset);
                    pendingLine = '';
                    offset = content.length;
                    return rest;
                }
                var wanted = Math.max(0, Number(size) || 0);
                var chunk = pendingLine.slice(0, wanted);
                pendingLine = pendingLine.slice(chunk.length);
                if (chunk.length < wanted) {
                    var extra = content.substr(offset, wanted - chunk.length);
                    offset += extra.length;
                    chunk += extra;
                }
                return chunk;
            };
            file.readline = function () {
                if (pendingLine.indexOf('\n') < 0) {
                    var breakIndex = content.indexOf('\n', offset);
                    if (breakIndex < 0) {
                        pendingLine += content.slice(offset);
                        offset = content.length;
                    } else {
                        pendingLine += content.slice(offset, breakIndex + 1);
                        offset = breakIndex + 1;
                    }
                }
                var index = pendingLine.indexOf('\n');
                if (index < 0) {
                    var tail = pendingLine;
                    pendingLine = '';
                    return tail === '' ? null : tail;
                }
                var line = pendingLine.slice(0, index);
                pendingLine = pendingLine.slice(index + 1);
                return line.replace(/\r$/, '');
            };
            file.readlines = function () {
                var result = [];
                for (var line = file.readline(); line !== null; line = file.readline()) result.push(line);
                return result;
            };
            return file;
        }
        if (textMode === 'w') {
            // 与 Rhino 的 PFiles.open 一致：打开时就把文件清空。
            files.write(resolved, '');
        }
        file.write = function (text) { files.append(resolved, text === undefined ? '' : String(text)); };
        file.writeline = function (line) {
            files.append(resolved, (line === undefined ? '' : String(line)) + '\n');
        };
        file.writelines = function (lines) {
            if (lines === null || lines === undefined) return;
            var count = Number(lines.length) || 0;
            for (var i = 0; i < count; i++) file.writeline(lines[i]);
        };
        file.flush = function () { /* 每次写入直接落盘，无需缓冲 */ };
        return file;
    }
    files.open = function (path, mode, encoding, bufferSize) {
        return openTextFile(path, mode, encoding, bufferSize);
    };

    global.files = Object.freeze(files);

    // ---- files.observe（android.os.FileObserver → 系统事件队列，40ms 派发）----
    // 与 Rhino 的 ScriptFileObserver 同事件名、同参数：直接事件回调收 (relativePath)，
    // "any" 回调收 (event, relativePath)。事件名来自 Pro 的 fileEventToString。
    var __aiFileWatcherBags = new Map();
    global.__aiDispatchFileObserverEvent = function (event) {
        var bag = __aiFileWatcherBags.get(event.id);
        if (!bag) return;
        var relativePath = event.path === undefined ? null : event.path;
        var direct = bag[event.event];
        if (direct) direct.slice().forEach(function (listener) { listener(relativePath); });
        var any = bag['any'];
        if (any) any.slice().forEach(function (listener) { listener(event.event, relativePath); });
    };
    function __aiCreateFileWatcher(id, path) {
        var bag = __aiFileWatcherBags.get(id) || { path: path, watching: true };
        __aiFileWatcherBags.set(id, bag);
        function listenersForFile(name, create) {
            name = String(name);
            var list = bag[name];
            if (!list && create) {
                list = [];
                bag[name] = list;
            }
            return list;
        }
        var watcher = {
            getPath: function () { return path; },
            isWatching: function () { return !!bag.watching; },
            startWatching: function () {
                if (!bag.watching) {
                    __aiNativeFileObserverStart(id);
                    bag.watching = true;
                    ensureSystemEventTimer('files');
                }
                return watcher;
            },
            stopWatching: function () {
                if (bag.watching) {
                    __aiNativeFileObserverStop(id);
                    bag.watching = false;
                    var active = false;
                    __aiFileWatcherBags.forEach(function (entry) { if (entry.watching) active = true; });
                    if (!active) {
                        systemEventConsumers.delete('files');
                        if (systemEventConsumers.size === 0 && systemEventTimer !== null) {
                            clearInterval(systemEventTimer);
                            systemEventTimer = null;
                        }
                    }
                }
                return watcher;
            },
            on: function (name, listener) {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                listenersForFile(name, true).push(listener);
                return watcher;
            },
            once: function (name, listener) {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                function onceListener() {
                    watcher.removeListener(name, onceListener);
                    return listener.apply(undefined, arguments);
                }
                onceListener.listener = listener;
                return watcher.on(name, onceListener);
            },
            removeListener: function (name, listener) {
                var list = listenersForFile(name, false);
                if (list) {
                    for (var i = list.length - 1; i >= 0; i--) {
                        if (list[i] === listener || list[i].listener === listener) list.splice(i, 1);
                    }
                    if (list.length === 0) delete bag[String(name)];
                }
                return watcher;
            },
            removeAllListeners: function (name) {
                if (name === undefined) {
                    var keys = Object.keys(bag);
                    for (var i = 0; i < keys.length; i++) {
                        if (keys[i] !== 'path' && keys[i] !== 'watching') delete bag[keys[i]];
                    }
                } else {
                    delete bag[String(name)];
                }
                return watcher;
            },
            listenerCount: function (name) {
                var list = listenersForFile(name, false);
                return list ? list.length : 0;
            },
            toString: function () { return 'FileObserver(' + path + ')'; }
        };
        return watcher;
    }

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

    // Rhino / Auto.js Pro 的 drawing 是「命令式」的：clear() → drawRect()/drawText() → update()。
    // QuickJS 的原生层只吃「一批框 + 一行统计」，所以这里加一层兼容垫片：把 drawRect/drawLine/
    // drawText 累积起来，update() 时连同传入的 detections 一起提交（文字映射成框的 label）。
    // 兼容性：只用 update(detections, stats) 的老脚本行为完全不变（那时累积列表是空的）。
    const drawingAccum = { shapes: [], stats: '' };
    const drawing = {
        panel: Object.freeze({
            show: function (options) { return JSON.parse(__aiNativeImGuiPanel('show', JSON.stringify(options || {}))); },
            update: function (state) { return JSON.parse(__aiNativeImGuiPanel('update', JSON.stringify(state || {}))); },
            poll: function () { return JSON.parse(__aiNativeImGuiPanel('poll', '{}')); },
            hide: function () { return JSON.parse(__aiNativeImGuiPanel('close', '{}')); }
        }),
        show: function (options) {
            const renderer = String(options && options.renderer || 'canvas');
            if (renderer !== 'canvas' && renderer !== 'imgui') throw new RangeError('drawing renderer must be canvas or imgui');
            drawingAccum.shapes = []; drawingAccum.stats = '';
            return __aiNativeDrawCreate(renderer);
        },
        hide: function () { drawingAccum.shapes = []; drawingAccum.stats = ''; __aiNativeDrawClose(); },
        clear: function () {
            drawingAccum.shapes = [];
            drawingAccum.stats = '';
            return __aiNativeDrawUpdate('[]', '');
        },
        drawRect: function (x1, y1, x2, y2, color, strokeWidth) {
            drawingAccum.shapes.push({
                x1: Number(x1), y1: Number(y1), x2: Number(x2), y2: Number(y2),
                label: '', score: 0, color: color, strokeWidth: strokeWidth
            });
            return drawing;
        },
        drawLine: function (x1, y1, x2, y2, color, strokeWidth) {
            drawingAccum.shapes.push({kind: 'line', x1:Number(x1), y1:Number(y1), x2:Number(x2), y2:Number(y2),
                label:'', color:color, strokeWidth:strokeWidth});
            return drawing;
        },
        drawArrow: function (x1, y1, x2, y2, color, strokeWidth, headSize) {
            drawingAccum.shapes.push({kind:'arrow', x1:Number(x1), y1:Number(y1), x2:Number(x2), y2:Number(y2),
                label:'', color:color, strokeWidth:strokeWidth, headSize:headSize});
            return drawing;
        },
        drawCircle: function (x, y, radius, color, strokeWidth) {
            drawingAccum.shapes.push({kind:'circle', x1:Number(x), y1:Number(y), x2:Number(x), y2:Number(y),
                radius:Number(radius), label:'', color:color, strokeWidth:strokeWidth});
            return drawing;
        },
        drawText: function (x, y, text, color, size) {
            drawingAccum.shapes.push({
                kind: 'text', x1: Number(x), y1: Number(y), x2: Number(x), y2: Number(y),
                label: String(text), score: 0, color: color, size: size
            });
            return drawing;
        },
        update: function (detections, stats) {
            const packed = [];
            if (Array.isArray(detections)) {
                detections.forEach(function (item) {
                    const bounds = item.bounds || item;
                    // 框的来源有三种写法：left/top/right/bottom、x1/y1/x2/y2、x/y/width/height。
                    const bx = bounds.x !== undefined ? Number(bounds.x) : undefined;
                    const by = bounds.y !== undefined ? Number(bounds.y) : undefined;
                    const x1 = bounds.left !== undefined ? Number(bounds.left)
                        : (bounds.x1 !== undefined ? Number(bounds.x1) : (bx === undefined ? 0 : bx));
                    const y1 = bounds.top !== undefined ? Number(bounds.top)
                        : (bounds.y1 !== undefined ? Number(bounds.y1) : (by === undefined ? 0 : by));
                    const x2 = bounds.right !== undefined ? Number(bounds.right)
                        : (bounds.x2 !== undefined ? Number(bounds.x2)
                            : (bx === undefined ? 0 : bx + Number(bounds.width || 0)));
                    const y2 = bounds.bottom !== undefined ? Number(bounds.bottom)
                        : (bounds.y2 !== undefined ? Number(bounds.y2)
                            : (by === undefined ? 0 : by + Number(bounds.height || 0)));
                    packed.push({
                        x1: x1, y1: y1, x2: x2, y2: y2,
                        kind: item.kind || 'rect', color: item.color, radius: item.radius,
                        strokeWidth: item.strokeWidth, size: item.size, headSize: item.headSize,
                        label: String(item.label === undefined ? '' : item.label),
                        score: Number(item.score || 0)
                    });
                });
            }
            for (let i = 0; i < drawingAccum.shapes.length; i++) {
                packed.push(drawingAccum.shapes[i]);
            }
            if (stats !== undefined) drawingAccum.stats = stats == null ? '' : String(stats);
            return __aiNativeDrawUpdate(JSON.stringify(packed), drawingAccum.stats);
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
        getTotalMem: function () { return Number(__aiNativeDeviceGetTotalMem()); },
        getIMEI: function () { return __aiNativeDeviceGetIMEI(); },
        getMusicVolume: function () { return Number(__aiNativeDeviceGetMusicVolume()); },
        getNotificationVolume: function () { return Number(__aiNativeDeviceGetNotificationVolume()); },
        keepScreenOn: function (ms) { __aiNativeDeviceKeepScreenOn(ms); },
        wakeUp: function () { __aiNativeDeviceWakeUp(); },
        getScreenOrientation: function () { return __aiNativeDeviceGetScreenOrientation(); },
        getScreenRotation: function () { return Number(__aiNativeDeviceGetScreenRotation()); }
    });

    // ---- app module (additional) ----
    var _app = global.app;
    // 版本信息读取：Rhino 的 __app__.js 暴露 app.versionCode/versionName 与 app.autojs.*，
    // QuickJS 之前整个缺 ⇒ 脚本里 app.versionName 恒为 undefined（·用户反馈）。
    // 原生 __aiNativeAppVersion() 返回 "versionCode|versionName"。
    function readAppVersion() {
        var parts = String(__aiNativeAppVersion()).split('|');
        return { code: Number(parts[0]) || 0, name: parts[1] || '' };
    }
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
        getTopActivity: function () {
            var info = String(__aiNativeAppGetTopActivityInfo());
            if (!info) return null;
            var parts = info.split('|');
            var activity = {
                className: parts[0],
                packageName: parts.length > 1 ? parts[1] : '',
                toString: function () { return 'Activity(' + activity.className + ')'; }
            };
            return activity;
        },
        getPathFromUri: function (uri) {
            if (uri === undefined || uri === null) return null;
            var path = String(__aiNativeAppGetPathFromUri(String(uri)));
            return path === '' ? null : path;
        },
        startActivity: function (opts) {
            opts = opts || {};
            return __aiNativeAppStartActivity(
                opts.action || '', opts.packageName || opts.package || '',
                opts.className || opts.class || '', opts.data || '',
                opts.type || '', JSON.stringify(opts.extras || {}),
                opts.flags || 0);
        },
        // 用 getter 惰性取值，与 device 模块一致（Object.freeze 后仍可读）
        get versionCode() { return readAppVersion().code; },
        get versionName() { return readAppVersion().name; },
        get autojs() { var v = readAppVersion(); return { versionCode: v.code, versionName: v.name }; }
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
    var currentEngineExecArgv = null;
    function makeEngineHandle(info) {
        info = info || {};
        var handle = Number(info.handle === undefined ? info : info.handle);
        if (handle === 0 && currentEngineExecArgv === null) currentEngineExecArgv = info.execArgv || {};
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
            execArgv: handle === 0 ? currentEngineExecArgv : (info.execArgv || {}),
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
    var systemEventConsumers = new Set();
    function ensureSystemEventTimer(consumer) {
        systemEventConsumers.add(consumer || 'external');
        if (systemEventTimer === null) {
            systemEventTimer = setInterval(dispatchSystemEvents, 40);
        }
    }
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
            } else if (event.type === 'fingerprint_gesture') {
                events.emit('fingerprint_gesture', event.direction);
            } else if (event.type === 'fingerprint_gesture_available') {
                events.emit('fingerprint_gesture_available', event.available);
            } else if (event.type === 'clip') {
                events.emit('clip_changed', event.text, event);
            } else if (event.type === 'file_observer') {
                if (typeof __aiDispatchFileObserverEvent === 'function') __aiDispatchFileObserverEvent(event);
            } else if (event.type === 'websocket') {
                if (typeof __aiDispatchWebSocketEvent === 'function') __aiDispatchWebSocketEvent(event);
            } else {
                events.emit(event.type, event);
            }
        }
    }
    function observeSystemEvent(kind) {
        var result = !!__aiNativeEventsObserve(String(kind));
        if (result) {
            ensureSystemEventTimer(kind);
        }
        return result;
    }
    function stopSystemEvent(kind) {
        __aiNativeEventsStopObserving(kind);
        systemEventConsumers.delete(kind);
        if (systemEventConsumers.size === 0 && systemEventTimer !== null) {
            clearInterval(systemEventTimer);
            systemEventTimer = null;
        }
    }
    // 与 Rhino/Pro 一致：订阅 clip_changed 才开始监听剪贴板，取消订阅即停止。
    function maybeObserveClipOnSubscribe(name) {
        if (String(name) === 'clip_changed') observeSystemEvent('clip');
    }
    function maybeStopClipOnUnsubscribe(name) {
        if (String(name) !== 'clip_changed') return;
        if (events.listenerCount('clip_changed') === 0) stopSystemEvent('clip');
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
            maybeObserveClipOnSubscribe(name);
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
            maybeStopClipOnUnsubscribe(name);
            return events;
        },
        removeAllListeners: function (name) {
            if (name === undefined) eventListeners.clear();
            else eventListeners.delete(String(name));
            maybeStopClipOnUnsubscribe('clip_changed');
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
            maybeObserveClipOnSubscribe(name);
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
            maybeObserveClipOnSubscribe(name);
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
        observeFingerprintGesture: function () { return observeSystemEvent('fingerprint_gesture'); },
        isFingerprintGestureAvailable: function () { return !!__aiNativeEventsObserve('fingerprint_available'); },
        stopObservingFingerprintGesture: function () { stopSystemEvent('fingerprint_gesture'); return events; },
        observeClip: function () { return observeSystemEvent('clip'); },
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
            systemEventConsumers.clear();
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
        /** 与 Rhino 的 sensors.delay 对齐（单位微秒，等价 SensorManager.SENSOR_DELAY_*）。 */
        delay: Object.freeze({ normal: 200000, ui: 66667, game: 20000, fastest: 0 }),
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
        Delay: Object.freeze({ normal: 200000, ui: 66667, game: 20000, fastest: 0 })
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
        // 参数顺序与 Rhino（__dialogs__.js）及 Auto.js 文档一致：(title, items, selectedIndex)
        singleChoice: function (title, items, selectedIndex) {
            var list = items || [];
            var idx = Number(selectedIndex) || 0;
            var r = dialogWait(Number(__aiNativeDialogsShow(4,
                String(title == null ? '' : title), '', JSON.stringify(list), String(idx))));
            return r && r.index !== undefined ? r.index : -1;
        },
        // 参数顺序：(title, items, selectedIndices)
        multiChoice: function (title, items, selectedIndices) {
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
    var floatyLifecycleHandlers = new Map();
    var floatyPollTimer = null;
    function floatyHandlerKey(windowId, viewId, mode) {
        return windowId + '#' + viewId + '#' + mode;
    }
    function makeFloatyTouchEvent(item) {
        return {
            action: item.action,
            x: item.x,
            y: item.y,
            rawX: item.rawX,
            rawY: item.rawY,
            ACTION_DOWN: 0,
            ACTION_UP: 1,
            ACTION_MOVE: 2,
            ACTION_CANCEL: 3,
            getAction: function () { return item.action; },
            getX: function () { return item.x; },
            getY: function () { return item.y; },
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
            // 窗口挂载/卸载：win.on('attached'/'detached') 的回调。
            if (item.event === 'attached' || item.event === 'detached') {
                var windowHandlers = floatyLifecycleHandlers.get(item.window);
                var list = windowHandlers && windowHandlers[item.event];
                if (list && list.length) list.slice().forEach(function (fn) { fn(); });
                continue;
            }
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
        } else if (mode === 'drag_end') {
            // 原生标题栏在手指抬起时发一次事件，无需再注册会覆盖触摸监听的 JS 监听器。
        } else if (mode === 'key') {
            __aiNativeFloatyViewKey(windowId, viewId);
        } else {
            __aiNativeFloatyViewClick(windowId, viewId, mode);
        }
        ensureFloatyPolling();
    }
    /**
     * 浮窗控件代理：方法与 Rhino 的 NativeView 对齐。
     * - `attr(name[, value])` 读写任意 XML 属性（ViewAttributes）
     * - 属性式访问：`view.text` 读、`view.text = 'x'` 写（走同一套 attr）
     * - `click()/longClick()` 无参时直接触发控件动作，传函数时注册监听
     */
    /**
     * 在主线程同步执行 fn（等价 Rhino 的 `ui.run(fn)`），并返回其返回值。
     *
     * <p>控件与真实 View 的 API（`setPadding`、`ObjectAnimator` 等）只能在主线程调用，
     * 否则系统会抛 `CalledFromWrongThreadException`；用本函数包一层即可：
     * {@code runOnMainThread(function () { view.javaView.setPadding(20, 20, 20, 20); })}。
     */
    function runOnMainThread(fn) {
        if (typeof fn !== 'function') throw new TypeError('runOnMainThread 需要函数');
        var callbackId = global.__aiRegisterCallback(fn);
        try {
            var result = JSON.parse(String(__aiNativeRunOnMain(callbackId)));
            if (result && result.error) throw new Error('主线程执行失败：' + result.error);
            return result ? result.value : undefined;
        } finally {
            global.__aiReleaseCallback(callbackId);
        }
    }
    global.runOnMainThread = runOnMainThread;
    global.runOnUiThread = runOnMainThread;
    global.postToMain = runOnMainThread;

    /** 通用动画入口：控件代理或窗口都能用（内部自动走主线程）。 */
    global.animateView = function (viewOrWindow, props, duration, easing) {
        var target = viewOrWindow && viewOrWindow.javaView ? viewOrWindow.javaView : viewOrWindow;
        animateJavaView(target, props, duration, easing);
        return viewOrWindow;
    };
    /**
     * ObjectAnimator 一行式（内部自动在主线程创建/启动）：
     * objectAnimator(win.c, 'alpha', 1, 0, 300)；直接写 ObjectAnimator.ofFloat 时需自己包 runOnMainThread。
     */
    global.objectAnimator = function (viewOrWindow, property, from, to, duration) {
        var target = viewOrWindow && viewOrWindow.javaView ? viewOrWindow.javaView : viewOrWindow;
        return runOnMainThread(function () {
            var ObjectAnimator = requireJavaClass('android.animation.ObjectAnimator');
            var animator = ObjectAnimator.ofFloat(target, String(property), Number(from), Number(to));
            animator.setDuration(Math.max(0, Math.round(Number(duration === undefined ? 300 : duration) || 0)));
            animator.start();
            return true;
        });
    };

    // ---- 系统动画（ViewPropertyAnimator）：不再用逐帧 sleep 做动画 ----
    var EASING_CLASSES = {
        linear: 'android.view.animation.LinearInterpolator',
        accelerate: 'android.view.animation.AccelerateInterpolator',
        decelerate: 'android.view.animation.DecelerateInterpolator',
        accelerate_decelerate: 'android.view.animation.AccelerateDecelerateInterpolator',
        anticipate: 'android.view.animation.AnticipateInterpolator',
        overshoot: 'android.view.animation.OvershootInterpolator',
        anticipate_overshoot: 'android.view.animation.AnticipateOvershootInterpolator',
        bounce: 'android.view.animation.BounceInterpolator',
        cycle: 'android.view.animation.CycleInterpolator'
    };
    /** 常见缓动别名（easeIn/easeOut/easeInOut 与 ease-in 等写法）。 */
    var EASING_ALIASES = {
        easein: 'accelerate',
        ease_in: 'accelerate',
        easeout: 'decelerate',
        ease_out: 'decelerate',
        easeinout: 'accelerate_decelerate',
        ease_in_out: 'accelerate_decelerate',
        ease: 'accelerate_decelerate',
        default: 'decelerate',
        accel: 'accelerate',
        decel: 'decelerate',
        spring: 'overshoot'
    };
    /** ViewPropertyAnimator 属性别名：scale → scaleX + scaleY。 */
    var ANIMATION_PROPERTY_ALIASES = { scale: ['scaleX', 'scaleY'] };
    /**
     * 用系统 ViewPropertyAnimator 驱动动画：动画在渲染线程执行，脚本不再逐帧 sleep。
     * @param {object} javaView android.view.View 的 Java 代理
     * @param {object} props    { alpha, scaleX, scaleY, x, y, translationX, translationY, rotation… }
     * @param {number} duration 毫秒（默认 300）
     * @param {string} easing   linear/accelerate/decelerate/accelerate_decelerate/…/bounce/cycle
     */
    function animateJavaView(javaView, props, duration, easing) {
        if (javaView === undefined || javaView === null || typeof javaView.animate !== 'function') {
            throw new TypeError('animate 需要可用的 View（控件代理的 javaView 为空）');
        }
        if (props === null || props === undefined || typeof props !== 'object') {
            throw new TypeError('animate(props[, duration[, easing]]) 需要属性对象');
        }
        // ViewPropertyAnimator 必须在主线程操作（脚本线程会抛 CalledFromWrongThreadException）。
        return runOnMainThread(function () {
            var animator = javaView.animate();
            animator.setDuration(Math.max(0, Math.round(Number(duration === undefined ? 300 : duration) || 0)));
            if (easing !== undefined && easing !== null && String(easing) !== '') {
                var key = String(easing).toLowerCase().replace(/-/g, '_');
                if (EASING_ALIASES[key]) key = EASING_ALIASES[key];
                var className = EASING_CLASSES[key];
                if (!className) {
                    throw new Error('未知的缓动函数：' + easing + '（可用：'
                        + Object.keys(EASING_CLASSES).join('/')
                        + '；别名：easeIn/easeOut/easeInOut）');
                }
                animator.setInterpolator(new (requireJavaClass(className))());
            }
            var animated = 0;
            for (var property in props) {
                if (property === 'duration' || property === 'easing') continue;
                var alias = ANIMATION_PROPERTY_ALIASES[property];
                if (alias) {
                    for (var a = 0; a < alias.length; a++) {
                        var aliasSetter = animator[alias[a]];
                        if (typeof aliasSetter !== 'function') {
                            throw new Error('ViewPropertyAnimator 不支持属性：' + property);
                        }
                        aliasSetter.call(animator, Number(props[property]));
                        animated++;
                    }
                    continue;
                }
                var setter = animator[property];
                if (typeof setter !== 'function') {
                    throw new Error('ViewPropertyAnimator 不支持属性：' + property
                        + '（可用：alpha/scaleX/scaleY/x/y/translationX/translationY/rotation，scale 会自动拆成 scaleX+scaleY）');
                }
                setter.call(animator, Number(props[property]));
                animated++;
            }
            animator.start();
            return animated;
        });
    }

    /**
     * 形状名归一化（圆形触摸穿透）：circle/oval | roundRect | rect。
     * circle 与 oval 在 Java 侧都用椭圆轮廓（正方形时就是圆）。
     */
    function normalizeOutlineShape(shape) {
        var name = shape === undefined || shape === null ? 'circle' : String(shape).toLowerCase();
        if (name === 'circle' || name === 'oval' || name === 'round') return 'oval';
        if (name === 'rect' || name === 'none') return 'rect';
        if (name === 'roundrect' || name === 'round_rect' || name === 'card') return 'roundRect';
        throw new Error('未知的轮廓形状：' + shape + '（可用：circle / oval / roundRect / rect）');
    }
    /** 半径参数：数字（px）或 '50%'（→ 圆）/ 其他百分比（需要已知窗口尺寸）。 */
    function normalizeOutlineRadius(radius) {
        if (radius === undefined || radius === null || radius === '') return -1;
        if (typeof radius === 'string' && radius.trim().endsWith('%')) {
            return -1;
        }
        return Math.max(0, Math.round(Number(radius) || 0));
    }
    /** 轮廓配置：{ clipToOutline, outlineShape, outlineRadius }。 */
    function outlineConfigOf(shape, radius) {
        var name = normalizeOutlineShape(shape);
        var cfg = { outlineShape: name, clipToOutline: name !== 'rect' };
        var px = normalizeOutlineRadius(radius);
        if (px >= 0) cfg.outlineRadius = px;
        return cfg;
    }
    /**
     * 按区域输入（圆外穿透）只是“尽力而为”：系统隐藏 API 不可用时给出明确告警，
     * 避免脚本误以为圆外已经不拦截触摸。
     */
    function warnTouchRegionUnsupported() {
        if (floaty.supportsTouchRegion()) return true;
        console.warn('按区域输入不可用（floaty.touchRegionInfo().supported=false）：'
            + '窗口对系统的输入区域始终是矩形，圆外方角仍会拦截触摸。'
            + '建议：容器类窗口用 setTouchable(false) 整窗穿透，菜单项/按钮各自独立小窗口。');
        return false;
    }
    function makeFloatyView(windowId, viewId) {
        var attributeCache = Object.create(null);
        var self = null;
        var view = {
            __windowId: windowId,
            __viewId: viewId,
            /** 真实 android.view.View 的 Java 代理：可直接交给 ObjectAnimator 等系统 API。 */
            get javaView() {
                return javaObjectOf(Number(__aiNativeFloatyViewHandle(windowId, viewId)), 'android.view.View');
            },
            /** 与 javaView 等价（Rhino 里真 View 就叫 view）。 */
            get view() {
                return this.javaView;
            },
            /**
             * 按轮廓裁剪：clipToOutline=true 时控件按自身轮廓（圆形/圆角）裁剪，
             * 等同 XML 的 clipToOutline="true"。
             */
            setClipToOutline: function (enabled) {
                __aiNativeFloatyViewShape(windowId, viewId,
                    JSON.stringify({ clipToOutline: !!enabled }));
                return view;
            },
            /** 轮廓形状：'circle' | 'roundRect'（可选半径 px）| 'rect'（取消裁剪）。 */
            setOutlineShape: function (shape, radius) {
                var cfg = outlineConfigOf(shape, radius);
                __aiNativeFloatyViewShape(windowId, viewId, JSON.stringify(cfg));
                view.__outlineShape = cfg.outlineShape;
                view.__outlineRadius = cfg.outlineRadius;
                return view;
            },
            /** 圆角：数字（px）或 '50%'（正方形 → 圆）。CardView 同时改写 cardCornerRadius。 */
            setCornerRadius: function (radius) {
                if (typeof radius === 'string' && radius.trim().endsWith('%')) {
                    return view.setOutlineShape('circle');
                }
                var px = Math.max(0, Math.round(Number(radius) || 0));
                var cfg = { outlineShape: 'roundRect', outlineRadius: px, clipToOutline: true };
                __aiNativeFloatyViewShape(windowId, viewId, JSON.stringify(cfg));
                try {
                    view.attr('cardCornerRadius', px + 'px');
                } catch (e) {
                    // 非 CardView 是正常的，轮廓仍由自定义 provider 提供。
                }
                return view;
            },
            /**
             * 让控件代理本身就能当 Java 参数用：
             * ObjectAnimator.ofFloat(win.c, 'alpha', 1, 0) 不会再报“无法把 [object Object] 作为 Java 参数传递”。
             */
            get __javaHandle() {
                return Number(__aiNativeFloatyViewHandle(windowId, viewId));
            },
            /** 供 threads 参数序列化识别（窗口代理也带这个标记）。 */
            __floatyView: true,
            /** 系统动画：view.animate({ alpha: 1, scale: 1.2, x: 600 }, 300, 'bounce')。 */
            animate: function (props, duration, easing) {
                animateJavaView(this.javaView, props, duration, easing);
                return self || view;
            },
            /** 立即停止当前动画。 */
            stopAnimation: function () {
                var javaView = this.javaView;
                runOnMainThread(function () { javaView.animate().cancel(); });
                return self || view;
            },
            click: function (fn) {
                if (typeof fn === 'function') {
                    addFloatyHandler(windowId, viewId, 'click', fn);
                    return view;
                }
                __aiNativeFloatyViewAction(windowId, viewId, 'click');
                return view;
            },
            longClick: function (fn) {
                if (typeof fn === 'function') {
                    addFloatyHandler(windowId, viewId, 'long_click', fn);
                    return view;
                }
                __aiNativeFloatyViewAction(windowId, viewId, 'longClick');
                return view;
            },
            on: function (event, fn) {
                event = String(event);
                if (event === 'click') return view.click(fn);
                if (event === 'long_click' || event === 'longClick') return view.longClick(fn);
                if (event === 'key' || event === 'key_down' || event === 'key_up') return view.onKey(fn);
                if (event === 'touch' || event === 'touch_down' || event === 'touch_up'
                        || event === 'touch_move') {
                    return view.onTouch(fn);
                }
                throw new Error('不支持的浮窗控件事件：' + event);
            },
            onKey: function (fn) {
                addFloatyHandler(windowId, viewId, 'key', fn);
                return view;
            },
            onTouch: function (fn) {
                addFloatyHandler(windowId, viewId, 'touch', fn);
                return view;
            },
            setOnTouchListener: function (fn) {
                addFloatyHandler(windowId, viewId, 'touch', fn);
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
            requestFocus: function () {
                __aiNativeFloatyViewRequestFocus(windowId, viewId);
                return view;
            },
            attr: function (name, value) {
                name = String(name);
                if (value === undefined) {
                    var text = __aiNativeFloatyViewGetAttr(windowId, viewId, name);
                    return text === '' ? null : text;
                }
                __aiNativeFloatyViewSetAttr(windowId, viewId, name, toAttributeText(value));
                return view;
            },
            getWidth: function () { return Number(view.attr('width')); },
            getHeight: function () { return Number(view.attr('height')); },
            getVisibility: function () { return Number(view.attr('visibility')); },
            isEnabled: function () { return view.attr('enabled') === true || view.attr('enabled') === 'true'; },
            setEnabled: function (enabled) { return view.attr('enabled', !!enabled); },
            setTextColor: function (color) { return view.attr('textColor', color); },
            setTextSize: function (size) { return view.attr('textSize', size); },
            setBackgroundColor: function (color) { return view.attr('backgroundColor', color); },
            setEnabledState: function (enabled) { return view.attr('enabled', !!enabled); }
        };
        // 属性式读写：读取时先看已知方法/缓存过的属性名，再当属性名查一次。
        // 方法统一包一层：链式调用返回「代理自身」而不是内部原始对象（否则 w.c.setX() === w.c 为 false）。
        var methodCache = new Map();
        var proxy = new Proxy(view, {
            get: function (target, prop) {
                if (typeof prop !== 'string') return undefined;
                if (prop in target) {
                    var value = target[prop];
                    if (typeof value !== 'function') return value;
                    var cached = methodCache.get(prop);
                    if (!cached) {
                        var raw = value;
                        cached = function () {
                            var result = raw.apply(target, arguments);
                            return result === target ? self : result;
                        };
                        methodCache.set(prop, cached);
                    }
                    return cached;
                }
                if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                        || prop === 'toString' || prop === 'constructor') {
                    return undefined;
                }
                var text = __aiNativeFloatyViewGetAttr(windowId, viewId, prop);
                if (text === '') return undefined;
                attributeCache[prop] = true;
                return text;
            },
            set: function (target, prop, value) {
                if (typeof prop !== 'string' || prop in target) {
                    return false;
                }
                __aiNativeFloatyViewSetAttr(windowId, viewId, prop, toAttributeText(value));
                return true;
            }
        });
        self = proxy;
        return proxy;
    }

    /** 属性值转成 Java 侧认的字符串（布尔/数字/字符串）。 */
    function toAttributeText(value) {
        if (value === null || value === undefined) return '';
        if (typeof value === 'string') return value;
        return String(value);
    }

    /** 控件代理缓存：同一个 window+id 复用同一个对象（Rhino 的 NativeView 也是同一实例）。 */
    var floatyViewCache = new Map();
    function floatyViewOf(windowId, viewId) {
        var key = windowId + ':' + viewId;
        var cached = floatyViewCache.get(key);
        if (!cached) {
            cached = makeFloatyView(windowId, viewId);
            floatyViewCache.set(key, cached);
        }
        return cached;
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
            return makeFloatyWindowProxy(id, cfg);
        },
        /** 按 id 取窗口代理：worker 线程（独立引擎）用它操作主脚本创建的窗口。 */
        windowById: function (windowId) {
            var existing = Number(windowId);
            if (!__aiNativeFloatyExists(existing)) throw new Error('窗口不存在或已关闭：' + windowId);
            return makeFloatyWindowProxy(existing, {});
        },
        getWindow: function (windowId) { return floaty.windowById(windowId); },
        exists: function (windowId) { return !!__aiNativeFloatyExists(Number(windowId)); },
        /** 窗口触摸区域能力（隐藏 API 探测）：{ supported, api, android }。 */
        touchRegionInfo: function () { return JSON.parse(String(__aiNativeFloatyTouchRegionInfo())); },
        supportsTouchRegion: function () { return floaty.touchRegionInfo().supported === true; },
        rawWindow: function (config, extra) {
            return floaty.window(config, extra);
        },
        /** 悬浮窗权限（PDF §2.14）：与 Rhino 的 floaty.checkPermission() 同一份宿主实现。 */
        checkPermission: function () { return !!__aiNativeFloatyCheckPermission(); },
        requestPermission: function () { __aiNativeFloatyRequestPermission(); },
        closeAll: function () {
            __aiNativeFloatyCloseAll();
        }
    };
    /**
     * 窗口代理工厂：id + 创建配置 → 可链式操作的窗口对象。
     * 窗口实现在进程级注册表里，所以同一个 id 在任意引擎（含 worker）都能拿到代理。
     */
    function makeFloatyWindowProxy(id, cfg) {
            var onClose = null;
            var exitOnClose = false;            var viewCache = new Map();
            // 坐标/尺寸缓存：setPosition 之后 getX() 立即可用（真实上屏仍由主线程完成，
            // 需要确认已生效时用 getX(true) / getRealX()）。
            var pos = {
                x: cfg.x === undefined ? null : Math.round(Number(cfg.x) || 0),
                y: cfg.y === undefined ? null : Math.round(Number(cfg.y) || 0),
                width: Number(cfg.width) || 0,
                height: Number(cfg.height) || 0
            };
            var scale = { x: 1, y: 1 };
            // self 在代理创建后指向代理本身，保证 setXxx(...) 的返回值可以继续链式调用。
            var self = null;
            var win = {
                id: id,
                /** threads 参数序列化用：窗口代理在 worker 里会被还原成同样的窗口对象。 */
                __floatyWindow: true,
                /** 根 View 句柄：窗口代理也能直接当 Java 参数用（ObjectAnimator.ofFloat(win, …)）。 */
                get __javaHandle() {
                    return Number(__aiNativeFloatyRootViewHandle(id));
                },
                setSize: function (width, height) {
                    pos.width = Math.max(0, Number(width) || 0);
                    pos.height = Math.max(0, Number(height) || 0);
                    __aiNativeFloatyUpdate(id, JSON.stringify({ width: pos.width, height: pos.height }));
                    return self || win;
                },
                setPosition: function (x, y) {
                    pos.x = Math.round(Number(x) || 0);
                    pos.y = Math.round(Number(y) || 0);
                    __aiNativeFloatyUpdate(id, JSON.stringify({ x: pos.x, y: pos.y }));
                    return self || win;
                },
                /**
                 * getX() 返回当前设定坐标（setPosition 后立即生效，且跨引擎一致）；
                 * getX(true) 会在主线程 flush 后再读一次。
                 */
                getX: function (real) {
                    return real ? Number(__aiNativeFloatyGetRealX(id)) : Number(__aiNativeFloatyGetX(id));
                },
                getY: function (real) {
                    return real ? Number(__aiNativeFloatyGetRealY(id)) : Number(__aiNativeFloatyGetY(id));
                },
                getRealX: function () { return Number(__aiNativeFloatyGetRealX(id)); },
                getRealY: function () { return Number(__aiNativeFloatyGetRealY(id)); },
                getWidth: function () { return Number(__aiNativeFloatyGetWidth(id)); },
                getHeight: function () { return Number(__aiNativeFloatyGetHeight(id)); },
                // ---- 窗口级显隐（原子，返回后即生效）----
                show: function () { return !!__aiNativeFloatySetVisibility(id, 0); },
                hide: function () { return !!__aiNativeFloatySetVisibility(id, 8); },
                setVisibility: function (visibility) {
                    return !!__aiNativeFloatySetVisibility(id, Number(visibility) || 0);
                },
                isShown: function () { return !!__aiNativeFloatyIsVisible(id); },
                isVisible: function () { return !!__aiNativeFloatyIsVisible(id); },
                /** 只切根 View 可见性（最快，~0ms），适合高频开合菜单；不会释放窗口。 */
                setContentVisible: function (visible) {
                    __aiNativeFloatySetContentVisibility(id, visible === false || Number(visible) === 8 || Number(visible) === 4 ? 8 : 0);
                    return self || win;
                },
                setContentVisibility: function (visibility) {
                    __aiNativeFloatySetContentVisibility(id, Number(visibility) || 0);
                    return self || win;
                },
                /** 真实根 View 的 Java 代理（整窗动画 / 任意 View API）。 */
                get javaView() {
                    return javaObjectOf(Number(__aiNativeFloatyRootViewHandle(id)), 'android.view.View');
                },
                /** 与 javaView 等价（Rhino 里窗口真 View 也叫 view）。 */
                get view() {
                    return this.javaView;
                },
                /** 窗口级轮廓裁剪（配合 setOutlineShape / setCornerRadius 使用）。 */
                setClipToOutline: function (enabled) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ clipToOutline: !!enabled }));
                    return self || win;
                },
                /**
                 * 轮廓形状：'circle'（正方形即圆）| 'roundRect'（可选半径）| 'rect'（取消）。
                 * 设置后窗口按轮廓裁剪；未显式关闭时窗口触摸区域会自动跟随圆形轮廓。
                 */
                setOutlineShape: function (shape, radius) {
                    var cfg = outlineConfigOf(shape, radius);
                    __aiNativeFloatyUpdate(id, JSON.stringify(cfg));
                    win.__outlineShape = cfg.outlineShape;
                    win.__outlineRadius = cfg.outlineRadius;
                    return self || win;
                },
                /** 圆角：数字（px）或 '50%'（→ 圆）。 */
                setCornerRadius: function (radius) {
                    if (typeof radius === 'string' && radius.trim().endsWith('%')) {
                        return win.setOutlineShape('circle');
                    }
                    return win.setOutlineShape('roundRect', radius);
                },
                /**
                 * 一步到位（圆形触摸穿透 P0-2）：轮廓 + 触摸区域同时按形状收缩，
                 * 圆外的事件不再命中本窗口，直接落到下层 App。
                 */
                setShape: function (shape, radius) {
                    var cfg = outlineConfigOf(shape, radius);
                    cfg.touchShape = cfg.outlineShape === 'rect' ? 'rect' : 'circle';
                    cfg.touchableOnlyInShape = cfg.outlineShape !== 'rect';
                    __aiNativeFloatyUpdate(id, JSON.stringify(cfg));
                    if (cfg.touchableOnlyInShape) warnTouchRegionUnsupported();
                    return self || win;
                },
                /** 触摸形状：'circle'（圆外穿透）| 'rect'（恢复整窗矩形）。 */
                setTouchShape: function (shape) {
                    var name = shape === undefined || shape === null
                        ? 'circle' : String(shape).toLowerCase();
                    var circle = name === 'circle' || name === 'oval' || name === 'round';
                    __aiNativeFloatyUpdate(id, JSON.stringify({
                        touchShape: circle ? 'circle' : 'rect',
                        touchableOnlyInShape: circle
                    }));
                    if (circle) warnTouchRegionUnsupported();
                    return self || win;
                },
                /** 显式圆形触摸区域：中心 (x, y) 与半径 r（窗口内坐标，px）。 */
                setTouchableRegion: function (x, y, r) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({
                        touchCenterX: Math.round(Number(x) || 0),
                        touchCenterY: Math.round(Number(y) || 0),
                        touchRadius: Math.max(1, Math.round(Number(r) || 0)),
                        touchShape: 'circle',
                        touchableOnlyInShape: true
                    }));
                    warnTouchRegionUnsupported();
                    return self || win;
                },
                /** 恢复整窗矩形触摸区域。 */
                clearTouchRegion: function () {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ touchableOnlyInShape: false }));
                    return self || win;
                },
                /** 是否只让形状内区域接收触摸（默认自动跟随圆形轮廓）。 */
                setTouchableOnlyInShape: function (enabled) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ touchableOnlyInShape: !!enabled }));
                    return self || win;
                },
                /** 当前触摸区域快照：{ supported, shape, cx, cy, r, touchable }。 */
                getTouchRegion: function () {
                    return JSON.parse(String(__aiNativeFloatyGetTouchRegion(id)));
                },
                /** 当前设备是否支持“圆外穿透”（隐藏 API 探测）。 */
                isTouchableRegionSupported: function () {
                    return floaty.supportsTouchRegion();
                },
                /** 整窗系统动画：win.animate({ alpha: 1, scaleX: 1.2 }, 300, 'decelerate')。 */
                animate: function (props, duration, easing) {
                    animateJavaView(this.javaView, props, duration, easing);
                    return self || win;
                },
                stopAnimation: function () {
                    var rootView = this.javaView;
                    runOnMainThread(function () { rootView.animate().cancel(); });
                    return self || win;
                },
                // ---- 窗口级透明度 / 缩放（直接作用于根 View，不重排布局）----
                setAlpha: function (alpha) {
                    __aiNativeFloatySetAlpha(id, Math.max(0, Math.min(1, Number(alpha))));
                    return self || win;
                },
                getAlpha: function () { return Number(__aiNativeFloatyGetAlpha(id)); },
                setScale: function (scaleX, scaleY) {
                    scale.x = Number(scaleX);
                    scale.y = scaleY === undefined ? Number(scaleX) : Number(scaleY);
                    __aiNativeFloatySetScale(id, scale.x, scale.y);
                    return self || win;
                },
                setScaleX: function (scaleX) {
                    scale.x = Number(scaleX);
                    __aiNativeFloatySetScale(id, scale.x, scale.y);
                    return self || win;
                },
                setScaleY: function (scaleY) {
                    scale.y = Number(scaleY);
                    __aiNativeFloatySetScale(id, scale.x, scale.y);
                    return self || win;
                },
                findView: function (viewId) {
                    viewId = String(viewId);
                    if (!__aiNativeFloatyViewExists(id, viewId)) return null;
                    return floatyViewOf(id, viewId);
                },
                setText: function (text) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ text: String(text == null ? '' : text) }));
                    return self || win;
                },
                setBackgroundColor: function (color) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ backgroundColor: String(color) }));
                    return self || win;
                },
                setTouchable: function (touchable) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ touchable: !!touchable }));
                    return self || win;
                },
                /** 可按住拖动窗口（与 setAdjustEnabled(true) 等价；触摸穿透与此无关）。 */
                setDraggable: function (draggable) {
                    __aiNativeFloatyUpdate(id, JSON.stringify({ draggable: !!draggable }));
                    return self || win;
                },
                setAdjustEnabled: function (enabled) {
                    __aiNativeFloatySetAdjustable(id, !!enabled);
                    return self || win;
                },
                /** 指定一块标题控件用原生 UI 线程拖动；回调只在拖动结束时调用。 */
                setDragHandle: function (viewId, onEnd) {
                    viewId = String(viewId);
                    if (typeof onEnd === 'function') addFloatyHandler(id, viewId, 'drag_end', onEnd);
                    __aiNativeFloatySetDragHandle(id, viewId);
                    return self || win;
                },
                /** 窗口是否仍存在（可被 worker 线程检查）。 */
                exists: function () { return !!__aiNativeFloatyExists(id); },
                /**
                 * 线程安全的窗口更新：在主线程同步执行 fn（可传入 worker 线程使用），
                 * 并回传返回值；delay 为可选延迟。
                 */
                post: function (fn, delay) {
                    if (typeof fn !== 'function') throw new TypeError('window.post 需要函数');
                    if (delay === undefined || Number(delay) <= 0) return runOnMainThread(fn);
                    var result;
                    var done = false;
                    setTimeout(function () {
                        try { result = runOnMainThread(fn); } finally { done = true; }
                    }, Math.max(0, Number(delay) || 0));
                    while (!done) sleep(4);
                    return result;
                },
                /**
                 * 窗口事件：'close' | 'attached'（已上屏）| 'detached'（已离屏）。
                 * attached/detached 由创建它的引擎接收。
                 */
                on: function (event, fn) {
                    if (typeof fn !== 'function') throw new TypeError('listener must be a function');
                    var name = String(event);
                    if (name === 'close') return win.onClose(fn);
                    if (name === 'attached' || name === 'detached') {
                        if (!floatyLifecycleHandlers.has(id)) {
                            floatyLifecycleHandlers.set(id, { attached: [], detached: [] });
                        }
                        floatyLifecycleHandlers.get(id)[name].push(fn);
                        ensureFloatyPolling();
                        return self || win;
                    }
                    throw new Error('不支持的窗口事件：' + name + '（可用：close/attached/detached）');
                },
                isAdjustEnabled: function () { return !!__aiNativeFloatyIsAdjustable(id); },
                requestFocus: function () {
                    __aiNativeFloatySetWindowFocusable(id, true);
                    return self || win;
                },
                disableFocus: function () {
                    __aiNativeFloatySetWindowFocusable(id, false);
                    return self || win;
                },
                resize: function (width, height) {
                    win.setSize(width, height);
                },
                onClose: function (fn) {
                    if (typeof fn !== 'function') throw new TypeError('listener must be a function');
                    onClose = fn;
                    return self || win;
                },
                exitOnClose: function () {
                    exitOnClose = true;
                    return self || win;
                },
                close: function () {
                    if (onClose) onClose(win);
                    __aiNativeFloatyClose(id);
                    if (exitOnClose && typeof global.exit === 'function') global.exit();
                }
            };
            // window.<id> 解析成控件代理；不存在的 id 返回 undefined（与 Rhino 的 findView 回退一致）。
            var proxy = new Proxy(win, {
                get: function (target, prop) {
                    if (typeof prop !== 'string') return undefined;
                    if (prop in target) return target[prop];
                    if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                            || prop === 'toString' || prop === 'constructor') {
                        return undefined;
                    }
                    var cached = viewCache.get(prop);
                    if (cached) return cached;
                    if (!__aiNativeFloatyViewExists(id, prop)) return undefined;
                    cached = floatyViewOf(id, prop);
                    viewCache.set(prop, cached);
                    return cached;
                }
            });
            self = proxy;
            return proxy;
    }

    // task-203：第三方 Node 后端（xiangqi）在 host-compat/headless-window 里会覆盖
    // global.floaty.window / rawWindow 做逻辑窗口；保持与 Rhino 一致，floaty 可写。
    global.floaty = floaty;

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
            var view = uiView(String(ev.id));
            var entry = uiEventListeners.get(String(ev.id));
            if (entry && entry[ev.event]) {
                entry[ev.event].slice().forEach(function (fn) {
                    if (ev.event === 'item_click' || ev.event === 'item_long_click') {
                        fn(Number(ev.index), view);
                    } else {
                        fn(view);
                    }
                });
            }
            // 与 Rhino 一致：控件事件同时挂在 ui.emitter 上。
            uiRootEmitter.emit(ev.event, view);
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
    var uiJsInterfaceNames = [];

    // ---- JavaAdapter：Rhino 会动态生成 Java 子类，QuickJS 里改为宿主预置子类 ----
    // 目前支持 android.webkit.WebViewClient / WebChromeClient（WebView 脚本最常用的两个），
    // 覆盖的方法在宿主子类里转发回脚本；其它基类会抛错（而不是静默失败）。
    global.JavaAdapter = function (base, overrides) {
        var baseHandle = base === null || base === undefined ? undefined : base.__javaHandle;
        if (baseHandle === undefined) {
            throw new TypeError('JavaAdapter: 第一个参数必须是 Java 类');
        }
        var implementation = overrides === null || overrides === undefined ? {} : overrides;
        var callbackId = global.__aiRegisterCallback(function (payload) {
            try {
                if (payload === null || payload === undefined) return null;
                var handler = implementation[String(payload.method)];
                if (typeof handler !== 'function') return null;
                // payload.args 里形如 {"__ref":handle} 的 Java 对象参数必须先解码成
                // 互操作代理，否则脚本拿到的只是 {__ref:...} 普通对象
                // （现象：result.confirm() 报 TypeError: not a function）。
                var handlerArgs = Array.isArray(payload.args)
                        ? payload.args.map(decodeCallbackArg) : [];
                var result = handler.apply(implementation, handlerArgs);
                return result === undefined ? null : result;
            } catch (error) {
                // 静默吞掉会让 WebView 侧的怪现象（例如 alert 之后整页失去响应）无从排查，
                // 因此这里必须把异常打到日志里。
                try {
                    console.error('JavaAdapter 回调 ' + String(payload.method) + ' 出错: ' + error);
                } catch (reportError) { }
                return null;
            }
        });
        var created = decodeJavaResult(__aiNativeJavaAdapterCreate(baseHandle, callbackId));
        if (!created) {
            var name = base.__className !== undefined ? String(base.__className) : String(base);
            throw new TypeError('JavaAdapter: 暂不支持继承 ' + name
                    + '（目前支持 android.webkit.WebViewClient / android.webkit.WebChromeClient）');
        }
        return created;
    };

    /** 页面桥 shim：把 window[name] 换成转发到 @JavascriptInterface 方法 __ajs 的 Proxy。 */
    function injectJsInterfaceShims(html) {
        if (!uiJsInterfaceNames.length) return html;
        var script = '<script>(function(){';
        for (var i = 0; i < uiJsInterfaceNames.length; i++) {
            var n = JSON.stringify(uiJsInterfaceNames[i]);
            script += 'var b' + i + '=window[' + n + '];'
                    + 'if(b' + i + '&&!b' + i + '.__ajsWrapped){'
                    + 'b' + i + '.__ajsWrapped=1;'
                    + 'window[' + n + ']=new Proxy({},{get:function(t,p){'
                    + 'if(typeof p!=="string"||p==="then"||p==="toString"||p==="valueOf"||p==="constructor")return undefined;'
                    + 'return function(){var a=Array.prototype.slice.call(arguments);'
                    + 'try{return b' + i + '.__ajs(p,JSON.stringify(a));}catch(e){return undefined;}};}});'
                    + '}';
        }
        script += '})();<\/script>';
        var idx = html.indexOf('</head>');
        if (idx >= 0) return html.substring(0, idx) + script + html.substring(idx);
        idx = html.indexOf('<body');
        if (idx >= 0) return html.substring(0, idx) + script + html.substring(idx);
        return script + html;
    }

    function uiView(id) {
        id = String(id);
        return new Proxy({
            __id: id,
            setText: function (text) {
                uiSet(id, { text: String(text == null ? '' : text) });
                return this;
            },
            getText: function () {
                return uiReadText(id);
            },
            setVisibility: function (visibility) {
                uiSet(id, { visibility: Number(visibility) || 0 });
                return this;
            },
            setBackgroundColor: function (color) {
                uiSet(id, { backgroundColor: String(color) });
                return this;
            },
            setTextColor: function (color) {
                uiSet(id, { textColor: String(color) });
                return this;
            },
            setTextSize: function (size) {
                uiSet(id, { textSize: Number(size) || 0 });
                return this;
            },
            setEnabled: function (enabled) {
                uiSet(id, { enabled: !!enabled });
                return this;
            },
            setDataSource: function (data) {
                if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
                __aiNativeUiSetDataSource(uiViewId, id, JSON.stringify(Array.isArray(data) ? data : []));
                return this;
            },
            click: function (fn) {
                if (typeof fn !== 'function') {
                    if (uiViewId > 0) __aiNativeUiViewAction(uiViewId, id, 'click');
                    return this;
                }
                uiListen(id, 'click', fn);
                return this;
            },
            longClick: function (fn) {
                if (typeof fn !== 'function') {
                    if (uiViewId > 0) __aiNativeUiViewAction(uiViewId, id, 'longClick');
                    return this;
                }
                uiListen(id, 'long_click', fn);
                return this;
            },
            on: function (name, fn) {
                uiListen(id, name, fn);
                return this;
            },
            attr: function (name, value) {
                if (value === undefined) {
                    if (uiViewId <= 0) throw new Error('ui.layout() must be called first');
                    return __aiNativeUiGetAttr(uiViewId, id, String(name));
                }
                var config = {};
                config[String(name)] = value;
                uiSet(id, config);
                return this;
            },
            getWidth: function () { return Number(this.attr('width')); },
            getHeight: function () { return Number(this.attr('height')); },
            getVisibility: function () { return Number(this.attr('visibility')); }
        }, {
            // ui.btn.text 这类属性式读写（Rhino 的 NativeView 通过 ViewAttributes 支持）
            get: function (target, prop) {
                if (typeof prop !== 'string' || prop in target) return target[prop];
                if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                        || prop === 'toString' || prop === 'constructor') {
                    return undefined;
                }
                if (uiViewId <= 0) return undefined;
                // task-149：先读 ViewAttributes（ui.title.text 必须返回字符串）。
                // 不能先探 JavaBean：TextView.getText 会让 .text 返回函数。
                var attrValue = __aiNativeUiGetAttr(uiViewId, id, prop);
                if (attrValue !== '' && attrValue !== undefined && attrValue !== null) {
                    return attrValue;
                }
                // 控件的 Java 成员（ui.wv.loadUrl(...) / getSettings() 这类）：探到方法或
                // JavaBean 属性就返回可调用包装，参数/返回值走与 java.* 相同的编码。
                var member = String(__aiNativeUiViewProbe(uiViewId, id, prop));
                if (member === 'm' || (member.length > 1 && member.charAt(0) === 'p')) {
                    if (prop === 'addJavascriptInterface') {
                        return function (object, name) {
                            // JS 对象注册为页面桥：页面里 window[name].xxx(...) 同步回到脚本对象的方法。
                            var target = object;
                            var callbackId = global.__aiRegisterCallback(function (payload) {
                                try {
                                    if (target == null || payload == null) return null;
                                    var fn = target[payload.method];
                                    if (typeof fn !== 'function') return null;
                                    var result = fn.apply(target, Array.isArray(payload.args) ? payload.args : []);
                                    if (result === undefined || result === null) return null;
                                    return typeof result === 'string' ? result : String(result);
                                } catch (error) {
                                    return null;
                                }
                            });
                            uiJsInterfaceNames.push(String(name));
                            return __aiNativeUiAddJsInterface(uiViewId, id, callbackId, String(name));
                        };
                    }
                    if (prop === 'loadDataWithBaseURL' || prop === 'loadData') {
                        return function () {
                            var args = Array.prototype.slice.call(arguments);
                            var dataIndex = prop === 'loadDataWithBaseURL' ? 1 : 0;
                            if (typeof args[dataIndex] === 'string') {
                                args[dataIndex] = injectJsInterfaceShims(args[dataIndex]);
                            }
                            return decodeJavaResult(__aiNativeUiViewInvoke(uiViewId, id, prop,
                                    JSON.stringify(encodeJavaArgs(args))));
                        };
                    }
                    return function () {
                        return decodeJavaResult(__aiNativeUiViewInvoke(uiViewId, id, prop,
                                JSON.stringify(encodeJavaArgs(Array.prototype.slice.call(arguments)))));
                    };
                }
                if (member === 'f') {
                    return decodeJavaResult(__aiNativeUiViewInvoke(uiViewId, id, prop, '[]'));
                }
                var text = __aiNativeUiGetAttr(uiViewId, id, prop);
                return text === '' ? undefined : text;
            },
            set: function (target, prop, value) {
                if (typeof prop !== 'string' || prop in target) return false;
                var config = {};
                config[prop] = value;
                uiSet(id, config);
                return true;
            }
        });
    }

    /** 极简 EventEmitter：ui.emitter（Rhino 里来自 UI Activity 的 EventEmitter）。 */
    function makeEmitter() {
        var listeners = new Map();
        var emitter = {
            on: function (name, fn) {
                if (typeof fn !== 'function') throw new TypeError('listener must be a function');
                name = String(name);
                (listeners.get(name) || listeners.set(name, []).get(name)).push(fn);
                return emitter;
            },
            once: function (name, fn) {
                function wrapper() {
                    emitter.removeListener(name, wrapper);
                    return fn.apply(undefined, arguments);
                }
                wrapper.listener = fn;
                return emitter.on(name, wrapper);
            },
            off: function (name, fn) { return emitter.removeListener(name, fn); },
            removeListener: function (name, fn) {
                var list = listeners.get(String(name));
                if (list) {
                    for (var i = list.length - 1; i >= 0; i--) {
                        if (list[i] === fn || list[i].listener === fn) list.splice(i, 1);
                    }
                }
                return emitter;
            },
            removeAllListeners: function (name) {
                if (name === undefined) listeners.clear();
                else listeners.delete(String(name));
                return emitter;
            },
            emit: function (name, payload) {
                var list = listeners.get(String(name));
                if (!list || !list.length) return false;
                list.slice().forEach(function (fn) { fn(payload); });
                return true;
            },
            eventNames: function () { return Array.from(listeners.keys()); },
            listenerCount: function (name) {
                var list = listeners.get(String(name));
                return list ? list.length : 0;
            },
            listeners: function (name) { return (listeners.get(String(name)) || []).slice(); }
        };
        return emitter;
    }
    // `"ui"` 模式的脚本跑在 ScriptExecuteActivity 里：宿主 Activity 由引擎注入，
    // 这里暴露成脚本里的 `activity` 全局（Rhino 里靠 engine.put("activity", this)）。
    // 普通脚本没有宿主——对应 Rhino 的 `typeof activity == 'undefined'`。
    Object.defineProperty(global, 'activity', {
        configurable: true,
        enumerable: true,
        get: function () {
            var ref = String(__aiNativeHostActivity());
            if (ref.length === 0) return undefined;
            var decoded = decodeJavaResult(ref);
            return decoded === null ? undefined : decoded;
        }
    });
    var ui = {
        layout: function (xml) {
            var id = Number(__aiNativeUiInflate(String(xml)));
            if (id < 0) throw new Error('Unable to inflate UI layout');
            uiViewId = id;
            // UI 存续期间事件循环必须活着：控件事件轮询、页面桥回调（postJsCallback）
            // 都靠它派发；Rhino 的 ui 模式同样是「界面在则脚本在」。
            if (uiPollTimer === null) {
                uiPollTimer = setInterval(uiDispatchEvents, 60);
            }
            return id;
        },
        layoutFile: function (file) {
            return ui.layout(files.read(String(file)));
        },
        findView: function (id) {
            id = String(id);
            if (uiViewId <= 0 || !__aiNativeUiViewExists(uiViewId, id)) return null;
            return uiView(id);
        },
        close: function () {
            __aiNativeUiClose();
            uiViewId = 0;
            uiEventListeners.clear();
            uiJsInterfaceNames.length = 0;
            if (uiPollTimer !== null) {
                clearInterval(uiPollTimer);
                uiPollTimer = null;
            }
        },
        /** Rhino 的 ui.finish() 关闭脚本界面；QuickJS 的 ui 是覆盖层，语义等价于 close()。 */
        finish: function () { ui.close(); },
        isUiThread: function () { return !!__aiNativeIsMainThread(); },
        /** 跨线程回调通路：延迟 delay 毫秒后在脚本引擎线程执行 fn。 */
        post: function (fn, delay) {
            if (typeof fn !== 'function') throw new TypeError('ui.post 需要函数');
            // JS callbacks execute on the engine thread. A native timer also
            // keeps the engine alive until this delayed callback has run.
            return setTimeout(fn, Math.max(0, Number(delay) || 0));
        },
        statusBarColor: function (color) { __aiNativeStatusBarColor(String(color)); },
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
        },
        emitter: null,
        activity: null,
        view: null
    };
    // ui.<id> / $ui.<id> 都解析成控件代理；也把控件事件转发给 ui.emitter。
    var uiRootEmitter = makeEmitter();
    ui.emitter = uiRootEmitter;
    global.ui = new Proxy(ui, {
        get: function (target, prop) {
            if (typeof prop !== 'string') return undefined;
            if (prop in target) return target[prop];
            if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                    || prop === 'toString' || prop === 'constructor') {
                return undefined;
            }
            if (uiViewId <= 0 || !__aiNativeUiViewExists(uiViewId, prop)) return undefined;
            return uiView(prop);
        }
    });
    global.$ui = new Proxy({}, {
        get: function (target, name) {
            if (name === 'layout') return global.ui.layout;
            if (name === 'findView') return global.ui.findView;
            if (name === 'close' || name === 'finish') return global.ui[name];
            if (name === 'emitter') return global.ui.emitter;
            if (typeof name !== 'string') return undefined;
            if (uiViewId <= 0 || !__aiNativeUiViewExists(uiViewId, name)) return undefined;
            return uiView(name);
        }
    });

    // ---- threads module (one QuickJS engine per worker) ----
    /**
     * worker 参数序列化：窗口代理（`{win: win}`）会变成 `{__floatyWindowId: n}`，
     * worker 侧由 __aiReviveFloatyArgs 还原成同 id 的窗口代理，脚本写法不用改。
     */
    function threadArgsJson(args) {
        if (args === undefined) return '';
        return JSON.stringify(args, function (key, value) {
            if (value && value.__floatyWindow === true && value.id !== undefined) {
                return { __floatyWindowId: Number(value.id) };
            }
            return value;
        });
    }
    /** worker 侧：把 `{__floatyWindowId}` 还原成窗口代理（递归处理对象/数组）。 */
    global.__aiReviveFloatyArgs = function revive(value) {
        if (value === null || value === undefined) return value;
        if (typeof value !== 'object') return value;
        if (value.__floatyWindowId !== undefined) {
            try {
                return floaty.getWindow(Number(value.__floatyWindowId));
            } catch (error) {
                return null;   // 窗口已关闭
            }
        }
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) value[i] = revive(value[i]);
            return value;
        }
        for (var key in value) {
            if (Object.prototype.hasOwnProperty.call(value, key)) value[key] = revive(value[key]);
        }
        return value;
    };
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
            var argsJson = threadArgsJson(args);
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
            var argsJson = threadArgsJson(args);
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

    // ---- JS 回调：Java 侧（选择器谓词等）可以同步调用脚本里的函数 ----
    // 脚本函数登记在 jsCallbacks 里，Java 侧只传 id；回调参数里形如 {"__node":handle}
    // 的描述符会在这里换回控件对象，返回值统一编码成 JSON 交给 Java。
    var jsCallbacks = Object.create(null);
    var jsCallbackSeq = 0;

    function decodeCallbackArg(value) {
        if (value === null || typeof value !== 'object') return value;
        if (value.__node !== undefined) return wrapAutomatorObject(Number(value.__node));
        if (value.__selector !== undefined) return wrapAutomatorSelector(Number(value.__selector));
        // Java 对象参数（适配器回调里的 View/JsResult 等）走与互操作相同的句柄协议。
        if (value.__ref !== undefined) return decodeJavaValue(value);
        if (Array.isArray(value)) return value.map(decodeCallbackArg);
        return value;
    }

    function encodeCallbackValue(value) {
        var type = typeof value;
        if (value === undefined) return { type: 'undefined', value: null };
        if (value === null || type === 'string' || type === 'number' || type === 'boolean') {
            return { type: type, value: value };
        }
        // 对象/函数返回给 Java 谓词时按真值处理（与 JS 布尔上下文一致）。
        return { type: type, value: true };
    }

    global.__aiRegisterCallback = function (callback) {
        if (typeof callback !== 'function') throw new TypeError('回调必须是函数');
        var id = ++jsCallbackSeq;
        jsCallbacks[id] = callback;
        return id;
    };
    global.__aiReleaseCallback = function (id) { delete jsCallbacks[Number(id)]; };
    global.__aiInvokeCallback = function (id, argsJson) {
        return runJsCallback(id, argsJson);
    };
    /** 真正的分发实现：Java → JS 同步回调（选择器谓词）与引擎线程上的异步任务共用。 */
    function runJsCallback(id, argsJson) {
        var callback = jsCallbacks[Number(id)];
        if (typeof callback !== 'function') {
            return JSON.stringify({ error: '回调 #' + id + ' 不存在（可能已被回收）' });
        }
        try {
            var raw = JSON.parse(argsJson === undefined || argsJson === null ? '[]' : String(argsJson));
            var args = Array.isArray(raw) ? raw.map(decodeCallbackArg) : [decodeCallbackArg(raw)];
            return JSON.stringify(encodeCallbackValue(callback.apply(null, args)));
        } catch (e) {
            var message = e === null || e === undefined ? '回调执行失败' : String(e.message === undefined ? e : e.message);
            return JSON.stringify({ error: message });
        }
    }

    /**
     * 跨线程任务的统一入口：native 事件循环在引擎线程上调用它。
     * 目前三种任务：callback（回调结果）、call（页面调脚本全局函数）、eval（页面请求求值）。
     */
    global.__aiRunJsTask = function (taskJson) {
        var task = JSON.parse(taskJson);
        switch (task.kind) {
            case 'callback': {
                var result = JSON.parse(runJsCallback(task.id, task.args));
                return result.error === undefined ? '' : String(result.error);
            }
            case 'call': {
                var fn = global[String(task.name)];
                if (typeof fn !== 'function') {
                    return '页面调用了不存在的脚本函数：' + task.name;
                }
                var callArgs = JSON.parse(task.args === undefined ? '[]' : String(task.args))
                    .map(decodeCallbackArg);
                try {
                    fn.apply(null, callArgs);
                } catch (e) {
                    return e === null || e === undefined ? '脚本函数执行失败' : String(e.message || e);
                }
                return '';
            }
            case 'eval':
                try {
                    (0, eval)(String(task.code));
                } catch (e) {
                    return e === null || e === undefined ? '脚本求值失败' : String(e.message || e);
                }
                return '';
            default:
                return '未知的跨线程任务类型：' + task.kind;
        }
    };

    // ---- Java 互操作（与 Rhino 相同：任意 public 反射）----
    // 类与实例都用 long 句柄跨边界，JS 侧是 Proxy：
    //   encodeJavaArg / encodeJavaArgs 把 JS 值编成 JSON + {"__ref":handle}
    //   decodeJavaResult 把 Java 返回值换回 JS 值（对象包成代理，同一个句柄复用同一代理）
    var javaObjectCache = new Map();
    var javaClassCache = new Map();
    var importedClasses = Object.create(null);
    var importedPackages = [];
    var javaNameCache = Object.create(null);

    function encodeJavaArg(value) {
        if (value === undefined || value === null) return null;
        var type = typeof value;
        if (type === 'number' || type === 'string' || type === 'boolean') return value;
        if (value.__javaHandle !== undefined) {
            return { __ref: Number(value.__javaHandle) };
        }
        if (Array.isArray(value)) {
            return value.map(encodeJavaArg);
        }
        if (type === 'function' && value.__javaClass) {
            return { __ref: Number(value.__javaHandle) };
        }
        throw new TypeError('无法把 ' + Object.prototype.toString.call(value)
            + ' 作为 Java 参数传递（支持数字/字符串/布尔/null/数组/Java 对象）');
    }

    function encodeJavaArgs(args) {
        var result = [];
        for (var i = 0; i < args.length; i++) result.push(encodeJavaArg(args[i]));
        return result;
    }

    function decodeJavaResult(text) {
        if (text === null || text === undefined || text === '') return undefined;
        var value;
        try {
            value = JSON.parse(String(text));
        } catch (e) {
            return String(text);
        }
        return decodeJavaValue(value);
    }

    function decodeJavaValue(value) {
        if (value === null || value === undefined) return value;
        if (Array.isArray(value)) return value.map(decodeJavaValue);
        if (typeof value !== 'object') return value;
        if (value.__ref !== undefined) {
            return value.__isClass
                ? javaClassOf(Number(value.__ref), value.__class)
                : javaObjectOf(Number(value.__ref), value.__class);
        }
        return value;
    }

    function javaMemberOf(handle, holder, prop, isClass) {
        var cached = holder.cache.get(prop);
        if (cached === undefined) {
            cached = String(__aiNativeJavaProbe(handle, prop));
            holder.cache.set(prop, cached);
        }
        if (cached === 'm') {
            return function () {
                return decodeJavaResult(__aiNativeJavaCall(
                    handle, prop, JSON.stringify(encodeJavaArgs(arguments))));
            };
        }
        if (cached === 'f') {
            return decodeJavaResult(__aiNativeJavaGetField(handle, prop));
        }
        // p:<getter>：Rhino 的 JavaBean 语义，file.path 这类写法直接给值。
        if (cached.length > 2 && cached.charAt(0) === 'p' && cached.charAt(1) === ':') {
            return decodeJavaResult(__aiNativeJavaCall(handle, cached.substring(2), '[]'));
        }
        // c：嵌套类（android.os.Build.VERSION 这类写法，与 Rhino 一致）
        if (cached === 'c') {
            var nestedName = String(__aiNativeJavaNestedClassName(handle, prop));
            return nestedName === '' ? undefined : requireJavaClass(nestedName);
        }
        return undefined;
    }

    function javaObjectOf(handle, className) {
        var cached = javaObjectCache.get(handle);
        if (cached) return cached;
        var cache = new Map();
        var target = { __javaHandle: handle, __className: className };
        var proxy = new Proxy(target, {
            get: function (self, prop) {
                if (prop === '__javaHandle' || prop === '__className') return self[prop];
                if (typeof prop !== 'string') return undefined;
                // then 必须为空，否则会被当作 thenable；其余成员交给 Java 侧解析（包括 toString/valueOf）。
                if (prop === 'then') return undefined;
                return javaMemberOf(handle, { cache: cache }, prop, false);
            },
            set: function (self, prop, value) {
                if (typeof prop !== 'string' || self[prop] !== undefined) return false;
                var encoded = JSON.stringify(encodeJavaArg(value));
                var kind = self.__javaSetters__ || (self.__javaSetters__ = new Map());
                var cached = kind.get(prop);
                if (cached === undefined) {
                    cached = String(__aiNativeJavaProbe(handle, prop));
                    kind.set(prop, cached);
                }
                if (cached === 'f') {
                    return __aiNativeJavaSetField(handle, prop, encoded);
                }
                // JavaBean 属性写入：set<Name>(value)
                if (cached.length > 2 && cached.charAt(0) === 'p' && cached.charAt(1) === ':') {
                    var getter = cached.substring(2);
                    var setter = 'set' + getter.substring(getter.indexOf('is') === 0 ? 2 : 3);
                    return __aiNativeJavaCall(handle, setter, JSON.stringify([encodeJavaArg(value)])) !== undefined;
                }
                return false;
            }
        });
        javaObjectCache.set(handle, proxy);
        return proxy;
    }

    function javaClassOf(handle, className) {
        var cached = javaClassCache.get(handle);
        if (cached) return cached;
        var cache = new Map();
        var target = function JavaClass() {
            throw new TypeError(className + ' 是 Java 类，请用 new 构造');
        };
        var proxy = new Proxy(target, {
            // construct(target, args, newTarget)：args 是构造参数数组（不要用 arguments）。
            construct: function (target, args) {
                return decodeJavaResult(__aiNativeJavaNew(
                    handle, JSON.stringify(encodeJavaArgs(args))));
            },
            get: function (self, prop) {
                if (prop === '__javaHandle') return handle;
                if (prop === '__className') return className;
                if (prop === '__javaClass') return true;
                if (prop === '__isJavaClass') return true;
                if (prop === 'name') return className;
                if (prop === 'class') return proxy;
                if (typeof prop === 'symbol') {
                    // 字符串转换（日志、拼接）与 Rhino 一致：显示成 "class 全限定名"
                    if (typeof Symbol !== 'undefined' && prop === Symbol.toPrimitive) {
                        return function () { return 'class ' + className; };
                    }
                    return undefined;
                }
                if (prop === 'toString') {
                    // 类引用被拼接/打印时给出可读文本（类上的静态 toString 极罕见，不做优先级区分）
                    return function () { return 'class ' + className; };
                }
                if (typeof prop !== 'string') return undefined;
                // then 必须为空（thenable 保护）；未知成员返回 undefined（与 Rhino 的 NOT_FOUND 一致）。
                if (prop === 'then') return undefined;
                return javaMemberOf(handle, { cache: cache }, prop, true);
            },
            set: function (self, prop, value) {
                if (typeof prop !== 'string' || prop in self) return false;
                __aiNativeJavaSetField(handle, prop, JSON.stringify(encodeJavaArg(value)));
                return true;
            },
            has: function (self, prop) {
                if (prop in self) return true;
                return String(__aiNativeJavaProbe(handle, String(prop))) !== '';
            },
            apply: function () {
                throw new TypeError(className + ' 是 Java 类，请用 new 构造');
            }
        });
        javaClassCache.set(handle, proxy);
        return proxy;
    }

    /** 全名解析（不做 import 前缀尝试）。 */
    function requireJavaClass(name) {
        name = String(name);
        var handle = Number(__aiNativeJavaResolveClass(name));
        return handle ? javaClassOf(handle, name) : null;
    }

    /** 裸名解析：已 import 的类 → 全名 → importPackage 列表。 */
    function resolveJavaClass(name) {
        name = String(name);
        if (importedClasses[name]) return importedClasses[name];
        if (Object.prototype.hasOwnProperty.call(javaNameCache, name)) {
            return javaNameCache[name];
        }
        var cls = requireJavaClass(name);
        if (cls === null) {
            for (var i = 0; i < importedPackages.length; i++) {
                cls = requireJavaClass(importedPackages[i] + '.' + name);
                if (cls !== null) break;
            }
        }
        javaNameCache[name] = cls;
        return cls;
    }

    /** 已知的顶层包名：`android.graphics.Point` 这类写法要靠它解析出包代理。 */
    var PACKAGE_ROOTS = ['java', 'javax', 'android', 'androidx', 'org', 'com', 'edu',
        'kotlin', 'dalvik', 'sun', 'groovy', 'io'];

    function isPackageName(name) {
        if (PACKAGE_ROOTS.indexOf(name) >= 0) return true;
        for (var i = 0; i < importedPackages.length; i++) {
            var pkg = importedPackages[i];
            if (pkg === name || pkg.indexOf(name + '.') === 0) return true;
        }
        return false;
    }

    /**
     * importPackage / 顶层包名（android 等）要能直接解析裸名：Rhino 靠 scope 解析器实现，
     * QuickJS 里把 globalThis 的原型设成代理，未命中的全局变量走这里查 import 列表与包根。
     * 标准全局对象仍是 globalThis 的自有属性，不受影响。
     */
    function installAutoImportResolver() {
        var resolver = new Proxy(Object.create(null), {
            get: function (target, prop) {
                if (typeof prop !== 'string' || prop === 'then' || prop === 'toJSON') {
                    return undefined;
                }
                var cls = resolveJavaClass(prop);
                if (cls !== null) return cls;
                return isPackageName(prop) ? makePackageProxy(prop) : undefined;
            },
            has: function (target, prop) {
                if (typeof prop !== 'string') return false;
                return resolveJavaClass(prop) !== null || isPackageName(prop);
            }
        });
        Object.setPrototypeOf(globalThis, resolver);
    }

    /** 包代理：Packages.android.content.Intent 逐级解析，解析不到就继续当包。 */
    function makePackageProxy(prefix) {
        var target = { __packageName: prefix };
        return new Proxy(target, {
            get: function (self, prop) {
                if (prop === '__packageName' || prop === '__isPackage') return self[prop];
                if (typeof prop !== 'string') return undefined;
                if (prop === 'then' || prop === 'toJSON' || prop === 'valueOf'
                        || prop === 'constructor') {
                    return undefined;
                }
                var full = self.__packageName ? self.__packageName + '.' + prop : prop;
                return requireJavaClass(full) || makePackageProxy(full);
            },
            has: function (self, prop) {
                var full = self.__packageName ? self.__packageName + '.' + String(prop) : String(prop);
                return requireJavaClass(full) !== null;
            }
        });
    }

    global.Packages = makePackageProxy('');
    global.java = makePackageProxy('java');
    global.javax = makePackageProxy('javax');
    global.org = makePackageProxy('org');
    global.com = makePackageProxy('com');
    global.edu = makePackageProxy('edu');
    global.android = makePackageProxy('android');
    global.androidx = makePackageProxy('androidx');
    global.kotlin = makePackageProxy('kotlin');
    global.dalvik = makePackageProxy('dalvik');

    global.importClass = function () {
        var imported = [];
        for (var i = 0; i < arguments.length; i++) {
            var cls = arguments[i];
            if (typeof cls === 'string') {
                cls = resolveJavaClass(cls) || requireJavaClass(cls);
                if (cls === null) throw new TypeError('找不到 Java 类：' + arguments[i]);
            }
            if (!cls || !cls.__javaClass) {
                throw new TypeError('importClass 需要类名或 Java 类对象');
            }
            var simple = String(cls.__className).substring(String(cls.__className).lastIndexOf('.') + 1);
            simple = simple.substring(simple.lastIndexOf('$') + 1);
            importedClasses[simple] = cls;
            global[simple] = cls;
            imported.push(simple);
        }
        return imported.join(', ');
    };

    global.importPackage = function () {
        for (var i = 0; i < arguments.length; i++) {
            var target = arguments[i];
            var name = typeof target === 'string'
                ? target
                : (target && target.__packageName ? String(target.__packageName) : '');
            if (!name) throw new TypeError('importPackage 需要包名或 Packages 里的包对象');
            if (importedPackages.indexOf(name) < 0) importedPackages.push(name);
            // 新包可能让之前解析失败的裸名变得可用，清掉负缓存。
            javaNameCache = Object.create(null);
        }
    };

    installAutoImportResolver();

    // ---- 与 Rhino 的 init.js / __app__.js / __http__.js / __shell__.js 预导入对齐 ----
    // Rhino 在这些模块里 importClass 之后脚本可以直接使用这些类名。
    ['android.view.KeyEvent',
        'com.stardust.autojs.core.util.Shell',
        'android.graphics.Paint',
        'android.content.Intent',
        'com.stardust.autojs.core.http.MutableOkHttp'].forEach(function (name) {
        var cls = requireJavaClass(name);
        if (cls === null) return;
        var simple = name.substring(name.lastIndexOf('.') + 1);
        importedClasses[simple] = cls;
        global[simple] = cls;
    });
    global.Canvas = requireJavaClass('com.stardust.autojs.core.graphics.ScriptCanvas');
    global.Image = requireJavaClass('com.stardust.autojs.core.image.ImageWrapper');
    // Rhino 的 __shell__.js：Input = Text（RootShell 文本输入；Text 在本段之后才定义，这里延迟转发）
    global.Input = function () { return global.Text.apply(null, arguments); };
    // Rhino 的 jvm-npm：这里复用 QuickJS 自己的 CommonJS 加载器。
    global.Module = {
        require: function (path) { return global.require(path); },
        runMain: function (path) { return global.require(path); },
        _load: function (path) { return global.require(path); }
    };
    // Rhino 的 RootAutomator：包装 com.stardust.autojs.core.inputevent.RootAutomator（需要 root）。
    (function () {
        var RootAutomatorClass = requireJavaClass('com.stardust.autojs.core.inputevent.RootAutomator');
        if (RootAutomatorClass === null) return;
        var methods = ['sendEvent', 'touch', 'setScreenMetrics', 'touchX', 'touchY', 'sendSync',
            'sendMtSync', 'tap', 'swipe', 'press', 'longPress', 'touchDown', 'touchUp', 'touchMove',
            'getDefaultId', 'setDefaultId', 'exit'];
        global.RootAutomator = function (inputDevice, nonBlockingForReady) {
            var ra = new RootAutomatorClass(global.context,
                inputDevice === undefined ? null : inputDevice, !nonBlockingForReady);
            this.__ra__ = ra;
            var self = this;
            methods.forEach(function (name) {
                self[name] = function () { return ra[name].apply(ra, arguments); };
            });
        };
    })();

    global.Java = {
        type: function (name) {
            var cls = requireJavaClass(name);
            if (cls === null) throw new TypeError('找不到 Java 类：' + name);
            return cls;
        },
        /** Java.array('int', [1,2,3])：返回 JS 数组，跨边界时按目标类型转成 Java 数组。 */
        array: function (typeName, values) {
            var args = Array.isArray(values) ? values : Array.prototype.slice.call(arguments, 1);
            return args.slice();
        }
    };

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
            case 'a': return result.v.map(function (item) {
                return item.k === 'c' ? wrapAutomatorCollection(item.v) : wrapAutomatorObject(item.v);
            });
            case 'h':
                return result.k === 'c' ? wrapAutomatorCollection(result.v) : wrapAutomatorObject(result.v);
            default: return undefined;
        }
    }

    /** Rhino 的 findAndReturnList 返回 java.util.List，脚本常用 size()/get()，这里补上同名方法。 */
    function decorateAutomatorList(items) {
        if (!Array.isArray(items)) return items;
        items.size = function () { return items.length; };
        items.get = function (index) { return items[index]; };
        items.isEmpty = function () { return items.length === 0; };
        return items;
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
    var AUTOMATOR_NODE_ACTIONS = ['click', 'clickCenter', 'longClick', 'accessibilityFocus', 'clearAccessibilityFocus',
        'focus', 'clearFocus', 'copy', 'paste', 'select', 'cut', 'collapse', 'expand', 'dismiss', 'show',
        'scrollForward', 'scrollBackward', 'scrollUp', 'scrollDown', 'scrollLeft', 'scrollRight', 'contextClick'];
    var AUTOMATOR_STRING_PROPERTIES = ['text', 'desc', 'id', 'className', 'packageName'];
    var AUTOMATOR_INT_PROPERTIES = ['depth', 'drawingOrder', 'indexInParent', 'childCount', 'row', 'column',
        'rowSpan', 'columnSpan', 'rowCount', 'columnCount'];
    var AUTOMATOR_BOOL_PROPERTIES = ['checkable', 'checked', 'focusable', 'focused', 'visibleToUser',
        'accessibilityFocused', 'selected', 'clickable', 'longClickable', 'enabled', 'password', 'scrollable', 'editable', 'contextClickable', 'dismissable', 'contentInvalid', 'multiLine', 'isHierarchically'];

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
        selector.untilFind = function (timeout, interval) {
            var findArgs = [];
            if (timeout !== undefined) {
                findArgs.push(Number(timeout));
                if (interval !== undefined) findArgs.push(Number(interval));
            }
            return decodeAutomatorResult(automatorCall(handle, 'untilFind', findArgs));
        };
        selector.findOnce = function (index) {
            return decodeAutomatorResult(automatorCall(handle, 'findOnce',
                index === undefined ? [] : [index]));
        };
        selector.findOne = function (timeout, interval) {
            var findArgs = [];
            if (timeout !== undefined) {
                findArgs.push(Number(timeout));
                if (interval !== undefined) findArgs.push(Number(interval));
            }
            return decodeAutomatorResult(automatorCall(handle, 'findOne', findArgs));
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
        // Rhino 的 __selector__.js 把 UiGlobalSelector.filter / addFilter 也挂到全局：
        // 谓词是脚本函数，Java 遍历控件时通过 __aiInvokeCallback 同步回调。
        selector.filter = function (predicate) {
            if (typeof predicate !== 'function') throw new TypeError('filter(predicate) 需要一个判断函数');
            automatorCall(handle, 'filter', [global.__aiRegisterCallback(predicate)]);
            return selector;
        };
        selector.addFilter = function (predicate) {
            if (typeof predicate !== 'function') throw new TypeError('addFilter(predicate) 需要一个判断函数');
            automatorCall(handle, 'addFilter', [global.__aiRegisterCallback(predicate)]);
            return selector;
        };
        selector.findAndReturnList = function (node, max) {
            var target = automatorHandleOf(node);
            if (target === null) throw new TypeError('findAndReturnList(node, max) expects a UiObject');
            var found = decodeAutomatorResult(automatorCall(handle, 'findAndReturnList',
                max === undefined ? [target, -1] : [target, max]));
            return decorateAutomatorList(found);
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
        object.findByText = function (text) { return decodeAutomatorResult(automatorCall(handle, 'findByText', [String(text)])); };
        object.findByViewId = function (id) { return decodeAutomatorResult(automatorCall(handle, 'findByViewId', [String(id)])); };
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
        'setText', 'setSelection', 'setProgress', 'filter', 'addFilter'].forEach(function (name) {
        global[name] = function () {
            var selector = global.selector();
            return selector[name].apply(selector, arguments);
        };
    });
    // findAndReturnList(node, max)：Rhino 的全局形式基于一个空选择器，返回 node 下符合条件的控件列表。
    global.findAndReturnList = function (node, max) {
        var selector = global.selector();
        return selector.findAndReturnList.apply(selector, arguments);
    };

    // ---- 多指手势 / 文本输入 / RootShell 按键助手 ----
    global.gesture = function (duration) {
        var points = Array.prototype.slice.call(arguments, 1);
        return __aiNativeGesture(0, Math.round(Number(duration)), JSON.stringify(points), false);
    };
    global.gestureAsync = function (duration) {
        var points = Array.prototype.slice.call(arguments, 1);
        return __aiNativeGesture(0, Math.round(Number(duration)), JSON.stringify(points), true);
    };
    global.gestures = function () {
        return __aiNativeGestures(JSON.stringify(Array.prototype.slice.call(arguments)), false);
    };
    global.gesturesAsync = function () {
        return __aiNativeGestures(JSON.stringify(Array.prototype.slice.call(arguments)), true);
    };
    global.input = function (text) {
        return __aiNativeInputText(String(text == null ? '' : text));
    };

    // RootShell 同名助手（与 Rhino 的 __shell__.js 一致，需要 root）。
    function rootShellCommand(command) {
        if (typeof shell.isRootAvailable === 'function' && !shell.isRootAvailable()) {
            throw new Error('该功能需要 root 权限');
        }
        return shell(command, { root: true });
    }
    global.KeyCode = function (keyCode) {
        return rootShellCommand('input keyevent ' + Math.round(Number(keyCode)));
    };
    global.Tap = function (x, y) {
        return rootShellCommand('input tap ' + Math.round(Number(x)) + ' ' + Math.round(Number(y)));
    };
    global.Swipe = function (x1, y1, x2, y2, duration) {
        var command = 'input swipe ' + Math.round(Number(x1)) + ' ' + Math.round(Number(y1)) + ' '
            + Math.round(Number(x2)) + ' ' + Math.round(Number(y2));
        if (duration !== undefined) command += ' ' + Math.round(Number(duration));
        return rootShellCommand(command);
    };
    global.Screencap = function (path) { return rootShellCommand('screencap -p ' + String(path)); };
    global.Text = function (text) {
        return rootShellCommand("input text '" + String(text).replace(/'/g, '').replace(/ /g, '%s') + "'");
    };
    global.Home = function () { return global.KeyCode(3); };
    global.Back = function () { return global.KeyCode(4); };
    global.Power = function () { return global.KeyCode(26); };
    global.Up = function () { return global.KeyCode(19); };
    global.Down = function () { return global.KeyCode(20); };
    global.Left = function () { return global.KeyCode(21); };
    global.Right = function () { return global.KeyCode(22); };
    global.OK = function () { return global.KeyCode(23); };
    global.VolumeUp = function () { return global.KeyCode(24); };
    global.VolumeDown = function () { return global.KeyCode(25); };
    global.Menu = function () { return global.KeyCode(1); };
    global.Camera = function () { return global.KeyCode(27); };

    // ---- app / 文件快捷别名（Auto.js 4.x 顶层函数）----
    ['launch', 'launchApp', 'launchPackage', 'openAppSetting', 'getAppName', 'getPackageName']
        .forEach(function (name) {
            if (typeof global.app[name] !== 'function') return;
            global[name] = function () { return global.app[name].apply(global.app, arguments); };
        });
    // Rhino 的 `__io__.js` 把 `files.open` 提升为全局 `open`（多参数按 mode/encoding/bufferSize 透传）。
    global.open = function (path, mode, encoding, bufferSize) {
        return global.files.open.apply(global.files, arguments);
    };

    // ---- io 模块（Rhino 的 __io__.js：把 files.open 提升为全局 open）----
    global.io = Object.freeze({
        open: function (path, mode, encoding, bufferSize) {
            return global.open.apply(null, arguments);
        },
        files: global.files
    });

    // ---- web 模块（QuickJS 版 InjectableWebView / InjectableWebClient）----
    // 页面里的 `rhino.call(...)` / `rhino.eval(...)` 由 WebView 线程入队，脚本引擎线程执行。
    function wrapWebInjector(handle) {
        var injector = { __handle: handle };
        injector.inject = function (script, callback) {
            var callbackId = typeof callback === 'function'
                ? global.__aiRegisterCallback(callback) : 0;
            __aiNativeWebCall(handle, 'inject', JSON.stringify([String(script), callbackId]));
        };
        injector.injectAndWait = function () {
            throw new Error('QuickJS 不支持 injectAndWait（跨线程同步求值）；请用 inject(script, callback)');
        };
        injector.loadUrl = function (url) {
            __aiNativeWebCall(handle, 'loadUrl', JSON.stringify([String(url)]));
        };
        injector.loadData = function (data, mimeType, encoding) {
            __aiNativeWebCall(handle, 'loadData', JSON.stringify([
                String(data), String(mimeType === undefined ? 'text/html' : mimeType),
                String(encoding === undefined ? 'utf-8' : encoding)
            ]));
        };
        injector.reload = function () { __aiNativeWebCall(handle, 'reload', '[]'); };
        injector.stopLoading = function () { __aiNativeWebCall(handle, 'stopLoading', '[]'); };
        injector.getUrl = function () {
            var text = __aiNativeWebCall(handle, 'getUrl', '[]');
            if (typeof text !== 'string' || text === '') return null;
            var result = JSON.parse(text);
            return result.t === 's' ? result.v : null;
        };
        return injector;
    }
    function createWebInjector(withView) {
        var handle = Number(__aiNativeWebCreate(withView));
        if (!handle) throw new Error('无法创建 WebView（当前环境没有可用的 Activity/上下文）');
        return wrapWebInjector(handle);
    }
    global.newInjectableWebView = function () { return createWebInjector(true); };
    global.newInjectableWebClient = function () { return createWebInjector(false); };

    // ---- web.newWebSocket / web.ByteString（事件走系统事件队列，40ms 派发）----
    var __aiWebSocketBags = new Map();

    function __aiUtf8Encode(text) {
        var bytes = [];
        for (var i = 0; i < text.length; i++) {
            var code = text.charCodeAt(i);
            if (code < 0x80) bytes.push(code);
            else if (code < 0x800) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 63));
            else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
                var next = text.charCodeAt(++i);
                var point = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
                bytes.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 63),
                    0x80 | ((point >> 6) & 63), 0x80 | (point & 63));
            } else {
                bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
            }
        }
        return new Uint8Array(bytes);
    }

    function __aiBase64ToBytes(text) {
        var TABLE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        text = String(text).replace(/[^A-Za-z0-9+/]/g, '');
        var bytes = [];
        for (var i = 0; i < text.length; i += 4) {
            var c0 = TABLE.indexOf(text[i]), c1 = TABLE.indexOf(text[i + 1]),
                c2 = TABLE.indexOf(text[i + 2]), c3 = TABLE.indexOf(text[i + 3]);
            bytes.push((c0 << 2) | (c1 >> 4));
            if (c2 !== -1) bytes.push(((c1 & 15) << 4) | (c2 >> 2));
            if (c3 !== -1) bytes.push(((c2 & 3) << 6) | c3);
        }
        return new Uint8Array(bytes);
    }

    function __aiBytesToBase64(bytes) {
        var TABLE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var out = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i],
                b1 = i + 1 < bytes.length ? bytes[i + 1] : 0,
                b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            out += TABLE[b0 >> 2];
            out += TABLE[((b0 & 3) << 4) | (b1 >> 4)];
            out += i + 1 < bytes.length ? TABLE[((b1 & 15) << 2) | (b2 >> 6)] : '=';
            out += i + 2 < bytes.length ? TABLE[b2 & 63] : '=';
        }
        return out;
    }

    function __aiMakeBytes(bytes) {
        bytes.size = function () { return bytes.length; };
        bytes.toByteArray = function () { return new Uint8Array(bytes); };
        bytes.hex = function () {
            var out = '';
            for (var i = 0; i < bytes.length; i++) {
                out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
            }
            return out;
        };
        bytes.base64 = function () { return __aiBytesToBase64(bytes); };
        bytes.md5 = function () {
            return String(__aiNativeCryptoCall('md5Base64', __aiBytesToBase64(bytes), ''));
        };
        bytes.toString = function () { return 'ByteString(' + bytes.length + ' bytes)'; };
        return bytes;
    }

    global.__aiDispatchWebSocketEvent = function (event) {
        var bag = __aiWebSocketBags.get(event.id);
        if (!bag) return;
        var list = bag[String(event.event)];
        if (!list || list.length === 0) return;
        var args = (event.args || []).slice();
        if (event.event === 'binary' && typeof args[0] === 'string') {
            args[0] = __aiMakeBytes(__aiBase64ToBytes(args[0]));
        }
        list.slice().forEach(function (listener) { listener.apply(undefined, args); });
    };

    function __aiNewWebSocket(url) {
        var id = Number(__aiNativeWebSocketNew(String(url)));
        if (!(id > 0)) throw new Error('无法创建 WebSocket: ' + url);
        __aiWebSocketBags.set(id, {});
        ensureSystemEventTimer();
        var ws = {
            __id: id,
            on: function (event, listener) {
                if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                var bag = __aiWebSocketBags.get(id);
                if (!bag) return ws;
                var name = String(event);
                (bag[name] = bag[name] || []).push(listener);
                return ws;
            },
            once: function (event, listener) {
                function onceListener() {
                    ws.removeListener(event, onceListener);
                    return listener.apply(undefined, arguments);
                }
                onceListener.listener = listener;
                return ws.on(event, onceListener);
            },
            removeListener: function (event, listener) {
                var bag = __aiWebSocketBags.get(id);
                var list = bag && bag[String(event)];
                if (!list) return ws;
                for (var i = list.length - 1; i >= 0; i--) {
                    if (list[i] === listener || list[i].listener === listener) list.splice(i, 1);
                }
                return ws;
            },
            removeAllListeners: function (event) {
                var bag = __aiWebSocketBags.get(id);
                if (bag) {
                    if (event === undefined) __aiWebSocketBags.set(id, {});
                    else delete bag[String(event)];
                }
                return ws;
            },
            send: function (data) {
                if (data === undefined || data === null) return false;
                if (typeof data === 'string') {
                    return !!__aiNativeWebSocketSend(id, 'text', data);
                }
                var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
                return !!__aiNativeWebSocketSend(id, 'binary', __aiBytesToBase64(bytes));
            },
            close: function (code, reason) {
                var closeCode = code === undefined || code === null ? 1000 : Math.floor(Number(code) || 1000);
                return !!__aiNativeWebSocketClose(id, closeCode,
                    reason === undefined || reason === null ? '' : String(reason));
            },
            cancel: function () { __aiNativeWebSocketCancel(id); },
            queueSize: function () { return Number(__aiNativeWebSocketQueueSize(id)); },
            toString: function () { return 'WebSocket(' + url + ')'; }
        };
        return ws;
    }

    var __aiByteString = Object.freeze({
        decodeBase64: function (text) { return __aiMakeBytes(__aiBase64ToBytes(String(text))); },
        encodeUtf8: function (text) { return __aiMakeBytes(__aiUtf8Encode(String(text))); },
        decodeHex: function (text) {
            var hex = String(text).replace(/[^0-9a-fA-F]/g, '');
            var bytes = new Uint8Array(Math.floor(hex.length / 2));
            for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
            return __aiMakeBytes(bytes);
        }
    });

    global.web = Object.freeze({
        newInjectableWebView: global.newInjectableWebView,
        newInjectableWebClient: global.newInjectableWebClient,
        newWebSocket: function (url, options) { return __aiNewWebSocket(String(url)); },
        ByteString: __aiByteString
    });

    // ---- continuation 模块 ----
    // Rhino 用 Continuation 实现阻塞式等待；QuickJS 是单线程执行模型，没有可挂起的 continuation，
    // 所以 delay 退化为阻塞 sleep，await/create 明确报错引导到 await 语法，enabled 报告 false。
    var continuation = {
        delay: function (millis) { sleep(Number(millis) || 0); },
        await: function () {
            throw new Error('QuickJS 不支持 continuation.await（无 continuation 特性）；请使用 await 语法或 .then() 回调');
        },
        create: function () {
            throw new Error('QuickJS 不支持 continuation.create（无 continuation 特性）；请使用 await 语法或 .then() 回调');
        }
    };
    Object.defineProperty(continuation, 'enabled', {
        get: function () { return false; },
        enumerable: true,
        configurable: false
    });
    global.continuation = Object.freeze(continuation);
    if (global.Promise && global.Promise.prototype.await === undefined) {
        Object.defineProperty(global.Promise.prototype, 'await', {
            value: function () {
                throw new Error('QuickJS 不支持 Promise.await；请使用 await 语法');
            },
            enumerable: false,
            configurable: true,
            writable: true
        });
    }

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

    global.setImmediate = function (callback) {
        var args = Array.prototype.slice.call(arguments);
        args.splice(1, 0, 0);
        return global.setTimeout.apply(null, args);
    };
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

    // ---- 内置模块：crypto / zips / util / automator / context / rawInput ----
    // 名称与语义对齐 Rhino 的 runtime.crypto / runtime.zips / __util__.js。

    function __aiHexNibble(ch) {
        var code = ch.charCodeAt(0);
        if (code >= 48 && code <= 57) return code - 48;      // 0-9
        if (code >= 97 && code <= 102) return code - 87;     // a-f
        if (code >= 65 && code <= 70) return code - 55;      // A-F
        throw new Error('char: ' + ch);
    }

    global.crypto = Object.freeze({
        md5: function (data) { return __aiNativeCryptoCall('md5', String(data), ''); },
        sha1: function (data) { return __aiNativeCryptoCall('sha1', String(data), ''); },
        sha256: function (data) { return __aiNativeCryptoCall('sha256', String(data), ''); },
        digest: function (algorithm, data) {
            return __aiNativeCryptoCall('digest', String(algorithm), String(data));
        },
        hmacSha256: function (data, key) {
            return __aiNativeCryptoCall('hmacSha256', String(data), String(key));
        },
        base64Encode: function (data) { return __aiNativeCryptoCall('base64Encode', String(data), ''); },
        base64Decode: function (data) { return __aiNativeCryptoCall('base64Decode', String(data), ''); },
        /**
         * 与 Auto.js Pro 9.3.11 的 Crypto.toHex/fromHex 同语义：fromHex 要求偶数长度
         * （否则抛 'input array length must even.'），非法字符抛 'char: x'；toHex 输出小写十六进制。
         * QuickJS 没有 Java byte[]，字节一律用 Uint8Array（可选带 ByteString 扩展方法），
         * 字符串参数按 UTF-8 编码。
         */
        toHex: function (data) {
            var bytes = data;
            if (typeof data === 'string') bytes = __aiUtf8Encode(data);
            if (bytes === null || bytes === undefined) return '';
            var out = '';
            for (var i = 0; i < bytes.length; i++) {
                out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
            }
            return out;
        },
        fromHex: function (hex) {
            hex = String(hex);
            if (hex.length % 2 !== 0) throw new Error('input array length must even.');
            var bytes = new Uint8Array(hex.length / 2);
            for (var i = 0; i < bytes.length; i++) {
                bytes[i] = (__aiHexNibble(hex.charAt(i * 2)) << 4) | __aiHexNibble(hex.charAt(i * 2 + 1));
            }
            return __aiMakeBytes(bytes);
        }
    });

    global.zips = Object.freeze({
        zip: function (srcDir, destZip) {
            return __aiNativeZipsCall('zip', String(srcDir), String(destZip)) === 'true';
        },
        unzip: function (zipPath, destDir) {
            return __aiNativeZipsCall('unzip', String(zipPath), String(destDir)) === 'true';
        },
        list: function (zipPath) {
            return JSON.parse(__aiNativeZipsCall('list', String(zipPath), ''));
        }
    });

    global.util = (function () {
        var util = {
            isArray: function (v) { return Array.isArray(v); },
            isBoolean: function (v) { return typeof v === 'boolean'; },
            isNull: function (v) { return v === null; },
            isNullOrUndefined: function (v) { return v === null || v === undefined; },
            isNumber: function (v) { return typeof v === 'number'; },
            isString: function (v) { return typeof v === 'string'; },
            isSymbol: function (v) { return typeof v === 'symbol'; },
            isUndefined: function (v) { return v === undefined; },
            isRegExp: function (v) { return Object.prototype.toString.call(v) === '[object RegExp]'; },
            isObject: function (v) { return v !== null && typeof v === 'object'; },
            isDate: function (v) { return Object.prototype.toString.call(v) === '[object Date]'; },
            isError: function (v) { return v instanceof Error; },
            isFunction: function (v) { return typeof v === 'function'; },
            isPrimitive: function (v) { return v === null || (typeof v !== 'object' && typeof v !== 'function'); },
            hasOwnProperty: function (obj, prop) { return Object.prototype.hasOwnProperty.call(obj, prop); },
            isEmpty: function (obj) {
                if (obj === null || obj === undefined) return true;
                if (typeof obj === 'string' || Array.isArray(obj)) return obj.length === 0;
                if (typeof obj === 'object') return Object.keys(obj).length === 0;
                return false;
            },
            size: function (obj) {
                if (obj === null || obj === undefined) return 0;
                if (typeof obj === 'string' || Array.isArray(obj)) return obj.length;
                if (typeof obj === 'object') return Object.keys(obj).length;
                return 0;
            },
            join: function (obj, sep) {
                if (Array.isArray(obj)) return obj.join(sep === undefined ? ',' : sep);
                if (obj !== null && typeof obj === 'object') {
                    return Object.keys(obj).map(function (key) { return key + ': ' + obj[key]; })
                        .join(sep === undefined ? '\n' : sep);
                }
                return String(obj);
            },
            extend: function (target, source) {
                // Rhino 用它做 Java 类继承；QuickJS 下退化为属性拷贝（含 source 的原型链）。
                if (target === null || target === undefined || source === null || source === undefined) {
                    return target;
                }
                var seen = {};
                for (var obj = source; obj !== null && obj !== Object.prototype; obj = Object.getPrototypeOf(obj)) {
                    Object.getOwnPropertyNames(obj).forEach(function (name) {
                        if (seen[name] || name === 'constructor') return;
                        seen[name] = true;
                        var value = obj[name];
                        if (typeof value === 'function' && target.prototype && !target.prototype[name]) {
                            target.prototype[name] = value;
                        } else if (target[name] === undefined) {
                            target[name] = value;
                        }
                    });
                }
                return target;
            },
            format: function (fmt) {
                if (typeof fmt !== 'string') return util.join(Array.prototype.slice.call(arguments), ' ');
                var args = Array.prototype.slice.call(arguments, 1);
                var index = 0;
                var out = fmt.replace(/%([sdifjoO%])/g, function (match, spec) {
                    if (spec === '%') return '%';
                    if (index >= args.length) return match;
                    var value = args[index++];
                    switch (spec) {
                        case 's': return String(value);
                        case 'd':
                        case 'i': return String(parseInt(value, 10));
                        case 'f': return String(parseFloat(value));
                        case 'j':
                            try { return JSON.stringify(value); } catch (e) { return '[Circular]'; }
                        default: return typeof value === 'string' ? value : JSON.stringify(value);
                    }
                });
                while (index < args.length) {
                    var extra = args[index++];
                    out += ' ' + (typeof extra === 'string' ? extra : JSON.stringify(extra));
                }
                return out;
            },
            random: function (min, max) {
                if (arguments.length === 0) return Math.random();
                return Math.floor(Math.random() * (max - min + 1)) + min;
            },
            range: function (start, end, step) {
                if (end === undefined) { end = start; start = 0; }
                step = step === undefined ? 1 : step;
                var result = [];
                for (var i = start; step > 0 ? i < end : i > end; i += step) result.push(i);
                return result;
            },
            sum: function (array, transform) {
                var total = 0;
                for (var i = 0; i < array.length; i++) {
                    total += transform ? transform(array[i]) : array[i];
                }
                return total;
            }
        };
        return util;
    })();

    // automator 模块：直接映射到全局同名函数，语义与 Rhino 的 __automator__.js 一致。
    global.automator = Object.freeze({
        click: function () { return global.click.apply(null, arguments); },
        longClick: function () { return global.longClick.apply(null, arguments); },
        press: function () { return global.press.apply(null, arguments); },
        swipe: function () { return global.swipe.apply(null, arguments); },
        gesture: function () { return global.gesture.apply(null, arguments); },
        gestureAsync: function () { return global.gestureAsync.apply(null, arguments); },
        gestures: function () { return global.gestures.apply(null, arguments); },
        gesturesAsync: function () { return global.gesturesAsync.apply(null, arguments); },
        scrollDown: function () { return global.scrollDown.apply(null, arguments); },
        scrollUp: function () { return global.scrollUp.apply(null, arguments); },
        setText: function () { return global.setText.apply(null, arguments); },
        input: function (text) { return global.input(text); },
        setMode: function (mode) { return global.auto.setMode(mode); },
        setFlags: function () { return global.auto.setFlags.apply(null, arguments); }
    });

    // context 模块：Rhino 给 Android Context；白名单桥只暴露常用目录与包名，
    // 目录以 file-like 对象返回（兼容 getAbsolutePath()/toString() 两种写法）。
    global.context = (function () {
        // Rhino 里 context 就是 Android Context 对象；QuickJS 通过 Java 互操作同样给真实对象，
        // 拿不到时回退到白名单版本（只暴露包名与常用目录）。
        var handle = Number(__aiNativeJavaContextHandle());
        if (handle) {
            return javaObjectOf(handle, 'android.content.Context');
        }
        function contextFile(path) {
            if (path === null) return null;
            return {
                path: path,
                getPath: function () { return path; },
                getAbsolutePath: function () { return path; },
                toString: function () { return path; }
            };
        }
        return {
            packageName: __aiNativeContextInfo('packageName'),
            getPackageName: function () { return __aiNativeContextInfo('packageName'); },
            getFilesDir: function () { return contextFile(__aiNativeContextInfo('filesDir')); },
            getCacheDir: function () { return contextFile(__aiNativeContextInfo('cacheDir')); },
            getNoBackupFilesDir: function () { return contextFile(__aiNativeContextInfo('noBackupFilesDir')); },
            getExternalFilesDir: function () { return contextFile(__aiNativeContextInfo('externalFilesDir')); },
            getExternalCacheDir: function () { return contextFile(__aiNativeContextInfo('externalCacheDir')); }
        };
    })();

    // rawInput 模块：走 shell 的 `input` 命令（无需 root）。
    global.rawInput = (function () {
        function run(command) {
            var result = shell(command);
            return result === null || result === undefined ? true : result.code === 0;
        }
        function escape(text) {
            return String(text).replace(/'/g, '').replace(/ /g, '%s');
        }
        return Object.freeze({
            keyevent: function (keyCode) { return run('input keyevent ' + Math.round(Number(keyCode))); },
            key: function (keyCode) { return run('input keyevent ' + Math.round(Number(keyCode))); },
            text: function (text) { return run("input text '" + escape(text) + "'"); },
            tap: function (x, y) {
                return run('input tap ' + Math.round(Number(x)) + ' ' + Math.round(Number(y)));
            },
            press: function (x, y, duration) {
                var px = Math.round(Number(x));
                var py = Math.round(Number(y));
                return run('input swipe ' + px + ' ' + py + ' ' + px + ' ' + py + ' '
                    + Math.round(Number(duration === undefined ? 500 : duration)));
            },
            swipe: function (x1, y1, x2, y2, duration) {
                var command = 'input swipe ' + Math.round(Number(x1)) + ' ' + Math.round(Number(y1)) + ' '
                    + Math.round(Number(x2)) + ' ' + Math.round(Number(y2));
                if (duration !== undefined) command += ' ' + Math.round(Number(duration));
                return run(command);
            },
            roll: function (x, y) {
                return run('input roll ' + Math.round(Number(x)) + ' ' + Math.round(Number(y)));
            }
        });
    })();

    // ---- 运行时状态与版本声明（Rhino __globals__.js）----
    global.isStopped = function () { return __aiNativeScriptStopped(); };
    global.notStopped = function () { return !__aiNativeScriptStopped(); };
    global.isRunning = global.notStopped;
    global.isShuttingDown = global.isStopped;
    global.stop = global.exit;
    // Rhino __timers__.js 的 loop() 已废弃：保留同名空实现（只给提示，不产生副作用）。
    global.loop = function () {
        console.warn('loop() has been deprecated and has no effect. Remove it from your code.');
    };
    global.requiresApi = function (api) { __aiNativeRequiresApi(Math.round(Number(api))); };

    var appVersion = String(__aiNativeAppVersion()).split('|');
    function compareVersion(left, right) {
        var a = String(left).split('.');
        var b = String(right).split('.');
        var length = Math.max(a.length, b.length);
        for (var i = 0; i < length; i++) {
            var x = parseInt(a[i] || '0', 10) || 0;
            var y = parseInt(b[i] || '0', 10) || 0;
            if (x !== y) return x > y ? 1 : -1;
        }
        return 0;
    }
    global.requiresAutojsVersion = function (version) {
        if (typeof version === 'number') {
            if (Number(appVersion[0]) < version) {
                throw new Error('需要 AI.js Pro 版本号 ' + version + ' 以上才能运行');
            }
        } else if (compareVersion(version, appVersion[1]) > 0) {
            throw new Error('需要 AI.js Pro 版本 ' + version + ' 以上才能运行');
        }
    };

    // sqlite 模块：复用 core/database 的 Database，事务用 begin/end 包住脚本回调。
    global.sqlite = (function () {
        var connections = new Map();
        function encodeValue(key, value) {
            if (value instanceof ArrayBuffer) value = new Uint8Array(value);
            if (ArrayBuffer.isView(value)) return { __sqliteBlob: Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength)) };
            return value;
        }
        function decodeValue(key, value) {
            return value && value.__sqliteBlob !== undefined ? new Uint8Array(value.__sqliteBlob) : value;
        }
        function sqlArgs(args) { return args === undefined ? null : args; }
        function cursorFrom(parsed) {
            var rows = parsed.rows || [], columns = parsed.columns || (rows.length ? Object.keys(rows[0]) : []);
            var position = -1, closed = false;
            function ensureOpen() { if (closed) throw new Error('Cursor is closed'); }
            function current() {
                ensureOpen();
                if (position < 0 || position >= rows.length) throw new RangeError('Cursor position is invalid');
                return rows[position];
            }
            var cursor = {
                moveToPosition: function (next) { ensureOpen(); position = Math.max(-1, Math.min(rows.length, Number(next))); return position >= 0 && position < rows.length; },
                moveToNext: function () { return cursor.moveToPosition(position + 1); },
                moveToPrevious: function () { return cursor.moveToPosition(position - 1); },
                moveToFirst: function () { return cursor.moveToPosition(0); },
                moveToLast: function () { return cursor.moveToPosition(rows.length - 1); },
                move: function (offset) { return cursor.moveToPosition(position + Number(offset)); },
                getPosition: function () { ensureOpen(); return position; },
                getCount: function () { ensureOpen(); return rows.length; },
                getColumnNames: function () { ensureOpen(); return columns.slice(); },
                getColumnCount: function () { ensureOpen(); return columns.length; },
                getColumnIndex: function (name) { ensureOpen(); return columns.indexOf(String(name)); },
                getColumnIndexOrThrow: function (name) { var i = cursor.getColumnIndex(name); if (i < 0) throw new Error('Column does not exist: ' + name); return i; },
                get: function (index) { index = Number(index); if (index < 0 || index >= columns.length) throw new RangeError('Column index is invalid'); var row = current(); return parsed.data ? parsed.data[position][index] : row[columns[index]]; },
                getByColumn: function (name) { return cursor.get(cursor.getColumnIndexOrThrow(name)); },
                getString: function (index) { var value = cursor.get(index); return value == null ? null : String(value); },
                getLong: function (index) { return Number(cursor.get(index)); },
                getInt: function (index) { return Math.trunc(Number(cursor.get(index))); },
                getDouble: function (index) { return Number(cursor.get(index)); },
                getFloat: function (index) { return Number(cursor.get(index)); },
                getShort: function (index) { return (cursor.getInt(index) << 16) >> 16; },
                getType: function (index) { var value = cursor.get(index); return parsed.types ? parsed.types[position][index] : value === null ? 0 : value instanceof Uint8Array ? 4 : typeof value === 'number' ? Number.isInteger(value) ? 1 : 2 : 3; },
                getBlob: function (index) { return cursor.get(index); },
                isNull: function (index) { return cursor.get(index) === null; },
                isBeforeFirst: function () { ensureOpen(); return position < 0 || rows.length === 0; },
                isAfterLast: function () { ensureOpen(); return position >= rows.length || rows.length === 0; },
                isFirst: function () { ensureOpen(); return rows.length > 0 && position === 0; },
                isLast: function () { ensureOpen(); return rows.length > 0 && position === rows.length - 1; },
                pick: function () { return Object.assign({}, current()); },
                next: function () { return cursor.moveToNext() ? cursor.pick() : null; },
                all: function (close) { var values = []; while (cursor.moveToNext()) values.push(cursor.pick()); if (close === undefined || close) cursor.close(); return values; },
                single: function () { var value = cursor.next(); cursor.close(); return value; },
                close: function () { closed = true; },
                isClosed: function () { return closed; }
            };
            Object.defineProperties(cursor, {
                count: { get: cursor.getCount }, position: { get: cursor.getPosition },
                columnNames: { get: cursor.getColumnNames }, columnCount: { get: cursor.getColumnCount },
                closed: { get: cursor.isClosed }
            });
            return cursor;
        }
        function resultFrom(parsed) {
            var rows = parsed.rows || [];
            Object.defineProperty(rows, 'item', { value: function (index) { return rows[index]; } });
            var result = { insertId: parsed.insertId, rowsAffected: parsed.rowsAffected, rows: rows,
                length: rows.length, item: function (index) { return rows[index]; } };
            return Object.assign(result, cursorFrom(parsed));
        }
        function wrapDatabase(handle) {
            if (connections.has(handle)) return connections.get(handle);
            var closed = false, callbackIds = [];
            function call(method, args) {
                if (closed) throw new Error('Database connection is closed');
                var json = __aiNativeSqliteCall(handle, method, JSON.stringify(args || [], encodeValue));
                if (json === null || json === undefined || json === '' || json === 'null') return null;
                var value = JSON.parse(json, decodeValue);
                return method === 'rawQuery' ? cursorFrom(value) : value && value.rows ? resultFrom(value) : value;
            }
            // task-151：按分号拆分多语句（跳过字符串/注释里的分号），exec('DROP; CREATE') 不再静默 no-op。
            function splitSqlStatements(sql) {
                var parts = [], start = 0, quote = null, lineComment = false, blockComment = false;
                for (var i = 0; i < sql.length; i++) {
                    var ch = sql.charAt(i), next = i + 1 < sql.length ? sql.charAt(i + 1) : '';
                    if (lineComment) { if (ch === '\n' || ch === '\r') lineComment = false; continue; }
                    if (blockComment) { if (ch === '*' && next === '/') { blockComment = false; i++; } continue; }
                    if (quote) { if (ch === '\\') { i++; continue; } if (ch === quote) quote = null; continue; }
                    if (ch === '-' && next === '-') { lineComment = true; i++; continue; }
                    if (ch === '/' && next === '*') { blockComment = true; i++; continue; }
                    if (ch === '\'' || ch === '"' || ch === '`') { quote = ch; continue; }
                    if (ch === ';') { var part = sql.substring(start, i).trim(); if (part.length > 0) parts.push(part); start = i + 1; }
                }
                var tail = sql.substring(start).trim();
                if (tail.length > 0) parts.push(tail);
                return parts;
            }

            var db = {
                handle: handle,
                __callbackIds: callbackIds,
                exec: function (sql, args) {
                    var text = String(sql);
                    var statements = splitSqlStatements(text);
                    if (statements.length > 1) {
                        if (args !== undefined && args !== null) {
                            throw new Error('db.exec 的多语句不支持绑定参数，请拆成多次调用或改用 execSQL/select');
                        }
                        for (var i = 0; i < statements.length; i++) { call('execSQL', [statements[i]]); }
                        return null;
                    }
                    if (statements.length === 0) {
                        throw new Error('db.exec 的 SQL 为空');
                    }
                    return call('exec', [text, sqlArgs(args)]);
                },
                execSQL: function (sql, args) { call('execSQL', [String(sql), sqlArgs(args)]); },
                executeSql: function (sql, args) { call('execSQL', [String(sql), sqlArgs(args)]); },
                select: function (sql, args) { return call('select', [String(sql), sqlArgs(args)]); },
                query: function (sql, args) { return call('select', [String(sql), sqlArgs(args)]); },
                rawQuery: function (sql, args) { return call('rawQuery', [String(sql), sqlArgs(args)]); },
                insert: function (table, values) { return call('insert', [String(table), values || {}]); },
                update: function (table, values, where, args) { return call('update', [String(table), values || {}, where == null ? null : String(where), sqlArgs(args)]); },
                delete: function (table, where, args) { return call('delete', [String(table), where == null ? null : String(where), sqlArgs(args)]); },
                insertResult: function (table, values) { return call('insertResult', [String(table), values || {}]); },
                updateResult: function (table, values, where, args) { return call('updateResult', [String(table), values || {}, where == null ? null : String(where), sqlArgs(args)]); },
                deleteResult: function (table, where, args) { return call('deleteResult', [String(table), where == null ? null : String(where), sqlArgs(args)]); },
                beginTransaction: function () { call('begin', [false]); },
                beginTransactionNonExclusive: function () { call('begin', [true]); },
                endTransaction: function () { call('end', [false]); },
                setTransactionSuccessful: function () { call('succeed'); },
                isOpen: function () { return !closed && call('isOpen'); },
                isReadOnly: function () { return call('isReadOnly'); },
                inTransaction: function () { return call('inTransaction'); },
                close: function () {
                    if (closed) return;
                    call('close'); closed = true; connections.delete(handle);
                    callbackIds.forEach(global.__aiReleaseCallback);
                }
            };
            ['version', 'pageSize', 'maximumSize', 'path'].forEach(function (name) {
                var getter = 'get' + name.charAt(0).toUpperCase() + name.slice(1);
                db[getter] = function () { return call(name); };
                var property = { get: db[getter], enumerable: true };
                if (name !== 'path') {
                    var setter = 'set' + name.charAt(0).toUpperCase() + name.slice(1);
                    db[setter] = function (value) { return call(setter, [Number(value)]); };
                    property.set = db[setter];
                }
                Object.defineProperty(db, name, property);
            });
            function transaction(action, second, third, nonExclusive) {
                if (typeof action !== 'function') throw new TypeError('transaction(action) requires a function');
                var emitter = makeEmitter(), occurred = new Map(), originalOn = emitter.on;
                emitter.on = function (name, listener) {
                    if (typeof listener !== 'function') throw new TypeError('listener must be a function');
                    if (occurred.has(String(name))) listener.apply(emitter, occurred.get(String(name)));
                    else originalOn(name, listener);
                    return emitter;
                };
                emitter.addListener = emitter.on;
                emitter.once = function (name, listener) { return emitter.on(name, listener); };
                function occur(name, value) { occurred.set(name, [value]); emitter.emit(name, value); }
                var ended = false, committed = false;
                var tx = Object.create(db);
                tx.succeed = function () { if (ended) throw new Error('Transaction has ended'); committed = true; };
                tx.end = function () { if (!ended) { call('end', [committed]); ended = true; } };
                tx.rollback = function () { committed = false; tx.end(); };
                call('begin', [nonExclusive === true || second === false]);
                occur('begin', tx);
                try { action(tx); if (!ended) { committed = true; tx.end(); } }
                catch (error) {
                    occur('error', error); committed = false; tx.end();
                    occur('rollback', tx); occur('end', tx);
                    if (typeof second === 'function') emitter.on('error', second);
                    throw error; // task-149：事务异常必须传播给调用方，不能静默回滚
                }
                occur(committed ? 'commit' : 'rollback', tx); occur('end', tx);
                if (typeof second === 'function') emitter.on('error', second);
                if (typeof third === 'function') emitter.on('commit', third);
                return emitter;
            }
            db.transaction = function (action, second, third) { return transaction(action, second, third, false); };
            db.readTransaction = function (action, error, success) { return transaction(action, error, success, true); };
            db.changeVersion = function (oldVersion, newVersion, action, error, success) {
                return transaction(function (tx) { if (db.version === Number(oldVersion)) { action(tx); db.version = Number(newVersion); } }, error, success, false);
            };
            connections.set(handle, db);
            return db;
        }
        function open(name, options, callbacks) {
            options = typeof options === 'number' ? { version: options } : Object.assign({}, options || {});
            if (options.version === undefined) options.version = 1;
            if (!Number.isInteger(Number(options.version)) || Number(options.version) < 1) throw new RangeError('SQLite version must be >= 1');
            options.version = Number(options.version);
            callbacks = callbacks || {};
            var callbackIds = [], nativeCallbacks = {};
            ['onCreate', 'onUpgrade', 'onConfigure', 'onOpen', 'onCorruption'].forEach(function (name) {
                if (typeof callbacks[name] !== 'function') return;
                var id = global.__aiRegisterCallback(function (handle, oldVersion, newVersion) {
                    var args = [wrapDatabase(Number(handle))];
                    if (name === 'onUpgrade') args.push(oldVersion, newVersion);
                    return callbacks[name].apply(callbacks, args);
                });
                callbackIds.push(id); nativeCallbacks[name] = id;
            });
            options.callbacks = nativeCallbacks;
            try {
                var handle = Number(__aiNativeSqliteOpen(String(name), JSON.stringify(options)));
                var db = wrapDatabase(handle);
                Array.prototype.push.apply(db.__callbackIds, callbackIds);
                return db;
            } catch (error) { callbackIds.forEach(global.__aiReleaseCallback); throw error; }
        }
        return Object.freeze({ open: open });
    })();

    // 内置模块表：require('crypto') 等直接返回全局对象，同名文件仍可覆盖（磁盘优先）。
    global.__aiModernModules = Object.create(null);
    var builtinModules = {
        crypto: global.crypto,
        zips: global.zips,
        util: global.util,
        automator: global.automator,
        context: global.context,
        rawInput: global.rawInput,
        sqlite: global.sqlite,
        io: global.io,
        web: global.web,
        continuation: global.continuation
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
            var name = String(request);
            if (Object.prototype.hasOwnProperty.call(global.__aiModernModules, name)) return global.__aiModernModules[name];
            if (Object.prototype.hasOwnProperty.call(builtinModules, name)) {
                return builtinModules[name];
            }
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

    global.require = function (request) {
        var name = String(request);
        // 内置模块优先于磁盘文件？Rhino 的 require 只解析 assets/modules 下的内置模块，
        // 这里同样先给内置的实现，避免脚本目录里同名文件意外覆盖核心能力。
        if (Object.prototype.hasOwnProperty.call(global.__aiModernModules, name)) return global.__aiModernModules[name];
        if (Object.prototype.hasOwnProperty.call(builtinModules, name)) {
            return builtinModules[name];
        }
        return loadModule(moduleFilename(request, null), null);
    };
    global.__moduleCache = moduleCache;
    (new Function('global', String(__aiNativeModernModulesScript())))(global);

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

std::shared_ptr<const cv::Mat> aijsSiftBorrowFrame(int64_t engine, int64_t frame) {
    const auto state = findEngine(engine);
    return state ? state->frames.get(frame) : nullptr;
}

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
    installNativeFunction(state->context, global, "__aiNativeGesture", nativeGesture, 4);
    installNativeFunction(state->context, global, "__aiNativeGestures", nativeGestures, 2);
    installNativeFunction(state->context, global, "__aiNativeInputText", nativeInputText, 1);
    installNativeFunction(state->context, global, "__aiNativeCryptoCall", nativeCryptoCall, 3);
    installNativeFunction(state->context, global, "__aiNativeZipsCall", nativeZipsCall, 3);
    installNativeFunction(state->context, global, "__aiNativeScriptStopped", nativeScriptStopped, 0);
    installNativeFunction(state->context, global, "__aiNativeRequiresApi", nativeRequiresApi, 1);
    installNativeFunction(state->context, global, "__aiNativeAppVersion", nativeAppVersion, 0);
    installNativeFunction(state->context, global, "__aiNativeContextInfo", nativeContextInfo, 1);
    installNativeFunction(state->context, global, "__aiNativeSqliteOpen", nativeSqliteOpen, 2);
    installNativeFunction(state->context, global, "__aiNativeSqliteCall", nativeSqliteCall, 3);
    installNativeFunction(state->context, global, "__aiNativeConsoleCall", nativeConsoleCall, 2);
    installNativeFunction(state->context, global, "__aiNativeWebCreate", nativeWebCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeWebCall", nativeWebCall, 3);
    installNativeFunction(state->context, global, "__aiNativePostJsCallbackAsync",
                          nativePostJsCallbackAsync, 2);
    installNativeFunction(state->context, global, "__aiNativeSelectorCreate", nativeSelectorCreate, 0);
    installNativeFunction(state->context, global, "__aiNativeAutomatorCall", nativeAutomatorCall, 3);
    installNativeFunction(state->context, global, "__aiNativeRequestScreenCapture", nativeRequestScreenCapture, 1);
    installNativeFunction(state->context, global, "__aiNativeRequestScreenCaptureEx", nativeRequestScreenCaptureEx, 4);
    installNativeFunction(state->context, global, "__aiNativeRequestScreenCaptureAsync", nativeRequestScreenCaptureAsync, 1);
    installNativeFunction(state->context, global, "__aiNativeGetScreenCaptureOptions", nativeGetScreenCaptureOptions, 0);
    installNativeFunction(state->context, global, "__aiNativeScreenCaptureTake", nativeScreenCaptureTake, 0);
    installNativeFunction(state->context, global, "__aiNativeImagesStopScreenCapture", nativeImagesStopScreenCapture, 0);
    installNativeFunction(state->context, global, "__aiNativeCaptureFrame", nativeCaptureFrame, 3);
    installNativeFunction(state->context, global, "__aiNativeWindowCaptureAvailable", nativeWindowCaptureAvailable, 0);
    installNativeFunction(state->context, global, "__aiNativeListCaptureWindows", nativeListCaptureWindows, 0);
    installNativeFunction(state->context, global, "__aiNativeCaptureWindow", nativeCaptureWindow, 1);
    installNativeFunction(state->context, global, "__aiNativeReadFrame", nativeReadFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeReadRawFrame", nativeReadFrameRaw, 3);
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
    installNativeFunction(state->context, global, "__aiNativeAnalyzeXiangqiBoard", nativeAnalyzeXiangqiBoard, 1);
    installNativeFunction(state->context, global, "__aiNativeDetectXiangqiCircles", nativeDetectXiangqiCircles, 5);
    installNativeFunction(state->context, global, "__aiNativeFindColor", nativeFindColor, 7);
    installNativeFunction(state->context, global, "__aiNativeFindAllColorPoints", nativeFindAllColorPoints, 7);
    installNativeFunction(state->context, global, "__aiNativeCountColor", nativeCountColor, 7);
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
    installNativeFunction(state->context, global, "__aiNativeOcrCreate", nativeOcrCreate, 6);
    installNativeFunction(state->context, global, "__aiNativeOcrDetect", nativeOcrDetect, 8);
    installNativeFunction(state->context, global, "__aiNativeOcrRelease", nativeOcrRelease, 1);
    installNativeFunction(state->context, global, "__aiNativeOcrLiveCount", nativeOcrLiveCount, 0);
    installNativeFunction(state->context, global, "__aiNativePikafishAvailable", nativePikafishAvailable, 0);
    installNativeFunction(state->context, global, "__aiNativePikafishStart", nativePikafishStart, 4);
    installNativeFunction(state->context, global, "__aiNativePikafishAnalyze", nativePikafishAnalyze, 5);
    installNativeFunction(state->context, global, "__aiNativePikafishAnalyzePosition", nativePikafishAnalyzePosition, 5);
    installNativeFunction(state->context, global, "__aiNativePikafishStop", nativePikafishStop, 0);
    installNativeFunction(state->context, global, "__aiNativePikafishQuit", nativePikafishQuit, 0);
    installNativeFunction(state->context, global, "__aiNativePikafishIsRunning", nativePikafishIsRunning, 0);
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
    installNativeFunction(state->context, global, "__aiNativeEventsStopObserving", nativeEventsStopObserving, 1);
    installNativeFunction(state->context, global, "__aiNativeFileObserverNew", nativeFileObserverNew, 1);
    installNativeFunction(state->context, global, "__aiNativeFileObserverStart", nativeFileObserverStart, 1);
    installNativeFunction(state->context, global, "__aiNativeFileObserverStop", nativeFileObserverStop, 1);
    installNativeFunction(state->context, global, "__aiNativeDialogsShow", nativeDialogsShow, 5);
    installNativeFunction(state->context, global, "__aiNativeDialogsPoll", nativeDialogsPoll, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyCreate", nativeFloatyCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyUpdate", nativeFloatyUpdate, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyClose", nativeFloatyClose, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyCloseAll", nativeFloatyCloseAll, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatyCheckPermission", nativeFloatyCheckPermission, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatyRequestPermission", nativeFloatyRequestPermission, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewGetText", nativeFloatyViewGetText, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewSetText", nativeFloatyViewSetText, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewSetVisibility", nativeFloatyViewSetVisibility, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewClick", nativeFloatyViewClick, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewPoll", nativeFloatyViewPoll, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatySetAdjustable", nativeFloatySetAdjustable, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatySetDragHandle", nativeFloatySetDragHandle, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyIsAdjustable", nativeFloatyIsAdjustable, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetX", nativeFloatyGetX, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetY", nativeFloatyGetY, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatySetVisibility", nativeFloatySetVisibility, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyIsVisible", nativeFloatyIsVisible, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatySetContentVisibility", nativeFloatySetContentVisibility, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetContentVisibility", nativeFloatyGetContentVisibility, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewHandle", nativeFloatyViewHandle, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyRootViewHandle", nativeFloatyRootViewHandle, 1);
    installNativeFunction(state->context, global, "__aiNativeRunOnMain", nativeRunOnMain, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyExists", nativeFloatyExists, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatySetAlpha", nativeFloatySetAlpha, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatySetScale", nativeFloatySetScale, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetAlpha", nativeFloatyGetAlpha, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetRealX", nativeFloatyGetRealX, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetRealY", nativeFloatyGetRealY, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewTouch", nativeFloatyViewTouch, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewKey", nativeFloatyViewKey, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatySetWindowFocusable", nativeFloatySetWindowFocusable, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewRequestFocus", nativeFloatyViewRequestFocus, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewShape", nativeFloatyViewShape, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyTouchRegionInfo", nativeFloatyTouchRegionInfo, 0);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetTouchRegion", nativeFloatyGetTouchRegion, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewExists", nativeFloatyViewExists, 2);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewGetAttr", nativeFloatyViewGetAttr, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewSetAttr", nativeFloatyViewSetAttr, 4);
    installNativeFunction(state->context, global, "__aiNativeFloatyViewAction", nativeFloatyViewAction, 3);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetWidth", nativeFloatyGetWidth, 1);
    installNativeFunction(state->context, global, "__aiNativeFloatyGetHeight", nativeFloatyGetHeight, 1);
    installNativeFunction(state->context, global, "__aiNativeUiViewExists", nativeUiViewExists, 2);
    installNativeFunction(state->context, global, "__aiNativeUiViewAction", nativeUiViewAction, 3);
    installNativeFunction(state->context, global, "__aiNativeUiViewProbe", nativeUiViewProbe, 3);
    installNativeFunction(state->context, global, "__aiNativeUiViewInvoke", nativeUiViewInvoke, 4);
    installNativeFunction(state->context, global, "__aiNativeUiAddJsInterface", nativeUiAddJsInterface, 4);
    installNativeFunction(state->context, global, "__aiNativeJavaAdapterCreate", nativeJavaAdapterCreate, 2);
    installNativeFunction(state->context, global, "__aiNativeIsMainThread", nativeIsMainThread, 0);
    installNativeFunction(state->context, global, "__aiNativeStatusBarColor", nativeStatusBarColor, 1);
    installNativeFunction(state->context, global, "__aiNativeJavaResolveClass", nativeJavaResolveClass, 1);
    installNativeFunction(state->context, global, "__aiNativeJavaProbe", nativeJavaProbe, 2);
    installNativeFunction(state->context, global, "__aiNativeJavaNestedClassName",
                          nativeJavaNestedClassName, 2);
    installNativeFunction(state->context, global, "__aiNativeJavaCall", nativeJavaCall, 3);
    installNativeFunction(state->context, global, "__aiNativeJavaGetField", nativeJavaGetField, 2);
    installNativeFunction(state->context, global, "__aiNativeJavaSetField", nativeJavaSetField, 3);
    installNativeFunction(state->context, global, "__aiNativeJavaNew", nativeJavaNew, 2);
    installNativeFunction(state->context, global, "__aiNativeJavaContextHandle", nativeJavaContextHandle, 0);
    installNativeFunction(state->context, global, "__aiNativeFrameToMat", nativeFrameToMat, 1);
    installNativeFunction(state->context, global, "__aiNativeSiftFrameInput", nativeSiftFrameInput, 1);
    installNativeFunction(state->context, global, "__aiNativeReleaseSiftInput", nativeReleaseSiftInput, 1);
    installNativeFunction(state->context, global, "__aiNativeMatToFrame", nativeMatToFrame, 1);
    installNativeFunction(state->context, global, "__aiNativeExitSelf", nativeExitSelf, 0);
    installNativeFunction(state->context, global, "__aiNativeUiInflate", nativeUiInflate, 1);
    installNativeFunction(state->context, global, "__aiNativeHostActivity", nativeHostActivity, 0);
    installNativeFunction(state->context, global, "__aiNativeUiClose", nativeUiClose, 0);
    installNativeFunction(state->context, global, "__aiNativeUiSetConfig", nativeUiSetConfig, 3);
    installNativeFunction(state->context, global, "__aiNativeUiGetText", nativeUiGetText, 2);
    installNativeFunction(state->context, global, "__aiNativeUiSetClickListener", nativeUiSetClickListener, 2);
    installNativeFunction(state->context, global, "__aiNativeUiSetDataSource", nativeUiSetDataSource, 3);
    installNativeFunction(state->context, global, "__aiNativeUiPollEvent", nativeUiPollEvent, 0);
    installNativeFunction(state->context, global, "__aiNativeUiGetAttr", nativeUiGetAttr, 3);
    installNativeFunction(state->context, global, "__aiNativeFilesGetSdcardPath", nativeFilesGetSdcardPath, 0);
    installNativeFunction(state->context, global, "__aiNativeFilesPath", nativeFilesPath, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesGetName", nativeFilesGetName, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesGetNameWithoutExtension", nativeFilesGetNameWithoutExtension, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesGetExtension", nativeFilesGetExtension, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesIsEmptyDir", nativeFilesIsEmptyDir, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesCreateIfNotExists", nativeFilesCreateIfNotExists, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesRemoveDir", nativeFilesRemoveDir, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesReadBytes", nativeFilesReadBytes, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesDirname", nativeFilesDirname, 1);
    installNativeFunction(state->context, global, "__aiNativeFilesStat", nativeFilesStat, 1);
    installNativeFunction(state->context, global, "__aiNativeSetTimeout", nativeSetTimeout, 2);
    installNativeFunction(state->context, global, "__aiNativeSetInterval", nativeSetInterval, 2);
    installNativeFunction(state->context, global, "__aiNativeClearTimer", nativeClearTimer, 1);
    installNativeFunction(state->context, global, "__aiNativeHttpRequest", nativeHttpRequest, 5);
    installNativeFunction(state->context, global, "__aiNativeDrawCreate", nativeDrawCreate, 1);
    installNativeFunction(state->context, global, "__aiNativeDrawUpdate", nativeDrawUpdate, 2);
    installNativeFunction(state->context, global, "__aiNativeDrawClose", nativeDrawClose, 0);
    installNativeFunction(state->context, global, "__aiNativeImGuiPanel", nativeImGuiPanel, 2);
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
    installNativeFunction(state->context, global, "__aiNativeDeviceGetIMEI", nativeDeviceGetIMEI, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetMusicVolume", nativeDeviceGetMusicVolume, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetNotificationVolume", nativeDeviceGetNotificationVolume, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceKeepScreenOn", nativeDeviceKeepScreenOn, 1);
    installNativeFunction(state->context, global, "__aiNativeDeviceWakeUp", nativeDeviceWakeUp, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetScreenOrientation", nativeDeviceGetScreenOrientation, 0);
    installNativeFunction(state->context, global, "__aiNativeDeviceGetScreenRotation", nativeDeviceGetScreenRotation, 0);
    installNativeFunction(state->context, global, "__aiNativeAppGetPackageName", nativeAppGetPackageName, 1);
    installNativeFunction(state->context, global, "__aiNativeAppGetAppName", nativeAppGetAppName, 1);
    installNativeFunction(state->context, global, "__aiNativeAppOpenAppSetting", nativeAppOpenAppSetting, 1);
    installNativeFunction(state->context, global, "__aiNativeAppViewFile", nativeAppViewFile, 1);
    installNativeFunction(state->context, global, "__aiNativeAppEditFile", nativeAppEditFile, 1);
    installNativeFunction(state->context, global, "__aiNativeAppUninstall", nativeAppUninstall, 1);
    installNativeFunction(state->context, global, "__aiNativeAppGetTopActivityInfo", nativeAppGetTopActivityInfo, 0);
    installNativeFunction(state->context, global, "__aiNativeAppGetPathFromUri", nativeAppGetPathFromUri, 1);
    installNativeFunction(state->context, global, "__aiNativeWebSocketNew", nativeWebSocketNew, 1);
    installNativeFunction(state->context, global, "__aiNativeWebSocketSend", nativeWebSocketSend, 3);
    installNativeFunction(state->context, global, "__aiNativeWebSocketClose", nativeWebSocketClose, 3);
    installNativeFunction(state->context, global, "__aiNativeWebSocketCancel", nativeWebSocketCancel, 1);
    installNativeFunction(state->context, global, "__aiNativeWebSocketQueueSize", nativeWebSocketQueueSize, 1);
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
    installNativeFunction(state->context, global, "__aiNativeDialogCall", nativeDialogCall, 1);
    installNativeFunction(state->context, global, "__aiNativeModernModulesScript", nativeModernModulesScript, 0);
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
    state->handle = handle;
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

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_createNativeFrameFromHardwareBuffer(
        JNIEnv *env, jclass, jlong engineHandle, jobject javaBuffer, jint targetShortEdge) {
    const auto state = findEngine(engineHandle);
    if (state == nullptr) {
        throwQuickJs(env, "QuickJS runtime is not available");
        return 0;
    }
    // Resolve lazily: the same native library must still load on API 21 devices.
    struct BufferApi {
        void *library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        using FromJava = AHardwareBuffer *(*)(JNIEnv *, jobject);
        using Describe = void (*)(const AHardwareBuffer *, AHardwareBuffer_Desc *);
        using Lock = int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, void **);
        using Unlock = int (*)(AHardwareBuffer *, int32_t *);
        FromJava fromJava = library ? reinterpret_cast<FromJava>(dlsym(library, "AHardwareBuffer_fromHardwareBuffer")) : nullptr;
        Describe describe = library ? reinterpret_cast<Describe>(dlsym(library, "AHardwareBuffer_describe")) : nullptr;
        Lock lock = library ? reinterpret_cast<Lock>(dlsym(library, "AHardwareBuffer_lock")) : nullptr;
        Unlock unlock = library ? reinterpret_cast<Unlock>(dlsym(library, "AHardwareBuffer_unlock")) : nullptr;
    };
    static const BufferApi api;
    if (!api.fromJava || !api.describe || !api.lock || !api.unlock || !javaBuffer) return 0;
    AHardwareBuffer *buffer = api.fromJava(env, javaBuffer);
    if (!buffer || env->ExceptionCheck()) return 0;
    AHardwareBuffer_Desc desc{};
    api.describe(buffer, &desc);
    // Only canonical RGBA: other formats use Bitmap's conversion fallback, not guessed colors.
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.layers != 1
            || desc.width == 0 || desc.height == 0 || desc.stride < desc.width
            || desc.stride > static_cast<uint32_t>(std::numeric_limits<int>::max() / 4)
            || desc.height > static_cast<uint32_t>(std::numeric_limits<int>::max())) return 0;
    void *pixels = nullptr;
    if (api.lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &pixels) != 0) return 0;
    struct UnlockOnExit {
        AHardwareBuffer *buffer;
        BufferApi::Unlock unlock;
        ~UnlockOnExit() { unlock(buffer, nullptr); }
    } unlockOnExit{buffer, api.unlock};
    if (!pixels) return 0;
    try {
        // Copies/resizes directly into the existing native reusable-frame pool.
        return state->frames.createFromRgba(static_cast<uint8_t *>(pixels),
                static_cast<int>(desc.width), static_cast<int>(desc.height),
                static_cast<int>(desc.stride * 4), 4, targetShortEdge);
    } catch (const std::exception &error) {
        throwQuickJs(env, error.what());
        return 0;
    }
}

// 执行收尾（定义在 evaluate 之后，先声明）：跑完挂起 job / 定时器，再把结果转成 Java 对象。
static jobject finishExecution(JNIEnv *env, const std::shared_ptr<EngineState> &state, JSValue result);

extern "C" JNIEXPORT jobject JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_evaluate(
        JNIEnv *env, jclass, jlong handle, jstring source, jstring sourceName) {    const auto state = findEngine(handle);
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

    return finishExecution(env, state, result);
}

// 执行收尾：跑完挂起的 job、把定时器事件循环转完，再把结果转成 Java 对象。
// evaluate() 与 evaluateBytecode() 共用，避免两套收尾逻辑逐渐跑偏。
static jobject finishExecution(JNIEnv *env, const std::shared_ptr<EngineState> &state, JSValue result) {
    auto finishRequestedExit = [&]() {
        if (!state->exitRequested.exchange(false, std::memory_order_relaxed)) return false;
        JS_FreeValue(state->context, result);
        return true;
    };
    if (finishRequestedExit()) return nullptr;
    JSContext *pendingContext = nullptr;
    int pendingResult;
    while ((pendingResult = JS_ExecutePendingJob(state->runtime, &pendingContext)) > 0) {
    }
    if (state->exitRequested.load(std::memory_order_relaxed)) {
        if (pendingResult < 0) {
            JSContext *failedContext = pendingContext == nullptr ? state->context : pendingContext;
            JSValue ignored = JS_GetException(failedContext);
            JS_FreeValue(failedContext, ignored);
        }
        if (finishRequestedExit()) return nullptr;
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
        pumpJavaJsTasks(state, state->context);
        if (finishRequestedExit()) return nullptr;
        waitForNextTimer(state.get());
    }

    jobject javaResult = resultToJava(env, state->context, result);
    JS_FreeValue(state->context, result);
    return javaResult;
}

// 把脚本编译成 QuickJS 字节码（打包加密等级 ≥ 2 用）。
//
// 刻意不依赖引擎句柄：打包发生在主应用进程里，那里不一定有正在运行的引擎，
// 于是这里临时建一套 runtime/context 编译完就释放。
// 字节码与 quickjs 版本严格绑定，运行端 JS_ReadObject 会做版本校验，不匹配时干净报错。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_compileToBytecode(
        JNIEnv *env, jclass, jstring source, jstring sourceName) {
    const std::string script = fromJavaString(env, source);
    const std::string filename = fromJavaString(env, sourceName);
    const std::string displayName = filename.empty() ? "<script>" : filename;

    JSRuntime *runtime = JS_NewRuntime();
    if (runtime == nullptr) {
        throwQuickJs(env, "Unable to create a QuickJS runtime for compilation");
        return nullptr;
    }
    JSContext *context = JS_NewContext(runtime);
    if (context == nullptr) {
        JS_FreeRuntime(runtime);
        throwQuickJs(env, "Unable to create a QuickJS context for compilation");
        return nullptr;
    }

    JSValue compiled = JS_Eval(context, script.data(), script.size(), displayName.c_str(),
                               JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(compiled)) {
        const std::string message = quickJsException(context);
        JS_FreeValue(context, compiled);
        JS_FreeContext(context);
        JS_FreeRuntime(runtime);
        throwQuickJs(env, message.empty() ? "Unable to compile the script" : message);
        return nullptr;
    }

    size_t length = 0;
    uint8_t *bytes = JS_WriteObject(context, &length, compiled, JS_WRITE_OBJ_BYTECODE);
    JS_FreeValue(context, compiled);
    if (bytes == nullptr || length == 0) {
        const std::string message = quickJsException(context);
        if (bytes != nullptr) {
            js_free(context, bytes);
        }
        JS_FreeContext(context);
        JS_FreeRuntime(runtime);
        throwQuickJs(env, message.empty() ? "Unable to serialize the compiled script" : message);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte *>(bytes));
    }
    js_free(context, bytes);
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);
    return result;
}

// 执行 QuickJS 字节码（打包加密等级 ≥ 2 的产物）。
extern "C" JNIEXPORT jobject JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_evaluateBytecode(
        JNIEnv *env, jclass, jlong handle, jbyteArray code, jstring sourceName) {
    const auto state = findEngine(handle);
    if (state == nullptr) {
        throwQuickJs(env, "QuickJS runtime is not available");
        return nullptr;
    }
    state->interrupted.store(false, std::memory_order_relaxed);
    const std::string filename = fromJavaString(env, sourceName);
    const std::string displayName = filename.empty() ? "<script>" : filename;
    if (!installMainModuleGlobals(state->context, displayName)) {
        const std::string message = quickJsException(state->context);
        throwQuickJs(env, message.empty() ? "Unable to create the main CommonJS module" : message);
        return nullptr;
    }

    const jsize length = code == nullptr ? 0 : env->GetArrayLength(code);
    if (length <= 0) {
        throwQuickJs(env, "Compiled script payload is empty");
        return nullptr;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(code, 0, length, reinterpret_cast<jbyte *>(bytes.data()));

    JSValue compiled = JS_ReadObject(state->context, bytes.data(), bytes.size(), JS_READ_OBJ_BYTECODE);
    if (JS_IsException(compiled)) {
        const std::string message = quickJsException(state->context);
        JS_FreeValue(state->context, compiled);
        throwQuickJs(env, message.empty()
                ? "Compiled script is not compatible with this engine build"
                : message);
        return nullptr;
    }

    JSValue result = JS_EvalFunction(state->context, compiled);
    if (JS_IsException(result)) {
        if (state->exitRequested.load(std::memory_order_relaxed)) {
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

    return finishExecution(env, state, result);
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

/**
 * Java -> JS 同步回调。宿主在脚本执行期间（同一引擎线程）需要把控制权交回脚本时使用，
 * 例如选择器谓词 `filter(fn)`：Java 遍历控件时逐个调用脚本里的判断函数。
 *
 * 只调用脚本注册表里的分发函数 `__aiInvokeCallback(id, argsJson)`，不重新安装主模块、
 * 不跑定时器循环，避免污染脚本的 module/exports 与事件循环。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_engine_QuickJsNativeBridge_invokeJsCallback(
        JNIEnv *env, jclass, jlong handle, jlong callbackId, jstring argsJson) {
    const auto state = findEngine(handle);
    if (state == nullptr) {
        return nullptr;
    }
    JSContext *context = state->context;
    JSValue global = JS_GetGlobalObject(context);
    JSValue dispatcher = JS_GetPropertyStr(context, global, "__aiInvokeCallback");
    JS_FreeValue(context, global);
    if (!JS_IsFunction(context, dispatcher)) {
        JS_FreeValue(context, dispatcher);
        return nullptr;
    }
    const std::string args = argsJson == nullptr ? std::string("[]") : fromJavaString(env, argsJson);
    JSValue argv[2];
    argv[0] = JS_NewInt64(context, callbackId);
    argv[1] = JS_NewStringLen(context, args.data(), args.size());
    JSValue result = JS_Call(context, dispatcher, JS_UNDEFINED, 2, argv);
    JS_FreeValue(context, argv[0]);
    JS_FreeValue(context, argv[1]);
    JS_FreeValue(context, dispatcher);
    if (JS_IsException(result)) {
        const std::string message = quickJsException(context);
        JS_FreeValue(context, result);
        throwQuickJs(env, message.empty() ? "脚本回调执行失败" : message);
        return nullptr;
    }
    const std::string text = jsString(context, result);
    JS_FreeValue(context, result);
    return toJavaString(env, text);
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
