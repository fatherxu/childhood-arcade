package org.childhood.arcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 设备无关的手柄处理。
 *
 * 动作按设备实际发送的键码解析。Android 标准键码始终是默认值，因此系统能
 * 识别的手柄无需配置即可使用。内置提示表（来自 MIT 许可的 RetroArch
 * joypad autoconfig 数据）只用于按键标签不同的设备；查不到时回退默认值。
 */
public final class GamepadProfile {
    public static final int A=0,B=1,SELECT=2,START=3,UP=4,DOWN=5,LEFT=6,RIGHT=7,X=8,Y=9,L=10,R=11,COUNT=12;
    public static final String[] NAMES={"A","B","SELECT","START","UP","DOWN","LEFT","RIGHT","X","Y","L","R"};
    public static final String LAYOUT_AUTO="auto",LAYOUT_DIAMOND="diamond",LAYOUT_SQUARE="square",LAYOUT_TWO="two",LAYOUT_ARCADE="arcade";
    private static final String[] LAYOUTS={LAYOUT_AUTO,LAYOUT_DIAMOND,LAYOUT_SQUARE,LAYOUT_TWO,LAYOUT_ARCADE};
    private static final Set<String> GENERIC=new HashSet<String>(Arrays.asList(
            "gamepad","game","pad","joystick","controller","control","usb","wireless","wired","hid","bluetooth",
            "android","input","device","generic","pc","for","the","and","with","of","xinput","dinput"));

    /** 一个物理触发器：键码、模拟轴方向或 hat 方向。 */
    public static final class Binding {
        public static final int KEY=0,AXIS=1,HAT=2;
        public final int type,code,dir;
        private Binding(int type,int code,int dir){this.type=type;this.code=code;this.dir=dir;}
        static Binding key(int code){return new Binding(KEY,code,0);}
        static Binding axis(int code,int dir){return new Binding(AXIS,code,dir);}
        static Binding hat(int code,int dir){return new Binding(HAT,code,dir);}
        public boolean isKey(int keyCode){return type==KEY&&code==keyCode;}
        public String store(){
            if(type==KEY)return "key:"+code;
            if(type==HAT)return "hat:"+code+":"+(dir>0?"+":"-");
            return "axis:"+code+":"+(dir>0?"+":"-");
        }
        public String label(){
            if(type==KEY)return keyLabel(code);
            String name=type==HAT?(code==0?"十字键左右":"十字键上下"):("轴 "+code);
            return name+(dir>0?" +":" −");
        }
        static Binding parse(String value){
            if(value==null)return null;
            value=value.trim();
            if(value.length()==0)return null;
            try{
                if(value.startsWith("key:"))return key(Integer.parseInt(value.substring(4)));
                if(value.startsWith("hat:")){
                    String[] p=value.substring(4).split(":");
                    if(p.length!=2)return null;
                    return hat(Integer.parseInt(p[0]),p[1].startsWith("-")?-1:1);
                }
                if(value.startsWith("axis:")){
                    String v=value.substring(5);
                    int colon=v.indexOf(':');
                    if(colon>=0)return axis(Integer.parseInt(v.substring(0,colon)),v.substring(colon+1).startsWith("-")?-1:1);
                    if(v.startsWith("+")||v.startsWith("-"))return axis(Integer.parseInt(v.substring(1)),v.startsWith("-")?-1:1);
                    return axis(Integer.parseInt(v),1);
                }
                // RetroArch autoconfig 语法。
                if(value.startsWith("btn:")){
                    String v=value.substring(4);
                    if(v.length()==0)return null;
                    if(v.startsWith("h")){
                        if(v.length()<4)return null;
                        int code="h0".equals(v.substring(0,2))?0:1;
                        String d=v.substring(2);
                        return hat(code,d.startsWith("up")||d.startsWith("left")?-1:1);
                    }
                    if(v.startsWith("+")||v.startsWith("-"))return axis(Integer.parseInt(v.substring(1)),v.startsWith("-")?-1:1);
                    return key(Integer.parseInt(v));
                }
            }catch(Exception ignored){}
            return null;
        }
    }

    /** 单个物理手柄（按 vendor/product id 识别）的最终绑定。 */
    public static final class Profile {
        public final String deviceKey,deviceName,vendorId,productId;
        public String matched="";
        public String layout=LAYOUT_AUTO;
        public float deadzone=.6f;
        public boolean custom=false;
        @SuppressWarnings("unchecked")
        public final ArrayList<Binding>[] bindings=new ArrayList[COUNT];

        private Profile(String key,String name,String vid,String pid){
            deviceKey=key;deviceName=name;vendorId=vid;productId=pid;
            for(int i=0;i<COUNT;i++)bindings[i]=defaults(i);
        }
        public int actionForKey(int keyCode){
            for(int a=0;a<COUNT;a++)for(int i=0;i<bindings[a].size();i++)if(bindings[a].get(i).isKey(keyCode))return a;
            return -1;
        }
        public boolean hasAxis(int action){
            ArrayList<Binding> list=bindings[action];
            for(int i=0;i<list.size();i++)if(list.get(i).type!=Binding.KEY)return true;
            return false;
        }
        public boolean axisPressed(MotionEvent event,int action,float threshold){
            ArrayList<Binding> list=bindings[action];
            for(int i=0;i<list.size();i++){
                Binding b=list.get(i);
                if(b.type==Binding.KEY)continue;
                int axis=b.type==Binding.HAT?(b.code==0?MotionEvent.AXIS_HAT_X:MotionEvent.AXIS_HAT_Y):b.code;
                float v=event.getAxisValue(axis);
                if(Float.isNaN(v)||Float.isInfinite(v))continue;
                if(b.dir>0&&v>threshold)return true;
                if(b.dir<0&&v<-threshold)return true;
            }
            return false;
        }
        public JSONObject toJson() throws JSONException {
            JSONObject out=new JSONObject();
            out.put("key",deviceKey);out.put("name",deviceName);
            out.put("vid",vendorId);out.put("pid",productId);
            out.put("profile",matched);out.put("layout",layout);
            out.put("custom",custom);out.put("deadzone",Math.round(deadzone*100)/100.0);
            JSONObject map=new JSONObject();
            for(int a=0;a<COUNT;a++){
                StringBuilder text=new StringBuilder();
                for(int i=0;i<bindings[a].size();i++){if(i>0)text.append(" / ");text.append(bindings[a].get(i).label());}
                map.put(NAMES[a],text.length()==0?"未绑定":text.toString());
            }
            out.put("bindings",map);
            return out;
        }
        boolean isCustom(){return custom;}
    }

    private static final class LibEntry {
        final String name;final int vid,pid;final Map<String,String> map;
        LibEntry(String name,int vid,int pid,Map<String,String> map){this.name=name;this.vid=vid;this.pid=pid;this.map=map;}
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences calibrated;
    private final Map<String,Profile> cache=new HashMap<String,Profile>();
    private final Map<String,List<LibEntry>> byId=new HashMap<String,List<LibEntry>>();
    private final List<LibEntry> byName=new ArrayList<LibEntry>();
    private final Set<String> customKeys=new HashSet<String>();
    private boolean customLoaded=false;
    private boolean libraryLoaded=false;

    public GamepadProfile(Context context){
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("gamepad-profiles",0);
        calibrated=this.context.getSharedPreferences("gamepad-calibration",0);
    }

    public static boolean validLayout(String layout){return Arrays.asList(LAYOUTS).contains(layout);}

    public String key(InputDevice device){
        if(device==null)return "unknown";
        int vid=device.getVendorId(),pid=device.getProductId();
        if(vid>=0&&pid>=0&&(vid!=0||pid!=0))return vid+":"+pid;
        String name=normalize(device.getName());
        return "n:"+Integer.toHexString(name.hashCode())+(name.length()>24?name.substring(0,24):name);
    }

    public synchronized Profile resolve(InputDevice device){
        String key=key(device);
        Profile profile=cache.get(key);
        if(profile!=null)return profile;
        profile=build(key,device);
        cache.put(key,profile);
        return profile;
    }

    public synchronized Profile cached(String key){
        if(key==null)return null;
        Profile profile=cache.get(key);
        if(profile!=null)return profile;
        JSONObject stored=stored(key);
        if(stored!=null&&stored.optBoolean("custom",false)){
            profile=new Profile(key,"", "-1","-1");
            applyStored(profile,stored);
            cache.put(key,profile);
        }
        return profile;
    }

    private Profile build(String key,InputDevice device){
        String name=device==null?"":String.valueOf(device.getName());
        int vid=device==null?-1:device.getVendorId(),pid=device==null?-1:device.getProductId();
        Profile profile=new Profile(key,name,String.valueOf(vid),String.valueOf(pid));
        ensureLibrary();
        LibEntry hit=match(vid,pid,name);
        if(hit!=null){
            profile.matched=hit.name;
            boolean[] assigned=new boolean[COUNT];
            for(Map.Entry<String,String> entry:hit.map.entrySet()){
                int action=actionOf(entry.getKey());
                if(action<0&&"L2".equalsIgnoreCase(entry.getKey()))action=L;
                if(action<0&&"R2".equalsIgnoreCase(entry.getKey()))action=R;
                if(action<0)continue;
                Binding binding=Binding.parse(entry.getValue());
                if(binding==null)continue;
                if(action>=UP&&action<=RIGHT||binding.type!=Binding.KEY){
                    // 方向键和轴类提示沿用系统默认值再叠加：其它平台的轴编号在安卓上可能对应不到
                    // 有效轴，不能因为一条提示就把原本能用的标准按键覆盖掉。
                    boolean duplicate=false;
                    for(int i=0;i<profile.bindings[action].size();i++){
                        Binding old=profile.bindings[action].get(i);
                        if(old.type==binding.type&&old.code==binding.code&&old.dir==binding.dir)duplicate=true;
                    }
                    if(!duplicate)profile.bindings[action].add(binding);
                }else{
                    if(!assigned[action]){profile.bindings[action].clear();assigned[action]=true;}
                    profile.bindings[action].add(binding);
                }
            }
        }
        JSONObject stored=stored(key);
        if(stored!=null)applyStored(profile,stored);
        return profile;
    }

    private void applyStored(Profile profile,JSONObject stored){
        profile.layout=validLayout(stored.optString("layout",""))?stored.optString("layout"):LAYOUT_AUTO;
        float deadzone=(float)stored.optDouble("deadzone",.6);
        profile.deadzone=clamp(deadzone,.2f,.8f);
        JSONObject map=stored.optJSONObject("bindings");
        if(map==null)return;
        profile.custom=true;
        for(int action=0;action<COUNT;action++){
            JSONArray list=map.optJSONArray(NAMES[action]);
            if(list==null)continue;
            ArrayList<Binding> parsed=new ArrayList<Binding>();
            for(int i=0;i<list.length();i++){
                Binding binding=Binding.parse(list.optString(i,""));
                if(binding!=null)parsed.add(binding);
            }
            profile.bindings[action]=parsed;
        }
    }

    private void save(Profile profile){
        JSONObject out=new JSONObject();
        try{
            out.put("schema",1);
            out.put("custom",true);
            out.put("layout",profile.layout);
            out.put("deadzone",Math.round(profile.deadzone*100)/100.0);
            out.put("source",profile.matched);
            JSONObject map=new JSONObject();
            for(int action=0;action<COUNT;action++){
                JSONArray list=new JSONArray();
                for(int i=0;i<profile.bindings[action].size();i++)list.put(profile.bindings[action].get(i).store());
                map.put(NAMES[action],list);
            }
            out.put("bindings",map);
        }catch(JSONException ignored){return;}
        prefs.edit().putString(profile.deviceKey,out.toString()).apply();
        customKeys.add(profile.deviceKey);
    }

    /** 该设备是否保存过自定义绑定（用于同一手柄的键盘接口）。 */
    public synchronized boolean hasCustom(String key){
        if(key==null)return false;
        if(!customLoaded){
            customLoaded=true;
            try{
                for(Map.Entry<String,?> entry:prefs.getAll().entrySet()){
                    Object value=entry.getValue();
                    if(value instanceof String&&((String)value).contains("\"custom\":true"))customKeys.add(entry.getKey());
                }
            }catch(Throwable ignored){
            }
        }
        return customKeys.contains(key);
    }

    private JSONObject stored(String key){
        if(key==null)return null;
        String value;
        // 配置存储被写成其它类型时不要让主线程抛 ClassCastException。
        try{value=prefs.getString(key,"");}catch(Throwable e){return null;}
        if(value.length()==0)return null;
        try{return new JSONObject(value);}catch(Exception e){return null;}
    }

    public synchronized boolean setLayout(String key,String layout){
        if(!validLayout(layout))return false;
        Profile profile=cached(key);
        if(profile==null)return false;
        profile.layout=layout;
        profile.custom=true;
        save(profile);
        return true;
    }

    public synchronized boolean bind(String key,int action,int keyCode){
        if(action<0||action>=COUNT)return false;
        Profile profile=cached(key);
        if(profile==null)return false;
        Binding binding=Binding.key(keyCode);
        // 读取绑定列表发生在主线程（按键分发），这里换新列表而不是原地删除，避免并发修改。
        for(int other=0;other<COUNT;other++){
            if(other==action)continue;
            ArrayList<Binding> list=profile.bindings[other];
            ArrayList<Binding> next=new ArrayList<Binding>(list.size());
            for(int i=0;i<list.size();i++)if(!list.get(i).isKey(keyCode))next.add(list.get(i));
            if(next.size()!=list.size())profile.bindings[other]=next;
        }
        ArrayList<Binding> target=new ArrayList<Binding>();
        target.add(binding);
        profile.bindings[action]=target;
        profile.custom=true;
        save(profile);
        return true;
    }

    /** 把摇杆轴或十字键方向绑定到动作（用于按键以轴上报的手柄）。 */
    public synchronized boolean bindAxis(String key,int action,int axisCode,int dir){
        if(action<0||action>=COUNT)return false;
        Profile profile=cached(key);
        if(profile==null)return false;
        Binding binding=Binding.axis(axisCode,dir);
        // 同上：主线程可能正在遍历这些列表，统一改成替换新列表。
        for(int other=0;other<COUNT;other++){
            if(other==action)continue;
            ArrayList<Binding> list=profile.bindings[other];
            ArrayList<Binding> next=new ArrayList<Binding>(list.size());
            for(int i=0;i<list.size();i++){
                Binding old=list.get(i);
                if(!(old.type==Binding.AXIS&&old.code==axisCode&&old.dir==dir))next.add(old);
            }
            if(next.size()!=list.size())profile.bindings[other]=next;
        }
        ArrayList<Binding> target=new ArrayList<Binding>();
        target.add(binding);
        profile.bindings[action]=target;
        profile.custom=true;
        save(profile);
        return true;
    }

    public static String axisLabel(int axisCode,int dir){
        String name=axisCode==MotionEvent.AXIS_HAT_X?"十字键左右":axisCode==MotionEvent.AXIS_HAT_Y?"十字键上下":("轴 "+axisCode);
        return name+(dir>0?" +":" −");
    }

    public synchronized boolean swapAB(String key){
        Profile profile=cached(key);
        if(profile==null)return false;
        ArrayList<Binding> swap=profile.bindings[A];
        profile.bindings[A]=profile.bindings[B];
        profile.bindings[B]=swap;
        profile.custom=true;
        save(profile);
        return true;
    }

    public synchronized boolean reset(String key){
        if(key==null)return false;
        boolean had=prefs.contains(key);
        prefs.edit().remove(key).apply();
        calibrated.edit().remove(key).apply();
        cache.remove(key);
        customKeys.remove(key);
        return had;
    }

    public synchronized boolean applyToAll(String sourceKey,Set<String> deviceKeys){
        Profile source=cached(sourceKey);
        if(source==null)return false;
        JSONObject out=new JSONObject();
        try{
            out.put("schema",1);
            out.put("custom",true);
            out.put("layout",source.layout);
            out.put("deadzone",Math.round(source.deadzone*100)/100.0);
            out.put("source",source.matched);
            JSONObject map=new JSONObject();
            for(int action=0;action<COUNT;action++){
                JSONArray list=new JSONArray();
                for(int i=0;i<source.bindings[action].size();i++)list.put(source.bindings[action].get(i).store());
                map.put(NAMES[action],list);
            }
            out.put("bindings",map);
        }catch(JSONException ignored){return false;}
        String value=out.toString();
        // 只写给当前连接的其它手柄，不动历史设备和电视遥控器的配置。
        Set<String> keys=new HashSet<String>();
        if(deviceKeys!=null)keys.addAll(deviceKeys);
        SharedPreferences.Editor marks=calibrated.edit();
        for(String key:keys)if(!key.equals(sourceKey)){prefs.edit().putString(key,value).apply();customKeys.add(key);marks.putBoolean(key,true);}
        marks.putBoolean(sourceKey,true);
        marks.apply();
        cache.clear();
        save(source);
        return true;
    }

    public synchronized boolean saveDeadzone(String key,float value){
        Profile profile=cached(key);
        if(profile==null)return false;
        float next=clamp(value,.2f,.8f);
        if(Math.abs(next-profile.deadzone)<.01f)return true;
        profile.deadzone=next;
        save(profile);
        return true;
    }

    /** 保存摇杆校准结果，并记为手动校准，自动死区测量不再覆盖该手柄。 */
    public synchronized boolean saveCalibratedDeadzone(String key,float value){
        if(!saveDeadzone(key,value))return false;
        calibrated.edit().putBoolean(key,true).apply();
        return true;
    }

    public boolean hasCalibration(String key){
        if(key==null)return false;
        try{return calibrated.getBoolean(key,false);}catch(Throwable e){return false;}
    }

    // ------------------------------------------------------------------ 工具方法

    private void ensureLibrary(){
        if(libraryLoaded)return;
        libraryLoaded=true;
        InputStream in=null;
        try{
            in=context.getAssets().open("gamepad-profiles.json");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];
            int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            JSONObject root=new JSONObject(new String(out.toByteArray(),"UTF-8"));
            JSONArray list=root.optJSONArray("profiles");
            if(list==null)return;
            for(int i=0;i<list.length();i++){
                JSONObject item=list.optJSONObject(i);
                if(item==null)continue;
                JSONObject map=item.optJSONObject("m");
                if(map==null)continue;
                Map<String,String> values=new HashMap<String,String>();
                JSONArray names=map.names();
                if(names!=null)for(int j=0;j<names.length();j++){
                    String name=names.optString(j,"");
                    values.put(name,map.optString(name,""));
                }
                if(values.isEmpty())continue;
                JSONArray versions=item.optJSONArray("v");
                if(versions==null||versions.length()==0){
                    byName.add(new LibEntry("",-1,-1,values));
                    continue;
                }
                for(int j=0;j<versions.length();j++){
                    JSONObject version=versions.optJSONObject(j);
                    if(version==null)continue;
                    String name=version.optString("n","");
                    int vid=parseInt(version.optString("vid",""));
                    int pid=parseInt(version.optString("pid",""));
                    LibEntry entry=new LibEntry(name,vid,pid,values);
                    if(vid>=0&&pid>=0){
                        String id=vid+":"+pid;
                        List<LibEntry> entries=byId.get(id);
                        if(entries==null){entries=new ArrayList<LibEntry>();byId.put(id,entries);}
                        entries.add(entry);
                    }else{
                        byName.add(entry);
                    }
                }
            }
        }catch(Exception ignored){
            // 配置表缺失或不可读时，不影响手柄基本输入。
        }finally{
            if(in!=null)try{in.close();}catch(Exception ignored){}
        }
    }

    private LibEntry match(int vid,int pid,String name){
        if(vid>=0&&pid>=0){
            List<LibEntry> exact=byId.get(vid+":"+pid);
            if(exact!=null&&!exact.isEmpty()){
                if(exact.size()==1)return exact.get(0);
                Set<String> tokens=tokens(name);
                LibEntry best=exact.get(0);
                float score=-1;
                for(int i=0;i<exact.size();i++){
                    float value=similarity(tokens,tokens(exact.get(i).name));
                    if(value>score){score=value;best=exact.get(i);}
                }
                return best;
            }
        }
        Set<String> tokens=tokens(name);
        if(tokens.isEmpty())return null;
        LibEntry best=null;
        float score=0;
        for(int i=0;i<byName.size();i++){
            float value=similarity(tokens,tokens(byName.get(i).name));
            if(value>score){score=value;best=byName.get(i);}
        }
        return score>=.6f?best:null;
    }

    private static Set<String> tokens(String name){
        Set<String> out=new LinkedHashSet<String>();
        if(name==null)return out;
        String[] parts=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim().split(" ");
        for(int i=0;i<parts.length;i++)if(parts[i].length()>0&&!GENERIC.contains(parts[i]))out.add(parts[i]);
        return out;
    }

    private static float similarity(Set<String> left,Set<String> right){
        if(left.isEmpty()||right.isEmpty())return 0;
        int shared=0;
        for(String token:left)if(right.contains(token))shared++;
        int union=left.size()+right.size()-shared;
        return union==0?0:(float)shared/union;
    }

    private static String normalize(String value){
        return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","");
    }

    private static int parseInt(String value){
        try{return Integer.parseInt(value.trim());}catch(Exception e){return -1;}
    }

    private static float clamp(float value,float min,float max){return value<min?min:value>max?max:value;}

    private static int actionOf(String name){
        if(name==null)return -1;
        for(int i=0;i<COUNT;i++)if(NAMES[i].equalsIgnoreCase(name))return i;
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Binding> defaults(int action){
        ArrayList<Binding> list=new ArrayList<Binding>(2);
        switch(action){
            case UP:list.add(Binding.key(KeyEvent.KEYCODE_DPAD_UP));list.add(Binding.hat(1,-1));break;
            case DOWN:list.add(Binding.key(KeyEvent.KEYCODE_DPAD_DOWN));list.add(Binding.hat(1,1));break;
            case LEFT:list.add(Binding.key(KeyEvent.KEYCODE_DPAD_LEFT));list.add(Binding.hat(0,-1));break;
            case RIGHT:list.add(Binding.key(KeyEvent.KEYCODE_DPAD_RIGHT));list.add(Binding.hat(0,1));break;
            case A:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_A));break;
            case B:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_B));break;
            case X:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_X));break;
            case Y:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_Y));break;
            case L:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_L1));list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_L2));break;
            case R:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_R1));list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_R2));break;
            case START:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_START));break;
            case SELECT:list.add(Binding.key(KeyEvent.KEYCODE_BUTTON_SELECT));break;
            default:break;
        }
        return list;
    }

    public static String keyLabel(int code){
        switch(code){
            case KeyEvent.KEYCODE_BUTTON_A:return "A";
            case KeyEvent.KEYCODE_BUTTON_B:return "B";
            case KeyEvent.KEYCODE_BUTTON_C:return "C";
            case KeyEvent.KEYCODE_BUTTON_X:return "X";
            case KeyEvent.KEYCODE_BUTTON_Y:return "Y";
            case KeyEvent.KEYCODE_BUTTON_Z:return "Z";
            case KeyEvent.KEYCODE_BUTTON_L1:return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1:return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2:return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2:return "R2";
            case KeyEvent.KEYCODE_BUTTON_START:return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT:return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_MODE:return "MODE";
            case KeyEvent.KEYCODE_BUTTON_THUMBL:return "左摇杆按下";
            case KeyEvent.KEYCODE_BUTTON_THUMBR:return "右摇杆按下";
            case KeyEvent.KEYCODE_DPAD_UP:return "方向上";
            case KeyEvent.KEYCODE_DPAD_DOWN:return "方向下";
            case KeyEvent.KEYCODE_DPAD_LEFT:return "方向左";
            case KeyEvent.KEYCODE_DPAD_RIGHT:return "方向右";
            case KeyEvent.KEYCODE_DPAD_CENTER:return "方向中";
            case KeyEvent.KEYCODE_BACK:return "返回";
            case KeyEvent.KEYCODE_MENU:return "菜单";
            default:
                String text=KeyEvent.keyCodeToString(code);
                return text==null?"键 "+code:text.replace("KEYCODE_","");
        }
    }
}
