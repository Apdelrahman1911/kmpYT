#!/usr/bin/env python3
"""Cheap scoped-tooling checks. No Gradle, browser, emulator or simulator."""
import hashlib,importlib.util,io,json,os,re
from pathlib import Path
import shutil,subprocess,tarfile,tempfile,unittest,zipfile
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('ffmpeg_tool',ROOT/'ci/ffmpeg-tool.py')
FFMPEG=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FFMPEG)
PROVENANCE_SPEC=importlib.util.spec_from_file_location('js_tool_provenance',ROOT/'ci/js-tool-provenance.py')
PROVENANCE=importlib.util.module_from_spec(PROVENANCE_SPEC)
PROVENANCE_SPEC.loader.exec_module(PROVENANCE)

class ToolingTests(unittest.TestCase):
    def test_ci_runs_once_per_pr_update_and_keeps_all_checks(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        self.assertIn('on:\n  push:\n    branches: [main]\n  pull_request:\n  workflow_dispatch:\n',workflow)
        for job in ('license','jvm','android','apple'):
            self.assertIn('\n  '+job+':\n',workflow)
        for check in ('License and provenance policy','JVM (${{ matrix.check_name }})','Android API ${{ matrix.api }}','iOS simulator and XCFramework'):
            self.assertIn('name: '+check,workflow)
        self.assertNotIn('continue-on-error:',workflow)

    def test_shell_syntax(self):
        for path in (ROOT/'ci').glob('*.sh'):
            subprocess.run(['bash','-n',str(path)],check=True)

    def test_native_test_sources_compile_before_unchanged_runtime_gates(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        android=workflow.split('\n  android:\n',1)[1].split('\n  apple:\n',1)[0]
        apple=workflow.split('\n  apple:\n',1)[1]
        for block,task,gate in ((android,':kmpyt:compileDebugAndroidTestKotlinAndroid','ci/native-boundary.sh android'),
            (apple,':kmpyt:compileTestKotlinIosX64','ci/native-boundary.sh ios')):
            self.assertEqual(block.count(task),1)
            self.assertLess(block.index(task),block.index(gate))
            step=next(step for step in block.split('      - name: ') if task in step)
            self.assertIn("if: ${{ always() && !cancelled() && steps.checkout.outcome == 'success' }}",step)
            self.assertIn('ci/scoped.sh bash -euo pipefail',step)
            self.assertIn('source ci/select-tools.sh',step)
            self.assertNotIn('ci/native-boundary.sh',step)
        self.assertEqual(android.count('ci/native-boundary.sh android'),1)
        self.assertEqual(apple.count('ci/native-boundary.sh ios'),2)
        self.assertIn(':kmpyt:connectedDebugAndroidTest',android)
        self.assertIn(':kmpyt:iosX64Test',apple)
        self.assertIn(':kmpyt:assembleKmpYTReleaseXCFramework',apple)
        self.assertIn('xcodebuild -project ci/ios-host/KmpYTHost.xcodeproj',apple)

    def test_startup_observation_allowlists_match_across_helper_receiver_and_summary(self):
        paths=('kmpyt/src/jvmMain/resources/youtube/chromium-runtime.cjs',
            'kmpyt/src/jvmMain/kotlin/dev/kmpyt/jvm/DesktopYouTubeJavaScriptRuntime.kt','ci/summary.py')
        codes=[set(re.findall(r'''['"](OFFLINE_STARTUP_[A-Z0-9_]+)['"]''',(ROOT/path).read_text())) for path in paths]
        self.assertEqual(len(codes[0]),16)
        self.assertEqual(codes[0],codes[1]); self.assertEqual(codes[0],codes[2])

    def test_browser_endpoint_fixture_regressions_without_a_browser(self):
        subprocess.run(['node','--test',str(ROOT/'kmpyt/src/jvmTest/resources/youtube/chromium-runtime-endpoint.test.cjs')],check=True)

    def test_homes_and_parent_are_preserved(self):
        before=dict(os.environ)
        names=['HOME','GRADLE_USER_HOME','KONAN_DATA_DIR','TMPDIR','TMP','TEMP','ANDROID_USER_HOME','GIT_CONFIG_GLOBAL']
        code='import json,os; print(json.dumps({k:os.environ[k] for k in '+repr(names)+'}))'
        result=subprocess.check_output(['bash',str(ROOT/'ci/scoped.sh'),'python3','-B','-c',code],text=True)
        values=json.loads(result)
        for value in values.values(): self.assertTrue(Path(value).resolve().is_relative_to(ROOT))
        self.assertEqual(values['TMPDIR'],values['TMP']); self.assertEqual(values['TMP'],values['TEMP'])
        self.assertEqual(dict(os.environ),before)

    def test_both_native_gates_fail_without_starting_services(self):
        for kind in ('android','ios'):
            result=subprocess.run(['bash',str(ROOT/'ci/native-boundary.sh'),kind],capture_output=True,text=True)
            self.assertEqual(result.returncode,78)
            self.assertIn('BLOCKED:',result.stderr)

    def test_escaping_work_symlink_rejected_before_writes(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); repo=base/'repo'; (repo/'ci').mkdir(parents=True)
            for file in ('env.sh','scoped.sh'): shutil.copy2(ROOT/'ci'/file,repo/'ci'/file)
            outside=base/'outside'; outside.mkdir()
            try: (repo/'.work').symlink_to(outside,target_is_directory=True)
            except OSError as error: self.skipTest(str(error))
            result=subprocess.run(['bash',str(repo/'ci/scoped.sh'),'true'],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0); self.assertIn('out-of-repository',result.stderr)
            self.assertEqual(list(outside.iterdir()),[])

    def test_tool_archive_paths_reject_escaping_or_wrong_roots(self):
        for name in ('/FFmpeg-n8.0.1/a','FFmpeg-n8.0.1/../../a','FFmpeg-n8.0.1/a\\b','FFmpeg-n8.0.1/C:/a','wrong/a'):
            with self.subTest(name=name),self.assertRaises(ValueError):
                FFMPEG.archive_name(name,'FFmpeg-n8.0.1')

    def test_source_link_rejected_before_any_extraction(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); archive=base/'source.tar.gz'; output=base/'output'
            with tarfile.open(archive,'w:gz') as bundle:
                plain=tarfile.TarInfo('FFmpeg-n8.0.1/first'); plain.size=2
                bundle.addfile(plain,io.BytesIO(b'ok'))
                link=tarfile.TarInfo('FFmpeg-n8.0.1/link'); link.type=tarfile.SYMTYPE; link.linkname='../outside'
                bundle.addfile(link)
            with self.assertRaises(ValueError): FFMPEG.extract_source(archive,output)
            self.assertFalse(output.exists())

    def test_windows_truncated_notice_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); archive=base/'tool.zip'; output=base/'output'
            with zipfile.ZipFile(archive,'w') as bundle:
                prefix='ffmpeg-8.0.1-essentials_build/'
                bundle.writestr(prefix+'bin/ffmpeg.exe',b'MZfixture-not-executed')
                bundle.writestr(prefix+'LICENSE','GNU GENERAL PUBLIC LICENSE Version 3\ntruncated fixture')
                bundle.writestr(prefix+'README.txt','Authored fixture, not a software grant')
            with self.assertRaises(ValueError): FFMPEG.extract_windows(archive,output)
            self.assertFalse(output.exists())

    def test_download_hash_mismatch_preserves_old_file_and_removes_partial(self):
        class Response(io.BytesIO):
            def geturl(self): return 'https://example.invalid/authored-fixture'
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            target=Path(temp)/'download'; target.write_bytes(b'old')
            with patch.object(FFMPEG,'urlopen',return_value=Response(b'wrong')):
                with self.assertRaises(ValueError):
                    FFMPEG.download('https://example.invalid/authored-fixture',target,hashlib.sha256(b'right').hexdigest(),5)
            self.assertEqual(target.read_bytes(),b'old')
            self.assertFalse(target.with_suffix('.partial').exists())

    def test_media_gate_requires_real_named_jvm_regression_not_a_skip(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); (base/'ci').mkdir(); shutil.copy2(ROOT/'ci/summary.py',base/'ci/summary.py')
            output=base/'kmpyt/build/test-results/jvmTest/TEST-fixture.xml'; output.parent.mkdir(parents=True)
            environment={k:v for k,v in os.environ.items() if k!='GITHUB_STEP_SUMMARY'}
            for child,passed in (('',True),('<skipped/>',False),('<failure/>',False)):
                output.write_text('<testsuite tests="1"><testcase classname="dev.kmpyt.jvm.JvmYouTubeDownloaderTest" name="actualSeparateVideoAndAudioAreDownloadedAndCombinedByCallerFfmpeg">'+child+'</testcase></testsuite>')
                result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-media'],capture_output=True,env=environment)
                self.assertEqual(result.returncode==0,passed)
            output.unlink()
            result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-media'],capture_output=True,env=environment)
            self.assertNotEqual(result.returncode,0)

    def test_tool_identity_is_read_only_and_omits_full_paths(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); tool=base/'authored-browser-fixture'
            content=b'#!/bin/sh\nprintf launched > should-not-exist\n'
            tool.write_bytes(content); tool.chmod(0o700)
            result=PROVENANCE.identity(tool)
            self.assertEqual(result,{'file':tool.name,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()})
            self.assertNotIn(str(base),json.dumps(result))
            self.assertEqual(sorted(path.name for path in base.iterdir()),[tool.name])
            with self.assertRaises(ValueError): PROVENANCE.identity(base)

    def test_failure_summary_only_reports_allowlisted_stage_codes(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); (base/'ci').mkdir(); shutil.copy2(ROOT/'ci/summary.py',base/'ci/summary.py')
            output=base/'kmpyt/build/test-results/jvmTest/TEST-fixture.xml'; output.parent.mkdir(parents=True)
            output.write_text('''<testsuite tests="2" failures="2"><testcase name="one">
                <failure message="OFFLINE_BROWSER_STARTUP_TIMEOUT">OFFLINE_BROWSER_STARTUP_TIMEOUT
                SECRET_FIXTURE_TOKEN https://example.invalid/private?signature=secret OFFLINE_PRIVATE_VALUE
                </failure></testcase><testcase name="two"><error>OFFLINE_CDP_TIMEOUT_EVALUATE_PROGRAM
                /private/fixture/path</error></testcase></testsuite>''')
            environment={k:v for k,v in os.environ.items() if k!='GITHUB_STEP_SUMMARY'}
            result=subprocess.check_output(['python3','-B',str(base/'ci/summary.py')],env=environment,text=True)
            self.assertIn('"OFFLINE_BROWSER_STARTUP_TIMEOUT": 1',result)
            self.assertIn('"OFFLINE_CDP_TIMEOUT_EVALUATE_PROGRAM": 1',result)
            for private in ('SECRET_FIXTURE_TOKEN','example.invalid','signature=secret','OFFLINE_PRIVATE_VALUE','/private/fixture/path'):
                self.assertNotIn(private,result)

    def test_startup_summary_requires_one_whole_bound_static_suppressed_receipt(self):
        timeout='OFFLINE_BROWSER_STARTUP_TIMEOUT'
        baseline=['OFFLINE_STARTUP_SPAWN_UNOBSERVED','OFFLINE_STARTUP_CHILD_UNCONFIRMED',
            'OFFLINE_STARTUP_STDERR_NONE','OFFLINE_STARTUP_PORT_MISSING']
        prefix='\tSuppressed: dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntime$StartupObservationReceipt: KMPYT_BROWSER_STARTUP_V1['
        def receipt(code,values): return prefix+code+';'+','.join(values)+']'
        cases=[(timeout,receipt(timeout,baseline),baseline)]
        for changed in ([],baseline+['OFFLINE_STARTUP_PRIVATE_VALUE'],baseline+['authored-secret'],
            baseline+[baseline[-1]],['OFFLINE_STARTUP_PORT_MISSING']*9,
            baseline+['OFFLINE_STARTUP_SPAWN_OBSERVED'],baseline+['OFFLINE_STARTUP_CHILD_EXIT_OBSERVED'],
            baseline+['OFFLINE_STARTUP_STDERR_SOME'],baseline+['OFFLINE_STARTUP_PORT_UNCHECKED'],
            [value.replace('CHILD_UNCONFIRMED','CHILD_NO_EXIT_OBSERVED') for value in baseline],
            [value.replace('SPAWN_UNOBSERVED','SPAWN_OBSERVED') for value in baseline],
            [value.replace('STDERR_NONE','STDERR_VALID_ANNOUNCEMENT') for value in baseline],
            [value.replace('PORT_MISSING','PORT_VALID') for value in baseline],
            baseline+['OFFLINE_STARTUP_PORT_FILE_REJECTED']):
            cases.append((timeout,receipt(timeout,changed),[]))
        valid=receipt(timeout,baseline)
        cases.extend((timeout,text,[]) for text in (
            ' '.join(baseline),valid+' authored-secret',valid+'\n'+valid,
            valid.replace('Suppressed: ','',1),valid.replace('$StartupObservationReceipt','$UntrustedFixture'),
            prefix+timeout+';'+'authored-secret'*1000+']',
            receipt('OFFLINE_BROWSER_SPAWN_FAILED',baseline)))
        for code in ('OFFLINE_CDP_CONNECTION_FAILED','OFFLINE_INPUT_INVALID','OFFLINE_RUNTIME_FAILURE',
            'OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT','OFFLINE_BROWSER_ENDPOINT_FILE_INVALID'):
            cases.append((code,receipt(code,baseline),[]))
        for code,values in (
            ('OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT',[baseline[0],'OFFLINE_STARTUP_CHILD_EXIT_OBSERVED',*baseline[2:]]),
            ('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID',baseline+['OFFLINE_STARTUP_PORT_FILE_REJECTED']),
            ('OFFLINE_PROFILE_INVALID',[*baseline[:3],'OFFLINE_STARTUP_PORT_UNCHECKED']),
            (timeout,['OFFLINE_STARTUP_SPAWN_OBSERVED','OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED',
                'OFFLINE_STARTUP_STDERR_INVALID_ANNOUNCEMENT','OFFLINE_STARTUP_PORT_MISSING',
                'OFFLINE_STARTUP_PORT_ACCESS_DENIED','OFFLINE_STARTUP_PORT_BUSY','OFFLINE_STARTUP_PORT_INCOMPLETE_OR_UNRECOGNIZED'])):
            cases.append((code,receipt(code,values),values))
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); (base/'ci').mkdir(); shutil.copy2(ROOT/'ci/summary.py',base/'ci/summary.py')
            output=base/'kmpyt/build/test-results/jvmTest/TEST-fixture.xml'; output.parent.mkdir(parents=True)
            environment={k:v for k,v in os.environ.items() if k!='GITHUB_STEP_SUMMARY'}
            for index,(primary,body,expected) in enumerate(cases):
                with self.subTest(case=index):
                    message='dev.kmpyt.extraction.YouTubeJavaScriptFailure: Offline player JavaScript failed ('+primary+').'
                    suite=ET.Element('testsuite',tests='1',failures='1')
                    test=ET.SubElement(suite,'testcase',name='authoredSymbolicReceipt')
                    failure=ET.SubElement(test,'failure',message=message)
                    failure.text=message+'\n'+body+'\nSECRET_FIXTURE_TOKEN https://private.invalid/source?signature=fixture /private/fixture'
                    output.write_text(ET.tostring(suite,encoding='unicode'))
                    result=subprocess.check_output(['python3','-B',str(base/'ci/summary.py')],env=environment,text=True)
                    reported=re.search(r'Allowlisted startup observations \(validated receipt testcase counts\): `([^`]+)`',result)
                    self.assertIsNotNone(reported)
                    self.assertEqual(json.loads(reported.group(1)),{value:1 for value in expected})
                    for private in ('SECRET_FIXTURE_TOKEN','authored-secret','private.invalid','signature=fixture','/private/fixture','OFFLINE_STARTUP_PRIVATE_VALUE'):
                        self.assertNotIn(private,result)

if __name__=='__main__': unittest.main(verbosity=2)
