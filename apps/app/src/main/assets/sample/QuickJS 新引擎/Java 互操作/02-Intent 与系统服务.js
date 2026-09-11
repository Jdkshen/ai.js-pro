// @engine quickjs
// Intent 与系统服务
// 用途：用预导入的 Intent / context 启动应用、发送广播、读剪贴板
// 前置：无
// 覆盖：device

console.log('包名 = ' + context.packageName);

// 2) 剪贴板：桥接方法（setClip/getClip）+ 真实系统服务两条路
//    注意：Android 10+ 只有前台应用能读剪贴板，所以先在这里读，再去切界面。
setClip('QuickJS Java 互操作示例');
console.log('剪贴板 = ' + getClip());
var clipboard = context.getSystemService('clipboard');
console.log('系统剪贴板实现类 = ' + clipboard.getClass().getSimpleName());

// 3) obj.getClass() 与 Rhino 一样返回可用的 java.lang.Class 对象
console.log('context.getClass().getName() = ' + context.getClass().getName());
console.log('context.getClass().getSimpleName() = ' + context.getClass().getSimpleName());

// 4) 打开一个网址（Intent.ACTION_VIEW + 浏览器）
var uri = android.net.Uri.parse('https://cn.bing.com');
var view = new Intent(Intent.ACTION_VIEW, uri);
view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(view);
sleep(1200);
back();
sleep(500);

// 5) 打开本应用的系统设置页（ACTION_APPLICATION_DETAILS_SETTINGS）
var detail = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
detail.setData(android.net.Uri.parse('package:' + context.packageName));
detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(detail);
sleep(1200);
back();
sleep(500);

// 6) 读自己的包信息（PackageManager 反射调用）
var info = context.getPackageManager().getPackageInfo(context.packageName, 0);
console.log('versionName = ' + info.versionName + ', versionCode = ' + info.versionCode);

// 7) 发送一个自定义广播（本应用内可被 BroadcastReceiver 收到）
var broadcast = new Intent('com.jdkshen.aijspro.DEMO_BROADCAST');
broadcast.putExtra('from', 'quickjs');
context.sendBroadcast(broadcast);
console.log('广播已发送');

// 8) 设备信息（桥接模块 + 反射两条路）
console.log('device.model = ' + device.model + ', sdkInt = ' + android.os.Build.VERSION.SDK_INT);
console.log('电池 = ' + JSON.stringify(device.getBattery()));

// 9) 预导入的其它类名同样可直接用：Paint / Shell / KeyEvent / MutableOkHttp / Canvas / Image / RootAutomator / Input / Module
var paint = new Paint();
paint.setTextSize(48);
console.log('Paint.measureText("QuickJS") = ' + paint.measureText('QuickJS'));
console.log('KeyEvent.KEYCODE_BACK = ' + KeyEvent.KEYCODE_BACK);
console.log('Shell 可用 = ' + (typeof Shell === 'function'));
