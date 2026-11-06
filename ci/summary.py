#!/usr/bin/env python3
"""Counts only, never XML failure bodies, media, artifact or cache uploads."""
import json,os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
counts=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
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
message+='Missing/zero counts are NOT executed tests. Native boundary failures remain failed checks. No artifact/cache uploads or physical-device/source-availability/installation claim.\n'
print(message)
if os.environ.get('GITHUB_STEP_SUMMARY'):
    # Explicit reporting channel, not application scratch or cache storage.
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a',encoding='utf-8') as stream: stream.write(message)
if '--require-executed' in sys.argv and (counts['tests']<=counts['skipped'] or counts['failures'] or counts['errors']):
    raise SystemExit('Required executed passing tests missing')
if '--require-media' in sys.argv and media_tests!=1:
    raise SystemExit('Required actual FFmpeg remux/decode JVM regression did not execute and pass exactly once')
