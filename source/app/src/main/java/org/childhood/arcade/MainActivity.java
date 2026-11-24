package org.childhood.arcade;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private WebView web;
    private NativeNes nativeNes;
    private ValueCallback<Uri[]> chooser;
    private volatile String pendingBackup;
    private boolean pageActive=true;
    private InputManager inputs;
    private final HashMap<Integer, boolean[]> axes = new HashMap<Integer, boolean[]>();
    private final HashMap<Integer, Integer> players = new HashMap<Integer, Integer>();
    private final HashMap<Integer, PadState> padStates = new HashMap<Integer, PadState>();
    private GamepadProfile gamepads;
    private volatile boolean gamepadMonitor=false;
    private volatile String captureKey=null;
    private volatile int captureAction=-1;
    private final android.os.Handler uiHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable captureTimeout=new Runnable(){public void run(){if(captureKey!=null){captureKey=null;captureAction=-1;js("Arcade.gamepadCaptureCanceled()");}}};
    private File storeDir;
    private final Object storeLock = new Object();
    private static final int PICK_FILE=10, EXPORT_FILE=11, PICK_FOLDER=12, PICK_BACKUP=13, MAX_LIBRARY=2000, MAX_SCAN_DEPTH=4;
    private volatile boolean scanning=false;
    private volatile boolean importCancel=false;
    private android.content.SharedPreferences playerPrefs;
    private AppUpdates updates;
    private LanServer lan;
    private LegacyFiles legacyFiles;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        storeDir=new File(getFilesDir(),"store");
        playerPrefs=getSharedPreferences("input_players",0);
        File recovery=new File(getFilesDir(),"store.previous");
        if(!storeDir.exists()&&recovery.exists())recovery.renameTo(storeDir);
        storeDir.mkdirs();
        updates=new AppUpdates(this);
        legacyFiles=new LegacyFiles(this,new LegacyFiles.Host(){
            public void legacyImportFolder(File dir){startLegacyImport(dir);}
            public void legacyExportBackup(File dir){finishLegacyExport(dir);}
            public void legacyRestoreBackup(File file){readLegacyBackup(file);}
            public void legacyCancelled(int mode){
                if(mode==LegacyFiles.FOLDER)js("Arcade.libraryResult(0,0,'已取消')");
                else if(mode==LegacyFiles.EXPORT){pendingBackup=null;js("Arcade.backupResult(false)");}
                else Toast.makeText(MainActivity.this,"没有选择备份文件",Toast.LENGTH_SHORT).show();
            }
        });
        WebView.setWebContentsDebuggingEnabled(false);
        getSharedPreferences("deployment",0).edit().putBoolean("page_loaded",false).remove("health").remove("health_at").apply();
        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        web.setBackgroundColor(0x00000000);
        web.addJavascriptInterface(new Bridge(),"NativeArcade");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return !(url.startsWith("file:///android_asset/")||url.startsWith("https://appassets.androidplatform.net/assets/"));}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                String address=request.getUrl().toString();
                if(address.startsWith("https://appassets.androidplatform.net/assets/")){
                    String asset=request.getUrl().getPath().substring("/assets/".length());
                    if(asset.contains("..")||asset.startsWith("/"))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                    String mime=asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".html")?"text/html":asset.endsWith(".wasm")?"application/wasm":asset.endsWith(".png")?"image/png":asset.endsWith(".ttf")?"font/ttf":"application/octet-stream";
                    try{return new WebResourceResponse(mime,(mime.startsWith("text/")?"UTF-8":null),getAssets().open(asset));}catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",null,new ByteArrayInputStream(new byte[0]));}
                }
                if(address.startsWith("https://appassets.androidplatform.net/library-cover/")){
                    String name=request.getUrl().getPath().substring("/library-cover/".length());
                    if(name.matches("[a-f0-9]{64}\\.(png|jpg|jpeg|webp)")){
                        File file=new File(new File(getFilesDir(),"library/covers"),name);
                        if(file.isFile()){
                            String mime=name.endsWith(".png")?"image/png":name.endsWith(".webp")?"image/webp":"image/jpeg";
                            HashMap<String,String> headers=new HashMap<String,String>();
                            headers.put("Cache-Control","public, max-age=31536000, immutable");
                            try{return new WebResourceResponse(mime,null,200,"OK",headers,new FileInputStream(file));}catch(IOException ignored){}
                        }
                    }
                    return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",null,new ByteArrayInputStream(new byte[0]));
                }
                String scheme=request.getUrl().getScheme();
                if("http".equals(scheme)||"https".equals(scheme))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onPageFinished(WebView v,String url){hideSystemUI();if(url.equals("https://appassets.androidplatform.net/assets/index.html"))getSharedPreferences("deployment",0).edit().putBoolean("page_loaded",true).apply();resyncPlayers();}
            @Override public boolean onRenderProcessGone(WebView v,android.webkit.RenderProcessGoneDetail detail){if(v!=web)return true;android.util.Log.w("ArcadeWeb","渲染进程已退出，正在重建页面");uiHandler.post(new Runnable(){public void run(){if(isFinishing())return;try{recreate();}catch(Throwable ignored){finish();}}});return true;}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(chooser!=null)chooser.onReceiveValue(null);
                chooser=callback;
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                try{startActivityForResult(intent,PICK_FILE);}catch(ActivityNotFoundException e){chooser.onReceiveValue(null);chooser=null;Toast.makeText(MainActivity.this,"电视没有可用的文件选择器，可通过电脑部署游戏文件",Toast.LENGTH_LONG).show();}
                return true;
            }
        });
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff100f0c);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);nativeNes=new NativeNes(this,root);
        lan=new LanServer(new LanServer.Host(){
            public void js(String code){MainActivity.this.js(code);}
            public void button(String source,int slot,int button,boolean down){if(nativeNes!=null&&nativeNes.isRunning())nativeNes.buttonFrom(source,slot,coreAction(button),down);}
            public void release(String source){if(nativeNes!=null)try{nativeNes.releaseSource(source);}catch(Throwable ignored){}}
            public File storeDir(){return storeDir;}
            public File cacheDir(){return getCacheDir();}
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        web.post(new Runnable(){public void run(){if(web==null)return;
            if(pageActive)web.evaluateJavascript("(function(){var a=window.Arcade;return {title:document.title,ready:!!a,state:a?a.getState():null,catalogCount:window.GAME_CATALOG?window.GAME_CATALOG.length:0,visible:document.visibilityState};})()",new ValueCallback<String>(){public void onReceiveValue(String value){getSharedPreferences("deployment",0).edit().putString("health",value).putLong("health_at",android.os.SystemClock.elapsedRealtime()).apply();}});
            web.postDelayed(this,2000);
        }});
        inputs=(InputManager)getSystemService(INPUT_SERVICE);gamepads=new GamepadProfile(this);inputs.registerInputDeviceListener(this,null);
    }
    private void hideSystemUI(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    private void js(final String value){if(web!=null)web.post(new Runnable(){public void run(){if(web!=null)web.evaluateJavascript("if(window.Arcade){"+value+"}",null);}});}
    /** WebView reloads (for example after restoring a phone backup) drop JS state; replay the live pad slots. */
    private void resyncPlayers(){for(Map.Entry<Integer,Integer> entry:players.entrySet())js("Arcade.nativeRegister("+entry.getKey()+","+entry.getValue()+")");}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)hideSystemUI();}
    @Override protected void onResume(){super.onResume();pageActive=true;}
    @Override protected void onPause(){pageActive=false;if(nativeNes!=null)try{nativeNes.pause();}catch(Throwable ignored){}super.onPause();js("Arcade.lifecyclePause()");}
    @Override protected void onDestroy(){if(lan!=null)lan.stop();if(nativeNes!=null){try{nativeNes.close();}catch(RuntimeException e){android.util.Log.w("ArcadeCore","退出时游戏线程未能及时停止",e);}}if(inputs!=null)inputs.unregisterInputDeviceListener(this);uiHandler.removeCallbacks(captureTimeout);if(web!=null){web.removeJavascriptInterface("NativeArcade");web.destroy();web=null;}super.onDestroy();}
    private boolean gamepad(KeyEvent event){
        InputDevice device=InputDevice.getDevice(event.getDeviceId());
        int sources=device==null?event.getSource():device.getSources();
        return (sources&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(sources&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;
    }
    private String padFingerprint(int id){
        InputDevice device=InputDevice.getDevice(id);
        if(device==null)return null;
        String descriptor=device.getDescriptor();
        return gamepads.key(device)+":"+(descriptor==null?"":descriptor);
    }
    /** Prefer the slot this controller used last time; otherwise take the first free one. */
    private int player(int id){
        Integer existing=players.get(id);if(existing!=null)return existing;
        String fingerprint=padFingerprint(id);
        int preferred=0;
        try{if(fingerprint!=null)preferred=playerPrefs.getInt(fingerprint,0);}catch(Throwable ignored){}
        int value=preferred>0&&!players.containsValue(preferred)?preferred:(!players.containsValue(1)?1:!players.containsValue(2)?2:0);
        players.put(id,value);
        try{if(value>0&&fingerprint!=null&&(preferred==0||preferred==value))playerPrefs.edit().putInt(fingerprint,value).apply();}catch(Throwable ignored){}
        js("Arcade.nativeRegister("+id+","+value+")");return value;
    }
    private static boolean volumeKey(int code){return code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_VOLUME_MUTE;}
    private static boolean padKey(int code){return (code>=KeyEvent.KEYCODE_BUTTON_A&&code<=KeyEvent.KEYCODE_BUTTON_MODE)||(code>=188&&code<=206)||(code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_CENTER)||code==KeyEvent.KEYCODE_ENTER;}
    private GamepadProfile.Profile profile(int id){return gamepads.resolve(InputDevice.getDevice(id));}
    private PadState state(int id){PadState s=padStates.get(id);if(s==null){s=new PadState();padStates.put(id,s);}return s;}
    private void startCapture(String key,int action){clearCapture(false);captureKey=key;captureAction=action;uiHandler.removeCallbacks(captureTimeout);uiHandler.postDelayed(captureTimeout,15000);}
    private void clearCapture(boolean notify){captureKey=null;captureAction=-1;uiHandler.removeCallbacks(captureTimeout);if(notify)js("Arcade.gamepadCaptureCanceled()");}
    /** Two face button systems keep the familiar Y=A, X=B fallback. */
    private int coreAction(int action){
        boolean two=nativeNes==null||nativeNes.isGba()||(!nativeNes.isArcade()&&!nativeNes.isMd()&&!nativeNes.isSfc());
        if(two){if(action==GamepadProfile.X)return GamepadProfile.B;if(action==GamepadProfile.Y)return GamepadProfile.A;}
        return action;
    }
    private void sendAction(int id,int action,boolean down,int rawCode){
        trackPadCombo(action,down);
        int p=player(id);
        if(nativeNes!=null&&nativeNes.isRunning()){if(p>0)nativeNes.buttonFrom("pad:"+id,p,coreAction(action),down);}
        else if(p>0)js("Arcade.nativeKey("+action+","+down+","+id+",true,false,"+rawCode+")");
    }
    private volatile long comboSelectAt=0,comboStartAt=0;
    private boolean comboFired=false;
    /** Select+Start held together opens the pause menu, so a pad-only player never needs the TV remote. */
    private void trackPadCombo(int action,boolean down){
        long now=android.os.SystemClock.elapsedRealtime();
        if(action==GamepadProfile.SELECT)comboSelectAt=down?now:0;
        else if(action==GamepadProfile.START)comboStartAt=down?now:0;
        else return;
        if(!down){if(comboSelectAt==0&&comboStartAt==0)comboFired=false;return;}
        if(comboSelectAt>0&&comboStartAt>0&&!comboFired){
            uiHandler.postDelayed(new Runnable(){public void run(){
                if(comboSelectAt>0&&comboStartAt>0&&!comboFired){
                    comboFired=true;
                    if(nativeNes!=null&&nativeNes.isRunning())try{nativeNes.pause();}catch(Throwable ignored){}
                    js("Arcade.togglePauseMenu()");
                }
            }},1000);
        }
    }
    private void syncAction(int id,int action,int rawCode){
        PadState st=state(id);
        boolean want=st.keys[action]>0||st.axes[action];
        if(want!=st.sent[action]){st.sent[action]=want;sendAction(id,action,want,rawCode);}
    }
    private void keyAction(int id,int action,boolean down,int rawCode){
        PadState st=state(id);
        if(down){if(st.keys[action]<2)st.keys[action]++;}else if(st.keys[action]>0)st.keys[action]--;
        syncAction(id,action,rawCode);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        int code=e.getKeyCode();
        if(volumeKey(code))return super.dispatchKeyEvent(e);
        int id=e.getDeviceId();
        boolean pad=gamepad(e);
        boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        boolean repeat=e.getRepeatCount()>0;
        if(captureKey!=null&&pad&&down&&!repeat){
            if(code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_MENU||code==KeyEvent.KEYCODE_BUTTON_MODE){clearCapture(true);return true;}
            InputDevice device=InputDevice.getDevice(id);
            String key=device==null?null:gamepads.key(device);
            if(key!=null&&key.equals(captureKey)){
                int action=captureAction;
                boolean ok=gamepads.bind(key,action,code);
                clearCapture(false);
                if(ok)js("Arcade.gamepadCaptured("+action+","+JSONObject.quote(GamepadProfile.keyLabel(code))+")");
                else js("Arcade.gamepadCaptureCanceled()");
                return true;
            }
        }
        int action=-1;
        if(pad){GamepadProfile.Profile profile=profile(id);if(profile!=null)action=profile.actionForKey(code);}
        if(gamepadMonitor&&pad)js("Arcade.gamepadTest("+id+",\"key\","+code+","+down+","+action+","+JSONObject.quote(GamepadProfile.keyLabel(code))+")");
        boolean ui=code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_MENU||code==KeyEvent.KEYCODE_BUTTON_MODE||(!pad&&(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER));
        if(action<0&&!ui){
            if(pad&&padKey(code))return true;
            return super.dispatchKeyEvent(e);
        }
        if(e.getAction()!=KeyEvent.ACTION_DOWN&&e.getAction()!=KeyEvent.ACTION_UP)return true;
        if(repeat)return true;
        if(action>=0){keyAction(id,action,down,code);return true;}
        js("Arcade.nativeKey(-1,"+down+","+id+","+pad+","+repeat+","+code+")");
        return true;
    }
    // WebView consumes joystick motion before Activity.onGenericMotionEvent.
    // Intercept at dispatch so hats/sticks follow the same native path as buttons.
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){
        int source=e.getSource();
        boolean joystick=(source&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK||(source&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD;
        if(!joystick||e.getAction()!=MotionEvent.ACTION_MOVE)return super.dispatchGenericMotionEvent(e);
        int id=e.getDeviceId();
        GamepadProfile.Profile profile=profile(id);
        PadState st=state(id);
        float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y);
        long now=android.os.SystemClock.elapsedRealtime();
        if(st.sampleStart==0)st.sampleStart=now;
        if(now-st.sampleStart<700){
            if(!Float.isNaN(x))st.maxX=Math.max(st.maxX,Math.abs(x));
            if(!Float.isNaN(y))st.maxY=Math.max(st.maxY,Math.abs(y));
        }else if(st.sampleStart>0){
            float base=Math.max(st.maxX,st.maxY);
            if(base<=.45f)gamepads.saveDeadzone(profile.deviceKey,Math.min(.8f,Math.max(.2f,base+.12f)));
            st.sampleStart=-1;
        }
        boolean[] old=axes.get(id);if(old==null)old=new boolean[4];
        boolean[] dirs=ControllerDirections.update(e.getAxisValue(MotionEvent.AXIS_HAT_X),e.getAxisValue(MotionEvent.AXIS_HAT_Y),x,y,old,profile.deadzone);
        for(int i=0;i<4;i++){
            int action=GamepadProfile.UP+i;
            if(profile.axisPressed(e,action,old[i]?.55f:.65f))dirs[i]=true;
            if(dirs[i]!=st.axes[action]){st.axes[action]=dirs[i];syncAction(id,action,-1);}
        }
        for(int action=0;action<GamepadProfile.COUNT;action++){
            if(action>=GamepadProfile.UP&&action<=GamepadProfile.RIGHT)continue;
            if(!profile.hasAxis(action))continue;
            boolean pressed=profile.axisPressed(e,action,st.axes[action]?.55f:.65f);
            if(pressed!=st.axes[action]){st.axes[action]=pressed;syncAction(id,action,-1);}
        }
        axes.put(id,dirs);
        if(gamepadMonitor&&(Math.abs(x-st.lastX)>=.3f||Math.abs(y-st.lastY)>=.3f)){
            st.lastX=x;st.lastY=y;
            js("Arcade.gamepadTest("+id+",\"axis\","+(Math.round(x*100)/100f)+","+(Math.round(y*100)/100f)+")");
        }
        return true;
    }
    private void releasePad(int id){
        if(nativeNes!=null)nativeNes.releaseSource("pad:"+id);
        PadState st=padStates.get(id);
        Integer p=players.get(id);
        if(st==null||p==null||p<=0)return;
        for(int action=0;action<GamepadProfile.COUNT;action++)if(st.sent[action]){
            if(nativeNes!=null&&nativeNes.isRunning())nativeNes.buttonFrom("pad:"+id,p,coreAction(action),false);
            else js("Arcade.nativeKey("+action+",false,"+id+",true,false,-1)");
        }
    }
    @Override public void onInputDeviceAdded(int id){releasePad(id);padStates.remove(id);axes.remove(id);players.remove(id);}
    @Override public void onInputDeviceChanged(int id){releasePad(id);PadState old=padStates.remove(id);if(old!=null){old.sampleStart=0;old.maxX=0;old.maxY=0;}}
    @Override public void onInputDeviceRemoved(int id){InputDevice device=InputDevice.getDevice(id);if(captureKey!=null&&(device==null||captureKey.equals(gamepads.key(device))))clearCapture(true);releasePad(id);axes.remove(id);padStates.remove(id);Integer p=players.remove(id);if(p!=null&&p>0&&nativeNes!=null&&nativeNes.isRunning())try{nativeNes.pause();}catch(Throwable ignored){}js("Arcade.nativeDisconnected("+id+")");}
    private static final class PadState {
        final int[] keys=new int[GamepadProfile.COUNT];
        final boolean[] axes=new boolean[GamepadProfile.COUNT];
        final boolean[] sent=new boolean[GamepadProfile.COUNT];
        long sampleStart=0;
        float maxX=0,maxY=0,lastX=2,lastY=2;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_FOLDER){
            if(result!=RESULT_OK||data==null||data.getData()==null){js("Arcade.libraryResult(0,0,'已取消')");return;}
            final Uri tree=data.getData();
            try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            if(scanning){js("Arcade.libraryResult(0,0,'正在导入游戏，请稍候')");return;}
            scanning=true;importCancel=false;
            new Thread(new Runnable(){public void run(){
                int added=0,skipped=0;String error=null;
                try{int[] value=importFolder(tree);added=value[0];skipped=value[1];}
                catch(SecurityException e){error="没有读取这个文件夹的权限，请重新选择";}
                catch(Throwable e){error=e.getMessage()==null||e.getMessage().length()==0?"导入没有完成，请重试":e.getMessage();}
                finally{scanning=false;}
                js("Arcade.libraryResult("+added+","+skipped+","+JSONObject.quote(error==null?"":error)+")");
            }},"ArcadeLibrary").start();
            return;
        }
        if(request==PICK_FILE&&chooser!=null){Uri[] uris=null;if(result==RESULT_OK&&data!=null){if(data.getClipData()!=null){int n=data.getClipData().getItemCount();uris=new Uri[n];for(int i=0;i<n;i++)uris[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)uris=new Uri[]{data.getData()};}chooser.onReceiveValue(uris);chooser=null;}
        if(request==EXPORT_FILE){boolean ok=false;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingBackup!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null){out.write(pendingBackup.getBytes("UTF-8"));ok=true;}}catch(Exception ignored){}}pendingBackup=null;js("Arcade.backupResult("+ok+")");}
        if(request==PICK_BACKUP){
            Uri uri=result==RESULT_OK&&data!=null?data.getData():null;
            if(uri!=null){
                try{byte[] bytes=readAll(getContentResolver().openInputStream(uri),8*1024*1024);restoreBackupText(new String(bytes,"UTF-8"));}
                catch(Exception e){Toast.makeText(this,message(e),Toast.LENGTH_LONG).show();}
            }
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==LegacyFiles.PERMISSION)legacyFiles.permissionResult(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED);
    }
    private File keyFile(File dir,String key)throws IOException {if(key==null||!key.matches("[a-z0-9:_-]{1,160}"))throw new IOException("Invalid key");return new File(dir,key+".json");}
    private byte[] readAll(InputStream in,int max)throws IOException{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Data too large");out.write(buf,0,n);}return out.toByteArray();}finally{in.close();}}
    private void atomicWrite(File f,String value)throws IOException {AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(value.getBytes("UTF-8"));a.finishWrite(out);}catch(IOException e){if(out!=null)a.failWrite(out);throw e;}}
    private void deleteTree(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}file.delete();}
    private static final String[] DOC_COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
    private static final char[] HEX="0123456789abcdef".toCharArray();
    private static final class PackEntry{String title,players,genre,version,group,aliases,cover;}
    private static final class Candidate{String docId,name,relative,ext;long size;PackEntry meta;File source;}
    private static final class Cover{String docId,ext;File file;}
    private static String extension(String name){int i=name.lastIndexOf('.');return i<0?"":name.substring(i+1).toLowerCase(Locale.ROOT);}
    private static String stem(String name){int i=name.lastIndexOf('.');return i<=0?name:name.substring(0,i);}
    private static String plain(String value){return value.replace('\\','/').toLowerCase(Locale.ROOT);}
    private static String clean(String value,int max){if(value==null)return "";value=value.trim();return value.length()>max?value.substring(0,max):value;}
    private static long sizeLimit(String ext){return "nes".equals(ext)?8L*1024*1024:"gba".equals(ext)?64L*1024*1024:"md".equals(ext)||"sfc".equals(ext)?16L*1024*1024:"zip".equals(ext)?256L*1024*1024:0;}
    private static final Pattern NAME_TAG=Pattern.compile("\\(([^)]*)\\)|\\[([^\\]]*)\\]");
    private static final Pattern NAME_REV=Pattern.compile("(?i)\\brev(?:ision)?\\s*([a-z0-9]+)");
    private static final Pattern NAME_SHORT_REV=Pattern.compile("(?i)^v(\\d+(?:\\.\\d+)?)$");
    private static final class NameInfo{String title="";String version="原版";}
    private static NameInfo parseName(String raw){
        NameInfo info=new NameInfo();
        if(raw==null||raw.length()==0)return info;
        String base=raw.replace('_',' ');
        Matcher m=NAME_TAG.matcher(base);
        StringBuffer sb=new StringBuffer();
        ArrayList<String> tags=new ArrayList<String>();
        while(m.find()){tags.add(m.group(1)!=null?m.group(1):m.group(2));m.appendReplacement(sb," ");}
        m.appendTail(sb);
        String title=sb.toString().replaceAll("\\s+"," ").trim();
        String region=null,revision=null;
        ArrayList<String> flags=new ArrayList<String>();
        for(int i=0;i<tags.size();i++){
            String low=tags.get(i)==null?"":tags.get(i).toLowerCase(Locale.ROOT);
            if(region==null)region=regionOf(low);
            Matcher rev=NAME_REV.matcher(low);
            if(revision==null&&rev.find())revision="Rev "+rev.group(1).toUpperCase(Locale.ROOT);
            else if(revision==null&&NAME_SHORT_REV.matcher(low).matches())revision="v"+low.substring(1);
            addFlag(flags,flagOf(low));
        }
        chineseFlags(raw,flags);
        ArrayList<String> parts=new ArrayList<String>();
        if(region!=null)parts.add(region);
        if(revision!=null)parts.add(revision);
        parts.addAll(flags);
        if(!parts.isEmpty()){
            StringBuilder value=new StringBuilder();
            for(int i=0;i<parts.size();i++){if(i>0)value.append(" · ");value.append(parts.get(i));}
            info.version=value.toString();
        }
        title=title.replaceAll("\\s*(无敌版|无限版|修改版|汉化版|中文版|金手指版|加强版|改版)\\s*$","").trim();
        info.title=title.length()>0?title:raw;
        return info;
    }
    private static void addFlag(ArrayList<String> flags,String flag){if(flag!=null&&!flags.contains(flag))flags.add(flag);}
    private static void chineseFlags(String raw,ArrayList<String> flags){
        if(raw.contains("无敌"))addFlag(flags,"无敌版");
        if(raw.contains("无限"))addFlag(flags,"无限版");
        if(raw.contains("汉化"))addFlag(flags,"汉化版");
        else if(raw.contains("中文"))addFlag(flags,"中文版");
        if(raw.contains("金手指"))addFlag(flags,"金手指版");
        if(raw.contains("加强"))addFlag(flags,"加强版");
        if(raw.contains("修改"))addFlag(flags,"修改版");
        if(raw.contains("改版"))addFlag(flags,"改版");
    }
    private static String regionOf(String token){
        if(token.length()==0)return null;
        if(token.equals("u")||token.contains("usa"))return "美版";
        if(token.equals("j")||token.contains("japan"))return "日版";
        if(token.equals("e")||token.contains("europe")||token.contains("pal"))return "欧版";
        if(token.equals("w")||token.contains("world"))return "世界版";
        if(token.contains("china"))return "中国版";
        if(token.contains("hong kong"))return "港版";
        if(token.contains("taiwan"))return "台版";
        if(token.equals("k")||token.contains("korea"))return "韩版";
        if(token.contains("asia"))return "亚洲版";
        if(token.contains("brazil"))return "巴西版";
        if(token.contains("australia"))return "澳洲版";
        if(token.contains("germany"))return "德版";
        if(token.contains("france"))return "法版";
        if(token.contains("spain"))return "西版";
        if(token.contains("italy"))return "意版";
        if(token.contains("russia"))return "俄版";
        if(token.contains("canada"))return "加版";
        return null;
    }
    private static String flagOf(String token){
        if(token.contains("translation")||token.contains("translated")||token.contains("chinese")||token.startsWith("t-chi")||token.startsWith("t+chi"))return "汉化版";
        if(token.startsWith("t-eng")||token.startsWith("t+eng"))return "英译版";
        if(token.contains("hack"))return "修改版";
        if(token.contains("pirate")||token.contains("bootleg"))return "盗版";
        if(token.contains("unl")||token.contains("unlicensed"))return "非授权版";
        if(token.contains("beta"))return "测试版";
        if(token.contains("proto"))return "原型版";
        if(token.contains("demo"))return "演示版";
        if(token.contains("aftermarket"))return "同人版";
        if(token.contains("virtual console"))return "VC版";
        return null;
    }
    private static String groupKey(String system,String title){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] bytes=digest.digest((system+"|"+title.toLowerCase(Locale.ROOT)).getBytes("UTF-8"));
            StringBuilder out=new StringBuilder(system).append('-');
            for(int i=0;i<8;i++){int v=bytes[i]&255;out.append(HEX[v>>4]).append(HEX[v&15]);}
            return out.toString();
        }catch(Exception e){return null;}
    }
    private Uri docUri(Uri tree,String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
    private byte[] readDocument(Uri tree,String id,int max)throws Exception{
        InputStream in=getContentResolver().openInputStream(docUri(tree,id));
        if(in==null)throw new IOException("无法读取文件");
        return readAll(in,max);
    }
    private HashMap<String,PackEntry> readPack(Uri tree,String rootId,HashMap<String,PackEntry> byName)throws Exception{
        HashMap<String,PackEntry> byPath=new HashMap<String,PackEntry>();
        Cursor c=null;
        try{
            c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId),DOC_COLUMNS,null,null,null);
            if(c==null)return byPath;
            while(c.moveToNext()){
                String id=c.getString(0),name=c.getString(1);
                long size=c.isNull(3)?-1:c.getLong(3);
                if(name==null||!name.equalsIgnoreCase("pack.json")||size>8L*1024*1024)continue;
                String text=new String(readDocument(tree,id,8*1024*1024),"UTF-8");
                if(text.startsWith("\uFEFF"))text=text.substring(1);
                JSONObject root;
                try{root=new JSONObject(text);}catch(Exception e){throw new IOException("pack.json 格式不正确");}
                JSONArray list=root.optJSONArray("games");
                if(list==null)break;
                for(int i=0;i<list.length()&&i<5000;i++){
                    JSONObject item=list.optJSONObject(i);
                    if(item==null)continue;
                    String file=clean(item.optString("file",""),200);
                    if(file.length()==0)continue;
                    PackEntry entry=new PackEntry();
                    entry.title=clean(item.optString("title",""),60);entry.players=clean(item.optString("players",""),20);
                    entry.genre=clean(item.optString("genre",""),20);entry.version=clean(item.optString("version",""),30);
                    entry.group=clean(item.optString("group",""),80);entry.aliases=clean(item.optString("aliases",""),200);
                    entry.cover=clean(item.optString("cover",""),200);
                    String path=plain(file);while(path.startsWith("./"))path=path.substring(2);
                    byPath.put(path,entry);
                    int slash=path.lastIndexOf('/');
                    if(slash>=0)byName.put(path.substring(slash+1),entry);
                }
                break;
            }
        }finally{if(c!=null)c.close();}
        return byPath;
    }
    private HashMap<String,Cover> readCovers(Uri tree,String rootId)throws Exception{
        HashMap<String,Cover> covers=new HashMap<String,Cover>();
        String coversId=null;Cursor c=null;
        try{
            c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId),DOC_COLUMNS,null,null,null);
            if(c==null)return covers;
            while(c.moveToNext()) {
                String name=c.getString(1),mime=c.getString(2);
                if(name!=null&&"covers".equalsIgnoreCase(name)&&DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){coversId=c.getString(0);break;}
            }
        }finally{if(c!=null)c.close();}
        if(coversId==null)return covers;
        c=null;
        try{
            c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,coversId),DOC_COLUMNS,null,null,null);
            if(c==null)return covers;
            while(c.moveToNext()){
                String id=c.getString(0),name=c.getString(1);
                long size=c.isNull(3)?-1:c.getLong(3);
                if(name==null)continue;
                String ext=extension(name);
                if(!("png".equals(ext)||"jpg".equals(ext)||"jpeg".equals(ext)||"webp".equals(ext))||size<0||size>128*1024)continue;
                Cover cover=new Cover();cover.docId=id;cover.ext=ext;covers.put(stem(name).toLowerCase(Locale.ROOT),cover);
            }
        }finally{if(c!=null)c.close();}
        return covers;
    }
    private void scanTree(Uri tree,String parentId,String prefix,int depth,ArrayList<Candidate> out,HashMap<String,PackEntry> byPath,HashMap<String,PackEntry> byName)throws Exception{
        if(depth>MAX_SCAN_DEPTH||out.size()>=MAX_LIBRARY)return;
        Cursor c=null;
        try{
            c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId),DOC_COLUMNS,null,null,null);
            if(c==null)return;
            while(c.moveToNext()&&out.size()<MAX_LIBRARY){
                String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
                long size=c.isNull(3)?-1:c.getLong(3);
                if(name==null||name.length()==0||name.charAt(0)=='.')continue;
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
                    if(depth<MAX_SCAN_DEPTH&&!"covers".equalsIgnoreCase(name))scanTree(tree,id,prefix+name+"/",depth+1,out,byPath,byName);
                    continue;
                }
                String ext=extension(name);long limit=sizeLimit(ext);
                if(limit==0||size<0||size>limit)continue;
                Candidate candidate=new Candidate();
                candidate.docId=id;candidate.name=name;candidate.ext=ext;candidate.size=size;
                candidate.relative=plain(prefix+name);
                candidate.meta=byPath.get(candidate.relative);
                if(candidate.meta==null)candidate.meta=byName.get(name.toLowerCase(Locale.ROOT));
                out.add(candidate);
            }
        }finally{if(c!=null)c.close();}
    }
    private String saveCover(Uri tree,HashMap<String,Cover> covers,String name,String hash){
        if(name==null||name.length()==0)return null;
        int slash=name.lastIndexOf('/');String file=slash>=0?name.substring(slash+1):name;
        Cover cover=covers.get(stem(file).toLowerCase(Locale.ROOT));
        if(cover==null)return null;
        try{
            byte[] data=readDocument(tree,cover.docId,128*1024);
            File dir=new File(getFilesDir(),"library/covers");
            if(!dir.isDirectory()&&!dir.mkdirs())return null;
            File temp=new File(dir,hash+".tmp");
            FileOutputStream out=new FileOutputStream(temp);
            try{out.write(data);out.getFD().sync();}finally{out.close();}
            File dest=new File(dir,hash+"."+cover.ext);
            if(dest.exists())dest.delete();
            return temp.renameTo(dest)?hash+"."+cover.ext:null;
        }catch(Exception e){return null;}
    }
    private String copyRom(Uri tree,Candidate candidate,File target)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        InputStream in=getContentResolver().openInputStream(docUri(tree,candidate.docId));
        if(in==null)throw new IOException("无法读取 "+candidate.name);
        FileOutputStream out=null;long total=0;
        try{
            out=new FileOutputStream(target);byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>candidate.size)throw new IOException("文件在读取过程中发生变化，请重试");out.write(buffer,0,n);digest.update(buffer,0,n);}
            out.flush();out.getFD().sync();
        }finally{try{in.close();}catch(Exception ignored){}if(out!=null)try{out.close();}catch(Exception ignored){}}
        if(total!=candidate.size){target.delete();throw new IOException("游戏文件读取不完整，请重试");}
        byte[] bytes=digest.digest();StringBuilder hex=new StringBuilder();
        for(byte b:bytes)hex.append(HEX[(b>>4)&15]).append(HEX[b&15]);
        return hex.toString();
    }
    private static String[] destination(String ext,String hash){
        if("nes".equals(ext))return new String[]{"library/roms",hash+".nes"};
        if("zip".equals(ext))return new String[]{"arcade/roms","user-"+hash.substring(0,32)+".zip"};
        return new String[]{ext+"/roms","user-"+hash.substring(0,32)+"."+ext};
    }
    private JSONObject libraryRecord(Candidate candidate,String hash,String path,String cover)throws Exception{
        PackEntry meta=candidate.meta;
        String id="user-"+hash.substring(0,16);
        String system="nes".equals(candidate.ext)?"nes":("zip".equals(candidate.ext)?"arcade":candidate.ext);
        NameInfo info=parseName(stem(candidate.name));
        String title=meta!=null&&meta.title.length()>0?meta.title:(info.title.length()>0?info.title:clean(stem(candidate.name),60));
        String group=meta!=null?meta.group:"";
        if(!group.matches("[a-z0-9-]{1,100}"))group=groupKey(system,title);
        if(group==null||!group.matches("[a-z0-9-]{1,100}"))group=id;
        String version=meta!=null&&meta.version.length()>0?meta.version:info.version;
        JSONObject record=new JSONObject();
        record.put("id",id);record.put("hash",hash);record.put("title",title);
        record.put("aliases",meta!=null?meta.aliases:"");
        record.put("genre",meta!=null&&meta.genre.length()>0?meta.genre:"其他");
        record.put("players",meta!=null&&meta.players.length()>0?meta.players:"待确认");
        record.put("version",version);
        record.put("group",group);record.put("path",path);
        record.put("src",candidate.relative);record.put("size",candidate.size);
        if(!"nes".equals(candidate.ext))record.put("system",system);
        if(cover!=null)record.put("cover",cover);
        return record;
    }
    private JSONArray readLibraryRecords()throws IOException{
        File file=new File(storeDir,"library.json");
        JSONArray out=new JSONArray();
        if(!file.isFile())return out;
        try{
            JSONObject root=new JSONObject(new String(readAll(new FileInputStream(file),32*1024*1024),"UTF-8"));
            JSONArray list=root.optJSONArray("games");
            if(list!=null)for(int i=0;i<list.length();i++){JSONObject record=list.optJSONObject(i);if(record!=null)out.put(record);}
            return out;
        }catch(Exception e){throw new IOException("游戏库记录无法读取，请重试");}
    }
    private int[] importFolder(Uri tree)throws Exception{
        String rootId=DocumentsContract.getTreeDocumentId(tree);
        HashMap<String,PackEntry> byName=new HashMap<String,PackEntry>();
        HashMap<String,PackEntry> byPath=readPack(tree,rootId,byName);
        HashMap<String,Cover> covers=readCovers(tree,rootId);
        ArrayList<Candidate> found=new ArrayList<Candidate>();
        scanTree(tree,rootId,"",0,found,byPath,byName);
        if(found.isEmpty())throw new IOException("这个文件夹里没有找到可用的游戏文件");
        JSONArray records=readLibraryRecords();
        HashSet<String> known=new HashSet<String>(),knownShapes=new HashSet<String>();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);
            if(record==null)continue;
            if(record.optString("hash","").length()==64)known.add(record.optString("hash"));
            String src=record.optString("src","");long size=record.optLong("size",-1);
            if(src.length()>0&&size>=0)knownShapes.add(src+"|"+size);
        }
        long need=64L*1024*1024;
        for(Candidate candidate:found)if(!knownShapes.contains(candidate.relative+"|"+candidate.size))need+=candidate.size;
        if(need>new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes())throw new IOException("电视剩余空间不足，需要约"+(need/1024/1024)+"MB，请清理后重试");
        File staging=new File(getFilesDir(),"library/staging");
        deleteTree(staging);
        if(!staging.mkdirs()&&!staging.isDirectory())throw new IOException("无法准备导入目录");
        int added=0,skipped=0;
        try{
            for(int i=0;i<found.size();i++){
                if(importCancel)throw new IOException("已取消导入");
                Candidate candidate=found.get(i);
                String shape=candidate.relative+"|"+candidate.size;
                if(knownShapes.contains(shape)){skipped++;continue;}
                File temp=new File(staging,"part-"+i);
                String hash=copyRom(tree,candidate,temp);
                if(known.contains(hash)){temp.delete();knownShapes.add(shape);skipped++;continue;}
                String[] dest=destination(candidate.ext,hash);
                File folder=new File(getFilesDir(),dest[0]);
                if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建游戏文件夹");
                File rom=new File(folder,dest[1]);
                if(rom.exists())rom.delete();
                if(!temp.renameTo(rom))throw new IOException("写入游戏文件失败，请检查存储空间");
                String cover=candidate.meta!=null?saveCover(tree,covers,candidate.meta.cover,hash):null;
                if(cover==null)cover=saveCover(tree,covers,candidate.name,hash);
                records.put(libraryRecord(candidate,hash,dest[0]+"/"+dest[1],cover));
                known.add(hash);knownShapes.add(shape);added++;
            }
        }finally{deleteTree(staging);}
        synchronized(storeLock){atomicWrite(new File(storeDir,"library.json"),"{\"schema\":1,\"games\":"+records.toString()+"}");}
        return new int[]{added,skipped};
    }
    private void startLegacyImport(final File dir){
        if(scanning){js("Arcade.libraryResult(0,0,'正在导入游戏，请稍候')");return;}
        scanning=true;importCancel=false;
        new Thread(new Runnable(){public void run(){
            int added=0,skipped=0;String error=null;
            try{int[] value=importFolder(dir);added=value[0];skipped=value[1];}
            catch(SecurityException e){error="没有读取这个文件夹的权限，请重新选择";}
            catch(Throwable e){error=message(e);}
            finally{scanning=false;}
            js("Arcade.libraryResult("+added+","+skipped+","+JSONObject.quote(error==null?"":error)+")");
        }},"ArcadeLegacyImport").start();
    }
    private int[] importFolder(File root)throws Exception{
        HashMap<String,PackEntry> byName=new HashMap<String,PackEntry>();
        HashMap<String,PackEntry> byPath=readPack(root,byName);
        HashMap<String,Cover> covers=readCovers(root);
        ArrayList<Candidate> found=new ArrayList<Candidate>();
        scanTree(root,"",0,found,byPath,byName);
        if(found.isEmpty())throw new IOException("这个文件夹里没有找到可用的游戏文件");
        JSONArray records=readLibraryRecords();
        HashSet<String> known=new HashSet<String>(),knownShapes=new HashSet<String>();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);
            if(record==null)continue;
            if(record.optString("hash","").length()==64)known.add(record.optString("hash"));
            String src=record.optString("src","");long size=record.optLong("size",-1);
            if(src.length()>0&&size>=0)knownShapes.add(src+"|"+size);
        }
        long need=64L*1024*1024;
        for(Candidate candidate:found)if(!knownShapes.contains(candidate.relative+"|"+candidate.size))need+=candidate.size;
        if(need>new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes())throw new IOException("电视剩余空间不足，需要约"+(need/1024/1024)+"MB，请清理后重试");
        File staging=new File(getFilesDir(),"library/staging");
        deleteTree(staging);
        if(!staging.mkdirs()&&!staging.isDirectory())throw new IOException("无法准备导入目录");
        int added=0,skipped=0;
        try{
            for(int i=0;i<found.size();i++){
                if(importCancel)throw new IOException("已取消导入");
                Candidate candidate=found.get(i);
                String shape=candidate.relative+"|"+candidate.size;
                if(knownShapes.contains(shape)){skipped++;continue;}
                File temp=new File(staging,"part-"+i);
                String hash=copyRom(candidate.source,candidate,temp);
                if(known.contains(hash)){temp.delete();knownShapes.add(shape);skipped++;continue;}
                String[] dest=destination(candidate.ext,hash);
                File folder=new File(getFilesDir(),dest[0]);
                if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建游戏文件夹");
                File rom=new File(folder,dest[1]);
                if(rom.exists())rom.delete();
                if(!temp.renameTo(rom))throw new IOException("写入游戏文件失败，请检查存储空间");
                String cover=candidate.meta!=null?saveCover(covers,candidate.meta.cover,hash):null;
                if(cover==null)cover=saveCover(covers,candidate.name,hash);
                records.put(libraryRecord(candidate,hash,dest[0]+"/"+dest[1],cover));
                known.add(hash);knownShapes.add(shape);added++;
            }
        }finally{deleteTree(staging);}
        synchronized(storeLock){atomicWrite(new File(storeDir,"library.json"),"{\"schema\":1,\"games\":"+records.toString()+"}");}
        return new int[]{added,skipped};
    }
    private HashMap<String,PackEntry> readPack(File root,HashMap<String,PackEntry> byName)throws Exception{
        HashMap<String,PackEntry> byPath=new HashMap<String,PackEntry>();
        File file=new File(root,"pack.json");
        if(!file.isFile()||file.length()>8L*1024*1024)return byPath;
        String text=new String(readAll(new FileInputStream(file),8*1024*1024),"UTF-8");
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        JSONObject data;
        try{data=new JSONObject(text);}catch(Exception e){throw new IOException("pack.json 格式不正确");}
        JSONArray list=data.optJSONArray("games");
        if(list==null)return byPath;
        for(int i=0;i<list.length()&&i<5000;i++){
            JSONObject item=list.optJSONObject(i);
            if(item==null)continue;
            String name=clean(item.optString("file",""),200);
            if(name.length()==0)continue;
            PackEntry entry=new PackEntry();
            entry.title=clean(item.optString("title",""),60);entry.players=clean(item.optString("players",""),20);
            entry.genre=clean(item.optString("genre",""),20);entry.version=clean(item.optString("version",""),30);
            entry.group=clean(item.optString("group",""),80);entry.aliases=clean(item.optString("aliases",""),200);
            entry.cover=clean(item.optString("cover",""),200);
            String path=plain(name);while(path.startsWith("./"))path=path.substring(2);
            byPath.put(path,entry);
            int slash=path.lastIndexOf('/');
            if(slash>=0)byName.put(path.substring(slash+1),entry);
        }
        return byPath;
    }
    private HashMap<String,Cover> readCovers(File root){
        HashMap<String,Cover> covers=new HashMap<String,Cover>();
        File[] files=new File(root,"covers").listFiles();
        if(files==null)return covers;
        for(File file:files){
            if(!file.isFile()||file.length()<1||file.length()>128*1024)continue;
            String ext=extension(file.getName());
            if(!"png".equals(ext)&&!"jpg".equals(ext)&&!"jpeg".equals(ext)&&!"webp".equals(ext))continue;
            Cover cover=new Cover();cover.file=file;cover.ext=ext;
            covers.put(stem(file.getName()).toLowerCase(Locale.ROOT),cover);
        }
        return covers;
    }
    private void scanTree(File dir,String prefix,int depth,ArrayList<Candidate> out,HashMap<String,PackEntry> byPath,HashMap<String,PackEntry> byName){
        if(depth>MAX_SCAN_DEPTH||out.size()>=MAX_LIBRARY)return;
        File[] children=dir.listFiles();
        if(children==null)return;
        for(File child:children){
            if(out.size()>=MAX_LIBRARY)return;
            String name=child.getName();
            if(name.length()==0||name.charAt(0)=='.')continue;
            if(child.isDirectory()){
                if(depth<MAX_SCAN_DEPTH&&!"covers".equalsIgnoreCase(name))scanTree(child,prefix+name+"/",depth+1,out,byPath,byName);
                continue;
            }
            if(!child.isFile())continue;
            String ext=extension(name);long size=child.length(),limit=sizeLimit(ext);
            if(limit==0||size<1||size>limit)continue;
            Candidate candidate=new Candidate();
            candidate.source=child;candidate.name=name;candidate.ext=ext;candidate.size=size;
            candidate.relative=plain(prefix+name);
            candidate.meta=byPath.get(candidate.relative);
            if(candidate.meta==null)candidate.meta=byName.get(name.toLowerCase(Locale.ROOT));
            out.add(candidate);
        }
    }
    private String saveCover(HashMap<String,Cover> covers,String name,String hash){
        if(name==null||name.length()==0)return null;
        int slash=name.lastIndexOf('/');String file=slash>=0?name.substring(slash+1):name;
        Cover cover=covers.get(stem(file).toLowerCase(Locale.ROOT));
        if(cover==null||cover.file==null)return null;
        try{
            byte[] data=readAll(new FileInputStream(cover.file),128*1024);
            File dir=new File(getFilesDir(),"library/covers");
            if(!dir.isDirectory()&&!dir.mkdirs())return null;
            File temp=new File(dir,hash+".tmp");
            FileOutputStream out=new FileOutputStream(temp);
            try{out.write(data);out.getFD().sync();}finally{out.close();}
            File dest=new File(dir,hash+"."+cover.ext);
            if(dest.exists())dest.delete();
            return temp.renameTo(dest)?hash+"."+cover.ext:null;
        }catch(Exception e){return null;}
    }
    private String copyRom(File source,Candidate candidate,File target)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        InputStream in=new FileInputStream(source);
        FileOutputStream out=null;long total=0;
        try{
            out=new FileOutputStream(target);byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>candidate.size)throw new IOException("文件在读取过程中发生变化，请重试");out.write(buffer,0,n);digest.update(buffer,0,n);}
            out.flush();out.getFD().sync();
        }finally{try{in.close();}catch(Exception ignored){}if(out!=null)try{out.close();}catch(Exception ignored){}}
        if(total!=candidate.size){target.delete();throw new IOException("游戏文件读取不完整，请重试");}
        byte[] bytes=digest.digest();StringBuilder hex=new StringBuilder();
        for(byte b:bytes)hex.append(HEX[(b>>4)&15]).append(HEX[b&15]);
        return hex.toString();
    }
    private void finishLegacyExport(File dir){
        boolean ok=false;File file=null;
        try{
            if(pendingBackup==null)throw new IOException("没有待导出的备份");
            String base="童年游戏厅_存档备份_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            for(int i=0;i<100;i++){
                File candidate=new File(dir,base+(i==0?"":"_"+i)+".json");
                if(candidate.createNewFile()){file=candidate;break;}
            }
            if(file==null)throw new IOException("无法创建备份文件");
            FileOutputStream out=new FileOutputStream(file);
            try{out.write(pendingBackup.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            ok=true;
        }catch(Exception e){if(file!=null)file.delete();}
        pendingBackup=null;
        js("Arcade.backupResult("+ok+")");
        if(!ok)Toast.makeText(this,"备份没有保存，请检查存储权限和空间",Toast.LENGTH_LONG).show();
    }
    private void readLegacyBackup(File file){
        try{
            if(!file.isFile()||file.length()<1||file.length()>8L*1024*1024)throw new IOException("备份文件过大或无法读取");
            restoreBackupText(new String(readAll(new FileInputStream(file),8*1024*1024),"UTF-8"));
        }catch(Exception e){Toast.makeText(this,message(e),Toast.LENGTH_LONG).show();}
    }
    private void restoreBackupText(String text){
        if(text==null||text.length()>8*1024*1024){Toast.makeText(this,"备份文件过大，请通过手机备份/恢复",Toast.LENGTH_LONG).show();return;}
        js("Arcade.restoreBackupText("+JSONObject.quote(text)+")");
    }
    private static String message(Throwable e){String text=e.getMessage();return text==null||text.length()==0?e.getClass().getSimpleName():text;}
    private static long treeSize(File file){if(file==null)return 0;if(file.isFile())return file.length();long total=0;File[] children=file.listFiles();if(children!=null)for(File child:children)total+=treeSize(child);return total;}
    public class Bridge {
        @JavascriptInterface public boolean nativeNesAvailable(){String dir=getApplicationInfo().nativeLibraryDir;return new File(dir,"libfceumm.so").isFile()||new File(dir,"libnestopia.so").isFile();}
        @JavascriptInterface public String nativeSfcStart(String name,String hash){return nativeNes.loadSfc(name,hash);}
        @JavascriptInterface public String nativeMdStart(String name,String hash){return nativeNes.loadMd(name,hash);}
        @JavascriptInterface public String nativeGbaStart(String name,String hash){return nativeNes.loadGba(name,hash);}
        @JavascriptInterface public String nativeArcadeStart(String name,String hash){return nativeNes.loadArcade(name,hash);}
        @JavascriptInterface public String nativeNesStart(String data,String hash){return nativeNes.load(data,hash);}
        @JavascriptInterface public void nativeNesPause(){nativeNes.pause();}
        @JavascriptInterface public void nativeNesResume(){nativeNes.resume();}
        @JavascriptInterface public void cancelImportFolder(){importCancel=true;}
        @JavascriptInterface public String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "1.0.0";}}
        @JavascriptInterface public int appVersionCode(){return updates.installedVersionCode();}
        @JavascriptInterface public String checkUpdate(){updates.check();return updates.status();}
        @JavascriptInterface public void startUpdateDownload(boolean allowMetered){updates.download(allowMetered);}
        @JavascriptInterface public String updateStatus(){return updates.status();}
        @JavascriptInterface public void installUpdate(){updates.install(MainActivity.this);}
        @JavascriptInterface public void cancelUpdate(){updates.cancel();}
        @JavascriptInterface public String updateLaunchResult(){return updates.launchResult().toString();}
        @JavascriptInterface public long freeBytes(){try{return new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();}catch(Throwable e){return 0;}}
        @JavascriptInterface public void nativeNesClose(){nativeNes.close();}
        @JavascriptInterface public void nativeNesButton(int player,int button,boolean down){nativeNes.button(player,button,down);}
        @JavascriptInterface public void nativeNesButtonFrom(String source,int player,int button,boolean down){nativeNes.buttonFrom("js:"+source,player,button,down);}
        @JavascriptInterface public void nativeNesRelease(){nativeNes.releaseAll();}
        @JavascriptInterface public void nativeNesVolume(float value){nativeNes.volume(value);}
        @JavascriptInterface public void nativeNesBounds(float left,float top,float width,float height,float viewportWidth,float viewportHeight){nativeNes.bounds(left,top,width,height,viewportWidth,viewportHeight);}
        @JavascriptInterface public String nativeNesSnapshot(){return nativeNes.snapshot();}
        @JavascriptInterface public String nativeNesRestore(String value){return nativeNes.restore(value);}
        @JavascriptInterface public String nativeNesImage(){return nativeNes.image();}
        @JavascriptInterface public String nativeNesStats(){return nativeNes.stats();}
        @JavascriptInterface public String nativeNesRewind(){return nativeNes.rewind();}
        @JavascriptInterface public String nativeNesRewindInfo(){return nativeNes.rewindInfo();}
        @JavascriptInterface public String systemCheck(){
            JSONObject out=new JSONObject();ArrayList<String> errors=new ArrayList<String>();
            try{String[] abis=Build.SUPPORTED_ABIS;out.put("abi",abis!=null&&abis.length>0?abis[0]:"unknown");}catch(Throwable e){errors.add(message(e));}
            try{out.put("sdk",Build.VERSION.SDK_INT);}catch(Throwable e){errors.add(message(e));}
            JSONArray cores=new JSONArray();
            try{String dir=getApplicationInfo().nativeLibraryDir;String[] names={"libfceumm.so","libnestopia.so","libsnes9x.so","libsnes9x2005.so","libsnes9x2005plus.so","libpicodrive.so","libgenplusgx.so","libmgba.so","libarcade.so","libjnidispatch.so"};for(String name:names)if(new File(dir,name).isFile())cores.put(name);}catch(Throwable e){errors.add(message(e));}
            try{out.put("cores",cores);}catch(Throwable e){errors.add(message(e));}
            try{out.put("freeBytes",new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes());}catch(Throwable e){errors.add(message(e));}
            String webview="unknown";
            try{if(Build.VERSION.SDK_INT>=26){PackageInfo info=WebView.getCurrentWebViewPackage();if(info!=null&&info.versionName!=null&&info.versionName.length()>0)webview=info.versionName;}}catch(Throwable e){errors.add(message(e));}
            try{out.put("webview",webview);}catch(Throwable e){errors.add(message(e));}
            int pads=0;
            try{if(inputs!=null)for(int id:inputs.getInputDeviceIds()){InputDevice device=InputDevice.getDevice(id);if(device==null)continue;int sources=device.getSources();if((sources&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(sources&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK)pads++;}}catch(Throwable e){errors.add(message(e));}
            try{out.put("gamepads",pads);}catch(Throwable e){errors.add(message(e));}
            int count=0;long bytes=0;
            try{File file=new File(storeDir,"library.json");if(file.isFile()){JSONArray games=new JSONObject(new String(readAll(new FileInputStream(file),32*1024*1024),"UTF-8")).optJSONArray("games");if(games!=null)count=games.length();}}catch(Throwable e){errors.add(message(e));}
            try{bytes=treeSize(new File(getFilesDir(),"library/roms"));}catch(Throwable e){errors.add(message(e));}
            try{out.put("libraryCount",count);out.put("libraryBytes",bytes);}catch(Throwable e){errors.add(message(e));}
            if(!errors.isEmpty()){StringBuilder text=new StringBuilder();for(String item:errors){if(text.length()>0)text.append("; ");text.append(item);}try{out.put("error",text.toString());}catch(Throwable ignored){}}
            return out.toString();
        }
        @JavascriptInterface public String checkResources(){
            try{
                JSONArray games=new JSONArray();
                File file=new File(storeDir,"library.json");
                if(file.isFile()){JSONArray list=new JSONObject(new String(readAll(new FileInputStream(file),32*1024*1024),"UTF-8")).optJSONArray("games");if(list!=null)games=list;}
                int missing=0;JSONArray titles=new JSONArray();
                for(int i=0;i<games.length();i++){
                    JSONObject game=games.optJSONObject(i);if(game==null)continue;
                    String path=game.optString("path","");boolean bad=false;
                    if(path.length()==0||path.contains("..")||path.startsWith("/"))bad=true;
                    else{
                        File rom=new File(getFilesDir(),path);
                        if(!rom.isFile())bad=true;
                        else if(game.has("size")){long size=game.optLong("size",-1);if(size>=0&&rom.length()!=size)bad=true;}
                    }
                    if(bad){missing++;if(titles.length()<20)titles.put(game.optString("title","未知游戏"));}
                }
                return new JSONObject().put("checked",games.length()).put("missingCount",missing).put("missing",titles).toString();
            }catch(Throwable e){
                try{return new JSONObject().put("checked",0).put("missingCount",0).put("missing",new JSONArray()).put("error",message(e)).toString();}catch(Exception ignored){return "{\"checked\":0,\"missingCount\":0,\"missing\":[],\"error\":\"检查失败\"}";}
            }
        }
        @JavascriptInterface public String startLanServer(){return lan.start();}
        @JavascriptInterface public String lanInfo(){return lan.infoJson();}
        @JavascriptInterface public void stopLanServer(){lan.stop();}
        @JavascriptInterface public String nativeGamepadList(){
            JSONArray out=new JSONArray();
            if(inputs==null)return out.toString();
            int[] ids=inputs.getInputDeviceIds();
            for(int i=0;i<ids.length;i++){
                InputDevice device=InputDevice.getDevice(ids[i]);
                if(device==null)continue;
                int sources=device.getSources();
                if((sources&InputDevice.SOURCE_GAMEPAD)!=InputDevice.SOURCE_GAMEPAD&&(sources&InputDevice.SOURCE_JOYSTICK)!=InputDevice.SOURCE_JOYSTICK)continue;
                try{JSONObject item=gamepads.resolve(device).toJson();item.put("id",ids[i]);out.put(item);}catch(Exception ignored){}
            }
            return out.toString();
        }
        @JavascriptInterface public void nativeGamepadMonitor(boolean on){gamepadMonitor=on;}
        @JavascriptInterface public void nativeGamepadCapture(String key,int action){startCapture(key,action);}
        @JavascriptInterface public void nativeGamepadCaptureCancel(){clearCapture(false);}
        @JavascriptInterface public boolean nativeGamepadLayout(String key,String layout){return gamepads.setLayout(key,layout);}
        @JavascriptInterface public boolean nativeGamepadSwap(String key){return gamepads.swapAB(key);}
        @JavascriptInterface public boolean nativeGamepadReset(String key){return gamepads.reset(key);}
        @JavascriptInterface public boolean nativeGamepadApplyAll(String key){
            HashSet<String> keys=new HashSet<String>();
            if(inputs!=null)for(int id:inputs.getInputDeviceIds()){InputDevice device=InputDevice.getDevice(id);if(device!=null)keys.add(gamepads.key(device));}
            return gamepads.applyToAll(key,keys);
        }
        @JavascriptInterface public String readAsset(String path){if(path==null||!path.matches("roms/[a-z0-9-]+\\.nes"))return null;try{return Base64.encodeToString(readAll(getAssets().open(path),8*1024*1024),Base64.NO_WRAP);}catch(Exception e){return null;}}
        @JavascriptInterface public void pickGameFolder(){runOnUiThread(new Runnable(){public void run(){
            if(scanning){js("Arcade.libraryResult(0,0,'正在导入游戏，请稍候')");return;}
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{startActivityForResult(intent,PICK_FOLDER);}
            catch(ActivityNotFoundException e){legacyFiles.startFolder();}
        }});}
        @JavascriptInterface public String readLibraryRom(String hash){
            if(hash==null||!hash.matches("[a-f0-9]{64}"))return null;
            try{File file=new File(getFilesDir(),"library/roms/"+hash+".nes");if(!file.isFile())return null;return Base64.encodeToString(readAll(new FileInputStream(file),8*1024*1024),Base64.NO_WRAP);}catch(Exception e){return null;}
        }
        @JavascriptInterface public boolean writeLibraryCover(String hash,String base64,String ext){
            if(hash==null||!hash.matches("[a-f0-9]{64}")||base64==null)return false;
            if(!"png".equals(ext)&&!"jpg".equals(ext)&&!"jpeg".equals(ext)&&!"webp".equals(ext))return false;
            if(base64.length()>1024*1024)return false;
            try{
                byte[] data=Base64.decode(base64,Base64.DEFAULT);
                if(data.length==0||data.length>512*1024)return false;
                File dir=new File(getFilesDir(),"library/covers");
                if(!dir.isDirectory()&&!dir.mkdirs())return false;
                File temp=new File(dir,hash+".tmp");
                FileOutputStream out=new FileOutputStream(temp);
                try{out.write(data);out.getFD().sync();}finally{out.close();}
                File dest=new File(dir,hash+"."+ext);
                if(dest.exists())dest.delete();
                return temp.renameTo(dest);
            }catch(Exception e){return false;}
        }
        @JavascriptInterface public String readStore(String key){synchronized(storeLock){try{File f=keyFile(storeDir,key);if(!f.exists())return null;return new String(readAll(new AtomicFile(f).openRead(),32*1024*1024),"UTF-8");}catch(Exception e){return "{\"__readError\":true}";}}}
        @JavascriptInterface public boolean writeStore(String key,String value){synchronized(storeLock){try{if(value==null||value.length()>32*1024*1024)return false;new JSONObject("{\"value\":"+value+"}");atomicWrite(keyFile(storeDir,key),value);return true;}catch(Exception e){return false;}}}
        @JavascriptInterface public String listStoreKeys(){synchronized(storeLock){JSONArray list=new JSONArray();File[] files=storeDir.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".json"))list.put(f.getName().substring(0,f.getName().length()-5));return list.toString();}}
        @JavascriptInterface public boolean writeBatch(String json){synchronized(storeLock){File stage=new File(getFilesDir(),"store.stage"),old=new File(getFilesDir(),"store.previous");boolean moved=false;try{
            JSONArray entries=new JSONArray(json);if(entries.length()>1000)return false;deleteTree(stage);stage.mkdirs();File[] originals=storeDir.listFiles();if(originals!=null)for(File f:originals)if(f.isFile()&&f.getName().endsWith(".json"))atomicWrite(new File(stage,f.getName()),new String(readAll(new FileInputStream(f),32*1024*1024),"UTF-8"));
            for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);String key=entry.getString("key");if(!key.equals("prefs")&&!key.matches("slot:[a-f0-9]{64}:[0-3]|battery:[a-f0-9]{64}"))throw new IOException("Invalid backup key");atomicWrite(keyFile(stage,key),entry.getJSONObject("value").toString());}
            deleteTree(old);if(!storeDir.renameTo(old))throw new IOException("Cannot stage original");moved=true;if(!stage.renameTo(storeDir))throw new IOException("Cannot commit backup");deleteTree(old);return true;
        }catch(Exception e){if(moved&&!storeDir.exists())old.renameTo(storeDir);deleteTree(stage);return false;}}}
        @JavascriptInterface public void finishApp(){runOnUiThread(new Runnable(){public void run(){finish();}});}
        @JavascriptInterface public void pickBackupFile(){runOnUiThread(new Runnable(){public void run(){
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
            try{startActivityForResult(intent,PICK_BACKUP);}
            catch(ActivityNotFoundException e){legacyFiles.startImport();}
        }});}
        @JavascriptInterface public void exportBackup(final String json){runOnUiThread(new Runnable(){public void run(){if(json==null||json.length()>50*1024*1024){js("Arcade.backupResult(false)");return;}pendingBackup=json;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"童年游戏厅_存档备份.json");try{startActivityForResult(intent,EXPORT_FILE);}catch(ActivityNotFoundException e){legacyFiles.startExport();}}});}
    }
}
