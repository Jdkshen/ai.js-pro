// SPDX-License-Identifier: MIT
#pragma once
#include <jni.h>
#include <string>
#include <stdexcept>
#include <utility>
#include "java_fast_jni.h"

// One synchronous JSON host boundary. Calls stay on an attached execution thread.
struct AjsHost {
  JavaVM* vm=nullptr;
  jobject object=nullptr;
  jmethodID invoke=nullptr;
  std::unique_ptr<AjsJavaFast> javaFast;
  struct Reply { std::u16string text; bool error; std::shared_ptr<JavaFastValue> fast = nullptr; };
  AjsHost(JNIEnv* env,jobject host) {
    if(!host)return;
    if(env->GetJavaVM(&vm)!=JNI_OK)throw std::runtime_error("Cannot access Java host VM");
    jclass type=env->GetObjectClass(host);
    invoke=type?env->GetMethodID(type,"invoke","(Ljava/lang/String;)Ljava/lang/String;"):nullptr;
    jmethodID typed=type?env->GetMethodID(type,"invokeJava","(II[Ljava/lang/Object;[I)[Ljava/lang/Object;"):nullptr;
    // Test/older hosts expose only the JSON ABI.
    if(env->ExceptionCheck()) env->ExceptionClear();
    if(typed) javaFast=std::make_unique<AjsJavaFast>(env,vm,typed);
    if(type)env->DeleteLocalRef(type);
    if(!invoke)throw std::runtime_error("Host must implement String invoke(String)");
    object=env->NewGlobalRef(host);
    if(!object)throw std::runtime_error("Cannot retain Java host");
  }
  ~AjsHost() {
    JNIEnv* env=nullptr;
    if(object&&vm&&vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_OK)env->DeleteGlobalRef(object);
  }
  AjsHost(const AjsHost&)=delete;
  AjsHost& operator=(const AjsHost&)=delete;
  Reply callJava(int member,int receiver,const std::vector<JavaFastValue>& args) {
    if(!javaFast) return {u"Typed Java host is unavailable",true};
    auto value=std::make_shared<JavaFastValue>(javaFast->call(object,member,receiver,args));
    return {value->kind==-1?value->text:std::u16string{},value->kind==-1,std::move(value)};
  }
  Reply call(const std::u16string& request) {
    JNIEnv* env=nullptr;
    if(!object||vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)
      return {u"Java host is unavailable on this thread",true};
    if(env->PushLocalFrame(16)!=JNI_OK){env->ExceptionClear();return {u"Cannot allocate Java host local frame",true};}
    jstring input=env->NewString(reinterpret_cast<const jchar*>(request.data()),static_cast<jsize>(request.size()));
    jstring value=nullptr;
    if(input)value=static_cast<jstring>(env->CallObjectMethod(object,invoke,input));
    bool failed=env->ExceptionCheck();
    if(failed) {
      jthrowable error=env->ExceptionOccurred();env->ExceptionClear();
      jclass type=env->FindClass("java/lang/Throwable");
      jmethodID toString=type?env->GetMethodID(type,"toString","()Ljava/lang/String;"):nullptr;
      if(toString)value=static_cast<jstring>(env->CallObjectMethod(error,toString));
      if(env->ExceptionCheck()){env->ExceptionClear();value=nullptr;}
    }
    std::u16string text;
    if(value) {
      const jchar* chars=env->GetStringChars(value,nullptr);
      if(chars){text.assign(reinterpret_cast<const char16_t*>(chars),env->GetStringLength(value));env->ReleaseStringChars(value,chars);}
    } else text=failed?u"Android host call failed":u"null";
    if(env->ExceptionCheck()){env->ExceptionClear();failed=true;text=u"Cannot read Android host response";}
    env->PopLocalFrame(nullptr);
    return {std::move(text),failed};
  }
};
