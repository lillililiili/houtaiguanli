import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import DeviceCoordinatePicker from '@/components/DeviceCoordinatePicker.vue';
import DeviceLocationMap from '@/components/DeviceLocationMap.vue';
import { loadDeviceMapStyle } from '@/services/deviceLocationMap.js';

const state = vi.hoisted(() => ({ maps: [], markers: [], observers: [] }));
vi.mock('@/services/deviceLocationMap.js', async original => {
  const actual = await original();
  return {
    ...actual,
    loadDeviceMapStyle: vi.fn(async () => ({ style: {}, bounds: [117, 36, 120, 39], maxZoom: 18 })),
    loadDeviceMapEngine: vi.fn(async () => ({
      Map: class {
        constructor(options) {
          this.options = options; this.events = {}; this.zoom = options.zoom;
          this.jumpTo = vi.fn(); this.remove = vi.fn(); this.resize = vi.fn(); this.setMinZoom = vi.fn();
          this.touchZoomRotate = { disableRotation: vi.fn() };
          state.maps.push(this);
        }
        on(event, listener) { this.events[event] = listener; }
        addControl() {}
        getZoom() { return this.zoom; }
        getMinZoom() { return this.options.minZoom; }
        getMaxZoom() { return this.options.maxZoom; }
        getContainer() { return this.options.container; }
      },
      Marker: class {
        constructor() {
          this.element = document.createElement('div'); this.remove = vi.fn();
          this.setLngLat = vi.fn(position => { this.position = position; return this; });
          state.markers.push(this);
        }
        addTo() { return this; }
        getElement() { return this.element; }
      },
      NavigationControl: class {}
    }))
  };
});

let app, host;
async function settle() { for (let n = 0; n < 15; n++) { await Promise.resolve(); await nextTick(); } }
async function mount(component, props) {
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ render: () => h(component, props) });
  app.directive('loading', {}); app.component('el-button', { template: '<button><slot /></button>' });
  app.component('el-empty', { template: '<div />' });
  app.mount(host); await settle();
}
beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback) { this.callback = callback; this.disconnect = vi.fn(); state.observers.push(this); }
    observe() {}
  });
});
afterEach(() => {
  app?.unmount(); host?.remove(); vi.unstubAllGlobals(); vi.clearAllMocks();
  state.maps.length = 0; state.markers.length = 0; state.observers.length = 0;
});

it.each([[118.64, 37.43], [119.01, 38.17]])('选点回填不重建底图、不跳变缩放；手填坐标才定位：%j', async (longitude, latitude) => {
  const props = reactive({ longitude: null, latitude: null, disabled: false,
    'onUpdate:longitude': value => { props.longitude = value; },
    'onUpdate:latitude': value => { props.latitude = value; } });
  await mount(DeviceCoordinatePicker, props);
  const map = state.maps[0]; map.zoom = 11;
  map.events.click({ lngLat: { lng: longitude, lat: latitude } }); await settle();
  expect([props.longitude, props.latitude]).toEqual([longitude, latitude]);
  expect(state.markers[0].position).toEqual([longitude, latitude]);
  expect(map.jumpTo).not.toHaveBeenCalled();
  expect(state.maps).toHaveLength(1);
  props.longitude += 0.01; await settle();
  expect(map.jumpTo).toHaveBeenLastCalledWith({ center: [props.longitude, latitude], zoom: 14 });
  map.events.load(); state.observers[0].callback();
  expect(map.resize).toHaveBeenCalledTimes(2);
  props.disabled = true; await settle();
  map.events.click({ lngLat: { lng: longitude, lat: latitude } }); await settle();
  expect(props.longitude).toBe(longitude + 0.01);
});

it('包外选点不写入，手填包外坐标提示但不伪造边界点，清空后移除标记', async () => {
  const props = reactive({ longitude: 118, latitude: 37,
    'onUpdate:longitude': vi.fn(), 'onUpdate:latitude': vi.fn() });
  await mount(DeviceCoordinatePicker, props);
  state.maps[0].events.click({ lngLat: { lng: 116, lat: 37 } });
  expect(props['onUpdate:longitude']).not.toHaveBeenCalled();
  props.longitude = 116; await settle();
  expect(host.textContent).toContain('原坐标未改动');
  expect(props.longitude).toBe(116);
  expect(state.markers[0].remove).toHaveBeenCalled();
  props.longitude = null; await settle();
  expect(host.textContent).not.toContain('范围外');
});

it('切换设备复用底图并定位新设备，缩放不超出瓦片显示上限', async () => {
  const props = reactive({ device: { device_id: 'one', name: '设备一', longitude: 118.6, latitude: 37.4, coordinate_system: 'WGS84' } });
  await mount(DeviceLocationMap, props);
  const first = state.maps[0]; first.events.load();
  expect(first.options.maxBounds).toEqual([[117, 36], [120, 39]]);
  expect(first.options.maxZoom).toBe(18);
  props.device = { ...props.device, device_id: 'two', longitude: 119.2, latitude: 38.1 };
  await settle();
  expect(first.remove).not.toHaveBeenCalled();
  expect(state.maps).toHaveLength(1);
  expect(first.jumpTo).toHaveBeenLastCalledWith({ center: [119.2, 38.1], zoom: 14 });
  expect(state.markers.at(-1).position).toEqual([119.2, 38.1]);
});

it.each([[118.614, 37.41], [119.12, 38.03]])('连续设备详情更新不重建或重新定位地图：%j', async (longitude, latitude) => {
  const props = reactive({ device: { device_id: `device-${longitude}`, name: '预览设备', longitude, latitude, coordinate_system: 'WGS84' } });
  await mount(DeviceLocationMap, props);
  const map = state.maps[0]; const marker = state.markers[0]; map.events.load(); map.zoom = 16;
  for (let n = 1; n <= 5; n++) {
    props.device = { ...props.device, longitude: String(longitude), latitude: String(latitude),
      last_heartbeat_at: n * 3000, connectivity: n % 2 ? 'ONLINE' : 'OFFLINE' };
    await settle();
  }
  expect(loadDeviceMapStyle).toHaveBeenCalledOnce();
  expect(state.maps).toHaveLength(1);
  expect(state.observers).toHaveLength(1);
  expect(map.remove).not.toHaveBeenCalled();
  expect(map.jumpTo).not.toHaveBeenCalled();
  expect(marker.setLngLat).toHaveBeenCalledOnce();
  props.device = { ...props.device, name: '改名后设备', longitude: longitude + 0.02 };
  await settle();
  expect(marker.position).toEqual([longitude + 0.02, latitude]);
  expect(marker.getElement().getAttribute('aria-label')).toBe('改名后设备位置');
  expect(map.jumpTo).not.toHaveBeenCalled();
  host.querySelector('.locate-button').click(); await settle();
  expect(map.jumpTo).toHaveBeenLastCalledWith({ center: [longitude + 0.02, latitude], zoom: 14 });
  app.unmount(); app = null;
  expect(map.remove).toHaveBeenCalledOnce();
  expect(marker.remove).toHaveBeenCalledOnce();
  expect(state.observers[0].disconnect).toHaveBeenCalledOnce();
});

it('预览坐标移出覆盖范围只移除标记，坐标缺失或失效时释放地图并可恢复', async () => {
  const props = reactive({ device: { device_id: 'bounds-device', name: '边界设备', longitude: 118.6, latitude: 37.4, coordinate_system: 'WGS84' } });
  await mount(DeviceLocationMap, props);
  const map = state.maps[0]; map.events.load();
  props.device = { ...props.device, longitude: 116 }; await settle();
  expect(host.textContent).toContain('覆盖范围外');
  expect(state.markers[0].remove).toHaveBeenCalledOnce();
  expect(map.remove).not.toHaveBeenCalled();
  expect(map.jumpTo).not.toHaveBeenCalled();
  props.device = { ...props.device, longitude: 119 }; await settle();
  expect(host.textContent).not.toContain('覆盖范围外');
  expect(state.maps).toHaveLength(1);
  expect(state.markers.at(-1).position).toEqual([119, 37.4]);
  props.device = { ...props.device, coordinate_system: 'GCJ02' }; await settle();
  expect(map.remove).toHaveBeenCalledOnce();
  expect(host.querySelector('.map-canvas')).toBeNull();
  props.device = { ...props.device, coordinate_system: 'WGS84' }; await settle();
  expect(state.maps).toHaveLength(2);
  props.device = { ...props.device, longitude: null }; await settle();
  expect(state.maps[1].remove).toHaveBeenCalledOnce();
});
