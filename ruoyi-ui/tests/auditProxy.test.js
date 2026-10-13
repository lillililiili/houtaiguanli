// @vitest-environment node
import { expect, it } from 'vitest';
import viteConfig from '../vite.config.js';

it.each([
  ['192.0.2.11', false, 'http'],
  ['2001:db8::11', true, 'https']
])('管理端代理覆盖伪造的转发头，保留实际连接地址 %s', (ip, encrypted, protocol) => {
  const config = viteConfig({ mode: 'test' });
  const proxy = Object.values(config.server.proxy).find(item => item.configure);
  let forward;
  proxy.configure({ on: (event, callback) => { if (event === 'proxyReq') forward = callback; } });
  const headers = new Map([['X-Forwarded-For', '203.0.113.99'], ['Forwarded', 'for=203.0.113.99'],
    ['X-Real-IP', '203.0.113.99'], ['X-Forwarded-Proto', 'forged']]);
  forward({ setHeader: (key, value) => headers.set(key, value), removeHeader: key => headers.delete(key) },
    { socket: { remoteAddress: ip, encrypted }, headers: {} });
  expect(headers.get('X-Forwarded-For')).toBe(ip);
  expect(headers.get('X-Forwarded-Proto')).toBe(protocol);
  expect(headers.has('Forwarded')).toBe(false);
  expect(headers.has('X-Real-IP')).toBe(false);
});
