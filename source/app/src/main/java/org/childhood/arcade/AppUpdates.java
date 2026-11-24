package org.childhood.arcade;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.Settings;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Update manifest check, HTTPS download, SHA-256/signature verification and system installer handoff. */
public final class AppUpdates {
    static final String MANIFEST_URL = "https://github.com/fatherxu/childhood-arcade/releases/latest/download/update.json";
    static final String PREFS = "updates";
    static final String KEY_EXPECTED = "expected_version_code";
    static final String KEY_LAST_CHECK = "last_check_ms";
    private static final long MAX_APK = 200L * 1024 * 1024;
    private static final long MIN_FREE = 50L * 1024 * 1024;
    private static final long MAX_MANIFEST = 64 * 1024;
    private static final int MAX_REDIRECTS = 4;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private static volatile SSLSocketFactory tlsFactory;

    private final Context context;
    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private volatile String phase = "idle";
    private volatile String error;
    private volatile JSONObject manifest;
    private volatile int progress;
    private volatile long downloaded;
    private volatile long total;
    private volatile File apkFile;
    private volatile Thread worker;
    private volatile boolean cancel;

    AppUpdates(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, 0);
        cleanCache();
    }

    int installedVersionCode() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    String installedVersionName() {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    synchronized void check() {
        if ("checking".equals(phase) || "downloading".equals(phase)) return;
        cancel = false;
        error = null;
        manifest = null;
        apkFile = null;
        progress = 0;
        downloaded = 0;
        total = 0;
        phase = "checking";
        start(new Runnable() {
            public void run() { doCheck(); }
        });
    }

    synchronized void download(boolean allowMetered) {
        final JSONObject current = manifest;
        if (current == null) {
            phase = "error";
            error = "请先检查更新";
            return;
        }
        if ("downloading".equals(phase)) return;
        if (metered() && !allowMetered) {
            phase = "metered";
            error = "当前网络按流量计费，建议在 Wi-Fi 下下载更新";
            return;
        }
        cancel = false;
        error = null;
        progress = 0;
        downloaded = 0;
        total = current.optJSONObject("apk") == null ? 0 : current.optJSONObject("apk").optLong("size", 0);
        phase = "downloading";
        start(new Runnable() {
            public void run() { doDownload(current); }
        });
    }

    boolean metered() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && manager.isActiveNetworkMetered();
        } catch (Throwable e) {
            return false;
        }
    }

    void cancel() {
        cancel = true;
        Thread current = worker;
        if (current != null) current.interrupt();
    }

    private void start(Runnable task) {
        Thread thread = new Thread(task, "arcade-update");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void doCheck() {
        try {
            JSONObject json = new JSONObject(new String(fetch(MANIFEST_URL, MAX_MANIFEST), "UTF-8"));
            validate(json);
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            if (json.optInt("versionCode", 0) <= installedVersionCode()) {
                manifest = null;
                phase = "current";
            } else {
                manifest = json;
                phase = "available";
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    private void doDownload(JSONObject json) {
        File temp = null;
        try {
            JSONObject apk = json.optJSONObject("apk");
            long size = apk.optLong("size", 0);
            long free = new StatFs(context.getCacheDir().getAbsolutePath()).getAvailableBytes();
            if (free < size + MIN_FREE) throw new IOException("电视剩余空间不足，需要约 " + ((size + MIN_FREE) / 1024 / 1024) + " MB");
            File dir = new File(context.getCacheDir(), "update");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新缓存目录");
            File target = new File(dir, "arcade-" + json.optInt("versionCode", 0) + ".apk");
            temp = new File(dir, "arcade.apk.part");
            downloadAll(apk.optString("url", ""), temp, size);
            if (cancel) throw new IOException("已取消更新");
            try {
                verifyPackage(temp, json);
            } catch (Exception e) {
                temp.delete();
                throw e;
            }
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!temp.renameTo(target)) throw new IOException("无法保存更新包");
            }
            apkFile = target;
            temp = null;
            downloaded = target.length();
            total = target.length();
            progress = 100;
            prefs.edit().putString(UpdateProvider.KEY_PATH, target.getAbsolutePath()).apply();
            phase = "ready";
        } catch (Throwable e) {
            if (cancel) {
                phase = "cancelled";
            } else {
                fail(e);
            }
        }
    }

    private void validate(JSONObject json) throws IOException {
        int versionCode = json.optInt("versionCode", -1);
        String versionName = json.optString("versionName", "");
        JSONObject apk = json.optJSONObject("apk");
        if (versionCode <= 0 || versionName.length() == 0 || apk == null) throw new IOException("更新清单格式不正确");
        int minSdk = json.optInt("minSdk", 0);
        if (minSdk > 0 && Build.VERSION.SDK_INT < minSdk) throw new IOException("新版本需要 Android " + minSdk + " 及以上");
        String url = apk.optString("url", "");
        if (!url.startsWith("https://")) throw new IOException("更新地址必须使用 HTTPS");
        long size = apk.optLong("size", -1);
        if (size <= 0 || size > MAX_APK) throw new IOException("更新包大小异常");
        if (!apk.optString("sha256", "").toLowerCase(Locale.US).matches("[0-9a-f]{64}")) throw new IOException("更新包校验值异常");
    }

    private void verifyPackage(File file, JSONObject json) throws Exception {
        if (cancel) throw new IOException("已取消更新");
        JSONObject apk = json.optJSONObject("apk");
        long expectedSize = apk.optLong("size", -1);
        if (file.length() != expectedSize) throw new IOException("更新包大小不符");
        String expectedHash = apk.optString("sha256", "").toLowerCase(Locale.US);
        if (!sha256(file).equals(expectedHash)) throw new IOException("更新包校验失败");
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (archive == null || archive.applicationInfo == null) throw new IOException("更新包无法识别");
        if (!context.getPackageName().equals(archive.applicationInfo.packageName)) throw new IOException("更新包不属于本应用");
        if (archive.versionCode != json.optInt("versionCode", 0)) throw new IOException("更新包版本不符");
        PackageInfo self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        if (archive.signatures == null || self.signatures == null || archive.signatures.length != self.signatures.length) throw new IOException("更新包签名不符");
        for (int i = 0; i < archive.signatures.length; i++) {
            if (!archive.signatures[i].toCharsString().equals(self.signatures[i].toCharsString())) throw new IOException("更新包签名不符");
        }
    }

    void install(final Activity activity) {
        final File file = apkFile;
        if (file == null || !file.isFile()) {
            phase = "error";
            error = "没有可安装的更新包，请重新下载";
            return;
        }
        phase = "installing";
        error = null;
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                        phase = "permission";
                        Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(permission);
                        return;
                    }
                    if (manifest != null && manifest.optInt("versionCode", 0) > 0) {
                        backupSaves();
                        prefs.edit().putInt(KEY_EXPECTED, manifest.optInt("versionCode", 0)).apply();
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse("content://" + UpdateProvider.AUTHORITY + "/update.apk"), "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    phase = "installing";
                } catch (Exception e) {
                    phase = "error";
                    error = "无法打开系统安装器，请检查是否允许安装未知应用";
                }
            }
        });
    }

    /** One-shot result: the installed version reached the expected code, so the update succeeded. */
    JSONObject launchResult() {
        JSONObject out = new JSONObject();
        int expected = prefs.getInt(KEY_EXPECTED, 0);
        if (expected <= 0) return out;
        try {
            int now = installedVersionCode();
            if (now >= expected) {
                out.put("updated", true);
                out.put("versionCode", now);
                out.put("versionName", installedVersionName());
                cleanApk();
            } else {
                deleteTree(new File(context.getFilesDir(), "store.update-backup"));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().remove(KEY_EXPECTED).remove(UpdateProvider.KEY_PATH).apply();
        return out;
    }

    String status() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", phase);
            out.put("progress", progress);
            out.put("downloaded", downloaded);
            out.put("total", total);
            out.put("currentVersionCode", installedVersionCode());
            out.put("currentVersionName", installedVersionName());
            out.put("lastCheck", prefs.getLong(KEY_LAST_CHECK, 0));
            out.put("metered", metered());
            out.put("error", error == null ? JSONObject.NULL : error);
            JSONObject current = manifest;
            if (current != null) {
                out.put("versionCode", current.optInt("versionCode", 0));
                out.put("versionName", current.optString("versionName", ""));
                out.put("changelog", current.optString("changelog", ""));
                JSONObject apk = current.optJSONObject("apk");
                if (apk != null) out.put("size", apk.optLong("size", 0));
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private void fail(Throwable e) {
        phase = "error";
        String text = e == null ? "" : e.getMessage();
        if (text == null || text.length() == 0) text = e == null ? "更新失败" : e.getClass().getSimpleName();
        error = text;
    }

    private static X509TrustManager trustManager(TrustManagerFactory factory) throws IOException {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IOException("系统证书验证不可用");
    }

    /** Android 7.0 及更早版本不内置 ISRG Root X1，补充根证书后仍按系统规则校验证书链。 */
    private SSLSocketFactory tls() {
        SSLSocketFactory cached = tlsFactory;
        if (cached != null) return cached;
        synchronized (AppUpdates.class) {
            if (tlsFactory != null) return tlsFactory;
            try {
                TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                system.init((KeyStore) null);
                KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
                extra.load(null, null);
                InputStream in = context.getAssets().open("isrg-root-x1.pem");
                try {
                    extra.setCertificateEntry("isrg-root-x1", CertificateFactory.getInstance("X.509").generateCertificate(in));
                } finally {
                    in.close();
                }
                TrustManagerFactory supplemental = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                supplemental.init(extra);
                final X509TrustManager primary = trustManager(system), fallback = trustManager(supplemental);
                X509TrustManager combined = new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        X509Certificate[] first = primary.getAcceptedIssuers(), second = fallback.getAcceptedIssuers();
                        X509Certificate[] all = Arrays.copyOf(first, first.length + second.length);
                        System.arraycopy(second, 0, all, first.length, second.length);
                        return all;
                    }
                    public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        primary.checkClientTrusted(chain, type);
                    }
                    public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        try {
                            primary.checkServerTrusted(chain, type);
                        } catch (CertificateException e) {
                            fallback.checkServerTrusted(chain, type);
                        }
                    }
                };
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{combined}, null);
                tlsFactory = ssl.getSocketFactory();
            } catch (Throwable e) {
                tlsFactory = null;
            }
            return tlsFactory;
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            SSLSocketFactory factory = tls();
            if (factory != null) ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
        }
        return connection;
    }

    private byte[] fetch(String address, long limit) throws IOException {
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("只允许 HTTPS 更新地址");
            HttpURLConnection connection = open(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Accept", "application/json, application/octet-stream, */*");
            connection.setRequestProperty("User-Agent", "childhood-arcade-updater");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() == 0) throw new IOException("更新地址跳转缺少目标");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("更新服务器返回 " + code);
                long declared = contentLength(connection);
                if (declared > limit) throw new IOException("更新数据过大");
                return readLimited(connection.getInputStream(), limit);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("更新地址跳转次数过多");
    }

    private void downloadAll(String address, File target, long expected) throws IOException {
        long existing = target.isFile() ? target.length() : 0;
        if (existing > expected) {
            target.delete();
            existing = 0;
        }
        String current = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("只允许 HTTPS 下载地址");
            HttpURLConnection connection = open(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Accept", "application/octet-stream, */*");
            connection.setRequestProperty("User-Agent", "childhood-arcade-updater");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() == 0) throw new IOException("下载地址跳转缺少目标");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (code == 416 && existing > 0) throw new IOException("下载进度已失效，请重试");
                boolean append = existing > 0 && code == 206;
                if (!append) {
                    if (code != 200) throw new IOException("下载服务器返回 " + code);
                    existing = 0;
                }
                long declared = contentLength(connection);
                if (declared > 0 && existing + declared > expected) throw new IOException("更新包过大");
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(target, append);
                try {
                    byte[] buffer = new byte[262144];
                    long written = existing;
                    int count;
                    downloaded = written;
                    if (expected > 0) progress = (int) Math.min(99, written * 100 / expected);
                    while ((count = in.read(buffer)) != -1) {
                        if (cancel) throw new IOException("已取消更新");
                        written += count;
                        if (written > expected || written > MAX_APK) throw new IOException("更新包大小超出预期");
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

    private long contentLength(HttpURLConnection connection) {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int count;
        try {
            while ((count = in.read(buffer)) != -1) {
                totalRead += count;
                if (totalRead > limit) throw new IOException("更新数据过大");
                out.write(buffer, 0, count);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return out.toByteArray();
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buffer = new byte[262144];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        StringBuilder text = new StringBuilder();
        for (byte b : digest.digest()) text.append(String.format("%02x", b & 255));
        return text.toString();
    }

    private void backupSaves() {
        File store = new File(context.getFilesDir(), "store");
        if (!store.isDirectory()) return;
        File backup = new File(context.getFilesDir(), "store.update-backup");
        File temp = new File(context.getFilesDir(), "store.update-backup.tmp");
        deleteTree(temp);
        if (!copyTree(store, temp)) {
            deleteTree(temp);
            return;
        }
        deleteTree(backup);
        if (!temp.renameTo(backup)) deleteTree(temp);
    }

    private boolean copyTree(File from, File to) {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) return false;
            File[] children = from.listFiles();
            if (children == null) return true;
            for (File child : children) {
                if (!copyTree(child, new File(to, child.getName()))) return false;
            }
            return true;
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            try { if (in != null) in.close(); } catch (IOException ignored) {}
        }
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private void cleanApk() {
        File dir = new File(context.getCacheDir(), "update");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private void cleanCache() {
        File dir = new File(context.getCacheDir(), "update");
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (now - file.lastModified() > 7L * 24 * 60 * 60 * 1000) file.delete();
        }
    }
}
