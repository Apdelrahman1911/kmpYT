#!/usr/bin/env python3
"""Check the reviewed grant and retained provenance; no build or network."""
import hashlib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def verify(root: Path) -> None:
    license=(root/'LICENSE').read_bytes().replace(b'\r\n',b'\n')
    expected='2b48ffd17a0263667e7df9b64b277520776571b1ce73365134b7e92a6d114bcb'
    if hashlib.sha256(license).hexdigest()!=expected:
        raise ValueError('MIT text/copyright differs from the owner-reviewed grant; review deliberately, never silently truncate')
    notices=(root/'THIRD_PARTY_NOTICES.md').read_text()
    for required in ('Ownership/contribution review','533562f781304338f72b27d32cfe5fb7a83dc3a8','CC0','Apache-2.0','EPL-1.0','GPL-3.0-or-later','not bundled','media remain'):
        if required not in notices:
            raise ValueError('Missing provenance/dependency qualification: '+required)
    gradle=(root/'kmpyt/build.gradle.kts').read_text()
    for required in ('name.set("MIT License")','https://opensource.org/license/mit','distribution.set("repo")'):
        if required not in gradle: raise ValueError('Missing published POM license metadata')
    for directory in ('kmpyt/src/jvmTest/resources/research','kmpyt/src/androidInstrumentedTest/assets/research'):
        matches=[p for p in (root/directory).glob('*') if p.is_file() and ('notice' in p.name.lower() or 'license' in p.name.lower() or 'readme' in p.name.lower())]
        if not any('CC0' in p.read_text() for p in matches):
            raise ValueError('Authored fixture CC0 notice missing: '+directory)

if __name__=='__main__':
    verify(ROOT)
    print('PASS: complete approved MIT grant, ownership/dependency qualifications, POM metadata and authored-fixture CC0 notices. This is NOT native test or binary-distribution verification.')
