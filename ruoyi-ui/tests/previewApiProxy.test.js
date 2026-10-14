// @vitest-environment node
import { createServer } from 'node:http';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { preview } from 'vite';
import { afterEach, expect, it, vi } from 'vitest';
import viteConfig from '../vite.config.js';

afterEach(() => vi.unstubAllEnvs());

it.each([['', '/api'], ['/dev-api', '/dev-api'], ['/dev-api', '/api']])(
  '构建预览将登录请求转发到真实 API 路径（配置=%s，请求=%s）', async (base, requestBase) => {
  const received = [];
  const upstream = createServer((req, res) => {
    received.push({ url: req.url, method: req.method, headers: req.headers });
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: { code: 'VALIDATION_ERROR' } }));
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const directory = await mkdtemp(path.join(tmpdir(), 'admin-preview-'));
  let server;
  try {
    await writeFile(path.join(directory, 'index.html'), '<html>管理后台</html>');
    vi.stubEnv('ADMIN_API_PROXY_TARGET', `http://127.0.0.1:${upstream.address().port}`);
    vi.stubEnv('VITE_APP_BASE_API', base);
    const config = viteConfig({ mode: 'production' });
    server = await preview({
      ...config, configFile: false,
      build: { ...config.build, outDir: directory },
      preview: { ...config.preview, host: '127.0.0.1', port: 0, strictPort: false, open: false }
    });
    const response = await fetch(`http://127.0.0.1:${server.httpServer.address().port}${requestBase}/v1/auth/login`, {
      method: 'POST', body: '{}',
      headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'BACKEND', 'X-Forwarded-For': '203.0.113.99' }
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ ok: false, error: { code: 'VALIDATION_ERROR' } });
    expect(received).toHaveLength(1);
    expect(received[0].url).toBe('/api/v1/auth/login');
    expect(received[0].method).toBe('POST');
    expect(received[0].headers['x-client-type']).toBe('BACKEND');
    expect(received[0].headers['x-forwarded-for']).toBe('127.0.0.1');
  } finally {
    if (server) await new Promise(resolve => server.httpServer.close(resolve));
    await new Promise(resolve => upstream.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});

it.each([
  [true, '', '/dev-api/v1'],
  [false, '', '/api/v1'],
  [false, '/dev-api/', '/dev-api/v1']
])('启动元信息跟随浏览器的 API 配置（DEV=%s）', (dev, base, expected) => {
  const config = viteConfig({ mode: dev ? 'development' : 'production' });
  const plugin = config.plugins.find(item => item.name === 'startup-metadata');
  plugin.configResolved({ command: dev ? 'serve' : 'build', env: { DEV: dev, VITE_APP_BASE_API: base } });
  const tags = Object.fromEntries(plugin.transformIndexHtml().map(tag => [tag.attrs.name, tag.attrs.content]));
  expect(tags['uav-api-base']).toBe(expected);
  expect(tags['uav-runtime-mode']).toBe(dev ? 'dev' : 'built');
  expect(tags['uav-source-id']).toMatch(/^[a-f0-9]{64}$/);
});
