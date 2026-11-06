#!/usr/bin/env python3
"""Counts and fixed authored failure codes only; never raw failure bodies."""
import json,os,re
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
counts=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
runtime_codes={}
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
media_tests=0
paths=list((root/'kmpyt/build/test-results').glob('**/TEST-*.xml'))
paths+=list((root/'kmpyt/build/outputs/androidTest-results').glob('**/TEST-*.xml'))
for path in paths:
    if not path.resolve().is_relative_to(root) or path.stat().st_size>20*1024*1024:
        raise SystemExit('Unsafe/oversized JUnit input rejected')
    value=ET.parse(path).getroot()
    suites=[value] if value.tag=='testsuite' else value.findall('testsuite')
    for suite in suites:
        for test in suite.findall('testcase'):
            seen_codes=set()
            for failure in test.findall('failure')+test.findall('error'):
                text=failure.attrib.get('message','')+'\n'+''.join(failure.itertext())
                seen_codes.update(code for code in re.findall(r'\bOFFLINE_[A-Z0-9_]+\b',text)
                    if code in safe_runtime_codes)
            for code in seen_codes:
                runtime_codes[code]=runtime_codes.get(code,0)+1
            if path.parent.name=='jvmTest' and test.attrib.get('classname','').endswith('.JvmYouTubeDownloaderTest') and test.attrib.get('name','').startswith('actualSeparateVideoAndAudioAreDownloadedAndCombinedByCallerFfmpeg'):
                if test.find('skipped') is None and test.find('failure') is None and test.find('error') is None:
                    media_tests+=1
        counts['suites']+=1
        for key in ('tests','failures','errors','skipped'):
            count=int(suite.attrib.get(key,'0'))
            if count<0: raise SystemExit('Negative JUnit count')
            counts[key]+=count
message='## kmpYT scoped CI\n\nJob status: '+os.environ.get('KMPYT_CI_JOB_STATUS','see job exit')+'\n\n'
message+='Discovered JUnit counts: `'+json.dumps(counts,sort_keys=True)+'`\n\n'
message+='Allowlisted offline-runtime failure codes (failing testcase counts): `'+json.dumps(runtime_codes,sort_keys=True)+'`\n\n'
message+='Missing/zero counts are NOT executed tests. Native boundary failures remain failed checks. No artifact/cache uploads or physical-device/source-availability/installation claim.\n'
print(message)
if os.environ.get('GITHUB_STEP_SUMMARY'):
    # Explicit reporting channel, not application scratch or cache storage.
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a',encoding='utf-8') as stream: stream.write(message)
if '--require-executed' in sys.argv and (counts['tests']<=counts['skipped'] or counts['failures'] or counts['errors']):
    raise SystemExit('Required executed passing tests missing')
if '--require-media' in sys.argv and media_tests!=1:
    raise SystemExit('Required actual FFmpeg remux/decode JVM regression did not execute and pass exactly once')
