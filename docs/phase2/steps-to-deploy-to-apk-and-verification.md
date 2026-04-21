# Phase 2: Deploy Rootfs to APK and Verify on Device

This runbook starts after Ubuntu rootfs artifacts are rebuilt and copied to `tools/bootstrap/output`.

## 1) Verify rootfs artifacts before staging

From repo root (`termux-app-800claw`):

```powershell
.\tools\bootstrap\verify_rootfs_artifacts.ps1 -ExtractArtifacts
```

Expected:
- `Integrity OK` for `arm64-v8a` and `x86_64`
- top-level entries listed for each tarball
- summary table with `SizeMB` and `SHA256`

Notes:
- Use `-ExtractArtifacts` when you placed GitHub artifact zip files in `tools/bootstrap/output`.
- If you already extracted files under `tools/bootstrap/output/rootfs/<arch>/`, run without `-ExtractArtifacts`.

## 2) Stage tarballs and metadata into APK assets

```powershell
.\tools\bootstrap\stage_rootfs_assets.ps1

.\tools\bootstrap\stage_rootfs_assets.ps1 -CleanDest

.\tools\bootstrap\stage_rootfs_assets.ps1 -Arch arm64-v8a -CleanDest
```

Expected destination:
- `app/src/main/assets/rootfs/ubuntu-noble-arm64-v8a.tar.xz`
- `app/src/main/assets/rootfs/ubuntu-noble-x86_64.tar.xz`
- `app/src/main/assets/rootfs/metadata/arm64-v8a/*`
- `app/src/main/assets/rootfs/metadata/x86_64/*`

Optional checks:

```powershell
Get-ChildItem .\app\src\main\assets\rootfs -Force
Get-ChildItem .\app\src\main\assets\rootfs\metadata\arm64-v8a -Force
Get-ChildItem .\app\src\main\assets\rootfs\metadata\x86_64 -Force
```

## 3) Build debug APK

```powershell
.\gradlew clean assembleDebug
```

Expected APK:
- `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk`

## 4) Reinstall app cleanly on emulator/device

```powershell
& $adb uninstall com.termux
& $adb install app\build\outputs\apk\debug\termux-app_apt-android-7-debug_universal.apk
```

Then launch Termux and wait for first-run bootstrap.

## 5) Validate first-launch rootfs bootstrap

Collect logs, sentinel status, and proot-distro registration state:

```powershell
& $adb shell run-as com.termux cat /data/data/com.termux/files/usr/var/log/claw800-rootfs-bootstrap.log
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/var/lib/claw800
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/claw800
& $adb shell run-as com.termux cat /data/data/com.termux/files/usr/etc/proot-distro/claw800.sh
```

Success criteria:
- rootfs setup log reaches `rootfs setup completed successfully`
- `rootfs-installed.stamp` exists under `.../var/lib/claw800`
- `.../var/lib/proot-distro/installed-rootfs/claw800` exists, contains `bin/`, `usr/`, etc., and its top-level mode is `755`
- `.../var/lib/proot-distro/installed-rootfs/claw800/tmp` is mode `1777`; `/proc` and `/sys` are mode `555`; `.l2s/` directory exists
- `.../etc/proot-distro/claw800.sh` exists with `DISTRO_NAME="Claw800 Ubuntu"`
- bootstrap log contains `registering Android-specific UIDs/GIDs ...` and the guest's `/etc/group` has `aid_*` lines for the Termux supplemental GIDs (see step 6 note)

Rootfs hygiene (baked-in, confirm after first login):
- `cat /etc/resolv.conf` → lists `223.5.5.5`, `223.6.6.6`, `8.8.8.8`
- `ls /etc/apt/sources.list.d/` → `ubuntu.sources`, `ubuntu.sources.bak`, `nodesource.sources.bak` (note the `.bak` on NodeSource)
- `grep -c aliyun /etc/apt/sources.list.d/ubuntu.sources` → non-zero
- `grep ^aid_ /etc/group | wc -l` → non-zero

## 6) Enter Ubuntu rootfs

Two equivalent entrypoints, pick whichever is convenient:

```bash
# Recommended: the claw800-enter wrapper (auto-installed by ClawRuntimeBootstrap)
claw800-enter

# Or drive proot-distro directly:
proot-distro login claw800
```

Prerequisites:
- `proot` and `proot-distro` packages must be installed in Termux. If the custom
  bootstrap shipped in the APK does not include them, install manually once:

  ```bash
  pkg install proot proot-distro -y
  ```

Success check inside the guest:

```bash
cd /tmp && pwd            # -> /tmp
cd /root && pwd           # -> /root
python3 --version          # -> Python 3.12.x
node --version             # -> v22.x
nanobot --version          # -> v0.1.x.postN
id                         # -> should NOT emit "groups: cannot find name for group ID ..."
```

Note on the AID warnings: `proot-distro install` seeds the guest's
`/etc/passwd,shadow,group,gshadow` with `aid_*` entries for each Android
supplemental GID the Termux process carries. Because our flow bypasses
`install` (the tarball is shipped in APK assets), `ClawRuntimeBootstrap`
replicates that step at first-run via the same `id -Gn` / `id -G` pairing.
If you ever see the warnings come back (e.g. after sideloading the APK into
a different Android profile that exposes new supplemental GIDs), reinstall
the APK to re-run the fixup.

Historical note (root cause that drove the current design):
An earlier iteration of `claw800-enter` assembled a hand-rolled `proot ...`
invocation with a minimal flag set. On real devices (Xiaomi 12X, Honor Play 20,
etc.), that invocation consistently failed with `Function not implemented` on
`chdir`/`getcwd` inside the guest, even though `execve` and shared-library
loading worked (so `python3 --version` and `node --version` succeeded but
`cd /tmp` failed). The failure was a combination of (a) mount-point directory
modes on the extracted rootfs and (b) the specific proot flag set
`proot-distro` assembles, including `--link2symlink` state in `.l2s/`,
`--kernel-release` spoof, and the full bind list. Rather than keep re-matching
that combination in-house, `ClawRuntimeBootstrap` now delegates to
`proot-distro login`, which is maintained in lockstep with the Termux `proot`
package. `claw800-enter` is now a one-line wrapper around that call.

## 7) Optional deep validation inside installed rootfs

```powershell
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/claw800/usr/bin | findstr "python3 node perl"
```

This confirms key binaries exist in the installed rootfs.

## 8) If bootstrap still fails

Capture and share these outputs for triage:

```powershell
& $adb shell run-as com.termux cat /data/data/com.termux/files/usr/var/log/claw800-rootfs-bootstrap.log
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/var/lib/claw800
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs
& $adb shell run-as com.termux ls -lah /data/data/com.termux/files/usr/etc/proot-distro
& $adb shell run-as com.termux stat /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/claw800 /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/claw800/tmp /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/claw800/proc 2>&1
```

If `cd /tmp` or similar fails inside the guest, the most likely diagnostic is
the mode on `<rootfs>/tmp` (should be `1777`) and `<rootfs>/proc` / `<rootfs>/sys`
(should be `555`). `ClawRuntimeBootstrap.applyPostInstallFixups()` enforces
these at install time; if they drifted (e.g. after manual tampering), reinstall
the APK to re-run the fixup, or chmod them back manually.

For emulator tests, continue launching with a clean state to avoid stale
app-data side effects:

```powershell
& $emu -avd Pixel_API35 -no-snapshot-load
```
