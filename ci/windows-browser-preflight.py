#!/usr/bin/env python3
"""Read-only, fail-closed admission for ONE manual Windows Chrome startup probe.

No browser/PowerShell process, registry write, path expansion or policy bypass.
Only fixed categories, bounded numerical versions and executable hashes escape.
This point-in-time snapshot is not a Windows sandbox or an atomic launch guard.
"""
import ctypes
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import stat
import struct
import sys

DWORD = ctypes.c_uint32
POINTER = ctypes.c_void_p
MAX_TOOL_BYTES = 512 * 1024 * 1024
MAX_VERSION_BYTES = 1024 * 1024
POLICY_PATHS = {
    'chrome': r'SOFTWARE\Policies\Google\Chrome',
    'chromium': r'SOFTWARE\Policies\Chromium',
}
POLICY_NAMES = ('RemoteDebuggingAllowed', 'UserDataDir')
POLICY_CATEGORIES = {'absent', 'enabled', 'disabled', 'present', 'invalid', 'unreadable'}
SAFE_REASONS = {
    'UNSUPPORTED_HOST', 'UNSUPPORTED_SELECTION', 'BROWSER_CHANGED',
    'VERSION_UNAVAILABLE', 'VERSION_INVALID', 'POLICY_DENIED',
    'PROFILE_OVERRIDE_PRESENT', 'POLICY_INVALID', 'POLICY_UNREADABLE',
    'PREFLIGHT_UNAVAILABLE',
}


class Refused(Exception):
    pass


def validate_selection(value):
    # Reject UNC/device/relative paths before any filesystem operation. Do not
    # silently substitute Edge, a shell wrapper, another profile or another tool.
    path = PureWindowsPath(value)
    if (not path.is_absolute() or not re.fullmatch(r'[A-Za-z]:', path.drive) or
            path.name.lower() != 'chrome.exe' or '..' in path.parts):
        raise Refused('UNSUPPORTED_SELECTION')


def browser_identity(path):
    path = Path(path).resolve(strict=True)
    if path.name.lower() != 'chrome.exe':
        raise Refused('UNSUPPORTED_SELECTION')
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 64 <= before.st_size <= MAX_TOOL_BYTES:
            raise Refused('UNSUPPORTED_SELECTION')
        dos = stream.read(64)
        offset = struct.unpack_from('<I', dos, 60)[0]
        if dos[:2] != b'MZ' or not 64 <= offset <= min(1024 * 1024, before.st_size - 26):
            raise Refused('UNSUPPORTED_SELECTION')
        stream.seek(offset)
        pe = stream.read(26)
        optional_size, flags, magic = struct.unpack_from('<HHH', pe, 20)
        if (pe[:4] != b'PE\0\0' or struct.unpack_from('<H', pe, 4)[0] != 0x8664 or
                magic != 0x20b or optional_size < 2 or offset + 24 + optional_size > before.st_size or
                not flags & 0x0002 or flags & 0x2000):
            raise Refused('UNSUPPORTED_SELECTION')
        stream.seek(0)
        digest, size = hashlib.sha256(), 0
        while chunk := stream.read(min(1024 * 1024, before.st_size + 1 - size)):
            size += len(chunk)
            if size > before.st_size:
                raise Refused('BROWSER_CHANGED')
            digest.update(chunk)
        after = os.fstat(stream.fileno())
        if size != before.st_size or any(getattr(before, key) != getattr(after, key)
                for key in ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns')):
            raise Refused('BROWSER_CHANGED')
    return {'file': 'chrome.exe', 'architecture': 'x64', 'bytes': size, 'sha256': digest.hexdigest()}


class WindowsReaders:
    """Injected native read functions also permit browser-free tests on Linux."""

    def __init__(self, registry, version, advapi):
        self.registry = registry
        self.version = version
        self.query = advapi.RegQueryValueExW
        self.query.argtypes = [POINTER, ctypes.c_wchar_p, POINTER,
                               ctypes.POINTER(DWORD), POINTER, ctypes.POINTER(DWORD)]
        self.query.restype = ctypes.c_int32
        version.GetFileVersionInfoSizeW.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(DWORD)]
        version.GetFileVersionInfoSizeW.restype = DWORD
        version.GetFileVersionInfoW.argtypes = [ctypes.c_wchar_p, DWORD, DWORD, POINTER]
        version.GetFileVersionInfoW.restype = ctypes.c_int32
        version.VerQueryValueW.argtypes = [POINTER, ctypes.c_wchar_p,
                                         ctypes.POINTER(POINTER), ctypes.POINTER(DWORD)]
        version.VerQueryValueW.restype = ctypes.c_int32

    def fixed_versions(self, path):
        size = self.version.GetFileVersionInfoSizeW(str(path), ctypes.byref(DWORD()))
        if not 52 <= size <= MAX_VERSION_BYTES:
            raise Refused('VERSION_UNAVAILABLE')
        data = ctypes.create_string_buffer(size)
        if not self.version.GetFileVersionInfoW(str(path), 0, size, data):
            raise Refused('VERSION_UNAVAILABLE')
        address, length = POINTER(), DWORD()
        if not self.version.VerQueryValueW(data, '\\', ctypes.byref(address), ctypes.byref(length)):
            raise Refused('VERSION_UNAVAILABLE')
        start = ctypes.addressof(data)
        if length.value != 52 or address.value is None or not start <= address.value <= start + size - 52:
            raise Refused('VERSION_INVALID')
        values = struct.unpack('<13I', ctypes.string_at(address, 52))
        if values[0] != 0xFEEF04BD or values[1] != 0x00010000 or values[9] != 1:
            raise Refused('VERSION_INVALID')
        def components(high, low):
            return [high >> 16, high & 0xffff, low >> 16, low & 0xffff]
        return {'file_version': components(values[2], values[3]),
                'product_version': components(values[4], values[5])}

    def policy(self, brand, hive, name):
        registry = self.registry
        try:
            with registry.OpenKey(getattr(registry, hive), POLICY_PATHS[brand], 0,
                                  registry.KEY_QUERY_VALUE | registry.KEY_WOW64_64KEY) as key:
                kind, size = DWORD(), DWORD()
                status = self.query(int(key), name, None, ctypes.byref(kind), None, ctypes.byref(size))
                if status == 2:  # ERROR_FILE_NOT_FOUND: value absent in the opened key.
                    return 'absent'
                if status != 0:
                    return 'unreadable'
                # Inspect existence only. Never read, expand, log or use a profile override.
                if name == 'UserDataDir':
                    return 'present'
                if kind.value != registry.REG_DWORD or size.value != 4:
                    return 'invalid'
                value = DWORD()
                status = self.query(int(key), name, None, ctypes.byref(kind),
                                    ctypes.byref(value), ctypes.byref(size))
                if status != 0:
                    return 'unreadable'
                if kind.value != registry.REG_DWORD or size.value != 4 or value.value not in (0, 1):
                    return 'invalid'
                return 'enabled' if value.value == 1 else 'disabled'
        except FileNotFoundError:
            return 'absent'
        except OSError:
            return 'unreadable'


def load_readers():
    import winreg
    # System DLL search only; never load a similarly named DLL from the checkout,
    # selected browser directory, current directory or caller-controlled PATH.
    version = ctypes.WinDLL('version.dll', winmode=0x00000800)
    advapi = ctypes.WinDLL('advapi32.dll', winmode=0x00000800)
    return WindowsReaders(winreg, version, advapi)


def empty_report():
    return {'schemaVersion': 1, 'decision': 'REFUSE', 'browser': None, 'policies': {}, 'reasons': []}


def inspect_browser(path, readers):
    report = empty_report()
    try:
        before = browser_identity(path)
        versions = readers.fixed_versions(path)
        if (set(versions) != {'file_version', 'product_version'} or
                any(not isinstance(value, list) or len(value) != 4 or
                    any(type(part) is not int or not 0 <= part <= 65535 for part in value)
                    for value in versions.values())):
            raise Refused('VERSION_INVALID')
        report['browser'] = {**before, **versions}
        reasons = set()
        for brand in POLICY_PATHS:
            for hive in ('HKEY_LOCAL_MACHINE', 'HKEY_CURRENT_USER'):
                for name in POLICY_NAMES:
                    category = readers.policy(brand, hive, name)
                    if category not in POLICY_CATEGORIES:
                        category = 'unreadable'
                    report['policies'][brand + '.' + hive + '.' + name] = category
                    if category == 'disabled': reasons.add('POLICY_DENIED')
                    elif category == 'present': reasons.add('PROFILE_OVERRIDE_PRESENT')
                    elif category == 'invalid': reasons.add('POLICY_INVALID')
                    elif category == 'unreadable': reasons.add('POLICY_UNREADABLE')
        if browser_identity(path) != before:
            reasons.add('BROWSER_CHANGED')
        report['reasons'] = sorted(reasons)
        if not reasons:
            report['decision'] = 'ALLOW'
    except Refused as error:
        code = error.args[0] if error.args else None
        report['reasons'] = [code if code in SAFE_REASONS else 'PREFLIGHT_UNAVAILABLE']
    except Exception:
        # Exception messages/causes may contain paths or values. Never publish them.
        report['reasons'] = ['PREFLIGHT_UNAVAILABLE']
    return report


def main():
    report = empty_report()
    try:
        if sys.platform != 'win32' or ctypes.sizeof(POINTER) != 8 or len(sys.argv) != 1:
            raise Refused('UNSUPPORTED_HOST')
        browser = os.environ.get('KMPYT_CHROMIUM', '')
        validate_selection(browser)
        report = inspect_browser(Path(browser), load_readers())
    except Refused as error:
        code = error.args[0] if error.args else None
        report['reasons'] = [code if code in SAFE_REASONS else 'PREFLIGHT_UNAVAILABLE']
    except Exception:
        report['reasons'] = ['PREFLIGHT_UNAVAILABLE']
    print('Windows startup preflight (browser not launched): ' + json.dumps(report, sort_keys=True))
    return 0 if report['decision'] == 'ALLOW' else 78


if __name__ == '__main__':
    sys.exit(main())
