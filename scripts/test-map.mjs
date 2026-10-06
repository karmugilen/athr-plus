// Real Chromium gesture tests; tiles are stubbed locally and no scooter is contacted.
// Run: node scripts/test-map.mjs
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFile, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../android/app/src/main/assets/', import.meta.url));
const profile = await mkdtemp(path.join(tmpdir(), 'ather-map-test-'));
const server = createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    const file = path.resolve(root, '.' + pathname);
    if (!file.startsWith(root)) { response.writeHead(403).end(); return; }
    let body = await readFile(file);
    if (pathname === '/map.js') {
      body = Buffer.concat([Buffer.from('L.Map.addInitHook(function(){window.testMap=this;});\n'), body]);
    }
    const type = file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html';
    response.writeHead(200, { 'Content-Type': type }).end(body);
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = spawn(process.env.CHROMIUM || '/usr/bin/chromium', [
  '--headless', '--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking',
  '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'
], { stdio: ['ignore', 'ignore', 'pipe'] });
let socket;
try {
  const endpoint = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Chromium did not start')), 15_000);
    let output = '';
    browser.stderr.on('data', data => {
      output += data;
      const match = output.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) { clearTimeout(timeout); resolve(match[1]); }
    });
    browser.once('error', reject);
  });
  socket = new WebSocket(endpoint);
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }));
  let nextId = 0, session, tileRequests = 0, failTiles = true;
  const waiting = new Map(), runtimeErrors = [];
  const tile = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#e9ead5"/><path d="M0 128H256M128 0V256" stroke="#9aab98" stroke-width="6"/></svg>').toString('base64');
  function send(method, params = {}, sessionId = session) {
    const id = ++nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { waiting.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10_000);
      waiting.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const waiter = waiting.get(message.id);
      if (!waiter) return;
      waiting.delete(message.id);
      clearTimeout(waiter.timer);
      if (message.error) waiter.reject(new Error(JSON.stringify(message.error))); else waiter.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') {
      runtimeErrors.push(message.params.exceptionDetails.text);
    } else if (message.method === 'Fetch.requestPaused') {
      tileRequests++;
      const requestId = message.params.requestId;
      const action = failTiles ? send('Fetch.failRequest', { requestId, errorReason: 'InternetDisconnected' }) :
        send('Fetch.fulfillRequest', { requestId, responseCode: 200,
          responseHeaders: [{ name: 'Content-Type', value: 'image/svg+xml' },
            { name: 'Cache-Control', value: 'max-age=604800' }], body: tile });
      action.catch(error => runtimeErrors.push(error.message));
    }
  });
  const target = await send('Target.createTarget', { url: 'about:blank' });
  session = (await send('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Fetch.enable', { patterns: [{ urlPattern: '*tile.openstreetmap.org/*' }] });
  await send('Emulation.setDeviceMetricsOverride', { width: 400, height: 400, deviceScaleFactor: 1, mobile: true });
  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 });
  async function evaluate(expression) {
    const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
    return response.result.value;
  }
  async function until(expression) {
    const deadline = Date.now() + 7_000;
    while (Date.now() < deadline) {
      if (await evaluate(expression)) return;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error(`Condition failed: ${expression}`);
  }
  await send('Page.navigate', { url: `http://127.0.0.1:${server.address().port}/map.html` });
  await until('!!window.testMap && !!window.setMapHeading');
  assert.equal(await evaluate('document.getElementById("empty-status").hidden'), false);
  await evaluate('updateAtherMarker(12.970,77.590,5); updatePhoneMarker(12.971,77.591,8,0);');
  assert.equal(await evaluate('document.querySelector("#phone-marker-icon .phone-arrow").hidden'), true);
  assert.match(await evaluate('document.getElementById("camera-status").textContent'), /North up/);
  console.log('PASS: no compass reading keeps north up and shows a position dot');
  await until('!document.getElementById("tile-status").hidden');
  failTiles = false;
  await evaluate('retryMapTiles()');
  await until('document.getElementById("tile-status").hidden && document.querySelectorAll(".leaflet-tile-loaded").length > 0');
  console.log('PASS: tile failure is visible and retry restores loaded tiles');

  await evaluate('setMapHeading(90); followMe();');
  await until('testMap.getBearing() === 270');
  assert.equal(await evaluate('document.getElementById("north-ring").style.transform'), 'rotate(270deg)');
  assert.equal(await evaluate('document.getElementById("phone-marker-icon").style.transform'), 'rotate(0deg)');
  const northPoint = await evaluate('testMap.latLngToContainerPoint([12.972,77.591])');
  assert.ok(northPoint.x < 200, 'When facing east, north should be left');
  console.log('PASS: east-facing map puts north left and the phone arrow up');

  await evaluate('updatePhoneMarker(12.971,77.591,8,180)');
  assert.equal(await evaluate('document.getElementById("phone-marker-icon").style.transform'), 'rotate(0deg)');
  await evaluate('setMapHeading(null)');
  await until('testMap.getBearing() === 0');
  assert.equal(await evaluate('document.querySelector("#phone-marker-icon .phone-arrow").hidden'), true);
  await evaluate('setMapHeading(90)');
  await until('testMap.getBearing() === 270');
  assert.equal(await evaluate('document.querySelector("#phone-marker-icon .phone-arrow").hidden'), false);
  console.log('PASS: GPS cannot overwrite heading; unavailable compass falls back and recovers');

  await evaluate('setMapHeading(0)');
  await until('testMap.getBearing() === 0');
  for (const smallHeading of [0.4, 0.8, 1.2]) {
    await evaluate(`setMapHeading(${smallHeading})`);
    await until(`Math.abs(testMap.getBearing() - ${360 - smallHeading}) < 0.00001`);
  }
  await evaluate('setMapHeading(90)');
  await until('testMap.getBearing() === 270');
  console.log('PASS: sub-degree heading changes settle without a permanent offset');

  await evaluate('setMapHeading(0)');
  await until('testMap.getBearing() === 0');
  // Drive the production animation with deterministic frames and 50 Hz sensor updates.
  const refreshResults = await evaluate(`(() => {
    const originalRequest = window.requestAnimationFrame;
    const originalCancel = window.cancelAnimationFrame;
    const frames = new Map();
    let nextFrame = 1;
    window.requestAnimationFrame = callback => { const id = nextFrame++; frames.set(id, callback); return id; };
    window.cancelAnimationFrame = id => frames.delete(id);
    function frame(time) {
      const callbacks = [...frames.values()]; frames.clear();
      callbacks.forEach(callback => callback(time));
    }
    const results = [];
    try {
      for (const hz of [60, 90, 120, 144]) {
        const dt = 1000 / hz;
        setMapHeading(0);
        for (let i = 0; i < hz; i++) frame(i * dt);
        let lastSample = -1, maxLag = 0;
        for (let i = 1; i <= hz; i++) {
          const time = 1000 + i * dt;
          const sensorTime = Math.floor(i * dt / 20) * 20;
          if (sensorTime !== lastSample) { setMapHeading(sensorTime * 0.09); lastSample = sensorTime; }
          frame(time);
          const shown = (360 - testMap.getBearing()) % 360;
          const lag = Math.abs(((i * dt * 0.09 - shown + 540) % 360) - 180);
          maxLag = Math.max(maxLag, lag);
        }
        setMapHeading(90);
        for (let i = 1; i <= hz; i++) frame(2000 + i * dt);
        results.push({ hz, maxLag, settled: testMap.getBearing() });
      }
      return results;
    } finally {
      window.requestAnimationFrame = originalRequest;
      window.cancelAnimationFrame = originalCancel;
    }
  })()`);
  for (const result of refreshResults) {
    assert.ok(result.maxLag < 3, `Rotation lag exceeded 3 degrees at ${result.hz} Hz: ${result.maxLag}`);
    assert.equal(result.settled, 270, `Heading did not settle at ${result.hz} Hz`);
  }
  console.log('PASS: 60/90/120/144 Hz animations stay below 3 degrees lag at 90 degrees/s and settle exactly');

  const beforeTheme = await evaluate('({center:testMap.getCenter(), zoom:testMap.getZoom(), bearing:testMap.getBearing()})');
  for (const theme of ['day', 'neon', 'night']) {
    await evaluate(`setMapTheme('${theme}')`);
    assert.equal(await evaluate('document.body.dataset.theme'), theme);
    assert.deepEqual(await evaluate('({center:testMap.getCenter(), zoom:testMap.getZoom(), bearing:testMap.getBearing()})'), beforeTheme);
  }
  console.log('PASS: all themes preserve geographic position, zoom and bearing');

  await evaluate('setMapHeading(359)');
  await until('testMap.getBearing() === 1');
  await evaluate(`window.headingBearings = [];
    testMap.on('rotate', function () { headingBearings.push(testMap.getBearing()); });
    setMapHeading(1);`);
  await until('testMap.getBearing() === 359');
  assert.ok(await evaluate('headingBearings.every(b => b < 2 || b > 358)'),
    'Crossing north must use the short rotation path');
  await evaluate('setMapHeading(20); setMapHeading(40); setMapHeading(60)');
  await until('testMap.getBearing() === 300');
  await evaluate('setHeadingFrozen(true); setMapHeading(180)');
  await new Promise(resolve => setTimeout(resolve, 150));
  assert.equal(await evaluate('testMap.getBearing()'), 300);
  await evaluate('setHeadingFrozen(false)');
  await until('testMap.getBearing() === 180');
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await evaluate('setMapHeading(225)');
  await until('testMap.getBearing() === 135');
  await send('Emulation.setEmulatedMedia', { features: [] });
  console.log('PASS: frame-paced headings use the short path, latest sensor value, freeze and reduced motion');


  await evaluate('window.anchor = testMap.containerPointToLatLng([150,180])');
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 150, y: 180, id: 0 }] });
  for (let i = 1; i <= 8; i++) {
    await send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 150 + i * 10, y: 180 + i * 3, id: 0 }] });
  }
  const dragged = await evaluate('testMap.latLngToContainerPoint(window.anchor)');
  assert.ok(Math.abs(dragged.x - 230) < 5 && Math.abs(dragged.y - 204) < 5,
    `Rotated drag moved anchor incorrectly: ${JSON.stringify(dragged)}`);
  const frozen = await evaluate('({center:testMap.getCenter(), bearing:testMap.getBearing()})');
  await evaluate('setMapHeading(180); updatePhoneMarker(12.980,77.600,8,180);');
  await new Promise(resolve => setTimeout(resolve, 400));
  assert.deepEqual(await evaluate('({center:testMap.getCenter(), bearing:testMap.getBearing()})'), frozen);
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await until('testMap.getBearing() === 180');
  const resumedCenter = await evaluate('testMap.getCenter()');
  assert.ok(Math.abs(resumedCenter.lat - frozen.center.lat) < 0.00001 &&
    Math.abs(resumedCenter.lng - frozen.center.lng) < 0.00001,
    'Resuming heading must preserve the manually chosen map position');
  await evaluate('setMapHeading(225)');
  await until('testMap.getBearing() === 135');
  assert.match(await evaluate('document.getElementById("camera-status").textContent'), /Browsing/);
  console.log('PASS: drag holds bearing; heading-up resumes automatically on release without recentering');

  await evaluate('window.pinchAnchor=testMap.containerPointToLatLng([200,200]); window.oldZoom=testMap.getZoom()');
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 160, y: 180, id: 0 }, { x: 240, y: 220, id: 1 }] });
  for (let i = 1; i <= 8; i++) {
    await send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [
      { x: 160 - i * 5, y: 180 - i * 2.5, id: 0 }, { x: 240 + i * 5, y: 220 + i * 2.5, id: 1 }
    ] });
  }
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  const pinch = await evaluate('({point:testMap.latLngToContainerPoint(window.pinchAnchor), zoom:testMap.getZoom(), old:window.oldZoom})');
  assert.equal(pinch.zoom, pinch.old + 1);
  assert.ok(Math.abs(pinch.point.x - 200) < 4 && Math.abs(pinch.point.y - 200) < 4,
    `Pinch lost midpoint: ${JSON.stringify(pinch)}`);
  console.log('PASS: rotated pinch keeps its geographic midpoint and changes zoom');

  await evaluate('setMapMode("north"); followMe()');
  await until('testMap.getBearing() === 0');
  assert.deepEqual(await evaluate('testMap.getCenter()'), { lat: 12.980, lng: 77.600 });
  assert.equal(await evaluate('document.getElementById("phone-marker-icon").style.transform'), 'rotate(225deg)');
  await evaluate('followScooter(); updatePhoneMarker(12.982,77.602,8,225); updateAtherMarker(12.973,77.594,5)');
  // Leaflet rounds the projected center to screen pixels; allow a sub-pixel geographic error.
  await until('testMap.distance(testMap.getCenter(), [12.973,77.594]) < 2');
  assert.match(await evaluate('document.getElementById("camera-status").textContent'), /Following scooter/);
  console.log('PASS: follow scooter tracks its fixes without phone updates stealing the camera');

  await evaluate('removeAtherMarker(); removePhoneMarker()');
  assert.equal(await evaluate('document.getElementById("empty-status").hidden'), false);
  await evaluate('updateAtherMarker(999,77,5)');
  assert.equal(await evaluate('document.getElementById("empty-status").hidden'), false);
  await evaluate('updateAtherMarker(12.973,77.594,5); updatePhoneMarker(12.974,77.595,8,225); fitBoth()');
  assert.equal(await evaluate('document.getElementById("empty-status").hidden'), true);
  console.log('PASS: missing or invalid GPS shows an empty state; valid fixes restore the map');

  await send('Emulation.setDeviceMetricsOverride', { width: 400, height: 520, deviceScaleFactor: 1, mobile: true });
  await until('testMap.getSize().y === 520');
  await evaluate('fitBoth()');
  assert.equal(await evaluate('getComputedStyle(document.getElementById("map-viewport")).borderRadius'), '18px');
  if (process.env.MAP_TEST_SCREENSHOT) {
    const screenshot = await send('Page.captureScreenshot', { format: 'png' });
    await writeFile(process.env.MAP_TEST_SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
  }
  console.log('PASS: expanded rectangular map resizes correctly');
  assert.deepEqual(runtimeErrors, []);
  console.log(`PASS: north-up and Follow restore correct orientation; no JavaScript errors (${tileRequests} local tile responses)`);
} finally {
  if (socket) socket.close();
  browser.kill('SIGTERM');
  await new Promise(resolve => browser.exitCode !== null ? resolve() : browser.once('exit', resolve));
  server.close();
  await rm(profile, { recursive: true, force: true, maxRetries: 3 });
}
