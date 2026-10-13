import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { PMTiles, Protocol } from 'pmtiles';
import { applyDeviceMapTheme } from './deviceMapTheme.js';

const protocol = new Protocol();

export function positionInMapBounds(position, bounds) {
  return Boolean(position && bounds && position[0] >= bounds[0] && position[0] <= bounds[2]
    && position[1] >= bounds[1] && position[1] <= bounds[3]);
}

// 与业务前台相同：视口的长边也必须在地图包覆盖范围内，避免宽弹窗露出外地底图。
export function deviceMapMinZoom(container, bounds, maxZoom) {
  const mercatorY = latitude => (1 - Math.log(Math.tan(Math.PI / 4 + latitude * Math.PI / 360)) / Math.PI) / 2;
  const dx = (bounds[2] - bounds[0]) / 360;
  const dy = mercatorY(bounds[1]) - mercatorY(bounds[3]);
  return Math.max(0, Math.min(maxZoom, Math.log2(Math.max(
    (container.clientWidth + 8) / dx, (container.clientHeight + 8) / dy
  ) / 512)));
}

export function deviceMapOptions(container, resources, position) {
  const { bounds, maxZoom } = resources;
  const minZoom = deviceMapMinZoom(container, bounds, maxZoom);
  const located = positionInMapBounds(position, bounds);
  return {
    container, style: resources.style,
    center: located ? position : [(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2],
    zoom: located ? Math.max(minZoom, Math.min(14, maxZoom)) : minZoom,
    minZoom, maxZoom, maxBounds: [[bounds[0], bounds[1]], [bounds[2], bounds[3]]],
    bearing: 0, pitch: 0, dragRotate: false, pitchWithRotate: false, touchPitch: false,
    renderWorldCopies: false, attributionControl: { compact: true },
    localIdeographFontFamily: 'Microsoft YaHei, sans-serif'
  };
}

export function resizeDeviceMap(map, resources) {
  map.setMinZoom(deviceMapMinZoom(map.getContainer(), resources.bounds, resources.maxZoom));
  map.resize();
}

export function devicePosition(device) {
  const values = [device?.longitude, device?.latitude];
  if (values.some(value => value == null || String(value).trim() === '' || !['string', 'number'].includes(typeof value))) return null;
  const [longitude, latitude] = values.map(Number);
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude) || Math.abs(longitude) > 180 || Math.abs(latitude) > 85.051129) return null;
  if (!['WGS84', 'WGS-84'].includes(device.coordinate_system)) return null;
  return [longitude, latitude];
}

function localUrl(value, base = window.location.href) {
  if (typeof value !== 'string' || !value.trim()) throw new Error('离线地图资源地址缺失');
  const url = new URL(value, base);
  if (url.origin !== window.location.origin || !['http:', 'https:'].includes(url.protocol)) throw new Error('离线地图必须使用同源资源');
  return url.href.replaceAll('%7B', '{').replaceAll('%7D', '}');
}

async function readJson(url, signal, optional = false) {
  const response = await fetch(url, { signal, cache: 'no-cache', redirect: 'error' });
  if (optional && (response.status === 204 || response.status === 404)) return null;
  if (!response.ok || !response.headers.get('content-type')?.includes('json')) throw new Error('离线地图资源读取失败，请检查地图服务');
  return response.json();
}

export async function loadDeviceMapStyle(signal) {
  let configUrl = localUrl('/map-data/control/map-config.json');
  let config = await readJson(configUrl, signal, true);
  if (!config) { configUrl = localUrl('/map-config.json'); config = await readJson(configUrl, signal); }
  const manifestUrl = localUrl(config.manifest, configUrl);
  const manifest = await readJson(manifestUrl, signal);
  if (manifest.coordinateSystem !== 'WGS84') throw new Error('底图坐标系不支持');
  const archiveUrl = localUrl(manifest.archive, manifestUrl);
  const archive = protocol.get(archiveUrl) || new PMTiles(archiveUrl);
  protocol.add(archive);
  const styleUrl = localUrl(manifest.style, manifestUrl);
  const [style, header] = await Promise.all([readJson(styleUrl, signal), archive.getHeader()]);
  signal.throwIfAborted();
  const bounds = [header.minLon, header.minLat, header.maxLon, header.maxLat];
  if (header.tileType !== 1 || !bounds.every(Number.isFinite)
      || bounds[0] < -180 || bounds[2] > 180 || bounds[1] < -85.051129 || bounds[3] > 85.051129
      || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) throw new Error('地图包覆盖范围无效');
  if (style.version !== 8 || !style.sources?.protomaps || Object.keys(style.sources).length !== 1 || style.imports) throw new Error('底图样式不支持');
  style.sources.protomaps = {
    type: 'vector', url: `pmtiles://${archiveUrl}`,
    attribution: '© OpenStreetMap contributors · Protomaps', minzoom: header.minZoom, maxzoom: header.maxZoom
  };
  if (style.glyphs) style.glyphs = localUrl(style.glyphs, styleUrl);
  if (style.sprite) style.sprite = localUrl(style.sprite, styleUrl);
  for (const faces of Object.values(style['font-faces'] || {})) {
    for (const face of faces) face.url = localUrl(face.url, styleUrl);
  }
  const imagery = manifest.imagery?.tiles ? {
    tiles: localUrl(manifest.imagery.tiles, manifestUrl),
    minzoom: manifest.imagery.minZoom ?? 7,
    maxzoom: manifest.imagery.maxZoom ?? 12,
    bounds,
    attribution: manifest.imagery.attribution || ''
  } : null;
  applyDeviceMapTheme(style, { imagery });
  return { style, bounds, maxZoom: manifest.displayMaxZoom ?? 18 };
}

let enginePromise;
export function loadDeviceMapEngine() {
  if (!enginePromise) enginePromise = import('maplibre-gl').then(lib => {
    const maplibre = lib.default || lib;
    maplibre.setWorkerUrl(workerUrl);
    maplibre.addProtocol('pmtiles', protocol.tile);
    return maplibre;
  }).catch(error => { enginePromise = null; throw error; });
  return enginePromise;
}
