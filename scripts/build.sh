#!/usr/bin/env bash
# droidOS Build Script for Realme 2 (RMX1805) - LineageOS 18.1
set -e
set -o pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINEAGE_DIR="${PROJECT_ROOT}/lineage"

export PATH="${HOME}/.bin:${PATH}"
export LD_LIBRARY_PATH="${HOME}/.local/lib:${LD_LIBRARY_PATH}"

cd "${LINEAGE_DIR}"

if [ ! -f "build/envsetup.sh" ]; then
    echo "[-] Android build environment not ready."
    exit 1
fi

# Setup ccache if available
if command -v ccache >/dev/null 2>&1; then
    export USE_CCACHE=1
    export CCACHE_EXEC="$(command -v ccache)"
    export CCACHE_DIR="${PROJECT_ROOT}/.ccache"
    mkdir -p "${CCACHE_DIR}"
    ccache -M 50G >/dev/null 2>&1 || true
    echo "[+] ccache enabled at ${CCACHE_DIR}"
fi

# Memory limits for Metalava / Soong / Java
export ALLOW_NINJA_ENV=true
export _JAVA_OPTIONS="-Xmx8g -XX:+UseG1GC"
export ANDROID_JACK_VM_ARGS="-Dfile.encoding=UTF-8 -XX:+TieredCompilation -Xmx8G"

echo "[+] Sourcing build environment..."
source build/envsetup.sh

echo "[+] Lunching lineage_RMX1805-userdebug..."
lunch lineage_RMX1805-userdebug

TARGET="${*:-bacon}"
THREADS=$(nproc 2>/dev/null || echo 4)
if [ "${THREADS}" -gt 8 ]; then
    THREADS=8
fi

echo "=========================================================="
echo " Building target: ${TARGET}"
echo " Parallel jobs:   ${THREADS}"
echo "=========================================================="

# Ensure legacy host shared libraries are in out host lib64 directories
mkdir -p "${LINEAGE_DIR}/out/soong/host/linux-x86/lib64" "${LINEAGE_DIR}/out/host/linux-x86/lib64"
cp -d "${HOME}/.local/lib"/lib*5* "${LINEAGE_DIR}/out/soong/host/linux-x86/lib64/" 2>/dev/null || true
cp -d "${HOME}/.local/lib"/lib*5* "${LINEAGE_DIR}/out/host/linux-x86/lib64/" 2>/dev/null || true

# Ensure zip is linked in out/.path
if [ -d "${LINEAGE_DIR}/out/.path" ] && [ ! -e "${LINEAGE_DIR}/out/.path/zip" ]; then
    ln -sf ../.path_interposer "${LINEAGE_DIR}/out/.path/zip"
fi

# Ensure metalava has adequate JVM heap
if [ -f "${LINEAGE_DIR}/out/soong/host/linux-x86/bin/metalava" ]; then
    sed -i 's/declare -a javaOpts=()/declare -a javaOpts=("-Xmx8g" "-XX:+UseG1GC")/g' "${LINEAGE_DIR}/out/soong/host/linux-x86/bin/metalava"
fi

m -j"${THREADS}" "${TARGET}" 2>&1 | tee -a "${PROJECT_ROOT}/build.log"

if [[ "${TARGET}" =~ bacon ]]; then
    echo "[+] Running droidOS packaging & boot patcher..."
    "${PROJECT_ROOT}/scripts/package_droidos.sh"
fi
