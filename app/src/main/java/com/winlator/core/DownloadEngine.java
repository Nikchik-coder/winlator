package com.winlator.core;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.util.Log;
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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadEngine {
    private static final String INSTALL_MARKER_NAME = ".retronexus_install_complete";

    public static class InstallStatus {
        public volatile int percent = -1; // -1 => indeterminate
        public volatile String status = "";
    }

    private static final ConcurrentHashMap<String, InstallStatus> ACTIVE_INSTALLS = new ConcurrentHashMap<>();

    public interface Callback {
        /** @param percent 0–100, or -1 when total size is unknown (use indeterminate UI) */
        default void onProgress(int percent, String status) {}

        void onSuccess();

        void onError(Exception e);
    }

    // Large game archives can take a long time on mobile networks; default OkHttp read timeout is too short.
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // no per-read timeout
        .writeTimeout(0, TimeUnit.SECONDS)  // no per-write timeout
        .callTimeout(0, TimeUnit.SECONDS)   // no overall timeout
        .build();
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
        Log.e("DownloadEngine", "Error during install", e);
        MAIN_HANDLER.post(() -> callback.onError(e));
    }

    private static String getInstallKey(Game game) {
        if (game == null) return "";
        if (game.id != null && !game.id.isEmpty()) return game.id;
        return game.title != null ? game.title : "";
    }

    public static InstallStatus getActiveInstallStatus(Game game) {
        String key = getInstallKey(game);
        if (key.isEmpty()) return null;
        return ACTIVE_INSTALLS.get(key);
    }

    public static void downloadAndInstall(Context context, Game game, String mode, Callback callback) {
        new Thread(() -> {
            final String installKey = getInstallKey(game);
            InstallStatus active = installKey.isEmpty() ? null : ACTIVE_INSTALLS.get(installKey);
            if (active != null) {
                // Already installing: just report current status so UI can resume.
                reportProgress(callback, active.percent, active.status != null ? active.status : "Installing…");
                return;
            }

            final InstallStatus status = new InstallStatus();
            if (!installKey.isEmpty()) ACTIVE_INSTALLS.put(installKey, status);
            final Callback trackingCallback = new Callback() {
                @Override
                public void onProgress(int percent, String statusText) {
                    status.percent = percent;
                    status.status = statusText != null ? statusText : "";
                    if (callback != null) callback.onProgress(percent, statusText);
                }

                @Override
                public void onSuccess() {
                    if (callback != null) callback.onSuccess();
                }

                @Override
                public void onError(Exception e) {
                    if (callback != null) callback.onError(e);
                }
            };

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

                File installMarker = new File(gamesDir, INSTALL_MARKER_NAME);
                File alreadyThere = findExeUnderGameDir(gamesDir, getExeName(game));
                if (alreadyThere != null && alreadyThere.isFile() && installMarker.isFile()) {
                    status.percent = 86;
                    status.status = "Using existing files…";
                    reportProgress(trackingCallback, status.percent, status.status);
                } else {
                    if (alreadyThere != null && alreadyThere.isFile() && !installMarker.isFile()) {
                        Log.w("DownloadEngine", "Install marker missing; re-downloading to recover from partial install: " + gamesDir.getPath());
                        status.percent = 0;
                        status.status = "Re-downloading…";
                        reportProgress(trackingCallback, status.percent, status.status);
                    }
                    streamDownloadAndExtractZip(game.download_url, gamesDir, trackingCallback);
                }

                // Download progress is measurable; the rest (patching/container setup) usually isn't.
                // Use indeterminate progress so UI keeps animating during the install phase.
                status.percent = -1;
                status.status = "Installing…";
                reportProgress(trackingCallback, status.percent, status.status);

                // 2) Validate expected exe exists (root or nested folder — zips often have one extra dir)
                String exeName = getExeName(game);
                Log.d("DownloadEngine", "Searching for exe: " + exeName + " in " + gamesDir.getPath());
                File exeFile = findExeUnderGameDir(gamesDir, exeName);

                if (exeFile == null || !exeFile.isFile()) {
                    Log.w("DownloadEngine", "Expected exe " + exeName + " not found. Searching for any .exe...");
                    exeFile = findAnyExe(gamesDir);
                }

                if (exeFile == null || !exeFile.isFile()) {
                    throw new IllegalStateException("Missing exe after extraction. Searched for " + exeName + " and others in " + gamesDir.getPath());
                }

                // Optional: apply a small patch zip into the game root (e.g. NFS widescreen fix: scripts/ + dinput8.dll)
                // config_preset example: { "patchZipUrl": "https://.../nfs_mw_widescreen_fix.zip" }
                status.percent = -1;
                status.status = "Applying fixes…";
                reportProgress(trackingCallback, status.percent, status.status);
                applyOptionalPatchZip(game, exeFile.getParentFile(), trackingCallback);

                warnIfNfsUg2HasUnderground1WidescreenFix(exeFile);

                // Mark extraction as complete so subsequent installs don't skip after a partial unzip.
                try {
                    if (!installMarker.isFile()) {
                        boolean created = installMarker.createNewFile();
                        Log.d("DownloadEngine", "Created install marker (" + created + "): " + installMarker.getPath());
                    }
                } catch (Exception e) {
                    Log.w("DownloadEngine", "Failed to write install marker: " + installMarker.getPath(), e);
                }

                // 3) Create/update container + inject config
                status.percent = -1;
                status.status = "Creating container…";
                reportProgress(trackingCallback, status.percent, status.status);
                Container container = ensureContainer(context, game, mode);
                if (container == null) {
                    throw new IllegalStateException("Failed to create container for " + game.title);
                }

                if (game.id != null && !game.id.isEmpty()) container.putExtra("retronexusGameId", game.id);
                container.putExtra("retronexusGameInstallDir", gamesDir.getPath());
                container.putExtra("retronexusExecPath", exeFile.getPath());
                container.saveData();

                reportSuccess(trackingCallback);
            } catch (Exception e) {
                reportError(trackingCallback, e);
            } finally {
                if (!installKey.isEmpty()) ACTIVE_INSTALLS.remove(installKey);
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

            // Apply latest presets before launching (so Supabase updates take effect without reinstall).
            try {
                applyPreset(context, target, game, "auto");
                target.saveData();
            } catch (Exception ignored) {}

            File gamesDir = getGameInstallDir(game);
            String exeName = getExeName(game);
            File exeFile = findExeUnderGameDir(gamesDir, exeName);
            if (exeFile == null || !exeFile.isFile()) {
                // If config is missing/wrong, try to locate any exe so the user can still launch.
                exeFile = findAnyExe(gamesDir);
            }
            if (exeFile == null || !exeFile.isFile()) {
                showToast(context, "Game files missing. Reinstall.");
                return;
            }

            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", target.id);
            intent.putExtra("exec_path", exeFile.getPath()); // bypass desktop and run directly
            String execArgs = getExecArgs(game, "auto");
            if (execArgs != null && !execArgs.trim().isEmpty()) {
                intent.putExtra("exec_args", execArgs.trim());
            }
            if (shouldAutoFullscreen(game, "auto")) {
                intent.putExtra("auto_fullscreen", true);
            }
            if (isWarcraft3(game)) {
                intent.putExtra("force_windows_fullscreen", true);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            if (context != null) showToast(context, "Launch failed: " + e.getMessage());
        }
    }

    public static File getGameInstallDir(Game game) {
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

    private static File findAnyExe(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        // Search files first
        for (File child : list) {
            if (child.isFile() && child.getName().toLowerCase().endsWith(".exe")) {
                // Ignore common setup/uninst files
                String name = child.getName().toLowerCase();
                if (name.contains("unins") || name.contains("setup") || name.contains("dxwebsetup")) continue;
                return child;
            }
        }
        // Then subdirectories
        for (File child : list) {
            if (child.isDirectory()) {
                File found = findAnyExe(child);
                if (found != null) return found;
            }
        }
        return null;
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
                    int clamped = Math.min(86, pct);
                    reportProgress(callback, clamped, "Downloading…");
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

    /**
     * ThirteenAG ships separate fixes: NFSUnderground.* = NFSU1, NFSUnderground2.* = NFSU2.
     * A mis-packaged zip (UG2 exe + UG1 fix) loads dinput8 but never patches — game stays 4:3.
     */
    private static void warnIfNfsUg2HasUnderground1WidescreenFix(File exeFile) {
        if (exeFile == null || !exeFile.isFile()) return;
        if (!"speed2.exe".equalsIgnoreCase(exeFile.getName())) return;
        File scripts = new File(exeFile.getParentFile(), "scripts");
        if (!scripts.isDirectory()) return;
        boolean ug2 = new File(scripts, "NFSUnderground2.WidescreenFix.ini").isFile();
        boolean ug1 = new File(scripts, "NFSUnderground.WidescreenFix.ini").isFile();
        if (ug1 && !ug2) {
            Log.e("DownloadEngine", "Widescreen: game is NFS Underground 2 (speed2.exe) but scripts/ contains NFS Underground 1 fix (NFSUnderground.WidescreenFix.*). Replace with ThirteenAG NFSUnderground2.WidescreenFix or set config_preset.patchZipUrl to that zip.");
        }
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

        final boolean enableDinput8Override = shouldEnableDinput8Override(game, preset);

        // NFS UG2:
        // - If the user ships a ThirteenAG loader (enableDinput8Override=true), prefer WineD3D to reduce mod-related crashes.
        // - If the user ships a patched exe (enableDinput8Override=false), keep DXVK default for performance.
        // Respect explicit config_preset dxwrapper if set.
        if (isNfsUnderground2(game) && enableDinput8Override && (preset == null || !preset.has("dxwrapper"))) {
            dxwrapper = DXWrappers.WINED3D;
            Log.i("DownloadEngine", "NFS Underground 2: using WineD3D for dinput8-loader stability (set graphics/performance.dxwrapper to override)");
        }

        container.setScreenSize(screenSize);
        container.setDXWrapper(dxwrapper);
        container.setGraphicsDriver(graphicsDriver);

        EnvVars env = new EnvVars(container.getEnvVars());
        // Default dll overrides:
        // - d3d9=n,b helps some D3D9 titles (and matches our historical Wine config guidance)
        // - dinput8=n,b is needed for ThirteenAG-style fixes (dinput8.dll loader in game dir)
        // UG2 is special: users may ship a patched exe (UniWS) and must NOT load a dinput8 loader that crashes on Box64.
        final String baseDllOverrides = enableDinput8Override ? "dinput8=n,b;d3d9=n,b" : "d3d9=n,b";
        Log.d("DownloadEngine", "Initial envVars: " + env.toString());

        try {
            if (preset != null && preset.has("envVars")) {
                Log.d("DownloadEngine", "Found envVars in preset");
                JsonObject envVars = preset.getAsJsonObject("envVars");
                for (String key : envVars.keySet()) {
                    String val = envVars.get(key).getAsString();
                    Log.d("DownloadEngine", "Preset envVar: " + key + "=" + val);
                    if ("WINEDLLOVERRIDES".equals(key) && val != null && !val.isEmpty()) {
                        val = val + ";" + baseDllOverrides;
                    }
                    env.put(key, val);
                }
            } else if (game != null && game.config_preset != null && game.config_preset.isJsonObject() && game.config_preset.getAsJsonObject().has("envVars")) {
                Log.d("DownloadEngine", "Found envVars in root config_preset");
                JsonObject envVars = game.config_preset.getAsJsonObject().getAsJsonObject("envVars");
                for (String key : envVars.keySet()) {
                    String val = envVars.get(key).getAsString();
                    Log.d("DownloadEngine", "Root envVar: " + key + "=" + val);
                    if ("WINEDLLOVERRIDES".equals(key) && val != null && !val.isEmpty()) {
                        val = val + ";" + baseDllOverrides;
                    }
                    env.put(key, val);
                }
            } else {
                Log.d("DownloadEngine", "No envVars found in preset or config_preset");
            }
        } catch (Exception e) {
            Log.e("DownloadEngine", "Error applying envVars", e);
        }

            // For FlatOut 2: disable Android audio driver to prevent mmdevapi crash
        if (game != null && "Flatout 2".equalsIgnoreCase(game.title)) {
            String currentOverrides = env.get("WINEDLLOVERRIDES");
            if (currentOverrides == null || currentOverrides.isEmpty()) {
                currentOverrides = baseDllOverrides;
            }
            // Disable wineandroid.drv to force PulseAudio, disable mmdevapi entirely
            env.put("WINEDLLOVERRIDES", "wineandroid.drv=d;mmdevapi=d;" + currentOverrides);
            Log.d("DownloadEngine", "FlatOut 2: Disabled Android audio driver and mmdevapi");
        } else if (!env.has("WINEDLLOVERRIDES")) {
            Log.d("DownloadEngine", "No WINEDLLOVERRIDES set, using default");
            env.put("WINEDLLOVERRIDES", baseDllOverrides);
        }

        // Ensure overrides are present (fixes older containers and malformed preset strings).
        ensureDllOverrides(env, enableDinput8Override);

        String finalEnvVars = env.toString();
        Log.i("DownloadEngine", "Final envVars: " + finalEnvVars);
        container.setEnvVars(finalEnvVars);

        applyWarcraft3WidescreenRegistry(container, game, screenSize);

        applyWinVersion(container, game, preset);
        applyAudioDriver(container, game, preset);
        applyWinComponents(container, game, preset);
        applyStartupSelection(container, game, preset);
        applyBox64Preset(container, game, preset);
        applyCpuAffinityDefaults(container);
        ensureExternalStorageDrive(container);
        restoreBackedUpDlls(container);
        cleanupLegacyDllOverrides(container);
        disableMmdevapiForFlatout2(container, game);

        // Save all container changes
        container.saveData();
        Log.d("DownloadEngine", "Container preset applied and saved for " + (game != null ? game.title : "unknown"));
    }

    /**
     * Nuclear option for FlatOut 2: Rename mmdevapi.dll to prevent audio crash.
     * This makes the game run silent but playable.
     */
    private static void disableMmdevapiForFlatout2(Container container, Game game) {
        try {
            if (game == null || !"Flatout 2".equalsIgnoreCase(game.title)) return;
            
            File rootDir = container.getRootDir();
            String[] paths = {
                ".wine/drive_c/windows/system32/mmdevapi.dll",
                ".wine/drive_c/windows/syswow64/mmdevapi.dll"
            };
            
            for (String path : paths) {
                File dllFile = new File(rootDir, path);
                File bakFile = new File(rootDir, path + ".bak");
                if (dllFile.exists() && !bakFile.exists()) {
                    if (dllFile.renameTo(bakFile)) {
                        Log.d("DownloadEngine", "Nuclear option: Disabled mmdevapi for FlatOut 2: " + path);
                    }
                }
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to disable mmdevapi for FlatOut 2", e);
        }
    }

    /**
     * Restore any DLLs that were renamed to .bak by previous app versions so the Wine prefix is back to a clean state.
     */
    private static void restoreBackedUpDlls(Container container) {
        try {
            File rootDir = container.getRootDir();
            String[] dllsToRestore = {"mmdevapi.dll", "avrt.dll"};
            String[] paths = {
                ".wine/drive_c/windows/system32/",
                ".wine/drive_c/windows/syswow64/"
            };
            for (String path : paths) {
                File dir = new File(rootDir, path);
                if (!dir.exists()) continue;
                for (String dll : dllsToRestore) {
                    File bakFile = new File(dir, dll + ".bak");
                    File dllFile = new File(dir, dll);
                    if (bakFile.exists() && !dllFile.exists()) {
                        if (bakFile.renameTo(dllFile)) {
                            Log.d("DownloadEngine", "Restored DLL: " + dllFile.getPath());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to restore backed-up DLLs", e);
        }
    }

    /**
     * Remove the mmdevapi/avrt DllOverrides entries written by older app versions. They were set to "" (disabled),
     * which prevented Wine's built-in mmdevapi from loading and left the game in a half-initialized audio state.
     */
    private static void cleanupLegacyDllOverrides(Container container) {
        try {
            File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
            if (!userRegFile.exists()) return;
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeValue("Software\\Wine\\DllOverrides", "mmdevapi");
                registryEditor.removeValue("Software\\Wine\\DllOverrides", "avrt");
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to cleanup legacy DllOverrides", e);
        }
    }

    /** Ensure our baseline WINEDLLOVERRIDES entries exist (d3d9 always; dinput8 only when enabled). */
    private static void ensureDllOverrides(EnvVars env, boolean enableDinput8Override) {
        if (env == null) return;
        String w = env.get("WINEDLLOVERRIDES");
        if (w == null) w = "";

        boolean needD3d9 = !w.contains("d3d9=n,b");
        boolean needDinput = enableDinput8Override && !w.contains("dinput8=n,b");

        if (w.isEmpty()) {
            if (needDinput) env.put("WINEDLLOVERRIDES", "dinput8=n,b;d3d9=n,b");
            else if (needD3d9) env.put("WINEDLLOVERRIDES", "d3d9=n,b");
            return;
        }

        if (needDinput) w = w + ";dinput8=n,b";
        if (needD3d9) w = w + ";d3d9=n,b";
        env.put("WINEDLLOVERRIDES", w);
    }

    /**
     * Default behavior:
     * - Enable dinput8 override for most games (so WidescreenFixesPack-style loaders work).
     * - Disable it for NFS Underground 2 (UniWS patched EXE path; avoid dinput8 loader crashes).
     *
     * Override from Supabase by setting `enableDinput8Override: true|false` in the mode preset
     * (graphics/performance) or in root config_preset.
     */
    private static boolean shouldEnableDinput8Override(Game game, JsonObject modePreset) {
        Boolean explicit = getBooleanConfig(modePreset, game, "enableDinput8Override");
        if (explicit != null) return explicit;
        if (isNfsUnderground2(game)) return false;
        return true;
    }

    private static Boolean getBooleanConfig(JsonObject modePreset, Game game, String key) {
        try {
            if (modePreset != null && modePreset.has(key)) return modePreset.get(key).getAsBoolean();
            if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has(key)) return obj.get(key).getAsBoolean();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean shouldAutoFullscreen(Game game, String mode) {
        try {
            JsonObject modePreset = getModePreset(game, mode);
            Boolean explicit = getBooleanConfig(modePreset, game, "autoFullscreen");
            if (explicit == null) explicit = getBooleanConfig(modePreset, game, "auto_fullscreen");
            if (explicit != null) return explicit;
        } catch (Exception ignored) {}
        // Default: Warcraft III benefits a lot from stretch fullscreen for FMVs / window changes.
        return isWarcraft3(game);
    }

    private static String getExecArgs(Game game, String mode) {
        try {
            JsonObject modePreset = getModePreset(game, mode);
            if (modePreset != null && modePreset.has("execArgs")) return modePreset.get("execArgs").getAsString();
            if (modePreset != null && modePreset.has("exec_args")) return modePreset.get("exec_args").getAsString();
            if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("execArgs")) return obj.get("execArgs").getAsString();
                if (obj.has("exec_args")) return obj.get("exec_args").getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static boolean isWarcraft3(Game game) {
        if (game == null) return false;
        String exe = getExeName(game);
        if (exe != null && exe.toLowerCase(Locale.US).contains("frozen throne")) return true;
        String t = game.title;
        return t != null && t.toLowerCase(Locale.US).contains("warcraft");
    }

    private static void applyWarcraft3WidescreenRegistry(Container container, Game game, String screenSize) {
        try {
            if (container == null || !isWarcraft3(game)) return;
            if (screenSize == null || !screenSize.contains("x")) return;
            String[] parts = screenSize.split("x");
            if (parts.length != 2) return;
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());

            // Warcraft III reads resolution from HKCU. Write it once into the prefix so users don't need regedit scripts.
            File wineDir = new File(container.getRootDir(), ".wine");
            if (!wineDir.exists()) wineDir.mkdirs();
            File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.setDwordValue("Software\\Blizzard Entertainment\\Warcraft III\\Video", "reswidth", w);
                registryEditor.setDwordValue("Software\\Blizzard Entertainment\\Warcraft III\\Video", "resheight", h);
            }
            Log.i("DownloadEngine", "Warcraft III: wrote registry reswidth/resheight=" + w + "x" + h);
        } catch (Exception e) {
            Log.w("DownloadEngine", "Warcraft III: failed to write widescreen registry", e);
        }
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

    private static void applyWinVersion(Container container, Game game, JsonObject modePreset) {
        // Optional config: { "winVersion": "winxp" } or { "graphics": { "winVersion": "winxp" } }
        try {
            String v = null;
            String source = null;
            if (modePreset != null && modePreset.has("winVersion")) {
                v = modePreset.get("winVersion").getAsString();
                source = "modePreset";
            }
            if (v == null && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("winVersion")) {
                    v = obj.get("winVersion").getAsString();
                    source = "root config";
                }
            }
            if (v == null) {
                Log.d("DownloadEngine", "No winVersion specified in preset");
                return;
            }
            int idx = findWinVersionIndex(v);
            if (idx != -1) {
                WineUtils.setWinVersion(container, idx);
                Log.d("DownloadEngine", "Applied winVersion=" + v + " from " + source);
            } else {
                Log.w("DownloadEngine", "Unknown winVersion: " + v);
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to apply winVersion", e);
        }
    }

    private static void applyAudioDriver(Container container, Game game, JsonObject modePreset) {
        // Optional config: { "audioDriver": "alsa" } or { "graphics": { "audioDriver": "pulseaudio" } }
        try {
            String v = null;
            if (modePreset != null && modePreset.has("audioDriver")) v = modePreset.get("audioDriver").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("audioDriver")) v = obj.get("audioDriver").getAsString();
            }
            if (v == null || v.isEmpty()) return;
            container.setAudioDriver(v);

            // Disable mmdevapi entirely to prevent crashes in games that use it
            // mmdevapi is buggy in Wine on Android; disabling it forces DirectSound fallback
            File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
            if (userRegFile.exists()) {
                try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                    registryEditor.setStringValue("Software\\Wine\\DllOverrides", "mmdevapi", "disabled");
                    registryEditor.setStringValue("Software\\Wine\\DllOverrides", "avrt", "disabled");
                    Log.d("DownloadEngine", "Disabled mmdevapi/avrt to prevent audio crashes");
                }
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to apply audio driver settings", e);
        }
    }

    private static void applyWinComponents(Container container, Game game, JsonObject modePreset) {
        // Optional config: { "wincomponents": "directsound=1,wmdecoder=0,..." }
        try {
            String v = null;
            if (modePreset != null && modePreset.has("wincomponents")) v = modePreset.get("wincomponents").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("wincomponents")) v = obj.get("wincomponents").getAsString();
            }
            if (v == null || v.isEmpty()) {
                return;
            }
            container.setWinComponents(v);
        } catch (Exception ignored) {}
    }

    private static void applyStartupSelection(Container container, Game game, JsonObject modePreset) {
        // Optional config: { "startupSelection": "normal"|"essential"|"aggressive"|0|1|2 }
        try {
            String v = null;
            if (modePreset != null && modePreset.has("startupSelection")) v = modePreset.get("startupSelection").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("startupSelection")) v = obj.get("startupSelection").getAsString();
            }
            if (v == null || v.isEmpty()) return;

            byte sel;
            if ("0".equals(v) || "normal".equalsIgnoreCase(v)) sel = Container.STARTUP_SELECTION_NORMAL;
            else if ("2".equals(v) || "aggressive".equalsIgnoreCase(v)) sel = Container.STARTUP_SELECTION_AGGRESSIVE;
            else if ("1".equals(v) || "essential".equalsIgnoreCase(v)) sel = Container.STARTUP_SELECTION_ESSENTIAL;
            else return;

            container.setStartupSelection(sel);
        } catch (Exception ignored) {}
    }

    private static void applyBox64Preset(Container container, Game game, JsonObject modePreset) {
        // Optional config: { "box64Preset": "STABILITY"|"CONSERVATIVE"|"INTERMEDIATE"|"PERFORMANCE" }
        // Also supports custom env vars: { "box64EnvVars": { "BOX64_DYNAREC_SAFEFLAGS": "0", ... } }
        try {
            String v = null;
            if (modePreset != null && modePreset.has("box64Preset")) v = modePreset.get("box64Preset").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("box64Preset")) v = obj.get("box64Preset").getAsString();
            }
            if (v != null && !v.isEmpty()) {
                container.setBox64Preset(v);
                Log.d("DownloadEngine", "Applied box64Preset=" + v);
            }

            // Apply custom Box64 env vars if specified (overrides preset values)
            JsonObject box64EnvVars = null;
            if (modePreset != null && modePreset.has("box64EnvVars")) {
                box64EnvVars = modePreset.getAsJsonObject("box64EnvVars");
            } else if (game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("box64EnvVars")) box64EnvVars = obj.getAsJsonObject("box64EnvVars");
            }

            if (box64EnvVars != null) {
                EnvVars env = new EnvVars(container.getEnvVars());
                for (String key : box64EnvVars.keySet()) {
                    String val = box64EnvVars.get(key).getAsString();
                    env.put(key, val);
                    Log.d("DownloadEngine", "Applied custom Box64 env: " + key + "=" + val);
                }
                container.setEnvVars(env.toString());
            }
        } catch (Exception e) {
            Log.w("DownloadEngine", "Failed to apply Box64 settings", e);
        }
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

    private static boolean isNfsUnderground2(Game game) {
        if (game == null) return false;
        if ("speed2.exe".equalsIgnoreCase(getExeName(game))) return true;
        String t = game.title;
        return t != null && t.toLowerCase(Locale.US).contains("underground 2");
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
