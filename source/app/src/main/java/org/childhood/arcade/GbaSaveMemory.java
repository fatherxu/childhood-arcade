package org.childhood.arcade;
/** mGBA 0.11-212-7a12d6d 的 libretro 内核固定按 GBA_SIZE_FLASH1M（128 KiB）分配存档。
 * 启动或恢复即时存档后，检测到的 EEPROM/Flash 长度可能变化。
 * 因此保留整个缓冲区，绝不截断已有的 128 KiB 存档。
 */
public final class GbaSaveMemory {
 public static final int CAPACITY=128*1024;
 private GbaSaveMemory(){}
 public static int capacity(int reported){if(reported<0||reported>CAPACITY)throw new IllegalArgumentException("GBA存档内存大小无效");return CAPACITY;}
 public static byte[] expand(byte[] saved,int reported){int size=capacity(reported);if(saved==null||saved.length<1||saved.length>size)throw new IllegalArgumentException("GBA存档大小无效，原文件已保留");byte[] out=new byte[size];java.util.Arrays.fill(out,(byte)0xff);System.arraycopy(saved,0,out,0,saved.length);return out;}
}
