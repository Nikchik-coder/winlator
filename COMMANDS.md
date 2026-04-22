adb logcat -s DownloadEngine:D

adb uninstall com.winlator.cmod && adb install app/build/outputs/apk/debug/app-debug.apk


export PATH="$HOME/.local/bin:$PATH"
uv run s3/upload_file.py