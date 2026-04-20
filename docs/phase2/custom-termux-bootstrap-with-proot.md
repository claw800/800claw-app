# Phase 2: Custom Termux Bootstrap with `proot` pre-bundled

## Why

On first launch our runtime APK extracts a pre-baked Ubuntu rootfs into
`~/.proot-distro/installed-rootfs/claw800` and then needs `proot` to enter
it. The stock Termux bootstrap zip does not include `proot`, so the
current workaround is:

```bash
pkg install proot proot-distro -y
```

That requires network on the device at first launch, which violates our
"offline first-launch" requirement. This doc describes how we build a
custom Termux bootstrap zip that already contains `proot` (and
`proot-distro`), so the runtime works offline from the very first run.

## Approach

We do **not** hand-roll an "augment the downloaded bootstrap" pipeline.
Instead we run Termux's own
`scripts/generate-bootstraps.sh` from `termux/termux-packages` with
`--add proot,proot-distro`. That tool already handles:

- `.deb` dependency resolution
- Per-architecture fetches from the upstream package repo
- Rewriting `SYMLINKS.txt` (Termux bootstrap's custom symlink manifest)

This keeps us on the officially supported path and lets us bump package
versions in the future with a one-line change.

## How to build

### Option A: GitHub Actions (recommended)

Workflow: `.github/workflows/build-custom-bootstrap.yml` (dispatch-only).

1. Push the `build-custom-bootstrap.yml` workflow to `master` (GitHub
   only shows `workflow_dispatch` buttons for workflows on the default
   branch; we hit this before with `build-ubuntu-rootfs.yml`).
2. GitHub → Actions → *Build Custom Termux Bootstrap* → **Run workflow**.
3. Inputs (all have defaults):
   - `extra_packages` → `proot,proot-distro`
   - `architectures` → `aarch64,x86_64`
   - `package_variant` → `apt-android-7`
4. Download the `termux-custom-bootstraps` artifact. Contents:
   - `bootstrap-aarch64.zip`
   - `bootstrap-x86_64.zip`
   - `SHA256SUMS.txt`
   - `MANIFEST.txt` (records extras, variant, and the upstream
     termux-packages commit used)

### Option B: Locally (requires Docker)

```bash
tools/bootstrap/build_custom_bootstraps.sh
```

Overridable via env:

```bash
EXTRA_PACKAGES=proot,proot-distro \
ARCHITECTURES=aarch64,x86_64 \
PACKAGE_VARIANT=apt-android-7 \
tools/bootstrap/build_custom_bootstraps.sh
```

Output lands at
`tools/bootstrap/output-custom/<package_variant>/bootstrap-<arch>.zip`
(both `output-custom/` and `build-custom/` are gitignored).

## How to consume in the APK (follow-up task)

This is intentionally a separate step from building the bootstrap, so we
can validate the zip contents first.

Current `app/build.gradle` uses Termux's upstream `downloadBootstrap`
helper, pinned by SHA256 to
`https://github.com/termux/termux-packages/releases/download/bootstrap-<ver>/bootstrap-<arch>.zip`.

Two options to switch over:

1. **Release-asset swap (preferred for reproducible CI builds)**
   - Upload the custom zips as assets on a release in our fork
     (`claw800/termux-app-800claw`).
   - Change the URL template in `app/build.gradle`'s `downloadBootstrap`
     block (or override it) to point at our release.
   - Update the pinned SHA256s to the values from `SHA256SUMS.txt`.

2. **Local-file swap (faster iteration)**
   - Teach `downloadBootstrap` to short-circuit when a file exists at a
     known cache path (e.g. `tools/bootstrap/output-custom/<variant>/bootstrap-<arch>.zip`).
   - Copy the custom zips into that path before running Gradle.
   - SHA256 check still enforced.

Either way, validate on device:

```bash
# inside Termux host shell, after fresh install
which proot          # should print /data/data/com.termux/files/usr/bin/proot
proot --version      # should not require pkg install
```

Then the existing `claw800-enter` script should work immediately, with
no `pkg install` step needed.

## Notes / caveats

- `generate-bootstraps.sh` needs Docker. On Linux CI that's free; locally
  Docker Desktop is fine.
- First run downloads the Termux build image (a few hundred MB) and all
  per-arch `.deb`s, expect ~10–15 min. Re-runs are much faster thanks to
  Docker layer cache.
- Artifact size delta vs stock bootstrap is small (proot + libtalloc are
  ~1–2 MB combined).
- We stick with upstream `termux/termux-packages` HEAD for now. If a
  reproducibility issue appears, pin `TP_DIR` to a specific commit.
