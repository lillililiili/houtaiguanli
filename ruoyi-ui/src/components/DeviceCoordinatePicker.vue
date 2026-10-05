<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import 'maplibre-gl/dist/maplibre-gl.css';
import { loadDeviceMapEngine, loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

const props = defineProps({
  longitude: { type: [Number, String], default: null },
  latitude: { type: [Number, String], default: null },
  disabled: Boolean
});
const emit = defineEmits(['update:longitude', 'update:latitude']);
const container = ref();
const loading = ref(false);
const error = ref('');
let map;
let marker;
let observer;
let controller;

function numericPosition() {
  if ([props.longitude, props.latitude].some(value => value == null || String(value).trim() === '')) return null;
  const longitude = Number(props.longitude);
  const latitude = Number(props.latitude);
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)
      || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) return null;
  return [longitude, latitude];
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
}

function setMarker(position, move = false) {
  if (!map || !position) return;
  if (!marker) marker = new maplibre.Marker({ color: '#1677ff' }).setLngLat(position).addTo(map);
  else marker.setLngLat(position);
  if (move) map.jumpTo({ center: position, zoom: Math.max(map.getZoom(), 14) });
}

function clickPosition(event) {
  if (props.disabled) return;
  const longitude = Number(event.lngLat.lng.toFixed(7));
  const latitude = Number(event.lngLat.lat.toFixed(7));
  emit('update:longitude', longitude);
  emit('update:latitude', latitude);
  setMarker([longitude, latitude]);
}

let maplibre;
async function renderMap() {
  dispose();
  error.value = '';
  if (!container.value) return;
  const current = new AbortController();
  controller = current;
  loading.value = true;
  try {
    const [engine, resources] = await Promise.all([loadDeviceMapEngine(), loadDeviceMapStyle(current.signal)]);
    if (current.signal.aborted) return;
    maplibre = engine;
    const position = numericPosition();
    const bounds = Array.isArray(resources.bounds) && resources.bounds.length === 4 ? resources.bounds : null;
    const center = position || (bounds ? [(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2] : [0, 0]);
    map = new engine.Map({ container: container.value, style: resources.style, center,
      zoom: position ? Math.min(14, resources.maxZoom) : Math.min(10, resources.maxZoom),
      maxZoom: resources.maxZoom, attributionControl: { compact: true },
      localIdeographFontFamily: 'Microsoft YaHei, sans-serif' });
    map.addControl(new engine.NavigationControl({ showCompass: false }), 'top-right');
    map.scrollZoom.disable();
    map.on('click', clickPosition);
    if (position) setMarker(position);
    map.on('load', () => {
      if (current.signal.aborted) return;
      if (!position && bounds) map.fitBounds([[bounds[0], bounds[1]], [bounds[2], bounds[3]]], { padding: 24, duration: 0 });
      loading.value = false;
    });
    map.on('error', () => {
      if (!current.signal.aborted) { loading.value = false; error.value = '底图加载失败，请重试。'; }
    });
    observer = new ResizeObserver(() => map?.resize());
    observer.observe(container.value);
  } catch (reason) {
    if (!current.signal.aborted) { loading.value = false; error.value = reason.message || '地图加载失败，请重试'; }
  }
}

watch(() => [props.longitude, props.latitude], () => {
  const position = numericPosition();
  if (position) setMarker(position, true);
  else { marker?.remove(); marker = null; }
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
  </section>
</template>

<style scoped>
.coordinate-picker { overflow: hidden; border: 1px solid #dfe8f5; border-radius: 6px; background: #f7faff; }
.coordinate-map { position: relative; height: 280px; }
.coordinate-map-canvas { width: 100%; height: 100%; }
.coordinate-map-hint { position: absolute; left: 10px; bottom: 10px; padding: 5px 8px; border-radius: 4px; color: #52647d; background: #fff; box-shadow: 0 1px 5px #172b4d1a; font-size: 12px; pointer-events: none; }
.coordinate-map-error { padding: 8px 10px; color: #b54708; font-size: 12px; }
</style>
