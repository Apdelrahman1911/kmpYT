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
function readActivePortEndpoint() {
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
    return activePortEndpoint(bytes.subarray(0, length).toString('utf8'));
  } catch (error) {
    if (error.safeCode) throw error;
    if (['ENOENT', 'EBUSY', 'EACCES', 'EPERM'].includes(error.code)) return undefined;
    throw failure('OFFLINE_BROWSER_ENDPOINT_FILE_INVALID');
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor);
  }
}
function discoverBrowserEndpoint(child) {
  return new Promise((resolve, reject) => {
    let text = '', settled = false, pollTimer;
    const finish = (error, endpoint) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer); clearTimeout(pollTimer);
      child.removeListener('exit', onExit);
      child.stderr.removeListener('data', onData);
      child.stderr.resume(); // Drain without retaining or disclosing later logs.
      error ? reject(error) : resolve(endpoint);
    };
    // Cold startup remains separate from CDP/JS limits. Never extend this bound
    // for missing stderr, incomplete files or an unresponsive browser.
    const timer = setTimeout(() => finish(failure('OFFLINE_BROWSER_STARTUP_TIMEOUT')), 30000);
    const onExit = () => finish(failure('OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT'));
    const onData = data => {
      if (child.exitCode !== null || child.signalCode !== null) return onExit();
      text = (text + data.toString()).slice(-16000);
      const lines = text.split('\n');
      // Chromium's stderr announcement is newline-terminated. Never accept an
      // unfinished line or a prefix of an invalid UUID/path across pipe chunks.
      text = lines.pop();
      for (const line of lines) {
        const match = /^DevTools listening on ws:\/\/127\.0\.0\.1:([0-9]+)(\/\S+)\r?$/.exec(line);
        const endpoint = match && activePortEndpoint(match[1] + '\n' + match[2]);
        if (endpoint) return finish(undefined, endpoint);
      }
    };
    // Leave the once-error handler attached after success so a later process
    // error cannot become an unhandled EventEmitter error containing paths.
    child.once('error', () => finish(failure('OFFLINE_BROWSER_SPAWN_FAILED')));
    child.once('exit', onExit);
    child.stderr.on('data', onData);
    const poll = () => {
      if (settled) return;
      if (child.exitCode !== null || child.signalCode !== null) return onExit();
      try {
        const endpoint = readActivePortEndpoint();
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
    } catch (error) { output({ ok: false, error: /^[A-Za-z0-9_$:-]{1,128}$/.test(error.safeCode || '') ? error.safeCode : 'OFFLINE_RUNTIME_FAILURE' }); }
  });
});
lines.on('close', () => { chain.finally(close).finally(() => process.exit(0)); });
