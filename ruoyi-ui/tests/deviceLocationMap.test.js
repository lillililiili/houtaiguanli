import { afterEach, describe, expect, it, vi } from 'vitest';
import { devicePosition, loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

afterEach(() => vi.unstubAllGlobals());
describe('设备地图坐标与离线资源', () => {
  it('保留有效零值，拒绝缺失、非数值、越界和未确认坐标系', () => {
    expect(devicePosition({ longitude: '118.65924', latitude: 37.43, coordinate_system: 'WGS84' })).toEqual([118.65924, 37.43]);
    expect(devicePosition({ longitude: 118.59, latitude: 37.4628, coordinate_system: 'WGS-84' })).toEqual([118.59, 37.4628]);
    expect(devicePosition({ longitude: 0, latitude: 0, coordinate_system: 'WGS84' })).toEqual([0, 0]);
    for (const longitude of [null, undefined, '', ' ', false, [], 'abc', Infinity, 181]) {
      expect(devicePosition({ longitude, latitude: 37.43, coordinate_system: 'WGS84' })).toBeNull();
    }
    expect(devicePosition({ longitude: 118, latitude: 91, coordinate_system: 'WGS84' })).toBeNull();
    expect(devicePosition({ longitude: 118, latitude: 37, coordinate_system: 'GCJ02' })).toBeNull();
    expect(devicePosition({ longitude: 118, latitude: 37 })).toBeNull();
  });

  it('优先使用已发布底图，重写离线瓦片、字体、图标地址', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ manifest: '/map-data/packages/current/manifest.json' }))
      .mockResolvedValueOnce(response({ coordinateSystem: 'WGS84', archive: './city.pmtiles', style: './style.json', bounds: [117, 36, 119, 38], maxZoom: 15 }))
      .mockResolvedValueOnce(response({ version: 8, sources: { protomaps: {} }, layers: [], glyphs: './fonts/{fontstack}/{range}.pbf', sprite: './sprites/light' }));
    vi.stubGlobal('fetch', fetch);
    const { style } = await loadDeviceMapStyle(new AbortController().signal);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(style.sources.protomaps.url).toBe(`pmtiles://${window.location.origin}/map-data/packages/current/city.pmtiles`);
    expect(style.glyphs).toContain('/map-data/packages/current/fonts/{fontstack}/{range}.pbf');
    expect(style.sprite).toContain('/map-data/packages/current/sprites/light');
  });

  it('无发布指针时回退内置配置；资源失败不伪装为已加载', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ status: 204 }).mockResolvedValueOnce(response({ manifest: '/map-data/default/manifest.json' }))
      .mockResolvedValueOnce({ ok: false, status: 500 });
    vi.stubGlobal('fetch', fetch);
    await expect(loadDeviceMapStyle(new AbortController().signal)).rejects.toThrow('读取失败');
    expect(fetch.mock.calls[1][0]).toBe(`${window.location.origin}/map-config.json`);
  });
});
function response(value) { return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }), json: async () => value }; }
