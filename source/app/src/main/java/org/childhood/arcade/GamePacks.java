package org.childhood.arcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.HttpsURLConnection;

/**
 * Downloadable game packs: a static catalog hosted next to the APK, chunked
 * zip archives, and an import step that reuses the folder importer.
 *
 * Packs are installed into the normal game library, so covers, version
 * grouping, save files and launcher integration all behave like a folder
 * import.  Only one pack is handled at a time.
 */
final class GamePacks {
    static final String CATALOG_URL = "https://github.com/fatherxu/childhood-arcade/releases/download/v1.1.0/packs.json";
    private static final String ASSET_CATALOG = "pack-catalog.json";
    private static final String PREFS = "game_packs";
    private static final String KEY_INSTALLED = "installed";
    private static final long MAX_CATALOG = 512L * 1024;
    private static final long MAX_PACK = 400L * 1024 * 1024;
    private static final long MAX_EXTRACT = 800L * 1024 * 1024;
    private static final long MAX_MANIFEST = 8L * 1024 * 1024;
    private static final long COVER_LIMIT = 128L * 1024;
    private static final long MIN_FREE = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 6000;
    private static final int MAX_REDIRECTS = 4;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final String[] PHASES_ACTIVE = {
            "downloading", "verifying", "extracting", "importing",
    };

    interface Progress {
        void onProgress(int done, int total);
    }

    interface Host {
        int[] importPack(File root, Progress progress) throws Exception;

        void cancelImport();

        void postJs(String code);
    }

    private final Context context;
    private final Host host;
    private final SharedPreferences prefs;
    private final File packsDir;
    private final File stagingRoot;
    private final File cacheRoot;
    private final Object lock = new Object();

    private volatile JSONObject catalog;
    private volatile String catalogSource = "";
    private volatile long catalogAt;
    private volatile String catalogError = "";

    private volatile String phase = "idle";
    private volatile String error = "";
    private volatile String activeId = "";
    private volatile int progress;
    private volatile long downloaded;
    private volatile long total;
    private volatile boolean cancel;
    private volatile Thread worker;

    GamePacks(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        packsDir = new File(this.context.getFilesDir(), "packs");
        stagingRoot = new File(packsDir, "staging");
        cacheRoot = new File(this.context.getCacheDir(), "packs");
        cleanup();
        loadLocalCatalog();
    }

    private void cleanup() {
        deleteTree(stagingRoot);
        if (cacheRoot.isDirectory()) {
            File[] files = cacheRoot.listFiles();
            if (files != null) {
                long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
                for (File file : files) {
                    String name = file.getName();
                    if ((name.endsWith(".part") || name.endsWith(".zip")) && file.lastModified() < cutoff) {
                        file.delete();
                    }
                }
            }
        }
    }

    private void loadLocalCatalog() {
        File file = new File(packsDir, "catalog.json");
        if (file.isFile()) {
            JSONObject value = readCatalog(file);
            if (value != null) {
                catalog = value;
                catalogSource = "cache";
                catalogAt = file.lastModified();
                return;
            }
            file.delete();
        }
        try {
            InputStream in = context.getAssets().open(ASSET_CATALOG);
            String text = new String(readLimited(in, MAX_CATALOG), "UTF-8");
            JSONObject value = new JSONObject(text);
            validateCatalog(value);
            catalog = value;
            catalogSource = "bundled";
            catalogAt = 0;
            writeCatalog(value);
        } catch (Throwable error) {
            Log.w("ArcadePacks", "bundled catalog unavailable: " + message(error));
        }
    }

    private JSONObject readCatalog(File file) {
        try {
            if (!file.isFile() || file.length() < 2 || file.length() > MAX_CATALOG) return null;
            String text = new String(readAll(new FileInputStream(file), MAX_CATALOG), "UTF-8");
            JSONObject value = new JSONObject(text);
            validateCatalog(value);
            return value;
        } catch (Throwable error) {
            return null;
        }
    }

    private void writeCatalog(JSONObject value) {
        try {
            if (!packsDir.isDirectory() && !packsDir.mkdirs()) return;
            File temp = new File(packsDir, "catalog.tmp");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(value.toString().getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            File dest = new File(packsDir, "catalog.json");
            if (dest.exists()) dest.delete();
            if (!temp.renameTo(dest)) temp.delete();
        } catch (Throwable error) {
            Log.w("ArcadePacks", "cannot cache catalog: " + message(error));
        }
    }

    void check() {
        synchronized (lock) {
            if (isBusy(phase) || "checking".equals(phase)) return;
            phase = "checking";
            error = "";
            catalogError = "";
        }
        Thread thread = new Thread(new Runnable() {
            public void run() {
                String failure = "";
                try {
                    byte[] data = AppUpdates.fetch(context, CATALOG_URL, MAX_CATALOG);
                    JSONObject value = new JSONObject(new String(data, "UTF-8"));
                    validateCatalog(value);
                    synchronized (lock) {
                        catalog = value;
                        catalogSource = "remote";
                        catalogAt = System.currentTimeMillis();
                        catalogError = "";
                        if ("checking".equals(phase)) phase = "ready";
                    }
                    writeCatalog(value);
                } catch (Throwable caught) {
                    failure = message(caught);
                }
                if (failure.length() > 0) {
                    synchronized (lock) {
                        catalogError = "在线目录获取失败：" + failure;
                        if ("checking".equals(phase)) {
                            if (catalog != null) {
                                phase = "ready";
                            } else {
                                phase = "error";
                                error = catalogError;
                            }
                        }
                    }
                }
            }
        }, "ArcadePackCatalog");
        thread.start();
    }

    void download(final String id, final boolean allowMetered) {
        synchronized (lock) {
            if (isBusy(phase)) return;
            JSONObject pack = packById(id);
            if (pack == null) {
                fail("没有找到这个游戏包");
                return;
            }
            int minimum = catalog != null ? catalog.optInt("minVersionCode", 0) : 0;
            if (minimum > appVersionCode()) {
                fail("当前应用版本过低，请先在设置中更新应用");
                return;
            }
            activeId = id;
            error = "";
            cancel = false;
            progress = 0;
            downloaded = 0;
            total = pack.optLong("size", 0);
            if (AppUpdates.isMetered(context) && !allowMetered) {
                phase = "metered";
                error = "当前网络可能按流量计费";
                return;
            }
            phase = "downloading";
        }
        Thread thread = new Thread(new Runnable() {
            public void run() {
                work(id);
            }
        }, "ArcadePackDownload");
        worker = thread;
        thread.start();
    }

    void cancel() {
        cancel = true;
        try {
            host.cancelImport();
        } catch (Throwable ignored) {
        }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        if (!isBusy(phase)) {
            synchronized (lock) {
                if (!isBusy(phase)) phase = "cancelled";
            }
        }
    }

    private void work(String id) {
        JSONObject pack = packById(id);
        File staging = new File(stagingRoot, id);
        File zip = new File(cacheRoot, id + ".zip");
        File part = new File(cacheRoot, id + ".part");
        try {
            if (pack == null) throw new IOException("游戏包信息不存在");
            long size = pack.optLong("size", 0);
            String sha = pack.optString("sha256", "").toLowerCase(Locale.US);
            long extract = Math.max(size, pack.optLong("extractBytes", 0));
            if (!zip.isFile() || !validZip(zip, size, sha)) {
                if (zip.isFile()) zip.delete();
                checkSpace(Math.max(size + extract, 2 * extract) + MIN_FREE);
                phase = "downloading";
                progress = 0;
                downloaded = part.isFile() ? part.length() : 0;
                if (downloaded > size) {
                    part.delete();
                    downloaded = 0;
                }
                try {
                    downloadTo(pack.optString("url", ""), part, size);
                } catch (IOException first) {
                    if (!cancel && part.isFile() && message(first).contains("下载进度已失效")) {
                        part.delete();
                        downloaded = 0;
                        downloadTo(pack.optString("url", ""), part, size);
                    } else {
                        throw first;
                    }
                }
                if (part.length() != size) throw new IOException("游戏包大小不正确");
                if (zip.exists()) zip.delete();
                if (!part.renameTo(zip)) throw new IOException("无法保存游戏包，请检查存储空间");
                downloaded = size;
                progress = 100;
            } else {
                downloaded = size;
                progress = 100;
            }
            if (cancel) throw new IOException("已取消");
            phase = "verifying";
            progress = 0;
            if (!validZip(zip, size, sha)) {
                zip.delete();
                throw new IOException("游戏包校验失败，请重新下载");
            }
            checkSpace(2 * extract + MIN_FREE);
            phase = "extracting";
            progress = 0;
            deleteTree(staging);
            if (!staging.isDirectory() && !staging.mkdirs()) throw new IOException("无法创建游戏包解压目录");
            unzip(zip, staging, extract);
            deleteTree(zip);
            deleteTree(part);
            File manifest = new File(staging, "pack.json");
            if (!manifest.isFile() || manifest.length() < 2 || manifest.length() > MAX_MANIFEST) {
                throw new IOException("游戏包缺少清单文件");
            }
            JSONObject meta = new JSONObject(new String(readAll(new FileInputStream(manifest), MAX_MANIFEST), "UTF-8"));
            JSONArray games = meta.optJSONArray("games");
            if (games == null || games.length() == 0) throw new IOException("游戏包里没有可用的游戏");
            if (cancel) throw new IOException("已取消");
            phase = "importing";
            progress = 0;
            final int totalGames = games.length();
            int[] result = host.importPack(staging, new Progress() {
                public void onProgress(int done, int total) {
                    progress = total > 0 ? Math.min(99, done * 100 / total) : 0;
                }
            });
            int added = result[0];
            int skipped = result[1];
            markInstalled(id, sha, added, skipped);
            deleteTree(staging);
            synchronized (lock) {
                phase = "done";
                progress = 100;
            }
            host.postJs("Arcade.packResult(" + added + "," + skipped + "," + JSONObject.quote(id) + ",\"\")");
        } catch (Throwable caught) {
            deleteTree(staging);
            boolean stopped = cancel || isCancel(caught);
            String text = message(caught);
            synchronized (lock) {
                if (stopped) {
                    phase = "cancelled";
                    error = "";
                } else {
                    phase = "error";
                    error = text;
                    host.postJs("Arcade.packResult(0,0," + JSONObject.quote(id) + ","
                            + JSONObject.quote(text) + ")");
                }
            }
            Log.w("ArcadePacks", "pack " + id + " failed: " + text);
        } finally {
            worker = null;
        }
    }

    private void downloadTo(String address, File part, long expected) throws IOException {
        long existing = part.isFile() ? part.length() : 0;
        if (existing > expected) {
            part.delete();
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
            connection.setRequestProperty("User-Agent", "childhood-arcade-packs");
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
                if (declared > 0 && existing + declared > expected) throw new IOException("游戏包大小超出预期");
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(part, append);
                try {
                    byte[] buffer = new byte[262144];
                    long written = existing;
                    int count;
                    downloaded = written;
                    progress = expected > 0 ? (int) Math.min(99, written * 100 / expected) : 0;
                    while ((count = in.read(buffer)) != -1) {
                        if (cancel) throw new IOException("已取消");
                        written += count;
                        if (written > expected) throw new IOException("游戏包大小超出预期");
                        out.write(buffer, 0, count);
                        downloaded = written;
                        progress = expected > 0 ? (int) Math.min(99, written * 100 / expected) : 0;
                    }
                    out.getFD().sync();
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("下载地址跳转次数过多");
    }

    private void unzip(File zip, File dest, long expectedBytes) throws Exception {
        String root = dest.getCanonicalPath() + File.separator;
        long limitBytes = Math.max(expectedBytes, zip.length()) * 12 / 10 + 16L * 1024 * 1024;
        if (limitBytes > MAX_EXTRACT) limitBytes = MAX_EXTRACT;
        long writtenTotal = 0;
        int extracted = 0;
        Set<String> seen = new HashSet<String>();
        ZipFile archive = new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                if (cancel) throw new IOException("已取消");
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || name.length() == 0) continue;
                if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.contains("..")) {
                    throw new IOException("游戏包路径异常");
                }
                if (entry.isDirectory()) {
                    File dir = new File(dest, name);
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录");
                    continue;
                }
                String lower = name.toLowerCase(Locale.US);
                long limit;
                if (lower.equals("pack.json")) {
                    limit = MAX_MANIFEST;
                } else if (lower.matches("^games/[^/]+\\.(nes|gba|md|sfc|smc|zip)$")) {
                    limit = romLimit(lower.substring(lower.lastIndexOf('.') + 1));
                } else if (lower.matches("^covers/[^/]+\\.(png|jpg|jpeg|webp)$")) {
                    limit = COVER_LIMIT;
                } else {
                    continue;
                }
                if (!seen.add(lower)) throw new IOException("游戏包内容重复");
                File out = new File(dest, name);
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("游戏包路径异常");
                if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) {
                    throw new IOException("无法创建目录");
                }
                InputStream in = new BufferedInputStream(archive.getInputStream(entry));
                FileOutputStream stream = new FileOutputStream(out);
                try {
                    byte[] buffer = new byte[65536];
                    long written = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (cancel) throw new IOException("已取消");
                        written += count;
                        writtenTotal += count;
                        if (written > limit) throw new IOException("游戏包内的文件过大");
                        if (writtenTotal > limitBytes) throw new IOException("游戏包解压后体积异常");
                        stream.write(buffer, 0, count);
                    }
                    stream.getFD().sync();
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
                extracted++;
                if (extracted > MAX_ENTRIES) throw new IOException("游戏包内文件数量过多");
                progress = expectedBytes > 0 ? (int) Math.min(99, writtenTotal * 100 / expectedBytes) : 0;
            }
        } finally {
            archive.close();
        }
        progress = 100;
    }

    private void checkSpace(long need) throws IOException {
        long free;
        try {
            free = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable error) {
            return;
        }
        if (free < need) {
            throw new IOException("电视剩余空间不足，需要约 " + (need / 1024 / 1024) + "MB，请清理后重试");
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            javax.net.ssl.SSLSocketFactory factory = AppUpdates.trustedFactory(context);
            if (factory != null) ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
        }
        return connection;
    }

    private boolean validZip(File zip, long size, String sha) {
        if (size <= 0 || size > MAX_PACK || !zip.isFile() || zip.length() != size) return false;
        if (!sha.matches("[0-9a-f]{64}")) return false;
        try {
            return AppUpdates.sha256(zip).equals(sha);
        } catch (Exception error) {
            return false;
        }
    }

    private JSONObject packById(String id) {
        JSONObject value = catalog;
        if (value == null || id == null || id.length() == 0) return null;
        JSONArray packs = value.optJSONArray("packs");
        if (packs == null) return null;
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack != null && id.equals(pack.optString("id", ""))) return pack;
        }
        return null;
    }

    private void validateCatalog(JSONObject value) throws Exception {
        if (value == null || value.optInt("schema", 0) != 1) throw new IOException("游戏包目录格式不正确");
        JSONArray packs = value.optJSONArray("packs");
        if (packs == null || packs.length() < 1 || packs.length() > 300) throw new IOException("游戏包目录数量异常");
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) throw new IOException("游戏包目录条目为空");
            String id = pack.optString("id", "");
            if (!id.matches("[a-z0-9-]{3,60}") || !ids.add(id)) throw new IOException("游戏包编号异常");
            String tier = pack.optString("tier", "");
            if (!"featured".equals(tier) && !"extended".equals(tier)) throw new IOException("游戏包分类异常");
            String name = pack.optString("name", "");
            if (name.length() < 1 || name.length() > 80) throw new IOException("游戏包名称异常");
            long size = pack.optLong("size", 0);
            if (size < 1 || size > MAX_PACK) throw new IOException("游戏包体积异常");
            String sha = pack.optString("sha256", "").toLowerCase(Locale.US);
            if (!sha.matches("[0-9a-f]{64}")) throw new IOException("游戏包校验值异常");
            String url = pack.optString("url", "");
            URL parsed = new URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())
                    || !"github.com".equalsIgnoreCase(parsed.getHost())) {
                throw new IOException("游戏包下载地址不受信任");
            }
            String asset = pack.optString("asset", "");
            if (!asset.matches("[A-Za-z0-9._-]{5,80}\\.zip")) throw new IOException("游戏包文件名异常");
            int count = pack.optInt("count", 0);
            if (count < 1 || count > 5000) throw new IOException("游戏包游戏数量异常");
            long extract = pack.optLong("extractBytes", 0);
            if (extract < 1 || extract > MAX_EXTRACT) throw new IOException("游戏包解压体积异常");
            int parts = pack.optInt("parts", 1);
            int part = pack.optInt("part", 1);
            if (parts < 1 || parts > 99 || part < 1 || part > parts) throw new IOException("游戏包分卷信息异常");
        }
    }

    private int appVersionCode() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception error) {
            return 0;
        }
    }

    String status() {
        JSONObject out = new JSONObject();
        try {
            JSONObject value = catalog;
            JSONArray packs = value != null ? value.optJSONArray("packs") : null;
            JSONObject installed = installed();
            JSONArray list = new JSONArray();
            int installedPacks = 0;
            int installedGames = 0;
            String currentPhase = phase;
            if (packs != null) {
                for (int i = 0; i < packs.length(); i++) {
                    JSONObject pack = packs.optJSONObject(i);
                    if (pack == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", pack.optString("id", ""));
                    item.put("tier", pack.optString("tier", ""));
                    item.put("name", pack.optString("name", ""));
                    item.put("platform", pack.optString("platform", ""));
                    item.put("platforms", pack.optJSONArray("platforms"));
                    item.put("genre", pack.optString("genre", ""));
                    item.put("count", pack.optInt("count", 0));
                    item.put("size", pack.optLong("size", 0));
                    item.put("extractBytes", pack.optLong("extractBytes", 0));
                    item.put("part", pack.optInt("part", 1));
                    item.put("parts", pack.optInt("parts", 1));
                    String id = pack.optString("id", "");
                    JSONObject state = installed.optJSONObject(id);
                    boolean done = state != null;
                    item.put("installed", done);
                    item.put("updateAvailable", done
                            && !pack.optString("sha256", "").equalsIgnoreCase(state.optString("sha", "")));
                    if (done) {
                        installedPacks++;
                        installedGames += state.optInt("added", 0);
                        item.put("installedAt", state.optLong("at", 0));
                        item.put("added", state.optInt("added", 0));
                        item.put("skipped", state.optInt("skipped", 0));
                    }
                    list.put(item);
                }
            }
            out.put("state", currentPhase);
            out.put("progress", progress);
            out.put("packId", activeId);
            out.put("downloaded", downloaded);
            out.put("total", total);
            out.put("error", error);
            out.put("catalogError", catalogError);
            out.put("catalogVersion", value != null ? value.optString("version", "") : "");
            out.put("catalogAt", catalogAt);
            out.put("catalogSource", catalogSource);
            out.put("metered", AppUpdates.isMetered(context));
            out.put("freeBytes", freeBytes());
            out.put("appVersionCode", appVersionCode());
            out.put("minVersionCode", value != null ? value.optInt("minVersionCode", 0) : 0);
            out.put("tiers", value != null ? value.optJSONArray("tiers") : null);
            out.put("stats", value != null ? value.optJSONObject("stats") : null);
            out.put("totalPacks", packs != null ? packs.length() : 0);
            out.put("installedPacks", installedPacks);
            out.put("installedGames", installedGames);
            out.put("packs", list);
        } catch (Throwable error) {
            try {
                out.put("state", "error");
                out.put("error", message(error));
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    private long freeBytes() {
        try {
            return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable error) {
            return 0;
        }
    }

    private JSONObject installed() {
        try {
            String text = prefs.getString(KEY_INSTALLED, "{}");
            JSONObject value = new JSONObject(text);
            return value;
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    private void markInstalled(String id, String sha, int added, int skipped) {
        synchronized (lock) {
            JSONObject value = installed();
            JSONObject state = new JSONObject();
            try {
                state.put("sha", sha);
                state.put("at", System.currentTimeMillis());
                state.put("added", added);
                state.put("skipped", skipped);
                value.put(id, state);
            } catch (Throwable ignored) {
            }
            prefs.edit().putString(KEY_INSTALLED, value.toString()).commit();
        }
    }

    private void fail(String text) {
        synchronized (lock) {
            phase = "error";
            error = text;
        }
        host.postJs("Arcade.packResult(0,0,\"\", " + JSONObject.quote(text) + ")");
    }

    private static boolean isBusy(String value) {
        for (String item : PHASES_ACTIVE) if (item.equals(value)) return true;
        return false;
    }

    private static boolean isCancel(Throwable error) {
        String text = message(error);
        return text.contains("已取消") || error instanceof InterruptedException;
    }

    private static long romLimit(String extension) {
        if ("nes".equals(extension)) return 8L * 1024 * 1024;
        if ("gba".equals(extension)) return 64L * 1024 * 1024;
        if ("md".equals(extension) || "sfc".equals(extension) || "smc".equals(extension)) {
            return 16L * 1024 * 1024;
        }
        if ("zip".equals(extension)) return 256L * 1024 * 1024;
        return 0;
    }

    private static long contentLength(HttpURLConnection connection) {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        try {
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("文件过大");
                out.write(buffer, 0, count);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return out.toByteArray();
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        return readLimited(in, limit);
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.length() == 0 ? error.getClass().getSimpleName() : text;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
