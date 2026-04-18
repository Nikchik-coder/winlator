package com.winlator;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.winlator.core.Game;
import com.winlator.core.DownloadEngine;
import com.winlator.core.ImageLoader;

public class GameDetailFragment extends Fragment {
    private Game game;
    private Button installButton;
    private LinearProgressIndicator installProgress;

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
    }

    private void refreshInstallOrPlayButton() {
        if (getContext() == null || game == null) return;
        if (DownloadEngine.isReadyToPlay(getContext(), game)) {
            installButton.setText("PLAY");
            installButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.retronexus_success)));
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> launchGame());
            installProgress.setVisibility(View.GONE);
        } else {
            installButton.setText("INSTALL");
            installButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.retronexus_accent)));
            installButton.setEnabled(true);
            installButton.setOnClickListener(v -> startInstall("auto"));
        }
    }

    private void startInstall(String mode) {
        installButton.setText("INSTALL");
        installButton.setEnabled(false);
        installProgress.setVisibility(View.VISIBLE);
        installProgress.setIndeterminate(false);
        installProgress.setProgress(0);
        installProgress.show();

        DownloadEngine.downloadAndInstall(getContext(), game, mode, new DownloadEngine.Callback() {
            @Override
            public void onProgress(int percent, String status) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    installButton.setText(status);
                    if (percent < 0) {
                        installProgress.setIndeterminate(true);
                    } else {
                        installProgress.setIndeterminate(false);
                        installProgress.setProgress(percent);
                    }
                });
            }

            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
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
        // Bypass desktop and launch directly
        DownloadEngine.launchGame(getContext(), game);
    }
}
