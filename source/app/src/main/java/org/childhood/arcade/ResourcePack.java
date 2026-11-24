package org.childhood.arcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;

/** In-app game resource pack download and verified per-file installation. */
public final class ResourcePack {
    static final String PREFS = "resources";
    static final String KEY_SHA = "pack_sha256";
    static final String KEY_VERSION = "pack_version";
    static final String KEY_AT = "pack_installed_at";
    static final String KEY_FILES = "pack_files";
    private static final long MAX_PACK = 600L * 1024 * 1024;
    private static final long MAX_EXTRACT = 2L * 1024 * 1024 * 1024;
    private static final long MAX_ENTRY = 256L * 1024 * 1024;
    private static final int MAX_FILES = 5000;
    private static final long MIN_FREE = 64L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 4;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private final Context context;
    private final SharedPreferences prefs;
    private volatile String phase = "idle";
    private volatile String error;
    private volatile JSONObject pack;
    private volatile int progress;
    private volatile long downloaded;
    private volatile long total;
    private volatile int files;
    private volatile int installed;
    private volatile Thread worker;
    private volatile boolean cancel;

    ResourcePack(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, 0);
    }

    synchronized void check() {
        if ("checking".equals(phase) || "downloading".equals(phase) || "installing".equals(phase)) return;
        cancel = false;
        error = null;
        phase = "checking";
        start(new Runnable() {
            public void run() { doCheck(); }
        });
    }

    synchronized void download(boolean allowMetered) {
        if ("checking".equals(phase) || "downloading".equals(phase) || "installing".equals(phase)) return;
        final JSONObject current = pack;
        if (current == null) {
            phase = "error";
            error = "请先检查游戏资源";
            return;
        }
        if (AppUpdates.isMetered(context) && !allowMetered) {
            phase = "metered";
            error = "当前网络按流量计费，建议在 Wi-Fi 下下载游戏资源";
            return;
        }
        cancel = false;
        error = null;
        progress = 0;
        File part = partFile();
        downloaded = part.isFile() ? part.length() : 0;
        total = current.optLong("size", 0);
        phase = "downloading";
        start(new Runnable() {
            public void run() { doInstall(current); }
        });
    }

    void cancel() {
        cancel = true;
        Thread current = worker;
        if (current != null) current.interrupt();
    }

    String status() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", phase);
            out.put("progress", progress);
            out.put("downloaded", downloaded);
            out.put("total", total);
            out.put("installed", installed);
            out.put("files", files > 0 ? files : (pack == null ? 0 : pack.optInt("files", 0)));
            out.put("version", pack == null ? "" : pack.optString("version", ""));
            out.put("packSize", pack == null ? 0 : pack.optLong("size", 0));
            out.put("metered", AppUpdates.isMetered(context));
            out.put("freeBytes", freeSpace());
            out.put("installedVersion", prefs.getString(KEY_VERSION, ""));
            out.put("installedFiles", prefs.getInt(KEY_FILES, 0));
            out.put("installedAt", prefs.getLong(KEY_AT, 0));
            out.put("error", error == null ? JSONObject.NULL : error);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private void start(Runnable task) {
        Thread thread = new Thread(task, "arcade-resources");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void doCheck() {
        try {
            JSONObject json = new JSONObject(new String(AppUpdates.fetch(context, AppUpdates.MANIFEST_URL, 64 * 1024), "UTF-8"));
            JSONObject res = json.optJSONObject("resources");
            if (res == null) throw new IOException("更新清单没有游戏资源信息，请先更新应用");
            String url = res.optString("url", "");
            if (!url.startsWith("https://")) throw new IOException("资源地址必须使用 HTTPS");
            long size = res.optLong("size", -1);
            if (size <= 0 || size > MAX_PACK) throw new IOException("资源包大小异常");
            String sha = res.optString("sha256", "").toLowerCase(Locale.US);
            if (!sha.matches("[0-9a-f]{64}")) throw new IOException("资源包校验值异常");
            pack = res;
            total = size;
            files = res.optInt("files", 0);
            if (sha.equals(prefs.getString(KEY_SHA, ""))) {
                installed = prefs.getInt(KEY_FILES, 0);
                progress = 100;
                phase = "current";
            } else {
                phase = "available";
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    private void doInstall(JSONObject json) {
        File part = partFile();
        try {
            long size = json.optLong("size", 0);
            String sha = json.optString("sha256", "").toLowerCase(Locale.US);
            long existing = part.isFile() ? part.length() : 0;
            long free = freeSpace();
            if (free + existing < size + MIN_FREE) throw new IOException("电视剩余空间不足，还需要约 " + megabytes(size + MIN_FREE - free - existing) + " MB");
            File dir = part.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建资源缓存目录");
            if (!part.isFile() || part.length() != size) download(json.optString("url", ""), part, size);
            if (cancel) throw new IOException("已取消下载");
            if (part.length() != size) throw new IOException("资源包大小不符");
            if (!AppUpdates.sha256(part).equals(sha)) {
                part.delete();
                throw new IOException("资源包校验失败，请重试");
            }
            phase = "installing";
            progress = 0;
            downloaded = 0;
            total = size;
            install(part);
            part.delete();
            prefs.edit().putString(KEY_SHA, sha).putString(KEY_VERSION, json.optString("version", ""))
                    .putLong(KEY_AT, System.currentTimeMillis()).putInt(KEY_FILES, installed).commit();
            progress = 100;
            phase = "ready";
        } catch (Throwable e) {
            if (cancel) phase = "cancelled";
            else fail(e);
        }
    }

    private void install(File archive) throws Exception {
        ZipFile zip = new ZipFile(archive);
        try {
            ZipEntry manifestEntry = zip.getEntry("game-pack/manifest.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) throw new IOException("资源包缺少清单文件");
            JSONObject manifest = new JSONObject(new String(readLimited(zip.getInputStream(manifestEntry), 1024 * 1024), "UTF-8"));
            if (manifest.optInt("schema", 0) != 1) throw new IOException("资源包清单格式不正确");
            JSONArray list = manifest.optJSONArray("files");
            if (list == null || list.length() == 0 || list.length() > MAX_FILES) throw new IOException("资源包清单为空或文件过多");
            long expected = 0, needed = 0, largest = 0;
            HashSet<String> seen = new HashSet<String>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) throw new IOException("资源包清单格式不正确");
                String name = entry.optString("name", "");
                String path = entry.optString("path", "");
                String sha = entry.optString("sha256", "").toLowerCase(Locale.US);
                long bytes = entry.optLong("bytes", -1);
                if (bytes <= 0 || bytes > MAX_ENTRY) throw new IOException("资源包文件大小异常");
                if (!name.matches("[a-z0-9-]{1,60}\\.(zip|gba|md|sfc)") || !sha.matches("[0-9a-f]{64}")) throw new IOException("资源包清单包含无效条目");
                if (!seen.add(name)) throw new IOException("资源包清单包含重复文件：" + name);
                if (path.length() == 0 || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.contains("..") || !path.endsWith(name)) throw new IOException("资源包包含不安全路径");
                ZipEntry source = zip.getEntry("game-pack/" + path);
                if (source == null || source.isDirectory() || source.getSize() != bytes) throw new IOException("资源包文件不完整：" + name);
                expected += bytes;
                File target = target(name);
                if (!target.isFile()) needed += bytes;
                if (bytes > largest) largest = bytes;
            }
            if (expected > MAX_EXTRACT) throw new IOException("资源包解压总量超过限制");
            long free = freeSpace();
            if (free < needed + largest + MIN_FREE) throw new IOException("电视剩余空间不足，解压还需要约 " + megabytes(needed + largest + MIN_FREE - free) + " MB，请清理后重试");
            files = list.length();
            installed = 0;
            long done = 0;
            for (int i = 0; i < files; i++) {
                if (cancel) throw new IOException("已取消更新");
                JSONObject entry = list.optJSONObject(i);
                String name = entry.optString("name", "");
                String path = entry.optString("path", "");
                String sha = entry.optString("sha256", "").toLowerCase(Locale.US);
                long bytes = entry.optLong("bytes", -1);
                ZipEntry source = zip.getEntry("game-pack/" + path);
                File target = target(name);
                if (target.isFile() && AppUpdates.sha256(target).equals(sha)) {
                    installed++;
                    done += bytes;
                    report(done, expected);
                    continue;
                }
                File folder = target.getParentFile();
                if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建资源目录");
                File temp = File.createTempFile(".resource-", ".part", folder);
                boolean saved = false;
                try {
                    copy(zip.getInputStream(source), temp, sha, bytes);
                    if (!temp.renameTo(target)) {
                        if (!target.exists() || !target.delete() || !temp.renameTo(target)) throw new IOException("无法保存资源：" + name);
                    }
                    saved = true;
                } finally {
                    if (!saved) temp.delete();
                }
                installed++;
                done += bytes;
                report(done, expected);
            }
        } finally {
            zip.close();
        }
    }

    /** Copy one archive entry while checking its declared SHA-256 and size. */
    private void copy(InputStream in, File temp, String expectedSha, long expectedBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            byte[] buffer = new byte[262144];
            long totalRead = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (cancel) throw new IOException("已取消更新");
                totalRead += count;
                if (totalRead > expectedBytes) throw new IOException("资源文件大小超出预期");
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            if (totalRead != expectedBytes) throw new IOException("资源文件读取不完整");
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
        }
        StringBuilder text = new StringBuilder();
        for (byte b : digest.digest()) text.append(String.format("%02x", b & 255));
        if (!text.toString().equals(expectedSha)) throw new IOException("资源校验失败");
    }

    private void report(long done, long expected) {
        if (expected > 0) progress = (int) Math.min(99, done * 100 / expected);
        downloaded = done;
    }

    private void download(String address, File target, long expected) throws IOException {
        long existing = target.isFile() ? target.length() : 0;
        if (existing > expected) {
            target.delete();
            existing = 0;
        }
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("只允许 HTTPS 下载地址");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                javax.net.ssl.SSLSocketFactory factory = AppUpdates.trustedFactory(context);
                if (factory != null) ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Accept", "application/octet-stream, */*");
            connection.setRequestProperty("User-Agent", "childhood-arcade-resources");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() == 0) throw new IOException("下载地址跳转缺少目标");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (code == 416 && existing > 0) {
                    target.delete();
                    throw new IOException("下载进度已失效，请重试");
                }
                boolean append = existing > 0 && code == 206;
                if (!append) {
                    if (code != 200) throw new IOException("资源服务器返回 " + code);
                    existing = 0;
                }
                long declared = length(connection);
                if (declared > 0 && existing + declared > expected) {
                    if (append) target.delete();
                    throw new IOException(append ? "下载进度已失效，请重试" : "资源包过大");
                }
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(target, append);
                try {
                    byte[] buffer = new byte[262144];
                    long written = existing;
                    int count;
                    downloaded = written;
                    if (expected > 0) progress = (int) Math.min(99, written * 100 / expected);
                    while ((count = in.read(buffer)) != -1) {
                        if (cancel) throw new IOException("已取消下载");
                        written += count;
                        if (written > expected) throw new IOException("资源包大小超出预期");
                        out.write(buffer, 0, count);
                        downloaded = written;
                        if (expected > 0) progress = (int) Math.min(99, written * 100 / expected);
                    }
                    out.getFD().sync();
                } finally {
                    try { out.close(); } catch (IOException ignored) {}
                    try { in.close(); } catch (IOException ignored) {}
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("下载地址跳转次数过多");
    }

    private static long length(HttpURLConnection connection) {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int count;
        try {
            while ((count = in.read(buffer)) != -1) {
                totalRead += count;
                if (totalRead > limit) throw new IOException("资源清单过大");
                out.write(buffer, 0, count);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return out.toByteArray();
    }

    private File target(String name) {
        String folder = name.endsWith(".gba") ? "gba" : name.endsWith(".md") ? "md" : name.endsWith(".sfc") ? "sfc" : "arcade";
        return new File(new File(context.getFilesDir(), folder + "/roms"), name);
    }

    private File partFile() {
        return new File(new File(context.getCacheDir(), "resources"), "game-pack.part");
    }

    private long freeSpace() {
        try {
            return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static long megabytes(long bytes) {
        return Math.max(1, (bytes + 1048575) / 1048576);
    }

    private void fail(Throwable e) {
        phase = "error";
        String text = e == null ? "" : e.getMessage();
        if (text == null || text.length() == 0) text = e == null ? "游戏资源更新失败" : e.getClass().getSimpleName();
        error = text;
    }
}
