package org.childhood.arcade;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class LanServer {
    interface Host {
        void js(String code);
        void button(String source,int slot,int button,boolean down);
        void release(String source);
        File storeDir();
        File cacheDir();
    }
    private final Host host;
    private final Handler uiHandler;
    private static final SecureRandom LAN_RANDOM=new SecureRandom();
    private final Object lanLock=new Object();
    private volatile ServerSocket lanSocket=null;
    private volatile Thread lanThread=null;
    private volatile String lanToken=null,lanAddress=null,lanPadUrl=null,lanBackupUrl=null,lanRestoreUrl=null;
    private volatile int lanPort=0;
    private volatile File lanTempFile=null;
    private final Object lanInputLock=new Object();
    private final String[] lanSlots=new String[2];
    private final HashMap<String,LanClient> lanClients=new HashMap<String,LanClient>();
    private volatile boolean lanWatchdogOn=false;
    LanServer(Host host){this.host=host;this.uiHandler=new Handler(Looper.getMainLooper());}
    private static String message(Throwable e){String text=e.getMessage();return text==null||text.length()==0?e.getClass().getSimpleName():text;}
    private static byte[] readAll(InputStream in,int max)throws IOException{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Data too large");out.write(buf,0,n);}return out.toByteArray();}finally{in.close();}}
    private static String lanRandomToken(){String chars="abcdefghijklmnopqrstuvwxyz0123456789";StringBuilder out=new StringBuilder(6);for(int i=0;i<6;i++)out.append(chars.charAt(LAN_RANDOM.nextInt(chars.length())));return out.toString();}
    private static String lanLocalAddress(){
        try{
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null&&interfaces.hasMoreElements()){
                NetworkInterface net=interfaces.nextElement();
                if(!net.isUp()||net.isLoopback())continue;
                Enumeration<InetAddress> addresses=net.getInetAddresses();
                while(addresses.hasMoreElements()){
                    InetAddress address=addresses.nextElement();
                    if(address instanceof Inet4Address&&!address.isLoopbackAddress()&&!address.isLinkLocalAddress())return address.getHostAddress();
                }
            }
        }catch(Throwable ignored){}
        return null;
    }
    private static boolean lanTokenOk(String value,String token){return value!=null&&token!=null&&value.equals(token);}
    private static String lanParam(String query,String key){
        if(query==null||query.length()==0)return null;
        String[] pairs=query.split("&");
        for(String pair:pairs){int at=pair.indexOf('=');if(at<0)continue;if(pair.substring(0,at).equals(key))return pair.substring(at+1);}
        return null;
    }
    private static int lanInt(String value,int fallback){try{return value==null?fallback:Integer.parseInt(value.trim());}catch(NumberFormatException e){return fallback;}}
    private String lanJson(){
        try{return new JSONObject().put("running",true).put("url",lanPadUrl).put("backupUrl",lanBackupUrl).put("restoreUrl",lanRestoreUrl).put("port",lanPort).put("token",lanToken).toString();}
        catch(Exception e){return "{\"running\":false}";}
    }
    String start(){
        synchronized(lanLock){
            try{
                if(lanSocket!=null&&!lanSocket.isClosed()){lanRefreshAddress();return lanJson();}
                String address=lanLocalAddress();
                if(address==null){try{return new JSONObject().put("running",false).put("error","电视没有连接网络，请先连接 Wi-Fi").toString();}catch(Exception ignored){return "{\"running\":false}";}}
                ServerSocket server=new ServerSocket(0,16,InetAddress.getByName("0.0.0.0"));
                String token=lanRandomToken();
                lanSocket=server;lanToken=token;lanAddress=address;lanPort=server.getLocalPort();
                lanPadUrl="http://"+lanAddress+":"+lanPort+"/pad/"+token;
                lanBackupUrl="http://"+lanAddress+":"+lanPort+"/backup/"+token;
                lanRestoreUrl="http://"+lanAddress+":"+lanPort+"/restore/"+token;
                synchronized(lanInputLock){lanClients.clear();lanSlots[0]=null;lanSlots[1]=null;}
                final ServerSocket listener=server;
                Thread thread=new Thread(new Runnable(){public void run(){lanAccept(listener);}},"ArcadeLan");
                thread.setDaemon(true);lanThread=thread;thread.start();
                lanWatchdogOn=true;uiHandler.removeCallbacks(lanWatchdog);uiHandler.postDelayed(lanWatchdog,2000);
                return lanJson();
            }catch(Throwable e){
                stop();
                try{return new JSONObject().put("running",false).put("error",message(e)).toString();}catch(Exception ignored){return "{\"running\":false}";}
            }
        }
    }
    String infoJson(){synchronized(lanLock){if(lanSocket==null||lanSocket.isClosed())return "{\"running\":false}";lanRefreshAddress();return lanJson();}}
    private void lanRefreshAddress(){
        String address=lanLocalAddress();
        if(address==null||address.equals(lanAddress))return;
        lanAddress=address;
        lanPadUrl="http://"+address+":"+lanPort+"/pad/"+lanToken;
        lanBackupUrl="http://"+address+":"+lanPort+"/backup/"+lanToken;
        lanRestoreUrl="http://"+address+":"+lanPort+"/restore/"+lanToken;
    }
    void stop(){
        synchronized(lanLock){
            lanWatchdogOn=false;uiHandler.removeCallbacks(lanWatchdog);lanRemoteRelease();
            ServerSocket server=lanSocket;lanSocket=null;
            if(server!=null){try{server.close();}catch(IOException ignored){}}
            Thread thread=lanThread;lanThread=null;
            if(thread!=null&&thread!=Thread.currentThread()){try{thread.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            lanToken=null;lanAddress=null;lanPadUrl=null;lanBackupUrl=null;lanRestoreUrl=null;lanPort=0;
            File temp=lanTempFile;lanTempFile=null;
            if(temp!=null)temp.delete();
        }
        host.js("Arcade.remoteReset()");
    }
    private void lanAccept(ServerSocket server){
        while(!server.isClosed()){
            Socket client;
            try{client=server.accept();}catch(IOException e){break;}
            final Socket socket=client;
            Thread worker=new Thread(new Runnable(){public void run(){lanHandle(socket);}},"ArcadeLanRequest");
            worker.setDaemon(true);worker.start();
        }
    }
    private static String lanReadLine(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int c;
        while((c=in.read())!=-1){
            if(c=='\n')break;
            if(c!='\r')out.write(c);
            if(out.size()>8192)throw new IOException("请求头过长");
        }
        if(c==-1&&out.size()==0)return null;
        return new String(out.toByteArray(),"UTF-8");
    }
    private static byte[] lanReadBody(InputStream in,int length)throws IOException{
        byte[] body=new byte[length];
        int offset=0;
        while(offset<length){int n=in.read(body,offset,length-offset);if(n<=0)throw new IOException("请求内容不完整");offset+=n;}
        return body;
    }
    private void lanRemoteRelease(){
        synchronized(lanInputLock){
            for(LanClient client:lanClients.values())try{host.release("lan:"+client.id);}catch(Throwable ignored){}
            lanClients.clear();lanSlots[0]=null;lanSlots[1]=null;
        }
    }
    private static final class LanClient{
        final String id;int slot;long seen;
        LanClient(String id,int slot,long seen){this.id=id;this.slot=slot;this.seen=seen;}
    }
    /** Caller holds lanInputLock. Returns 1/2, or 0 when both slots are taken. */
    private int lanClaimSlot(String id,int preferred){
        long now=android.os.SystemClock.elapsedRealtime();
        LanClient client=lanClients.get(id);
        boolean fresh=client==null;
        int current=client==null?0:client.slot;
        int want=(preferred==1||preferred==2)?preferred:current;
        if(want>0){
            String owner=lanSlots[want-1];
            if(owner==null||owner.equals(id)){
                if(current>0&&current!=want)lanSlots[current-1]=null;
                lanSlots[want-1]=id;current=want;
            }
        }
        if(current==0)for(int i=0;i<2&&current==0;i++)if(lanSlots[i]==null||lanSlots[i].equals(id)){lanSlots[i]=id;current=i+1;}
        if(current==0)return 0;
        if(client==null)lanClients.put(id,new LanClient(id,current,now));
        else{
            if(client.slot!=current)host.release("lan:"+id);
            client.slot=current;client.seen=now;
        }
        if(fresh)host.js("Arcade.remotePlayer("+current+")");
        return current;
    }
    private final Runnable lanWatchdog=new Runnable(){public void run(){
        if(!lanWatchdogOn)return;
        long now=android.os.SystemClock.elapsedRealtime();
        synchronized(lanInputLock){
            Iterator<Map.Entry<String,LanClient>> it=lanClients.entrySet().iterator();
            while(it.hasNext()){
                LanClient client=it.next().getValue();
                if(now-client.seen<=7000)continue;
                host.release("lan:"+client.id);
                if(client.slot>0&&client.id.equals(lanSlots[client.slot-1]))lanSlots[client.slot-1]=null;
                it.remove();
                host.js("Arcade.remoteLeft("+client.slot+")");
            }
        }
        if(lanWatchdogOn)uiHandler.postDelayed(this,2000);
    }};
    private void lanHandle(Socket socket){
        try{
            socket.setSoTimeout(8000);
            BufferedInputStream reader=new BufferedInputStream(socket.getInputStream());
            String request=lanReadLine(reader);
            if(request==null)return;
            int contentLength=0;
            for(int i=0;i<64;i++){
                String header=lanReadLine(reader);
                if(header==null||header.length()==0)break;
                int at=header.indexOf(':');
                if(at>0&&header.substring(0,at).trim().equalsIgnoreCase("Content-Length"))contentLength=lanInt(header.substring(at+1),-1);
            }
            String[] parts=request.split(" ");
            if(parts.length<2){lanWrite(socket,400,"错误请求");return;}
            String method=parts[0],target=parts[1],query="";
            int mark=target.indexOf('?');
            if(mark>=0){query=target.substring(mark+1);target=target.substring(0,mark);}
            String token=lanToken;
            if(target.startsWith("/pad/")){
                if(!lanTokenOk(target.substring(5),token)){lanWrite(socket,403,"禁止访问");return;}
                String client=lanRandomToken();
                lanWriteBytes(socket,200,"text/html; charset=utf-8",PAD_PAGE.replace("__TOKEN__",token).replace("__CLIENT__",client).getBytes("UTF-8"),null);
                return;
            }
            if(target.startsWith("/claim/")){
                if(!lanTokenOk(target.substring(7),token)){lanWrite(socket,403,"禁止访问");return;}
                String client=lanParam(query,"c");
                if(client==null||!client.matches("[a-z0-9]{6,16}")){lanWrite(socket,400,"参数错误");return;}
                int slot;
                synchronized(lanInputLock){slot=lanClaimSlot(client,lanInt(lanParam(query,"p"),0));}
                lanWriteBytes(socket,200,"application/json; charset=utf-8",("{\"slot\":"+slot+"}").getBytes("UTF-8"),null);
                return;
            }
            if(target.startsWith("/input/")){
                if(!lanTokenOk(target.substring(7),token)){lanWrite(socket,403,"禁止访问");return;}
                int player=lanInt(lanParam(query,"p"),0),button=lanInt(lanParam(query,"b"),-1),down=lanInt(lanParam(query,"d"),0);
                if(button<0||button>11){lanWrite(socket,400,"参数错误");return;}
                String client=lanParam(query,"c");
                if(client==null||!client.matches("[a-z0-9]{6,16}"))client=player==2?"legacy-2":"legacy-1";
                int slot;
                synchronized(lanInputLock){slot=lanClaimSlot(client,lanClients.containsKey(client)?0:player);}
                if(slot<=0){lanWrite(socket,409,"两个手柄位置都已占用");return;}
                try{host.button("lan:"+client,slot,button,down!=0);}catch(Throwable ignored){}
                lanWrite(socket,200,"ok");
                return;
            }
            if(target.startsWith("/ping/")){
                if(!lanTokenOk(target.substring(6),token)){lanWrite(socket,403,"禁止访问");return;}
                String client=lanParam(query,"c");
                boolean known=true;
                synchronized(lanInputLock){
                    LanClient entry=client==null?null:lanClients.get(client);
                    if(entry!=null)entry.seen=android.os.SystemClock.elapsedRealtime();
                    else if(client!=null)known=false;
                }
                if(!known){lanWrite(socket,410,"手柄位置已收回");return;}
                lanWrite(socket,200,"ok");
                return;
            }
            if(target.startsWith("/command/")){
                if(!lanTokenOk(target.substring(9),token)){lanWrite(socket,403,"禁止访问");return;}
                String client=lanParam(query,"c"),cmd=lanParam(query,"cmd");
                if(client==null||!client.matches("[a-z0-9]{6,16}")){lanWrite(socket,400,"参数错误");return;}
                int slot=0,leftSlot=0;
                synchronized(lanInputLock){
                    LanClient entry=lanClients.get(client);
                    if(entry!=null){entry.seen=android.os.SystemClock.elapsedRealtime();slot=entry.slot;}
                    if("leave".equals(cmd)&&entry!=null){
                        leftSlot=entry.slot;
                        lanClients.remove(client);
                        if(leftSlot>=1&&leftSlot<=2&&client.equals(lanSlots[leftSlot-1]))lanSlots[leftSlot-1]=null;
                        try{host.release("lan:"+client);}catch(Throwable ignored){}
                        slot=0;
                    }
                }
                if("leave".equals(cmd)){if(leftSlot>0)host.js("Arcade.remoteLeft("+leftSlot+")");lanWrite(socket,200,"ok");return;}
                if(slot<=0){lanWrite(socket,410,"手柄位置已收回");return;}
                if("pause".equals(cmd))host.js("Arcade.togglePauseMenu()");
                lanWrite(socket,200,"ok");
                return;
            }
            if(target.startsWith("/backup/")){
                if(!lanTokenOk(target.substring(8),token)){lanWrite(socket,403,"禁止访问");return;}
                File zip=null;
                try{
                    zip=lanBuildBackup();
                    byte[] data=readAll(new FileInputStream(zip),64*1024*1024);
                    lanWriteBytes(socket,200,"application/json; charset=utf-8",data,"childhood-arcade-backup.json");
                }catch(Throwable e){
                    lanWrite(socket,500,"备份生成失败，请稍后重试");
                }finally{
                    if(zip!=null)zip.delete();
                    if(lanTempFile==zip)lanTempFile=null;
                }
                return;
            }
            if(target.startsWith("/restore/")){
                if(!lanTokenOk(target.substring(9),token)){lanWrite(socket,403,"禁止访问");return;}
                if("GET".equals(method)){
                    lanWriteBytes(socket,200,"text/html; charset=utf-8",RESTORE_PAGE.replace("__TOKEN__",token).getBytes("UTF-8"),null);
                    return;
                }
                if(!"POST".equals(method)){lanWrite(socket,405,"方法不支持");return;}
                if(contentLength>64*1024*1024){lanWrite(socket,413,"备份文件过大");return;}
                if(contentLength<=0){lanWrite(socket,400,"备份文件无效");return;}
                String text=new String(lanReadBody(reader,contentLength),"UTF-8");
                boolean valid=false;
                try{
                    JSONObject root=new JSONObject(text);
                    valid=root.optInt("schema",-1)==1&&"childhood-arcade".equals(root.optString("app",null))&&root.opt("entries") instanceof JSONArray;
                }catch(Exception ignored){}
                if(!valid){lanWrite(socket,400,"不是有效的童年游戏厅备份");return;}
                host.js("Arcade.restoreFromPhone("+JSONObject.quote(text)+")");
                lanWriteBytes(socket,200,"text/html; charset=utf-8",RESTORE_DONE.getBytes("UTF-8"),null);
                return;
            }
            lanWrite(socket,404,"未找到");
        }catch(Throwable ignored){
        }finally{try{socket.close();}catch(IOException ignored){}}
    }
    private File lanBuildBackup()throws Exception{
        JSONArray entries=new JSONArray();
        File[] files=host.storeDir().listFiles();
        if(files!=null)for(File file:files){
            String name=file.getName();
            if(!file.isFile()||!name.endsWith(".json"))continue;
            String key=name.substring(0,name.length()-5);
            if(!(key.equals("prefs")||key.startsWith("battery:")||key.matches("slot:[a-f0-9]{64}:(?:[0-3]|auto)")))continue;
            try{
                String text=new String(readAll(new FileInputStream(file),32*1024*1024),"UTF-8");
                entries.put(new JSONObject().put("key",key).put("value",new JSONObject(text)));
            }catch(Exception ignored){}
        }
        JSONObject root=new JSONObject().put("schema",1).put("app","childhood-arcade").put("created",System.currentTimeMillis()).put("entries",entries);
        File target=File.createTempFile("arcade-backup-",".json",host.cacheDir());
        lanTempFile=target;
        FileOutputStream out=new FileOutputStream(target);
        try{out.write(root.toString().getBytes("UTF-8"));}finally{out.close();}
        return target;
    }
    private static void lanWrite(Socket socket,int code,String text){try{lanWriteBytes(socket,code,"text/plain; charset=utf-8",text.getBytes("UTF-8"),null);}catch(IOException ignored){}}
    private static void lanWriteBytes(Socket socket,int code,String type,byte[] body,String filename)throws IOException{
        OutputStream out=socket.getOutputStream();
        String status=code==200?"OK":code==400?"Bad Request":code==403?"Forbidden":code==404?"Not Found":code==405?"Method Not Allowed":code==409?"Conflict":code==410?"Gone":code==413?"Payload Too Large":"Internal Server Error";
        StringBuilder head=new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        if(filename!=null)head.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(body);out.flush();
    }
    private static final String PAD_PAGE="<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        +"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">"
        +"<title>童年游戏厅手柄</title><style>"
        +"*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
        +"html,body{margin:0;height:100%;background:#0d0d10;color:#f3efe3;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;overflow:hidden;touch-action:none}"
        +"body{display:flex;flex-direction:column;justify-content:space-between;padding:12px;gap:8px;height:100vh}"
        +"header{text-align:center;font-size:16px;letter-spacing:2px;color:#f7c948}"
        +"header span{display:block;margin-top:4px;font-size:11px;letter-spacing:0;color:#8f8f9d}"
        +".row{display:flex;justify-content:space-between;align-items:center;gap:10px}"
        +"#lat{flex:1;min-width:52px;font-size:11px;color:#8f8f9d}"
        +".players{display:flex;gap:8px}"
        +".players .btn{width:56px;height:36px;font-size:13px;border-radius:18px}"
        +"#burst,#pause{width:56px;height:36px;font-size:12px;border-radius:18px}"
        +".controls{justify-content:space-around;padding:0 4px}"
        +".pad,.face{display:grid;grid-template-columns:repeat(3,min(62px,14.5vw));grid-template-rows:repeat(3,min(62px,14.5vw));gap:6px;margin:0 auto}"
        +".joy{position:relative;width:min(112px,30vw);height:min(112px,30vw);border-radius:50%;background:#1a1a21;border:2px solid #3f3f4d;touch-action:none;user-select:none;-webkit-user-select:none;flex:0 0 auto}"
        +".knob{position:absolute;left:50%;top:50%;width:46%;height:46%;margin:-23% 0 0 -23%;border-radius:50%;background:#f7c948;opacity:.85;pointer-events:none}"
        +".btn{display:flex;align-items:center;justify-content:center;background:#23232b;border:1px solid #3f3f4d;border-radius:16px;color:#f3efe3;font-size:20px;font-weight:700;touch-action:none;user-select:none;-webkit-user-select:none;padding:0}"
        +".btn.on{background:#f7c948;border-color:#f7c948;color:#141410}"
        +".shoulder{flex:1;height:52px;font-size:16px;border-radius:14px}"
        +".meta{justify-content:center;gap:28px}"
        +".meta .btn{width:108px;height:48px;font-size:14px;border-radius:24px}"
        +"#up{grid-area:1/2}#left{grid-area:2/1}#right{grid-area:2/3}#down{grid-area:3/2}"
        +"#x{grid-area:1/2}#y{grid-area:2/1}#a{grid-area:2/3}#b{grid-area:3/2}"
        +"</style></head><body>"
        +"<header>已连接童年游戏厅<span><a id='leave' style='color:#f7c948'>离开</a> · 按住按钮控制游戏 · 请保持屏幕常亮</span></header>"
        +"<div class='row topbar'><span id='lat'>连接中…</span>"
        +"<div class='players'><button class='btn pbtn on' data-p='1'>1P</button><button class='btn pbtn' data-p='2'>2P</button></div>"
        +"<button class='btn' id='burst'>连发关</button><button class='btn' id='pause'>暂停</button></div>"
        +"<div class='row controls'><div class='pad'>"
        +"<button class='btn' id='up' data-b='4'>▲</button>"
        +"<button class='btn' id='left' data-b='6'>◀</button>"
        +"<button class='btn' id='right' data-b='7'>▶</button>"
        +"<button class='btn' id='down' data-b='5'>▼</button>"
        +"</div><div class='joy' id='joy'><div class='knob' id='knob'></div></div></div>"
        +"<div class='row'>"
        +"<button class='btn shoulder' data-b='10'>L</button>"
        +"<button class='btn shoulder' data-b='11'>R</button>"
        +"</div>"
        +"<div class='row'><div class='face'>"
        +"<button class='btn' id='x' data-b='8'>X</button>"
        +"<button class='btn' id='y' data-b='9'>Y</button>"
        +"<button class='btn' id='a' data-b='0'>A</button>"
        +"<button class='btn' id='b' data-b='1'>B</button>"
        +"</div></div>"
        +"<div class='row meta'>"
        +"<button class='btn' data-b='2'>SELECT</button>"
        +"<button class='btn' data-b='3'>START</button>"
        +"</div>"
        +"<script>(function(){var T='__TOKEN__',C='__CLIENT__',P=0,ready=false,active={},burst=false,burstTimers={},offline=false,wanted=1;"
        +"try{var saved=sessionStorage.getItem('padClient');if(saved&&/^[a-z0-9]{6,16}$/.test(saved))C=saved;else sessionStorage.setItem('padClient',C);}catch(err){}"
        +"var nodes=document.querySelectorAll('[data-b]'),pnodes=document.querySelectorAll('[data-p]'),lat=document.getElementById('lat'),burstBtn=document.getElementById('burst'),pauseBtn=document.getElementById('pause'),leaveBtn=document.getElementById('leave'),left=false;"
        +"try{if(localStorage.getItem('padPlayer')==='2')wanted=2;}catch(err){}P=wanted;"
        +"function status(text,fail){lat.textContent=text;lat.style.color=fail?'#ff6b6b':'#8f8f9d';}"
        +"function vibrate(){try{if(navigator.vibrate)navigator.vibrate(15);}catch(err){}}"
        +"function highlight(){for(var i=0;i<pnodes.length;i++)pnodes[i].classList.toggle('on',Number(pnodes[i].getAttribute('data-p'))===P);}"
        +"function claim(prefer){prefer=Number(prefer)||wanted;return fetch('/claim/'+T+'?c='+C+'&p='+prefer,{cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('http');return r.json();}).then(function(data){var slot=Number(data&&data.slot)||0;if(!slot){P=0;ready=false;status('两个手柄位置都已占用',true);highlight();throw new Error('full');}P=slot;wanted=slot;ready=true;offline=false;try{localStorage.setItem('padPlayer',''+P);}catch(err){}highlight();status('已连接 · '+(P===1?'1P':'2P'),false);return P;}).catch(function(err){if(err.message!=='full'){offline=true;status('已断开',true);}throw err;});}"
        +"function send(b,d){if(!ready||offline)return;var t0=performance.now();fetch('/input/'+T+'?c='+C+'&p='+P+'&b='+b+'&d='+d,{cache:'no-store'}).then(function(r){if(r.status===409){P=0;ready=false;releaseAll();status('两个手柄位置都已占用',true);highlight();throw new Error('full');}if(!r.ok)throw new Error('http');offline=false;status('延迟 '+Math.round(performance.now()-t0)+' ms · '+(P===1?'1P':'2P'),false);}).catch(function(){if(!ready)return;offline=true;status('已断开',true);releaseAll();});}"
        +"function startBurst(b){if(burstTimers[b])return;var d=1;send(b,1);burstTimers[b]=setInterval(function(){d=d?0:1;send(b,d);},90);}"
        +"function stopBurst(b,keep){if(burstTimers[b]){clearInterval(burstTimers[b]);delete burstTimers[b];}send(b,keep?1:0);}"
        +"function padDown(b){b=''+b;if(active[b])return;active[b]=1;if(burst&&(b==='0'||b==='1'||b==='8'||b==='9'))startBurst(b);else send(b,1);}"
        +"function padUp(b){b=''+b;if(!active[b])return;active[b]=0;if(burstTimers[b])stopBurst(b,false);else send(b,0);}"
        +"function down(el,e){if(e)e.preventDefault();var b=el.getAttribute('data-b');if(active[b])return;el.classList.add('on');vibrate();padDown(b);}"
        +"function up(el,e){if(e)e.preventDefault();var b=el.getAttribute('data-b');if(!active[b])return;el.classList.remove('on');padUp(b);}"
        +"function applyBurst(on){burst=on;burstBtn.textContent=on?'连发开':'连发关';burstBtn.classList.toggle('on',on);var keys=['0','1','8','9'];for(var i=0;i<keys.length;i++){var b=keys[i];if(active[b]){if(on)startBurst(b);else stopBurst(b,true);}}}"
        +"burstBtn.addEventListener('click',function(){vibrate();applyBurst(!burst);});"
        +"function command(c){return fetch('/command/'+T+'?c='+C+'&cmd='+c,{cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('http');});}"
        +"function leavePad(){if(left)return;left=true;releaseAll();command('leave').catch(function(){});ready=false;status('已离开，可以关闭页面',false);}"
        +"if(pauseBtn)pauseBtn.addEventListener('click',function(){vibrate();command('pause').catch(function(){});});"
        +"if(leaveBtn)leaveBtn.addEventListener('click',function(e){if(e)e.preventDefault();vibrate();leavePad();});"
        +"for(var i=0;i<nodes.length;i++){(function(el){"
        +"if(window.PointerEvent){"
        +"el.addEventListener('pointerdown',function(e){down(el,e);});"
        +"el.addEventListener('pointerup',function(e){up(el,e);});"
        +"el.addEventListener('pointercancel',function(e){up(el,e);});"
        +"el.addEventListener('pointerleave',function(e){up(el,e);});"
        +"}else{"
        +"el.addEventListener('touchstart',function(e){down(el,e);},{passive:false});"
        +"el.addEventListener('touchend',function(e){up(el,e);},{passive:false});"
        +"el.addEventListener('touchcancel',function(e){up(el,e);},{passive:false});"
        +"}})(nodes[i]);}"
        +"for(var j=0;j<pnodes.length;j++){(function(el){"
        +"el.addEventListener('click',function(){vibrate();if(!ready){claim(Number(el.getAttribute('data-p'))).catch(function(){});return;}releaseAll();claim(Number(el.getAttribute('data-p'))).catch(function(){});});"
        +"})(pnodes[j]);}"
        +"var joy=document.getElementById('joy'),knob=document.getElementById('knob'),joyId=null,joyState={left:0,right:0,up:0,down:0},JOY={left:6,right:7,up:4,down:5};"
        +"function joySet(name,on){on=on?1:0;if(joyState[name]===on)return;joyState[name]=on;if(on)padDown(JOY[name]);else padUp(JOY[name]);}"
        +"function joyReset(){joySet('left',0);joySet('right',0);joySet('up',0);joySet('down',0);knob.style.transform='';joyId=null;}"
        +"function joyMove(x,y){var rect=joy.getBoundingClientRect(),cx=rect.left+rect.width/2,cy=rect.top+rect.height/2,rad=rect.width/2,dx=x-cx,dy=y-cy,dist=Math.sqrt(dx*dx+dy*dy),limit=rad*0.55;if(dist>limit){dx=dx/dist*limit;dy=dy/dist*limit;}knob.style.transform='translate('+dx+'px,'+dy+'px)';var threshold=rad*0.28;joySet('right',dx>threshold);joySet('left',dx<-threshold);joySet('down',dy>threshold);joySet('up',dy<-threshold);}"
        +"if(window.PointerEvent){"
        +"joy.addEventListener('pointerdown',function(e){e.preventDefault();if(joyId!==null)return;joyId=e.pointerId;try{joy.setPointerCapture(e.pointerId);}catch(err){}joyMove(e.clientX,e.clientY);});"
        +"joy.addEventListener('pointermove',function(e){if(joyId!==e.pointerId)return;e.preventDefault();joyMove(e.clientX,e.clientY);});"
        +"joy.addEventListener('pointerup',function(e){if(joyId===e.pointerId)joyReset();});"
        +"joy.addEventListener('pointercancel',function(e){if(joyId===e.pointerId)joyReset();});"
        +"}else{"
        +"joy.addEventListener('touchstart',function(e){e.preventDefault();var t=e.changedTouches[0];if(t){joyId=t.identifier;joyMove(t.clientX,t.clientY);}},{passive:false});"
        +"joy.addEventListener('touchmove',function(e){e.preventDefault();var t=e.changedTouches[0];if(t)joyMove(t.clientX,t.clientY);},{passive:false});"
        +"joy.addEventListener('touchend',function(e){e.preventDefault();joyReset();},{passive:false});"
        +"joy.addEventListener('touchcancel',function(e){e.preventDefault();joyReset();},{passive:false});"
        +"}"
        +"function releaseAll(){for(var i=0;i<nodes.length;i++)up(nodes[i],null);joyReset();}"
        +"window.addEventListener('blur',releaseAll);"
        +"document.addEventListener('visibilitychange',function(){if(document.hidden)releaseAll();else if(ready)claim(P).catch(function(){});});"
        +"window.addEventListener('pagehide',releaseAll);"
        +"claim(wanted).catch(function(){});"
        +"setInterval(function(){if(left)return;if(!ready){claim(wanted).catch(function(){});return;}fetch('/ping/'+T+'?c='+C,{cache:'no-store'}).then(function(r){if(r.status===410){ready=false;releaseAll();status('正在重新连接…',true);claim(wanted).catch(function(){});return;}if(!r.ok)throw new Error('http');if(offline){offline=false;status('已连接 · '+(P===1?'1P':'2P'),false);}}).catch(function(){if(!ready)return;offline=true;status('已断开',true);releaseAll();});},2000);"
        +"})();</script></body></html>";
    private static final String RESTORE_PAGE="<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        +"<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>"
        +"<title>恢复游戏厅备份</title><style>"
        +"*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
        +"html,body{margin:0;min-height:100%;background:#0d0d10;color:#f3efe3;font-family:-apple-system,'Segoe UI',Roboto,sans-serif}"
        +"body{padding:26px 18px;display:flex;flex-direction:column;gap:18px}"
        +"h1{margin:0;font-size:19px;letter-spacing:1px;color:#f7c948;text-align:center}"
        +"p{margin:0;font-size:13px;line-height:1.7;color:#9a9aa8;text-align:center}"
        +"label{display:flex;align-items:center;justify-content:center;min-height:76px;border:1px dashed #4a4a58;border-radius:18px;background:#18181f;font-size:16px;color:#d8d4c8;padding:14px}"
        +"input[type=file]{position:absolute;opacity:0;width:1px;height:1px}"
        +"#msg{min-height:24px;font-size:14px;line-height:1.6;text-align:center;color:#8f8f9d;word-break:break-all}"
        +"</style></head><body>"
        +"<h1>恢复游戏厅备份</h1>"
        +"<p>只接受童年游戏厅导出的 JSON 备份。选择文件后会发送到电视，电视上会弹窗确认后才写入。</p>"
        +"<label for='file'>选择备份文件<input id='file' type='file' accept='.json,application/json'></label>"
        +"<div id='msg'></div>"
        +"<script>(function(){var T='__TOKEN__',input=document.getElementById('file'),msg=document.getElementById('msg');"
        +"function show(text,ok){msg.textContent=text;msg.style.color=ok?'#7bd88f':'#ff6b6b';}"
        +"input.addEventListener('change',function(){"
        +"var file=input.files&&input.files[0];if(!file)return;"
        +"if(file.size>64*1024*1024){show('文件超过 64MB，无法发送',false);return;}"
        +"show('正在读取备份…',true);"
        +"var reader=new FileReader();"
        +"reader.onerror=function(){show('读取文件失败，请重试',false);};"
        +"reader.onload=function(){"
        +"var text=String(reader.result||'');"
        +"try{var data=JSON.parse(text);if(!data||data.schema!==1||data.app!=='childhood-arcade'||!Array.isArray(data.entries))throw new Error('bad');}"
        +"catch(err){show('不是童年游戏厅导出的备份文件',false);return;}"
        +"show('正在发送到电视…',true);"
        +"fetch('/restore/'+T,{method:'POST',headers:{'Content-Type':'application/json'},body:text})"
        +".then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();})"
        +".then(function(){show('已发送到电视，请在电视上确认',true);input.value='';})"
        +".catch(function(){show('发送失败，请确认手机与电视在同一网络',false);});"
        +"};"
        +"reader.readAsText(file);"
        +"});"
        +"})();</script></body></html>";
    private static final String RESTORE_DONE="<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        +"<meta name='viewport' content='width=device-width,initial-scale=1'><title>已发送</title></head>"
        +"<body style='margin:0;min-height:100vh;display:flex;flex-direction:column;justify-content:center;padding:30px 24px;background:#0d0d10;color:#f3efe3;font-family:sans-serif;text-align:center'>"
        +"<p style='margin:0;color:#f7c948;font-size:21px'>已发送到电视</p>"
        +"<p style='margin:12px 0 0;color:#9a9aa8;font-size:14px;line-height:1.7'>请在电视上确认恢复</p>"
        +"</body></html>";
}
