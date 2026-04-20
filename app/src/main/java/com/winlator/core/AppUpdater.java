package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.winlator.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AppUpdater {

    public static void checkUpdate(Activity activity) {
        SupabaseClient.getLatestVersion(new SupabaseClient.Callback<AppVersion>() {
            @Override
            public void onSuccess(AppVersion latest) {
                if (latest != null && latest.version_code > BuildConfig.VERSION_CODE) {
                    new Handler(Looper.getMainLooper()).post(() -> showUpdateDialog(activity, latest));
                }
            }

            @Override
            public void onError(Exception e) {
                // Silently ignore update checks that fail on startup
            }
        });
    }

    private static void showUpdateDialog(Activity activity, AppVersion version) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update Available")
            .setMessage("Version " + version.version_name + " is available.\n\n" + (version.release_notes != null ? version.release_notes : ""))
            .setCancelable(!version.is_mandatory)
            .setPositiveButton("Download", (dialog, which) -> downloadAndInstall(activity, version));

        if (!version.is_mandatory) {
            builder.setNegativeButton("Later", (dialog, which) -> dialog.dismiss());
        }

        builder.show();
    }

    private static void downloadAndInstall(Activity activity, AppVersion version) {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        progress.setTitle("Downloading Update...");
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setCancelable(false);
        progress.show();

        File apkFile = new File(activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "update.apk");

        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(version.download_url).build();
                Call call = client.newCall(request);
                Response response = call.execute();

                if (!response.isSuccessful() || response.body() == null) {
                    throw new Exception("Failed to download APK");
                }

                long totalBytes = response.body().contentLength();
                InputStream in = response.body().byteStream();
                FileOutputStream out = new FileOutputStream(apkFile);

                byte[] buffer = new byte[4096];
                int len;
                long downloaded = 0;

                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    downloaded += len;
                    final int percent = (int) ((downloaded * 100) / totalBytes);
                    new Handler(Looper.getMainLooper()).post(() -> progress.setProgress(percent));
                }
                out.close();
                in.close();

                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    installApk(activity, apkFile);
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Update Failed")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null)
                        .show();
                });
            }
        }).start();
    }

    private static void installApk(Context context, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}