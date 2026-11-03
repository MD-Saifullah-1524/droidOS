#!/usr/bin/env bash
# droidOS LineageOS 18.1 Source Sync Script
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINEAGE_DIR="${PROJECT_ROOT}/lineage"
REPO_BIN="${HOME}/.bin/repo"

if [ ! -f "${REPO_BIN}" ]; then
    echo "[-] repo tool not found at ${REPO_BIN}, installing..."
    mkdir -p "${HOME}/.bin"
    curl -s https://storage.googleapis.com/git-repo-downloads/repo > "${REPO_BIN}"
    chmod a+rx "${REPO_BIN}"
fi

export PATH="${HOME}/.bin:${PATH}"

cd "${LINEAGE_DIR}"

# Initialize repo if not yet initialized
if [ ! -d ".repo/manifests" ]; then
    echo "[+] Initializing LineageOS 18.1 repository with --depth=1..."
    python3 "${REPO_BIN}" init -u https://github.com/LineageOS/android.git -b lineage-18.1 --git-lfs --depth=1
fi

# Ensure local manifest is linked
mkdir -p .repo/local_manifests
if [ -f "${PROJECT_ROOT}/manifests/rmx1805.xml" ] && [ ! -f ".repo/local_manifests/rmx1805.xml" ]; then
    echo "[+] Linking manifests/rmx1805.xml to .repo/local_manifests/rmx1805.xml"
    ln -sf "${PROJECT_ROOT}/manifests/rmx1805.xml" .repo/local_manifests/rmx1805.xml
fi

THREADS=$(nproc 2>/dev/null || echo 4)
if [ "${THREADS}" -gt 8 ]; then
    THREADS=8
fi
echo "=========================================================="
echo " Starting LineageOS 18.1 Sync for Realme 2 (RMX1805)"
echo " Parallel jobs: ${THREADS}"
echo " Destination:   ${LINEAGE_DIR}"
echo "=========================================================="

python3 "${REPO_BIN}" sync -c -j"${THREADS}" --force-sync --no-clone-bundle --no-tags --optimized-fetch "$@"

echo "[+] Sync completed successfully!"
