import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { checkFrontend, sourceId } from '../check-local-startup.mjs';

async function fixture(t, overrides = {}, { mode = 'built', scriptPath = '/assets/main.js', apiBase = '/api/v1' } = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'startup-check-'));
  const script = 'export const ready = true;';
  const html = `<html><head><meta name="uav-api-base" content="${apiBase}"><meta name="uav-source-id" content="${sourceId(dir)}"><meta name="uav-runtime-mode" content="${mode}"><script type="module" src="${scriptPath}"></script></head></html>`;
  await mkdir(path.join(dir, 'dist/assets'), { recursive: true });
  await writeFile(path.join(dir, 'dist/index.html'), html);
  await writeFile(path.join(dir, 'dist/assets/main.js'), script);
  const requests = [];
  const routes = {
    '/': [200, 'text/html', html],
    [scriptPath]: [200, 'text/javascript', script],
    '/api/v1/auth/login': [400, 'application/json', { ok: false, error: { code: 'VALIDATION_ERROR' } }],
    '/api/v1/auth/me': [401, 'application/json', { ok: false, error: { code: 'UNAUTHORIZED' } }],
    ...overrides
  };
  const server = createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    requests.push({ path: req.url, method: req.method, body });
    const [status, type, data] = routes[req.url] || [404, 'text/html', 'missing'];
    res.writeHead(status, { 'Content-Type': type });
    res.end(typeof data === 'string' ? data : JSON.stringify(data));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true }); });
  return { dir, requests, origin: `http://127.0.0.1:${server.address().port}` };
}

test('checks the API baked into the served page without trying a password', async t => {
  const f = await fixture(t);
  const result = await checkFrontend(f.origin, f.dir, 'BACKEND');
  assert.equal(result.api_base, '/api/v1');
  assert.equal(result.mode, 'built');
  assert.deepEqual(f.requests.find(r => r.path.endsWith('/login')), { path: '/api/v1/auth/login', method: 'POST', body: '{}' });
});

for (const [mode, query] of [['dev', `?t=${Date.now()}`], ['dev', '?v=alternate-module'], ['built', '?v=release-2']]) {
  test(`checks same-origin ${mode} assets with cache query ${query}`, async t => {
    const scriptPath = `/assets/main.js${query}`;
    const f = await fixture(t, {}, { mode, scriptPath });
    const result = await checkFrontend(f.origin, f.dir, 'FRONTEND');
    assert.equal(result.mode, mode);
    assert.ok(f.requests.some(r => r.path === scriptPath), 'request the exact resource URL, including its query');
    assert.ok(f.requests.some(r => r.path === '/api/v1/auth/login'));
    assert.ok(f.requests.some(r => r.path === '/api/v1/auth/me'));
  });
}

test('still compares cached built assets with the local build', async t => {
  const f = await fixture(t, {}, { scriptPath: '/assets/main.js?v=previous-build' });
  await writeFile(path.join(f.dir, 'dist/assets/main.js'), 'new build');
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /构建产物/);
});

test('timestamped dev resources still require actual JavaScript', async t => {
  const scriptPath = `/src/main.js?t=${Date.now()}`;
  const f = await fixture(t, { [scriptPath]: [200, 'text/html', '<html>fallback</html>'] }, { mode: 'dev', scriptPath });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'FRONTEND'), /静态资源/);
});

for (const scriptPath of ['https://example.invalid/main.js?t=1', '/assets/main.js?t=1#fragment']) {
  test(`rejects unsafe resource URL ${scriptPath}`, async t => {
    const f = await fixture(t, {}, { mode: 'dev', scriptPath });
    await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'));
    assert.deepEqual(f.requests.map(r => r.path), ['/']);
  });
}

for (const apiBase of ['/api/v1?version=1', 'https://example.invalid/api/v1', '/api/v1#fragment']) {
  test(`rejects unsafe API metadata ${apiBase}`, async t => {
    const f = await fixture(t, {}, { apiBase });
    await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'));
    assert.deepEqual(f.requests.map(r => r.path), ['/']);
  });
}

test('a cached development entry does not hide a broken API proxy', async t => {
  const f = await fixture(t, { '/api/v1/auth/login': [404, 'text/html', 'missing'] },
    { mode: 'dev', scriptPath: '/assets/main.js?t=2' });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /登录代理/);
});
for (const [name, route] of [
  ['wrong /api proxy even when /dev-api works', [404, 'text/html', '']],
  ['SPA fallback with HTTP 200', [200, 'text/html', '<html>login</html>']],
  ['unexpected validation contract', [400, 'application/json', { ok: false, error: { code: 'INTERNAL_ERROR' } }]]
]) test(`rejects ${name}`, async t => {
  const f = await fixture(t, { '/api/v1/auth/login': route, '/dev-api/v1/auth/login': [400, 'application/json', { ok: false, error: { code: 'VALIDATION_ERROR' } }] });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /登录代理/);
});
test('rejects a different checkout', async t => {
  const f = await fixture(t);
  await assert.rejects(checkFrontend(f.origin, `${f.dir}-other`, 'BACKEND'), /代码目录/);
});
test('rejects stale served assets', async t => {
  const f = await fixture(t);
  await writeFile(path.join(f.dir, 'dist/assets/main.js'), 'new build');
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /构建产物/);
});
test('rejects assets replaced by an HTML fallback', async t => {
  const f = await fixture(t, { '/assets/main.js': [200, 'text/html', '<html>fallback</html>'] });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /静态资源/);
});
test('rejects missing metadata instead of guessing an API prefix', async t => {
  const f = await fixture(t, { '/': [200, 'text/html', '<html>old build</html>'] });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /元信息/);
});
test('rejects absent authentication enforcement', async t => {
  const f = await fixture(t, { '/api/v1/auth/me': [200, 'application/json', { ok: true }] });
  await assert.rejects(checkFrontend(f.origin, f.dir, 'BACKEND'), /未登录拦截/);
});

test('accepts Spring Actuator vendor JSON and rejects status DOWN', async t => {
  const { checkReadiness } = await import('../check-local-startup.mjs');
  const up = await fixture(t, { '/actuator/health/readiness': [200, 'application/vnd.spring-boot.actuator.v3+json', { status: 'UP' }] });
  assert.deepEqual(await checkReadiness(up.origin), { status: 'UP' });
  const down = await fixture(t, { '/actuator/health/readiness': [200, 'application/json', { status: 'DOWN' }] });
  await assert.rejects(checkReadiness(down.origin), /尚未就绪/);
});

test('CLI writes a scoped report and exits nonzero on failed proxy', async t => {
  const { execFile } = await import('node:child_process');
  const { promisify } = await import('node:util');
  const { readFile } = await import('node:fs/promises');
  const { fileURLToPath } = await import('node:url');
  const run = promisify(execFile);
  for (const broken of [false, true]) {
    const f = await fixture(t, {
      '/actuator/health/readiness': [200, 'application/json', { status: 'UP' }],
      ...(broken ? { '/api/v1/auth/login': [404, 'text/html', 'missing'] } : {})
    });
    const reportFile = path.join(f.dir, 'report.json');
    const promise = run(process.execPath, [fileURLToPath(new URL('../check-local-startup.mjs', import.meta.url)),
      '--api', f.origin, '--admin', f.origin, '--admin-dir', f.dir, '--skip-business', '--skip-simulator', '--report', reportFile]);
    if (broken) await assert.rejects(promise, error => error.code === 1);
    else await promise;
    const report = JSON.parse(await readFile(reportFile, 'utf8'));
    assert.equal(report.passed, !broken);
    assert.equal(report.account_login, 'NOT_TESTED');
    assert.deepEqual(report.scope, { business: false, simulator: false });
  }
});
