package com.winlator;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.Game;
import com.winlator.core.DownloadEngine;
import com.winlator.core.ImageLoader;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GameDetailFragment extends Fragment {
    private Game game;
    private Button installButton;
    private Button deleteButton;
    private LinearProgressIndicator installProgress;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable installPoller = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            DownloadEngine.InstallStatus s = DownloadEngine.getActiveInstallStatus(game);
            if (s != null) {
                applyInstallStatus(s);
                uiHandler.postDelayed(this, 500);
            } else {
                refreshInstallOrPlayButton();
            }
        }
    };

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
        deleteButton = view.findViewById(R.id.DeleteButton);
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
        uiHandler.removeCallbacks(installPoller);
        if (DownloadEngine.getActiveInstallStatus(game) != null) {
            uiHandler.post(installPoller);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(installPoller);
    }

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

    private void applyInstallStatus(DownloadEngine.InstallStatus s) {
        installButton.setEnabled(false);
        deleteButton.setVisibility(View.GONE);
        String text = (s.status != null && !s.status.isEmpty()) ? s.status : "Installing…";
        installButton.setText(text);
        installProgress.setVisibility(View.VISIBLE);
        if (s.percent < 0) {
            if (!installProgress.isIndeterminate()) {
                // Material progress indicator can't change indeterminate mode while visible.
                installProgress.hide();
                installProgress.setVisibility(View.GONE);
                installProgress.setIndeterminate(true);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.show();
            }
        } else {
            if (installProgress.isIndeterminate()) {
                // Material progress indicator can't change indeterminate mode while visible.
                installProgress.hide();
                installProgress.setVisibility(View.GONE);
                installProgress.setIndeterminate(false);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.show();
            }
            installProgress.setProgress(s.percent);
        }
        installProgress.show();
    }

    private void confirmDelete() {
        if (getContext() == null || game == null) return;
        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setCancelable(false);
        dialog.setMessage("Delete \"" + game.title + "\" from this phone?");
        dialog.setOnConfirmCallback(this::deleteInstalledGame);
        dialog.show();
    }

    private void deleteInstalledGame() {
        if (getContext() == null || game == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();

        installButton.setEnabled(false);
        deleteButton.setEnabled(false);
        installButton.setText("DELETING...");
        // Material progress indicator can't change indeterminate mode while visible.
        installProgress.hide();
        installProgress.setVisibility(View.GONE);
        installProgress.setIndeterminate(true);
        installProgress.setVisibility(View.VISIBLE);
        installProgress.show();

        new Thread(() -> {
            Exception error = null;
            try {
                File exeFile = DownloadEngine.findInstalledGameExe(game);

                ContainerManager manager = new ContainerManager(appContext);
                Container target = null;
                for (Container c : manager.getContainers()) {
                    String gameId = game.id != null ? game.id : "";
                    String cGameId = c.getExtra("retronexusGameId");
                    String cExec = c.getExtra("retronexusExecPath");
                    if (!gameId.isEmpty() && gameId.equals(cGameId)) {
                        target = c;
                        break;
                    }
                    if (exeFile != null && exeFile.isFile() && exeFile.getPath().equals(cExec)) {
                        target = c;
                        break;
                    }
                    if (game.title != null && game.title.equals(c.getName())) {
                        target = c;
                        break;
                    }
                }

                // Delete game files (RetroNexus path + legacy Winlator Downloads/Games path).
                File gameDir = DownloadEngine.getGameInstallDir(game);
                String installDirOverride = target != null ? target.getExtra("retronexusGameInstallDir") : "";
                if (installDirOverride != null && !installDirOverride.isEmpty()) {
                    gameDir = new File(installDirOverride);
                }
                if (gameDir.exists()) FileUtils.delete(gameDir);

                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir != null) {
                    String safe = gameDir.getName();
                    File legacy = new File(downloadsDir, "Games/" + safe);
                    if (legacy.exists()) FileUtils.delete(legacy);
                }

                if (target != null) {
                    CountDownLatch latch = new CountDownLatch(1);
                    manager.removeContainerAsync(target, latch::countDown);
                    latch.await(120, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                error = e;
            }

            if (getActivity() != null) {
                Exception finalError = error;
                getActivity().runOnUiThread(() -> {
                    installProgress.hide();
                    installProgress.setVisibility(View.GONE);
                    if (finalError != null) {
                        Toast.makeText(getContext(), "Delete failed: " + finalError.getMessage(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), "Deleted.", Toast.LENGTH_SHORT).show();
                    }
                    refreshInstallOrPlayButton();
                });
            }
        }).start();
    }

    private void startInstall(String mode) {
        installButton.setText(R.string.install);
        installButton.setEnabled(false);
        deleteButton.setVisibility(View.GONE);
        installProgress.setVisibility(View.VISIBLE);
        installProgress.setIndeterminate(false);
        installProgress.setProgress(0);
        installProgress.show();
        uiHandler.removeCallbacks(installPoller);
        uiHandler.post(installPoller);
        if (getActivity() != null) {
            // Avoid the device sleeping/freezing the app during multi-GB downloads.
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        DownloadEngine.downloadAndInstall(getContext(), game, mode, new DownloadEngine.Callback() {
            @Override
            public void onProgress(int percent, String status) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    installButton.setText(status);
                    if (percent < 0) {
                        if (!installProgress.isIndeterminate()) {
                            installProgress.hide();
                            installProgress.setVisibility(View.GONE);
                            installProgress.setIndeterminate(true);
                            installProgress.setVisibility(View.VISIBLE);
                            installProgress.show();
                        }
                    } else {
                        if (installProgress.isIndeterminate()) {
                            installProgress.hide();
                            installProgress.setVisibility(View.GONE);
                            installProgress.setIndeterminate(false);
                            installProgress.setVisibility(View.VISIBLE);
                            installProgress.show();
                        }
                        installProgress.setProgress(percent);
                    }
                });
            }

            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        installProgress.hide();
                        installProgress.setVisibility(View.GONE);
                        installButton.setText(R.string.play);
                        installButton.setBackgroundResource(R.drawable.btn_rounded_success);
                        installButton.setEnabled(true);
                        installButton.setOnClickListener(v -> launchGame());
                    });
                }
            }

            @Override
            public void onError(Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        installProgress.hide();
                        installProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        installButton.setText(R.string.install);
                        installButton.setBackgroundResource(R.drawable.btn_rounded_accent);
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
