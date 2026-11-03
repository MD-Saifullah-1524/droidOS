# droidOS

**droidOS** turns a budget Android 11 device (**Realme 2 / RMX1805**, Qualcomm MSM8953 / Snapdragon 450) into a dedicated dual-mode workstation:
- **Phone Screen:** A distraction-free, minimalist typographic monochrome control panel (telemetry, quick controls, and companion virtual trackpad/keyboard).
- **USB Desktop:** A full multi-window desktop interface streamed over USB 2.0 via **AOA (Android Open Accessory 2.0)** to a host computer (macOS / Linux / Windows) without requiring DisplayPort Alt Mode or USB debugging.

---

## Workspace Layout

```
droidOS/
├── manifests/              # Local manifest definitions for LineageOS repo
│   └── rmx1805.xml         # Realme 2 device & vendor tree definitions
├── lineage/                # LineageOS 18.1 (Android 11) source tree
│   ├── .repo/local_manifests/rmx1805.xml
│   ├── device/oppo/RMX1805 # Realme 2 Device Tree
│   └── vendor/oppo         # Realme 2 Vendor Blobs
├── droid-packages/         # droidOS system components & applications
│   ├── DroidControlPanel/  # Minimalist typographic monochrome primary UI
│   └── DroidAoaService/    # AOA USB daemon, VirtualDisplay & MediaCodec pipeline
├── host-client/            # macOS / cross-platform desktop receiver & input bridge
└── scripts/
    ├── sync.sh             # Syncs LineageOS 18.1 source tree with local manifests
    └── build.sh            # Builds LineageOS 18.1 ROM image for RMX1805
```

---

## Device Specifications: Realme 2 (RMX1805)

- **Chipset:** Qualcomm Snapdragon 450 (8x Cortex-A53 @ 1.8 GHz, Adreno 506 GPU)
- **Display:** 6.2" IPS LCD, 720 x 1520 pixels (19:9 ratio)
- **Port:** Micro-USB 2.0 (High Speed, 480 Mbps)
- **Kernel:** Linux 4.9 (MSM8953, prebuilt in `device/oppo/RMX1805/prebuilt/kernel`)
- **Android Target:** Android 11.0.0 (LineageOS 18.1, API Level 30)

---

## Multi-Display & Desktop Mode Configuration

The device tree has been configured with native Android 11 Desktop Mode flags:
- `overlay/frameworks/base/core/res/res/values/config.xml`:
  - `config_freeformWindowManagement = true` (enables floating resizable windows)
  - `config_supportsMultiDisplay = true` (enables independent secondary display)
- `system.prop`:
  - `persist.sys.freeform_window=true`
  - `persist.sys.force_desktop_mode=true`
  - `persist.sys.multi_display=true`

---

## Getting Started

### 1. Sync LineageOS 18.1 Source
```bash
./scripts/sync.sh
```

### 2. Build the ROM
```bash
./scripts/build.sh
```
The output zip will be placed in `lineage/out/target/product/RMX1805/lineage-18.1-*-UNOFFICIAL-RMX1805.zip`.
