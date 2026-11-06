'use strict';
// Node-only regression tests: real owned fixture files, fake child/clock/CDP.
// No browser, network endpoint, JVM, Gradle or native service is launched.
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const { PassThrough } = require('node:stream');
const { test } = require('node:test');
const vm = require('node:vm');

const root = fs.realpathSync(path.resolve(__dirname, '../../../../..'));
const temporary = fs.realpathSync(path.join(root, '.work/tmp'));
assert.ok(temporary.startsWith(root + path.sep), 'Use a repository-owned scoped temp directory');
const source = fs.readFileSync(path.resolve(__dirname, '../../../jvmMain/resources/youtube/chromium-runtime.cjs'), 'utf8');
const uuid = '01234567-89ab-cdef-0123-456789abcdef';
const browserPath = '/devtools/browser/' + uuid;
const portFile = '43123\n' + browserPath;
const endpoint = 'ws://127.0.0.1:43123' + browserPath;

function fixture(t, fsOverrides = {}) {
  const directory = fs.mkdtempSync(path.join(temporary, 'chromium-endpoint-unit-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const profile = path.join(directory, 'kmpyt-player-fixture');
  fs.mkdirSync(profile);
  const activePort = path.join(profile, 'DevToolsActivePort');
  const child = new EventEmitter();
  child.stderr = new PassThrough();
  child.exitCode = null;
  child.signalCode = null;
  const timers = new Map();
  let now = 0, timerId = 0;
  const clock = {
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, at: now + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    advance(milliseconds) {
      const until = now + milliseconds;
      for (;;) {
        const due = [...timers].filter(([, timer]) => timer.at <= until).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
        if (!due) break;
        timers.delete(due[0]); now = due[1].at; due[1].callback();
      }
      now = until;
    },
    pending() { return timers.size; }
  };
  const spawns = [];
  const context = {
    Buffer, module: { exports: {} }, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
    process: { stdin: {}, stdout: { write() {} }, exit() { throw new Error('Unexpected helper exit'); } },
    require(name) {
      if (name === 'node:fs') return { ...fs, ...fsOverrides };
      if (name === 'node:os') return { tmpdir: () => directory };
      if (name === 'node:child_process') return { spawn(...args) { spawns.push(args); return child; } };
      if (name === 'node:readline') return { createInterface: () => new EventEmitter() };
      return require(name);
    }
  };
  vm.runInNewContext(source + '\nmodule.exports={activePortEndpoint,readActivePortEndpoint,discoverBrowserEndpoint,start,setProfile(value){profile=value;}};', context);
  const api = context.module.exports;
  api.setProfile(profile);
  return { ...api, directory, profile, activePort, child, clock, spawns };
}

test('active port file accepts only canonical loopback port and full browser UUID', t => {
  const f = fixture(t);
  for (const text of [portFile, portFile + '\n', portFile.replace('\n', '\r\n') + '\r\n']) {
    assert.equal(f.activePortEndpoint(text), endpoint);
  }
  assert.equal(f.activePortEndpoint('1\n' + browserPath), 'ws://127.0.0.1:1' + browserPath);
  assert.equal(f.activePortEndpoint('65535\n' + browserPath), 'ws://127.0.0.1:65535' + browserPath);
});

test('invalid ports, URLs, traversal, credentials and partial UUIDs are never endpoints', t => {
  const f = fixture(t);
  for (const port of ['0', '-1', '65536', '999999999', '043123', ' 43123', '43123 ', '1.5', '1e3', 'NaN']) {
    assert.equal(f.activePortEndpoint(port + '\n' + browserPath), undefined, port);
  }
  for (const target of ['ws://evil.invalid' + browserPath, 'ws://user:secret@127.0.0.1:43123' + browserPath,
    '//evil.invalid/' + uuid, '/devtools/page/' + uuid, '/devtools/browser/../' + uuid,
    browserPath + '?secret=fixture', browserPath + '#fragment', browserPath + '/suffix',
    browserPath.slice(0, -1), browserPath + '\nextra', browserPath + '\0', '%2fdevtools%2fbrowser%2f' + uuid]) {
    assert.equal(f.activePortEndpoint('43123\n' + target), undefined, target);
  }
  assert.equal(f.activePortEndpoint('\uFEFF' + portFile), undefined);
  assert.equal(f.activePortEndpoint(portFile + '\n\n'), undefined);
});

test('reader uses only the regular single-link bounded owned file', t => {
  const f = fixture(t);
  assert.equal(f.readActivePortEndpoint(), undefined);
  fs.writeFileSync(f.activePort, portFile);
  assert.equal(f.readActivePortEndpoint(), endpoint);
  fs.writeFileSync(f.activePort, '43123\n/devtools/browser/partial');
  assert.equal(f.readActivePortEndpoint(), undefined);
});

test('preexisting profile content cannot select a stale endpoint or spawn a browser', async t => {
  const f = fixture(t);
  fs.writeFileSync(f.activePort, portFile);
  await assert.rejects(f.start('never-executed', f.profile), /OFFLINE_PROFILE_INVALID/);
  assert.equal(f.spawns.length, 0);
  assert.equal(fs.readFileSync(f.activePort, 'utf8'), portFile);
});

test('profile symlink cannot select another profile', async t => {
  const f = fixture(t);
  const link = path.join(f.directory, 'kmpyt-player-link');
  fs.symlinkSync(f.profile, link, 'junction');
  await assert.rejects(f.start('never-executed', link), /OFFLINE_PROFILE_INVALID/);
  assert.equal(f.spawns.length, 0);
});

test('endpoint symlink cannot read even a valid endpoint from a sibling fixture', t => {
  const f = fixture(t);
  const outside = path.join(f.directory, 'outside-fixture');
  fs.writeFileSync(outside, portFile);
  fs.symlinkSync(outside, f.activePort);
  assert.throws(f.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
  assert.equal(fs.readFileSync(outside, 'utf8'), portFile);
});

test('hardlinked endpoint and directory are rejected without reading', t => {
  const f = fixture(t);
  const outside = path.join(f.directory, 'outside-fixture');
  fs.writeFileSync(outside, portFile); fs.linkSync(outside, f.activePort);
  assert.throws(f.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
  fs.unlinkSync(f.activePort); fs.mkdirSync(f.activePort);
  assert.throws(f.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
});

test('oversized endpoint and growth beyond the read bound are rejected', t => {
  const f = fixture(t);
  fs.writeFileSync(f.activePort, Buffer.alloc(1025, 65));
  assert.throws(f.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
  const growing = fixture(t, { readSync() { return 1025; } });
  fs.writeFileSync(growing.activePort, portFile);
  assert.throws(growing.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
});

test('replacement between lstat and open fails the descriptor identity check', t => {
  const f = fixture(t, { fstatSync(fd) {
    const info = fs.fstatSync(fd);
    return { ...info, isFile: () => true, ino: info.ino + 1 };
  } });
  fs.writeFileSync(f.activePort, portFile);
  assert.throws(f.readActivePortEndpoint, /OFFLINE_BROWSER_ENDPOINT_FILE_INVALID/);
});

test('fresh file discovers an endpoint without browser stderr and clears timers', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  fs.writeFileSync(f.activePort, portFile);
  f.clock.advance(25);
  assert.equal(await pending, endpoint);
  assert.equal(f.clock.pending(), 0);
  assert.equal(f.child.listenerCount('exit'), 0);
  assert.equal(f.child.stderr.listenerCount('data'), 0);
});

test('stderr discovery is preserved and later process errors are harmless', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  f.child.stderr.emit('data', Buffer.from('DevTools listening on ' + endpoint + '\n'));
  assert.equal(await pending, endpoint);
  f.child.emit('error', new Error('private fixture detail never reported'));
  assert.equal(f.clock.pending(), 0);
});

test('partially written port file is retried, never treated as a complete target', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  fs.writeFileSync(f.activePort, portFile.slice(0, -1));
  f.clock.advance(25);
  assert.equal(f.clock.pending(), 2);
  fs.writeFileSync(f.activePort, portFile);
  f.clock.advance(25);
  assert.equal(await pending, endpoint);
  assert.equal(f.clock.pending(), 0);
});

test('missing endpoint keeps exactly the original 30000ms startup timeout', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  f.clock.advance(29999);
  assert.equal(f.clock.pending(), 2);
  f.clock.advance(1);
  await assert.rejects(pending, /OFFLINE_BROWSER_STARTUP_TIMEOUT/);
  assert.equal(f.clock.pending(), 0);
  fs.writeFileSync(f.activePort, portFile); f.clock.advance(25);
  assert.equal(f.clock.pending(), 0);
});

test('non-loopback stderr and invalid file cannot redirect the helper', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  f.child.stderr.emit('data', Buffer.from('DevTools listening on ws://remote.invalid:43123' + browserPath));
  fs.writeFileSync(f.activePort, '43123\nws://remote.invalid' + browserPath);
  f.clock.advance(30000);
  await assert.rejects(pending, /OFFLINE_BROWSER_STARTUP_TIMEOUT/);
  assert.equal(f.clock.pending(), 0);
});

test('process exit before discovery cannot reuse a later endpoint', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  f.child.exitCode = 0; f.child.emit('exit', 0);
  fs.writeFileSync(f.activePort, portFile); f.clock.advance(25);
  await assert.rejects(pending, /OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT/);
  assert.equal(f.clock.pending(), 0);
});

test('already exited child cannot authenticate an existing port file', async t => {
  const f = fixture(t);
  fs.writeFileSync(f.activePort, portFile); f.child.exitCode = 0;
  await assert.rejects(f.discoverBrowserEndpoint(f.child), /OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT/);
  assert.equal(f.clock.pending(), 0);
});

test('spawn errors preserve safe codes without disclosing child error details', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  f.child.emit('error', new Error('SECRET fixture/path'));
  await assert.rejects(pending, error => error.message === 'OFFLINE_BROWSER_SPAWN_FAILED');
  assert.equal(f.clock.pending(), 0);
});

test('profile replacement during discovery fails closed', async t => {
  const f = fixture(t);
  const pending = f.discoverBrowserEndpoint(f.child);
  const other = path.join(f.directory, 'other'); fs.mkdirSync(other);
  fs.rmdirSync(f.profile); fs.symlinkSync(other, f.profile, 'junction');
  fs.writeFileSync(path.join(other, 'DevToolsActivePort'), portFile);
  f.clock.advance(25);
  await assert.rejects(pending, /OFFLINE_PROFILE_INVALID/);
  assert.equal(f.clock.pending(), 0);
});

test('startup does not alter the browser sandbox or original offline arguments', async t => {
  const f = fixture(t);
  const pending = f.start('authored-browser-not-executed', f.profile);
  assert.equal(f.spawns.length, 1);
  const [executable, args, options] = f.spawns[0];
  assert.equal(executable, 'authored-browser-not-executed');
  assert.deepEqual(Array.from(args), [
    '--headless=new', '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
    '--user-data-dir=' + f.profile, '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--disable-extensions', '--disable-features=MediaRouter,OptimizationHints',
    '--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>', 'about:blank'
  ]);
  assert.equal(options.windowsHide, true);
  f.clock.advance(30000);
  await assert.rejects(pending, /OFFLINE_BROWSER_STARTUP_TIMEOUT/);
});
