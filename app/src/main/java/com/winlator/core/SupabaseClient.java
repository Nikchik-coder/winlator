package com.winlator.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.winlator.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class SupabaseClient {
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

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

    public static void activateCode(String code, String deviceId, Callback<Boolean> callback) {
        new Thread(() -> {
            String supabaseUrl = BuildConfig.SUPABASE_URL;
            String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;
            if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
                callback.onError(new IllegalStateException("Supabase not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in app/local.properties."));
                return;
            }

            String payload = gson.toJson(new ActivateCodeRequest(code, deviceId));
            RequestBody body = RequestBody.create(payload, JSON);
            Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/rpc/activate_code")
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", supabaseAnonKey)
                .addHeader("Authorization", "Bearer " + supabaseAnonKey)
                .build();

            try (Response response = client.newCall(request).execute()) {
                String responseData = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + responseData);
                }

                Boolean result = null;
                try {
                    result = gson.fromJson(responseData, Boolean.class);
                }
                catch (Exception ignored) {
                    String trimmed = responseData != null ? responseData.trim() : "";
                    if ("true".equals(trimmed)) result = true;
                    else if ("false".equals(trimmed)) result = false;
                }

                callback.onSuccess(Boolean.TRUE.equals(result));
            } catch (Exception e) {
                callback.onError(e);
            }
        }).start();
    }

    private static class ActivateCodeRequest {
        final String p_code;
        final String p_device_id;

        ActivateCodeRequest(String code, String deviceId) {
            this.p_code = code;
            this.p_device_id = deviceId;
        }
    }
}
