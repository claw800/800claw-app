package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.file.FileUtils;
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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public static final String ACTION_CONFIG_READ = "dev.claw800.runtime.CONFIG_READ";
    public static final String ACTION_CONFIG_WRITE = "dev.claw800.runtime.CONFIG_WRITE";
    public static final String ACTION_LOG_TAIL = "dev.claw800.runtime.LOG_TAIL";
    public static final String ACTION_ENSURE_AUTOSTART = "dev.claw800.runtime.ENSURE_AUTOSTART";
    public static final String ACTION_BACKUP_CREATE = "dev.claw800.runtime.BACKUP_CREATE";
    public static final String ACTION_BACKUP_LIST = "dev.claw800.runtime.BACKUP_LIST";
    public static final String ACTION_BACKUP_RESTORE = "dev.claw800.runtime.BACKUP_RESTORE";

    public static final String EXTRA_CALLER_PACKAGE = "dev.claw800.runtime.extra.CALLER_PACKAGE";
    public static final String EXTRA_RESULT_MESSENGER = "dev.claw800.runtime.extra.RESULT_MESSENGER";
    public static final String EXTRA_CONFIG_JSON = "dev.claw800.runtime.extra.CONFIG_JSON";
    public static final String EXTRA_LOG_LINES = "dev.claw800.runtime.extra.LOG_LINES";
    public static final String EXTRA_BACKUP_FILENAME = "dev.claw800.runtime.extra.BACKUP_FILENAME";

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

    // Shared external storage for backups (Option B — survives both APK uninstalls).
    private static final String BACKUP_DIR_PATH =
        "/storage/emulated/0/Documents/800claw/backups";

    private static final String NOTIFICATION_CHANNEL_ID = "claw800_runtime_control_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "claw800 runtime";
    private static final int NOTIFICATION_ID = 8341;

    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;

    private static final Object STATE_LOCK = new Object();
    private static Process sNanobotProcess;
    private static long sNanobotStartedAtMs;
    private static int sNanobotLastExitCode = Integer.MIN_VALUE;
    private static long sNanobotLastExitAtMs = 0L;
    private static String sNanobotLastError = "";

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
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (action == null || action.isEmpty()) return START_NOT_STICKY;

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
            } else if (ACTION_CONFIG_READ.equals(action)) {
                handleConfigRead(intent);
            } else if (ACTION_CONFIG_WRITE.equals(action)) {
                handleConfigWrite(intent);
            } else if (ACTION_LOG_TAIL.equals(action)) {
                handleLogTail(intent);
            } else if (ACTION_BACKUP_CREATE.equals(action)) {
                handleBackupCreate(intent);
            } else if (ACTION_BACKUP_LIST.equals(action)) {
                handleBackupList(intent);
            } else if (ACTION_BACKUP_RESTORE.equals(action)) {
                handleBackupRestore(intent);
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
        out.put("nanobot", buildNanobotStatusJson(DEFAULT_TAIL_LINES));
        sendSuccessResult(intent, ACTION_RUNTIME_STATUS, out);
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
        } else if (!configExists) {
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

        File backupDir = new File(BACKUP_DIR_PATH);
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IllegalStateException("Cannot create backup directory: " + BACKUP_DIR_PATH);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String filename = "nanobot-backup-" + timestamp + ".tar.gz";
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

    private void handleBackupList(Intent intent) throws Exception {
        File backupDir = new File(BACKUP_DIR_PATH);
        JSONArray list = new JSONArray();

        if (backupDir.exists() && backupDir.isDirectory()) {
            File[] files = backupDir.listFiles();
            if (files != null) {
                // Sort by name descending so newest appears first.
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                for (File f : files) {
                    if (f.isFile() && f.getName().startsWith("nanobot-backup-") && f.getName().endsWith(".tar.gz")) {
                        JSONObject entry = new JSONObject();
                        entry.put("filename", f.getName());
                        entry.put("path", f.getAbsolutePath());
                        entry.put("sizeBytes", f.length());
                        entry.put("lastModified", f.lastModified());
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

    private void handleBackupRestore(Intent intent) throws Exception {
        String filename = intent.getStringExtra(EXTRA_BACKUP_FILENAME);
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("Missing " + EXTRA_BACKUP_FILENAME);
        }

        File archiveFile = new File(BACKUP_DIR_PATH, filename);
        if (!archiveFile.exists()) {
            throw new IllegalArgumentException("Backup file not found: " + archiveFile.getAbsolutePath());
        }

        if (!isRootfsReady()) {
            throw new IllegalStateException("Cannot restore backup: rootfs is not ready.");
        }

        // Step 1: clear the existing /root/.nanobot in the rootfs.
        String clearScript =
            "set -eu\n" +
            "rm -rf '" + GUEST_NANOBOT_DIR + "'\n" +
            "mkdir -p '" + GUEST_NANOBOT_DIR + "'\n";
        runTermuxShellCommand(clearScript, true);

        // Step 2: extract backup archive into the rootfs.
        // The archive contains ./root/.nanobot/... so we extract into ROOTFS_INSTALL_DIR_PATH.
        String restoreScript =
            "set -eu\n" +
            "tar xzf '" + archiveFile.getAbsolutePath() + "' " +
            "  -C '" + ROOTFS_INSTALL_DIR_PATH + "' " +
            "  './root/.nanobot'\n";
        String output = runTermuxShellCommand(restoreScript, true);

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

        // Add a clear restart boundary for operators reading tails in RN.
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            String marker = "\n=== " + nowIso() + " claw800 runtime starting nanobot gateway ===\n";
            fos.write(marker.getBytes(StandardCharsets.UTF_8));
        }

        String script =
            "set -eu\n" +
            "mkdir -p '" + new File(NANOBOT_PID_PATH).getParent() + "'\n" +
            "printf '%s\\n' $$ > '" + NANOBOT_PID_PATH + "'\n" +
            "exec proot-distro login " + ROOTFS_ALIAS + " -- bash -lc 'source /opt/venv/bin/activate && exec nanobot gateway'\n";

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

        startForegroundIfNeeded();
        watchNanobotExit(started);
        return buildNanobotStatusJson(DEFAULT_TAIL_LINES);
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

        if (processToStop == null || !processToStop.isAlive()) {
            synchronized (STATE_LOCK) {
                sNanobotProcess = null;
            }
            stopForegroundIfIdle();
            FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
            return;
        }

        processToStop.destroy();
        try {
            processToStop.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            processToStop.destroyForcibly();
        }

        synchronized (STATE_LOCK) {
            if (sNanobotProcess == processToStop) {
                sNanobotProcess = null;
                sNanobotLastExitAtMs = System.currentTimeMillis();
                sNanobotLastExitCode = 0;
            }
        }

        FileUtils.deleteFile("nanobot pid file", NANOBOT_PID_PATH, true);
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

        out.put("running", running);
        out.put("startedAtMs", startedAtMs);
        out.put("pidFilePath", NANOBOT_PID_PATH);
        out.put("logPath", NANOBOT_LOG_PATH);
        out.put("health", health);
        out.put("healthReason", healthReason);
        out.put("heartbeatSeen", hasHeartbeat);
        out.put("feishuConnectedMarkerSeen", connectedMarkerSeen);
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
        return sNanobotProcess != null && sNanobotProcess.isAlive();
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
        if (!isNanobotRunning()) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isNanobotRunning()) {
            stopForeground(true);
            stopSelf();
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
