package org.childhood.arcade;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/** 没有 DocumentsUI 的老电视上，用系统目录选择兜底导入游戏、导出/恢复备份。 */
final class LegacyFiles {
    static final int PERMISSION=90;
    static final int FOLDER=1,EXPORT=2,IMPORT=3;
    interface Host {
        void legacyImportFolder(File dir);
        void legacyExportBackup(File dir);
        void legacyRestoreBackup(File file);
        void legacyCancelled(int mode);
    }
    private final MainActivity activity;
    private final Host host;
    private int mode;
    LegacyFiles(MainActivity activity,Host host){this.activity=activity;this.host=host;}
    void startFolder(){start(FOLDER);}
    void startExport(){start(EXPORT);}
    void startImport(){start(IMPORT);}
    private void start(int value){
        if(Build.VERSION.SDK_INT>=29){host.legacyCancelled(value);return;}
        mode=value;
        String permission=mode==EXPORT?Manifest.permission.WRITE_EXTERNAL_STORAGE:Manifest.permission.READ_EXTERNAL_STORAGE;
        if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){
            activity.requestPermissions(new String[]{permission},PERMISSION);
            return;
        }
        roots();
    }
    void permissionResult(boolean granted){
        if(!granted){host.legacyCancelled(mode);return;}
        roots();
    }
    private void roots(){
        final ArrayList<File> dirs=new ArrayList<File>();
        File local=Environment.getExternalStorageDirectory();
        if(local!=null&&local.isDirectory())dirs.add(local);
        File[] storage=new File("/storage").listFiles();
        if(storage!=null)for(File f:storage){
            String name=f.getName();
            if(f.isDirectory()&&!name.equals("emulated")&&!name.equals("self")&&!name.equals("sdcard0"))dirs.add(f);
        }
        if(dirs.isEmpty()){host.legacyCancelled(mode);return;}
        CharSequence[] labels=new CharSequence[dirs.size()];
        for(int i=0;i<dirs.size();i++)labels[i]=(i==0?"电视本地存储":"存储设备 "+i)+" · "+dirs.get(i).getPath();
        String title=mode==FOLDER?"选择游戏文件夹的位置":mode==EXPORT?"选择备份保存位置":"选择备份文件位置";
        new AlertDialog.Builder(activity).setTitle(title).setItems(labels,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int n){browse(dirs.get(n),dirs.get(n));}
        }).setNegativeButton("取消",new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int n){host.legacyCancelled(mode);}
        }).setOnCancelListener(new DialogInterface.OnCancelListener(){
            public void onCancel(DialogInterface d){host.legacyCancelled(mode);}
        }).show();
    }
    private void browse(final File dir,final File root){
        File[] all=dir.listFiles();
        if(all==null){host.legacyCancelled(mode);return;}
        final ArrayList<File> files=new ArrayList<File>();
        for(File f:all){
            if(f.isHidden())continue;
            if(f.isDirectory())files.add(f);
            else if(mode==IMPORT&&f.isFile()&&f.getName().toLowerCase(Locale.US).endsWith(".json"))files.add(f);
        }
        Collections.sort(files,new Comparator<File>(){
            public int compare(File a,File b){
                if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        final boolean up=!dir.equals(root),pickHere=mode!=IMPORT;
        int actions=(pickHere?1:0)+(up?1:0);
        CharSequence[] names=new CharSequence[actions+files.size()];
        int index=0;
        if(pickHere)names[index++]=mode==FOLDER?"使用当前文件夹":"保存到当前文件夹";
        if(up)names[index++]="返回上一级";
        for(int i=0;i<files.size();i++){
            File f=files.get(i);
            names[index+i]=(f.isDirectory()?"[文件夹]  ":mode==IMPORT?"[备份]  ":"[文件]  ")+f.getName();
        }
        String title=mode==FOLDER?"选择包含游戏的文件夹":mode==EXPORT?"保存备份到当前文件夹":"选择备份文件";
        new AlertDialog.Builder(activity).setTitle(title).setItems(names,new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int n){
                int at=n;
                if(pickHere){
                    if(at==0){
                        if(mode==FOLDER)host.legacyImportFolder(dir);
                        else host.legacyExportBackup(dir);
                        return;
                    }
                    at--;
                }
                if(up){
                    if(at==0){browse(dir.getParentFile(),root);return;}
                    at--;
                }
                File f=files.get(at);
                if(f.isDirectory())browse(f,root);
                else host.legacyRestoreBackup(f);
            }
        }).setNegativeButton("返回位置",new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int n){roots();}
        }).setOnCancelListener(new DialogInterface.OnCancelListener(){
            public void onCancel(DialogInterface d){host.legacyCancelled(mode);}
        }).show();
    }
}
