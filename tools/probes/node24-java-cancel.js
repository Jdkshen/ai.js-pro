"nodejs";
// Device test: stop this script after JAVA_CANCEL_READY. JAVA_CANCEL_UNEXPECTED
// must never appear, and the next script must still run successfully.
const Runnable = $java.findClass('java.lang.Runnable');
new Runnable(() => {}); // Enable the real bidirectional Java callback bridge.
console.log('JAVA_CANCEL_READY');
java.lang.Thread.sleep(60000);
console.error('JAVA_CANCEL_UNEXPECTED');
process.exitCode = 1;
