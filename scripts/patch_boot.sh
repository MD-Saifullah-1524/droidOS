#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${PROJECT_ROOT}/tools/magisk/bin"
MAGISKBOOT="${TOOLS_DIR}/magiskboot"

INPUT_BOOT="${1}"
OUTPUT_BOOT="${2:-${INPUT_BOOT}}"

if [ -z "${INPUT_BOOT}" ] || [ ! -f "${INPUT_BOOT}" ]; then
    echo "Usage: $0 <input_boot.img> [output_boot.img]"
    exit 1
fi

WORK_DIR="$(mktemp -d /tmp/boot_patch_XXXXXX)"
trap 'rm -rf "${WORK_DIR}"' EXIT

cd "${WORK_DIR}"
cp "${MAGISKBOOT}" ./magiskboot
cp "${TOOLS_DIR}/magiskinit" ./magiskinit
cp "${TOOLS_DIR}/magisk32" ./magisk32
cp "${TOOLS_DIR}/magisk64" ./magisk64

echo "[+] Unpacking ${INPUT_BOOT}..."
./magiskboot unpack "${INPUT_BOOT}"

echo "[+] Compressing binaries..."
./magiskboot compress=xz magisk32 magisk32.xz
./magiskboot compress=xz magisk64 magisk64.xz

echo "KEEPVERITY=false" > config
echo "KEEPFORCEENCRYPT=false" >> config
echo "RECOVERYMODE=false" >> config

echo "[+] Checking ramdisk status..."
STATUS=0
if [ -e ramdisk.cpio ]; then
    ./magiskboot cpio ramdisk.cpio test || STATUS=$?
fi

if [ $((STATUS & 3)) -eq 1 ]; then
    echo "[!] Existing Magisk ramdisk detected, restoring..."
    ./magiskboot cpio ramdisk.cpio restore
fi

echo "[+] Injecting bootstrap ramdisk..."
./magiskboot cpio ramdisk.cpio \
  "add 0750 init magiskinit" \
  "mkdir 0750 overlay.d" \
  "mkdir 0750 overlay.d/sbin" \
  "add 0644 overlay.d/sbin/magisk32.xz magisk32.xz" \
  "add 0644 overlay.d/sbin/magisk64.xz magisk64.xz" \
  "patch" \
  "mkdir 000 .backup" \
  "add 000 .backup/.magisk config"

echo "[+] Patching device tree blobs (fstab/AVB)..."
for dt in dtb kernel_dtb extra; do
    if [ -f "${dt}" ]; then
        ./magiskboot dtb "${dt}" patch || true
        echo "[+] Processed fstab in ${dt}"
    fi
done

if [ -f kernel ]; then
    echo "[+] Hex-patching kernel to force loading rootfs (skip_initramfs -> want_initramfs)..."
    ./magiskboot hexpatch kernel \
        736B69705F696E697472616D667300 \
        77616E745F696E697472616D667300 || echo "[!] skip_initramfs pattern may already be patched."
    
    # Verify want_initramfs presence
    if strings kernel | grep -q "want_initramfs"; then
        echo "[+] Verified: kernel has want_initramfs patched successfully."
    else
        echo "[-] ERROR: want_initramfs not found in kernel after hexpatch!"
        exit 1
    fi
fi

echo "[+] Repacking boot image..."
./magiskboot repack "${INPUT_BOOT}" new-boot.img

cp new-boot.img "${OUTPUT_BOOT}"
echo "[+] Successfully patched boot image: ${OUTPUT_BOOT}"
