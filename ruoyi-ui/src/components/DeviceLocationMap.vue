<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import 'maplibre-gl/dist/maplibre-gl.css';
import { devicePosition, loadDeviceMapEngine, loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

const props = defineProps({ device: { type: Object, required: true } });
const container = ref();
const position = computed(() => devicePosition(props.device));
const loading = ref(false);
const error = ref('');
const outside = ref(false);
let map, marker, observer, controller;

function dispose() {
  controller?.abort(); observer?.disconnect(); marker?.remove(); map?.remove();
  map = null; marker = null;
}
function locate() {
  if (position.value) map?.jumpTo({ center: position.value, zoom: Math.min(14, map.getMaxZoom()) });
}
async function renderMap() {
  dispose(); error.value = ''; loading.value = false; outside.value = false;
  if (!position.value || !container.value) return;
  const current = new AbortController(); controller = current; loading.value = true;
  try {
    const [engine, resources] = await Promise.all([loadDeviceMapEngine(), loadDeviceMapStyle(current.signal)]);
    if (current.signal.aborted) return;
    const [lon, lat] = position.value;
    const bounds = resources.bounds;
    outside.value = Array.isArray(bounds) && (lon < bounds[0] || lat < bounds[1] || lon > bounds[2] || lat > bounds[3]);
    map = new engine.Map({ container: container.value, style: resources.style, center: position.value,
      zoom: Math.min(14, resources.maxZoom), maxZoom: resources.maxZoom,
      attributionControl: { compact: true }, localIdeographFontFamily: 'Microsoft YaHei, sans-serif' });
    map.addControl(new engine.NavigationControl({ showCompass: false }), 'top-right');
    map.scrollZoom.disable();
    marker = new engine.Marker({ color: '#1677ff' }).setLngLat(position.value).addTo(map);
    marker.getElement().setAttribute('aria-label', `${props.device.name || '设备'}位置`);
    marker.getElement().setAttribute('title', props.device.name || '设备位置');
    map.on('load', () => { if (!current.signal.aborted) loading.value = false; });
    map.on('error', () => {
      if (!current.signal.aborted) { loading.value = false; error.value = '底图加载失败，请重试。设备坐标仍可在下方查看。'; }
    });
    observer = new ResizeObserver(() => map?.resize()); observer.observe(container.value);
  } catch (reason) {
    if (!current.signal.aborted) { loading.value = false; error.value = reason.message || '地图加载失败，请重试'; }
  }
}
watch([container, () => props.device.device_id, () => props.device.name, position], renderMap, { flush: 'post' });
onBeforeUnmount(dispose);
</script>

<template>
  <section class="device-location" aria-label="设备地图位置">
    <div v-if="position" v-loading="loading" class="map-frame">
      <div ref="container" class="map-canvas" />
      <el-button class="locate-button" size="small" :disabled="loading || Boolean(error)" @click="locate">回到设备</el-button>
    </div>
    <el-empty v-else description="暂无可定位的 WGS84 坐标" :image-size="55" />
    <div v-if="error" class="map-error" role="alert"><span>{{ error }}</span><el-button link type="primary" @click="renderMap">重试</el-button></div>
    <p v-else-if="outside" class="map-note">设备位于当前离线底图覆盖范围外</p>
    <div class="location-caption"><b>{{ device.region_name || '所属区域未登记' }}</b><span>{{ device.address || '尚未登记安装地址' }}</span></div>
  </section>
</template>

<style scoped>
.device-location { overflow: hidden; border: 1px solid #e6edf6; border-radius: 6px; background: #f7faff; }
.map-frame { position: relative; height: 240px; }
.map-canvas { width: 100%; height: 100%; }
.locate-button { position: absolute; left: 10px; top: 10px; box-shadow: 0 2px 6px #172b4d1a; }
.location-caption { display: flex; flex-direction: column; gap: 6px; padding: 12px; font-size: 13px; overflow-wrap: anywhere; }
.location-caption span, .map-note { color: #728096; font-size: 12px; }
.map-note { margin: 10px 12px 0; }
.map-error { display: flex; align-items: center; gap: 8px; padding: 10px 12px 0; color: #b54708; font-size: 12px; }
</style>
