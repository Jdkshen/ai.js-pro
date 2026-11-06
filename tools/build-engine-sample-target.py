"""Build a local Android target for sample gestures, selectors and uninstall tests."""
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'output/mi8-three-engines-20261019/target-app'
SDK = Path('C:/Android')
JDK = Path('C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot/bin')
BUILD = SDK / 'build-tools/34.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'


def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=OUT)


def main():
    for directory in ['res/values', 'gen', 'classes', 'dex', 'assets']:
        (OUT / directory).mkdir(parents=True, exist_ok=True)
    (OUT / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="com.jdkshen.aijspro.apitest" android:versionCode="1" android:versionName="1.0">
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28"/>
 <application android:label="AI.js API Test" android:allowBackup="false" android:theme="@android:style/Theme.Material.Light.NoActionBar">
 <activity android:name=".MainActivity" android:exported="true"><intent-filter>
 <action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
 </intent-filter></activity></application></manifest>''', encoding='utf8')
    (OUT / 'res/values/ids.xml').write_text('''<resources>
 <item name="api_counter" type="id"/><item name="api_button" type="id"/>
 <item name="api_input" type="id"/><item name="api_checked" type="id"/>
 </resources>''', encoding='utf8')
    source = '''package com.jdkshen.aijspro.apitest;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.widget.*;
import android.util.Log;
public class MainActivity extends Activity {
 private int count;
 public boolean dispatchTouchEvent(MotionEvent e) {
  if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_UP)
   Log.i("EngineApiTarget","touch action="+e.getActionMasked()+" pointers="+e.getPointerCount()+" x="+e.getX()+" y="+e.getY());
  return super.dispatchTouchEvent(e);
 }
 public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  root.setPadding(24,24,24,24);root.setBackgroundColor(Color.WHITE);
  TextView title=new TextView(this);title.setText("Engine API Test / 三引擎手势与选择器测试");title.setTextSize(22);root.addView(title);
  TextView color=new TextView(this);color.setText("#009688");color.setTextColor(Color.WHITE);color.setBackgroundColor(0xff009688);
  root.addView(color,new LinearLayout.LayoutParams(-1,100));
  TextView counter=new TextView(this);counter.setId(R.id.api_counter);counter.setText("点击次数: 0");root.addView(counter);
  EditText input=new EditText(this);input.setId(R.id.api_input);input.setHint("测试输入");input.setSingleLine();root.addView(input);
  Button button=new Button(this);button.setId(R.id.api_button);button.setText("点我");
  button.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){counter.setText("点击次数: "+(++count));Log.i("EngineApiTarget","button count="+count);}});root.addView(button);
  CheckBox check=new CheckBox(this);check.setId(R.id.api_checked);check.setText("测试勾选");root.addView(check);
  ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER);
  try{image.setImageDrawable(Drawable.createFromStream(getAssets().open("template.png"),null));}catch(Exception e){throw new RuntimeException(e);}
  root.addView(image,new LinearLayout.LayoutParams(-1,180));
  ScrollView scroll=new ScrollView(this);LinearLayout items=new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);
  for(int n=0;n<50;n++){TextView text=new TextView(this);text.setText("测试列表项 "+n);text.setTextSize(18);text.setPadding(16,20,16,20);items.addView(text);}
  scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
 }
}'''
    (OUT / 'MainActivity.java').write_text(source, encoding='utf8')
    fixture = ROOT / 'apps/app/src/main/assets/sample/Rhino 引擎/图片与图色处理/找图/mario.png'
    (OUT / 'assets/template.png').write_bytes(fixture.read_bytes())
    run(BUILD / 'aapt.exe', 'package', '-f', '-M', OUT / 'AndroidManifest.xml', '-S', OUT / 'res',
        '-A', OUT / 'assets', '-I', ANDROID, '-J', OUT / 'gen', '-F', OUT / 'resources.apk')
    run(JDK / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', ANDROID,
        '-d', OUT / 'classes', OUT / 'MainActivity.java', OUT / 'gen/R.java')
    run(BUILD / 'd8.bat', '--lib', ANDROID, '--output', OUT / 'dex', *list((OUT / 'classes').rglob('*.class')))
    unsigned = OUT / 'target-unsigned.apk'
    unsigned.write_bytes((OUT / 'resources.apk').read_bytes())
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(OUT / 'dex/classes.dex', 'classes.dex')
    run(BUILD / 'zipalign.exe', '-f', '4', unsigned, OUT / 'target-aligned.apk')
    key = OUT / 'test-only.p12'
    if not key.exists():
        run(JDK / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'test', '-keyalg', 'RSA', '-validity', '30', '-dname', 'CN=Local Engine Sample Test')
    run(BUILD / 'apksigner.bat', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', OUT / 'engine-api-target.apk', OUT / 'target-aligned.apk')
    print('Built:', OUT / 'engine-api-target.apk')


if __name__ == '__main__':
    main()
