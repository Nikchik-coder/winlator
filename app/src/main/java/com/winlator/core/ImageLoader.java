package com.winlator.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.winlator.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ImageLoader {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // LruCache is sized in KB. Use a sane fraction of available memory, with a floor to avoid tiny caches.
    private static final int MEMORY_CACHE_KB = Math.max(8 * 1024, (int)(Runtime.getRuntime().maxMemory() / 1024L / 12L));
    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    public static void loadInto(ImageView imageView, String url) {
        if (url == null || url.isEmpty()) return;

        imageView.setTag(R.id.image_loader_url_tag, url);

        Bitmap mem = memoryCache.get(url);
        if (mem != null) {
            imageView.setImageBitmap(mem);
            return;
        }

        // Disk cache lookup (independent of HTTP cache headers).
        File diskFile = getDiskCacheFile(imageView, url);
        if (diskFile != null && diskFile.isFile()) {
            Bitmap diskBitmap = decodeScaledBitmap(diskFile.getPath(), imageView.getWidth(), imageView.getHeight());
            if (diskBitmap != null) {
                memoryCache.put(url, diskBitmap);
                imageView.setImageBitmap(diskBitmap);
                return;
            }
        }

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;
                byte[] bytes = response.body().bytes();
                if (diskFile != null) {
                    try (FileOutputStream fos = new FileOutputStream(diskFile)) {
                        fos.write(bytes);
                    } catch (Exception e) {
                        // Disk cache is best-effort; ignore failures.
                    }
                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                memoryCache.put(url, bitmap);

                mainHandler.post(() -> {
                    Object tag = imageView.getTag(R.id.image_loader_url_tag);
                    if (tag instanceof String && url.equals(tag)) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private static File getDiskCacheFile(ImageView imageView, String url) {
        try {
            File cacheDir = new File(imageView.getContext().getCacheDir(), "image_cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            String name = "img_" + sha1Hex(url) + ".bin";
            return new File(cacheDir, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static Bitmap decodeScaledBitmap(String path, int targetW, int targetH) {
        try {
            if (targetW <= 0) targetW = 512;
            if (targetH <= 0) targetH = 512;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int sample = 1;
            while ((o.outWidth / sample) > targetW * 2 || (o.outHeight / sample) > targetH * 2) {
                sample *= 2;
            }

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = Math.max(1, sample);
            o2.inPreferredConfig = Bitmap.Config.RGB_565; // thumbnails
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) {
            return null;
        }
    }
}

