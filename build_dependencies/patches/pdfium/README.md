# PDFium Patchset

This directory is the maintained PDFium source profile for MJ PDF. It contains the
upstream revision, common Android GN settings and the complete ordered patchset.

## Entrypoint

From the project root:

```sh
ANDROID_NDK=/path/to/android-ndk python3 build_dependencies.py --abi arm64-v8a
```

Omit `--abi` to build all four ABIs. Use `--dry-run` to inspect the revision,
patches and build steps without fetching, building or changing files.

`build_dependencies.py` is the public entrypoint. It prepares PDFium once, builds
the selected architectures, builds the other native dependencies and rebuilds
the JNI wrapper. Gradle then packages the resulting native libraries:

```sh
./gradlew :app:assembleDebug
```

## Profile

`series.json` is the source of truth. Patches are applied in its listed order,
after syncing the exact upstream commit and its DEPS, before GN or Ninja runs.
Every patch is checked against its recorded SHA-256. One validated profile
snapshot supplies every architecture in the invocation, so an edit to
`series.json` cannot change the settings halfway through a multi-ABI build.

| File | Purpose |
| --- | --- |
| `standalone.patch` | Build one shared PDFium library, export its public interface, and retain Android symbol visibility. |
| `arabic-*.patch` | Arabic geometry, replacement-text occurrence handling, logical units, conservative RTL reconstruction, combining-mark ownership, and regression tests. |

The shared-library patch replaces the previous build/header/toolchain patch
collection. At this revision, the tested standalone build needs only changes in
PDFium's root `BUILD.gn`. Chromium's nested `build` repository and public headers
remain upstream originals.

## Repeat Builds

Source caches are stored under:

```text
build_dependencies/pdfium_source/<revision>-<profile-fingerprint>/pdfium/
```

A new profile gets a new cache. Existing caches and the old unversioned checkout
are never reset, cleaned, or silently upgraded. A prepared cache is revalidated
and reused without another source sync. Its dependency inventory is sealed in
the preparation stamp. An interrupted or mismatched cache is refused with its path.

Patch application preflights the complete series using a disposable Git index
and object directory. It accepts only pristine source or the exact patched state.
Staged edits, other tracked edits, unexpected untracked files and `.orig`/`.rej`
backups beside patch targets are rejected without being discarded.

## Updating PDFium

1. Choose and record an exact upstream commit, not a moving branch.
2. In a separate checkout of that commit, rebase the patch series. Keep build
   adaptation and text behavior separate.
3. Regenerate the affected PDF test resources from their `.in` templates and run
   the PDFium embedder tests plus the Arabic source-based corpus checks.
4. Update `revision`, patch files and their `sha256` values in `series.json`.
   Update `gn_args` only with build/runtime validation. The new profile selects a
   new cache automatically.
5. Build all ABIs and verify Copy, Search, selection/highlight geometry and Text
   Mode with the matching JNI wrapper.

Do not use fuzzy patching, ignore failed hunks, or silently switch to an unpatched
binary when the source build fails. Native sources, tests and fixture templates
belong in the patch. Generated PDFs, object files and APKs do not.

Run the dependency preparation tests without network access or native builds:

```sh
python3 -m unittest discover -s build_dependencies/tests -v
```

## Other Modes

`--native-only` rebuilds JNI using the dependency libraries already present. It
does not fetch or apply this patchset. `--fresh --native-only` is invalid because
cleaning would remove those required libraries.

`--pdfium prebuilt` remains an explicit unpatched comparison mode. The stock
prebuilt release is configured separately and does not include this Arabic
repair. It is not a fallback for failed source preparation.

## Scope

The Arabic repair remains conservative and experimental. It does not recover
arbitrary malformed Unicode mappings, guarantee all paragraph/column reading
orders, or perform font-specific PDF rewriting. Maintaining a patchset makes builds
reproducible.
