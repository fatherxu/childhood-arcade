/* 手机触屏虚拟按键：直接复用 engine.button 的按键索引，不修改原生输入层。 */
(function(){
  'use strict';
  var root=document.getElementById('touch-controls');
  if(!root)return;
  var handler=null,layout=null,visible=false,padEl=null,padMask=0;
  var cfg={},zoneEls={};
  var touches={},counts=[0,0,0,0,0,0,0,0,0,0,0,0],btns={};
  /* 按键索引与手柄一致：A0 B1 SELECT2 START3 UP4 DOWN5 LEFT6 RIGHT7 X8 Y9 L10 R11 */
  var LAYOUTS={
    nes:{buttons:[{i:1,t:'B'},{i:0,t:'A'}]},
    gba:{shoulders:true,buttons:[{i:1,t:'B'},{i:0,t:'A'}]},
    sfc:{shoulders:true,grid:'four',buttons:[{i:8,t:'X'},{i:9,t:'Y'},{i:1,t:'B'},{i:0,t:'A'}]},
    md:{grid:'six',buttons:[{i:0,t:'A'},{i:1,t:'B'},{i:8,t:'C'},{i:9,t:'X'},{i:10,t:'Y'},{i:11,t:'Z'}]},
    arcade:{grid:'six',coin:true,buttons:[{i:0,t:'A'},{i:1,t:'B'},{i:8,t:'X'},{i:9,t:'Y'},{i:10,t:'L'},{i:11,t:'R'}]}
  };
  function make(tag,cls,text){var el=document.createElement(tag);if(cls)el.className=cls;if(text!==undefined)el.textContent=text;return el;}
  function paintAction(action){
    var el=btns[action];
    if(!el)return;
    if(counts[action]>0)el.className=el.className.indexOf('pressed')<0?el.className+' pressed':el.className;
    else el.className=el.className.replace(/\s*pressed/g,'');
  }
  function press(action){
    if(action<0||action>11)return;
    counts[action]++;
    if(counts[action]===1&&handler)handler(action,true);
    paintAction(action);
  }
  function release(action){
    if(action<0||action>11||counts[action]<1)return;
    counts[action]--;
    if(counts[action]===0&&handler)handler(action,false);
    paintAction(action);
  }
  function setPadMask(mask){
    for(var bit=0;bit<4;bit++){
      var next=(mask&(1<<bit))!==0,prev=(padMask&(1<<bit))!==0;
      if(next&&!prev)press(4+bit);
      else if(!next&&prev)release(4+bit);
    }
    padMask=mask;
    var arrows=padEl?padEl.getElementsByTagName('span'):[];
    for(var i=0;i<arrows.length;i++){
      var raw=arrows[i].getAttribute('data-act');
      if(raw===null)continue;
      var act=Number(raw);
      if(counts[act]>0){
        if(arrows[i].className.indexOf('pressed')<0)arrows[i].className+=' pressed';
      }else arrows[i].className=arrows[i].className.replace(/\s*pressed/g,'');
    }
  }
  function padMaskAt(x,y){
    if(!padEl)return 0;
    var r=padEl.getBoundingClientRect();
    if(!r.width||!r.height)return 0;
    var nx=(x-(r.left+r.width/2))/(r.width/2),ny=(y-(r.top+r.height/2))/(r.height/2),mask=0;
    if(ny<-.25)mask|=1;else if(ny>.25)mask|=2;
    if(nx<-.25)mask|=4;else if(nx>.25)mask|=8;
    return mask;
  }
  function findPad(node){
    while(node&&node!==root){
      if(node.getAttribute&&node.getAttribute('data-tc')==='pad')return node;
      node=node.parentNode;
    }
    return null;
  }
  function findButton(node){
    while(node&&node!==root){
      var value=node.getAttribute&&node.getAttribute('data-tc');
      if(value!==null&&value!==undefined&&value!=='pad'){
        var action=Number(value);
        if(!isNaN(action)&&action>=0&&action<=11)return action;
      }
      node=node.parentNode;
    }
    return -1;
  }
  /* 摇杆视觉：只移动中间球，方向判定仍按触点位置。 */
  function moveStick(x,y){
    if(!padEl)return;
    var stick=padEl.querySelector('.tc-stick');
    if(!stick)return;
    var r=padEl.getBoundingClientRect();
    if(!r.width)return;
    var nx=(x-(r.left+r.width/2))/(r.width/2),ny=(y-(r.top+r.height/2))/(r.height/2),len=Math.sqrt(nx*nx+ny*ny);
    if(len>1){nx/=len;ny/=len;}
    stick.style.transform='translate(calc(-50% + '+(nx*r.width*.2).toFixed(1)+'px),calc(-50% + '+(ny*r.height*.2).toFixed(1)+'px))';
    padEl.classList.add('active');
  }
  function resetStick(){
    if(!padEl)return;
    var stick=padEl.querySelector('.tc-stick');
    if(stick)stick.style.transform='';
    padEl.classList.remove('active');
  }
  function padTouches(){var n=0;for(var id in touches)if(touches[id]&&touches[id].pad)n++;return n;}
  function recomputePad(){
    var mask=0,id;
    for(id in touches)if(touches[id]&&touches[id].pad)mask|=touches[id].mask||0;
    setPadMask(mask);
  }
  function releaseAll(){
    for(var action=0;action<12;action++){
      if(counts[action]>0){
        counts[action]=0;
        if(handler)handler(action,false);
      }
      paintAction(action);
    }
    padMask=0;
    touches={};
    resetStick();
    if(padEl){
      var arrows=padEl.getElementsByTagName('span');
      for(var i=0;i<arrows.length;i++)arrows[i].className=arrows[i].className.replace(/\s*pressed/g,'');
    }
  }
  function addAction(box,item,extra){
    var el=make('div','tc-btn '+(extra||''),item.t);
    el.setAttribute('data-tc',String(item.i));
    el.setAttribute('role','button');
    el.setAttribute('aria-label',item.t);
    box.appendChild(el);
    btns[item.i]=el;
  }
  /* 自定义布局：按屏幕比例定位每组按键，缩放不改变触控语义。 */
  function applyLayout(){
    if(!zoneEls.dpad)return;
    var fallback={dpad:{x:.15,y:.72,s:1.05},ab:{x:.87,y:.74,s:1.15},pills:{x:.5,y:.9,s:1.1}};
    for(var key in zoneEls){
      var el=zoneEls[key],c=cfg[key]||{},d=fallback[key];
      var x=typeof c.x==='number'?c.x:d.x,y=typeof c.y==='number'?c.y:d.y,s=typeof c.s==='number'?c.s:d.s;
      el.style.left=(x*100)+'%';
      el.style.top=(y*100)+'%';
      el.style.right='auto';
      el.style.bottom='auto';
      el.style.transformOrigin='50% 50%';
      el.style.transform='translate(-50%,-50%) scale('+s+')';
    }
  }
  function build(kind){
    var spec=LAYOUTS[kind]||LAYOUTS.nes;
    root.innerHTML='';root.setAttribute('data-layout',kind);
    btns={};padEl=null;
    var left=make('div','tc-zone tc-left');
    padEl=make('div','tc-dpad');
    padEl.setAttribute('data-tc','pad');
    padEl.setAttribute('role','group');
    padEl.setAttribute('aria-label','方向键');
    var arrows=[['up',4],['right',7],['down',5],['left',6]];
    for(var i=0;i<arrows.length;i++){
      var arrow=make('span','tc-arrow tc-arrow-'+arrows[i][0],arrows[i][0]==='up'?'▲':arrows[i][0]==='down'?'▼':arrows[i][0]==='left'?'◀':'▶');
      arrow.setAttribute('data-act',String(arrows[i][1]));
      padEl.appendChild(arrow);
    }
    padEl.appendChild(make('span','tc-stick'));
    left.appendChild(padEl);root.appendChild(left);
    var right=make('div','tc-zone tc-right');
    if(spec.shoulders){
      var shoulders=make('div','tc-shoulders');
      addAction(shoulders,{i:10,t:'L'},'tc-shoulder');
      addAction(shoulders,{i:11,t:'R'},'tc-shoulder');
      right.appendChild(shoulders);
    }
    var box=make('div','tc-buttons '+(spec.grid==='six'?'tc-six':spec.grid==='four'?'tc-four':'tc-two'));
    for(var b=0;b<spec.buttons.length;b++)addAction(box,spec.buttons[b]);
    right.appendChild(box);root.appendChild(right);
    var center=make('div','tc-zone tc-center'),pills=make('div','tc-pills');
    addAction(pills,{i:2,t:spec.coin?'投币':'SELECT'},'tc-pill');
    addAction(pills,{i:3,t:'START'},'tc-pill');
    center.appendChild(pills);root.appendChild(center);
    zoneEls={dpad:left,ab:right,pills:center};
    applyLayout();
  }
  function start(event){
    if(!visible)return;
    if(event.preventDefault)event.preventDefault();
    var list=event.changedTouches||[];
    for(var i=0;i<list.length;i++){
      var touch=list[i],target=touch.target||event.target,id=touch.identifier;
      if(findPad(target)){
        touches[id]={pad:true,mask:padMaskAt(touch.clientX,touch.clientY)};
        moveStick(touch.clientX,touch.clientY);
        recomputePad();
      }else{
        var action=findButton(target);
        if(action>=0){touches[id]={action:action};press(action);}
      }
    }
  }
  function move(event){
    if(!visible)return;
    if(event.preventDefault)event.preventDefault();
    var list=event.changedTouches||[],changed=false;
    for(var i=0;i<list.length;i++){
      var touch=list[i],record=touches[touch.identifier];
      if(!record||!record.pad)continue;
      var mask=padMaskAt(touch.clientX,touch.clientY);
      moveStick(touch.clientX,touch.clientY);
      if(mask!==record.mask){record.mask=mask;changed=true;}
    }
    if(changed)recomputePad();
  }
  function end(event){
    if(event.preventDefault)event.preventDefault();
    var list=event.changedTouches||[];
    for(var i=0;i<list.length;i++){
      var touch=list[i],record=touches[touch.identifier];
      if(!record)continue;
      delete touches[touch.identifier];
      if(record.pad){recomputePad();if(!padTouches())resetStick();}else release(record.action);
    }
  }
  root.addEventListener('touchstart',start,false);
  root.addEventListener('touchmove',move,false);
  root.addEventListener('touchend',end,false);
  root.addEventListener('touchcancel',end,false);
  root.addEventListener('contextmenu',function(event){if(event.preventDefault)event.preventDefault();},false);
  window.addEventListener('pagehide',releaseAll,false);
  window.addEventListener('blur',releaseAll,false);
  window.ArcadeTouch={
    init:function(fn){handler=typeof fn==='function'?fn:null;},
    setLayout:function(kind){
      kind=kind||'nes';
      if(layout===kind)return;
      releaseAll();layout=kind;build(kind);
    },
    setVisible:function(value){
      value=!!value;
      if(value===visible)return;
      if(!value)releaseAll();
      visible=value;root.hidden=!value;
      if(value)applyLayout();
      document.documentElement.className=document.documentElement.className.replace(/\s*touch-playing/g,'')+(value?' touch-playing':'');
    },
    releaseAll:releaseAll,
    isVisible:function(){return visible;},
    getConfig:function(){return cfg;},
    setConfig:function(value){cfg=value||{};applyLayout();},
    capable:function(){return ('ontouchstart' in window)||(navigator.maxTouchPoints||0)>0;}
  };
}());
