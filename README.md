## RetroNexus Winlator (RetroNexus Marketplace build)

This repo contains an Android app based on Winlator, customized for **RetroNexus**:
- The app lists games from **Supabase**.
- On Install it **downloads a portable ZIP** from R2/S3 and extracts it to device storage.
- It then creates/configures a **Winlator container** and launches the game **directly** (no desktop).

This README documents:
- **repo structure**
- **how the app works (end-to-end)**
- **how to debug crashes**
- **how to upload a new game**
- **what we fixed** while debugging the “tap any game → crash” issue

---

## Repo structure (what’s what)

- **`app/`**: Android Studio / Gradle project
  - **`app/app/`**: Android application module
    - **`src/main/AndroidManifest.xml`**: package/activity declarations (package is `com.winlator`)
    - **`src/main/java/`**: Java sources (RetroNexus logic lives here)
    - **`src/main/assets/`**: container pattern, common DLL lists, etc.
    - **`src/main/res/`**: UI resources, strings
- **`s3/`**: upload tooling (Cloudflare R2 / S3-compatible)
  - **`s3/upload_file.py`**: one-off uploader using `.env`
  - **`s3/config.py`**: loads endpoint/bucket/keys from `.env`
- **`gamesetup.md`**: SOP for preparing “portable” games + Supabase insert template
- **`supabase_schema.sql`**: Supabase tables (`games`, `access_codes`) + policies + activation RPC
- **`COMMANDS.md`**: quick adb commands (logs, install)
- **`retronexus-v1.0.5.apk`**: a built APK snapshot (large binary)

Other folders like `glibc_patches/`, `cmod-glibc-branch/`, `wine_addons/`, etc. are build/runtime components that support Winlator.

---

## How the app works (runtime flow)

### 1) Data source (Supabase)
The app reads from the `games` table (see `supabase_schema.sql`) which contains:
- `title`, `description`, `thumbnail_url`
- `download_url`: direct link to the **game ZIP** in R2/S3
- `config_preset` (JSON): tells the app which `.exe` to run and how to configure the container

### 2) Game screen → “Install” / “Play”
On the game detail screen (`GameDetailFragment`), the button state is decided by:
- “Ready to play” = **installed game folder contains expected exe** AND a **matching container exists**

Code path:

```90:122:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/GameDetailFragment.java
    private void refreshInstallOrPlayButton() {
        if (getContext() == null || game == null) return;
        DownloadEngine.InstallStatus active = DownloadEngine.getActiveInstallStatus(game);
        if (active != null) {
            applyInstallStatus(active);
            return;
        }
        if (DownloadEngine.isReadyToPlay(getContext(), game)) {
            installButton.setText(R.string.play);
            installButton.setBackgroundResource(R.drawable.btn_rounded_success);
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> launchGame());
            deleteButton.setVisibility(View.VISIBLE);
            deleteButton.setEnabled(true);
            deleteButton.setOnClickListener(v -> confirmDelete());
            installProgress.setVisibility(View.GONE);
        } else {
            installButton.setText(R.string.install);
            installButton.setBackgroundResource(R.drawable.btn_rounded_accent);
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> startInstall("auto"));
            deleteButton.setVisibility(View.GONE);
            installProgress.setVisibility(View.GONE);
        }
    }
```

### 3) Install pipeline (download → unzip → container)
All install logic is centralized in `DownloadEngine`:
- downloads ZIP from `download_url`
- extracts to **`/storage/emulated/0/RetroNexus/Games/<SafeTitle>`**
- optionally applies `config_preset.patchZipUrl` (small patch ZIP) into the game root
- creates/configures a container and persists mapping extras

Relevant section:

```202:224:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/core/DownloadEngine.java
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
```

### 4) Launch pipeline (container → XServerDisplayActivity)
When you press Play, the app:
- finds the container by name (`container.getName().equals(game.title)`)
- resolves `exec_path` under the installed game folder (uses `config_preset.exe` if present, otherwise tries any `.exe`)
- starts `XServerDisplayActivity` with extras `container_id`, `exec_path`, `exec_args`, fullscreen flags

```225:283:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/core/DownloadEngine.java
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
```

---

## What we debugged (Portal install caused “tap any game → crash”)

### Symptom
After installing Portal, the app started **crashing when opening/tapping any game**.

### Root cause (captured via `adb logcat`)
The app crashed in `ContainerManager.loadContainers()` while reading container config JSON:
- `FileUtils.readString(File)` built `new String(byte[], UTF-8)` from a **null** byte array
- One container directory existed with a **missing/empty `.container` config**, which triggered the null read and crashed the whole UI.

This happened during `DownloadEngine.isReadyToPlay()` (called just to decide whether to show “Install” or “Play”).

### Fixes applied in this repo
1) **Make file reads null-safe** so a failed read returns `""` instead of crashing:

```54:62:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/core/FileUtils.java
    public static String readString(Context context, String assetFile) {
        byte[] data = read(context, assetFile);
        return data != null ? new String(data, StandardCharsets.UTF_8) : "";
    }

    public static String readString(File file) {
        byte[] data = read(file);
        return data != null ? new String(data, StandardCharsets.UTF_8) : "";
    }
```

2) **Harden container loading**: skip invalid containers instead of crashing the app:

```46:86:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/container/ContainerManager.java
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        File[] files = homeDir.listFiles();
        if (files == null) return;

        String prefix = RootFS.USER + "-";
        for (File file : files) {
            if (!file.isDirectory()) continue;
            String name = file.getName();
            if (!name.startsWith(prefix)) continue;

            int id;
            try {
                id = Integer.parseInt(name.substring(prefix.length()));
            } catch (NumberFormatException e) {
                continue;
            }

            try {
                Container container = new Container(id);
                container.setRootDir(file);

                String raw = FileUtils.readString(container.getConfigFile());
                if (raw == null || raw.trim().isEmpty()) {
                    Log.w(TAG, "Skipping container " + id + ": empty config at " + container.getConfigFile().getPath());
                    continue;
                }

                JSONObject data = new JSONObject(raw);
                container.loadData(data);
                containers.add(container);
                maxContainerId = Math.max(maxContainerId, container.id);
            } catch (JSONException e) {
                Log.w(TAG, "Skipping container due to invalid JSON: " + file.getPath(), e);
            } catch (Exception e) {
                Log.w(TAG, "Skipping container due to unexpected error: " + file.getPath(), e);
            }
        }
    }
```

3) **Fix container ID collisions**: if a broken `xuser-5/` folder exists, new container creation must skip to a free id (e.g. `xuser-6`), otherwise installs fail with “Failed to create container …”.

```119:189:/home/nik/Desktop/github/retronexus/winlator/app/app/src/main/java/com/winlator/container/ContainerManager.java
    private Container createContainer(JSONObject data) {
        try {
            int id = maxContainerId + 1;
            File containerDir = new File(homeDir, RootFS.USER+"-"+id);
            while (containerDir.exists()) {
                id++;
                containerDir = new File(homeDir, RootFS.USER+"-"+id);
            }

            data.put("id", id);
            if (!containerDir.mkdirs()) return null;
            // ...
            maxContainerId = Math.max(maxContainerId, id);
            containers.add(container);
            return container;
        }
        catch (JSONException e) {}
        return null;
    }
```

Result: the app no longer crashes when opening games, and installs can proceed even if an old broken container folder exists.

---

## How to debug (the “main files” and the logs you want)

### Most important code files for RetroNexus behavior
- **`app/app/src/main/java/com/winlator/core/DownloadEngine.java`**
  - install pipeline, preset application, exe resolution, launch extras
  - logs under tag **`DownloadEngine`**
- **`app/app/src/main/java/com/winlator/GameDetailFragment.java`**
  - install/play button logic, calls `DownloadEngine.isReadyToPlay()` and `launchGame()`
- **`app/app/src/main/java/com/winlator/container/ContainerManager.java`**
  - container discovery/creation/removal; crashes here can break the whole UI
- **`app/app/src/main/java/com/winlator/core/FileUtils.java`**
  - if you see weird NPEs around file IO, start here
- **`app/app/src/main/java/com/winlator/XServerDisplayActivity.java`**
  - if the app doesn’t crash but the game doesn’t open / black screens / XServer issues

### Logcat commands
Minimal (already in `COMMANDS.md`):

```bash
adb logcat -s DownloadEngine:D
```

Crash-focused (recommended):

```bash
adb logcat -c
adb logcat -v threadtime -b all DownloadEngine:D ContainerManager:W AndroidRuntime:E DEBUG:F libc:F tombstoned:F *:S
```

### When a single game breaks everything
If one bad container config or bad file read can crash UI, you’ll see it immediately in:
- `AndroidRuntime: FATAL EXCEPTION`
- stack trace pointing to `ContainerManager` / `FileUtils` / `DownloadEngine.isReadyToPlay`

---

## How to fix common “game doesn’t launch” situations

- **Container not found**
  - Means there is no container named exactly `game.title` yet.
  - Fix: reinstall the game (Install button) so the app creates the container.

- **Game files missing**
  - Game folder exists but `config_preset.exe` isn’t found.
  - Fix: ensure the uploaded ZIP contains the correct exe and `config_preset.exe` matches the real filename.

- **Install fails: “Failed to create container for …”**
  - Usually caused by broken/corrupted container folders or id collisions.
  - This repo now avoids id collisions, and skips invalid containers.
  - If you want to clean broken data on a debug build:

```bash
adb shell run-as com.winlator ls files/rootfs/home
```

---

## How to upload a game (portable ZIP → R2/S3 → Supabase)

### 1) Prepare a portable build (PC)
Follow `gamesetup.md`. Key rule: **no installers on device** — upload a ready-to-run portable folder.

### 2) Create the ZIP correctly
ZIP the **contents** of the game folder (not the folder itself), so extraction produces a runnable directory layout.

### 3) Upload to R2/S3
Configure `.env` with:
- `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET`
- `R2_ACCOUNT_ID` (or `S3_API_ENDPOINT`)

Then use `s3/upload_file.py`:
- set `LOCAL_ZIP` and `S3_OBJECT_KEY`
- run:

```bash
python3 s3/upload_file.py
```

If you have stuck multipart uploads:

```bash
python3 s3/upload_file.py --abort
```

### 4) Add the game to Supabase
Insert into `public.games` (template is in `gamesetup.md`), especially `config_preset`, e.g.:
- `exe`: main executable name (`Portal.exe`, etc.)
- `arguments`: optional launch args
- `graphics`: driver/wrapper options
- optional `patchZipUrl`: small fix zip applied after extraction

---

## Build & install (developer)

```bash
cd app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package name is **`com.winlator`** (see `app/app/src/main/AndroidManifest.xml` and `app/app/build.gradle`).

