package org.aijsprobe.nodeplugin;
public final class ProbeService extends android.app.Service {
 public android.os.IBinder onBind(android.content.Intent intent) {return new android.os.Binder() {
  @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags) throws android.os.RemoteException {
   if(code!=1)return super.onTransact(code,data,reply,flags);
   data.enforceInterface("org.aijsprobe.nodeplugin"); String action=data.readString();
   java.util.Map args=data.readHashMap(getClass().getClassLoader());
   java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
   if(!"sum".equals(action)) {reply.writeException(new IllegalArgumentException("unknown action: "+action));return true;}
   result.put("sum",((Number)args.get("a")).intValue()+((Number)args.get("b")).intValue());
   result.put("pid",android.os.Process.myPid());result.put("nested",args.get("nested"));
   reply.writeNoException(); reply.writeMap(result);return true;
  }
 };}
}
