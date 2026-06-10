package com.termux.app;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.shell.TermuxShellManager;

/**
 * Handles device boot so the claw800 runtime can start nanobot without opening the UI.
 *
 * Prerequisites (handled outside this code):
 * - User enables auto-start for the app in OEM settings.
 * - App has been opened at least once after install/force-stop (clears Android STOPPED state).
 * - Termux bootstrap and claw800 rootfs were provisioned on a prior launch.
 */
public final class ClawBootAutostart {

    private static final String LOG_TAG = "ClawBootAutostart";

    /** Xiaomi / HTC "quick boot" broadcasts — same semantics as {@link Intent#ACTION_BOOT_COMPLETED}. */
    static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    static final String ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON";

    private ClawBootAutostart() {}

    public static boolean isBootCompletedAction(@NonNull String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || ACTION_QUICKBOOT_POWERON.equals(action)
            || ACTION_HTC_QUICKBOOT_POWERON.equals(action);
    }

    public static void handleBootCompleted(@NonNull Context context, @NonNull Intent intent) {
        Logger.logInfo(LOG_TAG, "Device boot completed; starting claw800 runtime autostart chain.");

        TermuxShellManager.onActionBootCompleted(context, intent);

        Error filesError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, false, false);
        if (filesError != null) {
            Logger.logError(LOG_TAG,
                "Skipping nanobot autostart: Termux files directory is not accessible: "
                    + filesError.getMessage());
            return;
        }

        ClawRuntimeControlService.ensureNanobotAutostart(context);
        Logger.logInfo(LOG_TAG, "Dispatched ENSURE_AUTOSTART after boot.");
    }
}
