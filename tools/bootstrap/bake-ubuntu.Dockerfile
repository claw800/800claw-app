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

# 20260428 - update nanobot-ai to v0.1.5.post2
# https://pypi.org/project/nanobot-ai/
# ARG NANOBOT_AI_VERSION=0.1.4.post5
# ARG NANOBOT_AI_VERSION=0.1.5.post2
# 20260503 - update nanobot-ai to v0.1.5.post3
# ARG NANOBOT_AI_VERSION=0.1.5.post3
# 20260517 - update nanobot-ai to v0.2.0
ARG NANOBOT_AI_VERSION=0.2.0

ARG ENABLE_BROWSER_STACK=0

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Shanghai
ENV HOME=/root
ENV NANOBOT_DATA_DIR=/root/.nanobot
ENV NANOBOT_WORKSPACE=/root/.nanobot/workspace
ENV PYTHON_VERSION=3.12
ENV LANG=zh_CN.UTF-8
ENV LC_ALL=zh_CN.UTF-8

# 1) Create non-root runtime user (kept for parity with the validated template).
RUN useradd -m -s /bin/bash claws

# 2) Base runtime stack.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv python3-pip \
    curl wget git jq zip unzip tar xz-utils \
    cron \
    ffmpeg imagemagick poppler-utils \
    wkhtmltopdf pandoc \
    httpie netcat-openbsd dnsutils \
    vim htop tree ripgrep fd-find bat \
    ca-certificates gnupg \
    libpango-1.0-0 libharfbuzz0b libffi-dev \
    libcairo2 libpangocairo-1.0-0 libgdk-pixbuf-2.0-0 \
    fontconfig fonts-noto-cjk fonts-noto-color-emoji fonts-wqy-microhei locales \
    tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng \
    build-essential \
    # WeasyPrint/reportlab runtime dependencies.
    libxml2 libxslt1.1 libjpeg-turbo8 zlib1g \
    && sed -i 's/^# *\(zh_CN.UTF-8 UTF-8\)/\1/' /etc/locale.gen \
    && sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen \
    && locale-gen zh_CN.UTF-8 en_US.UTF-8 \
    && update-locale LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 \
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
    pip install \
    python-docx \
    reportlab \
    fpdf2 \
    pdfkit \
    pypandoc \
    weasyprint

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "markitdown[all]==${MARKITDOWN_VERSION}" && \
    markitdown --help >/dev/null

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "nanobot-ai==${NANOBOT_AI_VERSION}"

RUN npm install -g "@steipete/summarize@${SUMMARIZE_VERSION}" && \
    summarize --version >/dev/null && \
    npm cache clean --force && \
    rm -rf /root/.npm

# 6) Post-bake smoke tests (fail fast in CI if CJK/PDF stack is broken).
RUN set -eux; \
    # Core binaries must exist.
    command -v cron >/dev/null; \
    command -v wkhtmltopdf >/dev/null; \
    command -v pandoc >/dev/null; \
    command -v fc-list >/dev/null; \
    # CJK font discovery sanity check.
    fc-list :lang=zh | head -5; \
    test "$(fc-list :lang=zh | wc -l)" -gt 0; \
    # Python import/runtime sanity check for document generation toolchain.
    python3 - <<'PYEOF'
import os
from fpdf import FPDF
from reportlab.pdfgen import canvas
import weasyprint
import pypandoc
import pdfkit
import docx

# fpdf smoke
pdf = FPDF()
pdf.add_page()
pdf.set_font("helvetica", size=12)
pdf.cell(0, 10, "claw800 smoke test", ln=True)
pdf.output("/tmp/smoke-fpdf.pdf")

# reportlab smoke
c = canvas.Canvas("/tmp/smoke-reportlab.pdf")
c.drawString(72, 720, "claw800 smoke test")
c.save()

# weasyprint smoke (minimal html -> pdf)
weasyprint.HTML(string="<html><body><p>claw800 smoke test</p></body></html>").write_pdf("/tmp/smoke-weasyprint.pdf")

for path in ("/tmp/smoke-fpdf.pdf", "/tmp/smoke-reportlab.pdf", "/tmp/smoke-weasyprint.pdf"):
    if not os.path.exists(path) or os.path.getsize(path) <= 0:
        raise RuntimeError(f"Smoke PDF not generated: {path}")
PYEOF
RUN rm -f /tmp/smoke-fpdf.pdf /tmp/smoke-reportlab.pdf /tmp/smoke-weasyprint.pdf

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
      pip install "browser-use==${BROWSER_USE_VERSION}" && \
      npm cache clean --force && \
      rm -rf /root/.npm; \
    fi

# 7) Permissions and manifests.
RUN chown -R claws:claws /home/claws
RUN mkdir -p /output && \
    dpkg-query -W > /output/dpkg-manifest.txt && \
    python3 --version > /output/python-version.txt && \
    node --version > /output/node-version.txt

# NOTE: Guest hygiene tweaks (disable NodeSource apt source, swap Ubuntu
# mirrors to Aliyun, install canonical /etc/resolv.conf) are intentionally
# NOT applied here. They live in app/src/main/java/com/termux/app/
# ClawRuntimeBootstrap.java and run at first-launch on the Android side,
# right after the rootfs tarball is extracted.
#
# Two reasons:
#   1) Docker BuildKit bind-mounts /etc/resolv.conf (and /etc/hostname,
#      /etc/hosts) into every RUN step so DNS works during the build. You
#      cannot rm a bind-mounted file (EBUSY), and writes to the bind do
#      not persist into the image layer, so baking /etc/resolv.conf
#      inside this Dockerfile is fundamentally impossible.
#   2) All three tweaks are pure file edits on the extracted rootfs with
#      no dependency on bake-time state. Keeping them in Java avoids a
#      ~1.5 h CI round-trip whenever we tune mirrors or nameservers.
#
# See ClawRuntimeBootstrap#disableNodeSourceAptRepo, #switchAptMirrorsToAliyun,
# and #writeGuestResolvConf for the actual logic.

# 8) Pack rootfs tarball.
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
