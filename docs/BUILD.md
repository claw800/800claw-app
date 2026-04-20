# Building claw800 Runtime APK

Phase 0 baseline build instructions. Owner: claw800 dev team.
See `MIGRATION_PLAN_800CLAW.md` for the multi-phase plan this document supports.

---

## 1. Pinned toolchain

These versions are extracted verbatim from this repo. Keep this table in sync with
`build.gradle`, `gradle.properties`, and `gradle/wrapper/gradle-wrapper.properties`.

| Tool | Version | Source of truth |
|---|---|---|
| Android Gradle Plugin | **8.13.2** | `build.gradle:7` |
| Gradle wrapper | **9.2.1** | `gradle/wrapper/gradle-wrapper.properties:3` |
| Android NDK | **29.0.14206865** | `gradle.properties:20` |
| `compileSdkVersion` | **36** | `gradle.properties:21` |
| `targetSdkVersion` | **28** | `gradle.properties:19` (pinned low on purpose; see §1.1) |
| `minSdkVersion` | **21** | `gradle.properties:18` |
| Java source/target | **1.8** | `app/build.gradle:105-106` |
| Termux bootstrap variant | `apt-android-7` | `app/build.gradle:12` |
| Termux bootstrap version | `2026.02.12-r1+apt.android-7` | `app/build.gradle:222` |
| App versionCode | `118` | `app/build.gradle:46` |
| App versionName | `0.118.0` | `app/build.gradle:47` |

### 1.1 Why `targetSdkVersion = 28`?

Upstream Termux intentionally pins `targetSdk` at 28 to avoid Android 10+ scoped-storage
restrictions that would break `$PREFIX`. Do **not** raise this without a full
re-validation — it is a load-bearing setting for the `$HOME` / `$PREFIX` layout.

---

## 2. Local prerequisites

Install once per development machine.

### 2.1 JDK

- JDK 17 LTS (Temurin or Zulu). AGP 8.13 requires JDK 17. JDK 21 also works; JDK 11 does not.
- Verify: `java -version` and `javac -version` both report 17.x.
- Windows: ensure `JAVA_HOME` is set system-wide and `%JAVA_HOME%\bin` is on PATH.

### 2.2 Android SDK + NDK

- Android Studio Ladybug (2024.2) or newer — ships a compatible AGP plugin.
- Alternatively, the commandline SDK manager:
  ```powershell
  sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865" "platform-tools"
  ```
- Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK root.

### 2.3 Tooling sanity check

From repo root:

```powershell
./gradlew --version
```

Expected output lists Gradle 9.2.1, JVM 17, AGP 8.13.2.

---

## 3. ABI decisions (Phase 0)

Per migration plan §2.4, we ship **arm64-v8a** (primary) and **x86_64** (emulator-only) in
the PoC. `armeabi-v7a` and `x86` are dropped.

Enforced in two places in `app/build.gradle`:

```groovy
defaultConfig {
    ndk {
        abiFilters 'arm64-v8a', 'x86_64'
    }
    splits {
        abi {
            ...
            include 'arm64-v8a', 'x86_64'
        }
    }
}
```

Keep both lists in sync. If a list ever diverges from the other, the universal APK and
the split APKs will contain different arch sets, which is almost never what you want.

Note: the upstream `downloadBootstraps` task in `app/build.gradle:218-237` still fetches
all four Termux bootstrap zips at build time (~120 MB total). We leave it as-is in
Phase 0 because trimming it touches the auto-sync-with-upstream story. Phase 2 §6.1
will narrow the download list when we take over bootstrap management.

---

## 4. Signing keys

**Do not commit any `.jks`, `.keystore`, or `.p12` file to this repo.** `.gitignore`
already excludes them; check before pushing.

### 4.1 One-time keystore generation (run locally, keep offline)

One release key for the entire claw800 product. Same key signs `claw800-runtime.apk`
and `claw800.apk` (RN UI). Required for the Android signature-level IPC between the two
APKs in Phase 3.

```powershell
# Pick a secure password. Do not reuse dev-machine or SCM passwords.
# keytool will NOT auto-create the parent directory — create it first.
New-Item -ItemType Directory -Path keystore -Force | Out-Null

keytool -genkeypair `
  -alias claw800-release `
  -keyalg RSA `
  -keysize 4096 `
  -validity 9125 `
  -keystore keystore\claw800-release.jks `
  -storepass "REPLACE_ME" `
  -keypass "REPLACE_ME" `
  -dname "CN=claw800 runtime, O=claw800, C=US"


keytool -list -v -keystore keystore\claw800-release.jks -alias claw800-release


# Warning:
# The JKS keystore uses a proprietary format. It is recommended to migrate to PKCS12 which is an industry standard format using "keytool -importkeystore -srckeystore keystore\claw800-release.jks -destkeystore keystore\claw800-release.jks -deststoretype pkcs12".

```

- `validity 9125` = 25 years. Short-lived keys bite you years later when you need to ship
  an update and discover the key expired.
- The `-dname` values go into the cert; nothing in the build depends on them.
- Store `keystore\claw800-release.jks` somewhere outside the repo (1Password, an offline
  drive, or a secrets vault). **Losing this key means losing the ability to ship updates
  to any user who already installed the app.**

### 4.2 Wire the key into Gradle

Add to `%USERPROFILE%\.gradle\gradle.properties` (or the equivalent on CI secrets):

```properties
CLAW800_RELEASE_STORE_FILE=C:/absolute/path/to/keystore/claw800-release.jks
CLAW800_RELEASE_STORE_PASSWORD=REPLACE_ME
CLAW800_RELEASE_KEY_ALIAS=claw800-release
CLAW800_RELEASE_KEY_PASSWORD=REPLACE_ME
```

Then extend the `signingConfigs` block in `app/build.gradle` (not yet applied — will be
done alongside the first real release build):

```groovy
signingConfigs {
    debug {
        storeFile file('testkey_untrusted.jks')
        keyAlias 'alias'
        storePassword 'xrj45yWGLbsO7W0v'
        keyPassword 'xrj45yWGLbsO7W0v'
    }
    release {
        storeFile   file(findProperty('CLAW800_RELEASE_STORE_FILE') ?: 'unset')
        storePassword findProperty('CLAW800_RELEASE_STORE_PASSWORD') ?: 'unset'
        keyAlias    findProperty('CLAW800_RELEASE_KEY_ALIAS') ?: 'unset'
        keyPassword findProperty('CLAW800_RELEASE_KEY_PASSWORD') ?: 'unset'
    }
}

buildTypes {
    release {
        ...
        signingConfig signingConfigs.release
    }
}
```

Keep the upstream `debug` signing config intact — it signs debug builds with a
well-known untrusted key, which is fine for local install.

### 4.3 Key fingerprint for the record

Once generated, record the SHA-256 fingerprint in your password manager (not here):

```powershell
keytool -list -v -keystore keystore\claw800-release.jks -alias claw800-release
```

---

## 5. First build

### 5.1 Debug APK for local install

```powershell
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk`
(and `..._arm64-v8a.apk`, `..._x86_64.apk` if split builds are enabled by env var).

First build downloads the Termux bootstrap zips (~120 MB) and may take 10–20 minutes.
Subsequent builds are much faster (Gradle skips the download if checksums match).

### 5.2 Install on a physical device

```powershell
adb uninstall com.termux   # REQUIRED if any Termux variant is installed; see migration plan §2.1
adb install app\build\outputs\apk\debug\termux-app_apt-android-7-debug_universal.apk
```

### 5.3 Install on an emulator

```powershell
# One-time emulator creation (x86_64, API 33 recommended):
avdmanager create avd -n claw800 -k "system-images;android-33;google_apis;x86_64"
emulator -avd claw800
# Then:
adb install app\build\outputs\apk\debug\termux-app_apt-android-7-debug_universal.apk
```

### 5.4 Exit criteria (migration plan §4)

- [ ] `./gradlew assembleDebug` completes with `BUILD SUCCESSFUL`.
- [ ] APK installs on a physical arm64 device.
- [ ] APK installs on an x86_64 emulator.
- [ ] Launcher tap opens the Termux activity and the bootstrap self-installer runs.
- [ ] A `bash` prompt appears in `/data/data/com.termux/files/home`.

When all four are green, tag the baseline:

```powershell
git tag claw800-baseline-v0
git push origin claw800-baseline-v0
```

---

## 6. Common Phase 0 failure modes

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

Another app with `sharedUserId="com.termux"` is installed (Termux itself, or a plugin
like Termux:API, Termux:Boot, Termux:Widget, Termux:Tasker, Termux:Float, Termux:Styling).
Uninstall them all:

```powershell
adb shell pm list packages | findstr termux
# Then for each match:
adb uninstall <packagename>
```

### `Unable to download bootstrap-aarch64.zip: wrong checksum`

Upstream Termux re-cut the bootstrap release and the checksum in `app/build.gradle:222-226`
is stale. Options:
1. Pick up the new release by syncing `master` with `upstream/master` and re-running.
2. Manually update the four SHA-256 lines for the current `bootstrap-<ver>+apt.android-7`
   release at `https://github.com/termux/termux-packages/releases`.

Do **not** delete or comment out the checksum check — in Phase 2 we take over bootstrap
provenance and this is our last line of defense until then.

### App crashes on launch with `UnsatisfiedLinkError`

The ABI filter excluded the device's arch. Temporarily add the missing arch to
`ndk.abiFilters` to verify, then decide whether to expand the ship list (§3).

### `NDK not found at version X`

`sdkmanager "ndk;29.0.14206865"` to install, then verify `ANDROID_HOME\ndk\29.0.14206865\`
exists.

---

## 7. Keeping in sync with upstream Termux

Branch strategy:

- `master` — tracks our fork's shippable state. Only claw800 changes land here.
- `upstream-catchup` — periodic branch for merging `upstream/master`. PR into `master`.

```powershell
git fetch upstream
git checkout -b upstream-catchup-$(Get-Date -Format "yyyyMMdd") upstream/master
# Resolve any conflicts with claw800 changes, run assembleDebug, then:
gh pr create --base master --head upstream-catchup-...
```

Expected cadence: every 1–2 months. Termux is active but not fast-moving.

---

## 8. Pointers

- Full migration plan: `docs/MIGRATION_PLAN_800CLAW.md`.
- Upstream Termux wiki on building: <https://github.com/termux/termux-app#how-to-build>.
- Termux bootstrap releases: <https://github.com/termux/termux-packages/releases>.
