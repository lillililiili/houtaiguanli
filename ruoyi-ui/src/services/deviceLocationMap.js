import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { applyDeviceMapTheme } from './deviceMapTheme.js';

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
  const styleUrl = localUrl(manifest.style, manifestUrl);
  const style = await readJson(styleUrl, signal);
  if (style.version !== 8 || !style.sources?.protomaps || Object.keys(style.sources).length !== 1 || style.imports) throw new Error('底图样式不支持');
  style.sources.protomaps = {
    type: 'vector', url: `pmtiles://${localUrl(manifest.archive, manifestUrl)}`,
    attribution: '© OpenStreetMap contributors · Protomaps', maxzoom: manifest.maxZoom ?? 15
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
    bounds: manifest.bounds,
    attribution: manifest.imagery.attribution || ''
  } : null;
  applyDeviceMapTheme(style, { imagery });
  return { style, bounds: manifest.bounds, maxZoom: manifest.displayMaxZoom ?? 18 };
}

let enginePromise;
export function loadDeviceMapEngine() {
  if (!enginePromise) enginePromise = Promise.all([import('maplibre-gl'), import('pmtiles')]).then(([lib, { Protocol }]) => {
    const maplibre = lib.default || lib;
    maplibre.setWorkerUrl(workerUrl);
    maplibre.addProtocol('pmtiles', new Protocol().tile);
    return maplibre;
  }).catch(error => { enginePromise = null; throw error; });
  return enginePromise;
}
