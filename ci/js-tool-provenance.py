#!/usr/bin/env python3
"""Read-only tool identities; never launch Chromium just to obtain its version."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


def identity(value):
    path = Path(value).resolve(strict=True)
    info = path.stat()
    if not path.is_file() or not 0 < info.st_size <= 512 * 1024 * 1024:
        raise ValueError('Selected JavaScript tool is not a bounded regular file')
    if not re.fullmatch(r'[A-Za-z0-9._ -]{1,80}', path.name):
        raise ValueError('Selected JavaScript tool basename is not reportable')
    digest = hashlib.sha256()
    size = 0
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(chunk)
            if size > info.st_size:
                raise ValueError('Selected JavaScript tool changed while hashing')
            digest.update(chunk)
    if size != info.st_size:
        raise ValueError('Selected JavaScript tool changed while hashing')
    # Deliberately omit full paths, the environment, raw tool output and flags.
    return {'file': path.name, 'bytes': size, 'sha256': digest.hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', required=True)
    parser.add_argument('--node-version', required=True)
    parser.add_argument('--browser', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'v[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(?:-[A-Za-z0-9.-]{1,40})?', args.node_version):
        raise ValueError('Invalid Node version; refusing to print raw tool output')
    data = {'schemaVersion': 1, 'platform': sys.platform,
            'node': {**identity(args.node), 'version': args.node_version},
            'browser': identity(args.browser)}
    print('Selected JS tool provenance (browser not launched): ' + json.dumps(data, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError):
        raise SystemExit('Cannot safely record selected JavaScript tool identity')
