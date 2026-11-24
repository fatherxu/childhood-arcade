package org.childhood.arcade;
/** 复古游戏的方向二值化。阈值跟随每台设备校准后的死区。 */
public final class ControllerDirections {
 private static final float HAT=.5f;
 private ControllerDirections(){}
 private static float finite(float v){return Float.isNaN(v)||Float.isInfinite(v)?0:v;}
 private static boolean negative(float value,float threshold){return value<-threshold;}
 private static boolean positive(float value,float threshold){return value>threshold;}
 /**
  * @param press 该设备实测（或校准）的死区，范围 0.2–0.8
  */
 public static boolean[] update(float hatX,float hatY,float stickX,float stickY,boolean[] old,float press){
  hatX=finite(hatX);hatY=finite(hatY);stickX=finite(stickX);stickY=finite(stickY);
  if(old==null||old.length!=4)old=new boolean[4];
  if(press<.2f)press=.2f;else if(press>.8f)press=.8f;
  boolean xHat=Math.abs(hatX)>HAT,yHat=Math.abs(hatY)>HAT;
  return new boolean[]{
   yHat?hatY<0:negative(stickY,old[0]?press-.05f:press),
   yHat?hatY>0:positive(stickY,old[1]?press-.05f:press),
   xHat?hatX<0:negative(stickX,old[2]?press-.05f:press),
   xHat?hatX>0:positive(stickX,old[3]?press-.05f:press)
  };
 }
}
