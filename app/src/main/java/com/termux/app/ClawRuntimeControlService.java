package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Control-plane service for claw800 runtime operations exposed to the RN UI.
 *
 * <p>This service intentionally does NOT provide a generic shell API. It only
 * supports the narrow management actions required by the PoC:
 * start/stop/restart/status/log tail for `nanobot gateway`, plus read/write
 * operations for guest `~/.nanobot/config.json` with atomic replacement.
 */
public class ClawRuntimeControlService extends Service {

    private static final String LOG_TAG = "ClawRuntimeControlService";

    public static final String ACTION_NANOBOT_START = "dev.claw800.runtime.NANOBOT_START";
    public static final String ACTION_NANOBOT_STOP = "dev.claw800.runtime.NANOBOT_STOP";
    public static final String ACTION_NANOBOT_RESTART = "dev.claw800.runtime.NANOBOT_RESTART";
    public static final String ACTION_NANOBOT_STATUS = "dev.claw800.runtime.NANOBOT_STATUS";
    public static final String ACTION_RUNTIME_STATUS = "dev.claw800.runtime.RUNTIME_STATUS";
    public static final String ACTION_RUNTIME_POLICY_GET = "dev.claw800.runtime.RUNTIME_POLICY_GET";
    public static final String ACTION_RUNTIME_POLICY_SET = "dev.claw800.runtime.RUNTIME_POLICY_SET";
    public static final String ACTION_CONFIG_READ = "dev.claw800.runtime.CONFIG_READ";
    public static final String ACTION_CONFIG_WRITE = "dev.claw800.runtime.CONFIG_WRITE";
    public static final String ACTION_LOG_TAIL = "dev.claw800.runtime.LOG_TAIL";
    public static final String ACTION_LOG_STREAM_START = "dev.claw800.runtime.LOG_STREAM_START";
    public static final String ACTION_LOG_STREAM_STOP = "dev.claw800.runtime.LOG_STREAM_STOP";
    public static final String ACTION_LOG_STREAM_EVENT = "dev.claw800.runtime.LOG_STREAM_EVENT";
    public static final String ACTION_ENSURE_AUTOSTART = "dev.claw800.runtime.ENSURE_AUTOSTART";
    public static final String ACTION_BACKUP_CREATE = "dev.claw800.runtime.BACKUP_CREATE";
    public static final String ACTION_BACKUP_LIST = "dev.claw800.runtime.BACKUP_LIST";
    public static final String ACTION_BACKUP_RESTORE = "dev.claw800.runtime.BACKUP_RESTORE";
    public static final String ACTION_BACKUP_VALIDATE = "dev.claw800.runtime.BACKUP_VALIDATE";
    public static final String ACTION_BACKUP_ESTIMATE = "dev.claw800.runtime.BACKUP_ESTIMATE";
    public static final String ACTION_BACKUP_PERMISSION_STATUS = "dev.claw800.runtime.BACKUP_PERMISSION_STATUS";
    public static final String ACTION_WEIXIN_LOGIN_START = "dev.claw800.runtime.WEIXIN_LOGIN_START";
    public static final String ACTION_WEIXIN_LOGIN_STOP = "dev.claw800.runtime.WEIXIN_LOGIN_STOP";
    public static final String ACTION_WEIXIN_LOGIN_STATUS = "dev.claw800.runtime.WEIXIN_LOGIN_STATUS";
    public static final String ACTION_WEIXIN_ACCOUNT_READ = "dev.claw800.runtime.WEIXIN_ACCOUNT_READ";
    public static final String ACTION_WEIXIN_LOGIN_STREAM_START = "dev.claw800.runtime.WEIXIN_LOGIN_STREAM_START";
    public static final String ACTION_WEIXIN_LOGIN_STREAM_STOP = "dev.claw800.runtime.WEIXIN_LOGIN_STREAM_STOP";
    public static final String ACTION_WEIXIN_LOGIN_STREAM_EVENT = "dev.claw800.runtime.WEIXIN_LOGIN_STREAM_EVENT";

    public static final String EXTRA_CALLER_PACKAGE = "dev.claw800.runtime.extra.CALLER_PACKAGE";
    public static final String EXTRA_RESULT_MESSENGER = "dev.claw800.runtime.extra.RESULT_MESSENGER";
    public static final String EXTRA_CONFIG_JSON = "dev.claw800.runtime.extra.CONFIG_JSON";
    public static final String EXTRA_LOG_LINES = "dev.claw800.runtime.extra.LOG_LINES";
    public static final String EXTRA_BACKUP_FILENAME = "dev.claw800.runtime.extra.BACKUP_FILENAME";
    public static final String EXTRA_WAKE_LOCK_ENABLED = "dev.claw800.runtime.extra.WAKE_LOCK_ENABLED";
    public static final String EXTRA_WIFI_LOCK_ENABLED = "dev.claw800.runtime.extra.WIFI_LOCK_ENABLED";

    public static final String RESULT_OK = "ok";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_ACTION = "action";
    public static final String RESULT_JSON = "json";

    private static final String ROOTFS_ALIAS = "claw800";
    private static final String ROOTFS_INSTALL_DIR_PATH =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/" + ROOTFS_ALIAS;
    private static final String ROOTFS_SENTINEL_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/claw800/rootfs-installed.stamp";
    private static final String GUEST_NANOBOT_CONFIG_PATH =
        ROOTFS_INSTALL_DIR_PATH + "/root/.nanobot/config.json";
    private static final String GUEST_NANOBOT_DIR =
        ROOTFS_INSTALL_DIR_PATH + "/root/.nanobot";
    private static final String NANOBOT_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-nanobot-gateway.log";
    private static final String NANOBOT_PID_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/run/claw800-nanobot-gateway.pid";
    private static final String WEIXIN_LOGIN_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-weixin-login.log";
    private static final String WEIXIN_LOGIN_PID_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/run/claw800-weixin-login.pid";
    private static final String GUEST_WEIXIN_ACCOUNT_PATH =
        ROOTFS_INSTALL_DIR_PATH + "/root/.nanobot/weixin/account.json";
    private static final String GUEST_WEIXIN_LOGIN_RUNNER_PATH =
        ROOTFS_INSTALL_DIR_PATH + "/root/.nanobot/tools/weixin-login-runner.py";
    private static final String WEIXIN_LOGIN_RUNNER_SOURCE =
        "#!/usr/bin/env python3\n" +
        "import asyncio\n" +
        "import sys\n" +
        "\n" +
        "from nanobot.config.loader import load_config\n" +
        "from nanobot.channels.weixin import WeixinChannel\n" +
        "\n" +
        "\n" +
        "def _print_login_url(url: str) -> None:\n" +
        "    print(f\"\\nLogin URL: {url}\\n\", flush=True)\n" +
        "\n" +
        "\n" +
        "WeixinChannel._print_qr_code = staticmethod(_print_login_url)\n" +
        "\n" +
        "\n" +
        "def main() -> int:\n" +
        "    config = load_config()\n" +
        "    raw = getattr(config.channels, \"weixin\", None) or {}\n" +
        "    channel = WeixinChannel(raw if isinstance(raw, dict) else {}, bus=None)\n" +
        "    ok = asyncio.run(channel.login(force=True))\n" +
        "    return 0 if ok else 1\n" +
        "\n" +
        "\n" +
        "if __name__ == \"__main__\":\n" +
        "    sys.exit(main())\n";
    private static final String RUNTIME_POLICY_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/claw800/runtime-policy.json";

    // Shared external storage for backups (Option B — survives both APK uninstalls).
    private static final String BACKUP_DIR_PATH =
        "/storage/emulated/0/Documents/800claw/backups";

    private static final String NOTIFICATION_CHANNEL_ID = "claw800_runtime_control_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "claw800 runtime";
    private static final int NOTIFICATION_ID = 8341;

    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;
    private static final int LOG_STREAM_POLL_INTERVAL_MS = 300;
    private static final int LOG_STREAM_MAX_CHUNK_BYTES = 64 * 1024;
    private static final boolean DEFAULT_ENABLE_PARTIAL_WAKE_LOCK = false;
    private static final boolean DEFAULT_ENABLE_WIFI_LOCK = false;

    private static final Object STATE_LOCK = new Object();
    private static Process sNanobotProcess;
    private static long sNanobotStartedAtMs;
    private static int sNanobotLastExitCode = Integer.MIN_VALUE;
    private static long sNanobotLastExitAtMs = 0L;
    private static String sNanobotLastError = "";
    private static Thread sLogStreamThread;
    private static Process sWeixinLoginProcess;
    private static long sWeixinLoginStartedAtMs;
    private static int sWeixinLoginLastExitCode = Integer.MIN_VALUE;
    private static long sWeixinLoginLastExitAtMs = 0L;
    private static Thread sWeixinLoginStreamThread;
    private static PowerManager.WakeLock sNanobotWakeLock;
    private static WifiManager.WifiLock sNanobotWifiLock;

    private static final Set<String> ALLOWED_CALLER_PACKAGES = new HashSet<>(
        Arrays.asList("com.claw800.ui", "com.claw800.runtime", "com.claw800.app", "dev.claw800.ui", "dev.claw800.runtime", "dev.claw800.app")
    );

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        setupNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Defensive path: if caller used startForegroundService() but system
            // redelivers a null intent (or malformed call path), still promote
            // once to satisfy Android's foreground-service start contract.
            startForegroundIfNeeded();
            return maybeStopSelf();
        }
        String action = intent.getAction();
        if (action == null || action.isEmpty()) {
            // Same defensive handling for empty action. This prevents
            // ForegroundServiceDidNotStartInTimeException on intermittent
            // malformed/empty action starts.
            startForegroundIfNeeded();
            return maybeStopSelf();
        }

        if (!ACTION_ENSURE_AUTOSTART.equals(action)) {
            // External calls can happen while caller app transitions
            // foreground/background (permission dialogs, recreation). Promote
            // early to foreground so Android does not reject start from
            // background-restricted state.
            startForegroundIfNeeded();
        }

        if (ACTION_ENSURE_AUTOSTART.equals(action)) {
            // `nanobot onboard` can take noticeable time on first run. Keep this
            // path off the main thread to avoid UI jank/ANR risk when app launch
            // triggers ensure-autostart.
            final Intent requestIntent = intent;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleEnsureAutostart(requestIntent);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "runtime control action failed: " + ACTION_ENSURE_AUTOSTART, e);
                        sendFailureResult(requestIntent, ACTION_ENSURE_AUTOSTART, e.getMessage() != null ? e.getMessage() : "Unknown error");
                    } finally {
                        if (!isNanobotRunning()) stopSelf();
                    }
                }
            }, "claw800-ensure-autostart").start();
            return START_NOT_STICKY;
        }

        if (ACTION_NANOBOT_START.equals(action) || ACTION_NANOBOT_STOP.equals(action) || ACTION_NANOBOT_RESTART.equals(action)) {
            if (!isAllowedCaller(intent)) {
                sendFailureResult(intent, action, "Caller package is not in allowlist.");
                return maybeStopSelf();
            }

            final Intent requestIntent = intent;
            final String requestAction = action;
            // Keep start/stop/restart off the main thread to avoid service execution timeouts.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (ACTION_NANOBOT_START.equals(requestAction)) {
                            handleNanobotStart(requestIntent);
                        } else if (ACTION_NANOBOT_STOP.equals(requestAction)) {
                            handleNanobotStop(requestIntent);
                        } else {
                            handleNanobotRestart(requestIntent);
                        }
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "runtime control action failed: " + requestAction, e);
                        sendFailureResult(requestIntent, requestAction, e.getMessage() != null ? e.getMessage() : "Unknown error");
                    } finally {
                        if (!isNanobotRunning()) stopSelf();
                    }
                }
            }, "claw800-nanobot-control").start();
            return START_NOT_STICKY;
        }

        try {
            if (!isAllowedCaller(intent)) {
                sendFailureResult(intent, action, "Caller package is not in allowlist.");
                return maybeStopSelf();
            }

            if (ACTION_NANOBOT_START.equals(action)) {
                handleNanobotStart(intent);
            } else if (ACTION_NANOBOT_STOP.equals(action)) {
                handleNanobotStop(intent);
            } else if (ACTION_NANOBOT_RESTART.equals(action)) {
                handleNanobotRestart(intent);
            } else if (ACTION_NANOBOT_STATUS.equals(action)) {
                handleNanobotStatus(intent);
            } else if (ACTION_RUNTIME_STATUS.equals(action)) {
                handleRuntimeStatus(intent);
            } else if (ACTION_RUNTIME_POLICY_GET.equals(action)) {
                handleRuntimePolicyGet(intent);
            } else if (ACTION_RUNTIME_POLICY_SET.equals(action)) {
                handleRuntimePolicySet(intent);
            } else if (ACTION_CONFIG_READ.equals(action)) {
                handleConfigRead(intent);
            } else if (ACTION_CONFIG_WRITE.equals(action)) {
                handleConfigWrite(intent);
            } else if (ACTION_LOG_TAIL.equals(action)) {
                handleLogTail(intent);
            } else if (ACTION_LOG_STREAM_START.equals(action)) {
                handleLogStreamStart(intent);
            } else if (ACTION_LOG_STREAM_STOP.equals(action)) {
                handleLogStreamStop(intent);
            } else if (ACTION_BACKUP_CREATE.equals(action)) {
                handleBackupCreate(intent);
            } else if (ACTION_BACKUP_LIST.equals(action)) {
                handleBackupList(intent);
            } else if (ACTION_BACKUP_RESTORE.equals(action)) {
                handleBackupRestore(intent);
            } else if (ACTION_BACKUP_VALIDATE.equals(action)) {
                handleBackupValidate(intent);
            } else if (ACTION_BACKUP_ESTIMATE.equals(action)) {
                handleBackupEstimate(intent);
            } else if (ACTION_BACKUP_PERMISSION_STATUS.equals(action)) {
                handleBackupPermissionStatus(intent);
            } else if (ACTION_WEIXIN_LOGIN_START.equals(action)) {
                handleWeixinLoginStart(intent);
            } else if (ACTION_WEIXIN_LOGIN_STOP.equals(action)) {
                handleWeixinLoginStop(intent);
            } else if (ACTION_WEIXIN_LOGIN_STATUS.equals(action)) {
                handleWeixinLoginStatus(intent);
            } else if (ACTION_WEIXIN_ACCOUNT_READ.equals(action)) {
                handleWeixinAccountRead(intent);
            } else if (ACTION_WEIXIN_LOGIN_STREAM_START.equals(action)) {
                handleWeixinLoginStreamStart(intent);
            } else if (ACTION_WEIXIN_LOGIN_STREAM_STOP.equals(action)) {
                handleWeixinLoginStreamStop(intent);
            } else {
                sendFailureResult(intent, action, "Unsupported action: " + action);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "runtime control action failed: " + action, e);
            sendFailureResult(intent, action, e.getMessage() != null ? e.getMessage() : "Unknown error");
        }

        return isNanobotRunning() ? START_STICKY : maybeStopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void ensureNanobotAutostart(Service ownerService) {
        Intent i = new Intent(ownerService, ClawRuntimeControlService.class);
        i.setAction(ACTION_ENSURE_AUTOSTART);
        // IMPORTANT:
        // Do NOT use startForegroundService() for this probe action.
        //
        // ACTION_ENSURE_AUTOSTART often becomes a no-op when rootfs/config is
        // not ready yet, in which case this service may return quickly without
        // transitioning to foreground. If started with
        // Context.startForegroundService(), Android expects startForeground()
        // within a strict timeout and kills the app with
        // ForegroundServiceDidNotStartInTimeException otherwise.
        //
        // We intentionally start as a normal service here; if nanobot is
        // actually launched, startForeground() is invoked immediately by
        // startNanobotProcessIfNeeded().
        ownerService.startService(i);
    }

    public static void ensureNanobotAutostart(android.content.Context context) {
        Intent i = new Intent(context, ClawRuntimeControlService.class);
        i.setAction(ACTION_ENSURE_AUTOSTART);
        // Same rationale as overload above.
        context.startService(i);
    }

    private boolean isAllowedCaller(Intent intent) {
        String callerPackage = intent.getStringExtra(EXTRA_CALLER_PACKAGE);
        if (callerPackage == null || callerPackage.isEmpty()) return false;
        return ALLOWED_CALLER_PACKAGES.contains(callerPackage);
    }

    private void handleNanobotStart(Intent intent) throws Exception {
        JSONObject status = startNanobotProcessIfNeeded();
        sendSuccessResult(intent, ACTION_NANOBOT_START, status);
    }

    private void handleNanobotStop(Intent intent) throws Exception {
        stopNanobotProcess();
        sendSuccessResult(intent, ACTION_NANOBOT_STOP, buildNanobotStatusJson(DEFAULT_TAIL_LINES));
    }

    private void handleNanobotRestart(Intent intent) throws Exception {
        stopNanobotProcess();
        JSONObject status = startNanobotProcessIfNeeded();
        sendSuccessResult(intent, ACTION_NANOBOT_RESTART, status);
    }

    private void handleNanobotStatus(Intent intent) throws Exception {
        int tailLines = sanitizeTailCount(intent.getIntExtra(EXTRA_LOG_LINES, DEFAULT_TAIL_LINES));
        sendSuccessResult(intent, ACTION_NANOBOT_STATUS, buildNanobotStatusJson(tailLines));
    }

    private void handleRuntimeStatus(Intent intent) throws Exception {
        JSONObject out = new JSONObject();
        out.put("rootfsReady", isRootfsReady());
        out.put("rootfsInstallDir", ROOTFS_INSTALL_DIR_PATH);
        out.put("nanobotConfigPath", GUEST_NANOBOT_CONFIG_PATH);
        out.put("nanobotConfigExists", new File(GUEST_NANOBOT_CONFIG_PATH).exists());
        out.put("runtimePolicy", buildRuntimePolicyJson(resolveRuntimePowerPolicy()));
        out.put("nanobot", buildNanobotStatusJson(DEFAULT_TAIL_LINES));
        sendSuccessResult(intent, ACTION_RUNTIME_STATUS, out);
    }

    private void handleRuntimePolicyGet(Intent intent) throws Exception {
        JSONObject out = buildRuntimePolicyJson(resolveRuntimePowerPolicy());
        out.put("path", RUNTIME_POLICY_PATH);
        sendSuccessResult(intent, ACTION_RUNTIME_POLICY_GET, out);
    }

    private void handleRuntimePolicySet(Intent intent) throws Exception {
        boolean hasWakeLockEnabled = intent.hasExtra(EXTRA_WAKE_LOCK_ENABLED);
        boolean hasWifiLockEnabled = intent.hasExtra(EXTRA_WIFI_LOCK_ENABLED);
        if (!hasWakeLockEnabled && !hasWifiLockEnabled) {
            throw new IllegalArgumentException(
                "Missing runtime policy values. Provide EXTRA_WAKE_LOCK_ENABLED and/or EXTRA_WIFI_LOCK_ENABLED."
            );
        }

        RuntimePowerPolicy current = resolveRuntimePowerPolicy();
        RuntimePowerPolicy updated = new RuntimePowerPolicy(
            hasWakeLockEnabled ? intent.getBooleanExtra(EXTRA_WAKE_LOCK_ENABLED, current.wakeLockEnabled) : current.wakeLockEnabled,
            hasWifiLockEnabled ? intent.getBooleanExtra(EXTRA_WIFI_LOCK_ENABLED, current.wifiLockEnabled) : current.wifiLockEnabled
        );
        writeRuntimePowerPolicy(updated);
        applyRuntimePowerPolicyIfRunning(updated);

        JSONObject out = buildRuntimePolicyJson(updated);
        out.put("path", RUNTIME_POLICY_PATH);
        out.put("updatedAt", nowIso());
        sendSuccessResult(intent, ACTION_RUNTIME_POLICY_SET, out);
    }

    private void handleConfigRead(Intent intent) throws Exception {
        JSONObject out = new JSONObject();
        File configFile = new File(GUEST_NANOBOT_CONFIG_PATH);
        out.put("path", GUEST_NANOBOT_CONFIG_PATH);
        out.put("exists", configFile.exists());
        out.put("content", configFile.exists() ? readFile(configFile) : "");
        sendSuccessResult(intent, ACTION_CONFIG_READ, out);
    }

    private void handleConfigWrite(Intent intent) throws Exception {
        String content = intent.getStringExtra(EXTRA_CONFIG_JSON);
        if (content == null) throw new IllegalArgumentException("Missing config content in EXTRA_CONFIG_JSON.");
        // Validate JSON object format early so operator gets actionable error.
        new JSONObject(content);

        writeConfigAtomically(content);

        JSONObject out = new JSONObject();
        out.put("path", GUEST_NANOBOT_CONFIG_PATH);
        out.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        out.put("writtenAt", nowIso());
        sendSuccessResult(intent, ACTION_CONFIG_WRITE, out);
    }

    private void handleLogTail(Intent intent) throws Exception {
        int tailLines = sanitizeTailCount(intent.getIntExtra(EXTRA_LOG_LINES, DEFAULT_TAIL_LINES));
        JSONObject out = new JSONObject();
        out.put("path", NANOBOT_LOG_PATH);
        out.put("lines", new JSONArray(readTailLines(new File(NANOBOT_LOG_PATH), tailLines)));
        sendSuccessResult(intent, ACTION_LOG_TAIL, out);
    }

    private void handleLogStreamStart(final Intent intent) throws Exception {
        final File logFile = new File(NANOBOT_LOG_PATH);
        final File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create nanobot log directory: " + parent.getAbsolutePath());
        }
        if (!logFile.exists() && !logFile.createNewFile()) {
            throw new IllegalStateException("Cannot create nanobot log file: " + NANOBOT_LOG_PATH);
        }

        stopLogStreamWorkerLocked();

        final Intent streamIntent = new Intent(intent);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLogStreamWorker(streamIntent, logFile);
            }
        }, "claw800-log-stream");

        synchronized (STATE_LOCK) {
            sLogStreamThread = worker;
        }
        worker.start();

        JSONObject out = new JSONObject();
        out.put("streaming", true);
        out.put("path", NANOBOT_LOG_PATH);
        out.put("startedAt", nowIso());
        sendSuccessResult(intent, ACTION_LOG_STREAM_START, out);
    }

    private void handleLogStreamStop(Intent intent) throws Exception {
        stopLogStreamWorkerLocked();
        JSONObject out = new JSONObject();
        out.put("streaming", false);
        out.put("stoppedAt", nowIso());
        sendSuccessResult(intent, ACTION_LOG_STREAM_STOP, out);
    }

    private void runLogStreamWorker(Intent streamIntent, File logFile) {
        long cursor = Math.max(0L, logFile.length());
        while (true) {
            Thread current = Thread.currentThread();
            synchronized (STATE_LOCK) {
                if (sLogStreamThread != current) break;
            }
            if (current.isInterrupted()) break;
            try {
                long fileLen = logFile.length();
                if (fileLen < cursor) {
                    cursor = 0L;
                }
                if (fileLen > cursor) {
                    long remaining = fileLen - cursor;
                    int toRead = (int) Math.min(remaining, LOG_STREAM_MAX_CHUNK_BYTES);
                    byte[] bytes = new byte[toRead];
                    try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                        raf.seek(cursor);
                        raf.readFully(bytes);
                    }
                    cursor += toRead;
                    String chunk = new String(bytes, StandardCharsets.UTF_8);
                    if (!chunk.isEmpty()) {
                        JSONObject payload = new JSONObject();
                        payload.put("path", NANOBOT_LOG_PATH);
                        payload.put("chunk", chunk);
                        payload.put("appendedAt", nowIso());
                        sendStreamEvent(streamIntent, payload);
                    }
                }
                Thread.sleep(LOG_STREAM_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "log stream worker failed", e);
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("path", NANOBOT_LOG_PATH);
                    payload.put("error", e.getMessage() == null ? "log stream worker failed" : e.getMessage());
                    payload.put("appendedAt", nowIso());
                    sendStreamEvent(streamIntent, payload);
                } catch (Exception ignore) {
                    // Ignore secondary stream event failure.
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void sendStreamEvent(Intent requestIntent, JSONObject payload) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(RESULT_OK, true);
        bundle.putString(RESULT_ACTION, ACTION_LOG_STREAM_EVENT);
        bundle.putString(RESULT_JSON, payload.toString());
        sendResultBundle(requestIntent, 2, bundle);
    }

    private void handleWeixinLoginStart(Intent intent) throws Exception {
        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot start Weixin login: rootfs is not ready.");
        }

        stopWeixinLoginProcess();

        File logFile = new File(WEIXIN_LOGIN_LOG_PATH);
        File logParent = logFile.getParentFile();
        if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
            throw new IllegalStateException("Cannot create Weixin login log directory: " + logParent.getAbsolutePath());
        }

        try (FileOutputStream ignored = new FileOutputStream(logFile, false)) {
            // Truncate/create login log for this attempt.
        }
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            String marker = "\n=== " + nowIso() + " claw800 runtime starting weixin login ===\n";
            fos.write(marker.getBytes(StandardCharsets.UTF_8));
        }

        writeWeixinLoginRunnerIfNeeded();

        String script =
            "set -eu\n" +
            "mkdir -p '" + new File(WEIXIN_LOGIN_PID_PATH).getParent() + "'\n" +
            "printf '%s\\n' $$ > '" + WEIXIN_LOGIN_PID_PATH + "'\n" +
            "export PYTHONUNBUFFERED=1\n" +
            "exec proot-distro login " + ROOTFS_ALIAS + " -- bash -lc '" +
            "export PYTHONUNBUFFERED=1; source /opt/venv/bin/activate && exec python -u /root/.nanobot/tools/weixin-login-runner.py'\n";

        ProcessBuilder pb = new ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh", "-c", script);
        Map<String, String> env = pb.environment();
        String originalPath = env.get("PATH");
        if (originalPath == null) originalPath = "";
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + originalPath);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PYTHONUNBUFFERED", "1");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        Process started = pb.start();
        synchronized (STATE_LOCK) {
            sWeixinLoginProcess = started;
            sWeixinLoginStartedAtMs = System.currentTimeMillis();
            sWeixinLoginLastExitCode = Integer.MIN_VALUE;
        }
        startForegroundIfNeeded();
        watchWeixinLoginExit(started);

        JSONObject out = buildWeixinLoginStatusJson(DEFAULT_TAIL_LINES);
        out.put("startedAt", nowIso());
        sendSuccessResult(intent, ACTION_WEIXIN_LOGIN_START, out);
    }

    private void writeWeixinLoginRunnerIfNeeded() throws Exception {
        File runnerFile = new File(GUEST_WEIXIN_LOGIN_RUNNER_PATH);
        File parent = runnerFile.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("Weixin login runner parent directory is null.");
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create nanobot directory: " + parent.getAbsolutePath());
        }

        File tmpFile = new File(parent, "weixin-login-runner.py.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile, false)) {
            fos.write(WEIXIN_LOGIN_RUNNER_SOURCE.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }

        String mvScript =
            "set -eu\n" +
            "mv -f '" + tmpFile.getAbsolutePath() + "' '" + runnerFile.getAbsolutePath() + "'\n" +
            "chmod 700 '" + runnerFile.getAbsolutePath() + "'\n";
        runTermuxShellCommand(mvScript, true);
    }

    private void handleWeixinLoginStop(Intent intent) throws Exception {
        stopWeixinLoginProcess();
        JSONObject out = buildWeixinLoginStatusJson(DEFAULT_TAIL_LINES);
        out.put("stoppedAt", nowIso());
        sendSuccessResult(intent, ACTION_WEIXIN_LOGIN_STOP, out);
    }

    private void handleWeixinLoginStatus(Intent intent) throws Exception {
        int tailLines = sanitizeTailCount(intent.getIntExtra(EXTRA_LOG_LINES, DEFAULT_TAIL_LINES));
        sendSuccessResult(intent, ACTION_WEIXIN_LOGIN_STATUS, buildWeixinLoginStatusJson(tailLines));
    }

    private void handleWeixinAccountRead(Intent intent) throws Exception {
        sendSuccessResult(intent, ACTION_WEIXIN_ACCOUNT_READ, buildWeixinAccountJson());
    }

    private void handleWeixinLoginStreamStart(final Intent intent) throws Exception {
        final File logFile = new File(WEIXIN_LOGIN_LOG_PATH);
        final File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create Weixin login log directory: " + parent.getAbsolutePath());
        }
        if (!logFile.exists() && !logFile.createNewFile()) {
            throw new IllegalStateException("Cannot create Weixin login log file: " + WEIXIN_LOGIN_LOG_PATH);
        }

        stopWeixinLoginStreamWorkerLocked();

        final Intent streamIntent = new Intent(intent);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runWeixinLoginStreamWorker(streamIntent, logFile);
            }
        }, "claw800-weixin-login-stream");

        synchronized (STATE_LOCK) {
            sWeixinLoginStreamThread = worker;
        }
        worker.start();
        startForegroundIfNeeded();

        JSONObject out = new JSONObject();
        out.put("streaming", true);
        out.put("path", WEIXIN_LOGIN_LOG_PATH);
        out.put("startedAt", nowIso());
        sendSuccessResult(intent, ACTION_WEIXIN_LOGIN_STREAM_START, out);
    }

    private void handleWeixinLoginStreamStop(Intent intent) throws Exception {
        stopWeixinLoginStreamWorkerLocked();
        JSONObject out = new JSONObject();
        out.put("streaming", false);
        out.put("stoppedAt", nowIso());
        sendSuccessResult(intent, ACTION_WEIXIN_LOGIN_STREAM_STOP, out);
    }

    private void runWeixinLoginStreamWorker(Intent streamIntent, File logFile) {
        long cursor = Math.max(0L, logFile.length());
        while (true) {
            Thread current = Thread.currentThread();
            synchronized (STATE_LOCK) {
                if (sWeixinLoginStreamThread != current) break;
            }
            if (current.isInterrupted()) break;
            try {
                long fileLen = logFile.length();
                if (fileLen < cursor) {
                    cursor = 0L;
                }
                if (fileLen > cursor) {
                    long remaining = fileLen - cursor;
                    int toRead = (int) Math.min(remaining, LOG_STREAM_MAX_CHUNK_BYTES);
                    byte[] bytes = new byte[toRead];
                    try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                        raf.seek(cursor);
                        raf.readFully(bytes);
                    }
                    cursor += toRead;
                    String chunk = new String(bytes, StandardCharsets.UTF_8);
                    if (!chunk.isEmpty()) {
                        JSONObject payload = new JSONObject();
                        payload.put("path", WEIXIN_LOGIN_LOG_PATH);
                        payload.put("chunk", chunk);
                        payload.put("appendedAt", nowIso());
                        sendWeixinLoginStreamEvent(streamIntent, payload);
                    }
                }
                Thread.sleep(LOG_STREAM_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "weixin login stream worker failed", e);
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("path", WEIXIN_LOGIN_LOG_PATH);
                    payload.put("error", e.getMessage() == null ? "weixin login stream worker failed" : e.getMessage());
                    payload.put("appendedAt", nowIso());
                    sendWeixinLoginStreamEvent(streamIntent, payload);
                } catch (Exception ignore) {
                    // Ignore secondary stream event failure.
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void sendWeixinLoginStreamEvent(Intent requestIntent, JSONObject payload) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(RESULT_OK, true);
        bundle.putString(RESULT_ACTION, ACTION_WEIXIN_LOGIN_STREAM_EVENT);
        bundle.putString(RESULT_JSON, payload.toString());
        sendResultBundle(requestIntent, 2, bundle);
    }

    private JSONObject buildWeixinAccountJson() throws Exception {
        File accountFile = new File(GUEST_WEIXIN_ACCOUNT_PATH);
        JSONObject out = new JSONObject();
        out.put("path", GUEST_WEIXIN_ACCOUNT_PATH);
        out.put("accountExists", accountFile.exists());
        boolean hasToken = false;
        if (accountFile.exists()) {
            try {
                JSONObject parsed = new JSONObject(readFile(accountFile));
                String token = parsed.optString("token", "");
                hasToken = token != null && !token.trim().isEmpty();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse Weixin account.json", e);
            }
        }
        out.put("hasToken", hasToken);
        return out;
    }

    private JSONObject buildWeixinLoginStatusJson(int tailLines) throws Exception {
        boolean running;
        long startedAtMs;
        int lastExitCode;
        long lastExitAtMs;
        synchronized (STATE_LOCK) {
            running = isWeixinLoginRunningLocked();
            startedAtMs = sWeixinLoginStartedAtMs;
            lastExitCode = sWeixinLoginLastExitCode;
            lastExitAtMs = sWeixinLoginLastExitAtMs;
        }

        JSONObject account = buildWeixinAccountJson();
        File logFile = new File(WEIXIN_LOGIN_LOG_PATH);
        ArrayDeque<String> tail = readTailLines(logFile, tailLines);
        JSONArray logTail = new JSONArray();
        for (String line : tail) {
            logTail.put(line);
        }

        JSONObject out = new JSONObject();
        out.put("running", running);
        out.put("startedAtMs", startedAtMs == 0L ? JSONObject.NULL : startedAtMs);
        out.put("logPath", WEIXIN_LOGIN_LOG_PATH);
        out.put("accountPath", GUEST_WEIXIN_ACCOUNT_PATH);
        out.put("accountExists", account.optBoolean("accountExists", false));
        out.put("hasToken", account.optBoolean("hasToken", false));
        out.put("lastExitCode", lastExitCode == Integer.MIN_VALUE ? JSONObject.NULL : lastExitCode);
        out.put("lastExitAtMs", lastExitAtMs == 0L ? JSONObject.NULL : lastExitAtMs);
        out.put("outputTail", trimForJson(String.join("\n", tail.toArray(new String[0])), 1200));
        out.put("logTail", logTail);
        return out;
    }

    private void watchWeixinLoginExit(final Process observedProcess) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int exitCode = Integer.MIN_VALUE;
                try {
                    exitCode = observedProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                synchronized (STATE_LOCK) {
                    if (sWeixinLoginProcess == observedProcess) {
                        sWeixinLoginProcess = null;
                        sWeixinLoginLastExitCode = exitCode;
                        sWeixinLoginLastExitAtMs = System.currentTimeMillis();
                    }
                }

                FileUtils.deleteFile("weixin login pid file", WEIXIN_LOGIN_PID_PATH, true);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopForegroundIfIdle();
                    }
                });
            }
        }, "claw800-weixin-login-watch").start();
    }

    private void stopWeixinLoginProcess() throws Exception {
        Process processToStop;
        synchronized (STATE_LOCK) {
            processToStop = sWeixinLoginProcess;
        }

        if (processToStop == null || !isProcessAlive(processToStop)) {
            synchronized (STATE_LOCK) {
                sWeixinLoginProcess = null;
            }
            stopWeixinLoginByPidFileIfPresent();
            return;
        }

        processToStop.destroy();
        try {
            boolean exited = processToStop.waitFor(5, TimeUnit.SECONDS);
            if (!exited && isProcessAlive(processToStop)) {
                destroyProcessForcibly(processToStop);
                processToStop.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessForcibly(processToStop);
        }

        stopWeixinLoginByPidFileIfPresent();

        synchronized (STATE_LOCK) {
            if (sWeixinLoginProcess == processToStop) {
                sWeixinLoginProcess = null;
                sWeixinLoginLastExitAtMs = System.currentTimeMillis();
                sWeixinLoginLastExitCode = 0;
            }
        }

        FileUtils.deleteFile("weixin login pid file", WEIXIN_LOGIN_PID_PATH, true);
        stopForegroundIfIdle();
    }

    private void stopWeixinLoginByPidFileIfPresent() throws Exception {
        File pidFile = new File(WEIXIN_LOGIN_PID_PATH);
        if (!pidFile.exists()) {
            stopForegroundIfIdle();
            return;
        }

        String pidRaw = readFile(pidFile).trim();
        if (pidRaw.isEmpty()) {
            FileUtils.deleteFile("weixin login pid file", WEIXIN_LOGIN_PID_PATH, true);
            stopForegroundIfIdle();
            return;
        }

        String script =
            "set -eu\n" +
            "PID='" + pidRaw + "'\n" +
            "if kill -0 \"$PID\" 2>/dev/null; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  sleep 1\n" +
            "fi\n" +
            "if kill -0 \"$PID\" 2>/dev/null; then\n" +
            "  kill -9 \"$PID\" 2>/dev/null || true\n" +
            "fi\n";
        runTermuxShellCommand(script, false);

        FileUtils.deleteFile("weixin login pid file", WEIXIN_LOGIN_PID_PATH, true);
        synchronized (STATE_LOCK) {
            sWeixinLoginProcess = null;
            sWeixinLoginLastExitAtMs = System.currentTimeMillis();
            sWeixinLoginLastExitCode = 0;
        }
        stopForegroundIfIdle();
    }

    private boolean isWeixinLoginRunning() {
        synchronized (STATE_LOCK) {
            return isWeixinLoginRunningLocked();
        }
    }

    private boolean isWeixinLoginRunningLocked() {
        return sWeixinLoginProcess != null && isProcessAlive(sWeixinLoginProcess);
    }

    private boolean isWeixinLoginStreamActive() {
        synchronized (STATE_LOCK) {
            return sWeixinLoginStreamThread != null && sWeixinLoginStreamThread.isAlive();
        }
    }

    private void stopWeixinLoginStreamWorkerLocked() {
        Thread threadToStop;
        synchronized (STATE_LOCK) {
            threadToStop = sWeixinLoginStreamThread;
            sWeixinLoginStreamThread = null;
        }
        if (threadToStop != null) {
            threadToStop.interrupt();
        }
    }

    private boolean isRuntimeControlIdle() {
        return !isNanobotRunning()
            && !isLogStreamActive()
            && !isWeixinLoginRunning()
            && !isWeixinLoginStreamActive();
    }

    private void handleEnsureAutostart(Intent intent) throws Exception {
        JSONObject out = new JSONObject();
        boolean rootfsReady = isRootfsReady();
        File configFile = new File(GUEST_NANOBOT_CONFIG_PATH);
        boolean configExists = configFile.exists();
        out.put("rootfsReady", rootfsReady);
        out.put("configExists", configExists);

        if (!rootfsReady) {
            out.put("started", false);
            out.put("status", "waitingForRootfs");
            out.put("reason", "rootfs not ready");
        } else {
            ClawProvisiondManager.ensureProvisiond(getApplicationContext(), this::runTermuxShellCommand);
            out.put("provisiondEnsured", true);
            if (!configExists) {
                out.put("onboardAttempted", true);
                try {
                    String onboardOutput = runNanobotOnboard();
                    out.put("onboardSucceeded", true);
                    out.put("onboardOutputTail", trimForJson(onboardOutput, 1200));
                } catch (Exception e) {
                    out.put("onboardSucceeded", false);
                    out.put("onboardError", e.getMessage() != null ? e.getMessage() : "nanobot onboard failed");
                }

                boolean configCreated = configFile.exists();
                out.put("configExistsAfterOnboard", configCreated);
                if (!configCreated) {
                    out.put("started", false);
                    out.put("status", "waitingForConfig");
                    out.put("reason", "nanobot config still missing after onboard attempt");
                } else {
                    JSONObject status = startNanobotProcessIfNeeded();
                    out.put("started", true);
                    out.put("status", "running");
                    out.put("nanobot", status);
                }
            } else {
                JSONObject status = startNanobotProcessIfNeeded();
                out.put("started", true);
                out.put("status", "running");
                out.put("nanobot", status);
            }
        }
        sendSuccessResult(intent, ACTION_ENSURE_AUTOSTART, out);
    }

    private String runNanobotOnboard() throws Exception {
        String script =
            "set -eu\n" +
            "exec proot-distro login " + ROOTFS_ALIAS + " -- bash -lc 'source /opt/venv/bin/activate && nanobot onboard'\n";
        return runTermuxShellCommand(script, true);
    }

    // --- Backup / Restore ---

    private void handleBackupCreate(Intent intent) throws Exception {
        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot create backup: rootfs is not ready.");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                throw new IllegalStateException(
                    "Cannot create backup: All files access not granted. " +
                    "Go to Settings → Apps → Termux → Special app access → " +
                    "All files access → enable it."
                );
            }
        }

        File backupDir = new File(BACKUP_DIR_PATH);
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IllegalStateException("Cannot create backup directory: " + BACKUP_DIR_PATH);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        String nonce = String.valueOf(System.nanoTime());
        String filename = "800claw-backup-" + timestamp + "-" + nonce.substring(Math.max(0, nonce.length() - 6)) + ".tar.gz";
        File archiveFile = new File(backupDir, filename);

        // tar.gz the /root/.nanobot dir from inside the rootfs.
        // We run tar from the rootfs root so paths inside the archive are relative (./root/.nanobot/...).
        String script =
            "set -eu\n" +
            "tar czf '" + archiveFile.getAbsolutePath() + "' " +
            "  -C '" + ROOTFS_INSTALL_DIR_PATH + "' " +
            "  './root/.nanobot'\n";
        String output = runTermuxShellCommand(script, true);

        JSONObject out = new JSONObject();
        out.put("filename", filename);
        out.put("path", archiveFile.getAbsolutePath());
        out.put("sizeBytes", archiveFile.length());
        out.put("createdAt", nowIso());
        out.put("outputTail", trimForJson(output, 500));
        sendSuccessResult(intent, ACTION_BACKUP_CREATE, out);
    }

    private void handleBackupEstimate(Intent intent) throws Exception {
        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot estimate backup: rootfs is not ready.");
        }

        File sourceDir = new File(GUEST_NANOBOT_DIR);
        long sourceBytes = calculateDirectorySize(sourceDir);
        long estimatedBackupBytes = estimateCompressedBackupBytes(sourceBytes);

        boolean allFilesAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            || Environment.isExternalStorageManager();

        long freeStorageBytes = -1L;
        if (allFilesAccessGranted) {
            File backupDir = new File(BACKUP_DIR_PATH);
            if (!backupDir.exists()) {
                File parent = backupDir.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
            }
            // getUsableSpace works for both existing and non-existing leaf paths.
            freeStorageBytes = backupDir.getUsableSpace();
        }

        JSONObject out = new JSONObject();
        out.put("sourcePath", GUEST_NANOBOT_DIR);
        out.put("sourceBytes", sourceBytes);
        out.put("estimatedBackupBytes", estimatedBackupBytes);
        out.put("backupDir", BACKUP_DIR_PATH);
        out.put("freeStorageBytes", freeStorageBytes);
        out.put("allFilesAccessGranted", allFilesAccessGranted);
        out.put(
            "hasEnoughSpace",
            freeStorageBytes >= 0 && freeStorageBytes >= estimatedBackupBytes
        );
        sendSuccessResult(intent, ACTION_BACKUP_ESTIMATE, out);
    }

    private void handleBackupPermissionStatus(Intent intent) throws Exception {
        boolean allFilesAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            || Environment.isExternalStorageManager();
        File backupDir = new File(BACKUP_DIR_PATH);

        JSONObject out = new JSONObject();
        out.put("allFilesAccessGranted", allFilesAccessGranted);
        out.put("backupDir", BACKUP_DIR_PATH);
        out.put("backupDirExists", backupDir.exists());
        out.put("freeStorageBytes", allFilesAccessGranted ? backupDir.getUsableSpace() : -1L);
        sendSuccessResult(intent, ACTION_BACKUP_PERMISSION_STATUS, out);
    }

    private void handleBackupList(Intent intent) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                throw new IllegalStateException(
                    "Cannot list backups: All files access not granted. " +
                    "Go to Settings -> Apps -> Termux -> Special app access -> " +
                    "All files access -> enable it."
                );
            }
        }

        File backupDir = new File(BACKUP_DIR_PATH);
        JSONArray list = new JSONArray();

        if (backupDir.exists() && backupDir.isDirectory()) {
            File[] files = backupDir.listFiles();
            if (files != null) {
                // Sort by name descending so newest appears first.
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                for (File f : files) {
                    String name = f.getName();
                    boolean isKnownBackupPrefix = name.startsWith("800claw-backup-") || name.startsWith("nanobot-backup-");
                    if (f.isFile() && (name.endsWith(".tar.gz") || name.endsWith(".zip"))) {
                        // Known-prefix archives are always included.
                        // For unknown archives, probe for config.json to identify
                        // external nanobot instance backups.
                        if (!isKnownBackupPrefix && !archiveContainsConfig(f)) {
                            continue;
                        }
                        JSONObject entry = new JSONObject();
                        entry.put("filename", f.getName());
                        entry.put("path", f.getAbsolutePath());
                        entry.put("sizeBytes", f.length());
                        entry.put("lastModified", f.lastModified());
                        entry.put("source", isKnownBackupPrefix ? "native" : "external");
                        list.put(entry);
                    }
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("dir", BACKUP_DIR_PATH);
        out.put("count", list.length());
        out.put("backups", list);
        sendSuccessResult(intent, ACTION_BACKUP_LIST, out);
    }

    private void handleBackupValidate(Intent intent) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                throw new IllegalStateException(
                    "Cannot validate backup: All files access not granted. " +
                    "Go to Settings -> Apps -> Termux -> Special app access -> " +
                    "All files access -> enable it."
                );
            }
        }

        String filename = requireBackupFilename(intent);
        File archiveFile = resolveBackupArchiveFile(filename);
        JSONObject validation = validateBackupArchive(archiveFile);
        sendSuccessResult(intent, ACTION_BACKUP_VALIDATE, validation);
    }

    private void handleBackupRestore(Intent intent) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                throw new IllegalStateException(
                    "Cannot restore backup: All files access not granted. " +
                    "Go to Settings -> Apps -> Termux -> Special app access -> " +
                    "All files access -> enable it."
                );
            }
        }

        String filename = requireBackupFilename(intent);
        File archiveFile = resolveBackupArchiveFile(filename);

        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot restore backup: rootfs is not ready.");
        }

        // Validate before any destructive step (do not clear /root/.nanobot until confirmed valid).
        JSONObject validation = validateBackupArchive(archiveFile);
        if (!validation.optBoolean("valid", false)) {
            String reason = validation.optString("reason", "Invalid backup file.");
            throw new IllegalArgumentException("Cannot restore backup: " + reason);
        }

        String configRelPath = validation.getString("configPath");
        String nanobotDir = configRelPath.endsWith("/config.json")
            ? configRelPath.substring(0, configRelPath.length() - "/config.json".length())
            : configRelPath.replace("config.json", "");
        if (nanobotDir.endsWith("/")) {
            nanobotDir = nanobotDir.substring(0, nanobotDir.length() - 1);
        }

        // Step 1: clear the existing /root/.nanobot in the rootfs.
        String clearScript =
            "set -eu\n" +
            "rm -rf '" + GUEST_NANOBOT_DIR + "'\n" +
            "mkdir -p '" + GUEST_NANOBOT_DIR + "'\n";
        runTermuxShellCommand(clearScript, true);

        // Step 2: extract and place contents into rootfs.
        String output;
        boolean isNativeBackup = nanobotDir.equals("./root/.nanobot") || nanobotDir.equals("root/.nanobot");
        if (isNativeBackup) {
            // Native backup: extract directly into rootfs (paths already align).
            String restoreScript =
                "set -eu\n" +
                "tar xzf '" + archiveFile.getAbsolutePath() + "' " +
                "  -C '" + ROOTFS_INSTALL_DIR_PATH + "' " +
                "  '" + nanobotDir + "'\n";
            output = runTermuxShellCommand(restoreScript, true);
        } else {
            // External backup: extract (unwrap nested .tar if present), find data/ on disk, copy.
            output = restoreExternalBackupArchive(archiveFile, filename);
        }

        // Verify config survived.
        File configFile = new File(GUEST_NANOBOT_CONFIG_PATH);
        boolean configRestored = configFile.exists();

        JSONObject out = new JSONObject();
        out.put("filename", filename);
        out.put("restoredAt", nowIso());
        out.put("configRestored", configRestored);
        out.put("outputTail", trimForJson(output, 500));
        sendSuccessResult(intent, ACTION_BACKUP_RESTORE, out);
    }

    private JSONObject startNanobotProcessIfNeeded() throws Exception {
        synchronized (STATE_LOCK) {
            if (isNanobotRunningLocked()) {
                return buildNanobotStatusJson(DEFAULT_TAIL_LINES);
            }
        }

        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot start nanobot: rootfs is not ready.");
        }

        File configFile = new File(GUEST_NANOBOT_CONFIG_PATH);
        if (!configFile.exists()) {
            throw new IllegalStateException("Cannot start nanobot: config.json not found at " + GUEST_NANOBOT_CONFIG_PATH);
        }

        File logFile = new File(NANOBOT_LOG_PATH);
        File logParent = logFile.getParentFile();
        if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
            throw new IllegalStateException("Cannot create nanobot log directory: " + logParent.getAbsolutePath());
        }

        // Start each gateway run with a fresh log file at the same fixed path.
        try (FileOutputStream ignored = new FileOutputStream(logFile, false)) {
            // Opening with append=false truncates existing content (or creates the file).
        }

        // Add a clear start boundary for operators reading tails in RN.
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            String marker = "\n=== " + nowIso() + " claw800 runtime starting nanobot gateway ===\n";
            fos.write(marker.getBytes(StandardCharsets.UTF_8));
        }

        // 20260517 - start cron daemon if not running
        String script =
            "set -eu\n" +
            "mkdir -p '" + new File(NANOBOT_PID_PATH).getParent() + "'\n" +
            "printf '%s\\n' $$ > '" + NANOBOT_PID_PATH + "'\n" +
            "exec proot-distro login " + ROOTFS_ALIAS + " -- bash -lc '" +
            "if ! pgrep -x cron >/dev/null 2>&1; then cron; fi; " +
            "source /opt/venv/bin/activate && exec nanobot gateway'\n";

        ProcessBuilder pb = new ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh", "-c", script);
        Map<String, String> env = pb.environment();
        String originalPath = env.get("PATH");
        if (originalPath == null) originalPath = "";
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + originalPath);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        Process started = pb.start();

        synchronized (STATE_LOCK) {
            sNanobotProcess = started;
            sNanobotStartedAtMs = System.currentTimeMillis();
            sNanobotLastError = "";
        }

        RuntimePowerPolicy runtimePolicy = resolveRuntimePowerPolicy();
        acquireNanobotWakeAndWifiLock(runtimePolicy);
        startForegroundIfNeeded();
        watchNanobotExit(started);
        return buildNanobotStatusJson(DEFAULT_TAIL_LINES);
    }

    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void acquireNanobotWakeAndWifiLock(RuntimePowerPolicy policy) {
        if (policy.wakeLockEnabled) {
            ensureWakeLockHeld();
        } else {
            releaseNanobotWakeLockIfHeld();
        }

        if (policy.wifiLockEnabled) {
            ensureWifiLockHeld();
        } else {
            releaseNanobotWifiLockIfHeld();
        }

        // Request ignoring battery optimizations where possible (OEM-dependent).
        // This is already declared in the manifest; we don't fail if the user declines.
        try {
            if (policy.wakeLockEnabled && !PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
                PermissionUtils.requestDisableBatteryOptimizations(this);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request disabling battery optimizations", e);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void ensureWakeLockHeld() {
        synchronized (STATE_LOCK) {
            if (sNanobotWakeLock != null && sNanobotWakeLock.isHeld()) return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":claw800-nanobot-wakelock"
        );
        try {
            wakeLock.acquire();
            synchronized (STATE_LOCK) {
                sNanobotWakeLock = wakeLock;
            }
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to acquire PARTIAL_WAKE_LOCK", e);
        }
    }

    private void ensureWifiLockHeld() {
        synchronized (STATE_LOCK) {
            if (sNanobotWifiLock != null && sNanobotWifiLock.isHeld()) return;
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiManager.WifiLock wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":claw800-nanobot-wifilock"
                );
                wifiLock.acquire();
                synchronized (STATE_LOCK) {
                    sNanobotWifiLock = wifiLock;
                }
            }
        } catch (SecurityException se) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to acquire Wi-Fi lock (missing permission?)", se);
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to acquire Wi-Fi lock", e);
        }
    }

    private void applyRuntimePowerPolicyIfRunning(RuntimePowerPolicy policy) {
        if (!isNanobotRunning()) return;
        acquireNanobotWakeAndWifiLock(policy);
    }

    private void releaseNanobotWakeAndWifiLockIfHeld() {
        PowerManager.WakeLock wakeLockToRelease;
        WifiManager.WifiLock wifiLockToRelease;
        synchronized (STATE_LOCK) {
            wakeLockToRelease = sNanobotWakeLock;
            wifiLockToRelease = sNanobotWifiLock;
            sNanobotWakeLock = null;
            sNanobotWifiLock = null;
        }

        if (wakeLockToRelease != null) {
            try {
                if (wakeLockToRelease.isHeld()) wakeLockToRelease.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup only.
            }
        }
        if (wifiLockToRelease != null) {
            try {
                if (wifiLockToRelease.isHeld()) wifiLockToRelease.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void releaseNanobotWakeLockIfHeld() {
        PowerManager.WakeLock wakeLockToRelease;
        synchronized (STATE_LOCK) {
            wakeLockToRelease = sNanobotWakeLock;
            sNanobotWakeLock = null;
        }
        if (wakeLockToRelease != null) {
            try {
                if (wakeLockToRelease.isHeld()) wakeLockToRelease.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void releaseNanobotWifiLockIfHeld() {
        WifiManager.WifiLock wifiLockToRelease;
        synchronized (STATE_LOCK) {
            wifiLockToRelease = sNanobotWifiLock;
            sNanobotWifiLock = null;
        }
        if (wifiLockToRelease != null) {
            try {
                if (wifiLockToRelease.isHeld()) wifiLockToRelease.release();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private RuntimePowerPolicy resolveRuntimePowerPolicy() {
        File policyFile = new File(RUNTIME_POLICY_PATH);
        if (!policyFile.exists()) {
            return new RuntimePowerPolicy(DEFAULT_ENABLE_PARTIAL_WAKE_LOCK, DEFAULT_ENABLE_WIFI_LOCK);
        }

        try {
            String raw = readFile(policyFile);
            if (raw == null || raw.trim().isEmpty()) {
                return new RuntimePowerPolicy(DEFAULT_ENABLE_PARTIAL_WAKE_LOCK, DEFAULT_ENABLE_WIFI_LOCK);
            }
            JSONObject parsed = new JSONObject(raw);
            boolean wakeLockEnabled = parsed.optBoolean("wakeLockEnabled", DEFAULT_ENABLE_PARTIAL_WAKE_LOCK);
            boolean wifiLockEnabled = parsed.optBoolean("wifiLockEnabled", DEFAULT_ENABLE_WIFI_LOCK);
            return new RuntimePowerPolicy(wakeLockEnabled, wifiLockEnabled);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse runtime policy; falling back to defaults", e);
            return new RuntimePowerPolicy(DEFAULT_ENABLE_PARTIAL_WAKE_LOCK, DEFAULT_ENABLE_WIFI_LOCK);
        }
    }

    private void writeRuntimePowerPolicy(RuntimePowerPolicy policy) throws Exception {
        File policyFile = new File(RUNTIME_POLICY_PATH);
        File parent = policyFile.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("Runtime policy parent directory is null.");
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create runtime policy directory: " + parent.getAbsolutePath());
        }

        JSONObject out = buildRuntimePolicyJson(policy);
        try (FileOutputStream fos = new FileOutputStream(policyFile, false)) {
            fos.write(out.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
    }

    private JSONObject buildRuntimePolicyJson(RuntimePowerPolicy policy) throws Exception {
        JSONObject out = new JSONObject();
        out.put("wakeLockEnabled", policy.wakeLockEnabled);
        out.put("wifiLockEnabled", policy.wifiLockEnabled);
        return out;
    }

    private static final class RuntimePowerPolicy {
        final boolean wakeLockEnabled;
        final boolean wifiLockEnabled;

        RuntimePowerPolicy(boolean wakeLockEnabled, boolean wifiLockEnabled) {
            this.wakeLockEnabled = wakeLockEnabled;
            this.wifiLockEnabled = wifiLockEnabled;
        }
    }

    private void watchNanobotExit(final Process observedProcess) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int exitCode = Integer.MIN_VALUE;
                try {
                    exitCode = observedProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                synchronized (STATE_LOCK) {
                    if (sNanobotProcess == observedProcess) {
                        sNanobotProcess = null;
                        sNanobotLastExitCode = exitCode;
                        sNanobotLastExitAtMs = System.currentTimeMillis();
                    }
                }

                // Stop holding wake/Wi-Fi locks as soon as we observe nanobot exit.
                releaseNanobotWakeAndWifiLockIfHeld();

                FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopForegroundIfIdle();
                    }
                });
            }
        }, "claw800-nanobot-watch").start();
    }

    private void stopNanobotProcess() throws Exception {
        Process processToStop;
        synchronized (STATE_LOCK) {
            processToStop = sNanobotProcess;
        }

        if (processToStop == null || !isProcessAlive(processToStop)) {
            synchronized (STATE_LOCK) {
                sNanobotProcess = null;
            }
            // Service may have restarted and lost in-memory process handle while
            // nanobot is still running. Use pidfile as fallback stop source.
            stopNanobotByPidFileIfPresent();
            return;
        }

        processToStop.destroy();
        try {
            boolean exited = processToStop.waitFor(5, TimeUnit.SECONDS);
            if (!exited && isProcessAlive(processToStop)) {
                destroyProcessForcibly(processToStop);
                processToStop.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessForcibly(processToStop);
        }

        // If handle-based termination still leaves gateway alive, use pidfile fallback.
        stopNanobotByPidFileIfPresent();

        synchronized (STATE_LOCK) {
            if (sNanobotProcess == processToStop) {
                sNanobotProcess = null;
                sNanobotLastExitAtMs = System.currentTimeMillis();
                sNanobotLastExitCode = 0;
            }
        }

        FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
        releaseNanobotWakeAndWifiLockIfHeld();
        stopForegroundIfIdle();
    }

    private void stopNanobotByPidFileIfPresent() throws Exception {
        File pidFile = new File(NANOBOT_PID_PATH);
        if (!pidFile.exists()) {
            releaseNanobotWakeAndWifiLockIfHeld();
            stopForegroundIfIdle();
            return;
        }

        String pidRaw = readFile(pidFile).trim();
        if (pidRaw.isEmpty()) {
            FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
            releaseNanobotWakeAndWifiLockIfHeld();
            stopForegroundIfIdle();
            return;
        }

        // Prefer graceful TERM first, then force KILL if needed.
        String script =
            "set -eu\n" +
            "PID='" + pidRaw + "'\n" +
            "if kill -0 \"$PID\" 2>/dev/null; then\n" +
            "  kill \"$PID\" 2>/dev/null || true\n" +
            "  sleep 1\n" +
            "fi\n" +
            "if kill -0 \"$PID\" 2>/dev/null; then\n" +
            "  kill -9 \"$PID\" 2>/dev/null || true\n" +
            "fi\n";
        runTermuxShellCommand(script, false);

        FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
        synchronized (STATE_LOCK) {
            sNanobotProcess = null;
            sNanobotLastExitAtMs = System.currentTimeMillis();
            sNanobotLastExitCode = 0;
        }
        releaseNanobotWakeAndWifiLockIfHeld();
        stopForegroundIfIdle();
    }

    private void writeConfigAtomically(String content) throws Exception {
        File configFile = new File(GUEST_NANOBOT_CONFIG_PATH);
        File parent = configFile.getParentFile();
        if (parent == null) throw new IllegalStateException("Config parent directory is null.");
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create config directory: " + parent.getAbsolutePath());
        }

        File tmpFile = new File(parent, "config.json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }

        String moveScript =
            "set -eu\n" +
            "mv -f '" + tmpFile.getAbsolutePath() + "' '" + configFile.getAbsolutePath() + "'\n" +
            "chmod 600 '" + configFile.getAbsolutePath() + "'\n";
        runTermuxShellCommand(moveScript, true);
    }

    private JSONObject buildNanobotStatusJson(int tailLines) throws Exception {
        JSONObject out = new JSONObject();
        boolean running;
        long startedAtMs;
        int lastExitCode;
        long lastExitAtMs;

        synchronized (STATE_LOCK) {
            running = isNanobotRunningLocked();
            startedAtMs = sNanobotStartedAtMs;
            lastExitCode = sNanobotLastExitCode;
            lastExitAtMs = sNanobotLastExitAtMs;
        }

        ArrayDeque<String> lines = readTailLines(new File(NANOBOT_LOG_PATH), tailLines);

        boolean hasHeartbeat = false;
        int lastInfoIndex = -1;
        int lastErrorIndex = -1;
        String lastErrorLine = "";
        boolean connectedMarkerSeen = false;
        int feishuReconnectCount = 0;
        int feishuDnsResolveErrorCount = 0;
        int feishuPingTimeoutCount = 0;
        int feishuNoCloseFrameCount = 0;
        int feishuKeepaliveTimeoutCount = 0;
        String feishuLastDisconnectReason = "";
        boolean feishuDuplicateMessageDetected = false;
        String feishuDuplicateMessageSample = "";
        HashMap<String, Integer> feishuRecentMessageSeen = new HashMap<>();
        JSONArray logTail = new JSONArray();

        int idx = 0;
        for (String line : lines) {
            logTail.put(line);
            if (line.contains("nanobot.heartbeat.service")) hasHeartbeat = true;
            if (line.contains("[INFO]")) lastInfoIndex = idx;
            if (line.contains("[ERROR]") || line.contains("ConnectionResetError")
                || line.contains("timed out during opening handshake")
                || line.contains("Connection reset by peer")) {
                lastErrorIndex = idx;
                lastErrorLine = line;
            }
            if (line.contains("connected to wss://msg-frontier.feishu.cn/ws/v2")) connectedMarkerSeen = true;
            if (line.contains("trying to reconnect for the")) feishuReconnectCount++;
            if (line.contains("Failed to resolve 'open.feishu.cn'")) feishuDnsResolveErrorCount++;
            if (line.contains("ping_timeout")) {
                feishuPingTimeoutCount++;
                feishuLastDisconnectReason = "ping_timeout";
            }
            if (line.contains("no close frame received or sent")) {
                feishuNoCloseFrameCount++;
                feishuLastDisconnectReason = "no_close_frame";
            }
            if (line.contains("keepalive ping timeout")) {
                feishuKeepaliveTimeoutCount++;
                feishuLastDisconnectReason = "keepalive_ping_timeout";
            }
            if (line.contains("Processing message from feishu:")) {
                int messageBodyStart = line.lastIndexOf(": ");
                if (messageBodyStart > 0 && messageBodyStart + 2 < line.length()) {
                    String body = line.substring(messageBodyStart + 2).trim();
                    if (!body.isEmpty()) {
                        int count = feishuRecentMessageSeen.containsKey(body) ? feishuRecentMessageSeen.get(body) + 1 : 1;
                        feishuRecentMessageSeen.put(body, count);
                        if (count > 1) {
                            feishuDuplicateMessageDetected = true;
                            if (feishuDuplicateMessageSample.isEmpty()) {
                                feishuDuplicateMessageSample = body;
                            }
                        }
                    }
                }
            }
            idx++;
        }

        String health;
        String healthReason;
        if (!running) {
            health = "stopped";
            healthReason = "process is not running";
        } else if (hasHeartbeat) {
            health = "healthy";
            healthReason = "heartbeat logs present";
        } else if (lastInfoIndex >= 0 && (lastErrorIndex < 0 || lastErrorIndex < lastInfoIndex)) {
            health = "healthy";
            healthReason = "INFO logs present and no newer error line";
        } else if (lastErrorIndex >= 0) {
            health = "degraded";
            healthReason = "recent error line present";
        } else {
            health = "starting";
            healthReason = "running but no heartbeat/info log observed yet";
        }

        String feishuHealth;
        if (!running) {
            feishuHealth = "stopped";
        } else if (feishuDnsResolveErrorCount > 0) {
            feishuHealth = "dns_error";
        } else if (feishuPingTimeoutCount > 0 || feishuNoCloseFrameCount > 0 || feishuKeepaliveTimeoutCount > 0) {
            feishuHealth = "degraded";
        } else if (connectedMarkerSeen) {
            feishuHealth = "connected";
        } else {
            feishuHealth = "starting";
        }

        out.put("running", running);
        out.put("startedAtMs", startedAtMs);
        out.put("pidFilePath", NANOBOT_PID_PATH);
        out.put("logPath", NANOBOT_LOG_PATH);
        out.put("health", health);
        out.put("healthReason", healthReason);
        out.put("heartbeatSeen", hasHeartbeat);
        out.put("feishuConnectedMarkerSeen", connectedMarkerSeen);
        out.put("feishuHealth", feishuHealth);
        out.put("feishuReconnectCountRecent", feishuReconnectCount);
        out.put("feishuLastDisconnectReason", feishuLastDisconnectReason);
        out.put("feishuDnsResolveErrorCountRecent", feishuDnsResolveErrorCount);
        out.put("feishuPingTimeoutCountRecent", feishuPingTimeoutCount);
        out.put("feishuNoCloseFrameCountRecent", feishuNoCloseFrameCount);
        out.put("feishuKeepaliveTimeoutCountRecent", feishuKeepaliveTimeoutCount);
        out.put("feishuDuplicateMessageDetected", feishuDuplicateMessageDetected);
        out.put(
            "feishuDuplicateMessageSample",
            feishuDuplicateMessageSample.isEmpty() ? JSONObject.NULL : feishuDuplicateMessageSample
        );
        out.put("lastError", lastErrorLine);
        out.put("lastExitCode", lastExitCode == Integer.MIN_VALUE ? JSONObject.NULL : lastExitCode);
        out.put("lastExitAtMs", lastExitAtMs == 0L ? JSONObject.NULL : lastExitAtMs);
        out.put("logTail", logTail);
        return out;
    }

    private ArrayDeque<String> readTailLines(File file, int maxLines) throws Exception {
        ArrayDeque<String> deque = new ArrayDeque<String>();
        if (!file.exists()) return deque;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (deque.size() >= maxLines) deque.removeFirst();
                deque.addLast(line);
            }
        }
        return deque;
    }

    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private String runTermuxShellCommand(String command, boolean failOnNonZero) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh", "-c", command);
        Map<String, String> env = pb.environment();
        String originalPath = env.get("PATH");
        if (originalPath == null) originalPath = "";
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + originalPath);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int exit = process.waitFor();
        if (failOnNonZero && exit != 0) {
            throw new IllegalStateException("Shell command failed (" + exit + "): " + command + "\n" + output);
        }
        return output.toString();
    }

    private long calculateDirectorySize(File root) {
        if (root == null || !root.exists()) return 0L;
        if (root.isFile()) return root.length();
        long total = 0L;
        File[] children = root.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            total += calculateDirectorySize(child);
        }
        return total;
    }

    private long estimateCompressedBackupBytes(long sourceBytes) {
        if (sourceBytes <= 0) return 0L;
        // Conservative estimate for tar.gz in mixed text/json/log workloads.
        long estimated = (sourceBytes * 70L) / 100L;
        // Keep a small minimum envelope so tiny configs still show realistic non-zero.
        return Math.max(estimated, 16 * 1024L);
    }

    private String requireBackupFilename(Intent intent) {
        String filename = intent.getStringExtra(EXTRA_BACKUP_FILENAME);
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("Missing " + EXTRA_BACKUP_FILENAME);
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Invalid backup filename.");
        }
        return filename;
    }

    private File resolveBackupArchiveFile(String filename) {
        File archiveFile = new File(BACKUP_DIR_PATH, filename);
        if (!archiveFile.exists()) {
            throw new IllegalArgumentException("Backup file not found: " + archiveFile.getAbsolutePath());
        }
        return archiveFile;
    }

    /**
     * Pre-restore validation: file exists, supported archive type, and config.json is discoverable.
     * Safe to call without modifying rootfs (read-only checks on the backup archive).
     */
    private JSONObject validateBackupArchive(File archiveFile) throws Exception {
        JSONObject out = new JSONObject();
        String name = archiveFile.getName();
        out.put("filename", name);
        out.put("path", archiveFile.getAbsolutePath());
        out.put("sizeBytes", archiveFile.length());

        if (!archiveFile.isFile()) {
            out.put("valid", false);
            out.put("reason", "Backup file not found.");
            return out;
        }
        if (archiveFile.length() <= 0L) {
            out.put("valid", false);
            out.put("reason", "Backup file is empty.");
            return out;
        }

        String lowerName = name.toLowerCase(Locale.US);
        boolean supportedExt = lowerName.endsWith(".tar.gz")
            || lowerName.endsWith(".tgz")
            || lowerName.endsWith(".zip");
        if (!supportedExt) {
            out.put("valid", false);
            out.put("reason", "Unsupported format. Use .tar.gz or .zip.");
            return out;
        }

        String configRelPath = findConfigJsonRelativePathInArchive(archiveFile);
        if (configRelPath == null || configRelPath.isEmpty()) {
            out.put("valid", false);
            out.put("reason", "config.json not found in archive.");
            return out;
        }

        boolean isKnownNativePrefix = name.startsWith("800claw-backup-") || name.startsWith("nanobot-backup-");
        boolean isNativeLayout = configRelPath.startsWith("./root/.nanobot")
            || configRelPath.startsWith("root/.nanobot");
        String source = (isKnownNativePrefix || isNativeLayout) ? "native" : "external";

        out.put("valid", true);
        out.put("source", source);
        out.put("configPath", configRelPath);
        out.put("reason", JSONObject.NULL);
        return out;
    }

    /**
     * Check if a tar.gz or zip file contains a config.json anywhere in its tree.
     * Used to identify external nanobot backup archives without known prefixes.
     * Supports nested layouts such as outer.tar.gz → inner.tar → instance/data/config.json.
     */
    private boolean archiveContainsConfig(File archiveFile) {
        try {
            return findConfigJsonRelativePathInArchive(archiveFile) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Restore an external backup (flat or nested) by extracting to temp, unwrapping inner
     * .tar members, locating config.json on disk, and copying its parent directory (the
     * nanobot data folder) into GUEST_NANOBOT_DIR.
     */
    private String restoreExternalBackupArchive(File archiveFile, String filename) throws Exception {
        String tempExtractDir = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/claw800-restore-tmp";
        runTermuxShellCommand("rm -rf '" + tempExtractDir + "' && mkdir -p '" + tempExtractDir + "'", true);

        try {
            if (filename.endsWith(".zip")) {
                runTermuxShellCommand(
                    "unzip -o '" + archiveFile.getAbsolutePath() + "' -d '" + tempExtractDir + "'\n", true);
            } else {
                runTermuxShellCommand(
                    "tar xzf '" + archiveFile.getAbsolutePath() + "' -C '" + tempExtractDir + "'\n", true);
            }
            unwrapNestedTarArchives(tempExtractDir);

            String configPath = runTermuxShellCommand(
                "find '" + tempExtractDir + "' -name config.json -type f 2>/dev/null | head -1",
                false
            );
            if (configPath == null || configPath.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "Cannot restore backup: config.json not found after extraction."
                );
            }
            configPath = configPath.trim();

            // Parent of config.json is the nanobot data dir (cron, media, workspace, etc.).
            String dataDir = configPath.substring(0, configPath.length() - "/config.json".length());
            String copyScript =
                "set -eu\n" +
                "if [ -d '" + dataDir + "' ]; then\n" +
                "  cp -a '" + dataDir + "'/. '" + GUEST_NANOBOT_DIR + "'/\n" +
                "else\n" +
                "  exit 1\n" +
                "fi\n";
            return runTermuxShellCommand(copyScript, true);
        } finally {
            runTermuxShellCommand("rm -rf '" + tempExtractDir + "'", false);
        }
    }

    /**
     * Extract any nested .tar archives found under rootDir (e.g. clawbot-home-....tar inside .tar.gz).
     */
    private void unwrapNestedTarArchives(String rootDir) throws Exception {
        String script =
            "set -eu\n" +
            "find '" + rootDir + "' -maxdepth 4 -name '*.tar' -type f | while read -r t; do\n" +
            "  d=\"$(dirname \"$t\")\"\n" +
            "  tar xf \"$t\" -C \"$d\"\n" +
            "done\n";
        runTermuxShellCommand(script, false);
    }

    /**
     * Return a path to config.json inside the archive (relative to archive root), or null.
     * Handles flat archives and .tar.gz wrapping an inner .tar (clawbot-style backups).
     */
    private String findConfigJsonRelativePathInArchive(File archiveFile) throws Exception {
        String path = findConfigJsonPathInArchiveListing(archiveFile);
        if (path != null) return path;

        String name = archiveFile.getName().toLowerCase(Locale.US);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return findConfigJsonPathInNestedTarMember(archiveFile);
        }
        return null;
    }

    /**
     * Scan tar tzf / unzip -l output for config.json (single-level listing).
     */
    private String findConfigJsonPathInArchiveListing(File archiveFile) throws Exception {
        String listing;
        String name = archiveFile.getName();
        if (name.endsWith(".zip")) {
            listing = runTermuxShellCommand(
                "unzip -l '" + archiveFile.getAbsolutePath() + "'", false);
        } else {
            listing = runTermuxShellCommand(
                "tar tzf '" + archiveFile.getAbsolutePath() + "'", false);
        }
        if (listing == null) return null;
        return parseConfigJsonPathFromListing(listing, name.endsWith(".zip"));
    }

    /**
     * Some external backups are .tar.gz containing a single inner .tar; config.json lives
     * inside that inner archive. Stream the inner tar without writing to disk for detection.
     */
    private String findConfigJsonPathInNestedTarMember(File archiveFile) throws Exception {
        String outerListing = runTermuxShellCommand(
            "tar tzf '" + archiveFile.getAbsolutePath() + "'", false);
        if (outerListing == null) return null;

        String innerTarMember = null;
        for (String line : outerListing.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.endsWith(".tar") && !line.contains(" ")) {
                innerTarMember = line;
                break;
            }
        }
        if (innerTarMember == null) return null;

        String script =
            "tar xzf '" + archiveFile.getAbsolutePath() + "' -O '" + innerTarMember + "' 2>/dev/null " +
            "| tar tzf - 2>/dev/null | grep -m1 'config.json'";
        String output = runTermuxShellCommand(script, false);
        if (output == null || output.trim().isEmpty()) return null;

        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.contains("config.json")) {
                if (line.endsWith("/config.json") || line.equals("config.json")) {
                    return line.endsWith("/config.json") ? line : "config.json";
                }
            }
        }
        return null;
    }

    private String parseConfigJsonPathFromListing(String listing, boolean isZip) {
        for (String line : listing.split("\n")) {
            line = line.trim();
            if (line.startsWith("---") || line.startsWith("Archive") || line.isEmpty()) continue;

            String path;
            if (isZip) {
                String[] parts = line.split("\\s+");
                path = parts[parts.length - 1];
            } else {
                path = line;
            }

            if (path.endsWith("/config.json") || path.equals("config.json")) {
                return path;
            }
        }
        return null;
    }

    /**
     * Check if a Process is still alive. Uses Process.isAlive() on API 26+
     * and exitValue() probing on pre-O because the method was added in API 26.
     */
    private boolean isProcessAlive(Process p) {
        if (p == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.isAlive();
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Attempt to forcibly terminate a Process. On API 26+ uses
     * Process.destroyForcibly(); on pre-O falls back to the pidfile
     * stop mechanism (which uses kill -9) so we just call destroy() again.
     */
    private void destroyProcessForcibly(Process p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            p.destroyForcibly();
        } else {
            // Pre-O: no destroyForcibly(). destroy() was already called above;
            // stopNanobotByPidFileIfPresent() below handles leftovers via kill -9.
            p.destroy();
        }
    }

    private boolean isRootfsReady() {
        return new File(ROOTFS_SENTINEL_PATH).exists()
            && new File(ROOTFS_INSTALL_DIR_PATH, "bin").exists()
            && new File(ROOTFS_INSTALL_DIR_PATH, "usr").exists();
    }

    private boolean isNanobotRunning() {
        synchronized (STATE_LOCK) {
            return isNanobotRunningLocked();
        }
    }

    private boolean isNanobotRunningLocked() {
        return sNanobotProcess != null && isProcessAlive(sNanobotProcess);
    }

    private int sanitizeTailCount(int requested) {
        if (requested <= 0) return DEFAULT_TAIL_LINES;
        return Math.min(requested, MAX_TAIL_LINES);
    }

    private String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private String trimForJson(String raw, int maxChars) {
        if (raw == null) return "";
        if (raw.length() <= maxChars) return raw;
        return raw.substring(raw.length() - maxChars);
    }

    private void sendSuccessResult(Intent requestIntent, String action, JSONObject json) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(RESULT_OK, true);
        bundle.putString(RESULT_ACTION, action);
        bundle.putString(RESULT_JSON, json.toString());
        sendResultBundle(requestIntent, 0, bundle);
    }

    private void sendFailureResult(Intent requestIntent, String action, String errorMessage) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(RESULT_OK, false);
        bundle.putString(RESULT_ACTION, action);
        bundle.putString(RESULT_ERROR, errorMessage == null ? "Unknown error" : errorMessage);
        sendResultBundle(requestIntent, 1, bundle);
    }

    private void sendResultBundle(Intent requestIntent, int code, Bundle bundle) {
        Messenger messenger;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            messenger = requestIntent.getParcelableExtra(EXTRA_RESULT_MESSENGER, Messenger.class);
        } else {
            messenger = requestIntent.getParcelableExtra(EXTRA_RESULT_MESSENGER);
        }
        if (messenger == null) return;
        try {
            Message msg = Message.obtain();
            msg.what = code;
            msg.setData(bundle);
            messenger.send(msg);
        } catch (RemoteException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed sending Messenger callback", e);
        }
    }

    private int maybeStopSelf() {
        if (isRuntimeControlIdle()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForeground(true);
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void stopForegroundIfIdle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isRuntimeControlIdle()) {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stopLogStreamWorkerLocked();
        stopWeixinLoginStreamWorkerLocked();
        releaseNanobotWakeAndWifiLockIfHeld();
        super.onDestroy();
    }

    private boolean isLogStreamActive() {
        synchronized (STATE_LOCK) {
            return sLogStreamThread != null && sLogStreamThread.isAlive();
        }
    }

    private void stopLogStreamWorkerLocked() {
        Thread threadToStop;
        synchronized (STATE_LOCK) {
            threadToStop = sLogStreamThread;
            sLogStreamThread = null;
        }
        if (threadToStop != null) {
            threadToStop.interrupt();
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
            this,
            NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_LOW,
            NOTIFICATION_CHANNEL_NAME,
            "claw800 runtime - running",
            "claw800 runtime - running",
            null,
            null,
            NotificationUtils.NOTIFICATION_MODE_SILENT
        );
        if (builder == null) return new Notification();

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setContentTitle("claw800 runtime - running");
        builder.setContentText("nanobot gateway active");
        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationUtils.setupNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        );
    }
}
