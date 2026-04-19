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
