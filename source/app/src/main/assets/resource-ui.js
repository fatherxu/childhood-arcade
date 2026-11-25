(function(){
'use strict';
var ctx=null,poll=null;
function native(){return ctx?ctx.getNative():null;}
function status(){var api=native();if(!api||!api.resourcePackStatus)return null;try{return JSON.parse(api.resourcePackStatus())||null;}catch(e){return null;}}
function version(s){return s&&s.version?('v'+s.version):'';}
function note(){
 if(!native())return '电脑预览不能下载游戏资源，请在电视上使用';
 var s=status();
 if(!s)return '独立资源包已停止提供，游戏请使用「游戏包」下载';
 if(s.state==='checking')return '正在检查游戏资源…';
 if(s.state==='downloading'||s.state==='installing')return '正在处理游戏资源… '+Math.max(0,Math.min(100,Number(s.progress)||0))+'%';
 if(s.state==='available')return '发现游戏资源'+(s.version?' '+version(s):'')+'（'+ctx.formatBytes(s.packSize||s.total||0)+'），可更新';
 if(s.state==='current'||s.state==='ready')return '游戏资源已是最新'+(s.version?'（'+version(s)+'）':'')+'，可重新检查';
 if(s.state==='metered')return '游戏资源等待下载（当前网络按流量计费）';
 if(s.state==='unavailable')return '独立资源包已停止提供，游戏请使用「精选包与扩展包」下载';
 if(s.state==='error')return '游戏资源未安装完成，可重试';
 if(Number(s.installedFiles)>0)return '已安装 '+s.installedFiles+' 个游戏文件'+(s.installedVersion?'（资源 '+s.installedVersion+'）':'')+'，可检查更新';
 return '独立资源包已停止提供，游戏请使用「游戏包」下载';
}
function body(s){
 var name=s?String(s.state||'idle'):'idle';
 if(!s)return '<p class="notice">正在读取游戏资源状态…</p>';
 if(name==='checking')return '<p class="notice">正在检查游戏资源…</p>';
 if(name==='available')return '<p>发现游戏资源包 '+(s.version?ctx.esc(version(s))+' ':'')+'（约 '+ctx.esc(ctx.formatBytes(s.packSize||s.total||0))+'）。</p><p class="notice">安装会下载游戏与 BIOS 文件，逐个校验 SHA-256 后放入电视本机；已存在的相同文件会跳过。</p>'+(s.installedFiles?'<p class="notice">当前已安装 '+ctx.esc(s.installedFiles)+' 个文件。</p>':'');
 if(name==='downloading'){var pct=Math.max(0,Math.min(100,Number(s.progress)||0));return '<p>正在下载游戏资源… '+pct+'%</p><div class="update-progress"><i style="width:'+pct+'%"></i></div><p class="notice">'+ctx.esc(ctx.formatBytes(s.downloaded))+' / '+ctx.esc(ctx.formatBytes(s.total))+'</p><p class="notice">下载可以中断，下次会从已下载的部分继续。</p>';}
 if(name==='installing'){var ipct=Math.max(0,Math.min(100,Number(s.progress)||0));return '<p>正在解压并校验游戏文件… '+ipct+'%</p><div class="update-progress"><i style="width:'+ipct+'%"></i></div><p class="notice">'+(s.files?'已处理 '+ctx.esc(s.installed||0)+' / '+ctx.esc(s.files)+' 个文件。':'')+'请保持应用打开。</p>';}
 if(name==='ready')return '<p>游戏资源已安装并校验完成'+(s.files?'（'+ctx.esc(s.files)+' 个文件）':'')+'。</p><p class="notice">返回大厅即可游玩内置的街机、GBA、MD、SFC 游戏。</p>';
 if(name==='current')return '<p>游戏资源已是最新'+(s.version?'（'+ctx.esc(version(s))+'）':'')+'。</p><p class="notice">已安装 '+ctx.esc(s.installedFiles||s.files||0)+' 个文件。';
 if(name==='metered')return '<p>当前网络可能按流量计费。</p><p class="notice">'+ctx.esc(s.error||'建议连接 Wi-Fi 后下载')+'</p>';
 if(name==='cancelled')return '<p>已停止下载，下次会从已下载的部分继续。</p>';
 if(name==='unavailable')return '<p>独立资源包已停止提供。</p><p class="notice">NES、MD、SFC 游戏请到「游戏包」选择精选包或扩展包下载，导入后会直接出现在游戏库。</p>';
 if(name==='error')return '<p>游戏资源没有安装完成。</p><p class="check-error">'+ctx.esc(s.error||'未知错误')+'</p>';
 return '<p class="notice">独立资源包已停止提供，游戏请使用「游戏包」下载。</p>';
}
function buttons(s){
 var name=s?String(s.state||'idle'):'idle',close={text:'关闭',primary:true,fn:function(){stop();ctx.render('resources:');}};
 if(!s||name==='idle'||name==='checking'||name==='ready')return [close];
 if(name==='current')return [{text:'重新检查',fn:check},{text:'重新安装',fn:function(){reinstall(s);}},close];
 if(name==='available')return [{text:'下载并安装',primary:true,fn:function(){download(false);}},{text:'稍后',fn:function(){stop();ctx.render('resources:');}}];
 if(name==='metered')return [{text:'继续下载',fn:function(){download(true);}},close];
 if(name==='downloading'||name==='installing')return [{text:name==='installing'?'停止安装':'取消下载',primary:true,fn:function(){var api=native();if(api&&api.cancelResourcePack)api.cancelResourcePack();}}];
 if(name==='cancelled')return [{text:'继续下载',primary:true,fn:function(){download(false);}},close];
 if(name==='error')return [{text:'重试',primary:true,fn:function(){var s=status();if(s&&Number(s.total)>0)download(false);else check();}},close];
 return [close];
}
function show(){var s=status(),old=ctx.state.resourcePackInfo;ctx.state.resourcePackInfo=s;if(old&&s&&old.state===s.state&&old.progress===s.progress&&old.installed===s.installed&&old.error===s.error)return;ctx.dialog('游戏资源',body(s),buttons(s),0);}
function stop(){if(poll){clearInterval(poll);poll=null;}}
function startPoll(){stop();poll=setInterval(function(){if(!ctx.state.dialog||ctx.state.dialog.title!=='游戏资源'){stop();return;}var s=status();if(!s)return;show();if(s.state==='current'||s.state==='error'||s.state==='ready'||s.state==='cancelled'||s.state==='metered'||s.state==='unavailable')stop();},700);}
function reinstall(s){
 ctx.dialog('重新安装游戏资源？','<p>会重新下载约 '+ctx.esc(ctx.formatBytes(s.packSize||s.total||0))+' 的资源包，校验后覆盖本机游戏文件。存档不受影响。</p>',[{text:'开始重新安装',primary:true,fn:function(){download(false);}},{text:'取消',fn:function(){ctx.render('resources:');}}],1);
}
function check(){var api=native();if(!api||!api.checkResourcePack){ctx.toast('当前版本不支持应用内下载游戏资源');return;}try{api.checkResourcePack();}catch(e){}show();startPoll();}
function download(allow){
 var api=native();if(!api||!api.downloadResourcePack)return;
 var s=status();
 if(!allow&&s&&s.metered){ctx.dialog('使用移动数据下载？','<p>当前网络可能按流量计费，游戏资源约 '+ctx.esc(ctx.formatBytes(s.packSize||s.total||0))+'。</p><p class="notice">建议连接 Wi-Fi 后下载；继续会消耗较多移动数据。</p>',[{text:'继续下载',fn:function(){download(true);}},{text:'取消',primary:true,fn:function(){ctx.render('resources:');}}],1);return;}
 api.downloadResourcePack(!!allow);show();startPoll();
}
function open(){var api=native();if(!api){ctx.toast('电脑预览不能下载游戏资源，请在电视上使用');return;}check();}
window.ArcadeResourcesUI={init:function(c){ctx=c;},note:note,open:open,stop:stop};
}());
