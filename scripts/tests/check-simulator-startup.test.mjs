import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { checkSimulator, runChecks, sourceId } from '../check-local-startup.mjs';

const simulatorOrigin = 'http://simulator.example.invalid';
function statusFixture(now, phase = 'RUNNING') {
  return {
    phase, scene_mode: 'realtime', batch: `sim-${randomUUID()}`, error: '',
    mqtt_reconnect_count: 2, connected: true, mqtt_ready: true, mqtt_connected: true,
    session_verified_at: now - 4_000, last_published_at: null,
    realtime: {
      state: 'RUNNING', error: '', session_verified_at: now - 2_000,
      config: { notifications_enabled: true, countermeasure_enabled: true },
      notifications: { received_count: 0, last_received_at: null, last_error: null, lease_expires_at: now + 25_000 },
      countermeasure: { listening: true }
    }
  };
}
function serve(t, status, now) {
  const requests = [];
  const routes = new Map([
    [simulatorOrigin, ['text/html', '<html>simulator</html>']],
    [`${simulatorOrigin}/api/status`, ['application/json', status]]
  ]);
  t.mock.method(Date, 'now', () => now);
  t.mock.method(globalThis, 'fetch', async (url, options = {}) => {
    requests.push({ url: String(url), method: options.method || 'GET', body: options.body });
    assert.ok(routes.has(String(url)), `Unexpected request: ${url}`);
    const [type, value, httpStatus = 200] = routes.get(String(url));
    return new Response(type === 'application/json' ? JSON.stringify(value) : value, { status: httpStatus, headers: { 'Content-Type': type } });
  });
  t.after(() => assert.ok(requests.filter(r => r.url.startsWith(simulatorOrigin)).every(r => r.method === 'GET'), 'Simulator checks only read existing state'));
  return { requests, routes };
}

for (const phase of ['IDLE', 'STOPPED', 'COMPLETED']) {
  test(`accepts accessible ${phase} without requiring login, MQTT traffic or active receivers`, async t => {
    const now = Date.now();
    const status = { ...statusFixture(now, phase), connected: false, mqtt_ready: false, mqtt_connected: false,
      session_verified_at: null, error: phase === 'STOPPED' ? 'previous session interrupted' : '', realtime: { state: 'STOPPED' } };
    serve(t, status, now);
    const result = await checkSimulator(simulatorOrigin);
    assert.equal(result.scene_active, false);
    assert.equal(result.phase, phase);
    assert.equal(result.mqtt_connected, false);
  });
}

for (const phase of ['RUNNING', 'PAUSED']) {
  test(`accepts healthy ${phase} with a new batch and no recent messages`, async t => {
    const now = Date.now() + (phase === 'PAUSED' ? 86_400_000 : 0);
    const status = statusFixture(now, phase);
    status.last_published_at = now - 3_600_000;
    serve(t, status, now);
    const result = await checkSimulator(simulatorOrigin);
    assert.equal(result.scene_active, true);
    assert.equal(result.realtime_state, 'RUNNING');
    assert.equal(result.session_verified_at, status.session_verified_at);
  });
}

test('isolated QA scene can run without the realtime receiver controller', async t => {
  const now = Date.now();
  const status = { ...statusFixture(now), scene_mode: 'isolated_qa', realtime: null };
  serve(t, status, now);
  assert.equal((await checkSimulator(simulatorOrigin)).realtime_state, null);
});

test('idle scene can have healthy independent receivers without MQTT', async t => {
  const now = Date.now();
  const status = { ...statusFixture(now, 'IDLE'), mqtt_connected: false, mqtt_ready: false, session_verified_at: null };
  serve(t, status, now);
  const result = await checkSimulator(simulatorOrigin);
  assert.equal(result.scene_active, false);
  assert.equal(result.realtime_state, 'RUNNING');
});

test('disabled notification and command transports do not require a lease or listener', async t => {
  const now = Date.now();
  const status = statusFixture(now);
  status.realtime.config = { notifications_enabled: false, countermeasure_enabled: false };
  status.realtime.notifications = {};
  status.realtime.countermeasure = { listening: false };
  serve(t, status, now);
  assert.equal((await checkSimulator(simulatorOrigin)).phase, 'RUNNING');
});

for (const phase of ['FAILED', 'PREPARING', 'STOPPING', 'UNKNOWN', 'running']) {
  test(`rejects ${phase} even when the page and status endpoint are reachable`, async t => {
    const now = Date.now();
    serve(t, statusFixture(now, phase), now);
    await assert.rejects(checkSimulator(simulatorOrigin), new RegExp(`phase=${phase}`));
  });
}

const unhealthyCases = [
  ['platform session disconnected', s => { s.connected = false; }, /平台会话或 MQTT/],
  ['MQTT not ready', s => { s.mqtt_ready = false; }, /平台会话或 MQTT/],
  ['MQTT disconnected', s => { s.mqtt_connected = false; }, /平台会话或 MQTT/],
  ['truthy string is not a connection', s => { s.mqtt_connected = 'true'; }, /平台会话或 MQTT/],
  ['active scene retains an error', s => { s.error = 'unavailable'; }, /运行错误/],
  ['missing scene session verification', s => { delete s.session_verified_at; }, /模拟任务会话核验/],
  ['stale scene session verification', (s, now) => { s.session_verified_at = now - 30_001; }, /模拟任务会话核验/],
  ['future scene session verification', (s, now) => { s.session_verified_at = now + 1; }, /模拟任务会话核验/],
  ['missing realtime controller', s => { s.realtime = null; }, /实时收发服务未运行/],
  ['stopped realtime controller during active scene', s => { s.realtime.state = 'STOPPED'; }, /实时收发服务未运行/],
  ['receiver error despite RUNNING state', s => { s.realtime.error = 'unavailable'; }, /实时收发服务有运行错误/],
  ['missing receiver session verification', s => { s.realtime.session_verified_at = null; }, /实时收发会话核验/],
  ['stale receiver session verification', (s, now) => { s.realtime.session_verified_at = now - 30_001; }, /实时收发会话核验/],
  ['future receiver session verification', (s, now) => { s.realtime.session_verified_at = now + 1; }, /实时收发会话核验/],
  ['missing receiver enablement contract', s => { delete s.realtime.config; }, /启用配置/],
  ['string enablement cannot count as configured', s => { s.realtime.config.notifications_enabled = 'false'; }, /启用配置/],
  ['missing enabled receiver lease', s => { delete s.realtime.notifications.lease_expires_at; }, /通知接收器/],
  ['expired receiver lease', (s, now) => { s.realtime.notifications.lease_expires_at = now - 1; }, /通知接收器/],
  ['lease expiring now is not live', (s, now) => { s.realtime.notifications.lease_expires_at = now; }, /通知接收器/],
  ['notification receive failure', s => { s.realtime.notifications.last_error = 'retrying'; }, /通知接收器/],
  ['enabled command receiver not listening', s => { s.realtime.countermeasure.listening = false; }, /没有监听/],
  ['truthy listener string is not listening', s => { s.realtime.countermeasure.listening = 'true'; }, /没有监听/],
  ['invalid reconnect count', s => { s.mqtt_reconnect_count = -1; }, /状态契约/],
  ['unknown runtime mode', s => { s.scene_mode = 'unknown'; }, /状态契约/]
];
for (const [name, mutate, message] of unhealthyCases) {
  test(`rejects ${name}`, async t => {
    const now = Date.now();
    const status = statusFixture(now);
    mutate(status, now);
    serve(t, status, now);
    await assert.rejects(checkSimulator(simulatorOrigin), message);
  });
}

for (const state of ['FAILED', 'STARTING', 'STOPPING', 'UNKNOWN']) {
  test(`idle scene does not conceal realtime ${state}`, async t => {
    const now = Date.now();
    const status = statusFixture(now, 'IDLE');
    status.realtime.state = state;
    serve(t, status, now);
    await assert.rejects(checkSimulator(simulatorOrigin), new RegExp(`state=${state}`));
  });
}

test('accepts the documented session health window boundary', async t => {
  const now = Date.now();
  const status = statusFixture(now);
  status.session_verified_at = now - 30_000;
  status.realtime.session_verified_at = now - 30_000;
  serve(t, status, now);
  assert.equal((await checkSimulator(simulatorOrigin)).phase, 'RUNNING');
});

test('a failed simulator makes the overall startup report fail while other checks pass', async t => {
  const now = Date.now();
  const { routes } = serve(t, statusFixture(now, 'FAILED'), now);
  const admin = 'http://admin.example.invalid';
  const api = 'http://api.example.invalid';
  const adminDir = path.resolve(`simulator-check-${randomUUID()}`);
  routes.set(`${api}/actuator/health/readiness`, ['application/json', { status: 'UP' }]);
  routes.set(admin, ['text/html', `<meta name="uav-api-base" content="/api/v1"><meta name="uav-runtime-mode" content="dev"><meta name="uav-source-id" content="${sourceId(adminDir)}"><script src="/main.js"></script>`]);
  routes.set(`${admin}/main.js`, ['text/javascript', 'export const ready = true;']);
  routes.set(`${admin}/api/v1/auth/login`, ['application/json', { ok: false, error: { code: 'VALIDATION_ERROR' } }, 400]);
  routes.set(`${admin}/api/v1/auth/me`, ['application/json', { ok: false, error: { code: 'UNAUTHORIZED' } }, 401]);
  const report = await runChecks({ api, admin, adminDir, skipBusiness: true, skipSimulator: false, simulator: simulatorOrigin });
  assert.equal(report.passed, false);
  assert.deepEqual(report.results.map(result => result.status), ['PASS', 'PASS', 'FAIL']);
  assert.match(report.results[2].detail, /phase=FAILED/);
});
