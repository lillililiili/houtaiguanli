// 管理端设备地图使用业务前台的同一套底图主题；数据源仍由当前生效的地图包提供。
const PALETTE = {
  background: '#061a3d', land: '#10295a', green: '#0e3446', forest: '#124238', wetland: '#1e4150',
  urban: '#234f92', urbanDense: '#2d62b0', building: '#3a78cc', water: '#1a55b8', waterLine: '#3f8ff0',
  road: '#3378c8', roadMajor: '#62aef5', roadHighway: '#a6dcff', roadCasing: '#0a1e45', boundary: '#2f63a8',
  label: '#dbe9fb', labelMuted: '#93b6df', halo: '#071a3c'
};

function shade(hex, ratio) {
  const value = parseInt(hex.slice(1), 16);
  const channel = shift => Math.max(0, Math.min(255, Math.round(((value >> shift) & 255) * (1 + ratio))));
  return `#${[16, 8, 0].map(shift => channel(shift).toString(16).padStart(2, '0')).join('')}`;
}

export function applyDeviceMapTheme(style, { imagery = null } = {}) {
  for (const layer of style.layers || []) {
    if (layer.type === 'background') {
      layer.paint = { ...layer.paint, 'background-color': PALETTE.background };
      continue;
    }
    if (layer.source !== 'protomaps') continue;
    const source = layer['source-layer'];
    const paint = layer.paint ||= {};
    if (layer.type === 'fill') {
      if (source === 'earth') paint['fill-color'] = ['interpolate', ['linear'], ['zoom'], 11, PALETTE.land, 15, shade(PALETTE.land, 0.35)];
      if (source === 'landcover' || source === 'landuse') {
        paint['fill-color'] = ['match', ['get', 'kind'],
          ['park', 'forest', 'wood', 'grassland', 'grass', 'nature_reserve', 'protected_area', 'national_park', 'garden', 'cemetery', 'golf_course'], PALETTE.forest,
          ['industrial', 'commercial', 'aerodrome'], PALETTE.urbanDense,
          ['urban_area', 'hospital', 'school', 'university', 'college'], PALETTE.urban,
          PALETTE.land];
      }
      if (source === 'landcover' || source === 'landuse') paint['fill-opacity'] = 1;
      if (source === 'buildings') paint['fill-color'] = PALETTE.building;
      if (source === 'water') {
        paint['fill-color'] = ['match', ['get', 'kind'], 'river', PALETTE.waterLine,
          ['lake', 'water', 'reservoir', 'pond', 'basin', 'canal'], shade(PALETTE.water, 0.22), PALETTE.water];
      }
    } else if (layer.type === 'line') {
      if (source === 'water') paint['line-color'] = PALETTE.waterLine;
      if (source === 'boundaries') paint['line-color'] = PALETTE.boundary;
      if (source === 'roads') {
        paint['line-color'] = layer.id.includes('casing') ? PALETTE.roadCasing
          : layer.id.includes('highway') ? PALETTE.roadHighway
            : layer.id.includes('major') ? PALETTE.roadMajor : PALETTE.road;
      }
    } else if (layer.type === 'symbol' && layer.layout?.['text-field']) {
      if (layer.id === 'roads_shields') continue;
      paint['text-color'] = source === 'places' ? PALETTE.label : PALETTE.labelMuted;
      paint['text-halo-color'] = PALETTE.halo;
      if (source === 'places') {
        paint['text-halo-width'] = ['locality', 'region'].some(kind => layer.id.includes(kind)) ? 3.2 : 2;
        paint['text-halo-blur'] = 0.4;
      }
    }
  }
  if (imagery) injectImagery(style, imagery);
  return style;
}

function injectImagery(style, imagery) {
  style.sources.imagery = {
    type: 'raster', tiles: [imagery.tiles], tileSize: imagery.tileSize ?? 256,
    minzoom: imagery.minzoom, maxzoom: imagery.maxzoom, bounds: imagery.bounds, attribution: imagery.attribution
  };
  const layers = style.layers || [];
  // 地表面层保留在下方，供透明缺测和影像失败时显示；道路、边界与地名继续叠加。
  const ground = layer => layer.type === 'background' || (layer.source === 'protomaps' && layer.type === 'fill');
  style.layers = [...layers.filter(ground),
    {
      id: 'theme_imagery', type: 'raster', source: 'imagery',
      paint: {
        'raster-saturation': 0, 'raster-contrast': 0,
        'raster-brightness-min': 0, 'raster-brightness-max': 1,
        'raster-fade-duration': 0,
        'raster-opacity': 1
      }
    }, ...layers.filter(layer => !ground(layer))];
}

export function handleDeviceImageryError(map, event) {
  if (event?.sourceId !== 'imagery') return null;
  const error = event.error;
  if (error?.name === 'AbortError' || error?.code === 20 || /abort|cancelled|canceled/i.test(String(error?.message || ''))) return 'cancelled';
  if (map.getLayer('theme_imagery')) map.removeLayer('theme_imagery');
  if (map.getSource('imagery')) map.removeSource('imagery');
  return 'fallback';
}
