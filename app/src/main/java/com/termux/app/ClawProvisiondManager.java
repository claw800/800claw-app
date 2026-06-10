package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Ensures stable guest device identity and starts hub provisiond for remote initialization.
 */
final class ClawProvisiondManager {

    private static final String LOG_TAG = "ClawProvisiondManager";

    private static final String ROOTFS_ALIAS = "claw800";
    private static final String ROOTFS_INSTALL_DIR_PATH =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/" + ROOTFS_ALIAS;
    private static final String GUEST_CLAWBOT_DIR = ROOTFS_INSTALL_DIR_PATH + "/root/.clawbot";
    private static final String GUEST_DEVICE_ID_PATH = GUEST_CLAWBOT_DIR + "/device-id";
    private static final String GUEST_DEVICE_SECRET_PATH = GUEST_CLAWBOT_DIR + "/device-secret";
    private static final String HOST_PROVISIOND_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/claw800/provisiond.py";
    private static final String GUEST_PROVISIOND_PATH = GUEST_CLAWBOT_DIR + "/provisiond.py";
    private static final String PROVISIOND_PID_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/run/claw800-provisiond.pid";
    private static final String PROVISIOND_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-provisiond.log";

    private ClawProvisiondManager() {}

    static void ensureProvisiond(Context context, TermuxShellExecutor shellExecutor) {
        try {
            extractProvisiondAsset(context);
            ensureGuestDeviceIdentity(shellExecutor);
            startProvisiondIfNeeded(shellExecutor);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "provisiond ensure failed", e);
        }
    }

    private static void extractProvisiondAsset(Context context) throws Exception {
        File out = new File(HOST_PROVISIOND_PATH);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent.getAbsolutePath());
        }
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open("provisiond/provisiond.py");
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
        }
    }

    private static void ensureGuestDeviceIdentity(TermuxShellExecutor shellExecutor) throws Exception {
        String script =
            "set -eu\n" +
            "mkdir -p " + q(GUEST_CLAWBOT_DIR) + "\n" +
            "cp -f " + q(HOST_PROVISIOND_PATH) + " " + q(GUEST_PROVISIOND_PATH) + "\n" +
            "if [ ! -s " + q(GUEST_DEVICE_ID_PATH) + " ]; then\n" +
            "  proot-distro login " + ROOTFS_ALIAS + " -- bash -lc 'python3 - <<\"PY\"\n" +
            "import uuid, pathlib\n" +
            "path = pathlib.Path(\"/root/.clawbot/device-id\")\n" +
            "path.parent.mkdir(parents=True, exist_ok=True)\n" +
            "path.write_text(str(uuid.uuid4()), encoding=\"utf-8\")\n" +
            "PY'\n" +
            "fi\n" +
            "if [ ! -s " + q(GUEST_DEVICE_SECRET_PATH) + " ] && [ -n \"${CLAWBOT_DEVICE_SECRET:-}\" ]; then\n" +
            "  printf '%s' \"$CLAWBOT_DEVICE_SECRET\" > " + q(GUEST_DEVICE_SECRET_PATH) + "\n" +
            "fi\n";
        shellExecutor.run(script, false);
    }

    private static void startProvisiondIfNeeded(TermuxShellExecutor shellExecutor) throws Exception {
        String script =
            "set -eu\n" +
            "if [ -f " + q(PROVISIOND_PID_PATH) + " ] && kill -0 \"$(cat " + q(PROVISIOND_PID_PATH) + ")\" 2>/dev/null; then\n" +
            "  echo provisiond_already_running\n" +
            "  exit 0\n" +
            "fi\n" +
            "hub_url=\"${HUB_BASE_URL:-http://127.0.0.1:8060}\"\n" +
            "nohup proot-distro login " + ROOTFS_ALIAS + " -- bash -lc 'source /opt/venv/bin/activate && pip install -q websocket-client && python3 /root/.clawbot/provisiond.py --hub '\"$hub_url\"' --product 800claw-android --device-id-path /root/.clawbot/device-id --device-secret-path /root/.clawbot/device-secret' >>" +
            q(PROVISIOND_LOG_PATH) + " 2>&1 &\n" +
            "echo $! > " + q(PROVISIOND_PID_PATH) + "\n" +
            "echo provisiond_started\n";
        shellExecutor.run(script, false);
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    interface TermuxShellExecutor {
        String run(String script, boolean allowFailure) throws Exception;
    }
}
