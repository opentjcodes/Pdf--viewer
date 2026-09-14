import os
from pathlib import Path

LIB_EXTENSION = ".so"
TGZ_EXTENSION = ".tgz"
BUILD = "build"
LIBPNG_BUILD = "libpng_build"
FREETYPE_BUILD = "freetype_build"
BUILD_TYPE = "Release"  # Release or Debug
LINKER_FLAGS_16KB = "-Wl,-z,max-page-size=16384"
LIB_DIR_PATH = "PdfiumAndroid/src/main/jni/lib"
CMAKE_DIR_PATH = str(Path(__file__).resolve().parent / "cmake")


def get_toolchain_path(ndk_path):
    return os.path.join(ndk_path, "build/cmake")


# Toolchain path
NDK_PATH = "/opt/android-ndk/"
DEFAULT_TOOLCHAIN = get_toolchain_path(NDK_PATH)
ANDROID_TOOLCHAIN_FILENAME = "android.toolchain.cmake"
ANDROID_PLATFORM = "19"


class Arch:
    x86_64 = 0
    x86 = 1
    arm64 = 2
    armeabi = 3


class Lib:
    pdfium = 0
    freetype2 = 1
    libpng = 2
    cpp_shared = 3


FILE_NAMES = {
    Lib.pdfium: "libmodpdfium",
    Lib.freetype2: "libmodft2",
    Lib.libpng: "libmodpng",
    Lib.cpp_shared: "libc++_shared",
}

ARCH_NAMES = {
    Arch.x86: "x86",
    Arch.x86_64: "x86_64",
    Arch.arm64: "arm64-v8a",
    Arch.armeabi: "armeabi-v7a",
}

ARCH_BY_NAME = {ARCH_NAMES[a]: a for a in ARCH_NAMES}

ALL_ARCHES = [Arch.arm64, Arch.armeabi, Arch.x86_64, Arch.x86]
ABI_CHOICES = [ARCH_NAMES[arch] for arch in ALL_ARCHES]


def resolve_arches(selection):
    if selection == "all":
        return list(ALL_ARCHES)
    return [ARCH_BY_NAME[selection]]


PDFIUM_URLS = {
    # Arch.x86: "https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-x86.tgz",
    # Arch.x86_64: "https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-x64.tgz",
    # Arch.arm64: "https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-arm64.tgz",
    # Arch.armeabi: "https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-arm.tgz",
    Arch.x86: "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F7920/pdfium-android-x86.tgz",
    Arch.x86_64: "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F7920/pdfium-android-x64.tgz",
    Arch.arm64: "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F7920/pdfium-android-arm64.tgz",
    Arch.armeabi: "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F7920/pdfium-android-arm.tgz",
}

PDFIUM_SHA256 = {
    Arch.x86: "2a1f0f845323c5322d98c6ce76fcc9ef5ef4f38795e757228a10601179684045",
    Arch.x86_64: "5ac4a1894c18b228bb150b6e4d704b4eec527d68f61db4622dabc978e9ce90b0",
    Arch.arm64: "50657e8e0f8a5d94804be7fbf6f872fa5ebaceeb401dd1450022dd63bc07f005",
    Arch.armeabi: "7363c2ad4cb5e443a067ac0c74d693dfae84e8592d86a8823e39ac6cfd1502eb",
}

DEPOT_TOOLS_GIT_URL = "https://chromium.googlesource.com/chromium/tools/depot_tools.git"
DEPOT_TOOLS_REVISION = "13febbee9ece9e03df923f69d540afc63c6db93e"
PDFIUM_SOURCE_DIR = "pdfium_source"

PDFIUM_GN_CPU_NAMES = {
    Arch.x86: "x86",
    Arch.x86_64: "x64",
    Arch.arm64: "arm64",
    Arch.armeabi: "arm",
}

LIB_CPP_DIR_NAMES = {
    Arch.x86: "i686-linux-android",
    Arch.x86_64: "x86_64-linux-android",
    Arch.arm64: "aarch64-linux-android",
    Arch.armeabi: "arm-linux-androideabi",
}

LIBPNG_VERSION = "1.6.58"
LIBPNG_URLS = (
    f"https://downloads.sourceforge.net/project/libpng/libpng16/{LIBPNG_VERSION}/libpng-{LIBPNG_VERSION}.tar.xz",
    f"https://download.sourceforge.net/libpng/libpng-{LIBPNG_VERSION}.tar.xz",
)
LIBPNG_SHA256 = "28eb403f51f0f7405249132cecfe82ea5c0ef97f1b32c5a65828814ae0d34775"

FREETYPE_VERSION = "2.14.3"
FREETYPE_URLS = (
    f"https://download.savannah.gnu.org/releases/freetype/freetype-{FREETYPE_VERSION}.tar.xz",
    f"https://downloads.sourceforge.net/freetype/freetype-{FREETYPE_VERSION}.tar.xz",
)
FREETYPE_SHA256 = "36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f"
