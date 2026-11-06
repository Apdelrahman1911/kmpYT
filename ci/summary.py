#!/usr/bin/env python3
"""Counts and fixed authored failure codes only; never raw failure bodies."""
import json,os,re
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
counts=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
runtime_codes={}
startup_observations={}
# These are authored stage names, not a pattern that permits arbitrary source
# strings. Never print XML failure bodies, browser stderr, URLs or variables.
safe_runtime_codes={
    'OFFLINE_PROFILE_INVALID', 'OFFLINE_INPUT_INVALID', 'OFFLINE_RUNTIME_FAILURE',
    'OFFLINE_BROWSER_STARTUP_TIMEOUT', 'OFFLINE_BROWSER_SPAWN_FAILED',
    'OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT', 'OFFLINE_BROWSER_ENDPOINT_FILE_INVALID',
    'OFFLINE_CDP_CONNECTION_FAILED', 'OFFLINE_CDP_CONNECTION_TIMEOUT',
    'OFFLINE_CDP_CONNECTION_CLOSED', 'OFFLINE_DOCUMENT_NAVIGATION_FAILED',
    'OFFLINE_DOCUMENT_NOT_READY', 'OFFLINE_TRANSFORM_RESULT_INVALID',
    'OFFLINE_HELPER_INITIALIZATION_FAILED', 'OFFLINE_HELPER_EXITED',
    'OFFLINE_HELPER_RESPONSE_LIMIT', 'OFFLINE_HELPER_TIMEOUT', 'OFFLINE_HELPER_PROTOCOL_FAILED',
}
safe_runtime_codes.update('OFFLINE_CDP_'+kind+'_'+stage
    for kind in ('TIMEOUT','SEND_FAILED','REJECTED','CLOSED')
    for stage in ('REQUEST','TARGET_CREATE','TARGET_ATTACH','NETWORK_ENABLE','NETWORK_BLOCK',
        'FETCH_ENABLE','DOCUMENT_FULFILL','RESOURCE_BLOCK','RUNTIME_ENABLE','PAGE_ENABLE',
        'DOCUMENT_NAVIGATE','BROWSER_CLOSE','EVALUATE_READINESS','EVALUATE_PROGRAM',
        'EVALUATE_CAPABILITIES','EVALUATE_TRANSFORM'))
startup_failure_codes={
    'OFFLINE_BROWSER_STARTUP_TIMEOUT', 'OFFLINE_BROWSER_SPAWN_FAILED',
    'OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT', 'OFFLINE_BROWSER_ENDPOINT_FILE_INVALID', 'OFFLINE_PROFILE_INVALID',
}
startup_groups={
    'spawn': {'OFFLINE_STARTUP_SPAWN_OBSERVED', 'OFFLINE_STARTUP_SPAWN_UNOBSERVED'},
    'child': {'OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED', 'OFFLINE_STARTUP_CHILD_EXIT_OBSERVED', 'OFFLINE_STARTUP_CHILD_UNCONFIRMED'},
    'stderr': {'OFFLINE_STARTUP_STDERR_NONE', 'OFFLINE_STARTUP_STDERR_SOME',
        'OFFLINE_STARTUP_STDERR_INVALID_ANNOUNCEMENT', 'OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT',
        'OFFLINE_STARTUP_STDERR_DEVTOOLS_BIND_MESSAGE', 'OFFLINE_STARTUP_STDERR_PORT_WRITE_MESSAGE',
        'OFFLINE_STARTUP_STDERR_PROFILE_LOCK_MESSAGE', 'OFFLINE_STARTUP_STDERR_PROCESS_SINGLETON_MESSAGE'},
    'port': {'OFFLINE_STARTUP_PORT_UNCHECKED', 'OFFLINE_STARTUP_PORT_MISSING', 'OFFLINE_STARTUP_PORT_ACCESS_DENIED',
        'OFFLINE_STARTUP_PORT_BUSY', 'OFFLINE_STARTUP_PORT_INCOMPLETE_OR_UNRECOGNIZED',
        'OFFLINE_STARTUP_PORT_VALID', 'OFFLINE_STARTUP_PORT_FILE_REJECTED'},
}
startup_codes=set().union(*startup_groups.values())

def startup_receipt(text):
    # Count only one complete JVM suppressed receipt, bound to the actual named
    # primary failure. Known words embedded elsewhere in a failure are not a
    # receipt. Unknown/malformed/duplicate/contradictory input is dropped whole.
    candidates=[line.strip() for line in text.splitlines() if 'KMPYT_BROWSER_STARTUP_V1[' in line]
    if len(candidates)!=1 or len(candidates[0])>1024: return ()
    prefix='Suppressed: dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntime$StartupObservationReceipt: KMPYT_BROWSER_STARTUP_V1['
    line=candidates[0]
    if not line.startswith(prefix) or not line.endswith(']'): return ()
    body=line[len(prefix):-1]
    if body.count(';')!=1: return ()
    code,values=body.split(';')
    primary_codes=set(re.findall(r'^[ \t]*(?:Caused by: )?dev\.kmpyt\.extraction\.YouTubeJavaScriptFailure: Offline player JavaScript failed \((OFFLINE_[A-Z0-9_]+)\)\.$',text,re.M))
    if code not in startup_failure_codes or code not in primary_codes: return ()
    values=values.split(',')
    if not 4<=len(values)<=8 or len(set(values))!=len(values) or any(value not in startup_codes for value in values): return ()
    def count(group): return sum(value in startup_groups[group] for value in values)
    if (count('spawn')!=1 or count('child')!=1 or count('stderr')!=1 or count('port')<1 or
        ('OFFLINE_STARTUP_PORT_UNCHECKED' in values and count('port')!=1) or
        ('OFFLINE_STARTUP_SPAWN_UNOBSERVED' in values and 'OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED' in values) or
        ('OFFLINE_STARTUP_SPAWN_OBSERVED' in values and 'OFFLINE_STARTUP_CHILD_UNCONFIRMED' in values) or
        (code=='OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT' and 'OFFLINE_STARTUP_CHILD_EXIT_OBSERVED' not in values) or
        ((code=='OFFLINE_BROWSER_ENDPOINT_FILE_INVALID')!=('OFFLINE_STARTUP_PORT_FILE_REJECTED' in values)) or
        'OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT' in values or 'OFFLINE_STARTUP_PORT_VALID' in values): return ()
    return values

media_tests=0
startup_probe_tests=0
testcase_count=0
startup_probe_method='executesNativeDomAndUrlOperationsInOfflineChromium'
paths=list((root/'kmpyt/build/test-results').glob('**/TEST-*.xml'))
paths+=list((root/'kmpyt/build/outputs/androidTest-results').glob('**/TEST-*.xml'))
for path in paths:
    if not path.resolve().is_relative_to(root) or path.stat().st_size>20*1024*1024:
        raise SystemExit('Unsafe/oversized JUnit input rejected')
    value=ET.parse(path).getroot()
    suites=[value] if value.tag=='testsuite' else value.findall('testsuite')
    for suite in suites:
        for test in suite.findall('testcase'):
            testcase_count+=1
            seen_codes=set()
            seen_startup=set()
            for failure in test.findall('failure')+test.findall('error'):
                text=failure.attrib.get('message','')+'\n'+''.join(failure.itertext())
                seen_codes.update(code for code in re.findall(r'\bOFFLINE_[A-Z0-9_]+\b',text)
                    if code in safe_runtime_codes)
                seen_startup.update(startup_receipt(text))
            for code in seen_codes:
                runtime_codes[code]=runtime_codes.get(code,0)+1
            for code in seen_startup:
                startup_observations[code]=startup_observations.get(code,0)+1
            if path.parent.name=='jvmTest' and test.attrib.get('classname','').endswith('.JvmYouTubeDownloaderTest') and test.attrib.get('name','').startswith('actualSeparateVideoAndAudioAreDownloadedAndCombinedByCallerFfmpeg'):
                if test.find('skipped') is None and test.find('failure') is None and test.find('error') is None:
                    media_tests+=1
            if (path.parent==root/'kmpyt/build/test-results/jvmTest' and
                test.attrib.get('classname')=='dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntimeTest' and
                test.attrib.get('name') in (startup_probe_method,startup_probe_method+'[jvm]') and
                not any(test.find(kind) is not None for kind in ('skipped','failure','error'))):
                startup_probe_tests+=1
        counts['suites']+=1
        for key in ('tests','failures','errors','skipped'):
            count=int(suite.attrib.get(key,'0'))
            if count<0: raise SystemExit('Negative JUnit count')
            counts[key]+=count
message='## kmpYT scoped CI\n\nJob status: '+os.environ.get('KMPYT_CI_JOB_STATUS','see job exit')+'\n\n'
message+='Discovered JUnit counts: `'+json.dumps(counts,sort_keys=True)+'`\n\n'
message+='Allowlisted offline-runtime failure codes (failing testcase counts): `'+json.dumps(runtime_codes,sort_keys=True)+'`\n\n'
message+='Allowlisted startup observations (validated receipt testcase counts): `'+json.dumps(startup_observations,sort_keys=True)+'`\n\n'
message+='Missing/zero counts are NOT executed tests. Native boundary failures remain failed checks. No artifact/cache uploads or physical-device/source-availability/installation claim.\n'
print(message)
if os.environ.get('GITHUB_STEP_SUMMARY'):
    # Explicit reporting channel, not application scratch or cache storage.
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a',encoding='utf-8') as stream: stream.write(message)
if '--require-executed' in sys.argv and (counts['tests']<=counts['skipped'] or counts['failures'] or counts['errors']):
    raise SystemExit('Required executed passing tests missing')
if '--require-media' in sys.argv and media_tests!=1:
    raise SystemExit('Required actual FFmpeg remux/decode JVM regression did not execute and pass exactly once')
if '--require-startup-probe' in sys.argv and (len(paths)!=1 or testcase_count!=1 or startup_probe_tests!=1 or
    counts!=dict(suites=1,tests=1,failures=0,errors=0,skipped=0)):
    raise SystemExit('The Windows startup probe requires exactly one executed passing named JVM testcase and no others')
