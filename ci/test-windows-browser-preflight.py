#!/usr/bin/env python3
"""Authored PE bytes and mocked Win32 calls only; no native/browser/registry use."""
import contextlib
import ctypes
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('windows_preflight', ROOT / 'ci/windows-browser-preflight.py')
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)
PRIVATE = 'AUTHORED_PRIVATE_SENTINEL https://private.invalid/?signature=fixture'


def set_dword(pointer, value):
    ctypes.cast(pointer, ctypes.POINTER(PROBE.DWORD)).contents.value = value


class Function:
    def __init__(self, function):
        self.function, self.calls = function, []

    def __call__(self, *args):
        self.calls.append(args)
        return self.function(*args)


class Version:
    def __init__(self, size=128, length=52, address_delta=8, success=True, query_success=True, values=None):
        self.size, self.length, self.address_delta = size, length, address_delta
        self.success, self.query_success = success, query_success
        self.values = values or [0xFEEF04BD, 0x00010000, 156 << 16, (8078 << 16) | 80,
                                156 << 16, (8078 << 16) | 80, 0x3f, 0, 0x40004, 1, 0, 0, 0]
        self.GetFileVersionInfoSizeW = Function(lambda path, ignored: self.size)
        self.GetFileVersionInfoW = Function(self.read)
        self.VerQueryValueW = Function(self.query)

    def read(self, path, handle, size, buffer):
        if not self.success:
            return 0
        self.buffer = buffer
        ctypes.memmove(ctypes.addressof(buffer) + 8, struct.pack('<13I', *self.values), 52)
        return 1

    def query(self, buffer, name, address, length):
        if not self.query_success:
            return 0
        target = None if self.address_delta is None else ctypes.addressof(buffer) + self.address_delta
        ctypes.cast(address, ctypes.POINTER(PROBE.POINTER)).contents.value = target
        set_dword(length, self.length)
        return 1


class Key:
    def __init__(self, registry, identity):
        self.registry, self.identity = registry, identity

    def __int__(self):
        return self.identity

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.registry.closed.append(self.identity)


class Registry:
    HKEY_LOCAL_MACHINE = 1
    HKEY_CURRENT_USER = 2
    KEY_QUERY_VALUE = 0x0001
    KEY_WOW64_64KEY = 0x0100
    REG_DWORD = 4

    def __init__(self, failure=None):
        self.failure, self.opened, self.closed = failure, [], []

    def OpenKey(self, *args):
        if self.failure:
            raise self.failure
        self.opened.append(args)
        return Key(self, len(self.opened))


class Advapi:
    def __init__(self, first=(2, 0, 0, 0), second=None):
        self.first, self.second = first, second or first
        self.RegQueryValueExW = Function(self.query)

    def query(self, key, name, reserved, kind, data, size):
        status, value_type, length, value = self.first if data is None else self.second
        set_dword(kind, value_type)
        set_dword(size, length)
        if data is not None and status == 0 and length == 4:
            set_dword(data, value)
        return status


class WindowsPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / '.work/tmp')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'chrome.exe'
        data = bytearray(512)
        data[:2] = b'MZ'
        struct.pack_into('<I', data, 60, 128)
        data[128:132] = b'PE\0\0'
        struct.pack_into('<H', data, 132, 0x8664)
        struct.pack_into('<HHH', data, 148, 240, 0x0002, 0x20b)
        data[400:400 + len(PRIVATE)] = PRIVATE.encode()
        self.path.write_bytes(data)
        # These denials cover the entire test body, including error paths.
        for target in ('subprocess.Popen', 'os.system', 'socket.socket'):
            denial = patch(target, side_effect=AssertionError('Unexpected process/network use'))
            denial.start()
            self.addCleanup(denial.stop)

    def readers(self, version=None, registry=None, advapi=None):
        return PROBE.WindowsReaders(registry or Registry(), version or Version(), advapi or Advapi())

    def assert_private_absent(self, report):
        text = json.dumps(report, sort_keys=True)
        for private in (PRIVATE, 'AUTHORED_PRIVATE_SENTINEL', 'private.invalid', str(self.path.parent),
                        'signature=fixture', 'Traceback'):
            self.assertNotIn(private, text)
        self.assertLess(len(text), 4096)

    def test_selection_rejects_unc_device_wrappers_and_relative_paths_before_reads(self):
        for path in (r'\\server\share\chrome.exe', r'\\?\C:\chrome.exe', r'\\.\C:\chrome.exe',
                     r'C:chrome.exe', r'chrome.exe', r'C:\one\..\chrome.exe',
                     r'C:\owned\chrome.exe:stream', r'C:\owned\chrome.cmd', r'C:\owned\msedge.exe'):
            with self.subTest(path=path), self.assertRaises(PROBE.Refused):
                PROBE.validate_selection(path)
        for path in (r'C:\Program Files\Google\Chrome\Application\chrome.exe', 'D:/owned/Chrome.exe'):
            PROBE.validate_selection(path)

    def test_identity_reads_bounded_pe64_without_launch_or_path_disclosure(self):
        original = self.path.read_bytes()
        result = PROBE.browser_identity(self.path)
        self.assertEqual(result, {'file': 'chrome.exe', 'architecture': 'x64', 'bytes': len(original),
                                 'sha256': hashlib.sha256(original).hexdigest()})
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])
        self.assert_private_absent(result)

    def test_identity_rejects_invalid_truncated_wrong_architecture_and_dll_headers(self):
        original = self.path.read_bytes()
        changes = ((0, b'NO'), (60, struct.pack('<I', 63)), (60, struct.pack('<I', 1024 * 1024 + 1)),
                   (128, b'NOT!'), (132, struct.pack('<H', 0x14c)), (148, b'\x01\x00'),
                   (148, b'\xff\xff'), (150, b'\x00\x00'), (150, b'\x02\x20'), (152, b'\x0b\x01'))
        for offset, replacement in changes:
            with self.subTest(offset=offset, replacement=replacement):
                data = bytearray(original)
                data[offset:offset + len(replacement)] = replacement
                self.path.write_bytes(data)
                with self.assertRaises(PROBE.Refused):
                    PROBE.browser_identity(self.path)
        for size in (0, 63, 140):
            self.path.write_bytes(original[:size])
            with self.assertRaises(PROBE.Refused):
                PROBE.browser_identity(self.path)
        self.path.write_bytes(original)
        with patch.object(PROBE, 'MAX_TOOL_BYTES', 511), self.assertRaises(PROBE.Refused):
            PROBE.browser_identity(self.path)

    def test_native_version_reads_only_bounded_fixed_numerical_resource(self):
        version = Version()
        result = self.readers(version=version).fixed_versions(self.path)
        self.assertEqual(result, {'file_version': [156, 0, 8078, 80], 'product_version': [156, 0, 8078, 80]})
        self.assertEqual(version.VerQueryValueW.calls[0][1], '\\')
        self.assertEqual(len(version.GetFileVersionInfoSizeW.calls), 1)
        self.assertEqual(len(version.GetFileVersionInfoW.calls), 1)
        self.assert_private_absent(result)

    def test_native_version_refuses_missing_or_oversized_buffer_before_allocation(self):
        for size in (0, 51, PROBE.MAX_VERSION_BYTES + 1):
            version = Version(size=size)
            with self.subTest(size=size), self.assertRaises(PROBE.Refused):
                self.readers(version=version).fixed_versions(self.path)
            self.assertEqual(version.GetFileVersionInfoW.calls, [])

    def test_native_version_rejects_failed_apis_bad_pointers_and_lengths(self):
        for options in ({'success': False}, {'query_success': False}, {'length': 51}, {'length': 53},
                        {'address_delta': None}, {'address_delta': -1}, {'address_delta': 77}):
            with self.subTest(options=options), self.assertRaises(PROBE.Refused):
                self.readers(version=Version(**options)).fixed_versions(self.path)

    def test_native_version_rejects_bad_signature_structure_and_non_application(self):
        for offset, value in ((0, 1), (1, 0), (9, 2)):
            version = Version()
            version.values[offset] = value
            with self.subTest(offset=offset), self.assertRaises(PROBE.Refused):
                self.readers(version=version).fixed_versions(self.path)

    def test_system_dll_loader_never_searches_checkout_browser_directory_or_path(self):
        version, advapi, registry = Version(), Advapi(), Registry()
        with patch.dict(sys.modules, {'winreg': registry}), \
                patch.object(PROBE.ctypes, 'WinDLL', create=True, side_effect=[version, advapi]) as load:
            readers = PROBE.load_readers()
        self.assertIs(readers.registry, registry)
        self.assertEqual(load.call_args_list, [unittest.mock.call('version.dll', winmode=0x00000800),
                                              unittest.mock.call('advapi32.dll', winmode=0x00000800)])

    def test_policy_keys_missing_and_access_denied_are_distinct(self):
        for error, expected in ((FileNotFoundError(PRIVATE), 'absent'), (PermissionError(PRIVATE), 'unreadable'),
                                (OSError(PRIVATE), 'unreadable')):
            registry, advapi = Registry(failure=error), Advapi()
            self.assertEqual(self.readers(registry=registry, advapi=advapi).policy(
                'chrome', 'HKEY_LOCAL_MACHINE', 'RemoteDebuggingAllowed'), expected)
            self.assertEqual(advapi.RegQueryValueExW.calls, [])

    def test_profile_policy_reads_presence_only_even_for_huge_or_invalid_value(self):
        for kind, size in ((1, 100), (2, 0xffffffff), (3, 0), (4, 4)):
            registry, advapi = Registry(), Advapi(first=(0, kind, size, 0))
            result = self.readers(registry=registry, advapi=advapi).policy('chrome', 'HKEY_CURRENT_USER', 'UserDataDir')
            self.assertEqual(result, 'present')
            self.assertEqual(len(advapi.RegQueryValueExW.calls), 1)
            self.assertIsNone(advapi.RegQueryValueExW.calls[0][4], 'Never allocate/read a profile policy value')
            self.assertEqual(registry.closed, [1])

    def test_remote_debugging_policy_accepts_only_exact_four_byte_boolean_dword(self):
        for spec, expected, reads in (((0, 4, 4, 0), 'disabled', 2), ((0, 4, 4, 1), 'enabled', 2),
                                     ((0, 4, 4, 2), 'invalid', 2), ((0, 4, 4, 0xffffffff), 'invalid', 2),
                                     ((0, 1, 4, 1), 'invalid', 1), ((0, 4, 8, 1), 'invalid', 1),
                                     ((0, 4, 0, 1), 'invalid', 1), ((2, 0, 0, 0), 'absent', 1),
                                     ((5, 0, 0, 0), 'unreadable', 1), ((234, 0, 0, 0), 'unreadable', 1)):
            with self.subTest(spec=spec):
                registry, advapi = Registry(), Advapi(first=spec)
                result = self.readers(registry=registry, advapi=advapi).policy('chrome', 'HKEY_LOCAL_MACHINE', 'RemoteDebuggingAllowed')
                self.assertEqual(result, expected)
                self.assertEqual(len(advapi.RegQueryValueExW.calls), reads)
                self.assertEqual(registry.closed, [1])

    def test_policy_change_between_metadata_and_value_is_not_retried_or_admitted(self):
        for second, expected in (((234, 1, 1024, 0), 'unreadable'), ((2, 0, 0, 0), 'unreadable'),
                                 ((5, 0, 0, 0), 'unreadable'), ((0, 1, 4, 1), 'invalid'), ((0, 4, 0, 0), 'invalid')):
            advapi = Advapi(first=(0, 4, 4, 1), second=second)
            self.assertEqual(self.readers(advapi=advapi).policy('chrome', 'HKEY_LOCAL_MACHINE', 'RemoteDebuggingAllowed'), expected)
            self.assertEqual(len(advapi.RegQueryValueExW.calls), 2)

    def test_all_eight_fixed_policies_are_checked_in_native64_read_only_view(self):
        registry = Registry()
        report = PROBE.inspect_browser(self.path, self.readers(registry=registry))
        self.assertEqual(report['decision'], 'ALLOW')
        self.assertEqual(report['reasons'], [])
        self.assertEqual(len(report['policies']), 8)
        self.assertEqual(set(report['policies'].values()), {'absent'})
        self.assertEqual(registry.opened, [(hive, path, 0, 0x0101) for path in PROBE.POLICY_PATHS.values()
                                          for hive in (registry.HKEY_LOCAL_MACHINE, registry.HKEY_CURRENT_USER) for _ in range(2)])
        self.assertEqual(registry.closed, list(range(1, 9)))
        self.assert_private_absent(report)

    def test_denial_profile_override_invalid_and_unreadable_each_refuse_without_value_disclosure(self):
        for category, reason in (('disabled', 'POLICY_DENIED'), ('present', 'PROFILE_OVERRIDE_PRESENT'),
                                 ('invalid', 'POLICY_INVALID'), ('unreadable', 'POLICY_UNREADABLE'), (PRIVATE, 'POLICY_UNREADABLE')):
            readers = self.readers()
            with patch.object(readers, 'policy', return_value=category):
                report = PROBE.inspect_browser(self.path, readers)
            self.assertEqual(report['decision'], 'REFUSE')
            self.assertEqual(report['reasons'], [reason])
            self.assert_private_absent(report)

    def test_enabled_remote_policy_with_no_profile_override_is_admitted(self):
        readers = self.readers()
        with patch.object(readers, 'policy', side_effect=lambda brand, hive, name: 'enabled' if name == 'RemoteDebuggingAllowed' else 'absent'):
            report = PROBE.inspect_browser(self.path, readers)
        self.assertEqual(report['decision'], 'ALLOW')
        self.assertEqual(report['reasons'], [])

    def test_rehash_detects_executable_change_during_metadata_snapshot(self):
        readers = self.readers()
        def changed(*args):
            data = bytearray(self.path.read_bytes())
            data[-1] ^= 1
            self.path.write_bytes(data)
            return {'file_version': [1, 2, 3, 4], 'product_version': [1, 2, 3, 4]}
        with patch.object(readers, 'fixed_versions', side_effect=changed):
            report = PROBE.inspect_browser(self.path, readers)
        self.assertEqual(report['decision'], 'REFUSE')
        self.assertEqual(report['reasons'], ['BROWSER_CHANGED'])
        self.assert_private_absent(report)

    def test_arbitrary_native_exceptions_versions_and_reason_strings_cannot_leak(self):
        readers = self.readers()
        for error in (OSError(PRIVATE), ValueError(PRIVATE), PROBE.Refused(PRIVATE)):
            with patch.object(readers, 'fixed_versions', side_effect=error):
                report = PROBE.inspect_browser(self.path, readers)
            self.assertEqual(report['decision'], 'REFUSE')
            self.assertEqual(report['reasons'], ['PREFLIGHT_UNAVAILABLE'])
            self.assert_private_absent(report)
        for versions in ({'file_version': PRIVATE}, {'file_version': [1, 2, 3, 4], 'product_version': [True, 2, 3, 4]},
                         {'file_version': [1, 2, 3, 4], 'product_version': [1, 2, 3, 65536]}):
            with patch.object(readers, 'fixed_versions', return_value=versions):
                report = PROBE.inspect_browser(self.path, readers)
            self.assertEqual(report['reasons'], ['VERSION_INVALID'])
            self.assert_private_absent(report)

    def test_non_windows_cli_refuses_before_native_api_or_selection_reads(self):
        output = io.StringIO()
        with patch.object(PROBE.sys, 'platform', 'linux'), patch.object(PROBE, 'load_readers') as load, \
                patch.object(PROBE, 'inspect_browser') as inspect, contextlib.redirect_stdout(output):
            status = PROBE.main()
        self.assertEqual(status, 78)
        load.assert_not_called()
        inspect.assert_not_called()
        self.assertIn('UNSUPPORTED_HOST', output.getvalue())
        self.assertNotIn(PRIVATE, output.getvalue())


if __name__ == '__main__':
    unittest.main(verbosity=2)
