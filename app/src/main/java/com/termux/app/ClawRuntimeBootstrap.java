package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.res.AssetManager;
import android.os.Build;
import android.view.WindowManager;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Installs the pre-baked Ubuntu rootfs from local APK assets on first launch.
 *
 * This is the Phase 2 sub-step C hook:
 * - Sentinel logic: skip if already installed and valid.
 * - Local-only asset install: no network fetch.
 * - Logging and retry: append logs, show retry dialog on failure.
 */
final class ClawRuntimeBootstrap {

    private static final String LOG_TAG = "ClawRuntimeBootstrap";

    private static final String ROOTFS_ASSET_DIR = "rootfs";
    private static final String ROOTFS_ARM64_ASSET = "ubuntu-noble-arm64-v8a.tar.xz";
    private static final String ROOTFS_X64_ASSET = "ubuntu-noble-x86_64.tar.xz";
    private static final String ROOTFS_ALIAS = "claw800";

    private static final String ROOTFS_INSTALL_DIR_PATH =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.proot-distro/installed-rootfs/" + ROOTFS_ALIAS;
    private static final String ROOTFS_SENTINEL_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/claw800/rootfs-installed.stamp";
    private static final String ROOTFS_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-rootfs-bootstrap.log";
    private static final String ROOTFS_ASSET_STAGING_PATH =
        TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/claw800-rootfs.tar.xz";

    private static volatile boolean sInstallRunning = false;

    static void setupRootfsIfNeeded(final Activity activity, final Runnable whenDone) {
        if (sInstallRunning) {
            Logger.logInfo(LOG_TAG, "Rootfs install already running; waiting for current attempt.");
            return;
        }

        if (isRootfsReady()) {
            whenDone.run();
            return;
        }

        final ProgressDialog progress =
            ProgressDialog.show(activity, null, "Preparing Ubuntu rootfs...", true, false);

        sInstallRunning = true;
        new Thread(() -> {
            try {
                logToFile("=== rootfs setup start ===");
                logToFile("selectedAsset=" + resolveAssetNameForDevice());

                Error error;

                // Clean potentially broken previous install before retrying.
                error = FileUtils.deleteFile("claw800 rootfs install directory", ROOTFS_INSTALL_DIR_PATH, true);
                if (error != null) {
                    throw new RuntimeException("Failed to clear old rootfs directory: " + error.getMessage());
                }

                error = FileUtils.createDirectoryFile(ROOTFS_INSTALL_DIR_PATH);
                if (error != null) {
                    throw new RuntimeException("Failed to create rootfs install directory: " + error.getMessage());
                }

                copyAssetToStaging(activity, resolveAssetNameForDevice());
                extractStagedRootfs();
                writeSentinel();

                if (!isRootfsReady()) {
                    throw new RuntimeException("Rootfs install completed but verification failed.");
                }

                logToFile("rootfs setup completed successfully");
                activity.runOnUiThread(whenDone);
            } catch (Exception e) {
                logToFile("rootfs setup failed: " + e.getMessage());
                Logger.logStackTraceWithMessage(LOG_TAG, "Rootfs setup failed", e);
                showRootfsErrorDialog(activity, whenDone, e.getMessage());
            } finally {
                sInstallRunning = false;
                activity.runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (RuntimeException ignored) {
                        // Activity already dismissed.
                    }
                });
            }
        }).start();
    }

    private static boolean isRootfsReady() {
        File sentinel = new File(ROOTFS_SENTINEL_PATH);
        File rootfsBin = new File(ROOTFS_INSTALL_DIR_PATH, "bin");
        File rootfsUsr = new File(ROOTFS_INSTALL_DIR_PATH, "usr");
        return sentinel.exists() && rootfsBin.exists() && rootfsUsr.exists();
    }

    private static String resolveAssetNameForDevice() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return ROOTFS_ARM64_ASSET;
            if ("x86_64".equals(abi)) return ROOTFS_X64_ASSET;
        }
        throw new IllegalStateException("Unsupported ABI list for rootfs asset: " + String.join(",", abis));
    }

    private static void copyAssetToStaging(Activity activity, String assetName) throws Exception {
        File staging = new File(ROOTFS_ASSET_STAGING_PATH);
        File parent = staging.getParentFile();
        if (parent == null) throw new IllegalStateException("Staging parent directory is null.");
        Error error = FileUtils.createDirectoryFile(parent.getAbsolutePath());
        if (error != null) throw new RuntimeException("Failed to create staging dir: " + error.getMessage());

        // Remove stale tar from previous failed attempt.
        FileUtils.deleteFile("staged rootfs tar", staging.getAbsolutePath(), true);

        AssetManager assets = activity.getAssets();
        String assetPath = ROOTFS_ASSET_DIR + "/" + assetName;
        logToFile("copying asset to staging: " + assetPath);

        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(staging)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void extractStagedRootfs() throws Exception {
        File tar = new File(ROOTFS_ASSET_STAGING_PATH);
        if (!tar.exists()) throw new IllegalStateException("Staged rootfs tar not found at: " + tar.getAbsolutePath());

        String tarBinary = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar";
        String xzBinary = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/xz";
        File tarFile = new File(tarBinary);
        if (!tarFile.exists()) throw new IllegalStateException("Termux tar binary not found: " + tarBinary);
        ensureExecutable(new File(xzBinary), "xz");

        logToFile("extracting rootfs tar into: " + ROOTFS_INSTALL_DIR_PATH);

        ProcessBuilder pb = new ProcessBuilder(
            tarBinary, "-xJf", tar.getAbsolutePath(), "-C", ROOTFS_INSTALL_DIR_PATH
        );
        Map<String, String> env = pb.environment();
        String originalPath = env.get("PATH");
        if (originalPath == null) originalPath = "";
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + originalPath);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("tar extraction failed with exit=" + exitCode + "\n" + output);
        }

        // Best effort cleanup of staged tar to save disk.
        FileUtils.deleteFile("staged rootfs tar", tar.getAbsolutePath(), true);
    }

    private static void ensureExecutable(File binary, String label) {
        if (!binary.exists()) {
            throw new IllegalStateException("Required binary not found (" + label + "): " + binary.getAbsolutePath());
        }
        if (!binary.canExecute()) {
            // Try to repair permissions once; if this fails, caller gets a clear exception.
            //noinspection ResultOfMethodCallIgnored
            binary.setExecutable(true, true);
        }
        if (!binary.canExecute()) {
            throw new IllegalStateException("Required binary is not executable (" + label + "): " + binary.getAbsolutePath());
        }
    }

    private static void writeSentinel() throws Exception {
        File sentinel = new File(ROOTFS_SENTINEL_PATH);
        File parent = sentinel.getParentFile();
        if (parent == null) throw new IllegalStateException("Sentinel parent directory is null.");

        Error error = FileUtils.createDirectoryFile(parent.getAbsolutePath());
        if (error != null) throw new RuntimeException("Failed to create sentinel parent: " + error.getMessage());

        try (FileOutputStream fos = new FileOutputStream(sentinel, false)) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            fos.write(("installed_at=" + timestamp + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static synchronized void logToFile(String message) {
        try {
            File logFile = new File(ROOTFS_LOG_PATH);
            File parent = logFile.getParentFile();
            if (parent != null) {
                Error error = FileUtils.createDirectoryFile(parent.getAbsolutePath());
                if (error != null) {
                    Logger.logError(LOG_TAG, "Failed to create rootfs log directory: " + error.getMessage());
                    return;
                }
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = timestamp + " " + message + "\n";
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed writing rootfs log file", e);
        }
    }

    private static void showRootfsErrorDialog(Activity activity, Runnable whenDone, String details) {
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle("Ubuntu Rootfs Setup Error")
                    .setMessage("Local rootfs install failed.\n\nDetails:\n" + details)
                    .setNegativeButton("Abort", (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setNeutralButton("Skip For Now", (dialog, which) -> {
                        dialog.dismiss();
                        whenDone.run();
                    })
                    .setPositiveButton("Try Again", (dialog, which) -> {
                        dialog.dismiss();
                        setupRootfsIfNeeded(activity, whenDone);
                    })
                    .show();
            } catch (WindowManager.BadTokenException ignored) {
                // Activity dismissed.
            }
        });
    }

    private ClawRuntimeBootstrap() {}
}
