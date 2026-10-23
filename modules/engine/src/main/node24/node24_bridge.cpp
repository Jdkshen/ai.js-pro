// SPDX-License-Identifier: MIT
// Original bridge against the public Node 24 embedder API. Not Pluto/J2V8 ABI.
#include <jni.h>
#include <node.h>
#include <cppgc/platform.h>
#include <v8-version.h>
#include "host_jni.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include "node24_callbacks.h"
#include "java_fast_v8.h"

static_assert(NODE_MAJOR_VERSION == 24 && NODE_MINOR_VERSION == 22 && NODE_PATCH_VERSION == 0,
              "This bridge must be reviewed against the pinned Node 24.22.0 headers");

namespace {
struct Session {
  jlong id = 0;
  std::shared_ptr<Node24Callbacks> callbacks;
  std::mutex mutex;
  std::condition_variable changed;
  node::Environment* environment = nullptr; // protected until publication ends
  bool completed = false;
  std::atomic<bool> used{false};
  std::atomic<bool> cancelled{false};
  std::atomic<bool> timedOut{false};
  std::atomic<bool> exitRequested{false};
  std::atomic<int> exitCode{0};
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> nextId{1};

void fail(JNIEnv* env, const char* type, const char* message) {
  jclass cls = env->FindClass(type);
  if(cls) env->ThrowNew(cls, message);
}
std::shared_ptr<Session> find(JNIEnv* env, jlong id) {
  std::lock_guard<std::mutex> lock(registryMutex);
  auto it = sessions.find(id);
  if(it != sessions.end()) return it->second;
  fail(env, "java/lang/IllegalStateException", "Node session is closed");
  return {};
}
void requestStop(const std::shared_ptr<Session>& session, bool timeout) {
  std::shared_ptr<Node24Callbacks> callbacks;
  {
    std::lock_guard<std::mutex> lock(session->mutex);
    if(session->completed) return;
    if(timeout) session->timedOut.store(true);
    session->cancelled.store(true);
    callbacks=session->callbacks;
    // Node documents Stop(Environment*) as safe from another thread.
    if(session->environment) node::Stop(session->environment);
  }
  if(callbacks) callbacks->cancel();
}

struct Runtime {
  std::once_flag initialized;
  std::mutex activeRun;
  std::exception_ptr startupFailure;
  std::shared_ptr<node::InitializationResult> initialization;
  std::unique_ptr<node::MultiIsolatePlatform> platform;
  void ensure() {
    std::call_once(initialized, [&] {
      try {
      const std::string expected = std::to_string(V8_MAJOR_VERSION)+"."+std::to_string(V8_MINOR_VERSION)+"."+
          std::to_string(V8_BUILD_NUMBER)+"."+std::to_string(V8_PATCH_LEVEL);
      if(std::string(v8::V8::GetVersion()).rfind(expected,0) != 0)
        throw std::runtime_error("Loaded V8 does not match the pinned Node 24 headers; use an isolated process");
      initialization = node::InitializeOncePerProcess({"autojs-node24"}, {
        node::ProcessInitializationFlags::kNoInitializeV8,
        node::ProcessInitializationFlags::kNoInitializeNodeV8Platform,
        node::ProcessInitializationFlags::kNoInitializeCppgc,
        node::ProcessInitializationFlags::kDisableNodeOptionsEnv,
        node::ProcessInitializationFlags::kNoStdioInitialization,
        node::ProcessInitializationFlags::kNoDefaultSignalHandling,
        node::ProcessInitializationFlags::kNoAdjustResourceLimits
      });
      if(!initialization || initialization->early_return())
        throw std::runtime_error("Node process initialization failed");
      platform = node::MultiIsolatePlatform::Create(2);
      if(!platform) throw std::runtime_error("Node V8 platform initialization failed");
      v8::V8::InitializePlatform(platform.get());
      cppgc::InitializeProcess(platform->GetPageAllocator());
      if(!v8::V8::Initialize()) throw std::runtime_error("V8 initialization failed");
      } catch(...) { startupFailure=std::current_exception(); }
    });
    // Never retry partially initialized process-global Node/V8 state.
    if(startupFailure) std::rethrow_exception(startupFailure);
  }
};
Runtime& runtime() {
  // This JNI library owns a process-lifetime Node platform. Android must use a
  // dedicated process, not load Node 24 next to the legacy Node 16/libv8 pair.
  static Runtime* value = new Runtime();
  return *value;
}

std::u16string javaText(JNIEnv* env, jstring value) {
  const jchar* text = env->GetStringChars(value,nullptr);
  if(!text) throw std::runtime_error("Cannot read Java string");
  std::u16string result(reinterpret_cast<const char16_t*>(text),env->GetStringLength(value));
  env->ReleaseStringChars(value,text);
  return result;
}
std::string utf8(const std::u16string& input) {
  std::string out;
  for(size_t i=0;i<input.size();++i) {
    uint32_t code=input[i];
    if(code>=0xD800 && code<=0xDBFF && i+1<input.size() && input[i+1]>=0xDC00 && input[i+1]<=0xDFFF)
      code=0x10000+((code-0xD800)<<10)+(input[++i]-0xDC00);
    else if(code>=0xD800 && code<=0xDFFF) code=0xFFFD;
    if(code<0x80) out+=static_cast<char>(code);
    else if(code<0x800) { out+=static_cast<char>(0xC0|(code>>6)); out+=static_cast<char>(0x80|(code&63)); }
    else if(code<0x10000) { out+=static_cast<char>(0xE0|(code>>12)); out+=static_cast<char>(0x80|((code>>6)&63)); out+=static_cast<char>(0x80|(code&63)); }
    else { out+=static_cast<char>(0xF0|(code>>18)); out+=static_cast<char>(0x80|((code>>12)&63)); out+=static_cast<char>(0x80|((code>>6)&63)); out+=static_cast<char>(0x80|(code&63)); }
  }
  return out;
}
std::u16string exceptionText(v8::Isolate* isolate, v8::TryCatch& caught) {
  if(!caught.HasCaught() || caught.HasTerminated()) return {};
  v8::String::Value value(isolate,caught.Exception());
  if(!*value) return u"Node JavaScript exception";
  return std::u16string(reinterpret_cast<const char16_t*>(*value),value.length());
}
jobject result(JNIEnv* env,int code,const std::shared_ptr<Session>& session,const std::u16string& error) {
  jclass cls=env->FindClass("org/autojs/source/engine/Node24Engine$Result");
  if(!cls) return nullptr;
  jmethodID constructor=env->GetMethodID(cls,"<init>","(IZZLjava/lang/String;)V");
  if(!constructor) return nullptr;
  jstring message=env->NewString(reinterpret_cast<const jchar*>(error.data()),static_cast<jsize>(error.size()));
  if(!message) return nullptr;
  return env->NewObject(cls,constructor,code,static_cast<jboolean>(session->cancelled.load()),static_cast<jboolean>(session->timedOut.load()),message);
}

struct PublishedEnvironment {
  std::shared_ptr<Session> session;
  std::thread deadline;
  PublishedEnvironment(std::shared_ptr<Session> s,node::Environment* env,jlong timeout):session(std::move(s)) {
    {
      std::lock_guard<std::mutex> lock(session->mutex);
      session->environment=env;
      if(session->cancelled.load()) node::Stop(env);
    }
    if(timeout==0)return; // explicitly unbounded user task; cancel/close still work
    try {
      deadline=std::thread([s=session,timeout] {
        std::unique_lock<std::mutex> lock(s->mutex);
        if(s->changed.wait_for(lock,std::chrono::milliseconds(timeout),[&] { return s->completed; })) return;
        lock.unlock();
        requestStop(s,true);
      });
    } catch(...) {
      std::lock_guard<std::mutex> lock(session->mutex);
      session->environment=nullptr; session->completed=true;
      throw;
    }
  }
  void finish() {
    { std::lock_guard<std::mutex> lock(session->mutex); session->environment=nullptr; session->completed=true; }
    session->changed.notify_all();
    if(deadline.joinable()) deadline.join();
  }
  ~PublishedEnvironment() { finish(); }
};

void hostCall(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate=args.GetIsolate();
  if(args.Length()!=1||!args[0]->IsString()) {isolate->ThrowException(v8::Exception::TypeError(v8::String::NewFromUtf8Literal(isolate,"JSON host request required")));return;}
  v8::String::Value value(isolate,args[0]);
  auto* host=static_cast<Node24Callbacks*>(args.Data().As<v8::External>()->Value());
  auto reply=host->call(std::u16string(reinterpret_cast<const char16_t*>(*value),value.length()));
  v8::Local<v8::String> text;
  if(!v8::String::NewFromTwoByte(isolate,reinterpret_cast<const uint16_t*>(reply.text.data()),v8::NewStringType::kNormal,static_cast<int>(reply.text.size())).ToLocal(&text))return;
  if(reply.error)isolate->ThrowException(v8::Exception::Error(text));else args.GetReturnValue().Set(text);
}
jobject execute(JNIEnv* jni,const std::shared_ptr<Session>& session,const std::string& source,const std::string& file,jlong timeout,jobject javaHost=nullptr) {
  if(session->cancelled.load()) return result(jni,1,session,{});
  Runtime& process=runtime();
  std::unique_lock<std::mutex> exclusive(process.activeRun,std::try_to_lock);
  if(!exclusive.owns_lock()) throw std::runtime_error("Another Node session is running in this process");
  process.ensure();
  auto host=std::make_shared<Node24Callbacks>(jni,javaHost,session->id);
  std::vector<std::string> errors;
  auto setup=node::CommonEnvironmentSetup::Create(process.platform.get(),&errors,
      std::vector<std::string>{"autojs-node24",source,file},std::vector<std::string>{});
  if(!setup) throw std::runtime_error(errors.empty()?"Cannot create Node environment":errors.front());
  PublishedEnvironment active(session,setup->env(),timeout);
  int code=1;
  std::u16string error;
  {
    v8::Locker locker(setup->isolate());
    v8::Isolate::Scope isolateScope(setup->isolate());
    v8::HandleScope handles(setup->isolate());
    v8::Context::Scope contextScope(setup->context());
    v8::TryCatch caught(setup->isolate());
    if(javaHost) {
      host->initialize(setup->isolate(),setup->context(),setup->event_loop());
      { std::lock_guard<std::mutex> lock(session->mutex); session->callbacks=host; }
      auto data=v8::External::New(setup->isolate(),host.get());
      auto enable=v8::Function::New(setup->context(),[](const v8::FunctionCallbackInfo<v8::Value>& args) {
        auto* value=static_cast<Node24Callbacks*>(args.Data().As<v8::External>()->Value());
        try { value->enable(); args.GetReturnValue().Set(static_cast<double>(value->id)); }
        catch(const std::exception&) { args.GetIsolate()->ThrowException(v8::Exception::Error(
            v8::String::NewFromUtf8Literal(args.GetIsolate(),"Cannot enable Java callbacks"))); }
      },data).ToLocalChecked();
      if(!setup->context()->Global()->Set(setup->context(),v8::String::NewFromUtf8Literal(setup->isolate(),"__ajsEnableJavaCallbacks"),enable).FromMaybe(false))
        throw std::runtime_error("Cannot install Java callback initializer");
      auto function=v8::Function::New(setup->context(),hostCall,data).ToLocalChecked();
      if(!setup->context()->Global()->Set(setup->context(),v8::String::NewFromUtf8Literal(setup->isolate(),"__autojsHost"),function).FromMaybe(false))
        throw std::runtime_error("Cannot install Android host function");
      if(host->host.javaFast) {
        auto typed=v8::Function::New(setup->context(),javaFastCall,data).ToLocalChecked();
        if(!setup->context()->Global()->Set(setup->context(),v8::String::NewFromUtf8Literal(setup->isolate(),"__ajsJavaFastCall"),typed).FromMaybe(false))
          throw std::runtime_error("Cannot install typed Java host function");
      }
    }
    node::SetProcessExitHandler(setup->env(),[session](node::Environment* env,int exitCode) {
      session->exitRequested.store(true); session->exitCode.store(exitCode);
      node::Stop(env);
    });
    // Node's documented embedder bootstrap. User text is passed as argv, never
    // concatenated into this bootstrap. File is required to be absolute by Java.
    auto loaded=node::LoadEnvironment(setup->env(),
        "const path = require('node:path');"
        "const filename = process.argv[2];"
        "globalThis.require = require('node:module').createRequire(filename);"
        "globalThis.__filename = filename;"
        "globalThis.__dirname = path.dirname(filename);"
        "const vm = require('node:vm');"
        "vm.runInThisContext(process.argv[1], {filename, importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER});");
    if(!loaded.IsEmpty()) code=node::SpinEventLoop(setup->env()).FromMaybe(1);
    if(caught.HasCaught() && !caught.HasTerminated()) error=exceptionText(setup->isolate(),caught);
    if(session->exitRequested.load()) code=session->exitCode.load();
    if(session->cancelled.load()) code=1;
    node::Stop(setup->env());
    if(javaHost) host->close(setup->event_loop());
  }
  // active is destroyed before setup, preventing concurrent cancellation from
  // touching freed Node state. All Node/V8 destruction stays on the run thread.
  active.finish();
  return result(jni,code,session,error);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_org_autojs_source_engine_Node24Engine_nativeCreate(JNIEnv* env,jclass) {
  try {
    auto session=std::make_shared<Session>(); jlong id=nextId.fetch_add(1); session->id=id;
    std::lock_guard<std::mutex> lock(registryMutex); sessions.emplace(id,std::move(session)); return id;
  } catch(const std::bad_alloc&) { fail(env,"java/lang/OutOfMemoryError","Cannot allocate Node session"); return 0; }
  catch(const std::exception&) { fail(env,"java/lang/IllegalStateException","Cannot create Node session"); return 0; }
}
extern "C" JNIEXPORT jobject JNICALL Java_org_autojs_source_engine_Node24Engine_nativeExecute(JNIEnv* env,jclass,jlong id,jstring source,jstring filename,jlong timeout) {
  auto session=find(env,id); if(!session) return nullptr;
  if(!source || !filename || timeout<1 || timeout>60000) { fail(env,"java/lang/IllegalArgumentException","Source, filename and timeout 1..60000 ms required"); return nullptr; }
  if(session->used.exchange(true)) { fail(env,"java/lang/IllegalStateException","Node sessions execute once; create a new session"); return nullptr; }
  try { return execute(env,session,utf8(javaText(env,source)),utf8(javaText(env,filename)),timeout); }
  catch(const std::exception& error) {
    if(!env->ExceptionCheck()) fail(env,"java/lang/IllegalStateException",error.what());
    return nullptr;
  }
}
extern "C" JNIEXPORT jobject JNICALL Java_org_autojs_source_engine_Node24Engine_nativeExecuteWithHost(JNIEnv* env,jclass,jlong id,jstring source,jstring filename,jlong timeout,jobject host) {
  auto session=find(env,id);if(!session)return nullptr;
  if(!source||!filename||!host||timeout<0||timeout>3600000) {fail(env,"java/lang/IllegalArgumentException","Source, host and timeout 0..3600000 ms required");return nullptr;}
  if(session->used.exchange(true)){fail(env,"java/lang/IllegalStateException","Node session executes once");return nullptr;}
  try {return execute(env,session,utf8(javaText(env,source)),utf8(javaText(env,filename)),timeout,host);}
  catch(const std::exception& error){if(!env->ExceptionCheck())fail(env,"java/lang/IllegalStateException",error.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_autojs_source_engine_Node24Engine_nativeCancel(JNIEnv* env,jclass,jlong id) {
  auto session=find(env,id); if(session) requestStop(session,false);
}
extern "C" JNIEXPORT void JNICALL Java_org_autojs_source_engine_Node24Engine_nativeClose(JNIEnv*,jclass,jlong id) {
  std::shared_ptr<Session> session;
  { std::lock_guard<std::mutex> lock(registryMutex); auto it=sessions.find(id); if(it!=sessions.end()) { session=it->second; sessions.erase(it); } }
  if(session) requestStop(session,false);
}
extern "C" JNIEXPORT jstring JNICALL Java_org_autojs_source_engine_Node24Engine_nativeCompiledVersion(JNIEnv* env,jclass) {
  return env->NewStringUTF("Node " NODE_VERSION_STRING " headers; runtime linking/execution must be validated separately");
}
extern "C" JNIEXPORT jstring JNICALL Java_org_autojs_source_engine_Node24Engine_nativeInvokeCallback(
    JNIEnv* env,jclass,jlong id,jstring request) {
  auto session=find(env,id); if(!session)return nullptr;
  if(!request) {fail(env,"java/lang/IllegalArgumentException","Callback JSON required");return nullptr;}
  std::shared_ptr<Node24Callbacks> callbacks;
  { std::lock_guard<std::mutex> lock(session->mutex); callbacks=session->callbacks; }
  if(!callbacks) {fail(env,"java/lang/IllegalStateException","Node callbacks are unavailable");return nullptr;}
  try {
    auto reply=callbacks->invoke(javaText(env,request));
    if(reply.error) {
      if(reply.text==u"Node callback timed out") requestStop(session,true);
      auto message=utf8(reply.text);fail(env,"java/lang/IllegalStateException",message.c_str());return nullptr;
    }
    return env->NewString(reinterpret_cast<const jchar*>(reply.text.data()),static_cast<jsize>(reply.text.size()));
  } catch(const std::exception& error) {fail(env,"java/lang/IllegalStateException",error.what());return nullptr;}
}
