'use strict';
// Player code runs in an offline, fresh Chromium page, never in this Node process.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
let browser, socket, session, profile, counter = 0, pending = new Map();
const offlineDocument = 'https://kmpyt.invalid/player-offline';
const offlineHtml = '<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\' \'unsafe-eval\'; connect-src \'none\'"><title>kmpYT offline player context</title>';
const output = value => process.stdout.write(JSON.stringify(value) + '\n');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const failure = code => Object.assign(new Error(code), { safeCode: code });
const startupFailureCodes = new Set([
  'OFFLINE_BROWSER_STARTUP_TIMEOUT', 'OFFLINE_BROWSER_SPAWN_FAILED',
  'OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT', 'OFFLINE_BROWSER_ENDPOINT_FILE_INVALID', 'OFFLINE_PROFILE_INVALID'
]);
const startupGroups = Object.freeze({
  spawn: ['OFFLINE_STARTUP_SPAWN_OBSERVED', 'OFFLINE_STARTUP_SPAWN_UNOBSERVED'],
  child: ['OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED', 'OFFLINE_STARTUP_CHILD_EXIT_OBSERVED', 'OFFLINE_STARTUP_CHILD_UNCONFIRMED'],
  stderr: ['OFFLINE_STARTUP_STDERR_NONE', 'OFFLINE_STARTUP_STDERR_SOME',
    'OFFLINE_STARTUP_STDERR_INVALID_ANNOUNCEMENT', 'OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT'],
  port: ['OFFLINE_STARTUP_PORT_UNCHECKED', 'OFFLINE_STARTUP_PORT_MISSING', 'OFFLINE_STARTUP_PORT_ACCESS_DENIED',
    'OFFLINE_STARTUP_PORT_BUSY', 'OFFLINE_STARTUP_PORT_INCOMPLETE_OR_UNRECOGNIZED',
    'OFFLINE_STARTUP_PORT_VALID', 'OFFLINE_STARTUP_PORT_FILE_REJECTED']
});
const startupCodes = new Set(Object.values(startupGroups).flat());
function safeStartupObservations(code, values) {
  // A receipt is all-or-nothing. Never turn arbitrary helper/child text into a
  // diagnostic, or retain a partially valid, duplicate or contradictory list.
  if (!startupFailureCodes.has(code) || !Array.isArray(values) || values.length < 4 || values.length > 8 ||
      values.some(value => typeof value !== 'string' || !startupCodes.has(value)) ||
      new Set(values).size !== values.length) return undefined;
  const count = group => values.filter(value => startupGroups[group].includes(value)).length;
  if (count('spawn') !== 1 || count('child') !== 1 || count('stderr') !== 1 || count('port') < 1 ||
      (values.includes('OFFLINE_STARTUP_PORT_UNCHECKED') && count('port') !== 1) ||
      (values.includes('OFFLINE_STARTUP_SPAWN_UNOBSERVED') && values.includes('OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED')) ||
      (values.includes('OFFLINE_STARTUP_SPAWN_OBSERVED') && values.includes('OFFLINE_STARTUP_CHILD_UNCONFIRMED')) ||
      (code === 'OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT' && !values.includes('OFFLINE_STARTUP_CHILD_EXIT_OBSERVED')) ||
      ((code === 'OFFLINE_BROWSER_ENDPOINT_FILE_INVALID') !== values.includes('OFFLINE_STARTUP_PORT_FILE_REJECTED')) ||
      // A valid endpoint settles discovery successfully, never with one of the
      // before-endpoint failure codes above. Success observations stay private.
      values.includes('OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT') || values.includes('OFFLINE_STARTUP_PORT_VALID')) return undefined;
  return [...values];
}
function failureResponse(error) {
  const code = /^[A-Za-z0-9_$:-]{1,128}$/.test(error.safeCode || '') ? error.safeCode : 'OFFLINE_RUNTIME_FAILURE';
  const startup = safeStartupObservations(code, error.startup);
  return { ok: false, error: code, ...(startup ? { startup } : {}) };
}
// Codes name authored stages only; CDP messages, stderr, URLs and source values stay private.
const requestStages = Object.freeze({
  'Target.createTarget': 'TARGET_CREATE', 'Target.attachToTarget': 'TARGET_ATTACH',
  'Network.enable': 'NETWORK_ENABLE', 'Network.setBlockedURLs': 'NETWORK_BLOCK',
  'Fetch.enable': 'FETCH_ENABLE', 'Fetch.fulfillRequest': 'DOCUMENT_FULFILL',
  'Fetch.failRequest': 'RESOURCE_BLOCK', 'Runtime.enable': 'RUNTIME_ENABLE',
  'Page.enable': 'PAGE_ENABLE', 'Page.navigate': 'DOCUMENT_NAVIGATE', 'Browser.close': 'BROWSER_CLOSE'
});
function ownedProfile() {
  try {
    if (!profile || !path.basename(profile).startsWith('kmpyt-player-')) return false;
    const info = fs.lstatSync(profile);
    return info.isDirectory() && !info.isSymbolicLink() &&
      path.dirname(fs.realpathSync(profile)) === fs.realpathSync(os.tmpdir());
  } catch (_) { return false; }
}
function activePortEndpoint(text) {
  // Chromium writes a port and browser UUID, not an arbitrary WebSocket URL.
  // In particular do not permit credentials, another host, paths or query data.
  const match = /^([1-9][0-9]{0,4})\r?\n(\/devtools\/browser\/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:\r?\n)?$/.exec(text);
  if (!match || Number(match[1]) > 65535) return undefined;
  return 'ws://127.0.0.1:' + match[1] + match[2];
}
function readActivePortEndpoint(observe = () => {}) {
  if (!ownedProfile()) throw failure('OFFLINE_PROFILE_INVALID');
  const filename = path.join(profile, 'DevToolsActivePort');
  let descriptor;
  try {
    const before = fs.lstatSync(filename);
    if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1 || before.size > 1024) {
      throw failure('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID');
    }
    descriptor = fs.openSync(filename, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    const after = fs.fstatSync(descriptor);
    if (!after.isFile() || after.nlink !== 1 || after.size > 1024 ||
        before.dev !== after.dev || before.ino !== after.ino) throw failure('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID');
    const bytes = Buffer.alloc(1025);
    const length = fs.readSync(descriptor, bytes, 0, bytes.length, 0);
    if (length > 1024) throw failure('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID');
    // An incomplete write is not an endpoint. Poll again within the original
    // startup bound; a full UUID prevents accepting a partially written path.
    const endpoint = activePortEndpoint(bytes.subarray(0, length).toString('utf8'));
    observe(endpoint ? 'OFFLINE_STARTUP_PORT_VALID' : 'OFFLINE_STARTUP_PORT_INCOMPLETE_OR_UNRECOGNIZED');
    return endpoint;
  } catch (error) {
    if (error.safeCode) { observe('OFFLINE_STARTUP_PORT_FILE_REJECTED'); throw error; }
    const retryable = error.code === 'ENOENT' ? 'OFFLINE_STARTUP_PORT_MISSING' :
      error.code === 'EBUSY' ? 'OFFLINE_STARTUP_PORT_BUSY' :
        ['EACCES', 'EPERM'].includes(error.code) ? 'OFFLINE_STARTUP_PORT_ACCESS_DENIED' : undefined;
    if (retryable) { observe(retryable); return undefined; }
    observe('OFFLINE_STARTUP_PORT_FILE_REJECTED');
    throw failure('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID');
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor);
  }
}
function discoverBrowserEndpoint(child) {
  return new Promise((resolve, reject) => {
    let text = '', settled = false, pollTimer;
    let spawned = false, exited = false, stderr = 'OFFLINE_STARTUP_STDERR_NONE';
    const portObservations = new Set();
    const finish = (error, endpoint) => {
      if (settled) return;
      settled = true;
      // Snapshot event/field observations only: no extra I/O or post-deadline
      // probes. Null exit fields before a spawn event do not prove a live child.
      if (error) error.startup = Object.freeze([
        spawned ? 'OFFLINE_STARTUP_SPAWN_OBSERVED' : 'OFFLINE_STARTUP_SPAWN_UNOBSERVED',
        exited || child.exitCode !== null || child.signalCode !== null ? 'OFFLINE_STARTUP_CHILD_EXIT_OBSERVED' :
          spawned ? 'OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED' : 'OFFLINE_STARTUP_CHILD_UNCONFIRMED',
        stderr, ...(portObservations.size ? portObservations : ['OFFLINE_STARTUP_PORT_UNCHECKED'])
      ]);
      clearTimeout(timer); clearTimeout(pollTimer);
      child.removeListener('spawn', onSpawn);
      child.removeListener('exit', onExit);
      child.stderr.removeListener('data', onData);
      child.stderr.resume(); // Drain without retaining or disclosing later logs.
      error ? reject(error) : resolve(endpoint);
    };
    // Cold startup remains separate from CDP/JS limits. Never extend this bound
    // for missing stderr, incomplete files or an unresponsive browser.
    const timer = setTimeout(() => finish(failure('OFFLINE_BROWSER_STARTUP_TIMEOUT')), 30000);
    const onSpawn = () => { spawned = true; };
    const onExit = () => { exited = true; finish(failure('OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT')); };
    const onData = data => {
      if (data.length && stderr === 'OFFLINE_STARTUP_STDERR_NONE') stderr = 'OFFLINE_STARTUP_STDERR_SOME';
      if (child.exitCode !== null || child.signalCode !== null) return onExit();
      text = (text + data.toString()).slice(-16000);
      const lines = text.split('\n');
      // Chromium's stderr announcement is newline-terminated. Never accept an
      // unfinished line or a prefix of an invalid UUID/path across pipe chunks.
      text = lines.pop();
      for (const line of lines) {
        const match = /^DevTools listening on ws:\/\/127\.0\.0\.1:([0-9]+)(\/\S+)\r?$/.exec(line);
        const endpoint = match && activePortEndpoint(match[1] + '\n' + match[2]);
        if (endpoint) { stderr = 'OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT'; return finish(undefined, endpoint); }
        if (line.startsWith('DevTools listening on ')) stderr = 'OFFLINE_STARTUP_STDERR_INVALID_ANNOUNCEMENT';
      }
    };
    // Leave the once-error handler attached after success so a later process
    // error cannot become an unhandled EventEmitter error containing paths.
    child.once('error', () => finish(failure('OFFLINE_BROWSER_SPAWN_FAILED')));
    child.once('spawn', onSpawn);
    child.once('exit', onExit);
    child.stderr.on('data', onData);
    const poll = () => {
      if (settled) return;
      if (child.exitCode !== null || child.signalCode !== null) return onExit();
      try {
        const endpoint = readActivePortEndpoint(code => portObservations.add(code));
        if (endpoint) return finish(undefined, endpoint);
      } catch (error) { return finish(error); }
      pollTimer = setTimeout(poll, 25);
    };
    poll();
  });
}
async function request(method, params = {}, sessionId, stage = requestStages[method] || 'REQUEST') {
  const id = ++counter;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(failure('OFFLINE_CDP_TIMEOUT_' + stage)); }, 15000);
    pending.set(id, { resolve, reject, timer, stage });
    try { socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) })); }
    catch (_) {
      clearTimeout(timer); pending.delete(id); reject(failure('OFFLINE_CDP_SEND_FAILED_' + stage));
    }
  });
}
async function start(executable, suppliedProfile) {
  if (typeof suppliedProfile !== 'string') throw failure('OFFLINE_PROFILE_INVALID');
  profile = path.resolve(suppliedProfile);
  if (!ownedProfile() ||
      !fs.statSync(profile).isDirectory() || fs.readdirSync(profile).length !== 0) throw failure('OFFLINE_PROFILE_INVALID');
  browser = spawn(executable, [
    '--headless=new', '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
    '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--disable-extensions', '--disable-features=MediaRouter,OptimizationHints',
    '--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>', 'about:blank'
  ], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
  // This profile was empty before spawn: never reuse a prior browser's port.
  // The owned file is Chromium's supported discovery channel for ChromeDriver
  // and Telemetry, including when a Windows GUI executable has no usable stderr.
  const endpoint = await discoverBrowserEndpoint(browser);
  try { socket = new WebSocket(endpoint); }
  catch (_) { throw failure('OFFLINE_CDP_CONNECTION_FAILED'); }
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(failure('OFFLINE_CDP_CONNECTION_TIMEOUT')), 15000);
    socket.onopen = () => { clearTimeout(timer); resolve(); };
    socket.onerror = () => { clearTimeout(timer); reject(failure('OFFLINE_CDP_CONNECTION_FAILED')); };
    socket.onclose = () => { clearTimeout(timer); reject(failure('OFFLINE_CDP_CONNECTION_CLOSED')); };
  });
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const entry = pending.get(message.id);
      if (!entry) return;
      pending.delete(message.id); clearTimeout(entry.timer);
      message.error ? entry.reject(failure('OFFLINE_CDP_REJECTED_' + entry.stage)) : entry.resolve(message.result);
    } else if (message.method === 'Fetch.requestPaused') {
      // An authored local document gives native DOM APIs a non-opaque origin. It
      // is fulfilled inside Chromium, without DNS/HTTP or any platform account.
      if (message.sessionId === session && message.params.resourceType === 'Document' &&
          message.params.request.method === 'GET' && message.params.request.url === offlineDocument) {
        request('Fetch.fulfillRequest', { requestId: message.params.requestId, responseCode: 200,
          responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
          body: Buffer.from(offlineHtml).toString('base64') }, message.sessionId).catch(() => {});
      } else request('Fetch.failRequest', { requestId: message.params.requestId, errorReason: 'BlockedByClient' }, message.sessionId).catch(() => {});
    }
  };
  socket.onclose = () => {
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(failure('OFFLINE_CDP_CLOSED_' + entry.stage)); }
    pending.clear();
  };
  const target = await request('Target.createTarget', { url: 'about:blank' });
  session = (await request('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await request('Network.enable', {}, session);
  await request('Network.setBlockedURLs', { urls: ['file:*', 'data:*', 'ftp:*'] }, session);
  await request('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] }, session);
  await request('Runtime.enable', {}, session);
  await request('Page.enable', {}, session);
  const navigation = await request('Page.navigate', { url: offlineDocument }, session);
  if (navigation.errorText) throw failure('OFFLINE_DOCUMENT_NAVIGATION_FAILED');
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { ready = await evaluate('location.href===' + JSON.stringify(offlineDocument) + '&&document.readyState==="complete"&&document.cookie===""', 'READINESS'); }
    catch (error) {
      if (/^OFFLINE_CDP_(?:TIMEOUT|CLOSED|SEND_FAILED)_/.test(error.safeCode || '')) throw error;
    }
    if (ready) break;
    await delay(25);
  }
  if (!ready) throw failure('OFFLINE_DOCUMENT_NOT_READY');
}
async function evaluate(expression, stage = 'TRANSFORM') {
  const result = await request('Runtime.evaluate', { expression, returnByValue: true,
    awaitPromise: true, timeout: 10000, userGesture: false }, session, 'EVALUATE_' + stage);
  if (result.exceptionDetails || !result.result || result.result.subtype === 'error') {
    const details = result.exceptionDetails;
    const name = details?.exception?.className;
    const safeName = ['ReferenceError','SyntaxError','TypeError','RangeError','EvalError'].includes(name) ? name : 'EvaluationError';
    const unknownGlobal = /^ReferenceError: ([A-Za-z_$][A-Za-z0-9_$]{0,80}) is not defined(?:\n|$)/.exec(details?.exception?.description || '');
    const error = new Error('evaluation');
    error.safeCode = safeName + (unknownGlobal ? ':' + unknownGlobal[1] : '') + ':line' + Math.max(0, details?.lineNumber || 0);
    throw error;
  }
  return result.result.value;
}
async function close() {
  if (socket && socket.readyState === 1) {
    try { await request('Browser.close'); } catch (_) {}
    socket.close();
  }
  if (browser && browser.exitCode === null) {
    await delay(200);
    if (browser.exitCode === null) browser.kill();
  }
  if (ownedProfile()) {
    try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }); } catch (_) {}
  }
}
const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
let chain = Promise.resolve();
lines.on('line', line => {
  chain = chain.then(async () => {
    try {
      const command = JSON.parse(line);
      if (command.action === 'prepare') {
        if (browser || typeof command.program !== 'string' || command.program.length > 5200000) throw failure('OFFLINE_INPUT_INVALID');
        await start(command.executable, command.profile);
        await evaluate(command.program, 'PROGRAM');
        const capabilities = await evaluate('JSON.stringify(globalThis.__kmpyt_capabilities)', 'CAPABILITIES');
        output({ ok: true, capabilities: JSON.parse(capabilities) });
      } else if (command.action === 'call') {
        if (!['signature', 'throttling'].includes(command.kind) || typeof command.input !== 'string' || command.input.length > 16384) throw failure('OFFLINE_INPUT_INVALID');
        const result = await evaluate('globalThis.__kmpyt_transform(' + JSON.stringify(command.kind) + ',' + JSON.stringify(command.input) + ')');
        if (typeof result !== 'string' || result.length < 1 || result.length > 65536) throw failure('OFFLINE_TRANSFORM_RESULT_INVALID');
        output({ ok: true, result });
      } else if (command.action === 'close') {
        await close(); output({ ok: true }); process.exit(0);
      } else throw failure('OFFLINE_INPUT_INVALID');
    } catch (error) { output(failureResponse(error)); }
  });
});
lines.on('close', () => { chain.finally(close).finally(() => process.exit(0)); });
