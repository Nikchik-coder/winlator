# Winlator RetroNexus Integration Guide

This document contains all the code needed to integrate RetroNexus features into Winlator. This is a self-contained reference that can be shared outside the repository.

## Table of Contents
1. [Overview](#overview)
2. [Java Source Files](#java-source-files)
3. [XML Resources](#xml-resources)
4. [Build Configuration](#build-configuration)
5. [S3/Cloud Storage](#s3cloud-storage)
6. [Database Schema](#database-schema)
7. [Integration Steps](#integration-steps)

---

## Overview

The RetroNexus customization adds the following features to Winlator:
- **New RetroNexus Style**: Teal/ink color palette distinct from stock Winlator
- **App Store Integration**: Games store with grid layout from Supabase
- **Loading Game Integration**: Download, install, and launch games directly
- **S3 Integration**: Cloud storage for game distribution
- **Controls Fixes**: Direct launch support bypassing desktop
- **Delete Game Feature**: Remove installed games with cleanup
- **Supabase Integration**: Backend for game catalog

---

## Java Source Files

### 1. Game.java
**Path**: `app/src/main/java/com/winlator/core/Game.java`

```java
package com.winlator.core;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

public class Game {
    public String id;
    public String title;
    public String description;
    public String thumbnail_url;
    public String download_url;

    @SerializedName(value = "config_preset", alternate = {"config"})
    public JsonElement config_preset;
}
```

### 2. SupabaseClient.java
**Path**: `app/src/main/java/com/winlator/core/SupabaseClient.java`

```java
package com.winlator.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.winlator.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class SupabaseClient {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public static void fetchGames(Callback<List<Game>> callback) {
        new Thread(() -> {
            String supabaseUrl = BuildConfig.SUPABASE_URL;
            String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;
            if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
                callback.onError(new IllegalStateException("Supabase not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in app/local.properties."));
                return;
            }

            Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/games?select=*")
                .addHeader("Accept", "application/json")
                .addHeader("apikey", supabaseAnonKey)
                .addHeader("Authorization", "Bearer " + supabaseAnonKey)
                .build();
                
            try (Response response = client.newCall(request).execute()) {
                String responseData = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + responseData);
                }

                Type listType = new TypeToken<List<Game>>(){}.getType();
                List<Game> games = gson.fromJson(responseData, listType);
                
                callback.onSuccess(games);
            } catch (Exception e) {
                callback.onError(e);
            }
        }).start();
    }
}
```

### 3. ImageLoader.java
**Path**: `app/src/main/java/com/winlator/core/ImageLoader.java`

```java
package com.winlator.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ImageLoader {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    public static void loadInto(ImageView imageView, String url) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;
                byte[] bytes = response.body().bytes();
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                cache.put(url, bitmap);

                mainHandler.post(() -> imageView.setImageBitmap(bitmap));
            }
        });
    }
}
```

### 4. DownloadEngine.java
**Path**: `app/src/main/java/com/winlator/core/DownloadEngine.java`

```java
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
        default void onProgress(int percent, String status) {}
        void onSuccess();
        void onError(Exception e);
    }

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
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

                status.percent = -1;
                status.status = "Installing…";
                reportProgress(trackingCallback, status.percent, status.status);

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

                status.percent = -1;
                status.status = "Applying fixes…";
                reportProgress(trackingCallback, status.percent, status.status);
                applyOptionalPatchZip(game, exeFile.getParentFile(), trackingCallback);

                try {
                    if (!installMarker.isFile()) {
                        boolean created = installMarker.createNewFile();
                        Log.d("DownloadEngine", "Created install marker (" + created + "): " + installMarker.getPath());
                    }
                } catch (Exception e) {
                    Log.w("DownloadEngine", "Failed to write install marker: " + installMarker.getPath(), e);
                }

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

            try {
                applyPreset(context, target, game, "auto");
                target.saveData();
            } catch (Exception ignored) {}

            File gamesDir = getGameInstallDir(game);
            String exeName = getExeName(game);
            File exeFile = findExeUnderGameDir(gamesDir, exeName);
            if (exeFile == null || !exeFile.isFile()) {
                exeFile = findAnyExe(gamesDir);
            }
            if (exeFile == null || !exeFile.isFile()) {
                showToast(context, "Game files missing. Reinstall.");
                return;
            }

            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", target.id);
            intent.putExtra("exec_path", exeFile.getPath());
            context.startActivity(intent);
        } catch (Exception e) {
            if (context != null) showToast(context, "Launch failed: " + e.getMessage());
        }
    }

    public static File getGameInstallDir(Game game) {
        String safe = (game.title != null ? game.title : "Game").replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(Environment.getExternalStorageDirectory(), "RetroNexus/Games/" + safe);
    }

    public static File findInstalledGameExe(Game game) {
        if (game == null) return null;
        File gamesDir = getGameInstallDir(game);
        if (!gamesDir.isDirectory()) return null;
        return findExeUnderGameDir(gamesDir, getExeName(game));
    }

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

    private static File findExeUnderGameDir(File gamesDir, String exeName) {
        if (gamesDir == null || exeName == null || exeName.isEmpty()) return null;
        File direct = new File(gamesDir, exeName);
        if (direct.isFile()) return direct;
        return findFileNamedRecursive(gamesDir, exeName);
    }

    private static File findAnyExe(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File child : list) {
            if (child.isFile() && child.getName().toLowerCase().endsWith(".exe")) {
                String name = child.getName().toLowerCase();
                if (name.contains("unins") || name.contains("setup") || name.contains("dxwebsetup")) continue;
                return child;
            }
        }
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
        String effectiveMode = mode;
        if ("auto".equals(mode)) effectiveMode = chooseAutoMode(context);

        String screenSize = "performance".equals(effectiveMode) ? "1024x576" : "1280x720";
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
        String baseDllOverrides = "dinput8,d3d9=n,b";
        Log.d("DownloadEngine", "Initial envVars: " + env.toString());

        try {
            if (preset != null && preset.has("envVars")) {
                Log.d("DownloadEngine", "Found envVars in preset");
                JsonObject envVars = preset.getAsJsonObject("envVars");
                for (String key : envVars.keySet()) {
                    String val = envVars.get(key).getAsString();
                    Log.d("DownloadEngine", "Preset envVar: " + key + "=" + val);
                    if ("WINEDLLOVERRIDES".equals(key) && val != null && !val.isEmpty()) {
                        val = val + "," + baseDllOverrides;
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
                        val = val + "," + baseDllOverrides;
                    }
                    env.put(key, val);
                }
            } else {
                Log.d("DownloadEngine", "No envVars found in preset or config_preset");
            }
        } catch (Exception e) {
            Log.e("DownloadEngine", "Error applying envVars", e);
        }

        if (game != null && "Flatout 2".equalsIgnoreCase(game.title)) {
            String currentOverrides = env.get("WINEDLLOVERRIDES");
            if (currentOverrides == null || currentOverrides.isEmpty()) {
                currentOverrides = baseDllOverrides;
            }
            env.put("WINEDLLOVERRIDES", "wineandroid.drv=d;mmdevapi=d;" + currentOverrides);
            Log.d("DownloadEngine", "FlatOut 2: Disabled Android audio driver and mmdevapi");
        } else if (!env.has("WINEDLLOVERRIDES")) {
            Log.d("DownloadEngine", "No WINEDLLOVERRIDES set, using default");
            env.put("WINEDLLOVERRIDES", baseDllOverrides);
        }

        String finalEnvVars = env.toString();
        Log.d("DownloadEngine", "Final envVars: " + finalEnvVars);
        container.setEnvVars(finalEnvVars);

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

        container.saveData();
        Log.d("DownloadEngine", "Container preset applied and saved for " + (game != null ? game.title : "unknown"));
    }

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
                if (totalGb > 0 && totalGb <= 4) return "performance";
            }
        } catch (Exception ignored) {}
        return "graphics";
    }

    private static void applyWinVersion(Container container, Game game, JsonObject modePreset) {
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
        try {
            String v = null;
            if (modePreset != null && modePreset.has("audioDriver")) v = modePreset.get("audioDriver").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("audioDriver")) v = obj.get("audioDriver").getAsString();
            }
            if (v == null || v.isEmpty()) return;
            container.setAudioDriver(v);

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
        try {
            String v = null;
            if (modePreset != null && modePreset.has("wincomponents")) v = modePreset.get("wincomponents").getAsString();
            if ((v == null || v.isEmpty()) && game != null && game.config_preset != null && game.config_preset.isJsonObject()) {
                JsonObject obj = game.config_preset.getAsJsonObject();
                if (obj.has("wincomponents")) v = obj.get("wincomponents").getAsString();
            }
            if (v == null || v.isEmpty()) return;
            container.setWinComponents(v);
        } catch (Exception ignored) {}
    }

    private static void applyStartupSelection(Container container, Game game, JsonObject modePreset) {
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
```

### 5. GamesStoreFragment.java
**Path**: `app/src/main/java/com/winlator/GamesStoreFragment.java`

```java
package com.winlator;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.core.Game;
import com.winlator.core.ImageLoader;
import com.winlator.core.SupabaseClient;

import java.util.ArrayList;
import java.util.List;

public class GamesStoreFragment extends Fragment {
    private GridView gridView;
    private GamesAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.games_store_fragment, container, false);
        gridView = view.findViewById(R.id.GridView);
        adapter = new GamesAdapter();
        gridView.setAdapter(adapter);

        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle("RetroNexus");
        }

        loadGames();

        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            Game game = adapter.getItem(position);
            MainActivity activity = (MainActivity) getActivity();
            activity.showFragment(new GameDetailFragment(game));
        });

        return view;
    }

    private void loadGames() {
        SupabaseClient.fetchGames(new SupabaseClient.Callback<List<Game>>() {
            @Override
            public void onSuccess(List<Game> games) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        adapter.setGames(games);
                    });
                }
            }

            @Override
            public void onError(Exception e) {
                if (getActivity() != null) {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : "Unknown error";
                    if (msg.length() > 220) msg = msg.substring(0, 220);
                    String toastText = "Failed to load games: " + msg;
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), toastText, Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private class GamesAdapter extends BaseAdapter {
        private List<Game> games = new ArrayList<>();

        public void setGames(List<Game> games) {
            this.games = games;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return games.size();
        }

        @Override
        public Game getItem(int position) {
            return games.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.game_grid_item, parent, false);
            }

            Game game = getItem(position);
            TextView title = convertView.findViewById(R.id.Title);
            title.setText(game.title);
            
            ImageView thumbnail = convertView.findViewById(R.id.Thumbnail);
            thumbnail.setImageDrawable(null);
            ImageLoader.loadInto(thumbnail, game.thumbnail_url);
            
            return convertView;
        }
    }
}
```

### 6. GameDetailFragment.java
**Path**: `app/src/main/java/com/winlator/GameDetailFragment.java`

```java
package com.winlator;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.Game;
import com.winlator.core.DownloadEngine;
import com.winlator.core.ImageLoader;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GameDetailFragment extends Fragment {
    private Game game;
    private Button installButton;
    private Button deleteButton;
    private LinearProgressIndicator installProgress;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable installPoller = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            DownloadEngine.InstallStatus s = DownloadEngine.getActiveInstallStatus(game);
            if (s != null) {
                applyInstallStatus(s);
                uiHandler.postDelayed(this, 500);
            } else {
                refreshInstallOrPlayButton();
            }
        }
    };

    public GameDetailFragment(Game game) {
        this.game = game;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.game_detail_fragment, container, false);

        TextView title = view.findViewById(R.id.GameTitle);
        TextView description = view.findViewById(R.id.GameDescription);
        ImageView gameImage = view.findViewById(R.id.GameImage);
        installButton = view.findViewById(R.id.InstallButton);
        deleteButton = view.findViewById(R.id.DeleteButton);
        installProgress = view.findViewById(R.id.InstallProgress);

        title.setText(game.title);
        description.setText(game.description);
        gameImage.setImageDrawable(null);
        ImageLoader.loadInto(gameImage, game.thumbnail_url);

        refreshInstallOrPlayButton();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshInstallOrPlayButton();
        uiHandler.removeCallbacks(installPoller);
        if (DownloadEngine.getActiveInstallStatus(game) != null) {
            uiHandler.post(installPoller);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(installPoller);
    }

    private void refreshInstallOrPlayButton() {
        if (getContext() == null || game == null) return;
        DownloadEngine.InstallStatus active = DownloadEngine.getActiveInstallStatus(game);
        if (active != null) {
            applyInstallStatus(active);
            return;
        }
        if (DownloadEngine.isReadyToPlay(getContext(), game)) {
            installButton.setText("PLAY");
            installButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.retronexus_success)));
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> launchGame());
            deleteButton.setVisibility(View.VISIBLE);
            deleteButton.setEnabled(true);
            deleteButton.setOnClickListener(v -> confirmDelete());
            installProgress.setVisibility(View.GONE);
        } else {
            installButton.setText("INSTALL");
            installButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.retronexus_accent)));
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> startInstall("auto"));
            deleteButton.setVisibility(View.GONE);
            installProgress.setVisibility(View.GONE);
        }
    }

    private void applyInstallStatus(DownloadEngine.InstallStatus s) {
        installButton.setEnabled(false);
        deleteButton.setVisibility(View.GONE);
        String text = (s.status != null && !s.status.isEmpty()) ? s.status : "Installing…";
        installButton.setText(text);
        installProgress.setVisibility(View.VISIBLE);
        if (s.percent < 0) {
            if (!installProgress.isIndeterminate()) {
                installProgress.hide();
                installProgress.setVisibility(View.GONE);
                installProgress.setIndeterminate(true);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.show();
            }
        } else {
            if (installProgress.isIndeterminate()) {
                installProgress.hide();
                installProgress.setVisibility(View.GONE);
                installProgress.setIndeterminate(false);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.show();
            }
            installProgress.setProgress(s.percent);
        }
        installProgress.show();
    }

    private void confirmDelete() {
        if (getContext() == null || game == null) return;
        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setCancelable(false);
        dialog.setMessage("Delete \"" + game.title + "\" from this phone?");
        dialog.setOnConfirmCallback(this::deleteInstalledGame);
        dialog.show();
    }

    private void deleteInstalledGame() {
        if (getContext() == null || game == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();

        installButton.setEnabled(false);
        deleteButton.setEnabled(false);
        installButton.setText("DELETING...");
        installProgress.hide();
        installProgress.setVisibility(View.GONE);
        installProgress.setIndeterminate(true);
        installProgress.setVisibility(View.VISIBLE);
        installProgress.show();

        new Thread(() -> {
            Exception error = null;
            try {
                File exeFile = DownloadEngine.findInstalledGameExe(game);

                ContainerManager manager = new ContainerManager(appContext);
                Container target = null;
                for (Container c : manager.getContainers()) {
                    String gameId = game.id != null ? game.id : "";
                    String cGameId = c.getExtra("retronexusGameId");
                    String cExec = c.getExtra("retronexusExecPath");
                    if (!gameId.isEmpty() && gameId.equals(cGameId)) {
                        target = c;
                        break;
                    }
                    if (exeFile != null && exeFile.isFile() && exeFile.getPath().equals(cExec)) {
                        target = c;
                        break;
                    }
                    if (game.title != null && game.title.equals(c.getName())) {
                        target = c;
                        break;
                    }
                }

                File gameDir = DownloadEngine.getGameInstallDir(game);
                String installDirOverride = target != null ? target.getExtra("retronexusGameInstallDir") : "";
                if (installDirOverride != null && !installDirOverride.isEmpty()) {
                    gameDir = new File(installDirOverride);
                }
                if (gameDir.exists()) FileUtils.delete(gameDir);

                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir != null) {
                    String safe = gameDir.getName();
                    File legacy = new File(downloadsDir, "Games/" + safe);
                    if (legacy.exists()) FileUtils.delete(legacy);
                }

                if (target != null) {
                    CountDownLatch latch = new CountDownLatch(1);
                    manager.removeContainerAsync(target, latch::countDown);
                    latch.await(120, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                error = e;
            }

            if (getActivity() != null) {
                Exception finalError = error;
                getActivity().runOnUiThread(() -> {
                    installProgress.hide();
                    installProgress.setVisibility(View.GONE);
                    if (finalError != null) {
                        Toast.makeText(getContext(), "Delete failed: " + finalError.getMessage(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), "Deleted.", Toast.LENGTH_SHORT).show();
                    }
                    refreshInstallOrPlayButton();
                });
            }
        }).start();
    }

    private void startInstall(String mode) {
        installButton.setText("INSTALL");
        installButton.setEnabled(false);
        deleteButton.setVisibility(View.GONE);
        installProgress.setVisibility(View.VISIBLE);
        installProgress.setIndeterminate(false);
        installProgress.setProgress(0);
        installProgress.show();
        uiHandler.removeCallbacks(installPoller);
        uiHandler.post(installPoller);
        if (getActivity() != null) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        DownloadEngine.downloadAndInstall(getContext(), game, mode, new DownloadEngine.Callback() {
            @Override
            public void onProgress(int percent, String status) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    installButton.setText(status);
                    if (percent < 0) {
                        if (!installProgress.isIndeterminate()) {
                            installProgress.hide();
                            installProgress.setVisibility(View.GONE);
                            installProgress.setIndeterminate(true);
                            installProgress.setVisibility(View.VISIBLE);
                            installProgress.show();
                        }
                    } else {
                        if (installProgress.isIndeterminate()) {
                            installProgress.hide();
                            installProgress.setVisibility(View.GONE);
                            installProgress.setIndeterminate(false);
                            installProgress.setVisibility(View.VISIBLE);
                            installProgress.show();
                        }
                        installProgress.setProgress(percent);
                    }
                });
            }

            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        installProgress.hide();
                        installProgress.setVisibility(View.GONE);
                        installButton.setText("PLAY");
                        installButton.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.retronexus_success)));
                        installButton.setEnabled(true);
                        installButton.setOnClickListener(v -> launchGame());
                    });
                }
            }

            @Override
            public void onError(Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        installProgress.hide();
                        installProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        installButton.setText("INSTALL");
                        installButton.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.retronexus_accent)));
                        installButton.setEnabled(true);
                    });
                }
            }
        });
    }

    private void launchGame() {
        DownloadEngine.launchGame(getContext(), game);
    }
}
```

### 7. MainActivity.java Updates
**Path**: `app/src/main/java/com/winlator/MainActivity.java`

**Key changes to integrate**:

```java
// Add to onCreate()
@Override
protected void onCreate(Bundle savedInstanceState) {
    // ... existing code ...
    
    preferences = PreferenceManager.getDefaultSharedPreferences(this);
    applyRetroNexusMainChrome();  // ADD THIS LINE
    
    // ... rest of existing code ...
}

// Add method applyRetroNexusMainChrome()
private void applyRetroNexusMainChrome() {
    int theme = preferences.getInt("app_theme", SettingsFragment.APP_THEME_DARK);
    if (theme != SettingsFragment.APP_THEME_DARK) return;
    Toolbar toolbar = findViewById(R.id.Toolbar);
    toolbar.setBackgroundResource(R.drawable.toolbar_retronexus_bg);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.retronexus_primary_dark));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.retronexus_bg));
    }
}

// Update default menu item to Market
int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.menu_item_market;

// Add case in onNavigationItemSelected()
case R.id.menu_item_market:
    showFragment(new GamesStoreFragment());
    break;

// Update onBackPressed()
@Override
public void onBackPressed() {
    if (currentFragment != null && currentFragment.isVisible()) {
        if (currentFragment instanceof BaseFileManagerFragment) {
            BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
            if (fileManagerFragment.onBackPressed()) return;
        }
        else if (currentFragment instanceof ContainersFragment || currentFragment instanceof GamesStoreFragment) {
            finish();
        }
    }
    showFragment(new ContainersFragment());
}
```

### 8. XServerDisplayActivity.java Updates
**Path**: `app/src/main/java/com/winlator/XServerDisplayActivity.java`

**Key changes**:

```java
// In onCreate(), update the debug logging section:
final boolean directLaunch = getIntent().hasExtra("exec_path");
boolean enableLogs = directLaunch || preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
if (directLaunch) {
    ProcessHelper.addDebugCallback(line -> Log.d("Guest", line));
}

// In getWineStartCommand(), add handling for exec_path:
else {
    Intent intent = getIntent();
    if (intent.hasExtra("exec_path")) {
        String unixExecPath = intent.getStringExtra("exec_path");
        execPath = WineUtils.unixToDOSPath(unixExecPath, container);
        if (execPath == null || execPath.isEmpty()) {
            execPath = unixExecPath != null ? ("Z:" + unixExecPath.replace("/", "\\")) : null;
        }

        if (execPath.endsWith(".lnk")) {
            cmdArgs = "\""+execPath+"\"";
            execPath = null;
        }
    }
}

// Update termination callback:
guestProgramLauncherComponent.setTerminationCallback((status) -> {
    Log.e("Guest", "Guest program exited with code: " + status);
    final boolean direct = getIntent().hasExtra("exec_path");
    if (direct && !guestWindowShown) {
        runOnUiThread(() ->
            Toast.makeText(this, "Game exited before showing a window (code " + status + "). Check logs.", Toast.LENGTH_LONG).show()
        );
    }
    exit();
});
```

---

## XML Resources

### 1. colors.xml
**Path**: `app/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="retronexus_bg">#06090D</color>
    <color name="retronexus_surface">#0E141C</color>
    <color name="retronexus_elevated">#121E2A</color>
    <color name="retronexus_primary">#0D4D45</color>
    <color name="retronexus_primary_dark">#051018</color>
    <color name="retronexus_accent">#3FF5E0</color>
    <color name="retronexus_accent_muted">#00A896</color>
    <color name="retronexus_text_primary">#E8F0F7</color>
    <color name="retronexus_text_secondary">#8FA3B8</color>
    <color name="retronexus_placeholder">#2A3544</color>
    <color name="retronexus_success">#34D399</color>
</resources>
```

### 2. toolbar_retronexus_bg.xml
**Path**: `app/src/main/res/drawable/toolbar_retronexus_bg.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="0"
        android:endColor="#0A1620"
        android:startColor="#0D4D45"
        android:type="linear" />
</shape>
```

### 3. game_detail_fragment.xml
**Path**: `app/src/main/res/layout/game_detail_fragment.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:fillViewport="true"
        android:scrollbars="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="@color/retronexus_elevated"
                app:cardCornerRadius="16dp"
                app:cardElevation="2dp">

                <ImageView
                    android:id="@+id/GameImage"
                    android:layout_width="match_parent"
                    android:layout_height="200dp"
                    android:background="@color/retronexus_placeholder"
                    android:scaleType="centerCrop" />
            </com.google.android.material.card.MaterialCardView>

            <TextView
                android:id="@+id/GameTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="Game Title"
                android:textColor="?attr/colorPrimaryText"
                android:textSize="24sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/GameDescription"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:lineSpacingMultiplier="1.15"
                android:text="Game Description"
                android:textColor="?attr/colorSecondaryText"
                android:textSize="16sp" />
        </LinearLayout>
    </ScrollView>

    <com.google.android.material.progressindicator.LinearProgressIndicator
        android:id="@+id/InstallProgress"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:visibility="gone"
        android:indeterminate="false"
        app:indicatorColor="@color/retronexus_accent"
        app:trackColor="@color/retronexus_placeholder"
        app:trackThickness="4dp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="horizontal">

        <Button
            android:id="@+id/InstallButton"
            android:layout_width="0dp"
            android:layout_height="64dp"
            android:layout_weight="1"
            android:text="INSTALL"
            android:textAllCaps="true"
            android:textColor="@android:color/white"
            android:textSize="18sp"
            android:textStyle="bold"
            android:backgroundTint="@color/retronexus_accent" />

        <Button
            android:id="@+id/DeleteButton"
            android:layout_width="0dp"
            android:layout_height="64dp"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:text="DELETE"
            android:textAllCaps="true"
            android:textColor="@android:color/white"
            android:textSize="18sp"
            android:textStyle="bold"
            android:backgroundTint="@android:color/holo_red_dark"
            android:visibility="gone" />
    </LinearLayout>

</LinearLayout>
```

### 4. game_grid_item.xml
**Path**: `app/src/main/res/layout/game_grid_item.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="6dp"
    android:clickable="false"
    android:focusable="false"
    android:descendantFocusability="blocksDescendants"
    app:cardBackgroundColor="@color/retronexus_elevated"
    app:cardCornerRadius="14dp"
    app:cardElevation="3dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="10dp">

        <ImageView
            android:id="@+id/Thumbnail"
            android:layout_width="100dp"
            android:layout_height="140dp"
            android:background="@color/retronexus_placeholder"
            android:scaleType="centerCrop" />

        <TextView
            android:id="@+id/Title"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:ellipsize="end"
            android:maxLines="2"
            android:text="Game Title"
            android:textAlignment="center"
            android:textColor="?attr/colorPrimaryText"
            android:textSize="14sp" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

### 5. games_store_fragment.xml
**Path**: `app/src/main/res/layout/games_store_fragment.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="8dp">

    <GridView
        android:id="@+id/GridView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:numColumns="auto_fit"
        android:columnWidth="132dp"
        android:horizontalSpacing="8dp"
        android:verticalSpacing="8dp"
        android:stretchMode="columnWidth"
        android:drawSelectorOnTop="false"
        android:listSelector="?android:attr/selectableItemBackground" />

</LinearLayout>
```

### 6. main_menu.xml
**Path**: `app/src/main/res/menu/main_menu.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu
  xmlns:android="http://schemas.android.com/apk/res/android">
    <group android:checkableBehavior="single">
        <item android:icon="@drawable/icon_market" android:id="@+id/menu_item_market" android:title="@string/market" />
        <item android:icon="@drawable/icon_shortcut" android:id="@+id/menu_item_shortcuts" android:title="@string/shortcuts" />
        <item android:icon="@drawable/icon_games" android:id="@+id/menu_item_containers" android:title="@string/containers" />
        <item android:icon="@drawable/icon_input_controls" android:id="@+id/menu_item_input_controls" android:title="@string/input_controls" />
        <item android:icon="@drawable/icon_settings" android:id="@+id/menu_item_settings" android:title="@string/settings" />
        <item android:icon="@drawable/icon_about" android:id="@+id/menu_item_about" android:title="@string/about" />
    </group>
</menu>
```

### 7. icon_market.xml
**Path**: `app/src/main/res/drawable/icon_market.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#00000000"
        android:pathData="M24,28a3,3 0 1,0 -3,3h3zm3,3a3,3 0 1,0 -3,-3v3zm-3,3a3,3 0 1,0 3,-3h-3zm-3,-3a3,3 0 1,0 3,3v-3zm-5,-19.564a8,8 0 1,0 16,0"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#00000000"
        android:pathData="M9.5,5.5L38.5,5.5A4,4 0 0,1 42.5,9.5L42.5,38.5A4,4 0 0,1 38.5,42.5L9.5,42.5A4,4 0 0,1 5.5,38.5L5.5,9.5A4,4 0 0,1 9.5,5.5z"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

### 8. styles.xml (Key additions)
**Path**: `app/src/main/res/values/styles.xml`

**Update AppThemeDark**:
```xml
<style name="AppThemeDark" parent="@style/AppThemeBase">
    <item name="colorAccent">@color/retronexus_accent</item>
    <item name="colorPrimary">@color/retronexus_primary</item>
    <item name="colorPrimaryDark">@color/retronexus_primary_dark</item>
    <item name="colorControlNormal">#5A6B7E</item>
    <item name="colorPrimaryText">@color/retronexus_text_primary</item>
    <item name="colorSecondaryText">@color/retronexus_text_secondary</item>
    <item name="colorPrimarySurface">@color/retronexus_bg</item>
    <item name="colorSecondarySurface">@color/retronexus_elevated</item>
    <item name="colorPrimaryVariant">@color/retronexus_accent</item>
    <item name="colorSecondaryVariant">#2E3D4F</item>
    <item name="colorError">#F87171</item>
    <item name="android:statusBarColor">@color/retronexus_primary_dark</item>
    <item name="android:navigationBarColor">@color/retronexus_bg</item>
    <item name="android:windowLightStatusBar">false</item>
    <item name="android:toolbarStyle">@style/ToolbarRetroDark</item>
    <item name="toolbarStyle">@style/ToolbarRetroDark</item>
    <!-- ... other items ... -->
</style>

<!-- Add ToolbarRetroDark style -->
<style name="ToolbarRetroDark" parent="@style/Toolbar">
    <item name="android:background">@drawable/toolbar_retronexus_bg</item>
    <item name="titleTextColor">@color/retronexus_text_primary</item>
</style>
```

### 9. strings.xml (Key additions)
**Path**: `app/src/main/res/values/strings.xml`

```xml
<string name="app_name">RetroNexus</string>
<string name="nav_header_tagline">Classic PC games on Android</string>
<string name="about_tagline">Wine and Box64/Box86 on mobile — open-source components, RetroNexus shell.</string>
<string name="market">Market</string>
```

---

## Build Configuration

### build.gradle
**Path**: `app/build.gradle`

```groovy
apply plugin: 'com.android.application'

def localProps = new Properties()
def localPropsFile = rootProject.file('local.properties')
if (localPropsFile.exists()) {
    localPropsFile.withInputStream { stream ->
        localProps.load(stream)
    }
}

def escForBuildConfig = { String s ->
    if (s == null) return ""
    return s.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace 'com.winlator'
    compileSdk 34

    defaultConfig {
        applicationId 'com.winlator'
        minSdkVersion 26
        targetSdkVersion 28
        versionCode 27
        versionName "11.0.2"

        def supabaseUrl = localProps.getProperty('SUPABASE_URL', '')
        def supabaseAnonKey = localProps.getProperty('SUPABASE_ANON_KEY', '')
        buildConfigField 'String', 'SUPABASE_URL', "\"${escForBuildConfig(supabaseUrl)}\""
        buildConfigField 'String', 'SUPABASE_ANON_KEY', "\"${escForBuildConfig(supabaseAnonKey)}\""
    }

    buildTypes {
        debug {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'

            ndk {
                abiFilters 'arm64-v8a'
            }
        }
    }

    lintOptions {
        checkReleaseBuilds false
    }

    ndkVersion '24.0.8215888'

    externalNativeBuild {
        cmake {
            version '3.28.3'
            path 'src/main/cpp/CMakeLists.txt'
        }
    }

    packagingOptions {
        jniLibs {
            pickFirsts += ['**/*.so']
        }
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.4.0'
    implementation 'androidx.preference:preference:1.2.1'
    implementation 'com.google.android.material:material:1.4.0'
    implementation 'com.github.luben:zstd-jni:1.5.2-3@aar'
    implementation 'org.tukaani:xz:1.7'
    implementation 'org.apache.commons:commons-compress:1.20'
    implementation 'com.squareup.okhttp3:okhttp:4.11.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

### local.properties (create this file)
**Path**: `app/local.properties`

```properties
SUPABASE_URL=https://jkhpdbfdhskznxbyxxhc.supabase.co
SUPABASE_ANON_KEY=sb_publishable_FQDQMSSWyn6ES5PZuFLOww_Yaeb6sHe
```

---

## S3/Cloud Storage

### config.py
**Path**: `s3/config.py`

```python
from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    _project_root: Path = Path(__file__).resolve().parents[1]
    model_config = SettingsConfigDict(
        env_file=str(_project_root / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    r2_account_id: str | None = Field(default=None, alias="R2_ACCOUNT_ID")
    r2_access_key: str | None = Field(default=None, alias="R2_ACCESS_KEY")
    r2_secret_key: str | None = Field(default=None, alias="R2_SECRET_KEY")
    r2_bucket: str | None = Field(default=None, alias="R2_BUCKET")
    s3_api_endpoint: str | None = Field(default=None, alias="S3_API_ENDPOINT")
    s3_presign_expires_seconds: int = Field(default=3600, alias="S3_PRESIGN_EXPIRES_SECONDS")
    s3_temp_object_ttl_seconds: int = Field(default=3600, alias="S3_TEMP_OBJECT_TTL_SECONDS")
    s3_cleanup_interval_seconds: int = Field(default=120, alias="S3_CLEANUP_INTERVAL_SECONDS")

    log_incoming_file_id: bool = Field(default=True, alias="LOG_INCOMING_FILE_ID")
    echo_incoming_file_id_to_user: bool = Field(default=False, alias="ECHO_INCOMING_FILE_ID_TO_USER")
    log_dubbed_result_file_id: bool = Field(default=True, alias="LOG_DUBBED_RESULT_FILE_ID")


def load_settings() -> Settings:
    return Settings()


def r2_endpoint_url(settings: Settings) -> str | None:
    ep = (settings.s3_api_endpoint or "").strip()
    if ep:
        return ep
    aid = (settings.r2_account_id or "").strip()
    if aid:
        return f"https://{aid}.r2.cloudflarestorage.com"
    return None


def r2_configured(settings: Settings) -> bool:
    return bool(
        settings.r2_access_key
        and settings.r2_secret_key
        and settings.r2_bucket
        and r2_endpoint_url(settings)
    )
```

### upload_file.py
**Path**: `s3/upload_file.py`

```python
"""One-off upload: pushes a local file to the configured R2/S3 bucket (see .env)."""

from __future__ import annotations

import sys
import boto3
from pathlib import Path

import os
sys.path.insert(0, os.path.abspath(os.path.dirname(os.path.dirname(__file__))))

from s3.config import load_settings, r2_configured, r2_endpoint_url

LOCAL_ZIP = Path(
    "/home/nik/.wine/drive_c/Program Files (x86)/Empire Interactive/FlatOut2.zip"
)
S3_OBJECT_KEY = "Flatout2/FlatOut2.zip"


def _log(msg: str) -> None:
    print(msg, flush=True)


def abort_paused_uploads(settings) -> None:
    """Finds and aborts all incomplete multipart uploads in the bucket."""
    _log(f"Connecting to S3/R2 to find paused uploads in bucket {settings.r2_bucket}...")
    client = boto3.client(
        "s3",
        endpoint_url=r2_endpoint_url(settings),
        aws_access_key_id=settings.r2_access_key,
        aws_secret_access_key=settings.r2_secret_key,
        region_name="auto"
    )
    
    response = client.list_multipart_uploads(Bucket=settings.r2_bucket)
    uploads = response.get("Uploads", [])
    
    if not uploads:
        _log("No ongoing multipart uploads found.")
        return

    _log(f"Found {len(uploads)} ongoing multipart upload(s). Aborting...")
    for upload in uploads:
        key = upload["Key"]
        upload_id = upload["UploadId"]
        _log(f" -> Aborting UploadId: {upload_id} for Key: {key}")
        client.abort_multipart_upload(
            Bucket=settings.r2_bucket,
            Key=key,
            UploadId=upload_id
        )
    _log("Successfully aborted all paused multipart uploads.")


def main() -> None:
    settings = load_settings()
    if not r2_configured(settings):
        sys.stderr.write(
            "R2/S3 is not configured. Set R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET, "
            "and R2_ACCOUNT_ID or S3_API_ENDPOINT in the repo .env.\n"
        )
        raise SystemExit(1)

    if "--abort" in sys.argv:
        abort_paused_uploads(settings)
        return

    if not LOCAL_ZIP.is_file():
        sys.stderr.write(f"File not found: {LOCAL_ZIP}\n")
        raise SystemExit(1)

    size_mb = LOCAL_ZIP.stat().st_size / (1024 * 1024)
    _log(f"Uploading {LOCAL_ZIP} ({size_mb:.1f} MiB) to key {S3_OBJECT_KEY!r} …")

    client = boto3.client(
        "s3",
        endpoint_url=r2_endpoint_url(settings),
        aws_access_key_id=settings.r2_access_key,
        aws_secret_access_key=settings.r2_secret_key,
        region_name="auto"
    )
    
    client.upload_file(str(LOCAL_ZIP), settings.r2_bucket, S3_OBJECT_KEY)
    
    _log(f"Done: s3://{settings.r2_bucket}/{S3_OBJECT_KEY}")


if __name__ == "__main__":
    main()
```

---

## Database Schema

### supabase_schema.sql
**Path**: `supabase_schema.sql`

```sql
-- Create the games table
CREATE TABLE games (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title TEXT NOT NULL,
    description TEXT,
    thumbnail_url TEXT,
    download_url TEXT,
    config_preset JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Enable Row Level Security
ALTER TABLE games ENABLE ROW LEVEL SECURITY;

-- Create a policy that allows anyone to read games
CREATE POLICY "Allow public read access to games" ON games
    FOR SELECT
    USING (true);
```

### Sample Game Configuration (JSONB)
```json
{
  "exe": "speed.exe",
  "patchZipUrl": "https://your-cdn.com/nfs_mw_widescreen_fix.zip",
  "winVersion": "winxp",
  "audioDriver": "pulseaudio",
  "graphics": {
    "screenSize": "1280x720",
    "dxwrapper": "DXVK",
    "driver": "turnip"
  },
  "performance": {
    "screenSize": "1024x576",
    "dxwrapper": "WineD3D"
  },
  "envVars": {
    "WINEDLLOVERRIDES": "dinput8,d3d9=n,b"
  },
  "box64Preset": "PERFORMANCE",
  "wincomponents": "directsound=1,directplay=1"
}
```

---

## Icons

The new RetroNexus icons are **vector drawables (XML)** located in:

### Navigation Menu Icons (New/Updated)
**Path**: `app/src/main/res/drawable/`

| Icon | File | Description |
|------|------|-------------|
| Market | `icon_market.xml` | Store/Market icon (shopping bag) |
| Games | `icon_games.xml` | Game controller icon |
| Shortcuts | `icon_shortcut.xml` | Lightning bolt / shortcut icon |
| Input Controls | `icon_input_controls.xml` | D-pad controller icon |
| Settings | `icon_settings.xml` | Sliders/settings icon |
| About | `icon_about.xml` | Info circle icon |

### New Icon Source Code

#### icon_market.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#00000000"
        android:pathData="M24,28a3,3 0 1,0 -3,3h3zm3,3a3,3 0 1,0 -3,-3v3zm-3,3a3,3 0 1,0 3,-3h-3zm-3,-3a3,3 0 1,0 3,3v-3zm-5,-19.564a8,8 0 1,0 16,0"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#00000000"
        android:pathData="M9.5,5.5L38.5,5.5A4,4 0 0,1 42.5,9.5L42.5,38.5A4,4 0 0,1 38.5,42.5L9.5,42.5A4,4 0 0,1 5.5,38.5L5.5,9.5A4,4 0 0,1 9.5,5.5z"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

#### icon_games.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#00000000"
        android:pathData="M14.25,23.695v-5.427m-2.713,2.713h5.426m0.224,-7.289v-1.613c0,-0.58 -0.471,-1.052 -1.052,-1.052h-3.77c-0.58,0 -1.052,0.471 -1.052,1.052v1.77"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#00000000"
        android:pathData="m29.044,27.736l4.553,6.83a5.405,5.405 0 0,0 9.786,-4.108c-0.984,-4.67 -1.769,-9.026 -2.572,-11.982c-0.846,-3.113 -3.884,-5.28 -7.284,-5.28c-2.052,0 -3.912,0.81 -5.288,2.123h-8.478a7.64,7.64 0 0,0 -5.287,-2.124c-3.401,0 -6.44,2.168 -7.285,5.28c-0.803,2.957 -1.588,7.313 -2.572,11.983a5.405,5.405 0 0,0 9.787,4.107l4.553,-6.83z"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#00000000"
        android:pathData="M30.813,13.692v-1.613c0,-0.58 0.471,-1.052 1.052,-1.052h3.77c0.58,0 1.052,0.471 1.052,1.052v1.77"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M31.03,20.981m-0.75,0a0.75,0.75 0 1,1 1.5,0a0.75,0.75 0 1,1 -1.5,0" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M36.463,20.981m-0.75,0a0.75,0.75 0 1,1 1.5,0a0.75,0.75 0 1,1 -1.5,0" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M33.75,18.268m-0.75,0a0.75,0.75 0 1,1 1.5,0a0.75,0.75 0 1,1 -1.5,0" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M33.75,23.695m-0.785,0a0.785,0.785 0 1,1 1.57,0a0.785,0.785 0 1,1 -1.57,0" />
</vector>
```

#### icon_shortcut.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#00000000"
        android:pathData="M43.5,24L27.629,8.129v10.497H7.142L4.5,29.374h23.129v10.497zm-31.175,-5.374L9.683,29.374m7.825,-10.748l-2.641,10.748"
        android:strokeWidth="1.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

#### icon_input_controls.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <group
        android:scaleX="2"
        android:scaleY="2">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M14.828,6.343l1.415,-1.414L12,0.686L7.757,4.93l1.415,1.414L12,3.515zm-9.899,9.9l1.414,-1.415L3.515,12l2.828,-2.828L4.93,7.757L0.686,12zm2.828,2.828L12,23.314l4.243,-4.243l-1.415,-1.414L12,20.485l-2.828,-2.828zm9.9,-9.899L20.485,12l-2.828,2.828l1.414,1.415L23.314,12L19.07,7.757z" />
        <path
            android:fillColor="#FFFFFFFF"
            android:fillType="evenOdd"
            android:pathData="M12,8a4,4 0 1,1 0,8a4,4 0 0,1 0,-8m0,2a2,2 0 1,1 0,4a2,2 0 0,1 0,-4" />
    </group>
</vector>
```

#### icon_settings.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <group
        android:scaleX="1.5"
        android:scaleY="1.5">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M2.909,26.182h1.939v4.848H2.909z" />
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M4.848,16.62V0H2.91v16.62a3.879,3.879 0 1,0 1.94,0m-0.97,5.683a1.94,1.94 0 1,1 0,-3.879a1.94,1.94 0 0,1 0,3.879" />
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M14.545,16.485h1.939V31.03h-1.939z" />
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M16.485,6.924V0h-1.94v6.924a3.879,3.879 0 1,0 1.94,0m-0.97,5.682a1.94,1.94 0 1,1 0,-3.879a1.94,1.94 0 0,1 0,3.88" />
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M26.182,26.182h1.939v4.848h-1.939z" />
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M28.121,16.62V0h-1.94v16.62a3.879,3.879 0 1,0 1.94,0m-0.97,5.683a1.94,1.94 0 1,1 0,-3.879a1.94,1.94 0 0,1 0,3.879" />
    </group>
</vector>
```

#### icon_about.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <group
        android:scaleX="0.09375"
        android:scaleY="0.09375">
        <path
            android:fillColor="#FFFFFFFF"
            android:fillType="evenOdd"
            android:pathData="M256,42.667C138.18,42.667 42.667,138.179 42.667,256c0,117.82 95.513,213.334 213.333,213.334c117.822,0 213.334,-95.513 213.334,-213.334S373.822,42.667 256,42.667m0,384c-94.105,0 -170.666,-76.561 -170.666,-170.667S161.894,85.334 256,85.334c94.107,0 170.667,76.56 170.667,170.666S350.107,426.667 256,426.667m26.714,-256c0,15.468 -11.262,26.667 -26.497,26.667c-15.851,0 -26.837,-11.2 -26.837,-26.963c0,-15.15 11.283,-26.37 26.837,-26.37c15.235,0 26.497,11.22 26.497,26.666m-48,64h42.666v128h-42.666z" />
    </group>
</vector>
```

### Legacy Icons (PNG - Unchanged)
**Path**: `app/src/main/res/drawable-hdpi/` and `drawable-xxhdpi/`

These are the original Winlator PNG icons (unchanged):
- `icon_add.png`, `icon_edit.png`, `icon_remove.png`, etc.
- `icon_action_bar_*.png` (action bar icons)
- `icon_popup_menu_*.png` (popup menu icons)

### App Launcher Icons (PNG)
**Path**: `app/src/main/res/mipmap-*/`

| Folder | Files |
|--------|-------|
| `mipmap-hdpi/` | ic_launcher.png, ic_launcher_round.png, ic_launcher_foreground.png |
| `mipmap-mdpi/` | ic_launcher.png, ic_launcher_round.png, ic_launcher_foreground.png |
| `mipmap-xhdpi/` | ic_launcher.png, ic_launcher_round.png, ic_launcher_foreground.png |
| `mipmap-xxhdpi/` | ic_launcher.png, ic_launcher_round.png, ic_launcher_foreground.png |
| `mipmap-xxxhdpi/` | ic_launcher.png, ic_launcher_round.png, ic_launcher_foreground.png |
| `mipmap-anydpi-v26/` | ic_launcher.xml, ic_launcher_round.xml |

### How to Transfer Icons to New Repo

**Option 1: Copy XML Files Directly (Recommended)**

Since the new icons are vector XML files, copy these 6 files to the new repo:

```bash
# Create drawable directory if it doesn't exist
mkdir -p /path/to/new/repo/app/src/main/res/drawable

# Copy the 6 new vector icons
cp app/app/src/main/res/drawable/icon_market.xml           /path/to/new/repo/app/src/main/res/drawable/
cp app/app/src/main/res/drawable/icon_games.xml            /path/to/new/repo/app/src/main/res/drawable/
cp app/app/src/main/res/drawable/icon_shortcut.xml         /path/to/new/repo/app/src/main/res/drawable/
cp app/app/src/main/res/drawable/icon_input_controls.xml   /path/to/new/repo/app/src/main/res/drawable/
cp app/app/src/main/res/drawable/icon_settings.xml         /path/to/new/repo/app/src/main/res/drawable/
cp app/app/src/main/res/drawable/icon_about.xml            /path/to/new/repo/app/src/main/res/drawable/

# Copy toolbar background drawable
cp app/app/src/main/res/drawable/toolbar_retronexus_bg.xml /path/to/new/repo/app/src/main/res/drawable/
```

**Option 2: Copy from Document**
The XML source code above can be copied directly into new files in Android Studio.

**Option 3: Copy All Drawable Resources**
```bash
# Copy entire drawable directories
rsync -av app/app/src/main/res/drawable* /path/to/new/repo/app/src/main/res/

# Copy mipmap directories (launcher icons)
rsync -av app/app/src/main/res/mipmap* /path/to/new/repo/app/src/main/res/
```

### Icon References in Code

The icons are referenced in:

1. **main_menu.xml** - Navigation drawer menu:
```xml
<item android:icon="@drawable/icon_market" android:id="@+id/menu_item_market" ... />
<item android:icon="@drawable/icon_games" android:id="@+id/menu_item_containers" ... />
```

2. **MainActivity.java** - Action bar:
```java
actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
```

3. **strings.xml** - App name uses launcher icon:
```xml
<string name="app_name">RetroNexus</string>
```

---

## Integration Steps

### 1. Add Dependencies
Add to `app/build.gradle`:
```groovy
implementation 'com.squareup.okhttp3:okhttp:4.11.0'
implementation 'com.google.code.gson:gson:2.10.1'
```

### 2. Add Supabase Config to local.properties
**File**: `app/local.properties`

```properties
SUPABASE_URL=https://jkhpdbfdhskznxbyxxhc.supabase.co
SUPABASE_ANON_KEY=sb_publishable_FQDQMSSWyn6ES5PZuFLOww_Yaeb6sHe
```

### 3. Copy Java Files
Copy all Java files from this document to:
- `app/src/main/java/com/winlator/core/` (for Game.java, SupabaseClient.java, etc.)
- `app/src/main/java/com/winlator/` (for GamesStoreFragment.java, GameDetailFragment.java)

### 4. Copy XML Resources
Copy all XML files to:
- `app/src/main/res/values/` (colors.xml, strings.xml additions)
- `app/src/main/res/drawable/` (toolbar_retronexus_bg.xml, icon_market.xml)
- `app/src/main/res/layout/` (game_*.xml, games_store_fragment.xml)
- `app/src/main/res/menu/` (main_menu.xml)

### 5. Update MainActivity.java
- Add `applyRetroNexusMainChrome()` call in onCreate()
- Add `case R.id.menu_item_market:` in onNavigationItemSelected()
- Update default menu item to `R.id.menu_item_market`

### 6. Update XServerDisplayActivity.java
- Add direct launch handling for `exec_path` intent extra
- Update termination callback for error reporting

### 7. Update styles.xml
- Update `AppThemeDark` with RetroNexus colors
- Add `ToolbarRetroDark` style

### 8. Create Supabase Project
1. Create project at supabase.com
2. Run `supabase_schema.sql` in SQL Editor
3. Add games to the table with proper config_preset JSON

### 9. Upload Games to S3/R2
1. Configure `.env` with R2 credentials
2. Run `python s3/upload_file.py` to upload game ZIPs
3. Update `download_url` in Supabase with the S3 URLs

---

## Android SDK Setup for New Fork

Winlator requires the **full Android SDK** with NDK because it has C/C++ native components (Wine, graphics drivers, Box64/Box86).

### Required Components

| Component | Version | Purpose |
|-----------|---------|---------|
| Android SDK Platform | API 34 | Android 14 APIs |
| Build Tools | 30.0.3+ | APK compilation |
| NDK | 24.0.8215888 | Native C/C++ code |
| CMake | 3.28.3 | Native build system |

### Option 1: Copy SDK from Working Project (Fastest)

If you have a working Winlator project, just copy the SDK:

```bash
# From working repo to new repo
cp -r /path/to/working/winlator/android-sdk /path/to/new/winlator/

# Or create symlink (saves space)
ln -s /path/to/working/winlator/android-sdk /path/to/new/winlator/android-sdk
```

### Option 2: Setup Script

Add this script to your new repo as `setup-sdk.sh`:

```bash
#!/bin/bash
# setup-sdk.sh - Downloads required Android SDK components

set -e

SDK_DIR="./android-sdk"
CMDLINE_TOOLS="$SDK_DIR/cmdline-tools/latest/bin"

# Create SDK directory
mkdir -p "$SDK_DIR"

# Download command line tools
if [ ! -f "cmdline-tools.zip" ]; then
    echo "Downloading Android SDK command line tools..."
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
fi

# Extract
if [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
    echo "Extracting..."
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip -q cmdline-tools.zip -d "$SDK_DIR/cmdline-tools/tmp"
    mv "$SDK_DIR/cmdline-tools/tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$SDK_DIR/cmdline-tools/tmp"
fi

# Accept licenses
yes | "$CMDLINE_TOOLS/sdkmanager" --licenses > /dev/null 2>&1 || true

# Install required packages
echo "Installing SDK components..."
"$CMDLINE_TOOLS/sdkmanager" \
    "platforms;android-34" \
    "build-tools;30.0.3" \
    "ndk;24.0.8215888" \
    "cmake;3.28.3" \
    "platform-tools"

echo "SDK setup complete!"
echo "Add to local.properties:"
echo "sdk.dir=$(pwd)/android-sdk"
```

Make it executable and run:
```bash
chmod +x setup-sdk.sh
./setup-sdk.sh
```

### Option 3: Manual SDK Setup

```bash
# 1. Download command line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p android-sdk/cmdline-tools
mv cmdline-tools android-sdk/cmdline-tools/latest

# 2. Set environment
export ANDROID_SDK_ROOT=$(pwd)/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin

# 3. Accept licenses
yes | sdkmanager --licenses

# 4. Install components
sdkmanager "platforms;android-34"
sdkmanager "build-tools;30.0.3"
sdkmanager "ndk;24.0.8215888"
sdkmanager "cmake;3.28.3"
sdkmanager "platform-tools"
```

### Option 4: Use Android Studio

1. Open the project in Android Studio
2. It will prompt to download missing SDK components
3. Click "Download" on each prompt
4. Or go to: Tools → SDK Manager → Install:
   - Android 14.0 (API 34)
   - SDK Build-Tools 30.0.3
   - NDK (Side by side) 24.0.8215888
   - CMake 3.28.3

### local.properties Configuration

After SDK setup, create `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
SUPABASE_URL=https://jkhpdbfdhskznxbyxxhc.supabase.co
SUPABASE_ANON_KEY=sb_publishable_FQDQMSSWyn6ES5PZuFLOww_Yaeb6sHe
```

### Verify Setup

```bash
# Check SDK components
ls android-sdk/platforms/
ls android-sdk/ndk/
ls android-sdk/cmake/

# Check NDK version
cat android-sdk/ndk/24.0.8215888/source.properties | grep Pkg.Revision

# Try building
./gradlew :app:assembleDebug
```

---

## ADB Testing Commands

Common ADB commands for testing the RetroNexus Winlator app:

### Device Connection

```bash
# List connected devices
adb devices

# Start ADB server (if not running)
adb start-server

# Kill ADB server
adb kill-server

# Connect to device over WiFi (after USB setup)
adb tcpip 5555
adb connect 192.168.1.100:5555
```

### App Installation & Management

```bash
# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Install APK (force reinstall/upgrade)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Uninstall app
adb uninstall com.winlator

# Clear app data
adb shell pm clear com.winlator
```

### Logcat Debugging

```bash
# View all logs
adb logcat

# Filter for Winlator logs only
adb logcat -s Winlator

# Filter for specific tags
adb logcat -s Guest
adb logcat -s DownloadEngine
adb logcat -s XServerDisplay

# Clear log buffer
adb logcat -c

# Save logs to file
adb logcat -d > winlator_logs.txt

# View logs with timestamp
adb logcat -v time

# Filter for errors only
adb logcat *:E
```

### Shell Commands

```bash
# Open shell on device
adb shell

# Check app data directory
adb shell ls -la /data/data/com.winlator/

# Check external storage
adb shell ls -la /sdcard/Android/data/com.winlator/

# Copy files from device
adb pull /sdcard/Android/data/com.winlator/files/ ./backup/

# Copy files to device
adb push ./game.zip /sdcard/Download/

# Check running processes
adb shell ps | grep winlator

# Check memory usage
adb shell dumpsys meminfo com.winlator
```

### Testing Specific Features

```bash
# Simulate button press (HOME)
adb shell input keyevent KEYCODE_HOME

# Simulate back button
adb shell input keyevent KEYCODE_BACK

# Simulate touch at coordinates
adb shell input tap 500 500

# Take screenshot
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png

# Record screen (30 seconds)
adb shell screenrecord /sdcard/video.mp4
adb pull /sdcard/video.mp4

# Check network connectivity
adb shell ping -c 3 google.com

# Check GPU info
adb shell dumpsys gpu
```

### Performance & Debugging

```bash
# Check CPU usage
adb shell top -p $(adb shell pidof com.winlator)

# Monitor battery
adb shell dumpsys battery

# Check thermal status
adb shell dumpsys thermalservice

# View app permissions
adb shell dumpsys package com.winlator | grep permission

# Force stop app
adb shell am force-stop com.winlator

# Start app
adb shell am start -n com.winlator/.MainActivity
```

### Build & Deploy Script

```bash
#!/bin/bash
# build_and_deploy.sh

# Build debug APK
./gradlew :app:assembleDebug

# Install to device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.winlator/.MainActivity

# Stream logs
adb logcat -c
adb logcat -s Winlator:I DownloadEngine:I Guest:I
```

### Useful Logcat Filters

```bash
# All RetroNexus related logs
adb logcat | grep -E "(Winlator|DownloadEngine|SupabaseClient|GamesStore)"

# Error logs only
adb logcat *:E | grep com.winlator

# Filter by PID
adb shell pidof com.winlator
adb logcat --pid=$(adb shell pidof com.winlator)

# View crash logs
adb logcat -b crash

# View system logs
adb logcat -b system
```

---

## File Installation Summary

| File | Destination |
|------|-------------|
| Game.java | `app/src/main/java/com/winlator/core/` |
| SupabaseClient.java | `app/src/main/java/com/winlator/core/` |
| ImageLoader.java | `app/src/main/java/com/winlator/core/` |
| DownloadEngine.java | `app/src/main/java/com/winlator/core/` |
| GamesStoreFragment.java | `app/src/main/java/com/winlator/` |
| GameDetailFragment.java | `app/src/main/java/com/winlator/` |
| colors.xml additions | `app/src/main/res/values/` |
| strings.xml additions | `app/src/main/res/values/` |
| styles.xml additions | `app/src/main/res/values/` |
| toolbar_retronexus_bg.xml | `app/src/main/res/drawable/` |
| icon_market.xml | `app/src/main/res/drawable/` |
| icon_games.xml | `app/src/main/res/drawable/` |
| icon_shortcut.xml | `app/src/main/res/drawable/` |
| icon_input_controls.xml | `app/src/main/res/drawable/` |
| icon_settings.xml | `app/src/main/res/drawable/` |
| icon_about.xml | `app/src/main/res/drawable/` |
| game_detail_fragment.xml | `app/src/main/res/layout/` |
| game_grid_item.xml | `app/src/main/res/layout/` |
| games_store_fragment.xml | `app/src/main/res/layout/` |
| main_menu.xml | `app/src/main/res/menu/` |
| config.py | `s3/` |
| upload_file.py | `s3/` |
| supabase_schema.sql | Root of project |
