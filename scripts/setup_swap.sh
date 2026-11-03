#!/usr/bin/env bash
# droidOS Swap Setup Script (24 GB)
set -e

if [ "$EUID" -ne 0 ]; then
    echo "[-] Please run as root: sudo $0"
    exit 1
fi

SWAPFILE="/swapfile"
SWAP_SIZE="24G"

if [ -f "$SWAPFILE" ]; then
    echo "[*] Swap file $SWAPFILE already exists. Turning off old swap..."
    swapoff "$SWAPFILE" 2>/dev/null || true
    rm -f "$SWAPFILE"
fi

echo "[+] Allocating ${SWAP_SIZE} swap file at ${SWAPFILE}..."
fallocate -l "${SWAP_SIZE}" "${SWAPFILE}" || dd if=/dev/zero of="${SWAPFILE}" bs=1M count=24576 status=progress

echo "[+] Setting permissions (0600)..."
chmod 600 "${SWAPFILE}"

echo "[+] Setting up swap area..."
mkswap "${SWAPFILE}"

echo "[+] Activating swap..."
swapon "${SWAPFILE}"

# Add to fstab if not already present
if ! grep -q "${SWAPFILE}" /etc/fstab; then
    echo "${SWAPFILE} none swap sw 0 0" >> /etc/fstab
fi

echo "[+] Swap configured successfully! Current swap status:"
swapon --show
free -h
