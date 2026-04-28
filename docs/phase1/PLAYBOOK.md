# Phase 1 Playbook (Online Bootstrap PoC)

Goal: prove that `800claw-app` can run Ubuntu via `proot-distro`, install required toolchains/libraries, and launch nanobot manually.

This phase is intentionally online/networked. Offline bundling is Phase 2.

---

## 0) Preconditions

- Runtime APK installed and launches on target device.
- Device has stable internet.
- Target device is arm64 (preferred for PoC).
- Existing Termux/plugin apps uninstalled (sharedUserId/signature conflicts avoided).

---

## 1) Host-side setup (Windows PowerShell)

Set your adb executable once:

```powershell
$adb = "D:\dev\android\sdk\platform-tools\adb.exe"
& $adb devices
```

Expected: at least one `device` line (physical phone or emulator).

Optional cleanup if multiple Termux variants exist:

```powershell
& $adb shell pm list packages | findstr termux
```

---

## 2) Start an evidence log inside Termux

Open Termux app on device and run:

```bash
mkdir -p ~/phase1
export PHASE1_LOG=~/phase1/session-$(date +%Y%m%d-%H%M%S).log
script -q -f "$PHASE1_LOG"




# via adb ------------------------------------------------------------
& $adb -s emulator-5554 shell input text "mkdir -p ~/phase1"
& $adb -s emulator-5554 shell input keyevent 66   # KEYCODE_ENTER
& $adb -s emulator-5554 shell input text "export PHASE1_LOG=~/phase1/session-$(date +%Y%m%d-%H%M%S).log"
& $adb -s emulator-5554 shell input keyevent 66   # KEYCODE_ENTER
& $adb -s emulator-5554 shell input text "script -q -f "$PHASE1_LOG""
& $adb -s emulator-5554 shell input keyevent 66   # KEYCODE_ENTER


```







From this point onward, all terminal output is recorded until you run `exit` twice (once for shell, once for `script`).

---

## 3) Baseline checks in Termux

```bash
uname -a
pkg --version
termux-info | sed -n '1,80p'
```

Record these values in the log; they are useful if Phase 1 fails later.

---

## 4) Install proot-distro and inspect available distros

```bash
pkg update -y
pkg install -y proot-distro
proot-distro --version
proot-distro list
```

Pass criteria:

- `pkg install -y proot-distro` exits with code 0.
- `proot-distro list` shows `ubuntu`.

---

## 5) Install Ubuntu and enter it

```bash
proot-distro install ubuntu
proot-distro login ubuntu
```

Inside Ubuntu shell:

```bash
cat /etc/os-release
uname -m
python3 --version || true
```

Pass criteria:

- Install completes without seccomp/SIGSYS crashes.
- Ubuntu shell prompt opens.

---

## 6) Install required packages in Ubuntu

Still inside Ubuntu:

```bash
export DEBIAN_FRONTEND=noninteractive
apt update
apt install -y nodejs gcc g++ make python3.12 python3.12-venv python3-pip
```

If `python3.12` package is unavailable on your chosen Ubuntu image, fallback:

```bash
apt install -y python3 python3-venv python3-pip
```



```bash

# we install node v22.* manually ----------------------------------------------
curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
apt-get install -y nodejs

# verify Node.js 
node --version && npm --version



apt install -y python3.13-venv
# create python venv -----------------------
python3 -m venv /opt/venv
PATH="/opt/venv/bin:$PATH"


mkdir -p /root/.cache/pip
--mount=type=cache,target=/root/.cache/pip \
pip install --upgrade pip


pip install "pdf2docx==0.5.12"
pip install "markitdown[all]==0.1.5"
pip install "nanobot-ai==0.1.4.post5"
pip install "browser-use==0.12.2"

```



Verify versions:

```bash
node --version || true
gcc --version | head -n 1
python3.12 --version || python3 --version
pip3 --version
```

Pass criteria:

- All installs complete.
- Node, GCC, Python, and pip commands are usable.

---

## 7) Install Python libraries (minimum PoC set)

Inside Ubuntu:

```bash
pip3 install --upgrade pip setuptools wheel
pip3 install markitdown pdf2docx
```

Smoke tests:

```bash
python3 - <<'PY'
import markitdown
print("markitdown: OK")
PY

python3 - <<'PY'
import pdf2docx
print("pdf2docx: OK")
PY
```

If compilation/dependency issues occur, install common build deps and retry:

```bash
apt install -y build-essential python3-dev libffi-dev libxml2-dev libxslt1-dev zlib1g-dev libjpeg-dev
pip3 install markitdown pdf2docx
```

---

## 8) Nanobot manual launch check

Inside Ubuntu, run your nanobot bootstrap/start commands (team-specific). Template:

```bash
# Example only: replace with your actual repo and launch command.
cd /path/to/nanobot
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m nanobot.main
```

Pass criteria:

- Nanobot process reaches expected "ready" state.
- No immediate crash due to libc/seccomp/proot incompatibility.

If it fails, capture full traceback in log and proceed to evidence export.

---

## 9) Exit and export evidence

Inside Termux:

```bash
# Exit Ubuntu shell if still inside it
exit

# End `script` recording (if still active)
exit

ls -lh ~/phase1/
cp ~/phase1/session-*.log /sdcard/Download/
```

Host-side pull:

```powershell
& $adb pull /sdcard/Download/session-YYYYMMDD-HHMMSS.log docs/phase1/
```

Rename file to include device type, for example:

- `docs/phase1/session-20260420-arm64-pixel8.log`
- `docs/phase1/session-20260420-x86_64-emulator.log`

---

## 10) Phase 1 exit checklist

- [ ] `pkg install -y proot-distro` succeeded.
- [ ] `proot-distro install ubuntu` succeeded.
- [ ] Ubuntu shell launched via `proot-distro login ubuntu`.
- [ ] `apt install nodejs gcc python3.12 python3.12-venv python3-pip` succeeded (or documented fallback used).
- [ ] `pip3 install markitdown pdf2docx` succeeded.
- [ ] Python import smoke tests passed.
- [ ] Nanobot manual start reached ready state (or failure is captured clearly).
- [ ] Session log exported to `docs/phase1/`.

---

## 11) Known non-blockers for Phase 1

- `browser-use` / Playwright / Chromium may fail at this stage. Document and defer.
- Java 21 source/target 8 compile warnings in runtime APK build are expected and non-blocking.

