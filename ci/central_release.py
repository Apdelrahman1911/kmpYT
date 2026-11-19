#!/usr/bin/env python3
"""Seal verified Maven Local publications, release one immutable bundle and read it back."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4
from xml.etree import ElementTree
from zipfile import ZIP_DEFLATED, ZipFile


MAVEN = "https://repo.maven.apache.org/maven2/"
PORTAL = "https://central.sonatype.com/api/v1/publisher/"
POM_NS = {"p": "http://maven.apache.org/POM/4.0.0"}
RELEASE_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = build_opener(RejectRedirects())


class CentralHttpError(RuntimeError):
    def __init__(self, method, url, error):
        self.status = error.code
        detail = error.read(16_384).decode(errors="replace").lower()
        reason = next((label for label, terms in (
            ("publishing limit", ("quota", "usage limit", "publishing limit", "monthly limit")),
            ("deployment state", ("deployment state", "not validated", "already publishing")),
            ("authorization", ("unauthorized", "forbidden", "permission")),
        ) if any(term in detail for term in terms)), None)
        suffix = f" ({reason})" if reason else ""
        super().__init__(f"{method} {url.split('?', 1)[0]} returned HTTP {self.status}{suffix}")


def request(url, method="GET", data=None, headers=None, missing=False):
    try:
        with HTTP.open(Request(url, method=method, data=data, headers=headers or {}), timeout=120) as reply:
            return reply.read()
    except HTTPError as error:
        with error:
            if missing and error.code == 404:
                return None
            raise CentralHttpError(method, url, error) from None


def coordinates(inventory):
    manifest = json.loads(inventory.read_text(encoding="utf-8"))
    roots = {item["artifact"] for item in manifest["modules"]}
    return manifest["group"], sorted(roots | {f"{root}-{suffix}" for root in roots for suffix in manifest["platformArtifacts"]})


def version_for(tag):
    match = RELEASE_TAG.fullmatch(tag)
    if not match or tag.upper().endswith("-SNAPSHOT") or any(part.isdecimal() and len(part) > 1 and part.startswith("0") for part in (match[4] or "").split(".")):
        raise ValueError("Expected a vMAJOR.MINOR.PATCH release tag, with an optional SemVer prerelease")
    return tag[1:]


def pom_path(group, artifact, version):
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def verify_pom(data, group, artifact, version, tag, revision):
    root = ElementTree.fromstring(data)
    fields = {
        "p:groupId": group, "p:artifactId": artifact, "p:version": version,
        "p:scm/p:tag": tag, "p:properties/p:releaseSourceRevision": revision,
    }
    for field, expected in fields.items():
        if root.findtext(field, namespaces=POM_NS) != expected:
            raise ValueError(f"{artifact}:{version} differs at {field}; will not replace an existing version")


def is_public(group, artifacts, version, tag, revision):
    def read_pom(artifact):
        data = request(MAVEN + pom_path(group, artifact, version), missing=True)
        if data is not None:
            verify_pom(data, group, artifact, version, tag, revision)
        return data is not None

    with ThreadPoolExecutor(max_workers=8) as workers:
        states = list(workers.map(read_pom, artifacts))
    if any(states) and not all(states):
        raise ValueError("Only part of the immutable version is visible on Maven Central. Check the existing deployment before retrying.")
    return all(states)


def validated_files(stage, group, artifacts, version, tag, revision):
    group_dir = stage.joinpath(*group.split("."))
    if {path.name for path in group_dir.iterdir() if (path / version).is_dir()} != set(artifacts):
        raise ValueError("Staged artifact IDs do not match the release inventory")
    files = []
    for artifact in artifacts:
        pom = stage / pom_path(group, artifact, version)
        verify_pom(pom.read_bytes(), group, artifact, version, tag, revision)
        prefix = f"{artifact}-{version}"
        for item in sorted(pom.parent.iterdir()):
            if item.is_symlink() or not item.is_file() or not item.name.startswith(prefix) or item.suffix not in (".pom", ".module", ".json", ".klib", ".jar", ".aar", ".zip"):
                raise ValueError(f"Unexpected publication entry {item.name}")
            files.append(item)
    return files


def sign_bundle(stage, files, bundle, secret_key, passphrase):
    with tempfile.TemporaryDirectory(prefix="release-gpg-", dir=bundle.parent) as tmp:
        environment = {"PATH": os.environ["PATH"], "GNUPGHOME": tmp}
        gpg = ["gpg", "--batch", "--no-tty", "--quiet"]
        try:
            subprocess.run(gpg + ["--import"], input=secret_key, env=environment, check=True)
            key_records = subprocess.check_output(gpg + ["--with-colons", "--list-secret-keys"], env=environment).decode().splitlines()
            if sum(record.startswith("sec:") for record in key_records) != 1:
                raise ValueError("The release environment must contain exactly one PGP signing key")
            signer = next(record.split(":")[9] for record in key_records if record.startswith("fpr:"))
            with ZipFile(bundle, "x", ZIP_DEFLATED, compresslevel=3) as output:
                for file in files:
                    relative = file.relative_to(stage).as_posix()
                    signature = Path(tmp) / "signature.asc"
                    subprocess.run(gpg + ["--pinentry-mode", "loopback", "--passphrase-fd", "0", "--local-user", signer,
                                            "--armor", "--detach-sign", "--output", str(signature), str(file)],
                                   input=passphrase + b"\n", env=environment, check=True)
                    subprocess.run(gpg + ["--verify", str(signature), str(file)], env=environment,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                    output.write(file, relative)
                    output.write(signature, relative + ".asc")
                    signature.unlink()
                    data = file.read_bytes()
                    for algorithm in ("md5", "sha1", "sha256", "sha512"):
                        output.writestr(relative + "." + algorithm, hashlib.new(algorithm, data).hexdigest())
        finally:
            subprocess.run(["gpgconf", "--homedir", tmp, "--kill", "gpg-agent"], env=environment, check=False)


def portal(path, token, parameters=None, data=None, content_type=None):
    url = PORTAL + path + ("?" + urlencode(parameters) if parameters else "")
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    return request(url, method="POST", data=data if data is not None else b"", headers=headers)


def await_state(deployment, token, *required):
    deadline = time.monotonic() + 25 * 60
    last = None
    while time.monotonic() < deadline:
        response = json.loads(portal("status", token, {"id": deployment}))
        if response.get("deploymentId") != deployment:
            raise ValueError("Central returned a mismatched deployment receipt")
        status = response["deploymentState"]
        if status != last:
            print(f"Central deployment {deployment} is {status}", flush=True)
            last = status
        if status in required:
            return response
        if status == "FAILED":
            raise ValueError(f"Central validation failed for deployment {deployment}. See its validation messages in the Central Portal.")
        time.sleep(12)
    raise TimeoutError(f"Central deployment {deployment} did not reach {' or '.join(required)}; inspect it before retrying")


def compare_public(stage, files):
    deadline = time.monotonic() + 8 * 60

    def same_bytes(file):
        data = request(MAVEN + file.relative_to(stage).as_posix(), missing=True)
        if data is None:
            return False
        if hashlib.sha256(data).digest() != hashlib.sha256(file.read_bytes()).digest():
            raise ValueError(f"Published bytes differ from the approved bundle at {file.name}")
        return True

    with ThreadPoolExecutor(max_workers=8) as workers:
        pending = files
        while pending and time.monotonic() < deadline:
            pending = [file for file, match in zip(pending, workers.map(same_bytes, pending)) if not match]
            if pending:
                time.sleep(15)
    if pending:
        raise TimeoutError(f"Maven Central has not served {len(pending)} approved files yet")
    print(f"Compared SHA-256 for {len(files)} published files against the approved staged repository.")


def deployment_name(group, version, revision):
    return f"{group} {version} ({revision[:12]})"


def central_token():
    username = os.environ.pop("CENTRAL_USERNAME", "").strip()
    password = os.environ.pop("CENTRAL_PASSWORD", "").strip()
    if not username or not password:
        raise ValueError("The maven-central environment needs CENTRAL_USERNAME and CENTRAL_PASSWORD")
    return base64.b64encode(f"{username}:{password}".encode()).decode()


def publish_deployment(deployment, token, group, artifacts, version, revision):
    expected = {f"pkg:maven/{group}/{artifact}@{version}" for artifact in artifacts}
    for attempt in range(4):
        result = await_state(deployment, token, "VALIDATED", "PUBLISHING", "PUBLISHED")
        actual = {purl.split("?", 1)[0] for purl in result["purls"]}
        if actual != expected or result.get("deploymentName") != deployment_name(group, version, revision):
            raise ValueError(f"Deployment {deployment} does not match the verified release.")
        if result["deploymentState"] != "VALIDATED":
            break
        if attempt == 3:
            raise last_error
        try:
            portal(f"deployment/{deployment}", token)
            break
        except CentralHttpError as error:
            if error.status not in {400, 409, 429, 500, 502, 503, 504}:
                raise
            print(f"{error}. Checking the same deployment before retrying.", flush=True)
            last_error = error
            time.sleep(12 * (attempt + 1))
    await_state(deployment, token, "PUBLISHED")


def publish(stage, bundle, group, artifacts, version, tag, revision):
    if is_public(group, artifacts, version, tag, revision):
        print(f"{version} is already public for this release commit. Reusing the immutable publication.")
        return
    files = validated_files(stage, group, artifacts, version, tag, revision)
    token = central_token()
    key = os.environ.pop("SIGNING_KEY", "").encode()
    passphrase = os.environ.pop("SIGNING_PASSPHRASE", "").rstrip("\r\n").encode()
    if not key:
        raise ValueError("The maven-central environment needs SIGNING_KEY")
    bundle.parent.mkdir(parents=True, exist_ok=True)
    sign_bundle(stage, files, bundle, key, passphrase)
    boundary = uuid4().hex
    opening = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"release.zip\"\r\n"
               "Content-Type: application/octet-stream\r\n\r\n").encode()
    data = opening + bundle.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    raw_id = portal("upload", token, {"publishingType": "USER_MANAGED", "name": deployment_name(group, version, revision)},
                    data, f"multipart/form-data; boundary={boundary}")
    deployment = str(UUID(raw_id.decode().strip()))
    print(f"Submitted Central deployment {deployment}", flush=True)
    publish_deployment(deployment, token, group, artifacts, version, revision)
    compare_public(stage, files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "publish", "resume"))
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--deployment")
    args = parser.parse_args()
    version = version_for(args.tag)
    if not re.fullmatch("[0-9a-f]{40}", args.revision):
        parser.error("expected an immutable Git commit SHA")
    group, artifacts = coordinates(args.inventory)
    if args.command == "check":
        published = is_public(group, artifacts, version, args.tag, args.revision)
        print(f"{group} {version}: {len(artifacts)} coordinates; already public: {published}")
        if output := os.environ.get("GITHUB_OUTPUT"):
            with Path(output).open("a", encoding="utf-8") as stream:
                stream.write(f"version={version}\nrevision={args.revision}\nalready_public={str(published).lower()}\n")
    elif args.command == "publish":
        if not args.repository or not args.bundle:
            parser.error("publishing needs a verified local Maven repository and a new bundle path")
        publish(args.repository.resolve(strict=True), args.bundle.resolve(), group, artifacts, version, args.tag, args.revision)
    else:
        if not args.repository or not args.deployment:
            parser.error("resuming needs a verified local Maven repository and an existing deployment ID")
        stage = args.repository.resolve(strict=True)
        deployment = str(UUID(args.deployment))
        files = validated_files(stage, group, artifacts, version, args.tag, args.revision)
        if not is_public(group, artifacts, version, args.tag, args.revision):
            publish_deployment(deployment, central_token(), group, artifacts, version, args.revision)
        compare_public(stage, files)


if __name__ == "__main__":
    main()
