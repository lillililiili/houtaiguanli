<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import OperationMetrics from './OperationMetrics.vue';
import './operations-reference.css';
import ErrorAlert from '@/components/ErrorAlert.vue';
import DeviceCoordinatePicker from '@/components/DeviceCoordinatePicker.vue';
import { useRoute } from 'vue-router';
import { adsbReceiversApi, weatherSensorsApi } from '@/api/externalInterfaces.js';
import DeviceCatalogPreview from './DeviceCatalogPreview.vue';
import { deviceApi, integrationApi, mqttApi } from '@/api/devices.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
import { useRealtimeRefresh } from '@/services/realtime.js';
import { useAuthStore } from '@/stores/auth.js';
import { isUnitAdministrator } from '@/utils/userType.js';
import { display, formatDeviceNo, statusText, statusType } from '@/utils/format.js';
import { maintenanceTime as formatTime } from './maintenance/presentation.js';
import { deviceCatalogParams } from '@/utils/deviceCatalog.js';
import { confirmDeletion } from '@/utils/deleteConfirmation.js';
import { warmDeviceMapResources } from '@/services/deviceLocationMap.js';
import { useFlightDistance } from './deviceFlightDistance.js';

const MQTT_PROTOCOL = 'LINGYUN_MQTT_V8_6';
const EO_PROTOCOL = 'EO_EDGE_MQTT_20250826';
const RADAR_PROTOCOL = 'RADAR_TCP_V3_0_0';
const COUNTER_PROTOCOL = 'COUNTERMEASURE_TCP_4CH_V2_0';
const isMqttProtocol = code => [MQTT_PROTOCOL, EO_PROTOCOL].includes(code);
const deviceTypes = [
  { code: 'radar', name: '雷达', protocols: [RADAR_PROTOCOL, MQTT_PROTOCOL] },
  { code: 'eo', name: '光电设备', protocols: [EO_PROTOCOL] },
  { code: 'counter', name: '四通道反制设备', protocols: [COUNTER_PROTOCOL] },
  { code: 'weather_sensor', name: '天气传感器', protocols: [] },
  { code: 'adsb_receiver', name: 'ADS-B 航班接收机', protocols: [] },
  ...[['5ga', '5G-A'], ['tdoa', 'TDOA']]
    .map(([code, name]) => ({ code, name, protocols: [MQTT_PROTOCOL] }))
];
const selectedType = ref('');
const auth = useAuthStore();
const unitMode = computed(() => isUnitAdministrator(auth.user));
const PLATFORM_TYPES = ['weather_sensor', 'adsb_receiver'];
const availableDeviceTypes = computed(() => unitMode.value ? deviceTypes.filter(type => !PLATFORM_TYPES.includes(type.code)) : deviceTypes);
const readOverview = params => unitMode.value ? deviceApi.catalogOverview(params) : deviceApi.overview(params);
const canOperate = computed(() => auth.hasPermission('devices.op'));
const filters = reactive({ keyword: '', type_code: '', channel: '', region: '', vendor: '', connectivity: '', enabled: 'true', sort: 'priority' });
const options = ref({ types: [], channels: [], regions: [], vendors: [] });
const filterTypes = computed(() => {
  const items = options.value.type_options || [];
  const extra = [{ code: 'weather_sensor', name: '天气传感器' }, { code: 'adsb_receiver', name: 'ADS-B 航班接收机' }];
  return [...items, ...extra.filter(type => !items.some(item => item.code === type.code))];
});
const protocols = ref([]);
const overview = ref({ total: 0, online: 0, offline: 0, abnormal: 0, unknown: 0, alarm: 0, vendor_count: 0 });
const table = reactive({ items: [], page: 1, size: 10, total: 0 });
const selectedId = ref('');
const detail = ref(null);
const detailError = ref('');
const loading = ref(false);
const detailLoading = ref(false);
const deletingId = ref('');
const error = ref('');
let alive = true;
let detailSequence = 0;
let listSequence = 0;
let overviewSequence = 0;

const rate = value => overview.value.total ? `${((value || 0) / overview.value.total * 100).toFixed(1)}%` : '—';
const healthText = value => ({ GOOD: '良好', DEGRADED: '一般', BAD: '异常', UNKNOWN: '未知' })[value] || '未知';
const metrics = computed(() => [
  { label: '设备总数', value: overview.value.total, icon: 'total', note: '当前筛选范围内设备' },
  { label: '在线设备', value: overview.value.online, tone: 'green', icon: 'online', note: `在线率 ${rate(overview.value.online)}` },
  { label: '离线设备', value: overview.value.offline, tone: 'amber', icon: 'offline', note: `离线率 ${rate(overview.value.offline)}` },
  { label: '异常设备', value: overview.value.abnormal, tone: 'red', icon: 'abnormal', note: `另有 ${overview.value.unknown || 0} 台状态未知` },
  { label: '告警中设备', value: overview.value.alarm, tone: 'amber', icon: 'alarm', note: '包含在设备状态统计内' },
  { label: '接入厂家数', value: overview.value.vendor_count, tone: 'purple', icon: 'vendor', note: `设备型号 ${overview.value.model_count ?? '—'} 种` }
]);

const deviceDialog = reactive({ visible: false, saving: false, opening: false, editing: false, row: null, current: null, optionsError: '', optionsLoading: false });
const deviceFormRef = ref();
const coordinatePickerOpen = ref(false);
const manualRadarSimulationAllowed = ref(false);
const deviceForm = reactive({
  protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null, allowed_cidrs: '',
  recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false, device_address: 1,
  wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'live', scope_key: '',
  device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: '', address: '',
  longitude: null, latitude: null, coordinate_system: 'WGS-84', version: null, simulated: false, receive_range_km: 350,
  station_guid: '', last_data_at: null, ground_elevation_m: null, mount_height_m: null
});
// pingStation3 推送数据时带机身识别码；登记页用这里的提示告诉管理员往哪发、最近收到了哪些没登记的识别码。
const stationHint = reactive({ udpPort: null, stations: [] });
async function loadStationHint() {
  try { const data = await adsbReceiversApi.unmatchedStations(); Object.assign(stationHint, { udpPort: data?.udp_port ?? null, stations: data?.stations || [] }); }
  catch { Object.assign(stationHint, { udpPort: null, stations: [] }); }
}
watch(() => selectedType.value === 'adsb_receiver' && deviceDialog.visible, show => { if (show) void loadStationHint(); });
const unsupportedCoordinateSystem = computed(() => (deviceForm.longitude != null || deviceForm.latitude != null)
  && !['WGS84', 'WGS-84'].includes(String(deviceForm.coordinate_system || '').trim().toUpperCase()));
function clearDevicePosition() {
  deviceForm.longitude = null; deviceForm.latitude = null; deviceForm.coordinate_system = 'WGS-84';
}
function chooseDeviceLongitude(value) { deviceForm.longitude = value; deviceForm.coordinate_system = 'WGS-84'; }
function chooseDeviceLatitude(value) { deviceForm.latitude = value; deviceForm.coordinate_system = 'WGS-84'; }
// 地面海拔 + 架设高度 = 海拔高度。两项一起填；以前直接登记、没分开的海拔照旧显示。
const loadedElevation = reactive({ ground: null, mount: null, altitude: null });
function elevationNumber(value) { return value == null || value === '' ? null : Number(value); }
function rememberElevation(source) {
  deviceForm.ground_elevation_m = elevationNumber(source?.ground_elevation_m);
  deviceForm.mount_height_m = elevationNumber(source?.mount_height_m);
  Object.assign(loadedElevation, { ground: deviceForm.ground_elevation_m, mount: deviceForm.mount_height_m,
    altitude: source?.altitude_datum === 'AMSL' || source?.altitude_datum == null ? elevationNumber(source?.altitude_m) : null });
}
const deviceAltitudeText = computed(() => {
  const ground = elevationNumber(deviceForm.ground_elevation_m), mount = elevationNumber(deviceForm.mount_height_m);
  if (ground != null && mount != null) return `${Number((ground + mount).toFixed(2))} 米（地面海拔 ${ground} 米 + 架设高度 ${mount} 米）`;
  if (ground == null && mount == null && loadedElevation.altitude != null && loadedElevation.ground == null)
    return `${loadedElevation.altitude} 米（以前登记的，没分开填地面海拔和架设高度）`;
  return '未填写（地面海拔和架设高度都填了才算）';
});
function elevationChange() {
  const ground = elevationNumber(deviceForm.ground_elevation_m), mount = elevationNumber(deviceForm.mount_height_m);
  if ((ground == null) !== (mount == null)) throw new Error('地面海拔和架设高度要一起填写');
  if (ground === loadedElevation.ground && mount === loadedElevation.mount) return null;
  return { ground_elevation_m: ground, mount_height_m: mount };
}
const isWeather = computed(() => selectedType.value === 'weather_sensor');
const isAdsb = computed(() => selectedType.value === 'adsb_receiver');
const usesDemoConfiguration = computed(() => !deviceDialog.editing
  && Boolean(deviceTypes.find(type => type.code === selectedType.value)?.protocols.length)
  && deviceTypes.find(type => type.code === selectedType.value).protocols.every(isMqttProtocol));
const availableProtocols = computed(() => (deviceTypes.find(type => type.code === selectedType.value)?.protocols || [])
  .filter(code => deviceDialog.editing || !isMqttProtocol(code))
  .map(code => protocols.value.find(item => item.protocol_code === code)).filter(Boolean));
const selectedBroker = computed(() => brokers.value.find(item => item.broker_id === deviceForm.broker_id));
const brokers = ref([]);
const scopes = ref([]);
const isMqtt = computed(() => deviceForm.protocol_code === MQTT_PROTOCOL);
const isEo = computed(() => deviceForm.protocol_code === EO_PROTOCOL);
const isMqttTransport = computed(() => isMqtt.value || isEo.value);
const isRadar = computed(() => deviceForm.protocol_code === 'RADAR_TCP_V3_0_0');
const isCountermeasure = computed(() => deviceForm.protocol_code === 'COUNTERMEASURE_TCP_4CH_V2_0');
const flightDistance = useFlightDistance();
watch(isCountermeasure, counter => { if (!counter) flightDistance.clear(); else if (!deviceDialog.editing) void flightDistance.load(null); });

function listParams() {
  return { ...overviewParams(), page: table.page, size: table.size };
}

function overviewParams() {
  return deviceCatalogParams({ ...filters, enabled: filters.enabled === '' ? '' : filters.enabled === 'true' });
}

async function loadOverview() {
  const sequence = ++overviewSequence;
  try {
    const summary = await readOverview(overviewParams());
    if (alive && sequence === overviewSequence) overview.value = summary;
  } catch (e) { if (alive && sequence === overviewSequence && !error.value) error.value = e.message; }
}

async function loadDetail(id, quiet = false) {
  const sequence = ++detailSequence;
  detailError.value = '';
  if (!id) { detail.value = null; detailLoading.value = false; return; }
  if (detail.value?.device?.device_id !== id) detail.value = null;
  if (!quiet) detailLoading.value = true;
  try {
    const record = await deviceApi.detail(id);
    if (!alive || sequence !== detailSequence) return;
    detail.value = record;
  } catch (e) { if (alive && sequence === detailSequence) { if (!quiet || [401, 403, 404].includes(e.status)) detail.value = null; detailError.value = e.message || '设备档案加载失败'; if (quiet) throw e; } }
  finally { if (sequence === detailSequence) detailLoading.value = false; }
}

let listFailure = null;
async function loadList(keepSelection = true, refreshOverview = true, quiet = false) {
  const sequence = ++listSequence;
  if (!quiet) loading.value = true;
  error.value = '';
  const summaryRequest = refreshOverview ? loadOverview() : Promise.resolve();
  try {
    let data = await deviceApi.list(listParams());
    if (!alive || sequence !== listSequence) return;
    const lastPage = Math.max(1, Math.ceil(data.total / table.size));
    if (!data.items.length && table.page > lastPage) {
      table.page = lastPage;
      data = await deviceApi.list(listParams());
      if (!alive || sequence !== listSequence) return;
    }
    Object.assign(table, data);
    if (!keepSelection || !data.items.some(item => item.device_id === selectedId.value)) selectedId.value = data.items[0]?.device_id || '';
    await loadDetail(selectedId.value, quiet);
  } catch (e) { if (alive && sequence === listSequence) { listFailure = e; error.value = e.message || '设备台账加载失败'; if (quiet) throw e; } }
  finally { await summaryRequest; if (sequence === listSequence) loading.value = false; }
}

/* 实时刷新：设备数据变化后静默重读当前筛选和分页下的台账与概况，保留选中设备；
   选中行本身有变化时才重读设备档案。读取失败抛给实时刷新按退避重试；台账上次就读取失败时按正常流程重读。 */
async function realtimeRefresh(topics = []) {
  if (loading.value) return;
  if (error.value) {
    await loadList(true, true, true);
    if (error.value) throw listFailure || new Error(error.value);
    return;
  }
  const sequence = ++listSequence;
  const before = JSON.stringify(table.items?.find(item => item.device_id === selectedId.value) || null);
  void loadOverview();
  try {
    const data = await deviceApi.list(listParams());
    if (!alive || sequence !== listSequence) return;
    if (!data.items.length && table.page > 1 || selectedId.value && !data.items.some(item => item.device_id === selectedId.value)) {
      await loadList(true, false, true);
      return;
    }
    Object.assign(table, data);
    const after = JSON.stringify(data.items.find(item => item.device_id === selectedId.value) || null);
    if (selectedId.value && (after !== before || detailError.value || topics.includes('device') || topics.includes('*')) && after !== 'null' && !detailLoading.value) await loadDetail(selectedId.value, true);
  } catch (e) {
    // 静默刷新失败保留当前台账，抛给实时刷新退避重试。
    if (alive && sequence === listSequence) throw e;
  }
}
useRealtimeRefresh(['device', 'device_state'], realtimeRefresh, { minIntervalMs: 3_000 });

const route = useRoute();
if (route.query.type === 'weather_sensor') filters.type_code = 'weather_sensor';

async function bootstrap() {
  loading.value = true; error.value = '';
  const sequence = ++overviewSequence;
  try {
    const [filterOptions, summary, protocolList] = await Promise.all([deviceApi.options(), readOverview(overviewParams()), integrationApi.protocols()]);
    if (!alive) return;
    options.value = filterOptions; protocols.value = protocolList;
    if (sequence === overviewSequence) overview.value = summary;
    await loadList(false, false);
  } catch (e) { error.value = e.message || '设备管理数据加载失败'; loading.value = false; }
}

function search() { table.page = 1; void loadList(false); }
function reset() { Object.assign(filters, { keyword: '', type_code: '', channel: '', region: '', vendor: '', connectivity: '', enabled: 'true', sort: 'priority' }); search(); }
function selectRow(row) { selectedId.value = row.device_id; loadDetail(row.device_id); }

function resetDeviceForm() {
  Object.assign(deviceForm, { protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null,
    allowed_cidrs: '', recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false,
    device_address: 1, wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'live',
    scope_key: '', device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: '', address: '',
    longitude: null, latitude: null, coordinate_system: 'WGS-84', version: null, simulated: false, receive_range_km: 350,
    station_guid: '', last_data_at: null });
  rememberElevation(null);
  coordinatePickerOpen.value = false;
}

function clearProtocolFields() {
  Object.assign(deviceForm, { host: '', port: null, allowed_cidrs: '', recognition_code_ref: '', rtk_enabled: false,
    coordinate_transform_enabled: false, device_address: 1, wire_encoding: 'AUTO', poll_interval_millis: 5000,
    broker_id: '', source_mode: 'live', provider_code: '', external_device_id: '', edge_id: '', simulated: false });
  nextTick(() => deviceFormRef.value?.clearValidate());
}
function selectDeviceType(type) {
  if (deviceDialog.editing || deviceDialog.saving || selectedType.value === type.code) return;
  selectedType.value = type.code; clearProtocolFields();
  deviceForm.device_type_abbr = type.code;
  deviceForm.protocol_code = availableProtocols.value[0]?.protocol_code || '';
}
let accessOptionsSequence = 0;
async function refreshAccessOptions() {
  const sequence = ++accessOptionsSequence;
  deviceDialog.optionsError = '';
  deviceDialog.optionsLoading = true;
  try {
    // 新增及 TCP 接入只需要单位范围；已有 MQTT 设备才读取原绑定，不能让模拟通道故障阻断 TCP。
    const [brokerOptions, scopeOptions, onboardingOptions] = await Promise.all([
      isMqttProtocol(deviceDialog.current?.protocol_code) ? mqttApi.options() : Promise.resolve([]), mqttApi.scopes(),
      deviceDialog.editing ? Promise.resolve({}) : deviceApi.onboardingOptions().catch(() => ({}))
    ]);
    if (!alive || sequence !== accessOptionsSequence) return;
    brokers.value = unitMode.value ? brokerOptions.filter(item => item.owner_org_id === auth.user.management_context.org_id) : brokerOptions;
    scopes.value = unitMode.value ? scopeOptions.filter(item => item.org_id === auth.user.management_context.org_id) : scopeOptions;
    manualRadarSimulationAllowed.value = onboardingOptions.manual_radar_simulation_allowed === true;
  } catch (e) { if (alive && sequence === accessOptionsSequence) deviceDialog.optionsError = e.message || '接入资料读取失败，请重试'; }
  finally { if (sequence === accessOptionsSequence) deviceDialog.optionsLoading = false; }
}

async function openDevice(row = null) {
  if (!canOperate.value || deviceDialog.opening || unitMode.value && PLATFORM_TYPES.includes(row?.device_type_code)) return;
  deviceDialog.opening = true; selectedType.value = '';
  deviceDialog.editing = Boolean(row); deviceDialog.row = row; deviceDialog.current = null; resetDeviceForm(); flightDistance.clear();
  try {
    const current = row ? await (row.device_type_code === 'weather_sensor' ? weatherSensorsApi.get(row.device_id)
      : row.device_type_code === 'adsb_receiver' ? adsbReceiversApi.get(row.device_id) : deviceApi.detail(row.device_id)) : null;
    deviceDialog.current = current;
    await refreshAccessOptions();
    if (row?.device_type_code === 'weather_sensor') {
      selectedType.value = 'weather_sensor';
      Object.assign(deviceForm, current, { scope_key: `${current.owner_org_id}/${current.district_id}` });
    } else if (row?.device_type_code === 'adsb_receiver') {
      selectedType.value = 'adsb_receiver';
      Object.assign(deviceForm, current, { scope_key: `${current.owner_org_id}/${current.district_id}`,
        longitude: current.longitude == null ? null : Number(current.longitude), latitude: current.latitude == null ? null : Number(current.latitude),
        coordinate_system: 'WGS-84', vendor: current.vendor || '', model: current.model || '', address: current.address || '',
        station_guid: current.station_guid || '', last_data_at: current.last_data_at ?? null });
      rememberElevation(current);
    } else if (current) {
      const connection = current.connection || {}, protocol = current.protocol_configuration || {};
      Object.assign(deviceForm, {
        protocol_code: current.protocol_code || '', device_no: current.device?.device_no || '', name: current.device?.name || '',
        vendor: current.vendor || '', region_name: current.region_name || '', model: current.model || '', address: current.address || '',
        longitude: current.longitude ?? current.device?.longitude ?? null, latitude: current.latitude ?? current.device?.latitude ?? null,
        coordinate_system: current.longitude != null || current.latitude != null || current.device?.longitude != null || current.device?.latitude != null
          ? current.coordinate_system ?? current.device?.coordinate_system ?? null : 'WGS-84',
        scope_key: current.device?.owner_org_id && current.device?.district_id ? `${current.device.owner_org_id}/${current.device.district_id}` : '',
        host: connection.host || '', port: connection.port ?? null, allowed_cidrs: current.allowed_cidrs || '',
        recognition_code_ref: protocol.recognition_code_ref || '', rtk_enabled: Boolean(protocol.rtk_enabled),
        coordinate_transform_enabled: Boolean(protocol.coordinate_transform_enabled), device_address: protocol.device_address || 1,
        wire_encoding: protocol.wire_encoding || 'AUTO', poll_interval_millis: protocol.poll_interval_millis || 5000
      });
      rememberElevation(current);
      if ([MQTT_PROTOCOL, EO_PROTOCOL].includes(current.protocol_code)) {
        const status = await deviceApi.protocolStatus(row.device_id);
        const mqtt = status?.details || {};
        const broker = brokers.value.find(item => item.broker_id === mqtt.broker_id);
        Object.assign(deviceForm, mqtt, { model: current.model || '', scope_key: broker ? `${broker.owner_org_id}/${broker.district_id}` : deviceForm.scope_key });
      }
      if (current.protocol_code === COUNTER_PROTOCOL) await flightDistance.load(row.device_id);
      selectedType.value = current.protocol_code === RADAR_PROTOCOL ? 'radar' : current.protocol_code === COUNTER_PROTOCOL ? 'counter'
        : current.protocol_code === EO_PROTOCOL ? 'eo' : deviceForm.device_type_abbr;
    }
    deviceDialog.visible = true;
  } catch (e) { ElMessage.error(e.message || '接入配置加载失败'); }
  finally { deviceDialog.opening = false; }
}

function tcpConnection(values, current) {
  const previous = current?.connection || {};
  return { transport: 'TCP', host: values.host.trim(), port: values.port, data_format: 'BINARY', charset_name: 'UTF-8',
    auth_mode: 'Token', credential_ref: previous.credential_ref || null, heartbeat_interval_seconds: 30,
    report_interval_millis: 1000, timeout_millis: previous.timeout_millis || 3000, retry_count: 3,
    time_sync_mode: 'NTP', timezone_name: 'Asia/Shanghai', time_sync_interval_seconds: 60 };
}

function locationValues() {
  const longitude = deviceForm.longitude == null || deviceForm.longitude === '' ? null : Number(deviceForm.longitude);
  const latitude = deviceForm.latitude == null || deviceForm.latitude === '' ? null : Number(deviceForm.latitude);
  if (longitude == null && latitude == null) return { longitude: null, latitude: null, coordinate_system: null };
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) throw new Error('经度和纬度必须同时填写');
  if (longitude < -180 || longitude > 180) throw new Error('经度必须在 -180 到 180 之间');
  if (latitude < -90 || latitude > 90) throw new Error('纬度必须在 -90 到 90 之间');
  if (unsupportedCoordinateSystem.value) throw new Error('已有坐标系未声明为 WGS-84，请清空后录入已核实的 WGS-84 坐标，或重新地图选点');
  return { longitude, latitude, coordinate_system: 'WGS-84' };
}

function mqttPayload(values, version, location) {
  const broker = brokers.value.find(item => item.broker_id === values.broker_id);
  const [owner_org_id, district_id] = values.scope_key.split('/');
  if (!broker || broker.source_mode !== values.source_mode || broker.owner_org_id !== owner_org_id || broker.district_id !== district_id) throw new Error('原接入通道与设备来源或所属范围不一致，请检查接入资料后重试。');
  const common = { protocol_code: values.protocol_code, broker_id: broker.broker_id, external_device_id: values.external_device_id.trim(),
    source_mode: values.source_mode, owner_org_id, district_id, device_no: values.device_no.trim(), name: values.name.trim(),
    vendor: values.vendor || null, model: values.model || null, ...location, version };
  return values.protocol_code === EO_PROTOCOL ? { ...common, edge_id: values.edge_id.trim() }
    : { ...common, provider_code: values.provider_code.trim(), device_type_abbr: values.device_type_abbr };
}

async function saveDevice() {
  if (!canOperate.value || deviceDialog.saving || !selectedType.value || usesDemoConfiguration.value) return;
  if (isMqttTransport.value && !deviceDialog.editing) return;
  if (!isWeather.value && !isAdsb.value && !availableProtocols.value.some(item => item.protocol_code === deviceForm.protocol_code)) return;
  if (deviceDialog.optionsError || deviceDialog.optionsLoading) return;
  if (!await deviceFormRef.value.validate().catch(() => false) || deviceDialog.saving) return;
  let location, elevation = null;
  try { location = locationValues(); if (!isWeather.value) elevation = elevationChange(); }
  catch (e) { ElMessage.error(e.message); return; }
  const distanceProblem = isCountermeasure.value ? flightDistance.validate() : null;
  if (distanceProblem) { ElMessage.error(distanceProblem); return; }
  deviceDialog.saving = true;
  const current = deviceDialog.current;
  const key = newIdempotencyKey('device-form');
  try {
    let saved;
    const [tcpOrg, tcpDistrict] = deviceForm.scope_key.split('/');
    if (isWeather.value) {
      const [owner_org_id, district_id] = deviceForm.scope_key.split('/');
      const payload = { device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), vendor: deviceForm.vendor?.trim() || null,
        model: deviceForm.model?.trim() || null, address: deviceForm.address?.trim() || null, owner_org_id, district_id, version: deviceForm.version };
      const sensor = deviceDialog.editing ? await weatherSensorsApi.update(deviceDialog.row.device_id, payload) : await weatherSensorsApi.create(payload);
      saved = { device: sensor };
    } else if (isAdsb.value) {
      if (location.longitude == null) throw new Error('请填写航班接收机的安装经纬度');
      const [owner_org_id, district_id] = deviceForm.scope_key.split('/');
      const payload = { device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), vendor: deviceForm.vendor?.trim() || null,
        model: deviceForm.model?.trim() || null, address: deviceForm.address?.trim() || null, owner_org_id, district_id,
        longitude: location.longitude, latitude: location.latitude, receive_range_km: deviceForm.receive_range_km,
        station_guid: deviceForm.station_guid?.trim() || null, version: deviceForm.version };
      const receiver = deviceDialog.editing ? await adsbReceiversApi.update(deviceDialog.row.device_id, payload) : await adsbReceiversApi.create(payload);
      saved = { device: receiver };
    } else if (isMqttTransport.value) {
      const payload = mqttPayload(deviceForm, current?.device?.version, location);
      saved = await deviceApi.update(deviceDialog.row.device_id, payload, key);
    } else if (deviceDialog.editing) {
      saved = await deviceApi.update(deviceDialog.row.device_id, {
        version: current.device.version, owner_org_id: tcpOrg, district_id: tcpDistrict, source_id: current.source_id, external_device_id: current.external_device_id,
        device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), device_type_code: current.device.device_type_code,
        device_type_name: current.device.device_type_name, channel: current.device.channel, vendor: deviceForm.vendor || null,
        model: deviceForm.model || null, owner_name: current.owner_name || null, region_name: deviceForm.region_name || null,
        address: deviceForm.address || null, longitude: location.longitude, latitude: location.latitude,
        coordinate_system: location.coordinate_system, altitude_m: current.altitude_m ?? null, altitude_datum: current.altitude_datum ?? null,
        allowed_cidrs: deviceForm.allowed_cidrs.trim(), connection: tcpConnection(deviceForm, current),
        protocol_configuration: { login_role: 'DATA', recognition_code_ref: deviceForm.recognition_code_ref || null,
          rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
          device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }
      }, key);
    } else {
      saved = await deviceApi.onboard({ owner_org_id: tcpOrg, district_id: tcpDistrict, protocol_code: deviceForm.protocol_code, device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(),
        simulated: isRadar.value && manualRadarSimulationAllowed.value && deviceForm.simulated,
        host: deviceForm.host.trim(), port: deviceForm.port, allowed_cidrs: deviceForm.allowed_cidrs.trim(), vendor: deviceForm.vendor || null,
        region_name: deviceForm.region_name || null, model: deviceForm.model || null, address: deviceForm.address || null,
        ...location, recognition_code_ref: deviceForm.recognition_code_ref || null,
        rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
        device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }, key);
    }
    selectedId.value = saved.device.device_id;
    if (elevation) await deviceApi.elevation(saved.device.device_id, elevation);
    if (isCountermeasure.value) {
      try { await flightDistance.save(saved.device.device_id); }
      catch (e) {
        // 设备已经保存；距离没保存时留在弹窗里，提示再保存一次。
        deviceDialog.editing = true; deviceDialog.row = saved.device; deviceDialog.current = saved;
        ElMessage.error(`设备已保存，但附近航班安全距离没有保存：${e.message}`); await loadList(true); return;
      }
    }
    deviceDialog.visible = false;
    ElMessage.success(isWeather.value ? '天气传感器档案已保存，待接入' : isAdsb.value ? '航班接收机已保存' : deviceDialog.editing ? '设备已更新' : '接入配置已保存，请确认设备连接及上报状态');
    await loadList(true);
  } catch (e) { ElMessage.error(e.message); if (e.code === 'VERSION_CONFLICT') await loadList(); }
  finally { deviceDialog.saving = false; }
}

async function toggleDevice(row) {
  if (!canOperate.value) return;
  try {
    const { value } = await ElMessageBox.prompt(`${row.enabled ? '停用后会断开协议连接，并拒绝新建调测任务。' : '启用后会按配置重新建立协议连接。'}请输入操作原因：`, `${row.enabled ? '停用' : '启用'}设备 · ${formatDeviceNo(row.device_no)}`, { inputType: 'textarea', inputValidator: value => value?.trim().length >= 2 || '原因至少填写 2 个字符', confirmButtonText: '确认', cancelButtonText: '取消' });
    await deviceApi.setEnabled(row.device_id, { enabled: !row.enabled, version: row.version, reason: value.trim() });
    ElMessage.success(`设备已${row.enabled ? '停用' : '启用'}，审计记录已写入`); await loadList();
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e)); }
}

async function deleteDevice(row) {
  if (!canOperate.value || deletingId.value) return;
  if (row.enabled) { ElMessage.warning('请先停用设备，再执行删除'); return; }
  deletingId.value = row.device_id;
  try {
    const record = await confirmDeletion(`确认删除设备“${row.name}”（${formatDeviceNo(row.device_no)}）？删除后将从台账和监控列表移除，保留历史记录及设备编号，不能重新启用。`, '删除设备');
    await deviceApi.remove(row.device_id, { version: row.version, reason: record });
    ++listSequence;
    if (selectedId.value === row.device_id) {
      selectedId.value = '';
      await loadDetail('');
    }
    ElMessage.success('设备已删除，历史记录已保留');
    await loadList();
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.message || String(e));
      if (['VERSION_CONFLICT', 'DEVICE_NOT_FOUND', 'IDEMPOTENCY_REPLAY'].includes(e.code)) await loadList();
    }
  } finally { deletingId.value = ''; }
}

watch(() => [filters.type_code, filters.channel, filters.region, filters.vendor, filters.connectivity, filters.enabled, filters.sort], search);
const mapWarmup = new AbortController();
onMounted(() => { warmDeviceMapResources(mapWarmup.signal); return bootstrap(); });
onBeforeUnmount(() => { mapWarmup.abort(); alive = false; detailSequence++; });
</script>

<template>
  <section class="page-stack operation-page devices-reference">
    <PageHeader title="设备管理" description="设备台账、接入配置与运行状态统一管理。">
      <el-button type="primary" :disabled="!canOperate" :loading="deviceDialog.opening" @click="openDevice()">接入设备</el-button>
    </PageHeader>
    <OperationMetrics :items="metrics" />
    <ErrorAlert :message="error" @retry="bootstrap" />
    <div class="devices-workspace">
    <el-card class="table-card devices-list-panel">
      <template #header><div class="table-toolbar"><b>设备管理</b><span class="muted">共 {{ table.total }} 台 · 点击设备查看详情</span></div></template>
      <div class="device-filters">
      <el-form inline @submit.prevent="search">
        <el-form-item label="关键词"><el-input v-model="filters.keyword" clearable placeholder="设备编号或名称" @keyup.enter="search" /></el-form-item>
        <el-form-item label="设备类型"><el-select v-model="filters.type_code" clearable placeholder="全部"><el-option v-for="item in filterTypes" :key="item.code" :label="item.name || item.code" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="所属区域"><el-select v-model="filters.region" clearable placeholder="全部"><el-option v-for="item in options.regions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="供应商"><el-select v-model="filters.vendor" clearable placeholder="全部"><el-option v-for="item in options.vendors" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="接入通道"><el-select v-model="filters.channel" clearable placeholder="全部"><el-option v-for="item in options.channels" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="连接状态"><el-select v-model="filters.connectivity" clearable placeholder="全部"><el-option v-for="value in ['ONLINE','OFFLINE','ABNORMAL','UNKNOWN']" :key="value" :label="statusText(value)" :value="value" /></el-select></el-form-item>
        <el-form-item label="台账状态"><el-select v-model="filters.enabled" clearable placeholder="全部"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      </div>
        <div class="table-toolbar"><span class="table-toolbar__title">设备台账</span><span class="muted">默认优先显示异常、离线和未知设备</span></div>
        <div class="table-scroll"><el-table v-loading="loading" :data="table.items" max-height="560" row-key="device_id" :row-class-name="({row}) => row.device_id===selectedId?'selected-row':''" @row-click="selectRow">
          <el-table-column label="设备编号" min-width="135" fixed><template #default="{row}"><span class="device-number" :title="row.device_no">{{ formatDeviceNo(row.device_no) }}</span></template></el-table-column>
          <el-table-column prop="name" label="设备名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型 / 通道" min-width="125"><template #default="{row}">{{ display(row.device_type_name) }}<span class="cell-secondary">{{ display(row.channel) }}</span></template></el-table-column>
          <el-table-column label="产权单位 / 位置" min-width="170"><template #default="{row}">{{ display(row.owner_name) }}<span class="cell-secondary">{{ row.address || row.region_name || '未登记位置' }}</span></template></el-table-column>
          <el-table-column label="型号 / 供应商" min-width="145"><template #default="{row}">{{ display(row.model) }}<span class="cell-secondary">{{ display(row.vendor) }}</span></template></el-table-column>
          <el-table-column label="状态 / 健康" width="105"><template #default="{row}"><el-tag :type="statusType(row.connectivity)" effect="plain">{{ statusText(row.connectivity) }}</el-tag><span class="cell-secondary">{{ healthText(row.health_code) }}</span></template></el-table-column>
          <el-table-column prop="last_heartbeat_at" label="最后心跳" min-width="165"><template #default="{row}"><span class="mono">{{ formatTime(row.last_heartbeat_at) }}</span></template></el-table-column>
          <el-table-column label="状态" width="76"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'" effect="plain">{{ row.enabled?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="175" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!canOperate || Boolean(deletingId) || (unitMode && ['weather_sensor','adsb_receiver'].includes(row.device_type_code))" @click.stop="openDevice(row)">编辑</el-button><el-button link :type="row.enabled?'danger':'success'" :disabled="!canOperate || Boolean(deletingId) || (row.device_type_code==='weather_sensor' && !row.enabled) || (row.device_type_code==='adsb_receiver' && !row.enabled && !row.simulated)" @click.stop="toggleDevice(row)">{{ row.enabled?'停用':'启用' }}</el-button><el-button link type="danger" :disabled="!canOperate || Boolean(deletingId)" :loading="deletingId === row.device_id" @click.stop="deleteDevice(row)">删除</el-button></template></el-table-column>
        </el-table></div>
        <div class="pagination-row"><span>共 {{ table.total }} 台</span><el-pagination v-model:current-page="table.page" v-model:page-size="table.size" :page-sizes="[10,20,50,100]" layout="sizes, prev, pager, next" :total="table.total" @current-change="loadList(false)" @size-change="table.page=1;loadList(false)" /></div>
      </el-card>

      <DeviceCatalogPreview :detail="detail" :loading="detailLoading" :error="detailError" :observations-allowed="!unitMode || auth.hasPermission('monitoring.read')" @refresh="loadDetail(selectedId)" />
    </div>

    <el-dialog v-model="deviceDialog.visible" :title="deviceDialog.editing ? `编辑设备 · ${formatDeviceNo(deviceForm.device_no)}` : '接入设备'" width="min(990px, 94vw)" class="device-access-dialog" destroy-on-close :close-on-click-modal="!deviceDialog.saving" :close-on-press-escape="!deviceDialog.saving" :show-close="!deviceDialog.saving">
      <el-form ref="deviceFormRef" :model="deviceForm" label-position="top" :disabled="deviceDialog.saving" class="access-form">
        <section class="access-section">
          <div class="access-section-title"><span class="access-step-number">1</span><h3>选择设备类型</h3><small>{{ deviceDialog.editing ? '接入身份创建后不可修改' : '不同类型展示对应配置' }}</small></div>
          <div class="access-type-grid" role="group" aria-label="设备类型">
            <button v-for="item in availableDeviceTypes" :key="item.code" type="button" class="access-type" :class="{ selected: selectedType === item.code }" :aria-pressed="selectedType === item.code" :disabled="deviceDialog.editing || deviceDialog.saving" @click="selectDeviceType(item)">{{ item.name }}</button>
          </div>
        </section>
        <el-alert v-if="usesDemoConfiguration" class="demo-access-note" title="此类型沿用现有演示配置" description="请启动现有演示环境和模拟器，在设备列表中选择已登记设备进行资料编辑、实时监测及接入调测。若缺少模拟设备或上报，请检查演示环境；无需在此新增 MQTT 连接或重复登记设备。" type="info" :closable="false" show-icon />
        <template v-else-if="selectedType">
          <section class="access-section">
            <div class="access-section-title"><span class="access-step-number">2</span><h3>设备基本资料</h3><small>* 为必填项</small></div>
            <div class="access-basic-grid">
              <el-form-item label="设备编号" prop="device_no" :rules="[{required:true,whitespace:true,message:'请输入设备编号'}]"><el-input v-model="deviceForm.device_no" :disabled="deviceDialog.editing" maxlength="64" placeholder="请输入唯一设备编号" /></el-form-item>
              <el-form-item label="设备名称" prop="name" :rules="[{required:true,whitespace:true,message:'请输入设备名称'}]"><el-input v-model="deviceForm.name" maxlength="128" placeholder="请输入设备名称" /></el-form-item>
              <el-form-item label="供应商"><el-input v-model="deviceForm.vendor" maxlength="128" placeholder="请输入供应商" /></el-form-item>
              <el-form-item label="型号"><el-input v-model="deviceForm.model" maxlength="128" placeholder="请输入设备型号" /></el-form-item>
              <el-form-item label="所属单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择所属单位及区域'}]"><el-select v-model="deviceForm.scope_key" :disabled="deviceDialog.editing && (isWeather || isAdsb || isMqttTransport || Boolean(deviceDialog.current?.device?.owner_org_id))" placeholder="请选择单位及区域"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
              <el-form-item v-if="!isMqttTransport" label="安装位置"><el-input v-model="deviceForm.address" maxlength="256" placeholder="例如：园区东门楼顶" /></el-form-item>
              <div v-if="!isWeather" class="wide coordinate-section">
                <div class="coordinate-section-heading"><div><b>地图位置</b><span>可选，仅接收 WGS-84 坐标；手工录入前请核实坐标系，地图选点自动使用 WGS-84</span></div><div><el-button size="small" @click="clearDevicePosition">清空坐标</el-button><el-button size="small" type="primary" plain @click="coordinatePickerOpen = !coordinatePickerOpen">{{ coordinatePickerOpen ? '收起地图' : '打开地图选点' }}</el-button></div></div>
                <el-alert v-if="unsupportedCoordinateSystem" type="warning" :closable="false" show-icon :title="`已有坐标系：${deviceForm.coordinate_system || '未声明'}，无法按 WGS-84 保存`" description="请清空后录入已核实的 WGS-84 坐标，或重新地图选点；系统不会自动转换坐标。" />
                <div class="coordinate-fields">
                  <el-form-item label="安装经度"><el-input-number v-model="deviceForm.longitude" :min="-180" :max="180" :precision="7" :step="0.000001" controls-position="right" placeholder="例如：118.6592400" /></el-form-item>
                  <el-form-item label="安装纬度"><el-input-number v-model="deviceForm.latitude" :min="-90" :max="90" :precision="7" :step="0.000001" controls-position="right" placeholder="例如：37.4300000" /></el-form-item>
                  <el-form-item label="地面海拔（米）"><el-input-number v-model="deviceForm.ground_elevation_m" :min="-500" :max="9000" :precision="1" :step="1" controls-position="right" placeholder="设备所在地面的海拔，例如：5" /></el-form-item>
                  <el-form-item label="架设高度（米）"><el-input-number v-model="deviceForm.mount_height_m" :min="0" :max="1000" :precision="1" :step="1" controls-position="right" placeholder="设备离地面多高，例如：25" /></el-form-item>
                </div>
                <div class="access-field-note">海拔高度：{{ deviceAltitudeText }}</div>
                <DeviceCoordinatePicker v-if="coordinatePickerOpen" :longitude="unsupportedCoordinateSystem ? null : deviceForm.longitude" :latitude="unsupportedCoordinateSystem ? null : deviceForm.latitude" :disabled="deviceDialog.saving" @update:longitude="chooseDeviceLongitude" @update:latitude="chooseDeviceLatitude" />
              </div>
              <div v-if="isCountermeasure && flightDistance.state.visible" class="wide adsb-receive-settings flight-distance-settings">
                <b>附近航班安全距离</b>
                <el-radio-group v-model="flightDistance.state.custom" :disabled="!flightDistance.state.canManage"><el-radio :label="false">用规则管理里的默认值</el-radio><el-radio :label="true">这台设备单独设置</el-radio></el-radio-group>
                <fieldset v-if="flightDistance.state.custom" class="fd-fields" :disabled="!flightDistance.state.canManage">
                  <div class="adsb-receive-row"><label class="fd-mode">迫降</label><label>禁止反制范围</label><el-input-number class="fd-input" v-model="flightDistance.state.values.force_land_block_km" :min="0.5" :max="350" :precision="1" :step="1" controls-position="right" /><span class="adsb-unit">公里</span><label>提醒范围</label><el-input-number class="fd-input" v-model="flightDistance.state.values.force_land_warn_km" :min="0.5" :max="350" :precision="1" :step="1" controls-position="right" /><span class="adsb-unit">公里</span><label>只看</label><el-input-number class="fd-input" v-model="flightDistance.state.values.force_land_max_altitude_m" :min="100" :max="15000" :precision="0" :step="100" controls-position="right" /><span class="adsb-unit">米以下的航班</span></div>
                  <div class="adsb-receive-row"><label class="fd-mode">驱离</label><label>提醒范围</label><el-input-number class="fd-input" v-model="flightDistance.state.values.drive_away_warn_km" :min="0.5" :max="350" :precision="1" :step="1" controls-position="right" /><span class="adsb-unit">公里</span><label>只看</label><el-input-number class="fd-input" v-model="flightDistance.state.values.drive_away_max_altitude_m" :min="100" :max="15000" :precision="0" :step="100" controls-position="right" /><span class="adsb-unit">米以下的航班</span></div>
                </fieldset>
                <div v-else class="access-field-note">{{ flightDistance.defaultText() }}</div>
              </div>
              <div v-if="isAdsb" class="wide adsb-receive-settings">
                <b>接收设置</b>
                <div class="adsb-receive-row"><label for="adsb-receive-range">接收范围</label><el-input-number id="adsb-receive-range" class="adsb-range-input" v-model="deviceForm.receive_range_km" :min="10" :max="600" :precision="0" controls-position="right" /><span class="adsb-unit">公里</span><span class="access-field-note">地图上显示这个范围内的民航航班</span></div>
                <div class="adsb-receive-row"><label for="adsb-station-guid">接收机识别码</label><el-input id="adsb-station-guid" class="adsb-guid-input" v-model="deviceForm.station_guid" maxlength="16" placeholder="设备自带的 16 位识别码" /><span class="access-field-note">接收机发来的数据带这个识别码，对上才收</span></div>
                <div v-if="stationHint.stations.length" class="adsb-receive-row adsb-station-hint"><span class="access-field-note">最近收到数据但还没登记的识别码：</span><el-button v-for="station in stationHint.stations" :key="station.station_guid" link type="primary" @click="deviceForm.station_guid = station.station_guid">{{ station.station_guid }}</el-button></div>
                <div class="adsb-receive-row"><span class="access-field-note">{{ stationHint.udpPort > 0 ? `接收机的发送地址填本系统的地址和端口 ${stationHint.udpPort}` : '本系统收航班数据的端口没有打开，请联系维护人员' }}<template v-if="deviceDialog.editing">；{{ deviceForm.last_data_at ? `最近收到数据：${formatTime(deviceForm.last_data_at)}` : '还没收到过数据' }}</template></span></div>
              </div>
            </div>
            <el-alert v-if="deviceDialog.optionsError" :title="deviceDialog.optionsError" type="error" :closable="false"><el-button link @click="refreshAccessOptions">重新加载接入资料</el-button></el-alert>
          </section>
          <section class="access-section">
            <div class="access-section-title"><span class="access-step-number">3</span><h3>{{ isWeather ? '接入状态' : '接入配置' }}</h3><el-tag v-if="isWeather" class="access-mode-tag" type="warning" effect="plain">协议待确认</el-tag><el-tag v-else-if="isAdsb" class="access-mode-tag" effect="plain">网络接入</el-tag><el-tag v-else-if="deviceForm.protocol_code" class="access-mode-tag" effect="plain">{{ isMqttTransport ? '沿用已有配置' : 'TCP 直连' }}</el-tag></div>
            <el-alert v-if="isWeather" title="厂家协议待确认，当前仅登记设备档案" description="天气传感器统一纳入设备台账。协议确认前无需填写设备地址、端口或 MQTT 连接，登记后保持停用与待接入。" type="warning" :closable="false" show-icon />
            <div v-else-if="isAdsb" class="access-protocol-row"><el-form-item label="接入协议"><el-input model-value="ADS-B 航班数据" disabled /></el-form-item></div>
            <template v-else>
              <div class="access-protocol-row"><el-form-item label="接入协议" prop="protocol_code" :rules="[{required:true,message:'请选择接入协议'}]"><el-select v-model="deviceForm.protocol_code" :disabled="deviceDialog.editing || availableProtocols.length === 1" @change="clearProtocolFields"><el-option v-for="item in availableProtocols" :key="item.protocol_code" :label="`${item.name} · v${item.version}`" :value="item.protocol_code" /></el-select></el-form-item><span class="muted">仅展示当前设备类型已支持的协议</span></div>
              <el-alert v-if="!availableProtocols.length" title="当前没有可用的接入协议，请刷新后重试" type="warning" :closable="false" />
              <div v-else class="form-grid access-connection-grid">
                <template v-if="isMqttTransport">
                  <el-form-item label="接入通道" prop="broker_id" :rules="[{required:true,message:'原接入通道信息缺失，请重新加载'}]">
                    <span v-if="deviceDialog.optionsLoading" class="access-field-note" role="status">正在读取接入通道…</span>
                    <span v-else-if="deviceDialog.optionsError" class="access-field-note">接入通道读取失败，请先重新加载接入资料。</span>
                    <template v-else-if="selectedBroker"><span>{{ selectedBroker.name }}</span><span class="access-field-note">沿用已登记通道{{ !selectedBroker.enabled ? '，当前已停用' : '' }}{{ deviceForm.source_mode === 'replay' ? '（模拟数据）' : '' }}，此处仅修改设备资料。</span></template>
                    <span v-else class="access-field-note">原接入通道信息缺失，请检查演示环境或原接入配置后重新加载。</span>
                  </el-form-item>
                  <el-form-item label="外部设备编号" prop="external_device_id" :rules="[{required:true,whitespace:true,message:'请输入外部设备编号'}]"><el-input v-model="deviceForm.external_device_id" :disabled="deviceDialog.editing" placeholder="填写厂家设备 ID" /></el-form-item>
                  <el-form-item v-if="isMqtt" label="提供方编码" prop="provider_code" :rules="[{required:true,whitespace:true,message:'请输入提供方编码'}]"><el-input v-model="deviceForm.provider_code" :disabled="deviceDialog.editing" placeholder="填写厂家提供的编码" /></el-form-item>
                  <el-form-item v-if="isEo" label="边缘中心 ID" prop="edge_id" :rules="[{required:true,whitespace:true,message:'请输入边缘中心 ID'}]"><el-input v-model="deviceForm.edge_id" :disabled="deviceDialog.editing" placeholder="填写协议中的 edgeId" /></el-form-item>
                </template>
                <template v-else>
                  <el-form-item v-if="isRadar && !deviceDialog.editing && manualRadarSimulationAllowed" label="数据来源" class="wide"><el-radio-group v-model="deviceForm.simulated"><el-radio :label="false">真实设备</el-radio><el-radio :label="true">本机模拟测试</el-radio></el-radio-group><span v-if="deviceForm.simulated" class="access-field-note">先启动 TCP 雷达模拟器的“后台手动接入测试”，按该页面填写参数。保存后保留模拟标识。</span></el-form-item>
                  <el-form-item label="设备地址" prop="host" :rules="[{required:true,whitespace:true,message:'请输入设备地址'}]"><el-input v-model="deviceForm.host" placeholder="例如：192.168.1.100" /></el-form-item>
                  <el-form-item label="端口" prop="port" :rules="[{required:true,message:'请输入端口'}]"><el-input-number v-model="deviceForm.port" :min="1" :max="65535" controls-position="right" /></el-form-item>
                  <el-form-item label="允许网段" prop="allowed_cidrs" :rules="[{required:true,whitespace:true,message:'请输入允许网段'}]"><el-input v-model="deviceForm.allowed_cidrs" placeholder="例如：192.168.1.0/24" /></el-form-item>
                  <el-form-item v-if="isRadar" label="雷达识别码引用"><el-input v-model="deviceForm.recognition_code_ref" placeholder="env:RADAR_RECOGNITION_CODE" /></el-form-item>
                  <el-form-item v-if="isRadar" label="雷达扩展" class="wide"><el-checkbox v-model="deviceForm.rtk_enabled">采集 RTK</el-checkbox><el-checkbox v-model="deviceForm.coordinate_transform_enabled">派生经纬度</el-checkbox></el-form-item>
                  <el-form-item v-if="isCountermeasure" label="控制器地址"><el-input-number v-model="deviceForm.device_address" :min="1" :max="244" /></el-form-item>
                  <el-form-item v-if="isCountermeasure" label="线缆编码"><el-select v-model="deviceForm.wire_encoding"><el-option v-for="item in ['AUTO','RAW_BYTES','ASCII_HEX_SPACED','ASCII_HEX_COMPACT']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                </template>
              </div>
            </template>
          </section>
        </template>
        <el-empty v-else description="请选择需要接入的设备类型" :image-size="65" />
      </el-form>
      <template #footer><div class="access-footer"><span>{{ usesDemoConfiguration ? '模拟设备由现有演示环境提供。' : isWeather ? '仅保存设备档案，保持停用与待接入。' : isAdsb ? '持续收到航班数据时设备显示在线。' : '配置保存后，以实际连接与有效报文确认状态。' }}</span><div><el-button :disabled="deviceDialog.saving" @click="deviceDialog.visible=false">取消</el-button><el-button type="primary" :loading="deviceDialog.saving" :disabled="!selectedType || usesDemoConfiguration || (!isWeather && !isAdsb && !deviceForm.protocol_code) || deviceDialog.optionsLoading || Boolean(deviceDialog.optionsError) || (isMqttTransport && !selectedBroker)" @click="saveDevice">{{ isWeather ? '保存档案' : deviceDialog.editing ? '保存' : '保存接入配置' }}</el-button></div></div></template>
    </el-dialog>

  </section>
</template>

<style scoped>
.devices-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 350px; gap: 14px; align-items: start; }
.device-filters .el-form { display: flex; flex-wrap: wrap; gap: 0 12px; }
.device-filters .el-form-item { margin: 0 0 12px; }
.device-filters .el-select { width: 130px; }
.device-filters .el-input { width: 180px; }
.devices-list-panel .table-scroll { height: auto; }
.devices-list-panel .pagination-row { flex-wrap: wrap; }
.device-number { display: inline-block; max-width: 100%; white-space: normal; overflow-wrap: anywhere; vertical-align: bottom; }
.access-section { padding: 16px 0; border-bottom: 1px solid var(--admin-border); }
.access-section:first-child { padding-top: 0; }
.access-section:last-child { border-bottom: 0; }
.adsb-receive-settings { grid-column: 1 / -1; margin-top: 4px; padding: 14px 16px; border: 1px solid var(--admin-border); border-radius: 8px; }
.adsb-receive-settings b { display: block; margin-bottom: 10px; }
.flight-distance-settings .adsb-receive-row { margin-top: 10px; }
.access-basic-grid .adsb-receive-row > .fd-input { width: 120px !important; }
.fd-mode { font-weight: 600; min-width: 36px; }
.fd-fields { margin: 0; padding: 0; border: 0; min-width: 0; }
.access-basic-grid .flight-distance-settings .adsb-unit { margin-right: 10px; }
.access-basic-grid .adsb-receive-row { display: flex; flex-wrap: nowrap; align-items: center; gap: 8px; }
.access-basic-grid .adsb-receive-row > * { flex: none; width: auto !important; margin: 0; }
.access-basic-grid .adsb-receive-row > .adsb-range-input { width: 140px !important; }
.access-basic-grid .adsb-receive-row > .adsb-guid-input { width: 220px !important; }
.adsb-station-hint { flex-wrap: wrap !important; }
.adsb-receive-row label { color: var(--admin-text, #33415a); }
.adsb-unit { margin-right: 12px; color: var(--admin-muted); }
.access-section-title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.access-step-number { display: grid; place-items: center; flex: 0 0 24px; width: 24px; height: 24px; border-radius: 6px; color: var(--admin-primary); background: var(--admin-primary-soft); font-weight: 700; font-size: 12px; }
.access-section-title h3 { flex: 0 0 auto; margin: 0; font-size: 15px; }
.access-mode-tag { flex: 0 0 auto; height: 24px; padding: 0 10px; font-weight: 500; white-space: nowrap; }
.access-section-title small { color: var(--admin-muted); font-size: 12px; }
.access-type-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; }
.access-type { position: relative; min-height: 40px; padding: 9px; color: var(--admin-muted); background: var(--admin-card); border: 1px solid var(--admin-border); border-radius: 7px; }
.access-type:hover:not(:disabled), .access-type.selected { color: var(--admin-primary); border-color: var(--admin-primary); background: var(--admin-primary-soft); }
.access-type.selected::after { position: absolute; top: 2px; right: 6px; content: '✓'; font-size: 10px; }
.access-type:disabled { cursor: default; opacity: .65; }
.access-type.selected:disabled { opacity: 1; }
.access-basic-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 18px; }
.access-form :deep(.el-form-item) { margin-bottom: 18px; min-width: 0; }
.access-form :deep(.el-select), .access-form :deep(.el-input-number) { width: 100%; }
.coordinate-section { grid-column: 1 / -1; margin-top: 2px; padding: 12px; border: 1px solid #e6edf6; border-radius: 7px; background: #fbfdff; }
.coordinate-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.coordinate-section-heading > div:first-child { display: flex; flex-direction: column; gap: 3px; }
.coordinate-section-heading span { color: var(--admin-muted); font-size: 12px; }
.coordinate-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.coordinate-fields :deep(.el-form-item) { margin-bottom: 12px; }
.access-protocol-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: center; }
.access-field-note { display: block; width: 100%; font-size: 12px; color: var(--admin-muted); line-height: 1.6; }
.demo-access-note { margin-top: 16px; }
.demo-access-note :deep(.el-alert__description) { line-height: 1.8; }
.access-connection-grid .wide { grid-column: 1 / -1; }
.access-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.access-footer > span { font-size: 12px; color: var(--admin-muted); text-align: left; }
.access-footer > div { margin-left: auto; white-space: nowrap; }
@media (max-width: 700px) {
  .access-type-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .access-basic-grid, .access-protocol-row { grid-template-columns: minmax(0, 1fr); }
  .coordinate-fields { grid-template-columns: minmax(0, 1fr); }
  .access-protocol-row > .muted { display: none; }
}
@media (max-width: 1100px) { .devices-workspace { grid-template-columns: minmax(0, 1fr); } }
</style>
