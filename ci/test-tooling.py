#!/usr/bin/env python3
"""Cheap scoped-tooling checks. No Gradle, browser, emulator or simulator."""
import json,os
from pathlib import Path
import shutil,subprocess,tempfile,unittest

ROOT=Path(__file__).resolve().parents[1]

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

if __name__=='__main__': unittest.main(verbosity=2)
