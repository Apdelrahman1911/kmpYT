#!/usr/bin/env python3
"""Finite read-only LocalAppData observation, NEVER browser admission or repair.

The executing checkout root is the existing trust anchor. Attribute checks are
point-in-time, not handle-relative containment against concurrent renames; they
do not attest ancestors above that root. No returned outside path is inspected.
Only main() may select real native readers, after native64/environment checks.
"""
import ctypes
import json
import os
from pathlib import PureWindowsPath
import re
import sys

DWORD = ctypes.c_uint32
INT = ctypes.c_int32
POINTER = ctypes.c_void_p
MAX_PATH = 260
MAX_REPORT_BYTES = 4096
MAX_LEAF_CHECKS = 4
MAX_ANCESTOR_CHECKS = 16
CURRENT_FLAGS = 0x001c
DONT_VERIFY_FLAGS = 0x401c  # Diagnosis only. NEVER include CSIDL_FLAG_CREATE.
SYSTEM32_ONLY = 0x00000800
DIRECTORY_ATTRIBUTE = 0x10
REPARSE_ATTRIBUTE = 0x400
INVALID_ATTRIBUTES = 0xffffffff
DRIVE_FIXED = 3

PATH_CLASSES = {
    'EXACT_SCOPED_HOME_APPDATA_LOCAL', 'EXACT_SCOPED_LOCALAPPDATA_ENV',
    'OTHER_INSIDE_REPOSITORY', 'OUTSIDE_REPOSITORY_LOCAL',
    'NONLOCAL_OR_UNSUPPORTED', 'NO_USABLE_PATH',
}
INSIDE_CLASSES = {
    'EXACT_SCOPED_HOME_APPDATA_LOCAL', 'EXACT_SCOPED_LOCALAPPDATA_ENV',
    'OTHER_INSIDE_REPOSITORY',
}
METADATA_STATES = {
    'DIRECTORY', 'MISSING', 'NON_DIRECTORY', 'REPARSE_REJECTED',
    'ACCESS_DENIED', 'OTHER_ERROR', 'NOT_PROBED',
}
BUFFER_STATES = {
    'NOT_CALLED', 'NOT_READ_FAILED_HRESULT', 'NOT_READ_NONZERO_HRESULT',
    'EMPTY', 'UNTERMINATED', 'MALFORMED', 'PATH', 'CALL_ERROR', 'UNKNOWN',
}
REASONS = {
    'NONE', 'UNSUPPORTED_HOST', 'UNEXPECTED_ARGUMENTS', 'ENVIRONMENT_MISMATCH',
    'NATIVE_UNAVAILABLE', 'UNSUPPORTED_DRIVE', 'ROOT_UNSAFE',
    'UNUSABLE_NATIVE_RESULT', 'METADATA_LIMIT', 'UNEXPECTED_ERROR', 'INVALID_REPORT',
}
ENVIRONMENT_KEYS = (
    'scopedMarker', 'repositoryRoot', 'workingDirectory', 'home', 'userProfile',
    'localAppData', 'appData', 'temp', 'tmp', 'tmpdir',
)


def local_path(value):
    """Pure lexical check: no expansion, normalization across aliases, or I/O.

    Deliberately accept only bounded ASCII local-drive paths. This makes the
    component-wise case comparison exact for the supported subset, rather than
    equating Unicode casefold/Win32 normalization aliases. Unsupported is not
    missing. No UNC, device namespace, ADS, DOS device, dot or trailing-dot alias.
    """
    if (type(value) is not str or not 1 <= len(value) < MAX_PATH or
            any(not 32 <= ord(char) < 127 for char in value)):
        return None
    value = value.replace('/', '\\')
    if not re.match(r'^[A-Za-z]:\\', value):
        return None
    components = value[3:].split('\\') if len(value) > 3 else []
    if components and components[-1] == '':
        components.pop()  # A single trailing separator, not empty components.
    for component in components:
        if (not component or component in ('.', '..') or
                component.endswith((' ', '.')) or
                any(char in '<>:"|?*' for char in component) or
                re.fullmatch(r'CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9]|LPT[1-9]',
                             component.split('.')[0], re.IGNORECASE)):
            return None
    return PureWindowsPath(value)


def path_key(path):
    return tuple(part.lower() for part in path.parts)


def inside(path, root):
    parts, prefix = path_key(path), path_key(root)
    return parts[:len(prefix)] == prefix


def path_class(path, root):
    if path is None:
        return 'NONLOCAL_OR_UNSUPPORTED'
    if path_key(path) == path_key(root / '.work/home/AppData/Local'):
        return 'EXACT_SCOPED_HOME_APPDATA_LOCAL'
    if path_key(path) == path_key(root / '.work/data'):
        return 'EXACT_SCOPED_LOCALAPPDATA_ENV'
    return 'OTHER_INSIDE_REPOSITORY' if inside(path, root) else 'OUTSIDE_REPOSITORY_LOCAL'


def metadata(state='NOT_PROBED', error=None, at='NONE'):
    return {'state': state, 'winError': error, 'at': at}


def error_number(error):
    # No errno substitution, FormatError, str(error), path or exception text.
    try:
        value = getattr(error, 'winerror', None)
    except Exception:
        return None
    return value if type(value) is int and 0 <= value <= 0xffffffff else None


def attribute_error(number):
    state = 'MISSING' if number in (2, 3) else 'ACCESS_DENIED' if number == 5 else 'OTHER_ERROR'
    return metadata(state, number, 'SELF')


def empty_call(flags):
    return {
        'flags': flags, 'attempted': False, 'hresult': None, 'failed': None,
        'buffer': 'NOT_CALLED', 'nulTerminated': None, 'nonempty': None,
        'pathClass': 'NO_USABLE_PATH', 'metadata': metadata(), 'winError': None,
    }


class NativeReaders:
    """Only two fixed shell queries plus bounded read-only attribute/drive APIs."""

    def __init__(self, shell, kernel, get_last_error, set_last_error):
        self.folder = shell.SHGetFolderPathW
        self.folder.argtypes = [POINTER, INT, POINTER, DWORD, ctypes.POINTER(ctypes.c_wchar)]
        self.folder.restype = INT  # HRESULT is signed 32-bit, also on native64.
        self.get_attributes = kernel.GetFileAttributesW
        self.get_attributes.argtypes = [ctypes.c_wchar_p]
        self.get_attributes.restype = DWORD
        self.get_drive_type = kernel.GetDriveTypeW
        self.get_drive_type.argtypes = [ctypes.c_wchar_p]
        self.get_drive_type.restype = DWORD
        self.get_last_error, self.set_last_error = get_last_error, set_last_error

    def lookup(self, flags):
        if type(flags) is not int or flags not in (CURRENT_FLAGS, DONT_VERIFY_FLAGS):
            raise ValueError('UNSUPPORTED_FLAGS')
        report = empty_call(flags)
        try:
            buffer = ctypes.create_unicode_buffer(MAX_PATH)  # Zeroed 260 wchar_t.
            report['attempted'] = True
            value = self.folder(None, flags, None, 0, buffer)  # SHGFP_TYPE_CURRENT.
            if type(value) is not int or not -0x80000000 <= value <= 0x7fffffff:
                raise ValueError('INVALID_HRESULT')
            unsigned = value & 0xffffffff
            report['hresult'], report['failed'] = f'{unsigned:08X}', bool(unsigned & 0x80000000)
            if report['failed']:
                report['buffer'] = 'NOT_READ_FAILED_HRESULT'
                return report, None  # Output is undefined: do not even read it.
            if unsigned != 0:
                report['buffer'] = 'NOT_READ_NONZERO_HRESULT'
                return report, None  # S_FALSE is not S_OK or usable-path evidence.
            raw = buffer[:]
            if type(raw) is not str or len(raw) != MAX_PATH:
                report['buffer'] = 'MALFORMED'
                return report, None
            end = raw.find('\0')
            report['nulTerminated'], report['nonempty'] = end >= 0, end != 0
            if end < 0:
                report['buffer'] = 'UNTERMINATED'
                return report, None
            # Reject unterminated/invalid UTF-16 or a nonzero suffix after NUL.
            if any(char != '\0' for char in raw[end:]) or any(0xd800 <= ord(char) <= 0xdfff for char in raw[:end]):
                report['buffer'] = 'MALFORMED'
                return report, None
            if end == 0:
                report['buffer'] = 'EMPTY'
                return report, None
            report['buffer'] = 'PATH'
            return report, raw[:end]  # Private, internal only; never serialized.
        except Exception as error:
            report['buffer'], report['winError'] = 'CALL_ERROR', error_number(error)
            return report, None

    def drive_type(self, root):
        value = self.get_drive_type(root.drive + '\\')  # Explicit drive, not NULL/CWD.
        if type(value) is not int or not 0 <= value <= 6:
            raise ValueError('INVALID_DRIVE_TYPE')
        return value

    def attributes(self, path):
        try:
            self.set_last_error(0)
            value = self.get_attributes(str(path))
            if type(value) is not int or not 0 <= value <= 0xffffffff:
                return attribute_error(None)
            if value == INVALID_ATTRIBUTES:
                number = self.get_last_error()
                if type(number) is not int or not 0 <= number <= 0xffffffff:
                    number = None
                return attribute_error(number)
            # The API reports a final link's own attributes. Ancestors are
            # checked separately before descent; no observed reparse is followed.
            if value & REPARSE_ATTRIBUTE:
                return metadata('REPARSE_REJECTED', at='SELF')
            return metadata('DIRECTORY' if value & DIRECTORY_ATTRIBUTE else 'NON_DIRECTORY', at='SELF')
        except Exception as error:
            return attribute_error(error_number(error))


def load_readers():
    # Exact system-only search for both DLLs AND their dependencies. No PATH,
    # selected browser, environment-derived SystemRoot or checkout DLL lookup.
    shell = ctypes.WinDLL('shell32.dll', winmode=SYSTEM32_ONLY, use_last_error=True)
    kernel = ctypes.WinDLL('kernel32.dll', winmode=SYSTEM32_ONLY, use_last_error=True)
    return NativeReaders(shell, kernel, ctypes.get_last_error, ctypes.set_last_error)


class MetadataProbe:
    def __init__(self, root, readers, counts):
        self.root, self.readers, self.counts = root, readers, counts
        self.cache, self.limited = {}, False

    def check_root(self):
        return self.read(self.root, 'ancestorChecks', MAX_ANCESTOR_CHECKS)

    def read(self, path, counter, maximum):
        key = path_key(path)
        if key in self.cache:
            return self.cache[key]
        if self.counts[counter] >= maximum:
            self.limited = True
            return metadata()
        self.counts[counter] += 1
        result = self.readers.attributes(path)
        self.cache[key] = result
        return result

    def probe(self, path):
        if path is None or not inside(path, self.root):
            return metadata()
        root_result = self.cache.get(path_key(self.root), metadata())
        if root_result['state'] != 'DIRECTORY':
            return metadata()  # Root must be positively checked, not just named.
        current = self.root
        for component in path.parts[len(self.root.parts):-1]:
            current /= component
            result = self.read(current, 'ancestorChecks', MAX_ANCESTOR_CHECKS)
            if result['state'] != 'DIRECTORY':
                return {**result, 'at': 'NONE' if result['state'] == 'NOT_PROBED' else 'ANCESTOR'}
        return self.read(path, 'leafChecks', MAX_LEAF_CHECKS)


def empty_report():
    return {
        'schemaVersion': 1, 'diagnostic': 'WINDOWS_LOCAL_APP_DATA',
        'outcome': 'REFUSED', 'reason': 'UNEXPECTED_ERROR',
        'browserLaunched': False, 'environmentChanged': False,
        'directoriesCreated': 0, 'noBrowserAdmission': True,
        'environmentChecks': dict.fromkeys(ENVIRONMENT_KEYS, False),
        'boundary': {'driveType': None, 'root': metadata()},
        'current': empty_call(CURRENT_FLAGS), 'dontVerify': empty_call(DONT_VERIFY_FLAGS),
        'candidates': {'homeAppData': metadata(), 'homeAppDataLocal': metadata()},
        'counts': {'knownFolderAttempts': 0, 'driveTypeChecks': 0, 'leafChecks': 0, 'ancestorChecks': 0},
    }


def unknown_report():
    # A lost/invalid observation cannot truthfully claim zero native operations.
    # Fixed safety markers describe this script's action set, not OS attestation.
    report = empty_report()
    report['outcome'], report['reason'] = 'INCOMPLETE', 'INVALID_REPORT'
    report['counts'] = dict.fromkeys(report['counts'], None)
    for name in ('current', 'dontVerify'):
        report[name]['attempted'], report[name]['buffer'] = None, 'UNKNOWN'
    return report


def inspect(root_value, cwd_value, environment, reader_factory):
    """Injected source-only tests use mocks; main supplies the actual native64 host."""
    report = empty_report()
    root = local_path(root_value)
    checks = report['environmentChecks']
    checks['scopedMarker'] = environment.get('KMPYT_CI_SCOPED_ENV') == '1'
    checks['repositoryRoot'] = root is not None and len(root.parts) > 1
    if checks['repositoryRoot']:
        checks['workingDirectory'] = local_path(cwd_value) == root
        expected = (
            ('home', 'HOME', '.work/home'), ('userProfile', 'USERPROFILE', '.work/home'),
            ('localAppData', 'LOCALAPPDATA', '.work/data'), ('appData', 'APPDATA', '.work/config'),
            ('temp', 'TEMP', '.work/tmp'), ('tmp', 'TMP', '.work/tmp'), ('tmpdir', 'TMPDIR', '.work/tmp'),
        )
        for field, variable, suffix in expected:
            checks[field] = local_path(environment.get(variable)) == root / suffix
    if not all(checks.values()):
        report['reason'] = 'ENVIRONMENT_MISMATCH'
        return report  # No DLL, filesystem lookup or environment correction.
    try:
        readers = reader_factory()
    except Exception:
        report['reason'] = 'NATIVE_UNAVAILABLE'
        return report
    try:
        counts = report['counts']
        counts['driveTypeChecks'] = 1
        report['boundary']['driveType'] = readers.drive_type(root)
        if report['boundary']['driveType'] != DRIVE_FIXED:
            report['reason'] = 'UNSUPPORTED_DRIVE'
            return report
        probe = MetadataProbe(root, readers, counts)
        report['boundary']['root'] = probe.check_root()
        if report['boundary']['root']['state'] != 'DIRECTORY':
            report['reason'] = 'ROOT_UNSAFE'
            return report
        # Exactly these two independently recorded attempts, never a retry.
        for name, flags in (('current', CURRENT_FLAGS), ('dontVerify', DONT_VERIFY_FLAGS)):
            call, private_value = readers.lookup(flags)
            report[name] = call
            counts['knownFolderAttempts'] += int(call['attempted'])
            if private_value is not None:
                path = local_path(private_value)
                call['pathClass'] = path_class(path, root)
                call['metadata'] = probe.probe(path)
        for field, suffix in (('homeAppData', '.work/home/AppData'), ('homeAppDataLocal', '.work/home/AppData/Local')):
            report['candidates'][field] = probe.probe(root / suffix)
        report['outcome'], report['reason'] = 'OBSERVED', 'NONE'
        if any(report[name]['buffer'] in ('CALL_ERROR', 'UNTERMINATED', 'MALFORMED') for name in ('current', 'dontVerify')):
            report['outcome'], report['reason'] = 'INCOMPLETE', 'UNUSABLE_NATIVE_RESULT'
        if probe.limited:
            report['outcome'], report['reason'] = 'INCOMPLETE', 'METADATA_LIMIT'
    except Exception:
        report['outcome'], report['reason'] = 'INCOMPLETE', 'UNEXPECTED_ERROR'
    return report


def validate_report(report):
    """Complete output allowlist: no path, hash, arbitrary message or added key."""
    def require(condition):
        if not condition:
            raise ValueError('INVALID_REPORT')

    def keys(value, expected):
        require(type(value) is dict and set(value) == set(expected))

    def number(value, maximum):
        require(type(value) is int and 0 <= value <= maximum)

    def winerror(value):
        if value is not None:
            number(value, 0xffffffff)

    def meta(value):
        keys(value, ('state', 'winError', 'at'))
        require(type(value['state']) is str and value['state'] in METADATA_STATES and value['at'] in ('SELF', 'ANCESTOR', 'NONE'))
        winerror(value['winError'])
        require((value['state'] == 'NOT_PROBED') == (value['at'] == 'NONE'))
        if value['state'] in ('DIRECTORY', 'NON_DIRECTORY', 'REPARSE_REJECTED', 'NOT_PROBED'):
            require(value['winError'] is None)
        if value['state'] == 'MISSING':
            require(value['winError'] in (2, 3))
        if value['state'] == 'ACCESS_DENIED':
            require(value['winError'] == 5)
        if value['state'] == 'OTHER_ERROR':
            require(value['winError'] not in (2, 3, 5))

    keys(report, empty_report())
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1)
    require(report['diagnostic'] == 'WINDOWS_LOCAL_APP_DATA')
    require(report['outcome'] in ('REFUSED', 'INCOMPLETE', 'OBSERVED') and type(report['reason']) is str and report['reason'] in REASONS)
    require((report['outcome'] == 'OBSERVED') == (report['reason'] == 'NONE'))
    unknown = report['reason'] == 'INVALID_REPORT'
    if unknown:
        require(report == unknown_report())
    require(report['browserLaunched'] is False and report['environmentChanged'] is False)
    require(type(report['directoriesCreated']) is int and report['directoriesCreated'] == 0 and report['noBrowserAdmission'] is True)
    keys(report['environmentChecks'], ENVIRONMENT_KEYS)
    require(all(type(value) is bool for value in report['environmentChecks'].values()))
    keys(report['boundary'], ('driveType', 'root'))
    if report['boundary']['driveType'] is not None:
        number(report['boundary']['driveType'], 6)
    meta(report['boundary']['root'])
    require(report['boundary']['root']['at'] != 'ANCESTOR')
    for field, flags in (('current', CURRENT_FLAGS), ('dontVerify', DONT_VERIFY_FLAGS)):
        call = report[field]
        keys(call, empty_call(flags))
        require(type(call['flags']) is int and call['flags'] == flags)
        require(type(call['attempted']) is bool or (unknown and call['attempted'] is None))
        require(type(call['buffer']) is str and call['buffer'] in BUFFER_STATES)
        require(type(call['pathClass']) is str and call['pathClass'] in PATH_CLASSES)
        winerror(call['winError'])
        for nullable in ('failed', 'nulTerminated', 'nonempty'):
            require(call[nullable] is None or type(call[nullable]) is bool)
        if call['hresult'] is None:
            require(call['failed'] is None and (call['buffer'] in ('NOT_CALLED', 'CALL_ERROR') or (unknown and call['buffer'] == 'UNKNOWN')))
        else:
            require(type(call['hresult']) is str and re.fullmatch('[0-9A-F]{8}', call['hresult']) is not None)
            require(call['attempted'] and call['failed'] == bool(int(call['hresult'], 16) & 0x80000000))
            if call['failed']:
                require(call['buffer'] == 'NOT_READ_FAILED_HRESULT')
            elif call['hresult'] != '00000000':
                require(call['buffer'] == 'NOT_READ_NONZERO_HRESULT')
            else:
                require(call['buffer'] in ('EMPTY', 'UNTERMINATED', 'MALFORMED', 'PATH', 'CALL_ERROR'))
        if not call['attempted']:
            require(call['hresult'] is None)
        if call['buffer'] == 'NOT_CALLED':
            require(not call['attempted'] and call['winError'] is None)
        if call['buffer'] == 'PATH':
            require(call['hresult'] == '00000000' and call['nulTerminated'] is True and call['nonempty'] is True)
            require(call['pathClass'] != 'NO_USABLE_PATH')
        else:
            require(call['pathClass'] == 'NO_USABLE_PATH')
        if call['buffer'] in ('NOT_CALLED', 'NOT_READ_FAILED_HRESULT', 'NOT_READ_NONZERO_HRESULT', 'UNKNOWN'):
            require(call['nulTerminated'] is None and call['nonempty'] is None)
        if call['buffer'] == 'EMPTY':
            require(call['nulTerminated'] is True and call['nonempty'] is False)
        if call['buffer'] == 'UNTERMINATED':
            require(call['nulTerminated'] is False and call['nonempty'] is True)
        if call['buffer'] != 'CALL_ERROR':
            require(call['winError'] is None)
        meta(call['metadata'])
        if call['pathClass'] not in INSIDE_CLASSES:
            require(call['metadata']['state'] == 'NOT_PROBED')
    keys(report['candidates'], ('homeAppData', 'homeAppDataLocal'))
    for value in report['candidates'].values():
        meta(value)
    keys(report['counts'], ('knownFolderAttempts', 'driveTypeChecks', 'leafChecks', 'ancestorChecks'))
    for key, maximum in (('knownFolderAttempts', 2), ('driveTypeChecks', 1), ('leafChecks', MAX_LEAF_CHECKS), ('ancestorChecks', MAX_ANCESTOR_CHECKS)):
        if not unknown:
            number(report['counts'][key], maximum)
    if not unknown:
        require(report['counts']['knownFolderAttempts'] == sum(report[name]['attempted'] for name in ('current', 'dontVerify')))
    if report['outcome'] == 'OBSERVED':
        require(all(report['environmentChecks'].values()) and report['counts']['knownFolderAttempts'] == 2)
        require(report['boundary']['driveType'] == DRIVE_FIXED and report['boundary']['root']['state'] == 'DIRECTORY')
        require(all(report[name]['buffer'] in ('PATH', 'EMPTY', 'NOT_READ_FAILED_HRESULT', 'NOT_READ_NONZERO_HRESULT') for name in ('current', 'dontVerify')))


def encode_report(report):
    validate_report(report)
    value = json.dumps(report, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n'
    if len(value.encode('ascii')) > MAX_REPORT_BYTES:
        raise ValueError('INVALID_REPORT')
    return value


def parse_report(value):
    """Pure strict receipt checker; duplicate/extra keys never become evidence."""
    if type(value) is not str or len(value.encode('utf-8')) > MAX_REPORT_BYTES:
        raise ValueError('INVALID_REPORT')

    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError('INVALID_REPORT')
            result[key] = item
        return result

    result = json.loads(value, object_pairs_hook=unique)
    validate_report(result)
    return result


def main():
    report = empty_report()
    try:
        if len(sys.argv) != 1:
            report['reason'] = 'UNEXPECTED_ARGUMENTS'
        elif sys.platform != 'win32' or ctypes.sizeof(POINTER) != 8 or ctypes.sizeof(ctypes.c_wchar) != 2:
            report['reason'] = 'UNSUPPORTED_HOST'
        else:
            # Lexical location of this executing source, not an ambient root
            # override or realpath/resolve that traverses outside the checkout.
            root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            report = inspect(root, os.getcwd(), os.environ, load_readers)
        output = encode_report(report)
    except Exception:
        report = unknown_report()
        output = encode_report(report)
    sys.stdout.write(output)  # One <=4KiB JSON line; no raw exception/log tail.
    return 0 if report['outcome'] == 'OBSERVED' else 78


if __name__ == '__main__':
    sys.exit(main())
