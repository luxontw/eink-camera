# Eink Camera

Camera app for USB UVC (USB video class) devices — webcams, microscopes, capture cards. Plug a camera into your Android device and get a live preview with photo capture, video recording, and QR scanning.

Eink Camera (package `tw.newxe.einkcamera`) is a proprietary app derived from the open-source [Android USB Camera Viewer](https://gitlab.com/yaky/android-usb-cam-viewer) by Anton Yaky.

## Features

- **Live preview** — automatically detects the connected UVC device and opens it at the highest supported resolution (MJPEG, with YUYV fallback). The preview fills the screen with a center-crop; no distortion.
- **Photo** — captures the on-screen framing to `DCIM/EinkCamera`, with an optional 3 s / 10 s countdown.
- **Video** — records H.264/MP4 alongside the live preview, switchable between 720p / 1080p / 1440p.
- **Scan** — ML Kit QR/barcode scanning with draggable result overlays: tap to open a link, drag onto the save zone to re-encode it as a QR image in the photo album. Also scans images shared or opened from other apps.
- **Focus & exposure HUD** — tap the preview for a focus indicator and drag the sun slider to adjust brightness.
- **Extras** — grid overlay, electronic image stabilization, and Auto Run rules (auto-save QR codes matching keywords, auto-open links matching domains).
- **Quick launch** — launcher shortcuts for photo/video/scan, and automatic launch when a UVC device is attached.

## Requirements

- Android 7.0+ with USB OTG (host) support
- A UVC-compatible camera
- Camera permission — Android requires it for USB cameras; the app does not access the device's built-in camera

## Notes on usage

Whether using a USB OTG dongle or a USB hub, disconnect the entire dongle/hub from your Android device when disconnecting the camera. Re-connecting a camera (or any USB device) to a plugged-in USB OTG dongle will probably not be detected by Android. Disconnecting the camera from a connected USB hub might freeze and crash the app. Re-connecting the camera to the USB hub will re-enumerate it and present it as a different device (/dev/bus/usb/001/002, then /dev/bus/usb/001/003, and so on). USB on Android has some quirks.

Blocking cameras in GrapheneOS will block USB cameras as well, and will likely crash the app.

## Building

Developed with Android Studio 2026.1 and JDK 21. Releases are built and signed locally.

```
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed release APK -> app/build/outputs/apk/release/
```

Release signing reads `keystore.properties` (not in version control) from the repo root; without it, `assembleRelease` produces an unsigned APK.

The UVCCamera library is bundled as a locally rebuilt AAR (`app/libs/uvccamera-lib-0.0.13-16k.aar`) with 16 KB page-size alignment; see the comment in `app/build.gradle`.

## License

Eink Camera is proprietary software. Copyright © 2026 luxontw. All rights reserved. See [LICENSE](LICENSE).

The app is derived from the Apache-2.0-licensed [Android USB Camera Viewer](https://gitlab.com/yaky/android-usb-cam-viewer) by Anton Yaky; the inherited code remains under that license. All third-party attributions and full license texts are in [THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES).

## Acknowledgments

- [Android USB Camera Viewer](https://gitlab.com/yaky/android-usb-cam-viewer) — Copyright © 2025 Anton Yaky. [Apache License 2.0](https://gitlab.com/yaky/android-usb-cam-viewer/-/blob/main/LICENSE). Eink Camera is a modified version of this project.
- [UVCCamera](https://github.com/alexey-pelykh/UVCCamera) — Copyright © 2014–2017 saki t_saki@serenegiant.com. [Apache License 2.0](https://github.com/alexey-pelykh/UVCCamera/blob/main/LICENSE.md). Maintained fork by Alexey Pelykh; the upstream app was originally based on its usbCameraTest0 sample.
- [ZXing core](https://github.com/zxing/zxing) — Copyright © 2007 ZXing authors. [Apache License 2.0](https://github.com/zxing/zxing/blob/master/LICENSE).
- [Google ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning) — [Google APIs Terms of Service](https://developers.google.com/terms). Includes the bundled barcode model and associated Google Play services libraries.
- [AndroidX libraries](https://github.com/androidx/androidx) — Copyright © The Android Open Source Project. [Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt).
- [Material Components for Android](https://github.com/material-components/material-components-android) — Copyright © The Android Open Source Project. [Apache License 2.0](https://github.com/material-components/material-components-android/blob/master/LICENSE).
- [Kotlin Standard Library and kotlinx.coroutines](https://github.com/JetBrains/kotlin) — Copyright © 2010–2024 JetBrains s.r.o. and Kotlin Programming Language contributors. [Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt).
- Firebase SDK components, DataTransport, Error Prone annotations, Guava ListenableFuture, JSpecify, javax.inject — Copyright © Google LLC and the respective authors. Apache License 2.0.
- [libuvc](https://github.com/libuvc/libuvc) — Copyright © 2010–2015 Ken Tossell. [BSD License](https://github.com/libuvc/libuvc/blob/master/LICENSE.txt).
- [libusb](https://github.com/libusb/libusb) — Copyright © 2001–2013 the libusb project contributors. [GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html). Bundled as a separate shared library; source of the Android port at [alexey-pelykh/UVCCamera](https://github.com/alexey-pelykh/UVCCamera).
- [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) — Copyright © 2009–2016 D. R. Commander. [IJG, Modified BSD, and zlib licenses](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md). This software is based in part on the work of the Independent JPEG Group.

Full license texts are in [THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES).
