#!/usr/bin/env python3
"""Authored strings and mocked APIs only, including when run on Windows.

No native DLL, directory creation, subprocess, browser, registry or network use.
Passing this suite is NOT native known-folder or browser-startup evidence.
"""
import ast
import contextlib
import copy
import ctypes
import importlib.util
import io
import json
import os
from pathlib import Path, PureWindowsPath
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('known_folder', ROOT / 'ci/windows-known-folder-diagnostic.py')
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)
CHECKOUT = PureWindowsPath(r'D:\a\kmpYT\kmpYT')
HOME_LOCAL = CHECKOUT / '.work/home/AppData/Local'
LOCAL_ENV = CHECKOUT / '.work/data'
PRIVATE = 'AUTHORED_PRIVATE_SENTINEL https://private.invalid/?signature=fixture'


def environment():
    return {
        'KMPYT_CI_SCOPED_ENV': '1', 'HOME': str(CHECKOUT / '.work/home'),
        'USERPROFILE': str(CHECKOUT / '.work/home'), 'LOCALAPPDATA': str(LOCAL_ENV),
        'APPDATA': str(CHECKOUT / '.work/config'),
        **{key: str(CHECKOUT / '.work/tmp') for key in ('TEMP', 'TMP', 'TMPDIR')},
    }


def winerror(number):
    error = OSError(PRIVATE)
    error.winerror = number
    return error


class Function:
    def __init__(self, function):
        self.function, self.calls = function, []

    def __call__(self, *args):
        self.calls.append(args)
        return self.function(*args)


class Shell:
    def __init__(self, responses=None):
        self.responses = responses or ((0, str(LOCAL_ENV)), (0, str(HOME_LOCAL)))
        self.zeroed = []
        self.SHGetFolderPathW = Function(self.lookup)

    def lookup(self, hwnd, flags, token, current, buffer):
        self.zeroed.append(len(buffer) == 260 and all(char == '\0' for char in buffer))
        response = self.responses[len(self.SHGetFolderPathW.calls) - 1]
        if isinstance(response, Exception):
            raise response
        hresult, value = response
        if value is not None:
            for index, char in enumerate(value):
                buffer[index] = char
        return ctypes.c_int32(hresult).value


class Kernel:
    def __init__(self, attributes=None, drive=3):
        self.values = {PROBE.path_key(PureWindowsPath(key)): value for key, value in (attributes or {}).items()}
        self.drive, self.last_error = drive, 0
        self.GetFileAttributesW = Function(self.attributes)
        self.GetDriveTypeW = Function(lambda root: self.drive)

    def attributes(self, path):
        value = self.values.get(PROBE.path_key(PureWindowsPath(path)), PROBE.DIRECTORY_ATTRIBUTE)
        if isinstance(value, Exception):
            raise value
        if isinstance(value, tuple):
            self.last_error = value[0]
            return PROBE.INVALID_ATTRIBUTES
        return value

    def set_error(self, value):
        self.last_error = value


class KnownFolderTests(unittest.TestCase):
    def setUp(self):
        # Deny side effects in the entire test body, also on actual Windows.
        for target in ('subprocess.Popen', 'subprocess.run', 'subprocess.check_output',
                       'os.system', 'os.popen', 'socket.socket', 'socket.create_connection',
                       'os.mkdir', 'os.makedirs', 'os.remove', 'os.unlink', 'os.rmdir',
                       'os.rename', 'os.replace', 'os.symlink', 'os.link', 'pathlib.Path.resolve'):
            denial = patch(target, side_effect=AssertionError('Unexpected side effect'))
            denial.start()
            self.addCleanup(denial.stop)
        native = patch.object(PROBE.ctypes, 'WinDLL', create=True, side_effect=AssertionError('Unexpected native load'))
        native.start()
        self.addCleanup(native.stop)

    def readers(self, responses=None, attributes=None, drive=3):
        shell, kernel = Shell(responses), Kernel(attributes, drive)
        readers = PROBE.NativeReaders(shell, kernel, lambda: kernel.last_error, kernel.set_error)
        return readers, shell, kernel

    def inspect(self, responses=None, attributes=None, drive=3, env=None, root=None, cwd=None):
        readers, shell, kernel = self.readers(responses, attributes, drive)
        report = PROBE.inspect(str(CHECKOUT) if root is None else root,
                               str(CHECKOUT) if cwd is None else cwd,
                               environment() if env is None else env, lambda: readers)
        self.assert_private_absent(report)
        return report, shell, kernel

    def assert_private_absent(self, report):
        text = PROBE.encode_report(report)
        self.assertEqual(PROBE.parse_report(text), report)
        self.assertLessEqual(len(text.encode('ascii')), 4096)
        for private in (PRIVATE, 'AUTHORED_PRIVATE_SENTINEL', 'private.invalid', 'signature=fixture',
                        str(CHECKOUT), 'D:', 'C:', 'Traceback', 'username-secret', 'pathHash', 'sha256'):
            self.assertNotIn(private, text)

    def assert_only_inside_reads(self, kernel):
        for (value,) in kernel.GetFileAttributesW.calls:
            self.assertTrue(PROBE.inside(PROBE.local_path(value), CHECKOUT))

    def test_explicit_native_types_two_zeroed_calls_exact_flags_no_create(self):
        readers, shell, kernel = self.readers()
        report = PROBE.inspect(str(CHECKOUT), str(CHECKOUT), environment(), lambda: readers)
        self.assertEqual(shell.SHGetFolderPathW.argtypes,
                         [ctypes.c_void_p, ctypes.c_int32, ctypes.c_void_p, ctypes.c_uint32, ctypes.POINTER(ctypes.c_wchar)])
        self.assertIs(shell.SHGetFolderPathW.restype, ctypes.c_int32)
        self.assertEqual(ctypes.sizeof(shell.SHGetFolderPathW.restype), 4)
        self.assertEqual(kernel.GetFileAttributesW.argtypes, [ctypes.c_wchar_p])
        self.assertIs(kernel.GetFileAttributesW.restype, ctypes.c_uint32)
        self.assertEqual(kernel.GetDriveTypeW.argtypes, [ctypes.c_wchar_p])
        self.assertIs(kernel.GetDriveTypeW.restype, ctypes.c_uint32)
        self.assertEqual([args[:4] for args in shell.SHGetFolderPathW.calls],
                         [(None, 0x001c, None, 0), (None, 0x401c, None, 0)])
        self.assertEqual(shell.zeroed, [True, True])
        self.assertTrue(all(not args[1] & 0x8000 for args in shell.SHGetFolderPathW.calls))
        self.assertEqual(kernel.GetDriveTypeW.calls, [('D:\\',)])
        self.assertEqual(report['counts']['knownFolderAttempts'], 2)
        self.assert_private_absent(report)

    def test_loader_binds_only_fixed_system32_libraries_and_dependencies(self):
        shell, kernel = Shell(), Kernel()
        with patch.object(PROBE.ctypes, 'WinDLL', side_effect=[shell, kernel]) as loader, \
                patch.object(PROBE.ctypes, 'get_last_error', create=True, return_value=0), \
                patch.object(PROBE.ctypes, 'set_last_error', create=True):
            readers = PROBE.load_readers()
        self.assertIs(readers.folder, shell.SHGetFolderPathW)
        self.assertEqual(loader.call_args_list, [
            unittest.mock.call('shell32.dll', winmode=0x00000800, use_last_error=True),
            unittest.mock.call('kernel32.dll', winmode=0x00000800, use_last_error=True),
        ])
        self.assertEqual(shell.SHGetFolderPathW.calls, [])
        self.assertEqual(kernel.GetFileAttributesW.calls, [])

    def test_lookup_rejects_every_non_allowlisted_flag_before_a_call(self):
        readers, shell, _ = self.readers()
        for flags in (0, 0x001c | 0x8000, 0x401c | 0x8000, True, '28', -1):
            with self.subTest(flags=flags), self.assertRaises(ValueError):
                readers.lookup(flags)
        self.assertEqual(shell.SHGetFolderPathW.calls, [])

    def test_success_reports_path_classes_not_values_or_browser_admission(self):
        report, _, kernel = self.inspect()
        self.assertEqual(report['outcome'], 'OBSERVED')
        self.assertEqual(report['current']['pathClass'], 'EXACT_SCOPED_LOCALAPPDATA_ENV')
        self.assertEqual(report['dontVerify']['pathClass'], 'EXACT_SCOPED_HOME_APPDATA_LOCAL')
        self.assertEqual(report['current']['metadata'], PROBE.metadata('DIRECTORY', at='SELF'))
        self.assertEqual(report['current']['hresult'], '00000000')
        self.assertIs(report['current']['failed'], False)
        self.assertIs(report['noBrowserAdmission'], True)
        self.assertIs(report['browserLaunched'], False)
        self.assertIs(report['environmentChanged'], False)
        self.assertEqual(report['directoriesCreated'], 0)
        self.assert_only_inside_reads(kernel)

    def test_normal_failure_and_dont_verify_missing_owned_path_never_repairs_or_admits(self):
        env = environment()
        before = dict(env)
        report, shell, kernel = self.inspect(
            ((0x80070002, None), (0, str(HOME_LOCAL))), {str(HOME_LOCAL): (2,)}, env=env)
        self.assertEqual(env, before)
        self.assertEqual(report['outcome'], 'OBSERVED')  # Completed observation, not startup success.
        self.assertEqual(report['current']['hresult'], '80070002')
        self.assertIs(report['current']['failed'], True)
        self.assertEqual(report['current']['pathClass'], 'NO_USABLE_PATH')
        self.assertEqual(report['current']['metadata']['state'], 'NOT_PROBED')
        self.assertEqual(report['dontVerify']['metadata'], PROBE.metadata('MISSING', 2, 'SELF'))
        self.assertEqual(report['dontVerify']['pathClass'], 'EXACT_SCOPED_HOME_APPDATA_LOCAL')
        self.assertEqual(len(shell.SHGetFolderPathW.calls), 2)
        self.assertIs(report['noBrowserAdmission'], True)
        self.assertNotIn(str(LOCAL_ENV), [args[0] for args in kernel.GetFileAttributesW.calls])
        # A lexical LOCALAPPDATA match is not an extra assertion that it exists.

    def test_failed_hresult_is_exact_and_output_buffer_is_never_read(self):
        class UndefinedBuffer:
            def __getitem__(self, key):
                raise AssertionError('Undefined failed-call output was read')
        for unsigned in (0x80070002, 0x80070005, 0x80004005, 0xffffffff):
            readers, shell, _ = self.readers()
            shell.SHGetFolderPathW.function = lambda *args: ctypes.c_int32(unsigned).value
            with self.subTest(hresult=unsigned), patch.object(PROBE.ctypes, 'create_unicode_buffer', return_value=UndefinedBuffer()):
                result, private_path = readers.lookup(0x001c)
            self.assertEqual(result['hresult'], f'{unsigned:08X}')
            self.assertIs(result['failed'], True)
            self.assertIsNone(result['nulTerminated'])
            self.assertIsNone(result['nonempty'])
            self.assertEqual(result['buffer'], 'NOT_READ_FAILED_HRESULT')
            self.assertIsNone(private_path)

    def test_positive_nonzero_hresult_is_not_s_ok_and_never_supplies_a_path(self):
        for value in (1, 0x7fffffff):
            report, _, _ = self.inspect(((value, PRIVATE), (value, PRIVATE)))
            for name in ('current', 'dontVerify'):
                self.assertEqual(report[name]['hresult'], f'{value:08X}')
                self.assertIs(report[name]['failed'], False)
                self.assertEqual(report[name]['buffer'], 'NOT_READ_NONZERO_HRESULT')
                self.assertIsNone(report[name]['nulTerminated'])
                self.assertEqual(report[name]['pathClass'], 'NO_USABLE_PATH')

    def test_empty_output_is_distinct_from_a_failing_hresult(self):
        report, _, _ = self.inspect(((0, ''), (0x80070002, None)))
        self.assertEqual(report['current']['buffer'], 'EMPTY')
        self.assertEqual(report['current']['hresult'], '00000000')
        self.assertIs(report['current']['nulTerminated'], True)
        self.assertIs(report['current']['nonempty'], False)
        self.assertEqual(report['dontVerify']['buffer'], 'NOT_READ_FAILED_HRESULT')

    def test_unterminated_buffer_is_incomplete_and_not_a_truncated_path(self):
        report, _, _ = self.inspect(((0, 'X' * 260), (0, '')))
        self.assertEqual(report['outcome'], 'INCOMPLETE')
        self.assertEqual(report['current']['buffer'], 'UNTERMINATED')
        self.assertIs(report['current']['nulTerminated'], False)
        self.assertEqual(report['current']['pathClass'], 'NO_USABLE_PATH')

    def test_nonzero_suffix_after_nul_and_invalid_utf16_are_malformed(self):
        for value in (str(HOME_LOCAL) + '\0' + PRIVATE, '\0' + PRIVATE, str(CHECKOUT) + '\\x\ud800'):
            with self.subTest(value_kind=len(value)):
                report, _, _ = self.inspect(((0, value), (0, '')))
            self.assertEqual(report['current']['buffer'], 'MALFORMED')
            self.assertEqual(report['current']['pathClass'], 'NO_USABLE_PATH')
            self.assertEqual(report['outcome'], 'INCOMPLETE')

    def test_invalid_hresult_type_or_width_never_becomes_a_signed_success(self):
        for value in (True, None, PRIVATE, 0x80000000, -0x80000001):
            readers, shell, _ = self.readers()
            shell.SHGetFolderPathW.function = lambda *args: value
            with self.subTest(kind=type(value).__name__):
                call, private_path = readers.lookup(0x001c)
            self.assertEqual(call['buffer'], 'CALL_ERROR')
            self.assertIsNone(call['hresult'])
            self.assertIsNone(private_path)

    def test_supported_paths_compare_windows_components_case_insensitively(self):
        for value, expected in (
            ('d:/A/KMPYT/kmpyt/.WORK/HOME/appdata/LOCAL/', 'EXACT_SCOPED_HOME_APPDATA_LOCAL'),
            ('d:/a/KMPYT/KMPYT/.work/DATA', 'EXACT_SCOPED_LOCALAPPDATA_ENV'),
            (str(CHECKOUT / 'owned/with space'), 'OTHER_INSIDE_REPOSITORY'),
            (str(CHECKOUT), 'OTHER_INSIDE_REPOSITORY'),
            (str(CHECKOUT) + '-other\\username-secret', 'OUTSIDE_REPOSITORY_LOCAL'),
            (r'C:\Users\username-secret\AppData\Local', 'OUTSIDE_REPOSITORY_LOCAL'),
        ):
            with self.subTest(expected=expected):
                self.assertEqual(PROBE.path_class(PROBE.local_path(value), CHECKOUT), expected)

    def test_unsupported_path_forms_reject_before_any_metadata_read(self):
        values = (
            r'\\server\share\Local', r'\\?\D:\a\kmpYT\kmpYT\Local', r'\\.\D:\Local',
            r'\Device\HarddiskVolume1\Local', 'D:relative', r'\root-relative', 'relative',
            str(CHECKOUT / 'x') + '\\..\\escape', str(CHECKOUT) + '\\.\\Local',
            str(CHECKOUT) + '\\\\Local', str(CHECKOUT) + '\\Local.',
            str(CHECKOUT) + '\\Local ', str(CHECKOUT) + '\\Local:stream',
            str(CHECKOUT) + '\\CON.txt', str(CHECKOUT) + '\\LPT9', str(CHECKOUT) + '\\AUX',
            str(CHECKOUT) + '\\CONOUT$', str(CHECKOUT) + '\\x?y', str(CHECKOUT) + '\\x|y',
            str(CHECKOUT) + '\\x\ny', str(CHECKOUT) + '\\x\0y', str(CHECKOUT) + '\\x\x7fy',
            str(CHECKOUT) + '\\İ', str(CHECKOUT) + '\\i\u0307', 'D:\\' + 'x' * 257,
        )
        for value in values:
            with self.subTest(kind=len(value)):
                self.assertIsNone(PROBE.local_path(value))
                if '\0' not in value and len(value) < 260:
                    report, _, kernel = self.inspect(((0, value), (0, '')))
                    self.assertEqual(report['current']['pathClass'], 'NONLOCAL_OR_UNSUPPORTED')
                    self.assertEqual(report['current']['metadata']['state'], 'NOT_PROBED')
                    self.assertNotIn(value, [args[0] for args in kernel.GetFileAttributesW.calls])
                    self.assert_only_inside_reads(kernel)

    def test_every_outside_return_remains_not_probed_without_drive_or_ancestor_queries(self):
        for value in (r'C:\Users\username-secret\Local', r'Z:\mapped-network\Local',
                      str(CHECKOUT) + '-suffix\\Local', r'D:\other\Local'):
            with self.subTest(kind=len(value)):
                report, _, kernel = self.inspect(((0, value), (0, value)))
                self.assertEqual(report['current']['pathClass'], 'OUTSIDE_REPOSITORY_LOCAL')
                self.assertEqual(report['current']['metadata'], PROBE.metadata())
                self.assertEqual(kernel.GetDriveTypeW.calls, [('D:\\',)])
                self.assert_only_inside_reads(kernel)

    def test_environment_mismatch_refuses_before_loading_or_reading_and_preserves_values(self):
        for key in environment():
            env = environment()
            env[key] = PRIVATE
            before = dict(env)
            with self.subTest(variable=key), patch.object(PROBE, 'load_readers') as factory:
                report = PROBE.inspect(str(CHECKOUT), str(CHECKOUT), env, factory)
            factory.assert_not_called()
            self.assertEqual(env, before)
            self.assertEqual(report['reason'], 'ENVIRONMENT_MISMATCH')
            self.assertTrue(all(value == 0 for value in report['counts'].values()))
            self.assert_private_absent(report)

    def test_absent_inputs_unsupported_roots_and_wrong_cwd_refuse_without_guessing(self):
        cases = [(str(CHECKOUT), str(CHECKOUT), {})]
        cases.extend((root, str(CHECKOUT), environment()) for root in ('D:\\', r'\\host\share', PRIVATE, None))
        cases.append((str(CHECKOUT), str(CHECKOUT) + '-other', environment()))
        for root, cwd, env in cases:
            with self.subTest(kind=type(root).__name__), patch.object(PROBE, 'load_readers') as factory:
                report = PROBE.inspect(root, cwd, env, factory)
            factory.assert_not_called()
            self.assertEqual(report['reason'], 'ENVIRONMENT_MISMATCH')
            self.assert_private_absent(report)

    def test_environment_accepts_case_and_slash_variants_without_mutating_or_expanding(self):
        env = {key: value.lower().replace('\\', '/') for key, value in environment().items()}
        env.update({'UNRELATED_PRIVATE_VARIABLE': PRIVATE, 'KMPYT_CI_PROJECT_ROOT': PRIVATE})
        before = dict(env)
        report, _, _ = self.inspect(env=env, cwd='d:/A/KMPYT/KMPYT/')
        self.assertTrue(all(report['environmentChecks'].values()))
        self.assertEqual(env, before)
        # Root is independently the executing source + CWD, not an override.

    def test_loader_errors_are_finite_without_exception_strings(self):
        with patch.object(PROBE, 'load_readers', side_effect=winerror(5)) as factory:
            report = PROBE.inspect(str(CHECKOUT), str(CHECKOUT), environment(), factory)
        self.assertEqual(report['reason'], 'NATIVE_UNAVAILABLE')
        self.assertEqual(report['counts']['knownFolderAttempts'], 0)
        self.assert_private_absent(report)

    def test_non_fixed_or_unknown_drive_refuses_before_attributes_and_shell(self):
        for drive in (0, 1, 2, 4, 5, 6):
            with self.subTest(drive=drive):
                report, shell, kernel = self.inspect(drive=drive)
            self.assertEqual(report['reason'], 'UNSUPPORTED_DRIVE')
            self.assertEqual(kernel.GetFileAttributesW.calls, [])
            self.assertEqual(shell.SHGetFolderPathW.calls, [])

    def test_root_itself_must_be_an_observed_non_reparse_directory(self):
        for value, state in ((0x400 | 0x10, 'REPARSE_REJECTED'), (0, 'NON_DIRECTORY'),
                             ((2,), 'MISSING'), ((5,), 'ACCESS_DENIED'), ((32,), 'OTHER_ERROR')):
            with self.subTest(state=state):
                report, shell, kernel = self.inspect(attributes={str(CHECKOUT): value})
            self.assertEqual(report['reason'], 'ROOT_UNSAFE')
            self.assertEqual(report['boundary']['root']['state'], state)
            self.assertEqual(kernel.GetFileAttributesW.calls, [(str(CHECKOUT),)])
            self.assertEqual(shell.SHGetFolderPathW.calls, [])

    def test_attribute_results_distinguish_missing_denied_nondirectory_and_other_error(self):
        for value, state, error in ((0x10, 'DIRECTORY', None), (0, 'NON_DIRECTORY', None),
                                   ((2,), 'MISSING', 2), ((3,), 'MISSING', 3),
                                   ((5,), 'ACCESS_DENIED', 5), ((32,), 'OTHER_ERROR', 32),
                                   ((0,), 'OTHER_ERROR', 0)):
            with self.subTest(state=state, error=error):
                report, _, _ = self.inspect(attributes={str(LOCAL_ENV): value})
            self.assertEqual(report['current']['metadata'], PROBE.metadata(state, error, 'SELF'))

    def test_reparse_leaf_is_rejected_even_if_directory_attribute_is_also_set(self):
        report, _, kernel = self.inspect(attributes={str(LOCAL_ENV): 0x400 | 0x10})
        self.assertEqual(report['current']['metadata'], PROBE.metadata('REPARSE_REJECTED', at='SELF'))
        self.assert_only_inside_reads(kernel)

    def test_reparse_ancestor_is_not_followed_and_no_descendant_is_read(self):
        work = CHECKOUT / '.work'
        report, _, kernel = self.inspect(attributes={str(work): 0x400 | 0x10})
        self.assertEqual(kernel.GetFileAttributesW.calls, [(str(CHECKOUT),), (str(work),)])
        self.assertEqual(report['current']['metadata'], PROBE.metadata('REPARSE_REJECTED', at='ANCESTOR'))
        self.assertEqual(report['candidates']['homeAppDataLocal']['state'], 'REPARSE_REJECTED')

    def test_missing_parent_reports_ancestor_not_an_unperformed_leaf_lookup(self):
        appdata = CHECKOUT / '.work/home/AppData'
        report, _, kernel = self.inspect(attributes={str(appdata): (3,)})
        self.assertEqual(report['dontVerify']['metadata'], PROBE.metadata('MISSING', 3, 'ANCESTOR'))
        self.assertEqual(report['candidates']['homeAppData'], PROBE.metadata('MISSING', 3, 'SELF'))
        self.assertNotIn((str(HOME_LOCAL),), kernel.GetFileAttributesW.calls)

    def test_denied_or_nondirectory_parent_never_becomes_missing(self):
        home = CHECKOUT / '.work/home'
        for value, state, error in (((5,), 'ACCESS_DENIED', 5), (0, 'NON_DIRECTORY', None), ((32,), 'OTHER_ERROR', 32)):
            with self.subTest(state=state):
                report, _, kernel = self.inspect(attributes={str(home): value})
            self.assertEqual(report['dontVerify']['metadata'], PROBE.metadata(state, error, 'ANCESTOR'))
            self.assertNotIn((str(HOME_LOCAL),), kernel.GetFileAttributesW.calls)

    def test_duplicate_returned_and_candidate_paths_share_one_attribute_read(self):
        report, _, kernel = self.inspect(((0, str(HOME_LOCAL)), (0, str(HOME_LOCAL).upper())))
        paths = [PROBE.path_key(PureWindowsPath(args[0])) for args in kernel.GetFileAttributesW.calls]
        self.assertEqual(len(paths), len(set(paths)))
        self.assertEqual(report['counts']['leafChecks'], 1)
        self.assertEqual(report['counts']['ancestorChecks'], 4)
        self.assertEqual(report['current']['metadata'], report['dontVerify']['metadata'])
        self.assertEqual(report['current']['metadata'], report['candidates']['homeAppDataLocal'])

    def test_four_distinct_leaves_are_the_maximum_even_with_two_other_owned_returns(self):
        report, _, kernel = self.inspect(((0, str(CHECKOUT / 'one')), (0, str(CHECKOUT / 'two'))))
        self.assertEqual(report['counts']['leafChecks'], 4)
        self.assertEqual(report['counts']['ancestorChecks'], 3)
        self.assertEqual(len(kernel.GetFileAttributesW.calls), 7)
        self.assert_only_inside_reads(kernel)

    def test_ancestor_ceiling_yields_incomplete_not_missing_and_never_exceeds_sixteen(self):
        deep = str(CHECKOUT) + '\\a' * 20
        report, _, kernel = self.inspect(((0, deep), (0, '')))
        self.assertEqual(report['outcome'], 'INCOMPLETE')
        self.assertEqual(report['reason'], 'METADATA_LIMIT')
        self.assertEqual(report['counts']['ancestorChecks'], 16)
        self.assertEqual(report['counts']['leafChecks'], 0)
        self.assertEqual(len(kernel.GetFileAttributesW.calls), 16)
        self.assertEqual(report['current']['metadata'], PROBE.metadata())
        self.assertEqual(report['candidates']['homeAppDataLocal'], PROBE.metadata())

    def test_metadata_probe_enforces_four_leaf_limit_and_deduplicates_root(self):
        readers, _, kernel = self.readers()
        counts = PROBE.empty_report()['counts']
        probe = PROBE.MetadataProbe(CHECKOUT, readers, counts)
        self.assertEqual(probe.probe(LOCAL_ENV), PROBE.metadata())
        self.assertEqual(kernel.GetFileAttributesW.calls, [])
        probe.check_root()
        probe.check_root()
        probe.probe(CHECKOUT)
        for index in range(5):
            last = probe.probe(CHECKOUT / str(index))
        self.assertEqual(last, PROBE.metadata())
        self.assertTrue(probe.limited)
        self.assertEqual(counts['leafChecks'], 4)
        self.assertEqual(counts['ancestorChecks'], 1)
        self.assertEqual(len(kernel.GetFileAttributesW.calls), 5)

    def test_native_call_exception_records_only_numeric_error_and_never_retries(self):
        report, shell, _ = self.inspect((winerror(5), winerror(0xffffffff)))
        self.assertEqual(report['outcome'], 'INCOMPLETE')
        self.assertEqual(report['current']['buffer'], 'CALL_ERROR')
        self.assertEqual(report['current']['winError'], 5)
        self.assertEqual(report['dontVerify']['winError'], 0xffffffff)
        self.assertEqual(len(shell.SHGetFolderPathW.calls), 2)

    def test_invalid_winerror_or_attribute_numbers_never_print_or_become_missing(self):
        for value in (None, PRIVATE, True, 1.5, -1, 0x100000000):
            with self.subTest(kind=type(value).__name__):
                report, _, _ = self.inspect(attributes={str(LOCAL_ENV): winerror(value)})
            self.assertEqual(report['current']['metadata'], PROBE.metadata('OTHER_ERROR', at='SELF'))
        for value in (True, PRIVATE, -1, 0x100000000, (PRIVATE,)):
            with self.subTest(kind=type(value).__name__):
                report, _, _ = self.inspect(attributes={str(LOCAL_ENV): value})
            self.assertEqual(report['current']['metadata'], PROBE.metadata('OTHER_ERROR', at='SELF'))

    def test_raising_winerror_property_is_not_formatted_or_rethrown(self):
        class HostileError(Exception):
            @property
            def winerror(self):
                raise RuntimeError(PRIVATE)
        report, _, _ = self.inspect((HostileError(PRIVATE), HostileError(PRIVATE)))
        self.assertEqual(report['current']['winError'], None)
        self.assertEqual(report['reason'], 'UNUSABLE_NATIVE_RESULT')

    def test_complete_schema_rejects_added_fields_at_every_object_level(self):
        report, _, _ = self.inspect()
        routes = ((), ('environmentChecks',), ('boundary',), ('boundary', 'root'),
                  ('current',), ('dontVerify',), ('current', 'metadata'), ('dontVerify', 'metadata'),
                  ('candidates',), ('candidates', 'homeAppData'), ('candidates', 'homeAppDataLocal'), ('counts',))
        for route in routes:
            changed = copy.deepcopy(report)
            target = changed
            for key in route:
                target = target[key]
            target['privatePath'] = PRIVATE
            with self.subTest(route=route), self.assertRaises(ValueError):
                PROBE.encode_report(changed)

    def test_complete_schema_rejects_missing_fields_unknown_values_and_admission(self):
        report, _, _ = self.inspect()
        changes = (
            (('schemaVersion',), True), (('diagnostic',), PRIVATE), (('reason',), PRIVATE),
            (('browserLaunched',), True), (('environmentChanged',), True), (('directoriesCreated',), 1),
            (('noBrowserAdmission',), False), (('environmentChecks', 'home'), PRIVATE),
            (('current', 'flags'), 0x801c), (('current', 'hresult'), PRIVATE),
            (('current', 'buffer'), PRIVATE), (('current', 'pathClass'), PRIVATE),
            (('current', 'failed'), 0), (('current', 'nulTerminated'), 'true'),
            (('current', 'metadata', 'state'), PRIVATE), (('current', 'metadata', 'winError'), PRIVATE),
            (('current', 'metadata', 'at'), PRIVATE), (('counts', 'leafChecks'), 5),
            (('counts', 'ancestorChecks'), 17), (('counts', 'knownFolderAttempts'), 3),
            (('counts', 'driveTypeChecks'), 2),
        )
        for route, value in changes:
            changed = copy.deepcopy(report)
            target = changed
            for key in route[:-1]:
                target = target[key]
            target[route[-1]] = value
            with self.subTest(route=route), self.assertRaises(ValueError):
                PROBE.encode_report(changed)
        for key in report:
            changed = copy.deepcopy(report)
            del changed[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                PROBE.encode_report(changed)

    def test_schema_rejects_forged_hresult_and_metadata_contradictions(self):
        report, _, _ = self.inspect()
        for mutate in (
            lambda value: value['current'].update(hresult='80070002', failed=True),
            lambda value: value['current'].update(hresult='00000001'),
            lambda value: value['current'].update(nonempty=False),
            lambda value: value['current'].update(attempted=False),
            lambda value: value['current'].update(pathClass='OUTSIDE_REPOSITORY_LOCAL'),
            lambda value: value['current']['metadata'].update(state='MISSING', winError=5),
            lambda value: value['current']['metadata'].update(state='ACCESS_DENIED', winError=2),
            lambda value: value['current']['metadata'].update(at='NONE'),
            lambda value: value['counts'].update(knownFolderAttempts=1),
        ):
            changed = copy.deepcopy(report)
            mutate(changed)
            with self.assertRaises(ValueError):
                PROBE.encode_report(changed)

    def test_parser_rejects_duplicate_keys_oversize_trailing_json_and_nonfinite_data(self):
        report, _, _ = self.inspect()
        encoded = PROBE.encode_report(report)
        for value in ('{"schemaVersion":1,' + encoded[1:],
                      encoded.replace('"flags":28', '"flags":28,"flags":28', 1),
                      encoded + encoded, ' ' * 4097,
                      encoded.replace('"directoriesCreated":0', '"directoriesCreated":NaN')):
            with self.subTest(bytes=len(value)), self.assertRaises(ValueError):
                PROBE.parse_report(value)

    def test_output_size_is_enforced_and_maximum_numeric_errors_are_safe(self):
        report, _, _ = self.inspect((winerror(0xffffffff), winerror(0xffffffff)),
                                    {str(CHECKOUT / '.work/home/AppData'): (0xffffffff,)})
        encoded = PROBE.encode_report(report)
        self.assertLess(len(encoded.encode('ascii')), 4096)
        with patch.object(PROBE, 'MAX_REPORT_BYTES', len(encoded.encode('ascii')) - 1), self.assertRaises(ValueError):
            PROBE.encode_report(report)

    def test_unknown_report_round_trips_without_invented_zero_attempts_or_counts(self):
        report = PROBE.unknown_report()
        self.assertEqual(report['outcome'], 'INCOMPLETE')
        self.assertEqual(report['reason'], 'INVALID_REPORT')
        self.assertEqual(PROBE.parse_report(PROBE.encode_report(report)), report)
        self.assertTrue(all(value is None for value in report['counts'].values()))
        for name in ('current', 'dontVerify'):
            self.assertIsNone(report[name]['attempted'])
            self.assertEqual(report[name]['buffer'], 'UNKNOWN')
        self.assert_private_absent(report)
        for field in report['counts']:
            forged = copy.deepcopy(report)
            forged['counts'][field] = 0
            with self.subTest(field=field), self.assertRaises(ValueError):
                PROBE.encode_report(forged)
        observed, _, _ = self.inspect()
        observed['counts']['knownFolderAttempts'] = None
        with self.assertRaises(ValueError):
            PROBE.encode_report(observed)

    def test_non_windows_or_non_native64_cli_refuses_before_any_loader_or_cwd(self):
        for platform, pointer, wchar in (('linux', 8, 4), ('win32', 4, 2), ('win32', 8, 4)):
            def size(kind):
                return pointer if kind is PROBE.POINTER else wchar
            output = io.StringIO()
            with self.subTest(platform=platform, pointer=pointer, wchar=wchar), \
                    patch.object(PROBE.sys, 'platform', platform), patch.object(PROBE.sys, 'argv', ['diagnostic']), \
                    patch.object(PROBE.ctypes, 'sizeof', side_effect=size), \
                    patch.object(PROBE, 'load_readers') as loader, patch.object(PROBE.os, 'getcwd') as cwd, \
                    contextlib.redirect_stdout(output):
                code = PROBE.main()
            loader.assert_not_called()
            cwd.assert_not_called()
            self.assertEqual(code, 78)
            report = PROBE.parse_report(output.getvalue())
            self.assertEqual(report['reason'], 'UNSUPPORTED_HOST')
            self.assert_private_absent(report)

    def test_cli_rejects_arguments_without_echoing_private_values(self):
        output = io.StringIO()
        with patch.object(PROBE.sys, 'argv', ['diagnostic', PRIVATE]), patch.object(PROBE, 'load_readers') as loader, \
                contextlib.redirect_stdout(output):
            code = PROBE.main()
        loader.assert_not_called()
        self.assertEqual(code, 78)
        report = PROBE.parse_report(output.getvalue())
        self.assertEqual(report['reason'], 'UNEXPECTED_ARGUMENTS')
        self.assert_private_absent(report)

    def test_cli_emits_exactly_one_fixed_json_with_only_mocked_native_calls(self):
        readers, shell, _ = self.readers()
        output = io.StringIO()
        env = environment()
        with patch.object(PROBE.sys, 'argv', ['diagnostic']), patch.object(PROBE.sys, 'platform', 'win32'), \
                patch.object(PROBE.ctypes, 'sizeof', side_effect=lambda kind: 8 if kind is PROBE.POINTER else 2), \
                patch.object(PROBE.os.path, 'abspath', return_value='D:/a/kmpYT/kmpYT/ci/windows-known-folder-diagnostic.py'), \
                patch.object(PROBE.os, 'getcwd', return_value=str(CHECKOUT)), \
                patch.object(PROBE.os, 'environ', env), patch.object(PROBE, 'load_readers', return_value=readers), \
                contextlib.redirect_stdout(output):
            code = PROBE.main()
        self.assertEqual(code, 0)
        self.assertEqual(len(output.getvalue().splitlines()), 1)
        self.assertEqual(len(shell.SHGetFolderPathW.calls), 2)
        self.assertEqual(env, environment())
        self.assert_private_absent(PROBE.parse_report(output.getvalue()))

    def test_cli_unexpected_exception_emits_only_a_fixed_refusal(self):
        output = io.StringIO()
        with patch.object(PROBE.sys, 'argv', ['diagnostic']), patch.object(PROBE.sys, 'platform', 'win32'), \
                patch.object(PROBE.ctypes, 'sizeof', side_effect=lambda kind: 8 if kind is PROBE.POINTER else 2), \
                patch.object(PROBE, 'inspect', side_effect=RuntimeError(PRIVATE)), contextlib.redirect_stdout(output):
            code = PROBE.main()
        self.assertEqual(code, 78)
        report = PROBE.parse_report(output.getvalue())
        self.assertEqual(report['reason'], 'INVALID_REPORT')
        self.assert_private_absent(report)

    def test_production_source_has_no_process_network_registry_or_filesystem_write_api(self):
        source = (ROOT / 'ci/windows-known-folder-diagnostic.py').read_text()
        tree = ast.parse(source)
        imports = {node.names[0].name for node in ast.walk(tree) if isinstance(node, ast.Import)}
        self.assertEqual(imports, {'ctypes', 'json', 'os', 're', 'sys'})
        attributes = {node.attr for node in ast.walk(tree) if isinstance(node, ast.Attribute)}
        for forbidden in ('mkdir', 'makedirs', 'write_text', 'write_bytes', 'resolve', 'realpath',
                          'listdir', 'scandir', 'readlink', 'stat', 'lstat', 'open', 'system', 'popen',
                          'spawn', 'Popen', 'run', 'SHSetKnownFolderPath', 'SetEnvironmentVariableW',
                          'CreateDirectoryW', 'CreateFileW', 'RegSetValueExW'):
            self.assertNotIn(forbidden, attributes)
        named_calls = {node.func.id for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)}
        self.assertNotIn('open', named_calls)
        self.assertNotIn('eval', named_calls)
        self.assertNotIn('exec', named_calls)

    def test_existing_scoped_setup_does_not_create_the_diagnostic_candidates(self):
        source = (ROOT / 'ci/env.sh').read_text()
        python = source.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        tree = ast.parse(python)
        assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                          and any(isinstance(target, ast.Name) and target.id == 'names' for target in node.targets))
        names = ast.literal_eval(assignment.value)
        self.assertIn('home', names)
        self.assertIn('data', names)
        self.assertNotIn('home/AppData', names)
        self.assertNotIn('home/AppData/Local', names)
        self.assertNotIn('home/AppData/Roaming', names)


if __name__ == '__main__':
    unittest.main(verbosity=2)
