<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import 'maplibre-gl/dist/maplibre-gl.css';
import { devicePosition, deviceMapOptions, positionInMapBounds, resizeDeviceMap,
  loadDeviceMapEngine, loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

const props = defineProps({
  longitude: { type: [Number, String], default: null },
  latitude: { type: [Number, String], default: null },
  disabled: Boolean
});
const emit = defineEmits(['update:longitude', 'update:latitude']);
const container = ref();
const loading = ref(false);
const error = ref('');
const outside = ref(false);
let map;
let marker;
let observer;
let controller;
let resources;
let markedPosition;

function numericPosition() {
  return devicePosition({ longitude: props.longitude, latitude: props.latitude, coordinate_system: 'WGS84' });
}

function dispose() {
  controller?.abort();
  observer?.disconnect();
  marker?.remove();
  map?.remove();
  controller = null;
  observer = null;
  marker = null;
  map = null;
  resources = null;
  markedPosition = null;
}

function setMarker(position, move = false) {
  if (!map || !resources) return;
  outside.value = Boolean(position && !positionInMapBounds(position, resources.bounds));
  if (!position || outside.value) { marker?.remove(); marker = null; markedPosition = null; return; }
  // 点击选点先更新标记，v-model 回传相同坐标时不再次跳转或放大底图。
  if (markedPosition?.every((value, index) => value === position[index])) return;
  if (!marker) marker = new maplibre.Marker({ color: '#1677ff' }).setLngLat(position).addTo(map);
  else marker.setLngLat(position);
  markedPosition = [...position];
  marker.getElement().setAttribute('aria-label', '设备安装点');
  if (move) map.jumpTo({ center: position, zoom: Math.min(resources.maxZoom, Math.max(map.getZoom(), 14)) });
}

function clickPosition(event) {
  if (props.disabled) return;
  const longitude = Number(event.lngLat.lng.toFixed(7));
  const latitude = Number(event.lngLat.lat.toFixed(7));
  if (!positionInMapBounds([longitude, latitude], resources?.bounds)) return;
  emit('update:longitude', longitude);
  emit('update:latitude', latitude);
  setMarker([longitude, latitude]);
}

let maplibre;
async function renderMap() {
  dispose();
  error.value = '';
  outside.value = false;
  if (!container.value) return;
  const current = new AbortController();
  controller = current;
  loading.value = true;
  try {
    const [engine, loadedResources] = await Promise.all([loadDeviceMapEngine(), loadDeviceMapStyle(current.signal)]);
    if (current.signal.aborted) return;
    maplibre = engine;
    resources = loadedResources;
    const position = numericPosition();
    map = new engine.Map(deviceMapOptions(container.value, resources, position));
    map.addControl(new engine.NavigationControl({ showCompass: false }), 'top-right');
    map.touchZoomRotate.disableRotation();
    map.on('click', clickPosition);
    if (position) setMarker(position);
    map.on('load', () => {
      if (current.signal.aborted) return;
      resizeDeviceMap(map, resources);
      loading.value = false;
    });
    map.on('error', () => {
      if (!current.signal.aborted) { loading.value = false; error.value = '底图加载失败，请重试。'; }
    });
    observer = new ResizeObserver(() => { if (map) resizeDeviceMap(map, resources); });
    observer.observe(container.value);
  } catch (reason) {
    if (!current.signal.aborted) { loading.value = false; error.value = reason.message || '地图加载失败，请重试'; }
  }
}

watch(() => [props.longitude, props.latitude], () => {
  const position = numericPosition();
  setMarker(position, true);
}, { flush: 'post' });
watch(container, renderMap, { flush: 'post' });
onBeforeUnmount(dispose);
</script>

<template>
  <section class="coordinate-picker" aria-label="地图选点">
    <div v-loading="loading" class="coordinate-map">
      <div ref="container" class="coordinate-map-canvas" />
      <span class="coordinate-map-hint">点击地图设置设备安装点</span>
    </div>
    <div v-if="error" class="coordinate-map-error" role="alert">{{ error }}</div>
    <div v-else-if="outside" class="coordinate-map-error" role="status">所填坐标在当前地图覆盖范围外，请核对；原坐标未改动。</div>
  </section>
</template>

<style scoped>
.coordinate-picker { overflow: hidden; border: 1px solid #dfe8f5; border-radius: 6px; background: #f7faff; }
.coordinate-map { position: relative; height: 280px; }
.coordinate-map-canvas { width: 100%; height: 100%; }
.coordinate-map-hint { position: absolute; left: 10px; bottom: 10px; padding: 5px 8px; border-radius: 4px; color: #52647d; background: #fff; box-shadow: 0 1px 5px #172b4d1a; font-size: 12px; pointer-events: none; }
.coordinate-map-error { padding: 8px 10px; color: #b54708; font-size: 12px; }
</style>
