#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_OUT="${PROJECT_ROOT}/lineage/out/target/product/RMX1805"
SERVE_DIR="${SERVE_DIR:-/mnt/c/Users/mh122/droidOS_serve}"
if [ ! -d "/mnt/c" ]; then
    SERVE_DIR="${PROJECT_ROOT}/serve"
fi

echo "[+] Finding flashable ZIP in ${PRODUCT_OUT}..."
ZIP_FILE=$(ls -t "${PRODUCT_OUT}"/lineage-18.1-*.zip 2>/dev/null | head -n 1)

if [ -z "${ZIP_FILE}" ] || [ ! -f "${ZIP_FILE}" ]; then
    echo "[-] No flashable zip found in ${PRODUCT_OUT}"
    exit 1
fi

echo "[+] Target ZIP: ${ZIP_FILE}"
BOOT_IMG="${PRODUCT_OUT}/boot.img"

if [ ! -f "${BOOT_IMG}" ]; then
    echo "[-] boot.img not found in ${PRODUCT_OUT}"
    exit 1
fi

echo "[+] Patching boot.img with Magisk bootstrap ramdisk..."
"${PROJECT_ROOT}/scripts/patch_boot.sh" "${BOOT_IMG}" "${BOOT_IMG}"

echo "[+] Updating ZIP package with patched boot.img..."
cd "${PRODUCT_OUT}"
zip -u "${ZIP_FILE}" boot.img

echo "[+] Staging flashable ZIP to HTTPS serve directory..."
mkdir -p "${SERVE_DIR}"
cp -v "${ZIP_FILE}" "${SERVE_DIR}/droidos-18.1-RMX1805.zip"
cp -v "${ZIP_FILE}" "${SERVE_DIR}/$(basename "${ZIP_FILE}")"

echo "[+] Calculating Checksums..."
cd "${SERVE_DIR}"
md5sum droidos-18.1-RMX1805.zip > checksums.md5
sha256sum droidos-18.1-RMX1805.zip > checksums.sha256

echo "=========================================================="
echo " droidOS Package Ready!"
echo " File:   droidos-18.1-RMX1805.zip"
echo " Size:   $(du -h droidos-18.1-RMX1805.zip | awk '{print $1}')"
echo " MD5:    $(cat checksums.md5 | awk '{print $1}')"
echo " SHA256: $(cat checksums.sha256 | awk '{print $1}')"
echo "=========================================================="
