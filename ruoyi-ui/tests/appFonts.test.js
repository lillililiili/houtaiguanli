import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadAppFonts, startWithAppFonts } from '../src/services/appFonts.js';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  delete document.fonts;
  document.body.replaceChildren();
});

function fontConstructor(load = vi.fn().mockResolvedValue(undefined)) {
  return vi.fn(function (family, source, descriptors) {
    Object.assign(this, { family, source, descriptors, load });
  });
}

describe('bundled font readiness before canvas/map creation', () => {
  it('waits for both the Chinese and monospace face', async () => {
    let finishMono;
    const load = vi.fn().mockResolvedValueOnce([{}])
      .mockImplementationOnce(() => new Promise(resolve => { finishMono = resolve; }));
    let ready = false;
    const promise = loadAppFonts({ load }).then(() => { ready = true; });
    await Promise.resolve();
    expect(ready).toBe(false);
    finishMono([{}]);
    await promise;
    expect(ready).toBe(true);
    expect(load.mock.calls.map(call => call[0])).toEqual([
      '400 14px "Noto Sans SC"', '400 14px "Noto Sans Mono"'
    ]);
  });

  it.each([0, 1])('registers the bundled face when CSS has not registered font %i', async missing => {
    const load = vi.fn().mockResolvedValueOnce(missing === 0 ? [] : [{}])
      .mockResolvedValueOnce(missing === 1 ? [] : [{}]);
    const add = vi.fn();
    const Font = fontConstructor();
    await loadAppFonts({ load, add }, Font);
    expect(Font).toHaveBeenCalledTimes(1);
    expect(Font.mock.calls[0]).toEqual(missing === 0 ? [
      'Noto Sans SC', 'url("/fonts/NotoSansSC-Regular.woff2") format("woff2")',
      { style: 'normal', weight: '400', display: 'block' }
    ] : [
      'Noto Sans Mono', 'url("/fonts/NotoSansMono-Variable.woff2") format("woff2")',
      { style: 'normal', weight: '100 900', display: 'block' }
    ]);
    expect(add).toHaveBeenCalledWith(Font.mock.instances[0]);
  });

  it('waits for the actual bundled download before registering a previously missing face', async () => {
    let finish;
    const Font = fontConstructor(() => new Promise(resolve => { finish = resolve; }));
    const fonts = { load: vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([{}]), add: vi.fn() };
    const promise = loadAppFonts(fonts, Font);
    await Promise.resolve();
    expect(fonts.add).not.toHaveBeenCalled();
    finish();
    await promise;
    expect(fonts.add).toHaveBeenCalledTimes(1);
  });

  it('recovers a failed CSS face using a new face at the configured deployment base', async () => {
    vi.stubEnv('BASE_URL', '/management/');
    const Font = fontConstructor();
    const fonts = { load: vi.fn().mockRejectedValueOnce(new Error('cached failure')).mockResolvedValueOnce([{}]), add: vi.fn() };
    await loadAppFonts(fonts, Font);
    expect(Font.mock.calls[0][1]).toBe('url("/management/fonts/NotoSansSC-Regular.woff2") format("woff2")');
    expect(fonts.add).toHaveBeenCalledTimes(1);
  });

  it('reports an actual bundled font download failure without accepting fallback', async () => {
    const Font = fontConstructor(vi.fn().mockRejectedValue(new Error('missing bundled file')));
    const fonts = { load: vi.fn().mockResolvedValue([]), add: vi.fn() };
    await expect(loadAppFonts(fonts, Font)).rejects.toThrow('missing bundled file');
    expect(fonts.add).not.toHaveBeenCalled();
  });

  it('shows loading feedback, retries in place, and mounts only after fonts recover', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    Object.defineProperty(document, 'fonts', {
      configurable: true, value: { load: vi.fn().mockResolvedValue([]), add: vi.fn() }
    });
    let fail = true;
    vi.stubGlobal('FontFace', fontConstructor(() => fail
      ? Promise.reject(new Error('temporarily offline')) : Promise.resolve()));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const start = vi.fn();
    const pending = startWithAppFonts(start, host);
    expect(host.getAttribute('role')).toBe('status');
    await pending;
    expect(start).not.toHaveBeenCalled();
    expect(host.getAttribute('role')).toBe('alert');
    expect(host.textContent).toContain('无需安装字体');
    fail = false;
    host.querySelector('button').click();
    expect(host.getAttribute('role')).toBe('status');
    await vi.waitFor(() => expect(start).toHaveBeenCalledTimes(1));
    expect(host.hasAttribute('role')).toBe(false);
    expect(document.documentElement.style.getPropertyValue('--app-font-sans')).toBe('"Noto Sans SC"');
  });

  it('does not present an application mounting exception as a font error', async () => {
    const host = document.createElement('div');
    Object.defineProperty(document, 'fonts', { configurable: true, value: { load: vi.fn().mockResolvedValue([{}]) } });
    await expect(startWithAppFonts(() => { throw new Error('app error'); }, host)).rejects.toThrow('app error');
    expect(host.hasAttribute('role')).toBe(false);
    expect(host.textContent).not.toContain('字体资源');
  });
});
