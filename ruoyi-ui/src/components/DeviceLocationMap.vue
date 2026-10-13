<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import 'maplibre-gl/dist/maplibre-gl.css';
import { devicePosition, deviceMapOptions, positionInMapBounds, resizeDeviceMap,
  loadDeviceMapEngine, loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

const props = defineProps({ device: { type: Object, required: true } });
const container = ref();
const position = computed(() => devicePosition(props.device));
const loading = ref(false);
const error = ref('');
const outside = ref(false);
let map, marker, observer, controller, resources, mapEngine, markedPosition;

function dispose() {
  controller?.abort(); observer?.disconnect(); marker?.remove(); map?.remove();
  map = null; marker = null; observer = null; controller = null;
  resources = null; mapEngine = null; markedPosition = null;
}
function locate() {
  if (position.value && !outside.value) map?.jumpTo({ center: position.value,
    zoom: Math.max(map.getMinZoom(), Math.min(14, map.getMaxZoom())) });
}
function syncDevice(recenter = false) {
  if (!map || !resources) return;
  const point = position.value;
  outside.value = Boolean(point && !positionInMapBounds(point, resources.bounds));
  if (!point || outside.value) {
    marker?.remove(); marker = null; markedPosition = null;
  } else {
    if (!marker) marker = new mapEngine.Marker({ color: '#1677ff' }).setLngLat(point).addTo(map);
    else if (!markedPosition?.every((value, index) => value === point[index])) marker.setLngLat(point);
    markedPosition = [...point];
    marker.getElement().setAttribute('aria-label', `${props.device.name || '设备'}位置`);
    marker.getElement().setAttribute('title', props.device.name || '设备位置');
  }
  // 实时数据只移动标记，保留用户视角；选择另一设备时才主动定位。
  if (recenter) {
    const { center, zoom } = deviceMapOptions(container.value, resources, point);
    map.jumpTo({ center, zoom });
  }
}
async function renderMap() {
  dispose(); error.value = ''; loading.value = false; outside.value = false;
  if (!position.value || !container.value) return;
  const current = new AbortController(); controller = current; loading.value = true;
  try {
    const [engine, loadedResources] = await Promise.all([loadDeviceMapEngine(), loadDeviceMapStyle(current.signal)]);
    if (current.signal.aborted) return;
    mapEngine = engine; resources = loadedResources;
    map = new engine.Map(deviceMapOptions(container.value, resources, position.value));
    map.addControl(new engine.NavigationControl({ showCompass: false }), 'top-right');
    map.touchZoomRotate.disableRotation();
    syncDevice();
    map.on('load', () => { if (!current.signal.aborted) { resizeDeviceMap(map, resources); loading.value = false; } });
    map.on('error', () => {
      if (!current.signal.aborted) { loading.value = false; error.value = '底图加载失败，请重试。设备坐标仍可在下方查看。'; }
    });
    observer = new ResizeObserver(() => { if (map) resizeDeviceMap(map, resources); }); observer.observe(container.value);
  } catch (reason) {
    if (!current.signal.aborted) { loading.value = false; error.value = reason.message || '地图加载失败，请重试'; }
  }
}
watch(container, renderMap, { flush: 'post' });
// 对比规范化后的坐标值，不能因为详情返回新对象/数组就重建底图。
watch([() => props.device.device_id, () => props.device.name,
  () => position.value?.[0], () => position.value?.[1]],
([id], [previousId]) => syncDevice(id !== previousId), { flush: 'post' });
onBeforeUnmount(dispose);
</script>

<template>
  <section class="device-location" aria-label="设备地图位置">
    <div v-if="position" v-loading="loading" class="map-frame">
      <div ref="container" class="map-canvas" />
      <el-button class="locate-button" size="small" :disabled="loading || Boolean(error) || outside" @click="locate">回到设备</el-button>
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
