#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
from pathlib import Path

from build_dependencies.common import log, error
from build_dependencies.values import ABI_CHOICES, ALL_ARCHES, ARCH_NAMES, LIB_DIR_PATH, resolve_arches, \
    FILE_NAMES, LIB_EXTENSION, Lib
from build_dependencies.pdfium import fetch_prebuilt_pdfium
from build_dependencies.pdfium_source import build_pdfium_from_source, load_patchset
from build_dependencies.libpng import build_libpng_libs
from build_dependencies.freetype2 import build_freetype_libs
from build_dependencies.shared_cpp_lib import copy_shared_cpp_libs
from build_dependencies.native_code import build_native_code

PROJECT_ROOT = Path(__file__).resolve().parent


def parse_args():
    parser = argparse.ArgumentParser(
        description="Build MJ PDF's native dependencies (PDFium, libpng, FreeType, JNI bridge).")
    parser.add_argument("--pdfium", choices=["from-source", "prebuilt"], default="from-source",
                        help="Build pinned, patched PDFium (default), or download an unpatched comparison library.")
    parser.add_argument("--abi", choices=["all", *ABI_CHOICES], default="all",
                        help="Build every ABI (default), or a single ABI.")
    parser.add_argument("--fresh", action="store_true",
                        help="Delete generated native outputs before building.")
    parser.add_argument("--native-only", action="store_true",
                        help="Skip dependencies and only rebuild the JNI bridge (ndk-build).")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print what would be built and exit without building.")
    args = parser.parse_args()
    if args.fresh and args.native_only:
        parser.error("--fresh cannot be combined with --native-only: it removes the required dependency libraries")
    return args


def build_pdfium(mode, arches):
    if mode == "from-source":
        build_pdfium_from_source(arches)
    else:
        log("WARNING: prebuilt PDFium does not include the local Arabic patches.")
        fetch_prebuilt_pdfium(arches)


def summarize(args, arches):
    steps = []
    if args.fresh:
        steps.append("clean outputs")
    if args.native_only:
        steps.append("ndk-build (JNI only)")
    else:
        steps.append("PDFium (" + args.pdfium + ")")
        steps.extend(["libpng", "FreeType", "libc++_shared", "ndk-build (JNI)"])
    log("Dry run, nothing will be built.")
    if args.native_only:
        log("PDFium: existing libraries, not rebuilt")
    elif args.pdfium == "from-source":
        profile = load_patchset()
        log("PDFium revision: " + profile["revision"])
        log("PDFium patches: " + " -> ".join(entry["file"] for entry in profile["patches"]))
        log("PDFium profile: build_dependencies/patches/pdfium/series.json")
    else:
        log("WARNING: prebuilt PDFium does not include the local Arabic patches.")
    log("ABIs: " + ", ".join(ARCH_NAMES[arch] for arch in arches))
    log("NDK: " + os.environ.get("ANDROID_NDK", "<not set>"))
    log("Output: " + str(PROJECT_ROOT / LIB_DIR_PATH))
    log("Steps: " + " -> ".join(steps))


def clean_native_outputs():
    log("Cleaning native dependency outputs.")
    paths_to_clean = [
        Path("build_dependencies/build"),
        Path("build_dependencies/libpng_build"),
        Path("build_dependencies/freetype_build"),
        Path("PdfiumAndroid/src/main/libs"),
        Path("PdfiumAndroid/src/main/obj"),
        Path("PdfiumAndroid/src/main/jni/libs"),
        Path("PdfiumAndroid/src/main/jni/obj"),
    ]
    for path in paths_to_clean:
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.exists():
            shutil.rmtree(path)

    for lib in Path("PdfiumAndroid/src/main").glob("**/*.so"):
        lib.unlink()


def verify_native_deps(arches):
    required = [FILE_NAMES[lib] + LIB_EXTENSION
                for lib in (Lib.pdfium, Lib.freetype2, Lib.libpng, Lib.cpp_shared)]
    missing = [str(Path(LIB_DIR_PATH) / ARCH_NAMES[arch] / lib_filename)
               for arch in arches
               for lib_filename in required
               if not (Path(LIB_DIR_PATH) / ARCH_NAMES[arch] / lib_filename).exists()]

    if missing:
        log("Missing prebuilt dependencies (run a full build first, without --native-only):")
        for path in missing:
            log("  " + path)
        error("Cannot build native code because dependency libraries are missing.")


def main():
    args = parse_args()
    arches = resolve_arches(args.abi)
    if args.dry_run:
        summarize(args, arches)
        return
    if not args.native_only and args.pdfium == "from-source":
        load_patchset()
    previous = Path.cwd()
    try:
        os.chdir(PROJECT_ROOT)
        if args.fresh:
            clean_native_outputs()
        if args.native_only:
            verify_native_deps(arches)
        log("Start " + __file__)
        log("INSTALL_PATH: " + LIB_DIR_PATH)
        os.chdir(PROJECT_ROOT / "build_dependencies")
        if not args.native_only:
            build_pdfium(args.pdfium, arches)
            build_libpng_libs(arches)
            build_freetype_libs(arches)
            copy_shared_cpp_libs(arches)
        build_native_code(arches)
    finally:
        os.chdir(previous)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        details = failure.stderr
        if isinstance(details, bytes):
            details = details.decode("utf-8", errors="replace")
        error(details.strip() if details else str(failure))
    except (OSError, ValueError) as failure:
        error(str(failure))
