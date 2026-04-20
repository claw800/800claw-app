# Steps To Upgrade Fork To Upstream `v0.119.0-beta.3`

This document captures the exact workflow used to upgrade `claw800/termux-app-800claw` from upstream `v0.118.0` to `v0.119.0-beta.3`, while preserving claw800-specific deltas.

It is written as a reusable runbook for future upstream upgrades.

---

## 1) Pre-checks

Run all commands from repo root:

```powershell
cd D:\dev\cd\claw_dev\termux-app-800claw
git checkout master
git status
```

You need a clean working tree before the upgrade merge.

If you have local changes, commit or stash first.

### Commit path

```powershell
git add .
git commit -m "chore(claw800): phase0 baseline config and docs"
```

### Stash path (alternative)

```powershell
git stash push -u -m "wip-before-upstream-upgrade"
```

---

## 2) Fetch upstream and verify target tag

```powershell
git fetch upstream --tags
git tag -l "v0.119.0-beta.3"
git show --no-patch --oneline v0.119.0-beta.3
```

Expected:

- `git tag -l` prints `v0.119.0-beta.3`.
- `git show` prints tag commit info (in our run: `e634d8f9 ... Release: v0.119.0-beta.3`).

---

## 3) Create upgrade branch and merge upstream tag

```powershell
git checkout -b upgrade/upstream-v0.119.0-beta.3
git merge --no-ff v0.119.0-beta.3
```

Conflicts are normal. Do **not** panic-commit until every conflict marker is fully removed.

---

## 4) Resolve merge conflicts correctly

### 4.1 Find unresolved files

```powershell
git status
git diff --name-only --diff-filter=U
```

In our run, conflicts were in:

- `README.md`
- `app/build.gradle`
- `jitpack.yml`

### 4.2 Edit and keep intended values

For this specific upgrade, the intended post-merge state was:

- Keep upstream app version bump:
  - `versionCode 1022`
  - `versionName "0.119.0-beta.3"`
- Keep claw800 customizations:
  - ABI narrowing to `arm64-v8a`, `x86_64`
  - release signing config block
  - local docs and `.gitignore` keystore protections
- Keep valid CI JDK in `jitpack.yml`:
  - `openjdk17`
- Update `README.md` latest version line to:
  - ``Latest version is `v0.119.0-beta.3`.``

### 4.3 Stage and commit merge

```powershell
git add README.md app/build.gradle jitpack.yml
git commit
```

---

## 5) Critical safety check: no leftover conflict markers

Even if `git status` is clean, verify there are no raw markers committed:

```powershell
rg "^(<<<<<<<|=======|>>>>>>>)" -g "*.{gradle,md,yml,yaml,properties,java,kt}"
```

If you see matches, fix those files and recommit.

In our real run, leftover markers remained and caused:

- Gradle parse error: `Unexpected input: '{' @ line 22` in `app/build.gradle`

So this check is mandatory.

---

## 6) Build verification after merge

```powershell
./gradlew clean assembleDebug
```

Expected:

- Build completes successfully.
- Java 21 source/target 8 warnings are acceptable for now.

In our run, final result after cleanup was:

- `BUILD SUCCESSFUL`

---

## 7) Runtime install verification

```powershell
& $adb uninstall com.termux
& $adb install app\build\outputs\apk\debug\termux-app_apt-android-7-debug_universal.apk
```

Launch app and verify bootstrap + terminal prompt appear.

---

## 8) Push branch

```powershell
git push -u origin upgrade/upstream-v0.119.0-beta.3
```

Optional PR:

```powershell
gh pr create --base master --head upgrade/upstream-v0.119.0-beta.3 --title "Merge upstream v0.119.0-beta.3 into fork" --body "Sync with upstream tag v0.119.0-beta.3 and preserve claw800-specific deltas."
```

---

## 9) Quick rollback options

If merge is messy and you want to restart:

```powershell
git merge --abort
```

If you already committed but want to restart the branch:

```powershell
git checkout master
git branch -D upgrade/upstream-v0.119.0-beta.3
git checkout -b upgrade/upstream-v0.119.0-beta.3
```

---

## 10) Checklist (copy/paste for future upgrades)

- [ ] `git status` clean on `master`.
- [ ] `git fetch upstream --tags`.
- [ ] Tag exists and inspected.
- [ ] Upgrade branch created.
- [ ] Upstream tag merged.
- [ ] `git diff --name-only --diff-filter=U` empty.
- [ ] `rg "^(<<<<<<<|=======|>>>>>>>)"` returns no results.
- [ ] `./gradlew clean assembleDebug` passes.
- [ ] APK installs and launches.
- [ ] Branch pushed and PR opened.
