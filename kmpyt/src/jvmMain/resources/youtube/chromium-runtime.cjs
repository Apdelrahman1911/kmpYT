'use strict';
// Player code runs in an offline, fresh Chromium page, never in this Node process.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
let browser, socket, session, profile, counter = 0, pending = new Map();
const offlineDocument = 'https://kmpyt.invalid/player-offline';
const offlineHtml = '<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\' \'unsafe-eval\'; connect-src \'none\'"><title>ClearMedia offline player context</title>';
const output = value => process.stdout.write(JSON.stringify(value) + '\n');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(method, params = {}, sessionId) {
  const id = ++counter;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('timeout')); }, 15000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}
async function start(executable) {
  profile = fs.mkdtempSync(path.join(os.tmpdir(), 'kmpyt-player-'));
  browser = spawn(executable, [
    '--headless=new', '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
    '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--disable-extensions', '--disable-features=MediaRouter,OptimizationHints',
    '--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>', 'about:blank'
  ], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
  const endpoint = await new Promise((resolve, reject) => {
    let text = '';
    const timer = setTimeout(() => reject(new Error('startup')), 15000);
    browser.once('error', () => { clearTimeout(timer); reject(new Error('startup')); });
    browser.once('exit', () => { clearTimeout(timer); reject(new Error('startup')); });
    browser.stderr.on('data', data => {
      text = (text + data.toString()).slice(-16000);
      const match = /DevTools listening on (ws:\/\/127\.0\.0\.1:\d+\/devtools\/browser\/[a-zA-Z0-9-]+)/.exec(text);
      if (match) { clearTimeout(timer); resolve(match[1]); }
    });
  });
  socket = new WebSocket(endpoint);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('connection')); });
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const entry = pending.get(message.id);
      if (!entry) return;
      pending.delete(message.id); clearTimeout(entry.timer);
      message.error ? entry.reject(new Error('protocol')) : entry.resolve(message.result);
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
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error('closed')); }
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
  if (navigation.errorText) throw new Error('offline document');
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { ready = await evaluate('location.href===' + JSON.stringify(offlineDocument) + '&&document.readyState==="complete"&&document.cookie===""'); } catch (_) {}
    if (ready) break;
    await delay(25);
  }
  if (!ready) throw new Error('offline document');
}
async function evaluate(expression) {
  const result = await request('Runtime.evaluate', { expression, returnByValue: true,
    awaitPromise: true, timeout: 10000, userGesture: false }, session);
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
  if (profile && path.dirname(profile) === os.tmpdir() && path.basename(profile).startsWith('kmpyt-player-')) {
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
        if (browser || typeof command.program !== 'string' || command.program.length > 5200000) throw new Error('input');
        await start(command.executable);
        await evaluate(command.program);
        const capabilities = await evaluate('JSON.stringify(globalThis.__kmpyt_capabilities)');
        output({ ok: true, capabilities: JSON.parse(capabilities) });
      } else if (command.action === 'call') {
        if (!['signature', 'throttling'].includes(command.kind) || typeof command.input !== 'string' || command.input.length > 16384) throw new Error('input');
        const result = await evaluate('globalThis.__kmpyt_transform(' + JSON.stringify(command.kind) + ',' + JSON.stringify(command.input) + ')');
        if (typeof result !== 'string' || result.length < 1 || result.length > 65536) throw new Error('output');
        output({ ok: true, result });
      } else if (command.action === 'close') {
        await close(); output({ ok: true }); process.exit(0);
      } else throw new Error('input');
    } catch (error) { output({ ok: false, error: /^[A-Za-z0-9_$:-]{1,128}$/.test(error.safeCode || '') ? error.safeCode : 'OFFLINE_RUNTIME_FAILURE' }); }
  });
});
lines.on('close', () => { chain.finally(close).finally(() => process.exit(0)); });
