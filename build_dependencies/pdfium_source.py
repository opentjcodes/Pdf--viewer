import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import tempfile
from urllib.parse import urlsplit

from build_dependencies.common import DOWNLOAD_RETRY_DELAYS, error, get_lib_path, log
from build_dependencies.values import (
    ARCH_NAMES,
    DEPOT_TOOLS_GIT_URL,
    DEPOT_TOOLS_REVISION,
    FILE_NAMES,
    Lib,
    LIB_EXTENSION,
    PDFIUM_GN_CPU_NAMES,
    PDFIUM_SOURCE_DIR,
)

DEPOT_TOOLS_DIR = "depot_tools"
PATCH_DIR = Path(__file__).parent / "patches" / "pdfium"
_DIFF_ARGS = ("--no-ext-diff", "--no-textconv", "--ignore-submodules=none")


def _safe_path(path):
    path = Path(path).absolute()
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError(f"Symlinks are not allowed: {path}")
    return path.resolve()


def _read_patch(entry):
    if not isinstance(entry, dict):
        raise ValueError("Each PDFium patch must specify file and sha256")
    filename = entry.get("file")
    digest = entry.get("sha256")
    if (
        not isinstance(filename, str)
        or filename in ("", ".", "..")
        or Path(filename).name != filename
        or "\\" in filename
        or "\0" in filename
    ):
        raise ValueError(f"Invalid PDFium patch filename: {filename!r}")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
        raise ValueError(f"Invalid SHA256 for PDFium patch: {filename}")
    path = _safe_path(Path(PATCH_DIR) / filename)
    if not path.is_file():
        raise ValueError(f"Missing PDFium patch: {path}")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest.lower():
        raise ValueError(f"SHA256 mismatch for PDFium patch: {path}")
    return data


def load_patchset():
    data = _safe_path(Path(PATCH_DIR) / "series.json").read_bytes()
    config = json.loads(data)
    if not isinstance(config, dict):
        raise ValueError("PDFium series.json must contain an object")
    revision = config.get("revision")
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", revision):
        raise ValueError("PDFium revision must be a full 40-digit commit hash")
    url = config.get("url")
    if (
        not isinstance(url, str)
        or any(ord(char) <= 32 or ord(char) == 127 for char in url)
        or urlsplit(url).scheme != "https"
        or not urlsplit(url).hostname
    ):
        raise ValueError("PDFium URL must be an HTTPS URL")
    args = config.get("gn_args")
    if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
        raise ValueError("PDFium gn_args must be a list of strings")
    patches = config.get("patches")
    if not isinstance(patches, list):
        raise ValueError("PDFium patches must be an ordered list")
    seen = set()
    for entry in patches:
        _read_patch(entry)
        if entry["file"] in seen:
            raise ValueError(f"Duplicate PDFium patch: {entry['file']}")
        seen.add(entry["file"])
    config["fingerprint"] = hashlib.sha256(data).hexdigest()
    return config


def _git_env(env):
    return {
        **{key: value for key, value in env.items() if not key.startswith("GIT_")},
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_TERMINAL_PROMPT": "0",
    }


def _git(source, env, *args, input=None, allow_difference=False):
    command = [
        "git", "-c", "core.fsmonitor=false", "-c", "core.splitIndex=false",
        "-c", "core.filemode=true", "-c", "diff.autoRefreshIndex=false",
        "-c", "apply.ignoreWhitespace=no", *args,
    ]
    result = subprocess.run(command, cwd=source, env=env, input=input, capture_output=True)
    if result.returncode not in ((0, 1) if allow_difference else (0,)):
        raise subprocess.CalledProcessError(
            result.returncode, command, output=result.stdout, stderr=result.stderr
        )
    return result


def _validate_repository(source, revision, env):
    source = _safe_path(source)
    git_dir = _safe_path(source / ".git")
    if not source.is_dir() or not git_dir.is_dir():
        raise ValueError(f"PDFium repository must have its own Git directory: {source}")
    for name in ("HEAD", "index", "config", "refs", "packed-refs", "objects"):
        _safe_path(git_dir / name)
    for ref in (git_dir / "refs").rglob("*"):
        _safe_path(ref)
    if Path(os.fsdecode(_git(source, env, "rev-parse", "--show-toplevel").stdout).strip()) != source:
        raise ValueError(f"PDFium repository is not the exact Git root: {source}")
    if _git(source, env, "rev-parse", "--verify", "HEAD").stdout.decode().strip() != revision.lower():
        raise ValueError(f"PDFium repository HEAD does not match {revision}: {source}")
    if _git(
        source, env, "diff", "--cached", "--quiet", *_DIFF_ARGS, "HEAD", "--", allow_difference=True,
    ).returncode:
        raise ValueError(f"Refusing staged PDFium changes: {source}")
    if any(
        entry and not entry.startswith(b"H ")
        for entry in _git(source, env, "ls-files", "-v", "-z").stdout.split(b"\0")
    ):
        raise ValueError(f"Refusing PDFium index flags that can hide changes: {source}")
    return source


def build_pdfium_from_source(arches):
    config = load_patchset()
    root = Path(__file__).resolve().parent
    env = depot_tools_env(root)
    source = sync_pdfium(root, env, config)
    for arch in arches:
        build_arch(source, arch, env, config)


def depot_tools_env(root):
    depot_tools = os.path.join(root, DEPOT_TOOLS_DIR)
    ensure_depot_tools(root, depot_tools)

    env = _git_env(network_env())
    env["PATH"] = depot_tools + os.pathsep + env["PATH"]
    env["DEPOT_TOOLS_UPDATE"] = "0"
    run(
        [os.path.join(depot_tools, "ensure_bootstrap")],
        root,
        env,
        retry_delays=DOWNLOAD_RETRY_DELAYS,
        timeout=600,
    )
    return env

def network_env():
    env = os.environ.copy()
    env.setdefault("GIT_HTTP_LOW_SPEED_LIMIT", "1024")
    env.setdefault("GIT_HTTP_LOW_SPEED_TIME", "60")
    return env


def ensure_depot_tools(root, depot_tools):
    if os.path.isdir(depot_tools):
        if git_revision(depot_tools) == DEPOT_TOOLS_REVISION:
            return
        log("Replacing depot_tools checkout that is not at the pinned revision.")
        shutil.rmtree(depot_tools)

    partial_checkout = depot_tools + ".part"
    shutil.rmtree(partial_checkout, ignore_errors=True)
    os.makedirs(partial_checkout)

    try:
        env = network_env()
        run(["git", "init", "--quiet"], partial_checkout, env)
        run(["git", "remote", "add", "origin", DEPOT_TOOLS_GIT_URL], partial_checkout, env)
        run(
            ["git", "fetch", "--depth", "1", "origin", DEPOT_TOOLS_REVISION],
            partial_checkout,
            env,
            retry_delays=DOWNLOAD_RETRY_DELAYS,
            timeout=600,
        )
        run(["git", "checkout", "--detach", "FETCH_HEAD"], partial_checkout, env)
        os.replace(partial_checkout, depot_tools)
    finally:
        shutil.rmtree(partial_checkout, ignore_errors=True)


def git_revision(directory):
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=directory,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def sync_pdfium(root, env, config):
    env = _git_env(env)
    root = _safe_path(root)
    if not root.is_dir():
        raise ValueError(f"Missing dependency directory: {root}")
    cache = _safe_path(
        root / PDFIUM_SOURCE_DIR / (config["revision"] + "-" + config["fingerprint"][:12])
    )
    source = _safe_path(cache / "pdfium")
    stamp = _safe_path(cache / ".prepared")
    gclient = _safe_path(cache / ".gclient")
    solutions = [{
        "name": "pdfium",
        "url": config["url"],
        "managed": False,
        "custom_deps": {},
        "custom_vars": {"checkout_configuration": "minimal"},
    }]
    settings = f"solutions = {solutions!r}\ntarget_os = ['android']\n"
    existing = cache.exists()
    if existing:
        if (
            not cache.is_dir()
            or not source.is_dir()
            or not stamp.is_file()
            or not gclient.is_file()
            or gclient.read_text(encoding="utf-8") != settings
        ):
            raise ValueError(f"Refusing incomplete or mismatched PDFium cache: {cache}")
    else:
        cache.parent.mkdir(exist_ok=True)
        cache.mkdir()
        with gclient.open("x", encoding="utf-8") as output:
            output.write(settings)
        run(
            ["gclient", "sync", "-r", "pdfium@" + config["revision"], "--no-history", "--shallow"],
            cache,
            env,
            retry_delays=DOWNLOAD_RETRY_DELAYS,
            timeout=600,
        )

    inventory = _safe_path(cache / ".gclient_entries")
    if not inventory.is_file():
        raise ValueError(f"Missing PDFium dependency inventory: {inventory}")
    data = inventory.read_bytes()
    prepared = {"fingerprint": config["fingerprint"], "entries_sha256": hashlib.sha256(data).hexdigest()}
    if existing and json.loads(stamp.read_bytes()) != prepared:
        raise ValueError(f"Refusing changed PDFium cache inventory or profile: {cache}")
    try:
        statements = ast.parse(data).body
    except SyntaxError as exc:
        raise ValueError(f"Invalid PDFium dependency inventory: {inventory}") from exc
    if (
        len(statements) != 1
        or not isinstance(statements[0], ast.Assign)
        or len(statements[0].targets) != 1
        or not isinstance(statements[0].targets[0], ast.Name)
        or statements[0].targets[0].id != "entries"
    ):
        raise ValueError(f"Expected a literal entries assignment: {inventory}")
    entries = ast.literal_eval(statements[0].value)
    if not isinstance(entries, dict) or entries.get("pdfium") != config["url"]:
        raise ValueError(f"PDFium dependency inventory has the wrong root: {inventory}")
    for name, url in entries.items():
        if name == "pdfium":
            continue
        if not isinstance(name, str) or "\\" in name or "\0" in name:
            raise ValueError(f"Invalid PDFium dependency path: {name!r}")
        parts = name.partition(":")[0].split("/")
        if parts[0] != "pdfium" or any(part in ("", ".", "..") for part in parts):
            raise ValueError(f"Invalid PDFium dependency path: {name!r}")
        dependency = _safe_path(cache.joinpath(*parts))
        if dependency == source or not dependency.is_relative_to(source):
            raise ValueError(f"PDFium dependency escapes its source directory: {name!r}")
        if url is None or ":" in name:
            continue
        pin = re.fullmatch(r"https://\S+@([0-9a-fA-F]{40})", url) if isinstance(url, str) else None
        if pin is None:
            raise ValueError(f"PDFium Git dependency is not pinned to a full commit hash: {name}")
        _validate_repository(dependency, pin[1], env)
        if (
            _git(dependency, env, "diff", *_DIFF_ARGS, "HEAD", "--").stdout
            or _git(dependency, env, "ls-files", "--others", "--exclude-standard", "-z").stdout
        ):
            raise ValueError(f"Refusing modified PDFium dependency: {dependency}")
    apply_pdfium_patches(source, config, env)
    if not existing:
        with stamp.open("x", encoding="utf-8") as output:
            output.write(json.dumps(prepared, sort_keys=True) + "\n")
    return source


def apply_pdfium_patches(source, config, env):
    env = _git_env(env)
    source = _validate_repository(source, config["revision"], env)

    def git(*args, **kwargs):
        return _git(source, env, *args, **kwargs)

    objects = _safe_path(os.fsdecode(
        git("rev-parse", "--path-format=absolute", "--git-path", "objects").stdout
    ).strip())

    def matches_worktree():
        git("update-index", "--refresh", allow_difference=True)
        return (
            not git("ls-files", "--others", "--exclude-standard", "-z").stdout
            and not any(
                os.path.lexists(source / (os.fsdecode(path) + suffix))
                for path in affected_paths for suffix in (".orig", ".rej")
            )
            and git("diff", "--quiet", *_DIFF_ARGS, "--", allow_difference=True).returncode == 0
        )

    with tempfile.TemporaryDirectory(prefix=".pdfium-preflight-", dir=source.parent) as temporary:
        temporary = Path(temporary)
        (temporary / "objects").mkdir()
        env = dict(
            env,
            GIT_INDEX_FILE=str(temporary / "index"),
            GIT_OBJECT_DIRECTORY=str(temporary / "objects"),
            GIT_ALTERNATE_OBJECT_DIRECTORIES=json.dumps(str(objects), ensure_ascii=False),
        )
        git("read-tree", "HEAD")
        for entry in config["patches"]:
            git("apply", "--cached", "--whitespace=error-all", "-", input=_read_patch(entry))
        affected_paths = git(
            "diff", "--cached", "--name-only", "-z", "--no-renames", *_DIFF_ARGS, "HEAD", "--",
        ).stdout.split(b"\0")[:-1]
        if matches_worktree():
            return
        combined = git(
            "diff", "--cached", "--binary", "--full-index", "--no-renames", *_DIFF_ARGS,
            "--src-prefix=a/", "--dst-prefix=b/", "HEAD", "--",
        ).stdout
        git("read-tree", "HEAD")
        if not matches_worktree():
            raise ValueError(f"PDFium source is neither pristine nor exactly patched: {source}")
        git("apply", "--whitespace=error-all", "-", input=combined)


def build_arch(source, arch, env, config):
    log(f"Building PDFium from source for {ARCH_NAMES[arch]}.")
    out_dir = os.path.join("out", ARCH_NAMES[arch])
    write_gn_args(os.path.join(source, out_dir), arch, config)
    run(["gn", "gen", out_dir], source, env)
    run(["ninja", "-j6", "-C", out_dir, "pdfium"], source, env)
    install_lib(os.path.join(source, out_dir), arch)
    log(f"Finished building PDFium for {ARCH_NAMES[arch]}.")
    log("------------------------------------")


def write_gn_args(out_dir, arch, config):
    args = config["gn_args"] + [f'target_cpu = "{PDFIUM_GN_CPU_NAMES[arch]}"']
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "args.gn").write_text("\n".join(args) + "\n", encoding="utf-8")


def install_lib(out_dir, arch):
    built_lib = Path(out_dir) / "libpdfium.so"
    if not built_lib.is_file():
        error(f"Build produced no library at {built_lib}")
    lib_path = (
        Path(__file__).resolve().parent
        / get_lib_path(arch, FILE_NAMES[Lib.pdfium] + LIB_EXTENSION, levels_up=1)
    ).resolve()
    lib_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=f".{lib_path.name}.", dir=lib_path.parent, delete=False) as output:
        temporary = Path(output.name)
    try:
        shutil.copy(built_lib, temporary)
        os.replace(temporary, lib_path)
    finally:
        temporary.unlink(missing_ok=True)
    log(f"Installed {lib_path}.")


def run(command, cwd, env, retry_delays=(), timeout=None):
    attempts = len(retry_delays) + 1
    failure = None
    for attempt in range(attempts):
        log("Run: " + " ".join(map(str, command)))
        try:
            result = subprocess.run(command, cwd=cwd, env=_git_env(env), timeout=timeout)
            if result.returncode == 0:
                return
            failure = f"failed with code {result.returncode}"
        except subprocess.TimeoutExpired:
            failure = f"timed out after {timeout} seconds"

        if attempt < len(retry_delays):
            delay = retry_delays[attempt]
            log(f"'{command[0]}' {failure}; retrying in {delay} seconds.")
            time.sleep(delay)

    error(f"'{command[0]}' {failure} after {attempts} attempts")
