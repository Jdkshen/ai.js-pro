"nodejs";
// The main-thread Runnable fires after Node exits. It must safely return without
// calling a dead V8 context or crashing Android's main thread.
new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
  new java.lang.Runnable(() => { console.error('JAVA_LATE_CALLBACK_UNEXPECTED'); }), 1200);
console.log('JAVA_LATE_CALLBACK_SCHEDULED');
