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

    // We extract the baked rootfs under proot-distro's canonical convention at
    //   $PREFIX/var/lib/proot-distro/installed-rootfs/<alias>
    // and register it via a plugin file at $PREFIX/etc/proot-distro/<alias>.sh.
    // This lets `proot-distro login claw800` handle the full proot invocation
    // (flags, binds, env, --kernel-release, --link2symlink state, etc.), which
    // is the combination proven to work on Android devices where a hand-rolled
    // proot invocation hits ENOSYS on chdir/getcwd against the same rootfs.
    private static final String PROOT_DISTRO_INSTALLED_ROOTFS_DIR =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs";
    private static final String PROOT_DISTRO_PLUGIN_DIR_PATH =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/proot-distro";
    private static final String ROOTFS_INSTALL_DIR_PATH =
        PROOT_DISTRO_INSTALLED_ROOTFS_DIR + "/" + ROOTFS_ALIAS;
    private static final String ROOTFS_PLUGIN_FILE_PATH =
        PROOT_DISTRO_PLUGIN_DIR_PATH + "/" + ROOTFS_ALIAS + ".sh";
    private static final String ROOTFS_SENTINEL_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/lib/claw800/rootfs-installed.stamp";
    private static final String ROOTFS_LOG_PATH =
        TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH + "/log/claw800-rootfs-bootstrap.log";
    private static final String ROOTFS_ASSET_STAGING_PATH =
        TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/claw800-rootfs.tar.xz";
    private static final String ROOTFS_ENTER_SCRIPT_PATH =
        TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/claw800-enter";

    private static volatile boolean sInstallRunning = false;

    static void setupRootfsIfNeeded(final Activity activity, final Runnable whenDone) {
        if (sInstallRunning) {
            Logger.logInfo(LOG_TAG, "Rootfs install already running; waiting for current attempt.");
            return;
        }

        if (isRootfsReady()) {
            try {
                ensureRootfsEnterScript();
                ensureAllowExternalAppsEnabled();
                disableNodeSourceAptRepo();
                switchAptMirrorsToAliyun();
                writeGuestResolvConf();
                writeGuestTimezoneDefault();
                writeGuestNpmRegistryMirror();
                writeGuestPipMirror();
            } catch (Exception e) {
                // Do not block app startup for existing installs; user can still proceed.
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh claw800-enter script", e);
                logToFile("warning: failed to refresh rootfs runtime hygiene policies: " + e.getMessage());
            }
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
                applyPostInstallFixups();
                disableNodeSourceAptRepo();
                switchAptMirrorsToAliyun();
                writeGuestResolvConf();
                writeGuestTimezoneDefault();
                writeGuestNpmRegistryMirror();
                writeGuestPipMirror();
                registerAndroidAids();
                writeProotDistroPlugin();
                writeSentinel();
                ensureRootfsEnterScript();
                ensureAllowExternalAppsEnabled();

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

    /**
     * Writes a thin wrapper script that delegates to `proot-distro login claw800`.
     *
     * Historical context: an earlier revision of this method generated a full
     * hand-rolled `proot ...` invocation inline. On real Android devices (Honor,
     * Xiaomi 12X, etc.) that invocation failed with ENOSYS on chdir/getcwd even
     * though the same devices happily ran `proot-distro login ubuntu` against a
     * freshly-downloaded Canonical rootfs. The delta turned out to be a mix of
     * (a) rootfs layout expectations (mount-point directory modes, presence of
     * a .l2s state directory, etc.) and (b) the exact proot flag set + env
     * proot-distro assembles. Rather than continue re-implementing that
     * assembly in Java and chasing upstream changes forever, we let
     * proot-distro do what it's known to do correctly and keep this wrapper
     * trivial.
     */
    private static void ensureRootfsEnterScript() throws Exception {
        Error error = FileUtils.createDirectoryFile(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (error != null) {
            throw new RuntimeException("Failed to create bin directory for claw800-enter: " + error.getMessage());
        }

        String script =
            "#!/data/data/com.termux/files/usr/bin/sh\n" +
            "# Thin wrapper around `proot-distro login " + ROOTFS_ALIAS + "`.\n" +
            "# The baked rootfs lives at $PREFIX/var/lib/proot-distro/installed-rootfs/" + ROOTFS_ALIAS + "\n" +
            "# and is registered via $PREFIX/etc/proot-distro/" + ROOTFS_ALIAS + ".sh.\n" +
            "# Both are provisioned by ClawRuntimeBootstrap on first app launch.\n" +
            "\n" +
            "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
            "  echo \"proot-distro is not installed. Run: pkg install proot proot-distro -y\" >&2\n" +
            "  exit 1\n" +
            "fi\n" +
            "\n" +
            "exec proot-distro login " + ROOTFS_ALIAS + " \"$@\"\n";

        File scriptFile = new File(ROOTFS_ENTER_SCRIPT_PATH);
        try (FileOutputStream fos = new FileOutputStream(scriptFile, false)) {
            fos.write(script.getBytes(StandardCharsets.UTF_8));
        }
        if (!scriptFile.setExecutable(true, true)) {
            throw new RuntimeException("Failed to mark claw800-enter as executable: " + ROOTFS_ENTER_SCRIPT_PATH);
        }

        logToFile("claw800-enter script ready at: " + ROOTFS_ENTER_SCRIPT_PATH);
    }

    /**
     * Normalizes the extracted rootfs so that proot-distro's login machinery
     * can use it without surprises.
     *
     * Two categories of fix, both required on real devices:
     *
     *  1. Rootfs root directory mode. Termux extracts tarballs with umask 077,
     *     so our install dir lands as 700 even though the tarball intends 755.
     *     proot-distro's own installs have 755 here; we normalize to match.
     *  2. Mount-point directory existence and modes. The baked tarball excludes
     *     /proc /sys /dev /tmp /run /mnt /media on purpose (they are pseudo-fs
     *     or populated by bind mounts at runtime), so they are absent after
     *     extraction. proot-distro's login binds host /proc /sys /dev over
     *     guest paths and expects mode 555 on /proc and /sys, 1777 on /tmp,
     *     and 755 on the rest. Without correct modes, proot path resolution
     *     silently fails and chdir/getcwd return ENOSYS inside the guest.
     *
     * We also create the .l2s directory that --link2symlink uses for state.
     */
    private static void applyPostInstallFixups() throws Exception {
        logToFile("applying post-install fixups to rootfs: " + ROOTFS_INSTALL_DIR_PATH);

        runShell("chmod 755 '" + ROOTFS_INSTALL_DIR_PATH + "'");

        String[] mountDirs = {"proc", "sys", "dev", "tmp", "run", "mnt", "media", ".l2s"};
        for (String d : mountDirs) {
            Error err = FileUtils.createDirectoryFile(ROOTFS_INSTALL_DIR_PATH + "/" + d);
            if (err != null) {
                throw new RuntimeException("Failed to create rootfs mount-point directory '" + d
                    + "': " + err.getMessage());
            }
        }

        runShell(
            "chmod 555 '" + ROOTFS_INSTALL_DIR_PATH + "/proc' '" + ROOTFS_INSTALL_DIR_PATH + "/sys' && " +
            "chmod 1777 '" + ROOTFS_INSTALL_DIR_PATH + "/tmp' && " +
            "chmod 755 '" + ROOTFS_INSTALL_DIR_PATH + "/dev' '" + ROOTFS_INSTALL_DIR_PATH + "/run' '" +
            ROOTFS_INSTALL_DIR_PATH + "/mnt' '" + ROOTFS_INSTALL_DIR_PATH + "/media'"
        );
    }

    /**
     * Replicates the Android UID/GID registration that {@code proot-distro
     * install} performs against a freshly-extracted rootfs. Since our install
     * flow bypasses that command (the tarball is shipped in APK assets and
     * extracted directly by {@link #extractStagedRootfs()}), the guest's
     * {@code /etc/group} never learns about Android-specific supplemental GIDs
     * that the Termux process carries. Without this step, every login prints
     * one or more warnings like:
     *
     * <pre>
     *   groups: cannot find name for group ID 3003
     *   groups: cannot find name for group ID 9997
     *   groups: cannot find name for group ID 20210
     *   groups: cannot find name for group ID 50210
     * </pre>
     *
     * This runs in the Termux-app context (via {@code runShell}) so
     * {@code id -Gn} / {@code id -G} enumerate the Android supplemental GIDs
     * inherited from the app process. We then append matching
     * {@code aid_&lt;name&gt;} entries into the guest's
     * {@code /etc/passwd}, {@code /etc/shadow}, {@code /etc/group},
     * {@code /etc/gshadow} exactly as upstream {@code proot-distro} does.
     * Idempotent: existing entries are detected and skipped.
     *
     * Upstream reference: function "Registering Android-specific UIDs and GIDs"
     * in termux/proot-distro's {@code proot-distro.sh}.
     */
    private static void registerAndroidAids() throws Exception {
        logToFile("registering Android-specific UIDs/GIDs into guest /etc/{passwd,shadow,group,gshadow}");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "\n" +
            "# Make the files writable even if the bake shipped them read-only.\n" +
            "chmod u+rw \"$ROOT/etc/passwd\" \"$ROOT/etc/shadow\" \"$ROOT/etc/group\" \"$ROOT/etc/gshadow\" 2>/dev/null || true\n" +
            "\n" +
            "AID_NAME=\"aid_$(id -un)\"\n" +
            "AID_UID=\"$(id -u)\"\n" +
            "AID_GID=\"$(id -g)\"\n" +
            "\n" +
            "append_if_missing() {\n" +
            "  key=\"$1\"; file=\"$2\"; line=\"$3\"\n" +
            "  [ -f \"$file\" ] || return 0\n" +
            "  grep -q \"^${key}:\" \"$file\" 2>/dev/null || printf '%s\\n' \"$line\" >> \"$file\"\n" +
            "}\n" +
            "\n" +
            "append_if_missing \"$AID_NAME\" \"$ROOT/etc/passwd\" \"$AID_NAME:x:$AID_UID:$AID_GID:Termux:/:/sbin/nologin\"\n" +
            "append_if_missing \"$AID_NAME\" \"$ROOT/etc/shadow\" \"$AID_NAME:*:18446:0:99999:7:::\"\n" +
            "\n" +
            "# Pair `id -Gn` (names) with `id -G` (numeric IDs) by line position.\n" +
            "# Using tmp files to stay POSIX (no bash process substitution).\n" +
            "gnames_file=\"$(mktemp)\"\n" +
            "gids_file=\"$(mktemp)\"\n" +
            "id -Gn | tr ' ' '\\n' > \"$gnames_file\"\n" +
            "id -G  | tr ' ' '\\n' > \"$gids_file\"\n" +
            "\n" +
            "paste -d ' ' \"$gnames_file\" \"$gids_file\" | while IFS=' ' read -r gn gid; do\n" +
            "  [ -n \"${gn:-}\" ] && [ -n \"${gid:-}\" ] || continue\n" +
            "  append_if_missing \"aid_${gn}\" \"$ROOT/etc/group\"   \"aid_${gn}:x:${gid}:root,${AID_NAME}\"\n" +
            "  append_if_missing \"aid_${gn}\" \"$ROOT/etc/gshadow\" \"aid_${gn}:*::root,${AID_NAME}\"\n" +
            "done\n" +
            "\n" +
            "rm -f \"$gnames_file\" \"$gids_file\"\n";

        runShell(script);
    }

    /**
     * Guest hygiene tweaks run as first-launch fixups rather than at rootfs
     * bake time.
     *
     * <p>Historically we applied these three tweaks inside
     * {@code tools/bootstrap/bake-ubuntu.Dockerfile} as a {@code POST_BAKE}
     * {@code RUN} block. That was clean for the apt-source edits but not
     * workable for {@code /etc/resolv.conf}: Docker BuildKit bind-mounts
     * {@code /etc/resolv.conf} (and {@code /etc/hostname}, {@code /etc/hosts})
     * into every {@code RUN} step so DNS works during the build, which means
     *
     * <ul>
     *   <li>{@code rm -f /etc/resolv.conf} fails with EBUSY ("Device or
     *       resource busy") because you cannot unlink a bind-mounted file,
     *       and</li>
     *   <li>even a direct {@code cat > /etc/resolv.conf} hits the bind, not
     *       the image layer, and is discarded at RUN boundary so the
     *       subsequent {@code tar} step never ships our content.</li>
     * </ul>
     *
     * <p>Moving the three tweaks here uniformly:
     *
     * <ul>
     *   <li>removes the BuildKit gotcha entirely (no binds on this side);</li>
     *   <li>lets us iterate on hygiene without a 1.5&nbsp;h rootfs rebuild
     *       and CI round-trip;</li>
     *   <li>keeps all first-launch rootfs mutations in one place next to the
     *       existing {@link #applyPostInstallFixups()} and
     *       {@link #registerAndroidAids()} methods; and</li>
     *   <li>makes the tweaks idempotent by design (they run on every
     *       install, so a re-extracted rootfs picks them up immediately).</li>
     * </ul>
     */

    /**
     * Disables the NodeSource apt source inside the rootfs by renaming the
     * sources file to {@code .bak}. Node 22 is already installed at bake
     * time; leaving the source active would force the guest to hit
     * NodeSource's repo churn (key rotations, suite renames) on every
     * {@code apt-get update}. Idempotent.
     */
    private static void disableNodeSourceAptRepo() throws Exception {
        logToFile("disabling NodeSource apt source in guest (rename to .bak)");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "src=\"$ROOT/etc/apt/sources.list.d/nodesource.sources\"\n" +
            "if [ -f \"$src\" ]; then\n" +
            "  mv \"$src\" \"$src.bak\"\n" +
            "fi\n";

        runShell(script);
    }

    /**
     * Swaps {@code archive.ubuntu.com}, {@code security.ubuntu.com}, and
     * {@code ports.ubuntu.com} to CN-friendly mirrors inside
     * {@code /etc/apt/sources.list.d/ubuntu.sources}.
     * Users in CN see a large {@code apt-get update} speed-up; users
     * elsewhere can still reach Aliyun at respectable speeds, or edit the
     * file themselves. The original file is preserved as {@code .bak}.
     * Idempotent.
     *
     * <p>Important: ARM64 rootfs entries usually come from
     * {@code ports.ubuntu.com/ubuntu-ports}. Some mirrors (including Aliyun)
     * may be incomplete for arm64 universe/multiverse indexes, causing 404
     * warnings. We therefore rewrite ports to TUNA's ubuntu-ports endpoint for
     * better package coverage on arm64.
     */
    private static void switchAptMirrorsToAliyun() throws Exception {
        logToFile("switching guest Ubuntu apt mirrors to Aliyun");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "src=\"$ROOT/etc/apt/sources.list.d/ubuntu.sources\"\n" +
            "if [ -f \"$src\" ]; then\n" +
            "  # Keep a one-time snapshot of the upstream sources for fallback.\n" +
            "  [ -f \"$src.bak\" ] || cp -f \"$src\" \"$src.bak\"\n" +
            "  sed -Ei \\\n" +
            "    -e 's|https?://archive\\.ubuntu\\.com/ubuntu/?|https://mirrors.aliyun.com/ubuntu/|g' \\\n" +
            "    -e 's|https?://security\\.ubuntu\\.com/ubuntu/?|https://mirrors.aliyun.com/ubuntu/|g' \\\n" +
            "    -e 's|https?://ports\\.ubuntu\\.com/ubuntu-ports/?|https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/|g' \\\n" +
            "    \"$src\"\n" +
            "fi\n";

        runShell(script);
    }

    /**
     * Writes {@code $ROOTFS/etc/resolv.conf} with the canonical claw800
     * nameservers (Aliyun public DNS + Google fallback).
     *
     * <p>Ubuntu Noble ships {@code /etc/resolv.conf} as a symlink to
     * {@code /run/systemd/resolve/stub-resolv.conf}. Under proot-distro
     * there is no systemd-resolved running, so that symlink is dangling and
     * {@code /etc/resolv.conf} is authoritative inside the guest. We
     * {@code rm -f} the symlink first so the subsequent write creates a
     * real regular file rather than following the dangling link.
     */
    private static void writeGuestResolvConf() throws Exception {
        logToFile("writing guest /etc/resolv.conf with claw800 nameservers");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "rm -f \"$ROOT/etc/resolv.conf\"\n" +
            "cat > \"$ROOT/etc/resolv.conf\" <<'RESOLV'\n" +
            "# Written by claw800 ClawRuntimeBootstrap at first-launch.\n" +
            "# proot-distro guests do not run systemd-resolved, so this file\n" +
            "# is authoritative. Edit freely inside the guest if needed.\n" +
            "nameserver 223.5.5.5\n" +
            "nameserver 223.6.6.6\n" +
            "nameserver 8.8.8.8\n" +
            "RESOLV\n" +
            "chmod 644 \"$ROOT/etc/resolv.conf\"\n";

        runShell(script);
    }

    /**
     * Sets guest timezone to Asia/Shanghai by default (UTC+8).
     * This runs during the same first-launch hygiene stage as apt/dns updates.
     */
    private static void writeGuestTimezoneDefault() throws Exception {
        logToFile("setting guest timezone default to Asia/Shanghai");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "ZONE='Asia/Shanghai'\n" +
            "ZONEINFO=\"$ROOT/usr/share/zoneinfo/$ZONE\"\n" +
            "if [ -f \"$ZONEINFO\" ]; then\n" +
            "  printf '%s\\n' \"$ZONE\" > \"$ROOT/etc/timezone\"\n" +
            "  rm -f \"$ROOT/etc/localtime\"\n" +
            "  ln -s \"/usr/share/zoneinfo/$ZONE\" \"$ROOT/etc/localtime\"\n" +
            "fi\n";

        runShell(script);
    }

    /**
     * Sets guest npm registry mirror for CN-friendly package install speed.
     *
     * <p>Writes {@code /root/.npmrc} as:
     * {@code registry=https://registry.npmmirror.com/}
     * Idempotent and safe to re-run on every startup hygiene pass.
     */
    private static void writeGuestNpmRegistryMirror() throws Exception {
        logToFile("setting guest npm registry mirror to npmmirror");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "f=\"$ROOT/root/.npmrc\"\n" +
            "mkdir -p \"$ROOT/root\"\n" +
            "if [ -f \"$f\" ] && grep -q '^registry=https://registry\\.npmmirror\\.com/?$' \"$f\" 2>/dev/null; then\n" +
            "  exit 0\n" +
            "fi\n" +
            "if [ -f \"$f\" ] && grep -q '^registry=' \"$f\" 2>/dev/null; then\n" +
            "  sed -Ei 's|^registry=.*|registry=https://registry.npmmirror.com/|' \"$f\"\n" +
            "else\n" +
            "  printf 'registry=https://registry.npmmirror.com/\\n' >> \"$f\"\n" +
            "fi\n";

        runShell(script);
    }

    /**
     * Sets guest pip mirror config for CN-friendly package install speed.
     *
     * <p>Writes {@code /root/.pip/pip.conf} as:
     * <pre>
     * [global]
     * index-url = https://mirrors.aliyun.com/pypi/simple/
     * trusted-host = mirrors.aliyun.com
     * </pre>
     * Idempotent and safe to re-run on every startup hygiene pass.
     */
    private static void writeGuestPipMirror() throws Exception {
        logToFile("setting guest pip mirror to Aliyun");

        String script =
            "set -eu\n" +
            "ROOT='" + ROOTFS_INSTALL_DIR_PATH + "'\n" +
            "d=\"$ROOT/root/.pip\"\n" +
            "f=\"$d/pip.conf\"\n" +
            "mkdir -p \"$d\"\n" +
            "cat > \"$f\" <<'PIPCONF'\n" +
            "[global]\n" +
            "index-url = https://mirrors.aliyun.com/pypi/simple/\n" +
            "trusted-host = mirrors.aliyun.com\n" +
            "PIPCONF\n" +
            "chmod 644 \"$f\"\n";

        runShell(script);
    }

    /**
     * Ensures RUN_COMMAND-style APIs are usable by the RN management app.
     *
     * <p>The setting lives in {@code ~/.termux/termux.properties} as
     * {@code allow-external-apps=true}. We patch it idempotently:
     * replace existing line if present, otherwise append a new one.
     */
    private static void ensureAllowExternalAppsEnabled() throws Exception {
        logToFile("ensuring termux.properties sets allow-external-apps=true");

        String propFile = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH;
        String script =
            "set -eu\n" +
            "f='" + propFile + "'\n" +
            "mkdir -p \"$(dirname \"$f\")\"\n" +
            "touch \"$f\"\n" +
            "if grep -q '^allow-external-apps=' \"$f\" 2>/dev/null; then\n" +
            "  sed -Ei 's/^allow-external-apps=.*/allow-external-apps=true/' \"$f\"\n" +
            "else\n" +
            "  printf '\\nallow-external-apps=true\\n' >> \"$f\"\n" +
            "fi\n";

        runShell(script);
    }

    /**
     * Drops a minimal proot-distro plugin file that registers our pre-baked
     * rootfs under the alias {@link #ROOTFS_ALIAS}. The TARBALL_URL/SHA256
     * entries are placeholders: proot-distro only uses them for its own
     * `install` path, which we bypass (the tarball is shipped in APK assets
     * and extracted by {@link #extractStagedRootfs()}). `login`, `list`,
     * `rename`, `backup`, and `remove` all work with the plugin as-written.
     */
    private static void writeProotDistroPlugin() throws Exception {
        Error error = FileUtils.createDirectoryFile(PROOT_DISTRO_PLUGIN_DIR_PATH);
        if (error != null) {
            throw new RuntimeException("Failed to create proot-distro plugin directory: " + error.getMessage());
        }

        String plugin =
            "# Auto-generated by ClawRuntimeBootstrap at first-run. Do not edit.\n" +
            "# Registers the pre-baked Ubuntu Noble rootfs shipped inside the claw800\n" +
            "# runtime APK as a proot-distro alias named '" + ROOTFS_ALIAS + "'.\n" +
            "# TARBALL_URL/SHA256 are placeholders: the rootfs is extracted from APK\n" +
            "# assets, not downloaded.\n" +
            "\n" +
            "DISTRO_NAME=\"Claw800 Ubuntu\"\n" +
            "DISTRO_COMMENT=\"Pre-baked Ubuntu Noble rootfs shipped with the claw800 runtime APK.\"\n" +
            "\n" +
            "TARBALL_URL['aarch64']=\"https://example.invalid/claw800/not-used\"\n" +
            "TARBALL_SHA256['aarch64']=\"0000000000000000000000000000000000000000000000000000000000000000\"\n" +
            "TARBALL_URL['x86_64']=\"https://example.invalid/claw800/not-used\"\n" +
            "TARBALL_SHA256['x86_64']=\"0000000000000000000000000000000000000000000000000000000000000000\"\n";

        File pluginFile = new File(ROOTFS_PLUGIN_FILE_PATH);
        try (FileOutputStream fos = new FileOutputStream(pluginFile, false)) {
            fos.write(plugin.getBytes(StandardCharsets.UTF_8));
        }

        logToFile("proot-distro plugin written to: " + ROOTFS_PLUGIN_FILE_PATH);
    }

    /**
     * Runs a short shell command via Termux's `sh -c`, capturing combined
     * stdout/stderr and raising on non-zero exit. Used for chmod-and-friends
     * where driving the operation through a Termux binary is more portable
     * than Java's narrow File permission API (no sticky/setuid support).
     */
    private static void runShell(String command) throws Exception {
        String shBinary = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
        ProcessBuilder pb = new ProcessBuilder(shBinary, "-c", command);
        Map<String, String> env = pb.environment();
        String originalPath = env.get("PATH");
        if (originalPath == null) originalPath = "";
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + originalPath);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Shell command failed (rc=" + exitCode + "): " + command
                + "\n" + output);
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
