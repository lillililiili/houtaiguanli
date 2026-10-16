// @vitest-environment node
import { test } from 'vitest';
import assert from 'node:assert/strict';
import { warmMapResource, warmMapStyleAssets } from '../src/services/mapResourceWarmup.js';

test('starts independent glyph and sprite requests without waiting for one another', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.window = { location: { href: 'https://maps.test/app/', origin: 'https://maps.test' }, devicePixelRatio: 2 };
  const requests = [];
  globalThis.fetch = (url, options) => { requests.push({ url, options }); return Promise.resolve({ ok: true, arrayBuffer: async () => new ArrayBuffer(0) }); };
  try {
    const signal = new AbortController().signal;
    for (const pkg of ['city-a', 'alternate-version']) {
      const style = { sprite: '/packages/' + pkg + '/sprite', glyphs: '/packages/' + pkg + '/fonts/{fontstack}/{range}.pbf',
        layers: [{ layout: { 'text-font': ['Example Regular'] } }, { layout: { 'text-font': ['Example Regular'] } },
          { layout: { 'text-font': ['Another Font'] } }, { layout: { 'text-font': ['get', 'font'] } }] };
      const before = JSON.stringify(style);
      const offset = requests.length;
      warmMapStyleAssets(style, signal);
      assert.equal(requests.length - offset, 4);
      assert.equal(JSON.stringify(style), before);
      assert.ok(requests[offset].url.endsWith('/sprite@2x.json'));
      assert.ok(requests[offset + 1].url.endsWith('/sprite@2x.png'));
      assert.ok(requests[offset + 2].url.endsWith('/Example%20Regular/0-255.pbf'));
      assert.equal(requests[offset].options.signal, signal);
      assert.equal(requests[offset].options.cache, 'default');
    }
    await Promise.resolve();
  } finally { globalThis.fetch = originalFetch; delete globalThis.window; }
});

test('does not fetch foreign, credential-bearing, invalid or cancelled resources', () => {
  const originalFetch = globalThis.fetch;
  globalThis.window = { location: { href: 'https://maps.test/', origin: 'https://maps.test' } };
  const requests = [];
  globalThis.fetch = url => { requests.push(url); return Promise.resolve({ ok: true, arrayBuffer: async () => new ArrayBuffer(0) }); };
  try {
    for (const url of ['https://other.test/font.pbf', 'https://name:secret@maps.test/font.pbf', 'file:///font.pbf', 'http://[', null])
      warmMapResource(url, new AbortController().signal);
    const cancelled = new AbortController(); cancelled.abort();
    warmMapResource('/worker.js', cancelled.signal);
    assert.deepEqual(requests, []);
    warmMapResource('/worker.js', new AbortController().signal);
    assert.equal(requests.length, 1);
  } finally { globalThis.fetch = originalFetch; delete globalThis.window; }
});

test('a failed warmup never rejects the normal map loading chain', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.window = { location: { href: 'https://maps.test/', origin: 'https://maps.test' } };
  globalThis.fetch = async () => { throw new Error('temporary network failure'); };
  try {
    assert.equal(warmMapResource('/worker.js', new AbortController().signal), undefined);
    await new Promise(resolve => setImmediate(resolve));
  } finally { globalThis.fetch = originalFetch; delete globalThis.window; }
});
