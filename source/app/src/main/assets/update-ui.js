(function(){
'use strict';
var ctx=null,poll=null,available='';
function native(){return ctx?ctx.getNative():null;}
function status(){var api=native();if(!api||!api.updateStatus)return null;try{return JSON.parse(api.updateStatus())||null;}catch(e){return null;}}
function versionText(){var api=native();return api&&api.appVersion?('v'+api.appVersion()):'电脑预览';}
function note(){var badge=available?('，<span class="update-badge">有新版本 v'+ctx.esc(available)+'</span>'):'';return '当前版本 '+versionText()+badge+'，检查 GitHub 上的新版本';}
function remember(s){if(!s)return;if(s.state==='available')available=String(s.versionName||'');else if(s.state==='current'||s.state==='ready'||s.state==='installing'||s.state==='cancelled')available='';}
function notes(text){if(!text)return '';return '<p class="notice update-notes">'+ctx.esc(text).replace(/\n/g,'<br>')+'</p>';}
function body(s){
 var name=s?String(s.state||'idle'):'idle';
 if(!s)return '<p class="notice">正在读取更新状态…</p>';
 if(name==='checking')return '<p class="notice">正在检查最新版本…</p>';
 if(name==='current')return '<p>当前已是最新版本 v'+ctx.esc(s.currentVersionName||'')+'。</p>';
 if(name==='available')return '<p>发现新版本 <strong>v'+ctx.esc(s.versionName||'')+'</strong>（当前 v'+ctx.esc(s.currentVersionName||'')+'）。</p><p class="notice">更新包 '+ctx.esc(ctx.formatBytes(s.size))+'。下载后会校验大小、SHA-256、包名和签名，再交给系统安装。安装前会自动备份现有存档。</p>'+(ctx.state.current?'<p class="notice">安装会关闭当前游戏，建议先保存进度。</p>':'')+notes(s.changelog);
 if(name==='downloading'){var pct=Math.max(0,Math.min(100,Number(s.progress)||0));return '<p>正在下载更新包… '+pct+'%</p><div class="update-progress"><i style="width:'+pct+'%"></i></div><p class="notice">'+ctx.esc(ctx.formatBytes(s.downloaded))+' / '+ctx.esc(ctx.formatBytes(s.total))+'</p>';}
 if(name==='ready')return '<p>更新包已下载并校验通过。</p><p class="notice">点击“立即安装”会打开系统安装界面；安装完成后重新打开游戏厅即可。安装前会自动备份现有存档。</p>'+notes(s.changelog);
 if(name==='permission')return '<p>需要允许“童年游戏厅”安装未知来源的应用。</p><p class="notice">请在刚打开的设置页面中允许安装，然后回到这里点击“我已允许”。</p>';
 if(name==='installing')return '<p>已打开系统安装界面。</p><p class="notice">请按提示完成安装。如果安装被取消，可以重新检查更新。</p>';
 if(name==='metered')return '<p>当前网络可能按流量计费。</p><p class="notice">'+ctx.esc(s.error||'建议连接 Wi-Fi 后下载')+'</p>';
 if(name==='cancelled')return '<p>已取消下载，下次会从已下载的部分继续。</p>';
 if(name==='error')return '<p>更新没有完成。</p><p class="check-error">'+ctx.esc(s.error||'未知错误')+'</p>';
 return '<p class="notice">当前版本 v'+ctx.esc(s.currentVersionName||'')+'，可以检查是否有新版本。</p>';
}
function buttons(s){
 var name=s?String(s.state||'idle'):'idle',close={text:'关闭',primary:true,fn:function(){stop();ctx.render('update:');}};
 if(!s||name==='idle'||name==='cancelled'||name==='checking')return [close];
 if(name==='current')return [{text:'重新检查',fn:check},close];
 if(name==='available')return [{text:'下载并安装',primary:true,fn:function(){download(false);}},{text:'稍后',fn:function(){stop();ctx.render('update:');}}];
 if(name==='metered')return [{text:'继续下载',fn:function(){download(true);}},close];
 if(name==='downloading')return [{text:'取消下载',primary:true,fn:function(){var api=native();if(api&&api.cancelUpdate)api.cancelUpdate();}}];
 if(name==='ready')return [{text:'立即安装',primary:true,fn:install},{text:'稍后',fn:function(){stop();ctx.render('update:');}}];
 if(name==='permission')return [{text:'我已允许',primary:true,fn:install},{text:'去设置',fn:install},close];
 if(name==='installing')return [close];
 if(name==='error')return [{text:'重试',primary:true,fn:check},close];
 return [close];
}
function show(){var s=status();ctx.state.updateInfo=s;remember(s);ctx.dialog('软件更新',body(s),buttons(s),0);}
function stop(){if(poll){clearInterval(poll);poll=null;}}
function startPoll(){stop();poll=setInterval(function(){if(!ctx.state.dialog){stop();return;}var s=status();if(!s)return;remember(s);var old=ctx.state.updateInfo;ctx.state.updateInfo=s;var changed=!old||old.state!==s.state||(s.state==='downloading'&&Number(s.progress)!==Number(old.progress));if(changed)show();if(s.state==='current'||s.state==='error'||s.state==='ready'||s.state==='permission'||s.state==='installing'||s.state==='cancelled'||s.state==='metered')stop();},600);}
function check(){var api=native();if(!api||!api.checkUpdate){ctx.toast('当前版本不支持应用内更新，请手动安装新版本');return;}try{api.checkUpdate();}catch(e){}show();startPoll();}
function download(allow){
 var api=native();if(!api||!api.startUpdateDownload)return;
 var s=status();
 if(!allow&&s&&s.metered){ctx.dialog('使用移动数据下载？','<p>当前网络可能按流量计费，更新包约 '+ctx.esc(ctx.formatBytes(s.size))+'。</p><p class="notice">建议连接 Wi-Fi 后下载；继续会消耗移动数据。</p>',[{text:'继续下载',fn:function(){download(true);}},{text:'取消',primary:true,fn:function(){ctx.render('update:');}}],1);return;}
 api.startUpdateDownload(!!allow);startPoll();show();
}
function install(){var api=native();if(!api||!api.installUpdate)return;api.installUpdate();startPoll();show();}
function open(){var api=native();if(!api){ctx.toast('电脑预览不能检查更新，请在电视上使用');return;}check();}
function autoCheck(){
 var api=native();if(!api||!api.checkUpdate||!api.updateStatus)return;
 var s=status();
 if(s&&Number(s.lastCheck)>0&&Date.now()-Number(s.lastCheck)<86400000){remember(s);return;}
 try{api.checkUpdate();}catch(e){return;}
 var tries=0,timer=setInterval(function(){
  tries++;var v=status();if(!v)return;
  remember(v);
  if(v.state==='available'){clearInterval(timer);ctx.toast('发现新版本 v'+(v.versionName||'')+'，可在设置里更新');return;}
  if(v.state==='current'||v.state==='error'||tries>24)clearInterval(timer);
 },800);
}
function startup(){try{var api=native();if(api&&api.updateLaunchResult){var r=JSON.parse(api.updateLaunchResult());if(r&&r.updated)ctx.toast('已更新到 v'+(r.versionName||''));}}catch(e){}autoCheck();}
window.ArcadeUpdateUI={init:function(c){ctx=c;setTimeout(startup,1200);},note:note,open:open,stop:stop};
}());
