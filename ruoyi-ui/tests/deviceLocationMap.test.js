import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { devicePosition, loadDeviceMapStyle, deviceMapMinZoom, deviceMapOptions, positionInMapBounds } from '@/services/deviceLocationMap.js';

const archiveHeader = vi.hoisted(() => vi.fn());
vi.mock('pmtiles', () => ({
  PMTiles: class { getHeader() { return archiveHeader(); } },
  Protocol: class { get() {} add() {} }
}));
beforeEach(() => archiveHeader.mockResolvedValue({ tileType: 1, minLon: 117.9, minLat: 36.7, maxLon: 119.5, maxLat: 38.3, minZoom: 0, maxZoom: 15 }));

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
      .mockResolvedValueOnce(response({ coordinateSystem: 'WGS84', archive: './city.pmtiles', style: './style.json', bounds: [117, 36, 119, 38], maxZoom: 15,
        imagery: { tiles: './imagery/{z}/{x}/{y}.jpg', minZoom: 7, maxZoom: 12, attribution: '开发影像' } }))
      .mockResolvedValueOnce(response({ version: 8, sources: { protomaps: {} }, layers: [{ id: 'background', type: 'background', paint: {} }], glyphs: './fonts/{fontstack}/{range}.pbf', sprite: './sprites/light' }));
    vi.stubGlobal('fetch', fetch);
    const { style, bounds, maxZoom } = await loadDeviceMapStyle(new AbortController().signal);
    // 和前台一样读取实际包头，不把清单中的大范围误作当前地图覆盖范围。
    expect(bounds).toEqual([117.9, 36.7, 119.5, 38.3]);
    expect(style.sources.protomaps.maxzoom).toBe(15);
    expect(maxZoom).toBe(18);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(style.sources.protomaps.url).toBe(`pmtiles://${window.location.origin}/map-data/packages/current/city.pmtiles`);
    expect(style.sources.imagery.tiles[0]).toContain('/map-data/packages/current/imagery/{z}/{x}/{y}.jpg');
    expect(style.layers.find(layer => layer.id === 'theme_imagery').source).toBe('imagery');
    expect(style.layers.find(layer => layer.id === 'background').paint['background-color']).toBe('#061a3d');
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

  it.each([[117.9, 36.7, 119.5, 38.3], [111, 29, 112, 30]])('不同地图包及宽窄视口都由覆盖范围限制缩小，仍可继续放大：%j', (...bounds) => {
    for (const [width, height] of [[850, 280], [340, 700], [1600, 320]]) {
      const container = { clientWidth: width, clientHeight: height };
      const resources = { bounds, maxZoom: 18, style: {} };
      const options = deviceMapOptions(container, resources, null);
      const world = 512 * 2 ** options.minZoom;
      const toY = latitude => (1 - Math.asinh(Math.tan(latitude * Math.PI / 180)) / Math.PI) / 2;
      expect((bounds[2] - bounds[0]) / 360 * world).toBeGreaterThanOrEqual(width);
      expect((toY(bounds[1]) - toY(bounds[3])) * world).toBeGreaterThanOrEqual(height);
      expect(options.minZoom).toBeLessThan(options.maxZoom);
      expect(options.zoom).toBe(options.minZoom);
      expect(options.maxBounds).toEqual([[bounds[0], bounds[1]], [bounds[2], bounds[3]]]);
      expect(deviceMapMinZoom({ clientWidth: width * 2, clientHeight: height * 2 }, bounds, 18)).toBeGreaterThan(options.minZoom);
      const center = options.center;
      expect(positionInMapBounds(center, bounds)).toBe(true);
      expect(deviceMapOptions(container, resources, center).center).toEqual(center);
      expect(deviceMapOptions(container, resources, [0, 0]).center).toEqual(center);
      expect(positionInMapBounds([bounds[0] - 0.01, bounds[1]], bounds)).toBe(false);
    }
  });
});
function response(value) { return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }), json: async () => value }; }
