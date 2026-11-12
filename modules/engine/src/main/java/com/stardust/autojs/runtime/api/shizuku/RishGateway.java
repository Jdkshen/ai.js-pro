package com.stardust.autojs.runtime.api.shizuku;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Same-UID local command gateway. No TCP port, external DEX or app_process Context reflection. */
final class RishGateway {
    private static String address;
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private RishGateway() { }
    static synchronized String ensure(Context context) throws IOException {
        if (address != null) return address;
        final String name = "aijs-rish-" + android.os.Process.myPid() + "-" + UUID.randomUUID();
        final LocalServerSocket server = new LocalServerSocket(name);
        Context app = context.getApplicationContext(); final Context actual = app == null ? context : app;
        Thread accept = new Thread(() -> {
            while (true) try {
                final LocalSocket client = server.accept();
                if (client.getPeerCredentials().getUid() != android.os.Process.myUid()) { client.close(); continue; }
                if (CLIENTS.incrementAndGet() > 16) { CLIENTS.decrementAndGet(); client.close(); continue; }
                Thread worker = new Thread(() -> { try { handle(actual, client); } finally { CLIENTS.decrementAndGet(); } }, "aijs-rish-gateway-job");
                worker.setDaemon(true); worker.start();
            } catch (IOException failure) {
                synchronized (RishGateway.class) { if (name.equals(address)) address = null; }
                // 关闭失败可吞：上方 IOException 分支已表达失败并重置 address，关闭失败不影响结果。
                try { server.close(); } catch (IOException ignored) { } return;
            }
        }, "aijs-rish-gateway");
        accept.setDaemon(true); accept.start(); address = name; return name;
    }
    private static void handle(Context context, LocalSocket client) {
        AtomicReference<Process> active = new AtomicReference<>(); AtomicBoolean disconnected = new AtomicBoolean();
        PipedInputStream stdin = null; PipedOutputStream incoming = null;
        DataOutputStream sharedOutput = null;
        try {
            DataInputStream input = new DataInputStream(client.getInputStream());
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            sharedOutput = output;
            int size = input.readInt(); if (size < 1 || size > 262144) throw new IOException("rish请求过长");
            byte[] bytes = new byte[size]; input.readFully(bytes);
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!context.getPackageName().equals(request.optString("package"))) throw new IOException("rish应用身份不符");
            JSONArray array = request.getJSONArray("args");
            if (array.length() == 0 || array.length() > 256) throw new IOException("用法：rish -c '命令' 或 i <APK...>");
            String[] args = new String[array.length()];
            for (int i=0;i<args.length;i++) { args[i]=array.getString(i); if(args[i].indexOf('\0')>=0)throw new IOException("参数包含NUL"); }
            stdin=new PipedInputStream(65536); incoming=new PipedOutputStream(stdin); final PipedOutputStream pipe=incoming;
            Thread receive=new Thread(() -> {
                try {
                    while(true) {
                        int channel=input.readUnsignedByte(),length=input.readInt();
                        if(length<0||length>65536)throw new IOException("rish输入帧无效");
                        if(channel==1&&length==0){pipe.close();continue;}
                        if(channel!=0)throw new IOException("rish输入通道无效");
                        byte[] block=new byte[length];input.readFully(block);pipe.write(block);
                    }
                } catch(IOException end){disconnected.set(true);Process process=active.get();if(process!=null)process.destroy();}
                finally{try{pipe.close();}catch(IOException ignored){}}
            },"aijs-rish-owner");receive.setDaemon(true);receive.start();
            Process process;
            if("--install".equals(args[0])) {
                List<File> files=new ArrayList<>();List<String> options=new ArrayList<>();boolean filesOnly=false;
                File workingDirectory=new File(request.optString("cwd","/")).getCanonicalFile();
                for(int i=1;i<args.length;i++){
                    String arg=args[i];
                    if(!filesOnly && "--".equals(arg)){filesOnly=true;continue;}
                    if(!filesOnly && "--user".equals(arg)){if(++i>=args.length)throw new IOException("--user缺少参数");options.add(arg);options.add(args[i]);}
                    else if(!filesOnly && arg.startsWith("-"))options.add(arg);
                    else {File file=new File(arg);files.add((file.isAbsolute()?file:new File(workingDirectory,arg)).getCanonicalFile());}
                }
                process=ShizukuRemoteProcesses.install(files.toArray(new File[0]),options.toArray(new String[0]));
            }else{
                String command;
                if(args.length==1)command=args[0];
                else if("-c".equals(args[0])){
                    String[] argv=new String[args.length+1];argv[0]="/system/bin/sh";System.arraycopy(args,0,argv,1,args.length);
                    command="exec "+ShizukuCommands.argv(argv);
                }else throw new IOException("管道终端请使用 rish -c '命令'；不提供交互PTY");
                File cwd=new File(request.optString("cwd","/")).getCanonicalFile();
                String privateRoot=new File(context.getApplicationInfo().dataDir).getCanonicalPath();
                if(cwd.getPath().equals(privateRoot)||cwd.getPath().startsWith(privateRoot+File.separator))cwd=null;
                process=ShizukuRemoteProcesses.start(command,cwd,Collections.emptyMap(),false);
            }
            active.set(process);if(disconnected.get())process.destroy();
            Thread writer=copy(stdin,process.getOutputStream(),null,0);
            Thread stdout=copy(process.getInputStream(),null,output,1),stderr=copy(process.getErrorStream(),null,output,2);
            int exit=process.waitFor();stdout.join(3000);stderr.join(3000);writer.interrupt();
            synchronized(output){output.writeByte(3);output.writeInt(4);output.writeInt(exit);output.flush();}
        }catch(Throwable failure){
            try{
                DataOutputStream output=sharedOutput==null?new DataOutputStream(client.getOutputStream()):sharedOutput;
                byte[] text=("AI.js Pro Shizuku: "+failure.getMessage()+"\n").getBytes(StandardCharsets.UTF_8);
                synchronized(output){output.writeByte(2);output.writeInt(text.length);output.write(text);output.writeByte(3);output.writeInt(4);output.writeInt(1);output.flush();}
            }catch(IOException ignored){}
        }finally{
            Process process=active.get();if(process!=null)process.destroy();
            try{if(incoming!=null)incoming.close();}catch(IOException ignored){}
            try{if(stdin!=null)stdin.close();}catch(IOException ignored){}
            try{client.close();}catch(IOException ignored){}
        }
    }
    private static Thread copy(InputStream input,OutputStream raw,DataOutputStream frames,int channel){
        Thread thread=new Thread(() -> {try{
            byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))!=-1){
                if(frames==null){raw.write(buffer,0,count);raw.flush();}
                else synchronized(frames){frames.writeByte(channel);frames.writeInt(count);frames.write(buffer,0,count);frames.flush();}
            }
        // 可吞：流拷贝线程的 IOException（管道断开），finally 里已关闭输入/输出流，无实际后果（有意留空）。
        }catch(IOException ignored){}
        finally{try{input.close();if(raw!=null)raw.close();}catch(IOException ignored){}}},"aijs-rish-stream");
        thread.setDaemon(true);thread.start();return thread;
    }
}
