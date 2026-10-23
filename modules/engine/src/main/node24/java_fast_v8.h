// SPDX-License-Identifier: MIT
#pragma once

void javaFastCall(const v8::FunctionCallbackInfo<v8::Value>& args) {
  auto* isolate=args.GetIsolate();
  auto ctx=isolate->GetCurrentContext();
  auto failCall=[isolate](const char* message) {
    isolate->ThrowException(v8::Exception::TypeError(v8::String::NewFromUtf8(isolate,message).ToLocalChecked()));
  };
  if(args.Length()!=3||!args[0]->IsInt32()||!args[1]->IsInt32()||!args[2]->IsArray()) {
    failCall("Typed Java call requires member ID, receiver ID and argument array");return;
  }
  auto input=args[2].As<v8::Array>();
  if(input->Length()>1024) {failCall("Too many typed Java arguments");return;}
  std::vector<JavaFastValue> values;
  values.reserve(input->Length());
  for(uint32_t i=0;i<input->Length();++i) {
    v8::Local<v8::Value> value;
    if(!input->Get(ctx,i).ToLocal(&value)) return;
    JavaFastValue out;
    if(value->IsNullOrUndefined()) {}
    else if(value->IsBoolean()) {out.kind=1;out.boolean=value.As<v8::Boolean>()->Value();}
    else if(value->IsNumber()) {
      out.number=value.As<v8::Number>()->Value();
      out.kind=std::isfinite(out.number)?2:0;
    } else if(value->IsString()) {
      out.kind=3;v8::String::Value text(isolate,value);
      if(!*text) return;
      out.text.assign(reinterpret_cast<const char16_t*>(*text),text.length());
    } else if(value->IsObject()) {
      v8::Local<v8::Value> reference;
      if(!value.As<v8::Object>()->Get(ctx,v8::String::NewFromUtf8Literal(isolate,"$obj")).ToLocal(&reference)) return;
      if(!reference->IsInt32()||reference.As<v8::Int32>()->Value()<=0) {failCall("Invalid typed Java object reference");return;}
      out.kind=4;out.reference=reference.As<v8::Int32>()->Value();
    } else {failCall("Unsupported typed Java argument");return;}
    values.push_back(std::move(out));
  }
  auto* host=static_cast<Node24Callbacks*>(args.Data().As<v8::External>()->Value());
  AjsHost::Reply reply;
  try {reply=host->callJava(args[0].As<v8::Int32>()->Value(),args[1].As<v8::Int32>()->Value(),std::move(values));}
  catch(const std::exception& failure) {failCall(failure.what());return;}
  auto string=[isolate](const std::u16string& text) {
    return v8::String::NewFromTwoByte(isolate,reinterpret_cast<const uint16_t*>(text.data()),v8::NewStringType::kNormal,static_cast<int>(text.size())).ToLocalChecked();
  };
  if(reply.error) {isolate->ThrowException(v8::Exception::Error(string(reply.text)));return;}
  if(!reply.fast) {failCall("Missing typed Java result");return;}
  const auto& result=*reply.fast;
  auto put=[&](v8::Local<v8::Object> object,const char* key,v8::Local<v8::Value> value) {
    return object->Set(ctx,v8::String::NewFromUtf8(isolate,key).ToLocalChecked(),value).FromMaybe(false);
  };
  v8::Local<v8::Value> value=v8::Null(isolate);
  switch(result.kind) {
    case 1:value=v8::Boolean::New(isolate,result.boolean);break;
    case 2:value=v8::Number::New(isolate,result.number);break;
    case 3:value=string(result.text);break;
    case 4:case 5:case 6:case 7: {
      auto descriptor=v8::Object::New(isolate);
      if(result.kind!=5&&!put(descriptor,"$obj",v8::Integer::New(isolate,result.reference))) return;
      if(!put(descriptor,"$class",string(result.kind==5?result.text:result.className))) return;
      if(result.kind==6&&!put(descriptor,"$length",v8::Integer::New(isolate,result.length))) return;
      if(result.kind==7&&!put(descriptor,"$view",v8::True(isolate))) return;
      if(result.kind!=5&&!put(descriptor,"$type",v8::Integer::New(isolate,result.type))) return;
      value=descriptor;break;
    }
    default:break;
  }
  args.GetReturnValue().Set(value);
}
