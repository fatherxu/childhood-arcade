"""Build the split-resource Android release; provide explicit SDK/JDK and signing configuration."""
import argparse,hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--tools',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--signing-config',required=True);p.add_argument('--version',default='1.0.0');p.add_argument('--version-name');p.add_argument('--version-code',type=int,default=0);p.add_argument('--release-tag');p.add_argument('--repo',default='fatherxu/childhood-arcade');p.add_argument('--changelog',default='');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
for name in ['release-catalog.json','app/src/main/assets/index.html','app/src/main/assets/app.js','app/src/main/assets/style.css']:
 if not (root/name).is_file():raise SystemExit('缺少构建所需文件：'+name)
try:catalog=json.loads((root/'release-catalog.json').read_text(encoding='utf-8'))
except ValueError as error:raise SystemExit('release-catalog.json 不是有效JSON：'+str(error))
version_name=a.version_name or a.version
out=root/'build-production';out.mkdir(exist_ok=True);jdk=Path(a.jdk);tools=Path(a.tools);platform=Path(a.android_jar);config_path=Path(a.signing_config).resolve();sign=json.loads(config_path.read_text());keystore=Path(sign['keystore']);sign['keystore']=str(keystore if keystore.is_absolute() else config_path.parent/keystore);env=dict(os.environ,JAVA_HOME=str(jdk),ARCADE_SIGNING_PASSWORD=sign['password'])
def run(args):subprocess.run([str(x) for x in args],check=True,env=env)
def tool(name):
 for candidate in ([name+'.bat',name+'.exe'] if os.name=='nt' else [name]):
  path=tools/candidate
  if path.is_file():return path
 return tools/name
assets=out/'assets';shutil.rmtree(assets,ignore_errors=True);shutil.copytree(root/'app/src/main/assets',assets)
for rel in ['arcade','gba','md','sfc','vendor/fbneo','vendor/mgba','vendor/picodrive','vendor/snes9x']:shutil.rmtree(assets/rel,ignore_errors=True)
(assets/'catalog.js').write_text('window.GAME_CATALOG='+json.dumps(catalog,ensure_ascii=False)+';\n')
for name in ['classes','dex']:
 shutil.rmtree(out/name,ignore_errors=True);(out/name).mkdir()
run([tool('aapt2'),'compile','--dir',root/'app/src/main/res','-o',out/'resources.zip']);link=[tool('aapt2'),'link','-o',out/'base.apk','-I',platform,'--manifest',root/'app/src/main/AndroidManifest.xml','-A',assets,out/'resources.zip','--auto-add-overlay']
if a.version_code>0:link+=['--version-code',str(a.version_code),'--replace-version']
link+=['--version-name',version_name]
run(link)
run([jdk/'bin/javac','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',platform,'-cp',root/'app/libs/jna-5.13.0.jar','-d',out/'classes',*sorted((root/'app/src/main/java/org/childhood/arcade').glob('*.java'))]);run([jdk/'bin/jar','cf',out/'classes.jar','-C',out/'classes','.']);run([tool('d8'),'--lib',platform,'--min-api','23','--output',out/'dex',out/'classes.jar',root/'app/libs/jna-5.13.0.jar'])
shutil.copy2(out/'base.apk',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 z.write(out/'dex/classes.dex','classes.dex')
 for lib in (root/'app/src/main/jniLibs').rglob('*.so'):z.write(lib,'lib/'+lib.relative_to(root/'app/src/main/jniLibs').as_posix())
run([tool('zipalign'),'-f','-p','4',out/'unsigned.apk',out/'aligned.apk']);apk=out/('childhood-arcade-'+a.version+'.apk');run([jdk/'bin/java','-jar',tools/'lib/apksigner.jar','sign','--ks',sign['keystore'],'--ks-key-alias',sign['alias'],'--ks-pass','env:ARCADE_SIGNING_PASSWORD','--key-pass','env:ARCADE_SIGNING_PASSWORD','--out',apk,out/'aligned.apk']);run([jdk/'bin/java','-jar',tools/'lib/apksigner.jar','verify','--verbose',apk])
digest=hashlib.sha256(apk.read_bytes()).hexdigest();result={'apk':str(apk),'bytes':apk.stat().st_size,'sha256':digest,'versionCode':a.version_code,'versionName':version_name}
if a.version_code>0:
 tag=a.release_tag or ('v'+version_name)
 manifest={'schema':1,'versionCode':a.version_code,'versionName':version_name,'minSdk':23,'apk':{'url':'https://github.com/'+a.repo+'/releases/download/'+tag+'/'+apk.name,'size':apk.stat().st_size,'sha256':digest},'changelog':a.changelog}
 update=out/'update.json';update.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');result['updateJson']=str(update)
print(json.dumps(result))
