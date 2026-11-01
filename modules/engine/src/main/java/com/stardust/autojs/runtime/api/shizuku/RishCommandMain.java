package com.stardust.autojs.runtime.api.shizuku;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import androidx.annotation.Keep;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Command-mode compatibility client; loaded directly from the installed, read-only APK. */
@Keep
public final class RishCommandMain {
    private RishCommandMain() { }
    @Keep public static void main(String[] args) {
        int exit=1;
        try(LocalSocket socket=new LocalSocket()){
            String address=System.getenv("AIJS_RISH_SOCKET"),pkg=System.getenv("RISH_APPLICATION_ID");
            if(address==null||pkg==null)throw new IOException("请从AI.js Pro内置终端运行rish/i");
            socket.connect(new LocalSocketAddress(address,LocalSocketAddress.Namespace.ABSTRACT));
            DataInputStream input=new DataInputStream(socket.getInputStream());
            DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            JSONArray argv=new JSONArray();for(String arg:args)argv.put(arg);
            byte[] request=new JSONObject().put("package",pkg).put("args",argv).put("cwd",android.system.Os.readlink("/proc/self/cwd")).toString().getBytes(StandardCharsets.UTF_8);
            output.writeInt(request.length);output.write(request);output.flush();
            Thread writer=new Thread(() -> {try{
                byte[] buffer=new byte[16384];int count;
                while((count=System.in.read(buffer))!=-1)synchronized(output){output.writeByte(0);output.writeInt(count);output.write(buffer,0,count);output.flush();}
                synchronized(output){output.writeByte(1);output.writeInt(0);output.flush();}
            }catch(IOException ignored){}},"aijs-rish-stdin");writer.setDaemon(true);writer.start();
            while(true){
                int channel=input.readUnsignedByte(),length=input.readInt();
                if(length<0||length>1048576)throw new IOException("rish输出帧无效");
                if(channel==3&&length==4){exit=input.readInt();break;}
                byte[] bytes=new byte[length];input.readFully(bytes);
                if(channel==1)System.out.write(bytes);else if(channel==2)System.err.write(bytes);else throw new IOException("rish输出通道无效");
            }
        }catch(Throwable failure){System.err.println("AI.js Pro Shizuku: "+failure.getMessage());}
        finally{System.out.flush();System.err.flush();System.exit(exit);}
    }
}
