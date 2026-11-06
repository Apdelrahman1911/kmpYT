#!/usr/bin/env python3
"""Cheap scoped-tooling checks. No Gradle, browser, emulator or simulator."""
import ast,hashlib,importlib.util,io,json,os,re
from pathlib import Path,PureWindowsPath
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

def tooling_bash(environment=None,platform=None):
    """Resolve the reviewed shell explicitly; native Windows must not find WSL."""
    environment=os.environ if environment is None else environment
    platform=os.name if platform is None else platform
    if platform=='nt':
        value=environment.get('KMPYT_TEST_BASH')
        if (not isinstance(value,str) or not 1<=len(value)<=4096
                or any(ord(char)<32 for char in value)):
            raise ValueError('Windows tooling requires the workflow-local Git Bash path')
        candidate=PureWindowsPath(value)
        if (not candidate.is_absolute() or not re.fullmatch(r'[A-Za-z]:',candidate.drive)
                or candidate.name.lower()!='bash.exe'
                or '..' in re.split(r'[\\/]',value)
                or candidate.parent.name.lower()!='bin'):
            raise ValueError('Windows tooling requires an absolute installed Git Bash executable')
        installation=candidate.parent.parent
        if installation.name.lower()=='usr': installation=installation.parent
        # The override comes from the running Actions Git Bash ($BASH), converted
        # by cygpath. These read-only peer checks reject WSL/wrong installations;
        # they are not an executable signature or hostile-same-user attestation.
        required=(candidate,installation/'bin/git.exe',installation/'usr/bin/msys-2.0.dll')
        if not all(Path(str(path)).is_file() for path in required):
            raise ValueError('The selected Git Bash installation is unavailable or incomplete')
        return str(candidate)
    value=shutil.which('bash')
    if not value or not Path(value).is_absolute() or not Path(value).is_file():
        raise ValueError('An existing absolute Bash executable is required for tooling checks')
    return str(Path(value).resolve(strict=True))

def run_tooling_bash(arguments,**options):
    return subprocess.run([tooling_bash(options.get('env')),*arguments],**options)

class ToolingTests(unittest.TestCase):
    def test_ci_runs_once_per_pr_update_and_keeps_all_checks(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        self.assertIn('on:\n  push:\n    branches: [main]\n  pull_request:\n  workflow_dispatch:\n',workflow)
        for job in ('license','jvm','android','apple'):
            self.assertIn('\n  '+job+':\n',workflow)
        condition="github.event_name == 'workflow_dispatch' && (inputs.mode == 'windows-startup-probe' || inputs.mode == 'windows-known-folder-diagnostic')"
        ordinary=re.sub(r'name: \$\{\{ '+re.escape(condition)+r" && '[^']+' \|\| '([^']+)' \}\}",r'name: \1',workflow)
        for check in ('License and provenance policy','JVM (${{ matrix.check_name }})','Android API ${{ matrix.api }}','iOS simulator and XCFramework'):
            self.assertIn('name: '+check,ordinary)
        self.assertNotIn('continue-on-error:',workflow)

    def test_probe_skipped_contexts_and_concurrency_cannot_collide_with_full_mode(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        condition="github.event_name == 'workflow_dispatch' && (inputs.mode == 'windows-startup-probe' || inputs.mode == 'windows-known-folder-diagnostic')"
        names=(('license','Probe scope - license gate not executed','License and provenance policy',''),
            ('jvm','Probe scope - JVM gates not executed','JVM',' (${{ matrix.check_name }})'),
            ('android','Probe scope - Android gates not executed','Android API',' ${{ matrix.api }}'),
            ('apple','Probe scope - iOS gates not executed','iOS simulator and XCFramework',''))
        for job,probe,ordinary,suffix in names:
            block=workflow.split('\n  '+job+':\n',1)[1].split('\n\n  ',1)[0]
            self.assertIn("name: ${{ "+condition+" && '"+probe+"' || '"+ordinary+"' }}"+suffix,block)
            self.assertNotEqual(probe+suffix,ordinary+suffix)
        probe_prefix,diagnostic_prefix,ordinary_prefix='ci-windows-startup-probe-','ci-windows-known-folder-diagnostic-','ci-'
        probe_condition="github.event_name == 'workflow_dispatch' && inputs.mode == 'windows-startup-probe'"
        diagnostic_condition="github.event_name == 'workflow_dispatch' && inputs.mode == 'windows-known-folder-diagnostic'"
        self.assertIn("group: ${{ "+probe_condition+" && '"+probe_prefix+"' || "+diagnostic_condition+" && '"+diagnostic_prefix+"' || '"+ordinary_prefix+"' }}${{ github.ref }}",workflow)
        # A suffix collided across topic and topic-windows-startup-probe refs.
        # GitHub groups are case-insensitive; disjoint prefixes also cover this.
        refs=('refs/heads/topic','refs/heads/topic-windows-startup-probe',
            'refs/heads/windows-startup-probe-topic','refs/heads/topic-windows-known-folder-diagnostic','refs/heads/TOPIC',
            'refs/tags/topic','refs/pull/42/merge')
        probe_groups={(probe_prefix+ref).lower() for ref in refs}
        diagnostic_groups={(diagnostic_prefix+ref).lower() for ref in refs}
        ordinary_groups={(ordinary_prefix+ref).lower() for ref in refs}
        self.assertTrue(probe_groups.isdisjoint(ordinary_groups))
        self.assertTrue(diagnostic_groups.isdisjoint(ordinary_groups))
        self.assertTrue(diagnostic_groups.isdisjoint(probe_groups))
        self.assertNotEqual(probe_prefix+'refs/heads/topic',
            ordinary_prefix+'refs/heads/topic-windows-startup-probe')
        self.assertIn('cancel-in-progress: true',workflow)

    def test_startup_probe_is_explicit_manual_windows_only_and_does_not_weaken_default_jobs(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        self.assertIn('default: full\n        options: [full, windows-startup-probe, windows-known-folder-diagnostic]',workflow)
        ordinary="if: ${{ github.event.repository.private == false && (github.event_name != 'workflow_dispatch' || (inputs.mode != 'windows-startup-probe' && inputs.mode != 'windows-known-folder-diagnostic')) }}"
        for name in ('license','jvm','android','apple'):
            block=workflow.split('\n  '+name+':\n',1)[1].split('\n  ',1)[0]
            self.assertIn(ordinary,block)
        probe=workflow.split('\n  windows_startup_probe:\n',1)[1].split('\n\n  ',1)[0]
        self.assertIn("if: ${{ github.event.repository.private == false && github.event_name == 'workflow_dispatch' && inputs.mode == 'windows-startup-probe' }}",probe)
        self.assertIn('runs-on: windows-2022',probe)
        self.assertEqual(probe.count('ci/gradle.sh'),1)
        self.assertEqual(probe.count('--tests '),1)
        self.assertIn('--tests "dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntimeTest.executesNativeDomAndUrlOperationsInOfflineChromium"',probe)
        self.assertIn('source ci/select-tools.sh windows-startup-probe',probe)
        self.assertIn('ci/summary.py --require-startup-probe',probe)
        self.assertLess(probe.index('ci/windows-browser-preflight.py'),probe.index('ci/gradle.sh'))
        for other in (':kmpyt:jvmJar',':kmpyt:connected',':kmpyt:ios','ffmpeg-tool','upload-artifact','actions/cache','continue-on-error:'):
            self.assertNotIn(other,probe)

    def test_known_folder_diagnostic_is_explicit_manual_python_only_and_bounded(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        diagnostic=workflow.split('\n  windows_known_folder_diagnostic:\n',1)[1].split('\n\n  ',1)[0]
        self.assertIn("if: ${{ github.event.repository.private == false && github.event_name == 'workflow_dispatch' && inputs.mode == 'windows-known-folder-diagnostic' }}",diagnostic)
        self.assertIn('name: Windows known-folder diagnostic (no browser)',diagnostic)
        self.assertIn('runs-on: windows-2022',diagnostic)
        self.assertIn('    timeout-minutes: 5\n',diagnostic)
        self.assertEqual(diagnostic.count('        timeout-minutes: 1\n'),2)
        for script in ('verify-license.py','test-windows-known-folder-diagnostic.py','windows-known-folder-diagnostic.py'):
            self.assertEqual(diagnostic.count('ci/scoped.sh python3 -I -B ci/'+script),1)
        self.assertEqual(diagnostic.count('ci/scoped.sh '),3)
        self.assertEqual(diagnostic.count('uses: actions/checkout@93cb6efe18208431cddfb8368fd83d5badbf9bfd'),1)
        for disabled in ('persist-credentials','set-safe-directory','submodules'):
            self.assertIn(disabled+': false',diagnostic)
        for forbidden in ('ci/test-tooling.py','select-tools.sh','gradle','node','chromium.sh',
            'windows-browser-preflight.py','summary.py','upload-artifact','actions/cache',
            'continue-on-error:','always()','needs:','mkdir','powershell','gh workflow','sleep','timeout '):
            self.assertNotIn(forbidden,diagnostic)

    def test_shell_syntax(self):
        for path in (ROOT/'ci').glob('*.sh'):
            run_tooling_bash(['-n',str(path)],check=True)

    def test_windows_tooling_bash_accepts_only_the_explicit_native_git_shell(self):
        for selected in ('C:/Program Files/Git/bin/bash.exe','C:/Program Files/Git/usr/bin/bash.exe'):
            with self.subTest(selected=selected):
                expected=str(PureWindowsPath(selected))
                present={expected,str(PureWindowsPath('C:/Program Files/Git/bin/git.exe')),
                    str(PureWindowsPath('C:/Program Files/Git/usr/bin/msys-2.0.dll'))}
                environment={'KMPYT_TEST_BASH':selected,'PATH':'authored-untrusted-search-path'}
                before=dict(environment)
                with patch.object(Path,'is_file',lambda path: str(path) in present), \
                        patch.object(shutil,'which',side_effect=AssertionError('No Windows PATH search')), \
                        patch.object(subprocess,'run',side_effect=AssertionError('No shell identity launch')):
                    self.assertEqual(tooling_bash(environment,platform='nt'),expected)
                self.assertEqual(environment,before)

    def test_windows_tooling_bash_missing_wrong_or_unavailable_never_falls_back_or_launches(self):
        values=(None,'','bash','/usr/bin/bash','C:bin/bash.exe',
            'C:/Windows/System32/bash.exe','C:/Windows/System32/wsl.exe',
            'C:/Program Files/Git/bin/cmd.exe','C:/Program Files/Git/bin/../bin/bash.exe',
            r'\\server\share\Git\bin\bash.exe',r'\\?\C:\Git\bin\bash.exe',
            'C:/Program Files/Git/bin/bash.exe\n')
        with patch.object(shutil,'which',side_effect=AssertionError('No Windows PATH fallback')), \
                patch.object(subprocess,'run',side_effect=AssertionError('No wrong-shell launch')):
            for value in values:
                with self.subTest(value=value),patch.object(Path,'is_file',return_value=True):
                    with self.assertRaises(ValueError):
                        tooling_bash({} if value is None else {'KMPYT_TEST_BASH':value},platform='nt')
            selected='C:/Program Files/Git/usr/bin/bash.exe'
            required=(selected,'C:/Program Files/Git/bin/git.exe','C:/Program Files/Git/usr/bin/msys-2.0.dll')
            for missing in required:
                absent=str(PureWindowsPath(missing))
                with self.subTest(missing=missing), \
                        patch.object(Path,'is_file',lambda path: str(path)!=absent):
                    with self.assertRaises(ValueError):
                        tooling_bash({'KMPYT_TEST_BASH':selected},platform='nt')

    def test_tooling_shell_subprocess_uses_exact_resolved_argv_and_propagates_refusal(self):
        selected=r'C:\Program Files\Git\usr\bin\bash.exe'
        arguments=['--noprofile','--norc','-c','printf "%s" "authored space"']
        environment={'KMPYT_TEST_BASH':selected}
        with patch(__name__+'.tooling_bash',return_value=selected) as resolve, \
                patch.object(subprocess,'run') as launch:
            self.assertIs(run_tooling_bash(arguments,env=environment,check=True),launch.return_value)
            resolve.assert_called_once_with(environment)
            launch.assert_called_once_with([selected,*arguments],env=environment,check=True)
        with patch(__name__+'.tooling_bash',side_effect=ValueError('authored missing shell')), \
                patch.object(subprocess,'run') as launch:
            with self.assertRaises(ValueError): run_tooling_bash(arguments)
            launch.assert_not_called()
        # Bind each of the five formerly bare-bash sites to this checked helper.
        source=ast.parse(Path(__file__).read_text())
        methods={node.name:node for node in ast.walk(source) if isinstance(node,ast.FunctionDef)}
        names=('test_shell_syntax','test_probe_tool_selection_does_not_prepare_or_execute_ffmpeg_with_mocked_tools',
            'test_homes_and_parent_are_preserved','test_both_native_gates_fail_without_starting_services',
            'test_escaping_work_symlink_rejected_before_writes')
        for name in names:
            calls=[node for node in ast.walk(methods[name]) if isinstance(node,ast.Call)]
            self.assertEqual(sum(isinstance(call.func,ast.Name) and call.func.id=='run_tooling_bash' for call in calls),1)
            for call in calls:
                if isinstance(call.func,ast.Attribute) and isinstance(call.func.value,ast.Name):
                    self.assertNotEqual(call.func.value.id,'subprocess')

    def test_posix_tooling_shell_resolves_existing_bash_without_mutating_environment(self):
        selected=str(ROOT/'authored-bash-path')
        environment={'KMPYT_TEST_BASH':'ignored Windows-only override'}
        with patch.object(shutil,'which',return_value=selected) as search, \
                patch.object(Path,'is_file',return_value=True), \
                patch.object(Path,'resolve',return_value=Path(selected)):
            self.assertEqual(tooling_bash(environment,platform='posix'),selected)
            search.assert_called_once_with('bash')
        self.assertEqual(environment,{'KMPYT_TEST_BASH':'ignored Windows-only override'})
        for value in (None,'bash'):
            with self.subTest(value=value),patch.object(shutil,'which',return_value=value):
                with self.assertRaises(ValueError): tooling_bash({},platform='posix')

    def test_windows_probe_binds_the_running_git_shell_only_inside_probe_scope(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        ordinary,probe=workflow.split('\n  windows_startup_probe:\n',1)
        self.assertNotIn('KMPYT_TEST_BASH',ordinary)
        self.assertIn('tooling_bash="$(cygpath -am "$BASH")"',probe)
        self.assertIn('tooling_bash="$tooling_bash.exe"',probe)
        self.assertIn('export KMPYT_TEST_BASH="$tooling_bash"',probe)
        self.assertLess(probe.index('export KMPYT_TEST_BASH='),probe.index('ci/test-tooling.py'))
        self.assertNotIn('export PATH=',probe)

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
        self.assertEqual(len(codes[0]),22)
        self.assertEqual(codes[0],codes[1]); self.assertEqual(codes[0],codes[2])

    def test_browser_endpoint_fixture_regressions_without_a_browser(self):
        subprocess.run(['node','--test',str(ROOT/'kmpyt/src/jvmTest/resources/youtube/chromium-runtime-endpoint.test.cjs')],check=True)

    def test_windows_preflight_with_authored_pe_and_mocked_apis_only(self):
        subprocess.run(['python3','-B',str(ROOT/'ci/test-windows-browser-preflight.py')],check=True)

    def test_windows_known_folder_diagnostic_with_mocked_apis_only(self):
        subprocess.run(['python3','-I','-B',str(ROOT/'ci/test-windows-known-folder-diagnostic.py')],check=True)

    def test_probe_tool_selection_does_not_prepare_or_execute_ffmpeg_with_mocked_tools(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); (base/'jdk/bin').mkdir(parents=True)
            (base/'jdk/bin/java.exe').write_text('authored fixture, not executed')
            environment={**os.environ,'JAVA_HOME':str(base/'jdk'),'JAVA_HOME_17_X64':str(base/'jdk'),
                'PROBE_TEST_ROOT':str(base),'KMPYT_CI_SCOPED_ENV':'1','KMPYT_CI_PROJECT_ROOT':str(ROOT)}
            script=r'''
set -euo pipefail
uname() { if [ "${1:-}" = -s ]; then printf 'MINGW64_NT-10.0\n'; else printf 'x86_64\n'; fi; }
java() { printf '    java.specification.version = 17\n'; }
node() {
    if [ "${1:-}" = -p ]; then
        if [ "$2" = process.execPath ]; then printf '%s/node.exe\n' "$PROBE_TEST_ROOT"; else printf 'v22.23.4\n'; fi
    fi
}
cygpath() { printf '%s\n' "$2"; }
python3() {
    case "${2:-}" in
        -) printf '%s/chrome.exe\n' "$PROBE_TEST_ROOT" ;;
        */js-tool-provenance.py) printf 'MOCK_PROVENANCE_ONLY\n' ;;
        *) printf 'UNEXPECTED_TOOL\n' >&2; return 97 ;;
    esac
}
source "$KMPYT_CI_PROJECT_ROOT/ci/select-tools.sh" windows-startup-probe
printf 'SELECTED_NODE=%s\nSELECTED_BROWSER=%s\n' "$KMPYT_NODE" "$KMPYT_CHROMIUM"
'''
            result=run_tooling_bash(['--noprofile','--norc','-c',script],capture_output=True,text=True,env=environment)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('MOCK_PROVENANCE_ONLY',result.stdout)
            self.assertIn('SELECTED_BROWSER='+str(base)+'/chrome.exe',result.stdout)
            self.assertNotIn('UNEXPECTED_TOOL',result.stderr)

    def test_homes_and_parent_are_preserved(self):
        before=dict(os.environ)
        names=['HOME','GRADLE_USER_HOME','KONAN_DATA_DIR','TMPDIR','TMP','TEMP','ANDROID_USER_HOME','GIT_CONFIG_GLOBAL']
        code='import json,os; print(json.dumps({k:os.environ[k] for k in '+repr(names)+'}))'
        result=run_tooling_bash([str(ROOT/'ci/scoped.sh'),'python3','-B','-c',code],stdout=subprocess.PIPE,check=True,text=True).stdout
        values=json.loads(result)
        for value in values.values(): self.assertTrue(Path(value).resolve().is_relative_to(ROOT))
        self.assertEqual(values['TMPDIR'],values['TMP']); self.assertEqual(values['TMP'],values['TEMP'])
        self.assertEqual(dict(os.environ),before)

    def test_both_native_gates_fail_without_starting_services(self):
        for kind in ('android','ios'):
            result=run_tooling_bash([str(ROOT/'ci/native-boundary.sh'),kind],capture_output=True,text=True)
            self.assertEqual(result.returncode,78)
            self.assertIn('BLOCKED:',result.stderr)

    def test_escaping_work_symlink_rejected_before_writes(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); repo=base/'repo'; (repo/'ci').mkdir(parents=True)
            for file in ('env.sh','scoped.sh'): shutil.copy2(ROOT/'ci'/file,repo/'ci'/file)
            outside=base/'outside'; outside.mkdir()
            try: (repo/'.work').symlink_to(outside,target_is_directory=True)
            except OSError as error: self.skipTest(str(error))
            result=run_tooling_bash([str(repo/'ci/scoped.sh'),'true'],capture_output=True,text=True)
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

    def test_startup_probe_gate_requires_exactly_one_actual_named_passing_testcase(self):
        method='executesNativeDomAndUrlOperationsInOfflineChromium'
        classname='dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntimeTest'
        with tempfile.TemporaryDirectory(dir=ROOT/'.work/tmp') as temp:
            base=Path(temp); (base/'ci').mkdir(); shutil.copy2(ROOT/'ci/summary.py',base/'ci/summary.py')
            output=base/'kmpyt/build/test-results/jvmTest/TEST-fixture.xml'; output.parent.mkdir(parents=True)
            environment={k:v for k,v in os.environ.items() if k!='GITHUB_STEP_SUMMARY'}
            cases=[(method,classname,'',1,True),(method+'[jvm]',classname,'',1,True),
                (method+'UnexpectedSuffix',classname,'',1,False),(method,'wrong.Class','',1,False),
                (method,classname,'<skipped/>',1,False),(method,classname,'<failure/>',1,False),
                (method,classname,'<error/>',1,False),(method,classname,'',0,False),(method,classname,'',2,False)]
            for name,owner,child,declared,passed in cases:
                with self.subTest(name=name,owner=owner,child=child,declared=declared):
                    output.write_text(f'<testsuite tests="{declared}"><testcase classname="{owner}" name="{name}">{child}</testcase></testsuite>')
                    result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-startup-probe'],capture_output=True,env=environment)
                    self.assertEqual(result.returncode==0,passed)
            case=f'<testcase classname="{classname}" name="{method}"/>'
            for body in (f'<testsuite tests="1">{case}{case}</testsuite>',
                f'<testsuite tests="2">{case}<testcase name="another"/></testsuite>',
                f'<testsuite tests="1" failures="1">{case}</testsuite>',
                '<testsuite tests="1"/>'):
                output.write_text(body)
                result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-startup-probe'],capture_output=True,env=environment)
                self.assertNotEqual(result.returncode,0)
            output.write_text(f'<testsuite tests="1">{case}</testsuite>')
            other=output.with_name('TEST-duplicate.xml'); shutil.copy2(output,other)
            result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-startup-probe'],capture_output=True,env=environment)
            self.assertNotEqual(result.returncode,0)
            output.unlink(); other.unlink()
            result=subprocess.run(['python3','-B',str(base/'ci/summary.py'),'--require-startup-probe'],capture_output=True,env=environment)
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
        for token in ('DEVTOOLS_BIND','PORT_WRITE','PROFILE_LOCK','PROCESS_SINGLETON','DEVTOOLS_POLICY','DEVTOOLS_DATA_DIR'):
            values=[*baseline[:2],'OFFLINE_STARTUP_STDERR_'+token+'_MESSAGE',baseline[3]]
            cases.append((timeout,receipt(timeout,values),values))
            cases.append((timeout,receipt(timeout,baseline+[values[2]]),[]))
            # Exact whole tokens only; these are never new primary failure codes.
            for invalid in (values[2][:-1],values[2]+'_PRIVATE',values[2]+' authored-secret'):
                cases.append((timeout,receipt(timeout,[*baseline[:2],invalid,baseline[3]]),[]))
            cases.append((values[2],receipt(values[2],values),[]))
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
