// SPDX-License-Identifier: MIT
#pragma once
#include <deque>
#include <functional>
#include <uv.h>

// V8 stays on the Node thread. A foreign Java callback waits here and services
// reflected Java calls on its original thread (important for Canvas/View calls).
struct Node24HostTask {
  std::u16string request;
  std::function<AjsHost::Reply()> action;
  AjsHost::Reply reply{u"Java host stopped", true};
  bool done = false;
  std::mutex mutex;
  std::condition_variable changed;
};
struct Node24CallbackTask {
  std::u16string request;
  AjsHost::Reply reply{u"Node callback stopped", true};
  bool done = false;
  std::deque<std::shared_ptr<Node24HostTask>> hostCalls;
  std::mutex mutex;
  std::condition_variable changed;
};

struct Node24Callbacks {
  AjsHost host;
  jlong id;
  v8::Isolate* isolate = nullptr;
  v8::Global<v8::Context> context;
  std::thread::id nodeThread;
  uv_async_t async{};
  bool initialized = false;
  std::atomic<bool> closing{false};
  bool enabled = false; // Node thread only
  Node24CallbackTask* currentCallback = nullptr; // Node thread only
  std::mutex mutex;
  std::condition_variable changed;
  std::deque<std::shared_ptr<Node24CallbackTask>> callbacks;
  std::deque<std::shared_ptr<Node24HostTask>> hostCalls;
  std::thread worker;
  jobject workerJavaThread = nullptr; // protected by mutex

  Node24Callbacks(JNIEnv* env, jobject javaHost, jlong sessionId):host(env,javaHost),id(sessionId) {}

  void initialize(v8::Isolate* value, v8::Local<v8::Context> ctx, uv_loop_t* loop) {
    isolate = value;
    context.Reset(isolate, ctx);
    nodeThread = std::this_thread::get_id();
    if (uv_async_init(loop, &async, [](uv_async_t* h) {
          static_cast<Node24Callbacks*>(h->data)->pump();
        }) != 0) throw std::runtime_error("Cannot initialize Node callback queue");
    async.data = this;
    uv_unref(reinterpret_cast<uv_handle_t*>(&async));
    initialized = true;
  }

  void enable() {
    if (enabled) return;
    worker = std::thread([this] {
      JNIEnv* env = nullptr;
      if (!host.vm || host.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        closing.store(true);
        return;
      }
      jclass threadClass = env->FindClass("java/lang/Thread");
      jmethodID current = threadClass ? env->GetStaticMethodID(threadClass,"currentThread","()Ljava/lang/Thread;") : nullptr;
      jobject self = current ? env->CallStaticObjectMethod(threadClass,current) : nullptr;
      if (env->ExceptionCheck()) env->ExceptionClear();
      if (self) {
        std::lock_guard<std::mutex> lock(mutex);
        workerJavaThread = env->NewGlobalRef(self);
        env->DeleteLocalRef(self);
      }
      if (threadClass) env->DeleteLocalRef(threadClass);
      for (;;) {
        std::shared_ptr<Node24HostTask> task;
        {
          std::unique_lock<std::mutex> lock(mutex);
          changed.wait(lock, [&] { return closing.load() || !hostCalls.empty(); });
          if (closing.load() || hostCalls.empty()) break;
          task = hostCalls.front(); hostCalls.pop_front();
        }
        finishHost(task);
      }
      {
        std::lock_guard<std::mutex> lock(mutex);
        if (workerJavaThread) env->DeleteGlobalRef(workerJavaThread);
        workerJavaThread = nullptr;
      }
      host.vm->DetachCurrentThread();
    });
    enabled = true;
  }

  void finishHost(const std::shared_ptr<Node24HostTask>& task) {
    AjsHost::Reply reply;
    try { reply = task->action ? task->action() : host.call(task->request); }
    catch (const std::exception&) { reply = {u"Java callback host failed", true}; }
    {
      std::lock_guard<std::mutex> lock(task->mutex);
      task->reply = std::move(reply); task->done = true;
    }
    task->changed.notify_all();
  }

  AjsHost::Reply call(const std::u16string& request) {
    if (!enabled) return host.call(request); // preserve the fast path for ordinary scripts
    auto task = std::make_shared<Node24HostTask>(); task->request = request;
    return submit(task);
  }

  AjsHost::Reply callJava(int member,int receiver,std::vector<JavaFastValue> args) {
    if (!enabled) return host.callJava(member,receiver,args);
    auto task = std::make_shared<Node24HostTask>();
    task->action = [this,member,receiver,args=std::move(args)] { return host.callJava(member,receiver,args); };
    return submit(task);
  }

  AjsHost::Reply submit(const std::shared_ptr<Node24HostTask>& task) {
    if (currentCallback) {
      {
        std::lock_guard<std::mutex> lock(currentCallback->mutex);
        if (currentCallback->done) return {u"Java callback is no longer active", true};
        currentCallback->hostCalls.push_back(task);
      }
      currentCallback->changed.notify_all();
    } else {
      {
        std::lock_guard<std::mutex> lock(mutex);
        if (closing.load()) return {u"Node callbacks are closing", true};
        hostCalls.push_back(task);
      }
      changed.notify_all();
    }
    for (;;) {
      {
        std::unique_lock<std::mutex> lock(task->mutex);
        if (task->done) return task->reply;
        if (closing.load()) return {u"Node callbacks are closing", true};
        task->changed.wait_for(lock, std::chrono::milliseconds(2));
      }
      // A Java call can synchronously invoke an override, including while a
      // previous override is already waiting for a reflected Java call.
      pump();
    }
  }

  AjsHost::Reply dispatch(const std::u16string& request) {
    v8::HandleScope handles(isolate);
    auto ctx = context.Get(isolate);
    v8::Context::Scope scope(ctx);
    v8::TryCatch caught(isolate);
    v8::Local<v8::Value> fn;
    v8::Local<v8::String> input;
    if (!ctx->Global()->Get(ctx, v8::String::NewFromUtf8Literal(isolate,"__ajsDispatchJavaCallback")).ToLocal(&fn)
        || !fn->IsFunction()) return {u"Java callback dispatcher is unavailable", true};
    if (!v8::String::NewFromTwoByte(isolate, reinterpret_cast<const uint16_t*>(request.data()),
        v8::NewStringType::kNormal, static_cast<int>(request.size())).ToLocal(&input))
      return {u"Cannot allocate Java callback input", true};
    v8::Local<v8::Value> argv[] = {input};
    v8::Local<v8::Value> value;
    if (!fn.As<v8::Function>()->Call(ctx, ctx->Global(), 1, argv).ToLocal(&value))
      return {u"JavaScript callback threw an exception or was cancelled", true};
    if (!value->IsString()) return {u"Java callback must return JSON text", true};
    v8::String::Value text(isolate,value);
    if (!*text) return {u"Cannot read Java callback result", true};
    return {std::u16string(reinterpret_cast<const char16_t*>(*text),text.length()), false};
  }

  void pump() {
    for (;;) {
      std::shared_ptr<Node24CallbackTask> task;
      {
        std::lock_guard<std::mutex> lock(mutex);
        if (callbacks.empty()) break;
        task = callbacks.front(); callbacks.pop_front();
      }
      {
        std::lock_guard<std::mutex> lock(task->mutex);
        if (task->done) continue;
      }
      auto* previous = currentCallback;
      currentCallback = task.get();
      auto reply = closing.load() ? AjsHost::Reply{u"Node callback session closed",true} : dispatch(task->request);
      currentCallback = previous;
      {
        std::lock_guard<std::mutex> lock(task->mutex);
        task->reply = std::move(reply); task->done = true;
      }
      task->changed.notify_all();
    }
  }

  AjsHost::Reply invoke(const std::u16string& request) {
    if (closing.load()) return {u"Node callback session closed",true};
    if (std::this_thread::get_id() == nodeThread) return dispatch(request);
    auto task = std::make_shared<Node24CallbackTask>(); task->request = request;
    {
      std::lock_guard<std::mutex> lock(mutex);
      if (closing.load()) return {u"Node callback session closed",true};
      callbacks.push_back(task);
      uv_async_send(&async);
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    for (;;) {
      std::shared_ptr<Node24HostTask> hostTask;
      {
        std::unique_lock<std::mutex> lock(task->mutex);
        if (task->done) return task->reply;
        if (closing.load() || std::chrono::steady_clock::now() >= deadline) {
          task->done = true;
          return {closing.load() ? u"Node callback session closed" : u"Node callback timed out",true};
        }
        if (!task->hostCalls.empty()) {
          hostTask = task->hostCalls.front(); task->hostCalls.pop_front();
        } else task->changed.wait_for(lock,std::chrono::milliseconds(2));
      }
      if (hostTask) finishHost(hostTask);
    }
  }

  void cancel() {
    {
      std::lock_guard<std::mutex> lock(mutex);
      closing.store(true);
      callbacks.clear();
    }
    changed.notify_all();
    JNIEnv* env = nullptr;
    if (host.vm && host.vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6) == JNI_OK) {
      jobject thread = nullptr;
      { std::lock_guard<std::mutex> lock(mutex); if (workerJavaThread) thread=env->NewLocalRef(workerJavaThread); }
      if (thread) {
        jclass type=env->GetObjectClass(thread);
        jmethodID interrupt=type?env->GetMethodID(type,"interrupt","()V"):nullptr;
        if (interrupt) env->CallVoidMethod(thread,interrupt);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (type) env->DeleteLocalRef(type);
        env->DeleteLocalRef(thread);
      }
    }
  }

  void close(uv_loop_t* loop) {
    cancel();
    if (worker.joinable()) worker.join();
    if (initialized) {
      uv_close(reinterpret_cast<uv_handle_t*>(&async),nullptr);
      uv_run(loop,UV_RUN_NOWAIT);
      initialized = false;
    }
    context.Reset();
  }
};
