package com.winlator.core;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.Drive;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.WineUtils;
import com.winlator.core.GPUHelper;
import com.winlator.win32.WinVersions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadEngine {
    public interface Callback {
        /** @param percent 0–100, or -1 when total size is unknown (use indeterminate UI) */
        default void onProgress(int percent, String status) {}

        void onSuccess();

        void onError(Exception e);
    }

    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static void reportProgress(Callback callback, int percent, String status) {
        if (callback == null) return;
        MAIN_HANDLER.post(() -> callback.onProgress(percent, status));
    }

    private static void reportSuccess(Callback callback) {
        if (callback == null) return;
        MAIN_HANDLER.post(callback::onSuccess);
    }

    private static void reportError(Callback callback, Exception e) {
        if (callback == null) return;
        MAIN_HANDLER.post(() -> callback.onError(e));
    }

    public static void downloadAndInstall(Context context, Game game, String mode, Callback callback) {
        new Thread(() -> {
            try {
                if (game == null) throw new IllegalArgumentException("Game is null");
                if (game.title == null || game.title.trim().isEmpty()) throw new IllegalArgumentException("Game title missing");
                if (game.download_url == null || game.download_url.trim().isEmpty()) {
                    throw new IllegalArgumentException("download_url is missing for " + game.title);
                }

                // 1) Download + extract to /Internal Storage/RetroNexus/Games/<GameName>/
                File gamesDir = getGameInstallDir(game);
                if (!gamesDir.exists() && !gamesDir.mkdirs()) {
                    throw new IllegalStateException("Unable to create install dir: " + gamesDir.getPath());
                }

                File alreadyThere = findExeUnderGameDir(gamesDir, getExeName(game));
                if (alreadyThere != null && alreadyThere.isFile()) {
                    reportProgress(callback, 86, "Using existing files…");
                } else {
                    streamDownloadAndExtractZip(game.download_url, gamesDir, callback);
                }

                reportProgress(callback, 88, "Setting up…");
                // 2) Validate expected exe exists (root or nested folder — zips often have one extra dir)
                String exeName = getExeName(game);
                File exeFile = findExeUnderGameDir(gamesDir, exeName);
                if (exeFile == null || !exeFile.isFile()) {
                    throw new IllegalStateException("Missing exe after extraction: " + new File(gamesDir, exeName).getPath()
                        + " (searched under " + gamesDir.getPath() + ")");
                }

                // Optional: apply a small patch zip into the game root (e.g. NFS widescreen fix: scripts/ + dinput8.dll)
                // config_preset example: { "patchZipUrl": "https://.../nfs_mw_widescreen_fix.zip" }
                applyOptionalPatchZip(game, exeFile.getParentFile(), callback);

                // 3) Create/update container + inject config
                Container container = ensureContainer(context, game, mode);
                if (container == null) {
                    throw new IllegalStateException("Failed to create container for " + game.title);
                }

                container.putExtra("retronexusExecPath", exeFile.getPath());
                container.saveData();

                reportSuccess(callback);
            } catch (Exception e) {
                reportError(callback, e);
            }
        }).start();
    }

    public static void launchGame(Context context, Game game) {
        try {
            if (context == null || game == null) return;

            ContainerManager manager = new ContainerManager(context);
            Container target = null;
            for (Container container : manager.getContainers()) {
                if (container.getName().equals(game.title)) {
                    target = container;
                    break;
                }
            }

            if (target == null) {
                showToast(context, "Container not found. Reinstall the game.");
                return;
            }

            File gamesDir = getGameInstallDir(game);
            String exeName = getExeName(game);
            File exeFile = findExeUnderGameDir(gamesDir, exeName);
            if (exeFile == null || !exeFile.isFile()) {
                showToast(context, "Game files missing. Reinstall.");
                return;
            }

            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", target.id);
            intent.putExtra("exec_path", exeFile.getPath()); // bypass desktop and run directly
            context.startActivity(intent);
        } catch (Exception e) {
            if (context != null) showToast(context, "Launch failed: " + e.getMessage());
        }
    }

    private static File getGameInstallDir(Game game) {
        String safe = (game.title != null ? game.title : "Game").replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(Environment.getExternalStorageDirectory(), "RetroNexus/Games/" + safe);
    }

    /** Resolved main exe if already present under the game install folder, else null. */
    public static File findInstalledGameExe(Game game) {
        if (game == null) return null;
        File gamesDir = getGameInstallDir(game);
        if (!gamesDir.isDirectory()) return null;
        return findExeUnderGameDir(gamesDir, getExeName(game));
    }

    /** True when game folder has the expected exe and a matching Wine container exists. */
    public static boolean isReadyToPlay(Context context, Game game) {
        if (context == null || game == null) return false;
        File exe = findInstalledGameExe(game);
        if (exe == null || !exe.isFile()) return false;
        ContainerManager manager = new ContainerManager(context);
        for (Container c : manager.getContainers()) {
            if (game.title != null && game.title.equals(c.getName())) return true;
        }
        return false;
    }

    /**
     * Zip archives often contain a single top-level folder (e.g. NFS_MW/speed.exe). Look for {@code exeName}
     * directly under {@code gamesDir}, then recursively under subfolders.
     */
    private static File findExeUnderGameDir(File gamesDir, String exeName) {
        if (gamesDir == null || exeName == null || exeName.isEmpty()) return null;
        File direct = new File(gamesDir, exeName);
        if (direct.isFile()) return direct;
        return findFileNamedRecursive(gamesDir, exeName);
    }

    private static File findFileNamedRecursive(File dir, String fileName) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File child : list) {
            if (child.isFile() && fileName.equalsIgnoreCase(child.getName())) return child;
        }
        for (File child : list) {
            if (child.isDirectory()) {
                File found = findFileNamedRecursive(child, fileName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void streamDownloadAndExtractZip(String url, File destDir, Callback callback) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Download failed: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("Empty download body");

            long contentLength = body.contentLength();
            InputStream raw = body.byteStream();
            InputStream metered;
            if (contentLength > 0) {
                metered = new CountingInputStream(raw, contentLength, (read, total) -> {
                    int pct = (int) (read * 86L / total);
                    reportProgress(callback, Math.min(86, pct), "Downloading…");
                });
            } else {
                reportProgress(callback, -1, "Downloading…");
                metered = raw;
            }

            try (InputStream in = new BufferedInputStream(metered);
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(destDir, entry.getName());

                    // ZipSlip protection
                    String canonicalDestDir = destDir.getCanonicalPath() + File.separator;
                    String canonicalOut = outFile.getCanonicalPath();
                    if (!canonicalOut.startsWith(canonicalDestDir)) {
                        throw new IllegalStateException("Blocked zip path traversal: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw new IllegalStateException("Failed to create dir: " + outFile.getPath());
                        }
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IllegalStateException("Failed to create dir: " + parent.getPath());
                        }
                        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                            int read;
                            while ((read = zis.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
    }

    private static void applyOptionalPatchZip(Game game, File gameRootDir, Callback callback) throws Exception {
        String patchUrl = getPatchZipUrl(game);
        if (patchUrl == null || patchUrl.trim().isEmpty()) return;
        if (gameRootDir == null || !gameRootDir.isDirectory()) return;

        reportProgress(callback, -1, "Applying fix…");
        streamDownloadAndExtractZip(patchUrl, gameRootDir, callback);
    }

    private static String getPatchZipUrl(Game game) {
        try {
            if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("patchZipUrl")) return obj.get("patchZipUrl").getAsString();
                if (obj.has("patch_zip_url")) return obj.get("patch_zip_url").getAsString();
                if (obj.has("widescreenFixZipUrl")) return obj.get("widescreenFixZipUrl").getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private interface ByteProgress {
        void onBytesRead(long read, long total);
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long total;
        private final ByteProgress listener;
        private long read;
        private int lastReported = -1;

        CountingInputStream(InputStream in, long total, ByteProgress listener) {
            super(in);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) {
            read += n;
            if (total <= 0 || listener == null) return;
            int pct = (int) (read * 86L / total);
            if (pct != lastReported) {
                lastReported = pct;
                listener.onBytesRead(read, total);
            }
        }
    }

    private static Container ensureContainer(Context context, Game game, String mode) throws Exception {
        ContainerManager manager = new ContainerManager(context);
        for (Container c : manager.getContainers()) {
            if (c.getName().equals(game.title)) {
                applyPreset(context, c, game, mode);
                c.saveData();
                return c;
            }
        }

        JSONObject data = new JSONObject();
        data.put("name", game.title);

        Container[] created = new Container[1];
        CountDownLatch latch = new CountDownLatch(1);
        manager.createContainerAsync(data, (container) -> {
            created[0] = container;
            latch.countDown();
        });
        latch.await(120, TimeUnit.SECONDS);

        if (created[0] != null) {
            applyPreset(context, created[0], game, mode);
            created[0].saveData();
        }
        return created[0];
    }

    private static void applyPreset(Context context, Container container, Game game, String mode) {
        // Defaults based on the roadmap + NFS manual.
        String effectiveMode = mode;
        if ("auto".equals(mode)) effectiveMode = chooseAutoMode(context);

        // Prefer 16:9 defaults to avoid visible cutoffs on phone screens.
        String screenSize = "performance".equals(effectiveMode) ? "1024x576" : "1280x720";

        // Prefer DXVK when Vulkan is available; fall back to WineD3D otherwise.
        String dxwrapper = isVulkanAvailable(context) ? DXWrappers.DXVK : DXWrappers.WINED3D;
        String graphicsDriver = GraphicsDrivers.getDefaultDriver(context);

        JsonObject preset = getModePreset(game, effectiveMode);
        if (preset != null) {
            if (preset.has("screenSize")) screenSize = preset.get("screenSize").getAsString();
            if (preset.has("dxwrapper")) dxwrapper = preset.get("dxwrapper").getAsString();
            if (preset.has("driver")) {
                String vulkan = preset.get("driver").getAsString();
                graphicsDriver = vulkan + "," + GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
            }
        }

        container.setScreenSize(screenSize);
        container.setDXWrapper(dxwrapper);
        container.setGraphicsDriver(graphicsDriver);

        EnvVars env = new EnvVars(container.getEnvVars());
        env.put("WINEDLLOVERRIDES", "dinput8,d3d9=n,b");
        container.setEnvVars(env.toString());

        applyWinVersion(container, game);
        applyCpuAffinityDefaults(container);
        ensureExternalStorageDrive(container);
    }

    /**
     * Our game installs live under /storage/emulated/0/RetroNexus/... but the default container drives only map
     * Downloads and app-internal storage. Add a drive for the root of external storage so unixToDOSPath works.
     */
    private static void ensureExternalStorageDrive(Container container) {
        try {
            String storage = Environment.getExternalStorageDirectory().getPath();
            boolean hasStorage = false;
            boolean fUsed = false;
            boolean gUsed = false;
            for (Drive d : container.drivesIterator()) {
                if (storage.equals(d.path)) hasStorage = true;
                if ("F".equalsIgnoreCase(d.letter)) fUsed = true;
                if ("G".equalsIgnoreCase(d.letter)) gUsed = true;
            }
            if (hasStorage) return;

            String letter = !fUsed ? "F" : (!gUsed ? "G" : null);
            if (letter == null) return;
            container.setDrives(container.getDrives() + letter + ":" + storage);
        } catch (Exception ignored) {}
    }

    private static boolean isVulkanAvailable(Context context) {
        try {
            return GPUHelper.vkGetApiVersion() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String chooseAutoMode(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                long totalGb = info.totalMem / (1024L * 1024L * 1024L);
                // Old heuristic (<=6 GB) was too aggressive for mid-range phones like Snapdragon 695 devices.
                if (totalGb > 0 && totalGb <= 4) return "performance";
            }
        } catch (Exception ignored) {}
        return "graphics";
    }

    private static void applyWinVersion(Container container, Game game) {
        // Optional config: { "winVersion": "winxp" }
        try {
            if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("winVersion")) {
                    String v = obj.get("winVersion").getAsString();
                    int idx = findWinVersionIndex(v);
                    if (idx != -1) WineUtils.setWinVersion(container, idx);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private static int findWinVersionIndex(String version) {
        if (version == null || version.isEmpty()) return -1;
        WinVersions.WinVersion[] versions = WinVersions.getWinVersions();
        for (int i = 0; i < versions.length; i++) {
            if (version.equalsIgnoreCase(versions[i].version)) return i;
        }
        return -1;
    }

    private static void applyCpuAffinityDefaults(Container container) {
        try {
            int n = Runtime.getRuntime().availableProcessors();
            if (n >= 6) {
                // Simple default: avoid CPU 0/1 (often little cores / OS busy) if we have enough cores.
                String list = "";
                for (int i = 2; i < n; i++) list += (!list.isEmpty() ? "," : "") + i;
                container.setCPUList(list);
                container.setCPUListWoW64(list);
            }
        } catch (Exception ignored) {}
    }

    private static String getExeName(Game game) {
        try {
            if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("exe")) return obj.get("exe").getAsString();
            }
        } catch (Exception ignored) {}
        return "speed.exe";
    }

    private static JsonObject getModePreset(Game game, String mode) {
        try {
            JsonElement el = game != null ? game.config_preset : null;
            if (el == null || !el.isJsonObject()) return null;
            JsonObject obj = el.getAsJsonObject();
            if (mode == null || mode.isEmpty()) return null;
            if (!obj.has(mode)) return null;
            JsonElement m = obj.get(mode);
            return m != null && m.isJsonObject() ? m.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void showToast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        );
    }
}
