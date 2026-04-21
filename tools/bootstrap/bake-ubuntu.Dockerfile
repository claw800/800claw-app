# syntax=docker/dockerfile:1.7
#
# Build a reusable Ubuntu rootfs tarball for claw800 runtime.
# This is Phase 2 Sub-step B (online CI bake) and is consumed by later offline packaging work.

FROM ubuntu:24.04

ARG ROOTFS_ARCH_LABEL
ARG PDF2DOCX_VERSION=0.5.12
ARG MARKITDOWN_VERSION=0.1.5
ARG BROWSER_USE_VERSION=0.12.2
ARG SUMMARIZE_VERSION=0.12.0
ARG NANOBOT_AI_VERSION=0.1.4.post5
ARG ENABLE_BROWSER_STACK=0

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Shanghai
ENV HOME=/root
ENV NANOBOT_DATA_DIR=/root/.nanobot
ENV NANOBOT_WORKSPACE=/root/.nanobot/workspace
ENV PYTHON_VERSION=3.12

# 1) Create non-root runtime user (kept for parity with the validated template).
RUN useradd -m -s /bin/bash claws

# 2) Base runtime stack.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv python3-pip \
    curl wget git jq zip unzip tar xz-utils \
    ffmpeg imagemagick poppler-utils \
    httpie netcat-openbsd dnsutils \
    vim htop tree ripgrep fd-find bat \
    ca-certificates gnupg \
    libpango-1.0-0 libharfbuzz0b libffi-dev \
    tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 3) Node.js 22.x via NodeSource.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

ENV NODE_VERSION=22

# 4) uv (python packaging helper).
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:/opt/venv/bin:${PATH}"

# 5) Python virtual env and required packages.
RUN python3 -m venv /opt/venv
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --upgrade pip setuptools wheel

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "pdf2docx==${PDF2DOCX_VERSION}"

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "markitdown[all]==${MARKITDOWN_VERSION}" && \
    markitdown --help >/dev/null

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "nanobot-ai==${NANOBOT_AI_VERSION}"

RUN npm install -g "@steipete/summarize@${SUMMARIZE_VERSION}" && \
    summarize --version >/dev/null

# 6) Browser stack is intentionally optional in Phase 2.
#    Enable with --build-arg ENABLE_BROWSER_STACK=1 when validating browser-use later.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    if [ "${ENABLE_BROWSER_STACK}" = "1" ]; then \
      apt-get update && \
      apt-get install -y --no-install-recommends npm && \
      rm -rf /var/lib/apt/lists/* && \
      npm install -g playwright && \
      npx playwright install-deps && \
      npx playwright install chromium && \
      pip install "browser-use==${BROWSER_USE_VERSION}"; \
    fi

# 7) Permissions and manifests.
RUN chown -R claws:claws /home/claws
RUN mkdir -p /output && \
    dpkg-query -W > /output/dpkg-manifest.txt && \
    python3 --version > /output/python-version.txt && \
    node --version > /output/node-version.txt

# 8) Guest-side runtime tweaks applied to the rootfs before packing.
#
#    a) Disable the NodeSource apt source now that Node 22 is installed.
#       Leaving it active would force end users to hit NodeSource's repo
#       churn (key rotations, suite renames) every time they run apt-get
#       inside the proot guest. We preserve the file as .bak for reference.
#
#    b) Swap Ubuntu archive/security mirrors to Aliyun. Users in CN see a
#       large `apt-get update` speed-up; users outside CN can still reach
#       Aliyun at respectable speeds, or edit the file themselves.
#
#    c) Replace /etc/resolv.conf with a real file. Under proot-distro
#       there is no systemd-resolved running (Ubuntu's default resolv.conf
#       symlink into /run/systemd/resolve/ is dead), so /etc/resolv.conf
#       is authoritative. We seed it with Aliyun public DNS plus a Google
#       fallback so fresh installs resolve names without user setup.
RUN <<'POST_BAKE'
set -eux

if [ -f /etc/apt/sources.list.d/nodesource.sources ]; then
    mv /etc/apt/sources.list.d/nodesource.sources /etc/apt/sources.list.d/nodesource.sources.bak
fi

if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    cp -f /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/ubuntu.sources.bak
    sed -Ei \
        -e 's|https?://archive\.ubuntu\.com/ubuntu/?|https://mirrors.aliyun.com/ubuntu/|g' \
        -e 's|https?://security\.ubuntu\.com/ubuntu/?|https://mirrors.aliyun.com/ubuntu/|g' \
        /etc/apt/sources.list.d/ubuntu.sources
fi

rm -f /etc/resolv.conf
cat > /etc/resolv.conf <<'RESOLV'
# Baked by claw800. proot-distro guests do not run systemd-resolved,
# so /etc/resolv.conf here is authoritative.
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 8.8.8.8
RESOLV
chmod 644 /etc/resolv.conf
POST_BAKE

# 9) Pack rootfs tarball.
#    ROOTFS_ARCH_LABEL is provided by the build script/workflow as arm64-v8a or x86_64.
# NOTE: --hard-dereference is REQUIRED for Android.
# Android app-private storage (/data/data/<pkg>/...) does not allow
# creation of hard links due to SELinux / filesystem restrictions, even
# within the same package's data directory. tarballs containing hard
# links (perl5.38.2 -> perl, bzcat -> bzip2, etc.) therefore fail with
# "Cannot hard link ... : Permission denied" on extraction.
# --hard-dereference resolves every hard link to a full file copy at
# pack time, producing an Android-extractable tarball at the cost of
# a modest size increase (~20-40 MB for a typical Ubuntu Noble image).
# NOTE: --anchored plus ./<name> pattern prefix is REQUIRED.
# Without --anchored, GNU tar treats --exclude=PATTERN as a BASENAME
# match (any path whose last component equals PATTERN). That silently
# drops unrelated directories deep in the tree, e.g.
# /opt/venv/.../prompt_toolkit/output/ was removed before this fix,
# which made nanobot crash with
#   ModuleNotFoundError: No module named 'prompt_toolkit.output'.
# --anchored constrains excludes to paths relative to tar's working
# directory (-C /), so ./output only matches /output, ./dev only
# matches /dev, etc. This is the only safe way to exclude top-level
# pseudo-filesystem and build-artifact paths.
RUN tar -C / \
      --xattrs \
      --acls \
      --numeric-owner \
      --hard-dereference \
      --anchored \
      --exclude=./proc \
      --exclude=./sys \
      --exclude=./dev \
      --exclude=./run \
      --exclude=./tmp \
      --exclude=./mnt \
      --exclude=./media \
      --exclude=./output \
      --exclude=./.dockerenv \
      -cJf "/output/ubuntu-noble-${ROOTFS_ARCH_LABEL}.tar.xz" .

FROM scratch
COPY --from=0 /output/ /
