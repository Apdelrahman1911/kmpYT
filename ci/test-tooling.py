#!/usr/bin/env python3
"""Cheap scoped-tooling checks. No Gradle, browser, emulator or simulator."""
import hashlib,importlib.util,io,json,os
from pathlib import Path
import shutil,subprocess,tarfile,tempfile,unittest,zipfile
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('ffmpeg_tool',ROOT/'ci/ffmpeg-tool.py')
FFMPEG=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FFMPEG)

class ToolingTests(unittest.TestCase):
    def test_shell_syntax(self):
        for path in (ROOT/'ci').glob('*.sh'):
            subprocess.run(['bash','-n',str(path)],check=True)

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

if __name__=='__main__': unittest.main(verbosity=2)
