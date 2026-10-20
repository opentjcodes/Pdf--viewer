# Fresh Linux Setup

This guide describes how to build and run MJ PDF from a fresh Linux install.

## 1. Install System Packages

Install Java 17, Python, CMake, Git, and basic build tools.

Arch Linux:

```bash
sudo pacman -S jdk17-openjdk python python-requests git base-devel cmake
```

Debian/Ubuntu:

```bash
sudo apt install openjdk-17-jdk python3 python3-requests git build-essential cmake
```

## 2. Install Android SDK Components

Install Android Studio, then open:

`More Actions` > `SDK Manager`

Install these SDK components:

- Android SDK Platform `API 36`
- Android SDK Build-Tools
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- NDK (Side by side)
- CMake

The default Android Studio SDK path on Linux is usually:

```text
/home/user/Android/Sdk
```

Use your actual username/path if different.

## 3. Configure `local.properties`

Create `local.properties` in the project root if it does not already exist:

```properties
sdk.dir=/home/user/Android/Sdk
```

The crash-reporting values (`AI`, `AK`, `AU`) are committed in `gradle.properties`, so no
extra setup is needed. To send crash reports to a different backend for local testing,
override them in `~/.gradle/gradle.properties` or pass `-PAI=... -PAK=... -PAU=...`.

## 4. Build Native Dependencies

Find the installed NDK version:

```bash
ls /home/user/Android/Sdk/ndk
```

Then run the dependency build script with `ANDROID_NDK` pointing to that NDK directory:

```bash
ANDROID_NDK=/home/user/Android/Sdk/ndk/30.0.14904198 python3 build_dependencies.py
```

Replace `30.0.14904198` with the NDK version installed on your machine.

This is the single entrypoint for native dependency and JNI builds. Add
`--abi arm64-v8a` to build one architecture, or `--dry-run` to inspect the selected
revision, patches and steps without fetching or modifying files.

By default it fetches the exact PDFium revision from
[`build_dependencies/patches/pdfium/series.json`](build_dependencies/patches/pdfium/series.json),
syncs its DEPS and strictly applies the local patches before building. This needs
depot_tools, GN and Ninja and can take a while. The source profile also contains
the Android GN settings. See the [patchset maintenance guide](build_dependencies/patches/pdfium/README.md).

Prepared source caches are revision/profile-specific. Existing checkouts are not
reset or cleaned; unknown local edits and incomplete caches stop preparation.

`--pdfium prebuilt` explicitly downloads an **unpatched** comparison build without
the local Arabic fixes. It is not a fallback when patch application fails.

The script places dependency libraries under:

```text
PdfiumAndroid/src/main/jni/lib
```

It then builds JNI and places the libraries Gradle packages under
`PdfiumAndroid/src/main/libs`. After changing only JNI code, use the same
entrypoint with `--native-only`. Do not combine it with `--fresh`, which removes
the libraries JNI needs.

## 5. Run it with Android Studio
At this point Android Studio should be able to run it with no issues.

## 6. Build the Debug APK

From the project root:

```bash
./gradlew :app:assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 7. Build the Release APKs

From the project root:

```bash
./gradlew :app:assembleRelease
```

This produces one APK per architecture plus a universal one under:

```text
app/build/outputs/apk/release/
```

The release APKs are unsigned. Sign each APK you distribute with your release keystore:

```bash
apksigner sign --ks your-keystore.jks --out mj-pdf.apk app-arm64-v8a-release-unsigned.apk
```

`apksigner` is in the SDK's `build-tools` directory.
