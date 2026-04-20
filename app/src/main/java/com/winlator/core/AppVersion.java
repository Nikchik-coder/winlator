package com.winlator.core;

import androidx.annotation.Keep;

@Keep
public class AppVersion {
    public int id;
    public int version_code;
    public String version_name;
    public String download_url;
    public String release_notes;
    public boolean is_mandatory;
}