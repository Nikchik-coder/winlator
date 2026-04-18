package com.retronexus.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.retronexus.BuildConfig;

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
