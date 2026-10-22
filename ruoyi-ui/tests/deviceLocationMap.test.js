import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { devicePosition, loadDeviceMapStyle, deviceMapMinZoom, deviceMapOptions, positionInMapBounds, readDeviceImageryTile } from '@/services/deviceLocationMap.js';
import { applyDeviceMapTheme, handleDeviceImageryError } from '@/services/deviceMapTheme.js';

const archiveHeader = vi.hoisted(() => vi.fn());
vi.mock('@/services/mapResourceWarmup.js', () => ({ warmMapResource: vi.fn(), warmMapStyleAssets: vi.fn() }));
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
    expect(devicePosition({ longitude: 119.2, latitude: 36.8, coordinate_system: ' wgs84 ' })).toEqual([119.2, 36.8]);
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

  it('使用同包 XYZ 瓦片且仍由包头确认地图范围，中文字体优先使用本机字体', async () => {
    stubOptimizedPackage('./xyz/{z}/{x}/{y}.pbf');
    const { style, bounds } = await loadDeviceMapStyle(new AbortController().signal);
    expect(style.sources.protomaps.tiles).toEqual([`${window.location.origin}/map-data/alternate/xyz/{z}/{x}/{y}.pbf`]);
    expect(style.sources.protomaps.url).toBeUndefined();
    expect(bounds).toEqual([117.9, 36.7, 119.5, 38.3]);
    expect(style['font-faces'].standard).toBeUndefined();
    expect(style['font-faces'].custom[0].url).toContain('/map-data/alternate/fonts/custom.otf');
  });

  it('每次重新核对发布指针，地图包资源遵循服务端缓存策略', async () => {
    stubOptimizedPackage('./xyz/{z}/{x}/{y}.pbf');
    await loadDeviceMapStyle(new AbortController().signal);
    expect(fetch.mock.calls.map(([, options]) => options.cache)).toEqual(['no-cache', 'default', 'default']);
  });

  it.each(['https://outside.test/{z}/{x}/{y}.pbf', './xyz/{z}/{x}.pbf', './xyz/{z}/{x}/{y}.pbf?key=1'])('拒绝不安全或不完整的矢量瓦片地址：%s', async template => {
    stubOptimizedPackage(template);
    await expect(loadDeviceMapStyle(new AbortController().signal)).rejects.toThrow(/地图/);
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

describe('卫星影像与本地矢量回退', () => {
  const imagery = { tiles: './imagery/{z}/{x}/{y}.webp', minZoom: 0, maxZoom: 13, attribution: 'Copernicus Sentinel-2' };
  function stubImageryPackage(overrides, stylePath = './styles/style.json') {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response({ manifest: '/map-data/satellite/manifest.json' }))
      .mockResolvedValueOnce(response({ coordinateSystem: 'WGS84', archive: './city.pmtiles', style: stylePath, imagery: overrides }))
      .mockResolvedValueOnce(response({ version: 8, sources: { protomaps: {} }, layers: [] })));
  }

  it.each([undefined, 256, 512])('兼容缺省和显式尺寸 %s，影像相对清单定位，不跟随嵌套样式目录', async tileSize => {
    stubImageryPackage({ ...imagery, ...(tileSize === undefined ? {} : { tileSize }) });
    const { style } = await loadDeviceMapStyle(new AbortController().signal);
    expect(style.sources.imagery.tileSize).toBe(tileSize ?? 256);
    expect(style.sources.imagery.tiles).toEqual([`offlineimagery://${window.location.origin}/map-data/satellite/imagery/{z}/{x}/{y}.webp`]);
    expect(style.sources.imagery.minzoom).toBe(0);
    expect(style.sources.imagery.maxzoom).toBe(13);
  });

  it.each([
    { tileSize: 300 }, { tileSize: '512' }, { minZoom: -1 }, { maxZoom: 25 }, { minZoom: 14, maxZoom: 13 },
    { maxZoom: 12.5 }, { tiles: 'https://outside.test/imagery/{z}/{x}/{y}.webp' },
    { tiles: '../other/imagery/{z}/{x}/{y}.webp' }, { tiles: './imagery/{z}/{x}.webp' },
    { tiles: './imagery/{z}/{x}/{y}.webp?key=1' }
  ])('拒绝错误影像清单 %j', async change => {
    stubImageryPackage({ ...imagery, ...change });
    await expect(loadDeviceMapStyle(new AbortController().signal)).rejects.toThrow(/地图/);
  });

  it('旧矢量包不引入影像源', async () => {
    stubImageryPackage(undefined);
    const { style } = await loadDeviceMapStyle(new AbortController().signal);
    expect(style.sources.imagery).toBeUndefined();
    expect(style.layers.some(layer => layer.source === 'imagery')).toBe(false);
  });

  it('影像保持自然颜色及持续显示，地表在下方而道路、地名和业务叠加仍在上方', () => {
    const style = { sources: { protomaps: {} }, layers: [
      { id: 'background', type: 'background' },
      { id: 'earth', type: 'fill', source: 'protomaps', 'source-layer': 'earth' },
      { id: 'roads_tunnels_minor', type: 'line', source: 'protomaps', 'source-layer': 'roads' },
      { id: 'buildings', type: 'fill', source: 'protomaps', 'source-layer': 'buildings' },
      { id: 'places', type: 'symbol', source: 'protomaps', 'source-layer': 'places', layout: { 'text-field': '{name}' } },
      { id: 'business-airspace', type: 'fill', source: 'business' }
    ] };
    applyDeviceMapTheme(style, { imagery: { tiles: '/local/{z}/{x}/{y}.webp', tileSize: 512, minzoom: 0, maxzoom: 13 } });
    const at = style.layers.findIndex(layer => layer.id === 'theme_imagery');
    for (const id of ['background', 'earth', 'buildings']) expect(style.layers.findIndex(layer => layer.id === id)).toBeLessThan(at);
    for (const id of ['roads_tunnels_minor', 'places', 'business-airspace']) expect(style.layers.findIndex(layer => layer.id === id)).toBeGreaterThan(at);
    expect(style.layers[at]).not.toHaveProperty('maxzoom');
    expect(style.layers[at]).not.toHaveProperty('minzoom');
    expect(style.layers[at].paint).toMatchObject({ 'raster-opacity': 1, 'raster-saturation': 0, 'raster-contrast': 0,
      'raster-brightness-min': 0, 'raster-brightness-max': 1 });
    expect(style.layers.some(layer => /tint|hillshade|theme_tex/.test(layer.id))).toBe(false);
  });

  it.each([404, 500])('影像 HTTP %s 只移除影像层和源，保留矢量及业务层', status => {
    const layers = new Set(['theme_imagery', 'roads', 'devices']);
    const sources = new Set(['imagery', 'protomaps', 'business']);
    const map = { getLayer: id => layers.has(id), getSource: id => sources.has(id),
      removeLayer: vi.fn(id => layers.delete(id)), removeSource: vi.fn(id => sources.delete(id)) };
    expect(handleDeviceImageryError(map, { sourceId: 'imagery', error: { status } })).toBe('fallback');
    expect([...layers]).toEqual(['roads', 'devices']);
    expect([...sources]).toEqual(['protomaps', 'business']);
    expect(handleDeviceImageryError(map, { sourceId: 'imagery', error: { status } })).toBe('fallback');
    expect(map.removeLayer).toHaveBeenCalledTimes(1);
    expect(map.removeSource).toHaveBeenCalledTimes(1);
  });

  it('平移取消的影像请求不会降级，其他源错误交回调用者', () => {
    const map = { removeLayer: vi.fn(), removeSource: vi.fn() };
    expect(handleDeviceImageryError(map, { sourceId: 'imagery', error: { name: 'AbortError' } })).toBe('cancelled');
    expect(handleDeviceImageryError(map, { sourceId: 'protomaps', error: { status: 404 } })).toBeNull();
    expect(map.removeSource).not.toHaveBeenCalled();
  });

  it.each([404, 500, 204])('读取协议把 HTTP %s 作为可见错误，避免引擎吞掉缺失瓦片', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: status === 204, status }));
    const controller = new AbortController();
    const error = await readDeviceImageryTile({ url: `offlineimagery://${window.location.origin}/map-data/image/13/1/2.webp` }, controller).catch(reason => reason);
    expect(error.message).toContain(`HTTP ${status}`);
    expect(error.status).toBeUndefined();
    expect(fetch.mock.calls[0][1]).toEqual({ signal: controller.signal, redirect: 'error' });
  });

  it('读取协议拒绝公网、不吞掉取消信号，并返回本地字节及缓存信息', async () => {
    const controller = new AbortController();
    vi.stubGlobal('fetch', vi.fn());
    await expect(readDeviceImageryTile({ url: 'offlineimagery://https://outside.test/a.webp' }, controller)).rejects.toThrow(/同源/);
    expect(fetch).not.toHaveBeenCalled();
    const aborted = new DOMException('Aborted', 'AbortError');
    fetch.mockRejectedValueOnce(aborted);
    await expect(readDeviceImageryTile({ url: `offlineimagery://${window.location.origin}/a.webp` }, controller)).rejects.toBe(aborted);
    const data = new Uint8Array([1, 2]).buffer;
    fetch.mockResolvedValueOnce({ ok: true, status: 200, arrayBuffer: async () => data, headers: new Headers({ 'Cache-Control': 'max-age=3600' }) });
    expect(await readDeviceImageryTile({ url: `offlineimagery://${window.location.origin}/a.webp` }, controller)).toEqual({ data, cacheControl: 'max-age=3600', expires: null });
    fetch.mockResolvedValueOnce({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(0), headers: new Headers() });
    await expect(readDeviceImageryTile({ url: `offlineimagery://${window.location.origin}/a.webp` }, controller)).rejects.toThrow('为空');
    fetch.mockResolvedValueOnce({ ok: true, status: 200, headers: new Headers({ 'Content-Type': 'text/html' }) });
    await expect(readDeviceImageryTile({ url: `offlineimagery://${window.location.origin}/a.webp` }, controller)).rejects.toThrow('非图片');
  });
});
function response(value) { return { ok: true, status: 200, headers: new Headers({ 'Content-Type': 'application/json' }), json: async () => value }; }

function stubOptimizedPackage(vectorTiles) {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(response({ manifest: '/map-data/alternate/manifest.json' }))
    .mockResolvedValueOnce(response({ coordinateSystem: 'WGS84', archive: './other.pmtiles', style: './style.json', vectorTiles }))
    .mockResolvedValueOnce(response({ version: 8, sources: { protomaps: {} }, layers: [], 'font-faces': {
      standard: [{ url: './fonts/NotoSansSC-Regular.otf', 'unicode-range': ['U+2E80-9FFF'] }],
      custom: [{ url: './fonts/custom.otf', 'unicode-range': ['U+0000-00FF'] }]
    } })));
}
