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
import android.os.ResultReceiver;

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

    public static final String EXTRA_CALLER_PACKAGE = "dev.claw800.runtime.extra.CALLER_PACKAGE";
    public static final String EXTRA_RESULT_RECEIVER = "dev.claw800.runtime.extra.RESULT_RECEIVER";
    public static final String EXTRA_CONFIG_JSON = "dev.claw800.runtime.extra.CONFIG_JSON";
    public static final String EXTRA_LOG_LINES = "dev.claw800.runtime.extra.LOG_LINES";

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
    private static final String NANOBOT_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-nanobot-gateway.log";
    private static final String NANOBOT_PID_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/run/claw800-nanobot-gateway.pid";

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
        Arrays.asList("com.claw800.ui", "dev.claw800.ui")
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

        try {
            if (!ACTION_ENSURE_AUTOSTART.equals(action) && !isAllowedCaller(intent)) {
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
            } else if (ACTION_ENSURE_AUTOSTART.equals(action)) {
                handleEnsureAutostart(intent);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ownerService.startForegroundService(i);
        } else {
            ownerService.startService(i);
        }
    }

    public static void ensureNanobotAutostart(android.content.Context context) {
        Intent i = new Intent(context, ClawRuntimeControlService.class);
        i.setAction(ACTION_ENSURE_AUTOSTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
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
        out.put("rootfsReady", isRootfsReady());
        out.put("configExists", new File(GUEST_NANOBOT_CONFIG_PATH).exists());

        if (!isRootfsReady()) {
            out.put("started", false);
            out.put("reason", "rootfs not ready");
        } else if (!new File(GUEST_NANOBOT_CONFIG_PATH).exists()) {
            out.put("started", false);
            out.put("reason", "nanobot config missing");
        } else {
            JSONObject status = startNanobotProcessIfNeeded();
            out.put("started", true);
            out.put("nanobot", status);
        }
        sendSuccessResult(intent, ACTION_ENSURE_AUTOSTART, out);
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
        ResultReceiver receiver = requestIntent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        if (receiver == null) return;
        try {
            receiver.send(code, bundle);
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed sending ResultReceiver callback", e);
        }
    }

    private int maybeStopSelf() {
        if (!isNanobotRunning()) stopSelf();
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
