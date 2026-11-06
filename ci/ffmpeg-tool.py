#!/usr/bin/env python3
"""Pinned repository-owned CI FFmpeg; no global install or product bundle.

POSIX builds official source with existing C/make tools. Windows extracts a
pinned Gyan build (provider linked by FFmpeg's official download page).
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.0.1"
SOURCE_SHA256 = "679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1"
SOURCE_BYTES = 16_408_559
SOURCE_COMMIT = "894da5ca7d742e4429ffb2af534fcda0103ef593"
WINDOWS_URL = "https://github.com/GyanD/codexffmpeg/releases/download/8.0.1/ffmpeg-8.0.1-essentials_build.zip"
WINDOWS_SHA256 = "e2aaeaa0fdbc397d4794828086424d4aaa2102cef1fb6874f6ffd29c0b88b673"
WINDOWS_BYTES = 106_259_850

# The JVM test remuxes H.264/AAC AND decodes both tracks to null. HTTP/TCP
# supports the explicit loopback SABR bridge test, not external fixtures.
# No autodetected/external codec/crypto libraries, GPL or nonfree features.
CONFIGURE = [
    "--disable-autodetect", "--disable-doc", "--disable-debug", "--disable-x86asm",
    "--disable-everything", "--disable-programs", "--enable-ffmpeg", "--enable-small",
    "--disable-shared", "--enable-static", "--disable-gpl", "--disable-nonfree",
    "--enable-protocol=file,pipe,crypto,data,http,tcp",
    "--enable-demuxer=mov,matroska,mpegts,aac,mp3,ogg,flac,wav",
    "--enable-muxer=mp4,null",
    "--enable-decoder=h264,aac,mp3,opus,vorbis,flac,pcm_s16le",
    "--enable-encoder=wrapped_avframe,pcm_s16le",
    "--enable-parser=h264,aac,mpegaudio,opus,vorbis,flac",
    "--enable-bsf=aac_adtstoasc,h264_mp4toannexb,extract_extradata",
    "--enable-filter=anull,null,aresample,aformat,format,scale",
]


def owned(path: Path, root: Path = ROOT) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise ValueError("Refusing an out-of-repository tool path")
    return resolved


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, destination: Path, expected: str, size: int) -> None:
    destination = owned(destination)
    if destination.is_file() and destination.stat().st_size == size and sha256(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = owned(destination.with_suffix(destination.suffix + ".partial"))
    started = time.monotonic()
    try:
        request = Request(url, headers={"User-Agent": "kmpYT-scoped-CI/1"})
        with urlopen(request, timeout=60) as response, part.open("wb") as output:
            if urlsplit(response.geturl()).scheme != "https":
                raise ValueError("Non-HTTPS tool download redirect rejected")
            count = 0
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > size or time.monotonic() - started > 600:
                    raise ValueError("Tool download exceeds pinned size/time limit")
                output.write(block)
        if part.stat().st_size != size or sha256(part) != expected:
            raise ValueError("Tool archive size/SHA256 mismatch")
        part.replace(destination)
    finally:
        part.unlink(missing_ok=True)


def archive_name(name: str, prefix: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if "\\" in name or path.is_absolute() or ".." in path.parts or ":" in name:
        raise ValueError("Unsafe tool archive path")
    if not path.parts or path.parts[0] != prefix:
        raise ValueError("Unexpected tool archive root")
    return path


def extract_source(archive: Path, output: Path, root: Path = ROOT) -> Path:
    prefix = "FFmpeg-n8.0.1"
    output = owned(output, root)
    with tarfile.open(archive, "r:gz") as bundle:
        entries = bundle.getmembers()
        if len(entries) > 30_000 or sum(item.size for item in entries) > 512 * 1024 * 1024:
            raise ValueError("Tool source archive exceeds bounded limits")
        names = set()
        # Validate ALL entries before creating archive-derived files.
        for item in entries:
            name = archive_name(item.name, prefix)
            if str(name) in names or not (item.isdir() or item.isfile()):
                raise ValueError("Duplicate/link/special source archive entry rejected")
            names.add(str(name))
            owned(output / str(name), root)
        for item in entries:
            destination = owned(output / str(archive_name(item.name, prefix)), root)
            if item.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(item) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                destination.chmod(0o755 if item.mode & 0o111 else 0o644)
    source = output / prefix
    text = (source / "COPYING.LGPLv2.1").read_text(encoding="utf-8")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in text or len(text) < 25_000:
        raise ValueError("Complete official FFmpeg LGPL notice missing")
    return source


def extract_windows(archive: Path, output: Path, root: Path = ROOT) -> Path:
    prefix = "ffmpeg-8.0.1-essentials_build"
    output = owned(output, root)
    wanted = {f"{prefix}/bin/ffmpeg.exe": "bin/ffmpeg.exe",
              f"{prefix}/LICENSE": "notices/LICENSE",
              f"{prefix}/README.txt": "notices/README.txt"}
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        if len(entries) > 30_000 or sum(item.file_size for item in entries) > 768 * 1024 * 1024:
            raise ValueError("Windows tool archive exceeds bounded limits")
        names = set()
        for item in entries:
            name = str(archive_name(item.filename, prefix))
            if name in names or ((item.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError("Duplicate/symlink tool archive entry rejected")
            names.add(name)
        if not set(wanted).issubset(names):
            raise ValueError("Pinned Windows executable/notices missing")
        license_text = bundle.read(f"{prefix}/LICENSE").decode("utf-8")
        if "GNU GENERAL PUBLIC LICENSE" not in license_text or "Version 3" not in license_text or len(license_text) < 30_000:
            raise ValueError("Complete Windows FFmpeg GPL3 notice missing")
        for name, relative in wanted.items():
            path = owned(output / relative, root)
            path.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(name) as source, path.open("wb") as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
    return output / "bin/ffmpeg.exe"


def run(command: list[str], cwd: Path, timeout: int) -> None:
    subprocess.run(command, cwd=cwd, check=True, stdout=sys.stderr, stderr=sys.stderr, timeout=timeout)


def prepare() -> tuple[Path, dict]:
    if os.environ.get("KMPYT_CI_SCOPED_ENV") != "1":
        raise ValueError("Use ci/scoped.sh before preparing writable tools")
    system = platform.system()
    if platform.machine().lower() not in ("x86_64", "amd64", "arm64", "aarch64"):
        raise ValueError("Unsupported CI tool architecture")
    base = owned(ROOT / ".work/tools/ffmpeg-ci")
    base.mkdir(parents=True, exist_ok=True)
    archive_dir = owned(base / "downloads")
    if system == "Windows":
        if platform.machine().lower() not in ("x86_64", "amd64"):
            raise ValueError("The pinned Windows test tool is x64 only")
        archive = archive_dir / "ffmpeg-8.0.1-essentials_build.zip"
        download(WINDOWS_URL, archive, WINDOWS_SHA256, WINDOWS_BYTES)
        executable = extract_windows(archive, base)
        metadata = {"origin": WINDOWS_URL, "archiveSha256": WINDOWS_SHA256,
                    "license": "GPL-3.0-or-later (Gyan essentials CI-only tool)", "builtFromSourceHere": False}
    elif system in ("Linux", "Darwin"):
        for tool in ("cc", "make", "bash"):
            if shutil.which(tool) is None:
                raise ValueError("Existing C compiler/GNU make/bash required for scoped FFmpeg source build")
        archive = archive_dir / "FFmpeg-n8.0.1.tar.gz"
        download(SOURCE_URL, archive, SOURCE_SHA256, SOURCE_BYTES)
        source = extract_source(archive, base / "source")
        build = owned(base / ("build-" + system.lower() + "-" + platform.machine().lower()))
        build.mkdir(parents=True, exist_ok=True)
        # No make install: the build executable stays in this repository.
        jobs = os.environ.get("KMPYT_CI_NATIVE_JOBS", "2")
        if jobs not in ("1", "2"):
            raise ValueError("Native CI build jobs must be 1 or 2")
        run(["bash", str(source / "configure"), "--prefix=" + str(base / "prefix"), *CONFIGURE], build, 300)
        run(["make", "-j" + jobs, "ffmpeg"], build, 900)
        executable = build / "ffmpeg"
        metadata = {"origin": SOURCE_URL, "archiveSha256": SOURCE_SHA256, "sourceCommit": SOURCE_COMMIT,
                    "license": "LGPL-2.1-or-later (no GPL/nonfree/external libraries enabled)",
                    "builtFromSourceHere": True, "configure": CONFIGURE}
    else:
        raise ValueError("Unsupported CI FFmpeg host")
    if not executable.is_file():
        raise ValueError("FFmpeg preparation did not produce the required executable")
    metadata.update({"executableSha256": sha256(executable), "system": system,
                     "architecture": platform.machine(), "shippedInLibraryOrApp": False})
    (base / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return executable.resolve(), metadata


def verify(executable: Path, root: Path = ROOT) -> None:
    """Same authored remux AND native decode required by the JVM regression."""
    work = owned(root / ".work/tmp", root)
    work.mkdir(parents=True, exist_ok=True)
    media = root / "kmpyt/src/jvmTest/resources/research"
    with tempfile.TemporaryDirectory(prefix="ffmpeg-ci-proof-", dir=work) as directory:
        output = Path(directory) / "combined.mp4"
        run([str(executable), "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
             "-filter_threads", "1", "-filter_complex_threads", "1",
             "-threads", "1", "-i", str(media / "video.mp4"), "-threads", "1", "-i", str(media / "audio.m4a"),
             "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart",
             "-f", "mp4", str(output)], Path(directory), 30)
        run([str(executable), "-nostdin", "-v", "error", "-filter_threads", "1", "-filter_complex_threads", "1",
             "-threads", "1", "-i", str(output), "-map", "0:v:0", "-map", "0:a:0",
             "-threads", "1", "-f", "null", "-"], Path(directory), 30)
        if not output.is_file() or output.stat().st_size <= 0:
            raise ValueError("Authored media tool verification produced no output")


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "--verify":
        verify(Path(sys.argv[2]))
        print("Authored FFmpeg remux + H264/AAC decode passed; not live source, application or physical-audio proof.")
    elif len(sys.argv) == 1:
        executable, _ = prepare()
        # stdout is a path-only contract for select-tools.sh.
        print(executable.as_posix())
    else:
        raise ValueError("Usage: ffmpeg-tool.py [--verify executable]")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, zipfile.BadZipFile) as error:
        # Download failures may contain redirected signatures: never print them.
        print("Scoped FFmpeg preparation/verification failed (" + type(error).__name__ + "). No global install or skipped-test fallback.", file=sys.stderr)
        if isinstance(error, ValueError):
            print(str(error), file=sys.stderr)
        raise SystemExit(1)
