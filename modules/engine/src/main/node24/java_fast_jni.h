// SPDX-License-Identifier: MIT
#pragma once
#include <jni.h>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

// Owned C++ values only cross the callback queues; never V8 handles or local JNI refs.
struct JavaFastValue {
  int kind = 0; // null/bool/number/string/object/class/array/view; -1 = error
  bool boolean = false;
  double number = 0;
  int reference = 0;
  int length = 0;
  int type = 0;
  std::u16string text;
  std::u16string className;
};

struct AjsJavaFast {
  JavaVM* vm;
  jclass objectClass=nullptr, numberClass=nullptr, integerClass=nullptr;
  jclass longClass=nullptr, doubleClass=nullptr, booleanClass=nullptr;
  jmethodID invoke, intValue, doubleValue, booleanValue;
  jmethodID integerOf, longOf, doubleOf, booleanOf;

  static jclass retain(JNIEnv* env, const char* name) {
    jclass local=env->FindClass(name);
    if(!local) throw std::runtime_error("Cannot resolve typed Java bridge classes");
    auto result=static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if(!result) throw std::runtime_error("Cannot retain typed Java bridge class");
    return result;
  }
  AjsJavaFast(JNIEnv* env, JavaVM* value, jmethodID method):vm(value),invoke(method) {
    try {
    objectClass=retain(env,"java/lang/Object");
    numberClass=retain(env,"java/lang/Number");
    integerClass=retain(env,"java/lang/Integer");
    longClass=retain(env,"java/lang/Long");
    doubleClass=retain(env,"java/lang/Double");
    booleanClass=retain(env,"java/lang/Boolean");
    intValue=env->GetMethodID(numberClass,"intValue","()I");
    doubleValue=env->GetMethodID(numberClass,"doubleValue","()D");
    booleanValue=env->GetMethodID(booleanClass,"booleanValue","()Z");
    integerOf=env->GetStaticMethodID(integerClass,"valueOf","(I)Ljava/lang/Integer;");
    longOf=env->GetStaticMethodID(longClass,"valueOf","(J)Ljava/lang/Long;");
    doubleOf=env->GetStaticMethodID(doubleClass,"valueOf","(D)Ljava/lang/Double;");
    booleanOf=env->GetStaticMethodID(booleanClass,"valueOf","(Z)Ljava/lang/Boolean;");
    if(env->ExceptionCheck()) throw std::runtime_error("Cannot bind typed Java bridge methods");
    } catch(...) {clear(env);throw;}
  }
  void clear(JNIEnv* env) {
    for(jclass c:{objectClass,numberClass,integerClass,longClass,doubleClass,booleanClass})
      if(c) env->DeleteGlobalRef(c);
  }
  ~AjsJavaFast() {
    JNIEnv* env=nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_OK)
      clear(env);
  }
  static std::u16string text(JNIEnv* env, jobject object) {
    if(!object) return {};
    auto value=static_cast<jstring>(object);
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars) throw std::runtime_error("Cannot read typed Java string");
    std::u16string result(reinterpret_cast<const char16_t*>(chars),env->GetStringLength(value));
    env->ReleaseStringChars(value,chars);
    return result;
  }
  JavaFastValue call(jobject host, int member, int receiver, const std::vector<JavaFastValue>& args) {
    JNIEnv* env=nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)
      throw std::runtime_error("Typed Java host is unavailable on this thread");
    if(env->PushLocalFrame(32)!=JNI_OK) {env->ExceptionClear();throw std::runtime_error("Cannot allocate typed Java frame");}
    try {
      auto values=env->NewObjectArray(static_cast<jsize>(args.size()),objectClass,nullptr);
      auto references=env->NewIntArray(static_cast<jsize>(args.size()));
      if(!values||!references) throw std::runtime_error("Cannot allocate typed Java arguments");
      std::vector<jint> ids(args.size(),0);
      for(size_t i=0;i<args.size();++i) {
        const auto& arg=args[i];
        jobject value=nullptr;
        if(arg.kind==1) value=env->CallStaticObjectMethod(booleanClass,booleanOf,static_cast<jboolean>(arg.boolean));
        else if(arg.kind==2) {
          // Match JSONObject's integer/long/double representation and overload scoring.
          double n=arg.number;
          if(n>=-2147483648.0&&n<=2147483647.0&&std::trunc(n)==n)
            value=env->CallStaticObjectMethod(integerClass,integerOf,static_cast<jint>(n));
          else if(n>=-9223372036854775808.0&&n<9223372036854775808.0&&std::trunc(n)==n)
            value=env->CallStaticObjectMethod(longClass,longOf,static_cast<jlong>(n));
          else value=env->CallStaticObjectMethod(doubleClass,doubleOf,n);
        } else if(arg.kind==3) value=env->NewString(reinterpret_cast<const jchar*>(arg.text.data()),static_cast<jsize>(arg.text.size()));
        else if(arg.kind==4) ids[i]=arg.reference;
        if(env->ExceptionCheck()) throw std::runtime_error("Cannot box typed Java argument");
        env->SetObjectArrayElement(values,static_cast<jsize>(i),value);
        if(value) env->DeleteLocalRef(value);
        if(env->ExceptionCheck()) throw std::runtime_error("Cannot store typed Java argument");
      }
      if(!ids.empty()) env->SetIntArrayRegion(references,0,static_cast<jsize>(ids.size()),ids.data());
      if(env->ExceptionCheck()) throw std::runtime_error("Cannot store typed Java references");
      auto result=static_cast<jobjectArray>(env->CallObjectMethod(host,invoke,member,receiver,values,references));
      if(env->ExceptionCheck()||!result) throw std::runtime_error("Typed Java host invocation failed");
      JavaFastValue out;
      jobject tag=env->GetObjectArrayElement(result,0);
      out.kind=env->CallIntMethod(tag,intValue);
      jobject payload=env->GetObjectArrayElement(result,1);
      if(out.kind==1) out.boolean=env->CallBooleanMethod(payload,booleanValue);
      else if(out.kind==2) {
        out.number=env->CallDoubleMethod(payload,doubleValue);
      } else if(out.kind==3||out.kind==5||out.kind==-1) out.text=text(env,payload);
      else if(out.kind==4||out.kind==6||out.kind==7) {
        out.reference=env->CallIntMethod(payload,intValue);
        out.className=text(env,env->GetObjectArrayElement(result,2));
        if(out.kind==6) out.length=env->CallIntMethod(env->GetObjectArrayElement(result,3),intValue);
        out.type=env->CallIntMethod(env->GetObjectArrayElement(result,4),intValue);
      }
      if(env->ExceptionCheck()) throw std::runtime_error("Cannot decode typed Java result");
      env->PopLocalFrame(nullptr);
      return out;
    } catch(...) {if(env->ExceptionCheck()) env->ExceptionClear();env->PopLocalFrame(nullptr);throw;}
  }
};
