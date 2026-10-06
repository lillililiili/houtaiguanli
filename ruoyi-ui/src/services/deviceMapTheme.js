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
  const tintOpacity = imagery ? 0.42 : 1;
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
      if (source === 'landcover' || source === 'landuse') paint['fill-opacity'] = tintOpacity;
      if (source === 'buildings') { paint['fill-color'] = PALETTE.building; if (imagery) paint['fill-opacity'] = 0.6; }
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
    type: 'raster', tiles: [imagery.tiles], tileSize: 256,
    minzoom: imagery.minzoom, maxzoom: imagery.maxzoom, bounds: imagery.bounds, attribution: imagery.attribution
  };
  const layers = style.layers || [];
  const earthAt = layers.findIndex(layer => layer.id === 'earth');
  layers.splice(earthAt < 0 ? 1 : earthAt + 1, 0,
    {
      id: 'theme_imagery', type: 'raster', source: 'imagery', minzoom: 6,
      paint: {
        'raster-saturation': -0.72, 'raster-contrast': 0.22,
        'raster-brightness-min': 0.04, 'raster-brightness-max': 0.82,
        'raster-fade-duration': 0,
        'raster-opacity': ['interpolate', ['linear'], ['zoom'], 6, 0.9, imagery.maxzoom, 0.9, imagery.maxzoom + 2, 0.3]
      }
    },
    {
      id: 'theme_imagery_tint', type: 'fill', source: 'protomaps', 'source-layer': 'earth',
      filter: ['==', ['geometry-type'], 'Polygon'],
      paint: { 'fill-color': PALETTE.land, 'fill-antialias': false,
        'fill-opacity': ['interpolate', ['linear'], ['zoom'], 6, 0.5, imagery.maxzoom + 2, 0.75] }
    });
  style.layers = layers;
}
