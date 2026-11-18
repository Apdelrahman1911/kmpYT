#!/usr/bin/env python3
"""Verify kmpYT Maven metadata, variant redirects, payloads and licenses."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

from central_release import coordinates, validated_files, version_for

def verify(stage, group, version, artifacts):
    group_path = stage.joinpath(*group.split("."))
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    redirects = set()
    for artifact, extension in artifacts.items():
        directory = group_path / artifact / version
        base = f"{artifact}-{version}"
        pom = ElementTree.parse(directory / f"{base}.pom").getroot()
        for key, value in {"groupId": group, "artifactId": artifact, "version": version}.items():
            assert pom.findtext(f"m:{key}", namespaces=namespace) == value
        for path in (
            "m:name", "m:description", "m:url", "m:developers/m:developer/m:name",
            "m:licenses/m:license/m:name", "m:scm/m:connection",
        ):
            assert pom.findtext(path, namespaces=namespace), (artifact, path)
        module = json.loads((directory / f"{base}.module").read_text())
        for variant in module["variants"]:
            if target := variant.get("available-at"):
                redirected_module = (directory / target["url"]).resolve(strict=True)
                assert redirected_module.is_relative_to(group_path)
                assert target["group"] == group and target["version"] == version
                assert redirected_module == group_path / target["module"] / version / f"{target['module']}-{version}.module"
                redirects.add(target["module"])
            for entry in variant.get("files", []):
                payload = directory / entry["url"]
                assert payload.parent == directory and payload.stat().st_size == entry["size"]
                assert hashlib.sha256(payload.read_bytes()).hexdigest() == entry["sha256"], payload
        for name in (f"{base}{extension}", f"{base}-sources.jar", f"{base}-javadoc.jar"):
            with ZipFile(directory / name) as archive:
                assert archive.testzip() is None, name
                if not name.endswith(".klib"):
                    assert "META-INF/kmpyt/LICENSE" in archive.namelist(), name
                    assert "META-INF/kmpyt/THIRD_PARTY_NOTICES.md" in archive.namelist(), name
                if name.endswith("-javadoc.jar"):
                    assert "README.md" in archive.namelist(), name
        for payload in sorted(directory.iterdir()):
            assert payload.is_file() and not payload.is_symlink() and payload.name.startswith(base), payload
            assert payload.suffix in (".pom", ".module", ".json", ".klib", ".jar", ".aar"), payload
    assert redirects == set(artifacts) - {"kmpyt"}, redirects
    assert {path.name for path in group_path.iterdir()} == set(artifacts)
    print(f"Verified kmpYT metadata, variant payloads and notices for {len(artifacts)} coordinates.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    group, artifact_ids = coordinates(Path(__file__).with_name("publications.json"))
    platform_types = {"android": ".aar", "iosarm64": ".klib", "iossimulatorarm64": ".klib", "iosx64": ".klib"}
    artifacts = {artifact: platform_types.get(artifact.removeprefix("kmpyt-"), ".jar") for artifact in artifact_ids}
    version = version_for(args.tag)
    stage = args.repository.resolve(strict=True)
    validated_files(stage, group, artifact_ids, version, args.tag, args.revision)
    verify(stage, group, version, artifacts)
