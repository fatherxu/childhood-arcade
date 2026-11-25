(function(){
'use strict';
var ctx=null,poll=null,lastKey='';
function native(){return ctx?ctx.getNative():null;}
function api(){var n=native();return n&&n.gamePackStatus?n:null;}
function status(){var n=api();if(!n)return null;try{return JSON.parse(n.gamePackStatus())||null;}catch(e){return null;}}
function busy(s){return s&&(s.state==='downloading'||s.state==='verifying'||s.state==='extracting'||s.state==='importing');}
function find(s,id){var list=s&&Array.isArray(s.packs)?s.packs:[];for(var i=0;i<list.length;i++)if(list[i].id===id)return list[i];return null;}
function phaseText(s){
 var pct=Math.max(0,Math.min(100,Number(s.progress)||0)),p=find(s,s.packId);
 var name=p?p.name:'游戏包';
 if(s.state==='downloading')return '正在下载 '+name+' · '+pct+'%'+(s.total?'（'+ctx.formatBytes(s.downloaded||0)+' / '+ctx.formatBytes(s.total)+'）':'');
 if(s.state==='verifying')return '正在校验 '+name+'…';
 if(s.state==='extracting')return '正在解压 '+name+' · '+pct+'%';
 if(s.state==='importing')return '正在导入游戏库 '+name+' · '+pct+'%';
 return '';
}
function progressHtml(s){
 if(!busy(s))return '';
 var pct=Math.max(0,Math.min(100,Number(s.progress)||0));
 return '<div class="pack-active" id="pack-active"><div class="pack-active-head"><strong id="pack-active-text">'+ctx.esc(phaseText(s))+'</strong>'+ctx.btn('取消','packs-cancel','','','packs-cancel')+'</div><div class="update-progress"><i id="pack-active-fill" style="width:'+pct+'%"></i></div><p class="notice">取消后已下载的部分会保留，下次从断点继续。</p></div>';
}
function tabButton(label,action,arg,on){
 return ctx.btn(label,action,arg,on?'primary':'','packs-tab:'+action+':'+arg);
}
function actionButton(s,p){
 if(busy(s))return '<span class="pack-wait">当前任务进行中</span>';
 if(p.installed&&!p.updateAvailable)return ctx.btn('重新导入','packs-reimport',p.id,'','packs-action:'+p.id);
 if(p.updateAvailable)return ctx.btn('更新并导入','packs-download',p.id,'primary','packs-action:'+p.id);
 return ctx.btn('下载并导入','packs-download',p.id,'primary','packs-action:'+p.id);
}
function row(s,p){
 var meta=p.count+' 款 · '+ctx.formatBytes(p.size||0);
 if(Number(p.parts)>1)meta+=' · 第 '+p.part+'/'+p.parts+' 包';
 if(p.platforms&&p.platforms.length===3)meta+=' · 三平台';
 else if(p.platform)meta+=' · '+p.platform;
 var badge=p.installed?('<em class="pack-badge'+(p.updateAvailable?' update':'')+'">'+(p.updateAvailable?'已导入 · 可更新':'已导入')+'</em>'):'';
 return '<div class="pack-row'+(p.installed?' installed':'')+'" data-id="'+ctx.esc(p.id)+'"><div class="pack-row-main"><strong>'+ctx.esc(p.name)+'</strong><small>'+ctx.esc(meta)+'</small>'+badge+'</div><div class="pack-row-action">'+actionButton(s,p)+'</div></div>';
}
function view(){
 var s=status();
 if(!s)return ctx.title('游戏包','设置')+'<main class="pack-page"><div class="cartridge settings-panel"><p class="notice">电脑预览不能下载游戏包，请在电视上使用。</p></div></main>';
 var tier=ctx.state.packTier==='extended'?'extended':'featured';
 var platform=ctx.state.packPlatform||'all';
 var all=Array.isArray(s.packs)?s.packs:[];
 var tiers=s.tiers||[];
 var packs=all.filter(function(p){return p.tier===tier&&(tier!=='extended'||platform==='all'||p.platform===platform);});
 var featured=all.filter(function(p){return p.tier==='featured';});
 var extended=all.filter(function(p){return p.tier==='extended';});
 var featuredGames=featured.reduce(function(n,p){return n+(Number(p.count)||0);},0);
 var extendedGames=extended.reduce(function(n,p){return n+(Number(p.count)||0);},0);
 var head='<div class="pack-head"><h3>游戏包</h3><p class="notice">精选包按游戏类别打包；扩展包收录全集横版，按平台和类别拆分。每个包下载后会校验并导入游戏库，已存在的游戏自动跳过。</p><div class="pack-tabs">'+tabButton('精选包 '+featured.length,'packs-tier','featured',tier==='featured')+tabButton('扩展包 '+extended.length,'packs-tier','extended',tier==='extended')+ctx.btn('刷新目录','packs-refresh')+'</div>';
 if(tier==='extended')head+='<div class="pack-tabs pack-platforms">'+tabButton('全部','packs-platform','all',platform==='all')+tabButton('FC','packs-platform','FC',platform==='FC')+tabButton('MD','packs-platform','MD',platform==='MD')+tabButton('SFC','packs-platform','SFC',platform==='SFC')+'</div>';
 head+='<p class="pack-summary">精选 '+featuredGames+' 款 · 扩展 '+extendedGames+' 款 · 已导入 '+ctx.esc(s.installedGames||0)+' 款 · 电视剩余 '+ctx.esc(ctx.formatBytes(s.freeBytes||0))+' · 目录 '+(s.catalogVersion?('v'+ctx.esc(s.catalogVersion)):'未加载')+(s.catalogSource==='remote'?'（在线）':s.catalogSource==='bundled'?'（内置）':s.catalogSource==='cache'?'（缓存）':'')+'</p></div>';
 if(s.catalogError)head+='<p class="check-error">'+ctx.esc(s.catalogError)+'</p>';
 if(Number(s.minVersionCode)>Number(s.appVersionCode))head+='<p class="check-error">当前应用版本过低，请先在「软件更新」中升级后再下载游戏包。</p>';
 if(s.error&&!busy(s)&&s.error!==s.catalogError)head+='<p class="check-error">'+ctx.esc(s.error)+'</p>';
 if(s.state==='cancelled')head+='<p class="notice">已取消，已下载的部分会保留，可再次下载继续。</p>';
 head+=progressHtml(s);
 var body=packs.length?'<div class="pack-list">'+packs.map(function(p){return row(s,p);}).join('')+'</div>':'<div class="cartridge settings-panel"><p class="notice">这个分类暂时没有游戏包。</p></div>';
 return ctx.title('游戏包','设置')+'<main class="pack-page">'+head+body+'</main>';
}
function stop(){if(poll){clearInterval(poll);poll=null;}}
function startPoll(){stop();poll=setInterval(tick,900);}
function tick(){
 if(!ctx||ctx.state.page!=='packs'){stop();return;}
 var s=status();if(!s)return;
 var key=[s.state,s.packId,s.catalogVersion,s.catalogSource,s.installedPacks,s.installedGames,s.catalogError,s.error,s.minVersionCode].join('|');
 if(key!==lastKey){lastKey=key;ctx.render('packs:');return;}
 var pct=Math.max(0,Math.min(100,Number(s.progress)||0));
 var fill=document.getElementById('pack-active-fill'),text=document.getElementById('pack-active-text');
 if(fill&&busy(s))fill.style.width=pct+'%';
 if(text&&busy(s))text.textContent=phaseText(s);
}
function check(){
 var n=api();if(!n||!n.checkGamePacks){ctx.toast('当前版本不支持游戏包');return;}
 lastKey='';
 try{n.checkGamePacks();}catch(e){}
 ctx.render('packs:');
 startPoll();
}
function start(id,allow){
 var n=api();if(!n||!n.downloadGamePack)return;
 try{n.downloadGamePack(id,!!allow);}catch(e){ctx.toast('无法启动下载');return;}
 lastKey='';ctx.render('packs-cancel');startPoll();
}
function confirmDownload(id,reimport){
 var s=status();var p=find(s,id);if(!p)return;
 if(busy(s)){ctx.toast('请先等待当前游戏包完成');return;}
 var need=Number(p.extractBytes||p.size||0)*2+128*1024*1024;
 var warning=Number(s.freeBytes)>0&&Number(s.freeBytes)<need?'<p class="check-error">电视剩余空间可能不足，建议先清理后再下载。</p>':'';
 var text='<p>'+ctx.esc(p.name)+'</p><p class="notice">'+ctx.esc(p.count+' 款游戏 · 下载约 '+ctx.formatBytes(p.size||0)+'，导入后约占用 '+ctx.formatBytes(p.extractBytes||p.size||0)+'。')+'</p>'+warning+'<p class="notice">下载会逐文件校验并导入游戏库，已存在的游戏自动跳过；下载过程中可以取消，下次从断点继续。</p>';
 ctx.dialog(reimport?'重新下载并导入？':'下载这个游戏包？',text,[{text:'开始下载',primary:true,fn:function(){if(s.metered)metered(id);else start(id,false);}},{text:'取消',fn:function(){ctx.render('packs:');}}],1);
}
function metered(id){
 var s=status();var p=find(s,id);
 ctx.dialog('使用移动数据下载？','<p>当前网络可能按流量计费，'+ (p?ctx.esc(p.name)+' 约 '+ctx.esc(ctx.formatBytes(p.size||0)):'这个游戏包体积较大') +'。</p><p class="notice">建议连接 Wi-Fi 后下载；继续会消耗较多移动数据。</p>',[{text:'继续下载',fn:function(){start(id,true);}},{text:'取消',primary:true,fn:function(){ctx.render('packs:');}}],1);
}
function open(){
 var n=api();
 ctx.state.page='packs';ctx.state.packTier=ctx.state.packTier==='extended'?'extended':'featured';ctx.state.packPlatform=ctx.state.packPlatform||'all';
 lastKey='';
 if(!n){ctx.render('packs:');return;}
 startPoll();
 check();
}
function leave(){stop();lastKey='';}
function reload(){lastKey='';if(ctx&&ctx.state.page==='packs')ctx.render('packs:');}
function action(name,arg){
 if(name==='packs-tier'){ctx.state.packTier=arg==='extended'?'extended':'featured';ctx.state.packPlatform='all';ctx.render('packs-tier:'+arg);return true;}
 if(name==='packs-platform'){ctx.state.packPlatform=arg||'all';ctx.render('packs-platform:'+arg);return true;}
 if(name==='packs-download'){confirmDownload(arg,false);return true;}
 if(name==='packs-reimport'){confirmDownload(arg,true);return true;}
 if(name==='packs-cancel'){var n=api();if(n&&n.cancelGamePack){try{n.cancelGamePack();}catch(e){}}ctx.toast('正在取消游戏包');return true;}
 if(name==='packs-refresh'){check();return true;}
 return false;
}
window.ArcadePacksUI={init:function(c){ctx=c;},open:open,leave:leave,view:view,action:action,reload:reload};
}());
