# claw800 Runtime Migration Plan

Status: **DRAFT v1** — phased, PoC-first.
Target repo: `claw800/termux-app-800claw` (this fork).
Companion repo: `claw800/800claw` (existing React Native UI, will be slimmed).
Last reviewed: 2026-04-08.

---

## 1. Scope and non-goals

### In scope
- Forking `termux/termux-app` into a headless **runtime APK** that ships with a pre-baked Ubuntu rootfs and all nanobot prerequisites (Python 3.12+, Node, GCC, plus `markitdown`, `pdf2docx`, `browser-use`).
- Keeping the React Native app (`claw800.apk`, from the existing `800claw` repo) as the **UI APK** that talks to the runtime over Android Intents.
- Offline first-launch: zero network calls required to provision the environment on a fresh device.
- Manual APK sideloading for end users (no Play Store).
- Bringing `nanobot` up under the bundled Ubuntu.

### Explicitly out of scope for the PoC
- Google Play / F-Droid distribution.
- Hiding the Termux launcher icon (deferred to Phase 4; see §9).
- Rebuilding `termux-packages` with a custom `$PREFIX` path (deferred indefinitely; see §2.1).
- `browser-use` + Chromium runtime validation (functional requirement, but sandboxing / `--no-sandbox --disable-dev-shm-usage` tuning is deferred until a later phase).
- iOS.

---

## 2. Locked decisions

These were confirmed by the product owner on 2026-04-08.

### 2.1 Package name strategy: **Option A**
Keep `applicationId = "com.termux"`. Sign with claw800's own key. Users **must uninstall any existing Termux** before installing `claw800-runtime.apk` due to signing-key mismatch. This is acceptable for manual-sideload PoC distribution.

Rationale: every binary in Termux's bootstrap zip (`bash`, `apt`, `proot`, `python`, …) has `/data/data/com.termux/files/usr/…` hardcoded in its ELF interpreter and rpath. Renaming the package requires rebuilding the entire `termux-packages` matrix with a new `$PREFIX` path, which we are explicitly deferring.

This decision is revisitable when we move to public distribution.

### 2.2 App identity
- Display name: **"Termux"** (no rebrand in PoC).
- Launcher icon: Termux default.
- App label change deferred to Phase 4.

### 2.3 Distribution
- End user installs two APKs manually from the claw800 website:
  1. `claw800-runtime-<ver>.apk` — this fork, ~800 MB–1.5 GB (contains Ubuntu rootfs).
  2. `claw800-<ver>.apk` — RN UI, 5–20 MB.
- Install order matters: runtime first, UI second.

### 2.4 Target ABIs
Primary: `arm64-v8a` (modern Android devices).
Secondary: `x86_64` (emulator dev).
**Dropped for PoC**: `armeabi-v7a`, `x86`.

Gradle consequence: set `ndk { abiFilters 'arm64-v8a', 'x86_64' }` in `app/build.gradle` to cut APK size roughly in half.

---

## 3. Repo and workspace layout

Two repos, one workspace.

```
D:\dev\cd\claw_dev\
├── 800claw\                    # RN UI (slimmed in Phase 3)
│   ├── mobile\                 # React Native source
│   ├── android\                # Android shell for RN UI only (stripped of proot/rootfs work)
│   └── docs\
└── termux-app-800claw\         # Runtime APK (this fork)
    ├── app\                    # Android app module (Termux core)
    ├── termux-shared\          # Shared Kotlin/Java lib
    ├── terminal-emulator\
    ├── terminal-view\
    ├── tools\
    │   └── bootstrap\          # NEW — CI pipeline for pre-baked bootstrap + Ubuntu rootfs
    └── docs\
        └── MIGRATION_PLAN_800CLAW.md   # this file
```

Upstream tracking for the runtime fork:

```bash
git remote add upstream https://github.com/termux/termux-app.git
git fetch upstream
# Periodically: git merge upstream/master into a `upstream-catchup` branch, then PR.
```

We do **not** fork `termux-packages` or `proot-distro` in the PoC. We consume their release artifacts as CI inputs.

---

## 4. Phase 0 — Baseline build (1 day)

**Goal**: prove the fork builds, signs, and installs with no code changes. Flush out NDK / Gradle / signing-key issues before any real work.

### Exit criteria
- [ ] `./gradlew assembleDebug` succeeds on a dev laptop with Android SDK + NDK installed.
- [ ] APK installs on a physical arm64 Android device and an x86_64 emulator.
- [ ] Launching the app from the launcher icon drops into a working `bash` prompt in `/data/data/com.termux/files/home`.
- [ ] Bootstrap (`$PREFIX`) self-installs on first launch (still fetched from `packages.termux.dev`; that's fine for Phase 0).

### Tasks
1. Generate a long-lived release signing key for claw800 (`keystore/claw800-release.jks`, **do not commit**). Record fingerprint in `docs/SIGNING.md` (not in this plan). This key MUST also be used for the RN UI APK when Phase 3 lands — Android's signature-matching permission system relies on both APKs sharing a signer.
2. Set up `~/.gradle/gradle.properties` or CI secrets with `CLAW800_KEYSTORE_*` envs; do **not** put the keystore or its password in the repo.
3. Cut `abiFilters` to `arm64-v8a, x86_64` in `app/build.gradle` under `defaultConfig.ndk` (add block if absent). This keeps the APK manageable.
4. Add a top-level `docs/BUILD.md` with exact NDK / CMake / JDK versions (Termux is picky; upstream pins them in `app/build.gradle` and `gradle.properties` already — just document what you used).
5. Confirm `versionCode = 118` / `versionName = "0.118.0"` at `app/build.gradle:46-47`. Leave as upstream values during Phase 0; Phase 3 will renumber.
6. Tag the working baseline: `git tag claw800-baseline-v0 && git push --tags`.

### Files touched in this phase
- `app/build.gradle` — `abiFilters` only.
- `docs/BUILD.md` — new.
- `docs/SIGNING.md` — new, local reference.

No Java/Kotlin edits in Phase 0. If anything requires code changes to make it build, stop and document it; don't paper over.

### Debug checklist if it won't install
- `adb uninstall com.termux` first (to remove any real Termux on the device).
- If "INSTALL_FAILED_UPDATE_INCOMPATIBLE", another app with `sharedUserId="com.termux"` (e.g. Termux:API) is present. Uninstall it too.
- If the app installs but immediately crashes with `UnsatisfiedLinkError`, the ABI filter dropped the arch your device needs; re-add it.

---

## 5. Phase 1 — Online bootstrap PoC (2–3 days)

**Goal**: prove end-to-end that the fork, when combined with `proot-distro`, can install Ubuntu, install every nanobot prereq via `apt` + `pip`, and run nanobot interactively. This is a **throwaway proof** — we still download the bootstrap and the Ubuntu rootfs from the network. No offline-first yet, no RN integration yet.

### Exit criteria
- [ ] From the on-device terminal: `pkg install -y proot-distro` succeeds.
- [ ] `proot-distro install ubuntu` succeeds.
- [ ] Inside `proot-distro login ubuntu`: `apt update && apt install -y nodejs gcc python3.12 python3.12-venv python3-pip` succeeds.
- [ ] `pip install markitdown pdf2docx` succeeds (no wheel-build failures).
- [ ] A minimal `python3.12 -c "import markitdown; print('ok')"` prints `ok`.
- [ ] nanobot can be started manually inside the Ubuntu guest and reaches its serving state. (Exact command set TBD by the nanobot team; PoC gate is "can be started," not "is production-quality.")

### Tasks
1. **Do not edit the fork** for this phase beyond what Phase 0 already did. The whole point is to verify upstream Termux + `proot-distro` solves the kernel-level problems we've been fighting. If it doesn't, we abort and re-scope before Phase 2.
2. Install `claw800-runtime.apk` from Phase 0 on the target arm64 device.
3. Run the on-device playbook documented in `docs/phase1/PLAYBOOK.md` (to be written in this phase — a literal list of commands with expected output). Record the full session with `script` or `screen -L` and commit it to the repo as `docs/phase1/session-<date>.log`.
4. Time each step. These timings are the inputs to the Phase 2 CI build — whatever `apt install` and `pip install` do here is what the CI will bake into the offline image.

### Risks and contingencies
- **`pip install browser-use` fails**: defer it. Phase 1 exit criterion for `browser-use` is "noted as failing," not "working."
- **Playwright / Chromium fails to run**: deferred to a later phase. Do not spend time on it in Phase 1.
- **proot-distro Ubuntu tarball is wrong arch or version**: `proot-distro` supports pinning (`proot-distro install ubuntu --override-alias noble`). Use the tag Canonical guarantees has Python 3.12 (Ubuntu 24.04 "noble").
- **Device seccomp kill during `apt install`**: if this happens, we have a real problem — it means Termux's `proot` does *not* actually solve our seccomp issue on this device, and we need to fall back to the UserLAnd-based approach. Probability: very low (hundreds of thousands of Termux users). If it happens, file a ticket, attach `strace`, and escalate before Phase 2.

### Files touched
- `docs/phase1/PLAYBOOK.md` — new.
- `docs/phase1/session-<date>.log` — recorded session, committed as evidence.

---

## 6. Phase 2 — Offline bootstrap (5–7 days, biggest chunk)

**Goal**: bake everything Phase 1 did at runtime into the APK assets so that a fresh device with airplane mode on can install `claw800-runtime.apk` and reach the same end-state as Phase 1.

This phase has the most engineering density. Sequenced so that each sub-step is independently verifiable.

### 6.1 — Sub-step A: bundle Termux's own bootstrap zip

Upstream Termux downloads `bootstrap-<arch>.zip` from `packages.termux.dev` on first launch. Replace that with a local asset.

- Add a CI workflow `.github/workflows/build-bootstrap.yml` that:
  1. Downloads the latest `bootstrap-aarch64.zip` and `bootstrap-x86_64.zip` from `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/` (actual URL verified at Phase 2 start — termux-packages ships bootstraps at a known path).
  2. Pins the exact upstream release by SHA256 into `tools/bootstrap/BOOTSTRAP_VERSIONS.txt`. Bumps require a PR.
  3. Places them in `app/src/main/assets/bootstrap/`.
- Edit `app/src/main/java/com/termux/app/TermuxInstaller.java`:
  - `setupBootstrapIfNeeded()` currently loads the zip from a generated shared library (`libtermux-bootstrap-*.so`) via `BootstrapInstaller.getZipBytes()`. Replace that call with a direct `context.getAssets().open("bootstrap/bootstrap-<abi>.zip")` read.
  - The rest of `TermuxInstaller` (symlink setup from `SYMLINKS.txt`, permission fixups) stays untouched.
- Drop the `packaging.ndkLibrariesFilter`-style bootstrap `.so` generation if present in `app/build.gradle`; it is no longer needed.
- Exit criterion: install APK on an emulator with network **disabled**; bootstrap completes; terminal reaches a prompt.

### 6.2 — Sub-step B: pre-bake the Ubuntu rootfs

- Add `tools/bootstrap/bake-ubuntu.Dockerfile` + `tools/bootstrap/bake-ubuntu.sh`. Recipe:
  ```
  FROM ubuntu:24.04 (--platform=linux/arm64 for the arm64 build, linux/amd64 for x86_64)
  apt-get update
  apt-get install -y --no-install-recommends \
      sudo ca-certificates curl nodejs gcc g++ make \
      python3.12 python3.12-venv python3-pip \
      dropbear xz-utils tar
  python3.12 -m pip install --no-cache-dir markitdown pdf2docx
  # browser-use intentionally deferred.
  tar -C / --exclude=proc --exclude=sys --exclude=dev \
      --exclude=run --exclude=tmp --exclude=mnt \
      -cJf /output/ubuntu-noble-<arch>.tar.xz .
  ```
- Verify the resulting tarball is what `proot-distro` expects. `proot-distro`'s `install` command consumes a tar.xz with a well-known layout; reuse their convention so the runtime-side install code (§6.3) can be minimal.
- Expected artifact size: ~600–900 MB compressed per arch. We accept it.
- Split artifacts for CI artifact-store size limits using `split -b 90m`, concatenate at APK-assemble time. See `UserLAnd-Assets-Debian/.circleci/config.yml` for a working reference.
- Add `.github/workflows/build-ubuntu-rootfs.yml` that runs this Dockerfile on `ubuntu-latest` using QEMU (`docker/setup-qemu-action`) for the arm64 build.
- The rootfs tarballs get placed in `app/src/main/assets/rootfs/`. **Important**: APKs have per-asset-file size limits in practice; split >100 MB files into `.part00`, `.part01` chunks. Reassemble at first launch.

### 6.3 — Sub-step C: extract the Ubuntu rootfs on first launch

Two options. Pick one:

- **Option C1 — "Call proot-distro once":** after bootstrap completes (§6.1), run `proot-distro install ubuntu --override-alias claw800 --from-file /path/to/ubuntu-noble-<arch>.tar.xz` from a first-run hook. Simplest; `proot-distro` does all the work.
- **Option C2 — "Hand-rolled extract":** write our own shell script in `$PREFIX/etc/termux/bootstrap/` that reassembles `ubuntu-noble-<arch>.tar.xz.part*`, extracts it to `$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/`, and writes `proot-distro`'s sentinel files.

**Recommendation: Option C1.** Much less code we own. First-launch hook location TBD — probably a one-shot line appended to `~/.bashrc` by Phase 2 code, then self-deletes on success.

- Add `app/src/main/java/com/termux/app/ClawRuntimeBootstrap.java` — a new post-bootstrap hook called from `TermuxActivity.onCreate()` after `TermuxInstaller.setupBootstrapIfNeeded`. The hook:
  1. Checks for a sentinel `$PREFIX/var/.claw800_rootfs_installed`.
  2. If absent, executes `proot-distro install ubuntu --override-alias claw800 --from-file <path>` via the same mechanism `TermuxInstaller` uses to run bootstrap scripts.
  3. Writes the sentinel on success.
- Exit criterion: airplane-mode fresh install → launcher tap → bootstrap extracts → Ubuntu rootfs extracts → `proot-distro login claw800 -- python3.12 -c "import markitdown; print('ok')"` prints `ok`.

### 6.4 — Asset compression

APKs apply zstd / deflate to assets by default, which silently decompresses at install time and doubles disk use, plus it recompresses our already-compressed tarballs for no gain. Add to `app/build.gradle`:

```groovy
androidResources {
    noCompress 'xz', 'tar', 'part00', 'part01', 'part02', 'part03'
}
```

Verify with `unzip -l app-release.apk | grep ubuntu` — the `Method` column must read `Stored`, not `Defl:N`.

### 6.5 — Size budget

Hard caps for the PoC:
- `claw800-runtime.apk` total size: ≤ 1.6 GB (Android 14 sideload maximum tested comfortably; some OEMs reject >2 GB APKs).
- If we blow that budget: split into a **split APK** (`base.apk` + `config.arm64_v8a.apk`) via `splits.abi`. Each arch gets its own APK, installer picks one. This is the Play-Store idiom but works with sideloading too via `adb install-multiple`.

### Files touched in Phase 2
- `app/build.gradle` — `noCompress`, possibly `splits.abi`.
- `app/src/main/java/com/termux/app/TermuxInstaller.java` — bootstrap source swap.
- `app/src/main/java/com/termux/app/ClawRuntimeBootstrap.java` — new.
- `app/src/main/java/com/termux/app/TermuxActivity.java` — one extra line in `onCreate()`.
- `app/src/main/assets/bootstrap/` — new.
- `app/src/main/assets/rootfs/` — new.
- `tools/bootstrap/*` — new.
- `.github/workflows/build-bootstrap.yml` — new.
- `.github/workflows/build-ubuntu-rootfs.yml` — new.

---

## 7. Phase 3 — RN UI Intent surface (3–4 days)

**Goal**: wire `claw800.apk` (the RN UI) to `claw800-runtime.apk` such that the UI can (a) probe runtime readiness, (b) send commands for nanobot to execute, (c) receive structured responses.

### 7.1 — Use Termux's existing `RunCommandService`

Already declared in `app/src/main/AndroidManifest.xml:200-207`:

```200:207:app/src/main/AndroidManifest.xml
        <service
            android:name=".app.RunCommandService"
            android:exported="true"
            android:permission="${TERMUX_PACKAGE_NAME}.permission.RUN_COMMAND">
            <intent-filter>
                <action android:name="${TERMUX_PACKAGE_NAME}.RUN_COMMAND" />
            </intent-filter>
        </service>
```

This is exactly the IPC surface we want. Key extras supported by `RunCommandService`: `RUN_COMMAND_PATH`, `RUN_COMMAND_ARGUMENTS`, `RUN_COMMAND_WORKDIR`, `RUN_COMMAND_BACKGROUND`, `RUN_COMMAND_SESSION_ACTION`, plus a `PendingIntent` for result delivery.

### 7.2 — Runtime-side changes

1. Flip `app/src/main/res/xml/com.termux.app.properties` (or wherever `allow-external-apps` lives — one of the files found by grep under `termux-shared/.../TermuxPropertyConstants.java`) to `true`. Without this, `RunCommandService` rejects all external intents.
2. Declare a claw800-specific action alongside `RUN_COMMAND`: `dev.claw800.runtime.EXEC`. Keep `RUN_COMMAND` too for debugging from adb.
3. Lock the permission name to `com.termux.permission.RUN_COMMAND` (because §2.1 keeps `com.termux` as `applicationId`). The RN UI APK must `<uses-permission>` it.
4. Harden: reject intents whose `callingPackage` is not `com.claw800.ui`, `com.claw800.runtime`, `com.claw800.app`, `dev.claw800.ui`, `dev.claw800.runtime`, `dev.claw800.app` (the RN UI APK's applicationId). Add a whitelist check in `RunCommandService.onStartCommand()`.

### 7.3 — RN UI side changes (in `800claw` repo)

1. Delete the old rootfs / proot / launcher-launcher code under `800claw/android/app/src/main/java/dev/claw800/app/` — specifically `BootstrapManager.kt`, `EncryptedConfigStore.kt` (move to RN UI side if still needed), `Claw800BridgeModule.kt` (gut and replace), and the entire `android/runtime-launcher/` folder.
2. Delete `android/app/src/main/jniLibs/` — no native libs shipped from the UI APK anymore.
3. Delete `runtime/bootstrap/`, `runtime/config/`, `tools/runtime/` — they move into the runtime APK repo's `tools/bootstrap/` (Phase 2).
4. Replace `Claw800BridgeModule.kt` with a thin `RuntimeBridgeModule.kt` that:
   - `isRuntimeInstalled(): Boolean` — probes for `com.termux` via `PackageManager.getPackageInfo()`.
   - `exec(cmd, args, workdir): Promise<{stdout, stderr, exitCode}>` — constructs the `dev.claw800.runtime.EXEC` intent, sends it, waits for the result via a `PendingIntent`.
   - `getRuntimeStatus(): Promise<{version, rootfsReady}>` — uses a lightweight `exec` of a status script.
5. Change `AndroidManifest.xml` (UI APK) to declare `<uses-permission android:name="com.termux.permission.RUN_COMMAND" />`.

### 7.4 — Exit criteria
- [ ] `claw800.apk` installs, detects runtime, sends `echo hello` via the bridge, receives `hello` as stdout.
- [ ] `claw800.apk` triggers nanobot start inside the Ubuntu rootfs and receives a ready signal within an acceptable timeout.
- [ ] Uninstalling `claw800-runtime.apk` makes the UI gracefully show "Runtime not installed" instead of crashing.

### Files touched in Phase 3
- Runtime repo: `app/src/main/java/com/termux/app/RunCommandService.java`, `app/src/main/AndroidManifest.xml`, properties file.
- UI repo: `android/app/src/main/java/.../RuntimeBridgeModule.kt` (new), `android/app/src/main/AndroidManifest.xml`, plus large deletes.

---

## 8. Phase 3.5 — Clean up the `800claw` repo (0.5 day, can overlap with Phase 3)

Literal deletion list. Everything listed here exists in `800claw/` today as artifacts of the old embedded-proot approach and is obsoleted by the runtime split.

```
android/app/src/main/jniLibs/**
android/runtime-launcher/**
android/app/src/main/java/dev/claw800/app/BootstrapManager.kt
android/app/src/main/java/dev/claw800/app/Claw800BridgeModule.kt   # replaced, not removed
android/app/src/main/java/dev/claw800/app/RuntimePaths.kt          # most fields obsolete
runtime/bootstrap/**
runtime/config/**
tools/runtime/**
docs/RUNTIME_CHECKPOINTS.md
docs/Ubuntu_VM_prerequisites_proot.md
docs/VALIDATION.md   # rewrite against the new architecture
```

Commit message template: `chore(ui): remove embedded-proot runtime, split into claw800-runtime APK`.

---

## 9. Phase 4 — Hide the launcher + rebrand (deferred, 1 day)

Do not do this until Phase 3 is green and PoC has been demoed at least once end-to-end. Reasoning in the prior conversation: the visible terminal is our most valuable debug tool.

Changes:
1. `app/src/main/AndroidManifest.xml`: remove the `<intent-filter>` block containing `MAIN` + `LAUNCHER` from the `TermuxActivity` declaration (lines 61-65 today). Optionally delete the `HomeActivity` activity-alias too (lines 77-89).
2. Rename the app: `manifestPlaceholders.TERMUX_APP_NAME = "claw800 runtime"` in `app/build.gradle:55`.
3. Replace `app/src/main/res/mipmap-*/ic_launcher*` with claw800 icon set.
4. Leave `TermuxActivity` in place, just not launcher-reachable. Devs can still launch it with `adb shell am start -n com.termux/.app.TermuxActivity` for debug.

Exit criterion: only one icon visible in the launcher (the RN UI), runtime invisible but `adb`-reachable.

---

## 10. Decision log

Kept short. Every decision that changes the architecture goes here with a one-liner.

| Date | Decision | Rationale |
|---|---|---|
| 2026-04-08 | Fork `termux/termux-app` as the runtime base | Inherits years of Android-seccomp / proot patches that we had been reimplementing badly in 800claw. |
| 2026-04-08 | Option A package name (keep `com.termux`) | Avoids `termux-packages` full rebuild; accepts "uninstall Termux first" UX trade-off. |
| 2026-04-08 | Ship two APKs, runtime + UI, signed with the same key | Required by Android for `signature`-level IPC surface between the two; also gives us a legal firewall (runtime GPLv3, UI proprietary). |
| 2026-04-08 | arm64-v8a + x86_64 only | Halves APK size; armv7 devices are out of target. |
| 2026-04-08 | Defer `browser-use` validation | Chromium sandboxing under proot is a separate multi-week problem; not on PoC critical path. |

---

## 11. Open questions to close before Phase 2

- Exact nanobot install command set (treat as a black box in Phase 1; bake in Phase 2).
- Exact Ubuntu-noble apt package set (start from `nodejs gcc python3.12 python3.12-venv python3-pip` and grow from Phase 1 evidence).
- CI runner: GitHub Actions `ubuntu-latest` sufficient, or do we need our own hardware for the arm64 tarball build? (`docker buildx` + qemu is slow but works; a single build is ~25 minutes.)
- Storage permissions: does the runtime need `MANAGE_EXTERNAL_STORAGE`? Upstream Termux requests it; we should keep it for Phase 1 and consider removing in Phase 4 if nanobot doesn't need it.

---

## 12. Timeline estimate

Assuming one engineer, no nanobot blockers:

| Phase | Effort | Cumulative |
|---|---|---|
| 0 — Baseline | 1 day | 1 d |
| 1 — Online PoC | 2–3 days | 3–4 d |
| 2 — Offline bootstrap | 5–7 days | 8–11 d |
| 3 — RN Intent surface | 3–4 days | 11–15 d |
| 3.5 — 800claw cleanup | 0.5 day | 11.5–15.5 d |
| 4 — Launcher hide | 1 day | 12.5–16.5 d (deferred) |

End of PoC: **~3 working weeks** to a state where we can demo a fresh-device, airplane-mode install reaching "nanobot running under Ubuntu, controllable from the RN UI."
