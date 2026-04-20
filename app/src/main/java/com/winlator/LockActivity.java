package com.winlator;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.AppLock;
import com.winlator.core.AppUtils;
import com.winlator.core.SupabaseClient;

public class LockActivity extends AppCompatActivity {
    private EditText etCode;
    private Button btUnlock;
    private ProgressBar pbLoading;
    private TextView tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.lock_activity);

        if (AppLock.isUnlocked(this)) {
            openMain();
            return;
        }

        etCode = findViewById(R.id.ETCode);
        btUnlock = findViewById(R.id.BTUnlock);
        pbLoading = findViewById(R.id.PBLoading);
        tvError = findViewById(R.id.TVError);

        btUnlock.setOnClickListener(v -> submit());
    }

    private void submit() {
        tvError.setVisibility(View.GONE);
        String code = etCode.getText().toString().trim().toUpperCase();
        if (code.length() != 8) {
            showError(getString(R.string.lock_invalid_code_length));
            return;
        }

        setUiBusy(true);
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        SupabaseClient.activateCode(code, deviceId, new SupabaseClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(() -> {
                    if (Boolean.TRUE.equals(result)) {
                        AppLock.saveUnlocked(LockActivity.this, code);
                        Toast.makeText(LockActivity.this, R.string.lock_unlocked_toast, Toast.LENGTH_SHORT).show();
                        openMain();
                    } else {
                        setUiBusy(false);
                        showError(getString(R.string.lock_invalid_or_bound));
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    setUiBusy(false);
                    showError(e.getMessage() != null ? e.getMessage() : getString(R.string.a_network_error_occurred));
                });
            }
        });
    }

    private void setUiBusy(boolean busy) {
        btUnlock.setEnabled(!busy);
        etCode.setEnabled(!busy);
        pbLoading.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}

