<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import OperationMetrics from './OperationMetrics.vue';
import './operations-reference.css';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { useRoute } from 'vue-router';
import { weatherSensorsApi } from '@/api/externalInterfaces.js';
import DeviceCatalogPreview from './DeviceCatalogPreview.vue';
import { deviceApi, integrationApi, mqttApi } from '@/api/devices.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
import { useAuthStore } from '@/stores/auth.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';

const MQTT_PROTOCOL = 'LINGYUN_MQTT_V8_6';
const EO_PROTOCOL = 'EO_EDGE_MQTT_20250826';
const RADAR_PROTOCOL = 'RADAR_TCP_V3_0_0';
const COUNTER_PROTOCOL = 'COUNTERMEASURE_TCP_4CH_V2_0';
const deviceTypes = [
  { code: 'radar', name: '雷达', protocols: [RADAR_PROTOCOL, MQTT_PROTOCOL] },
  { code: 'eo', name: '光电设备', protocols: [EO_PROTOCOL] },
  { code: 'counter', name: '四通道反制设备', protocols: [COUNTER_PROTOCOL] },
  { code: 'weather_sensor', name: '天气传感器', protocols: [] },
  ...[['5ga', '5G-A'], ['tdoa', 'TDOA'], ['aoa', 'AOA'], ['dcd', '协议破解'], ['rid', 'RemoteID'], ['dec', '诱骗'], ['ifr', '干扰'], ['bsc', '驱鸟炮']]
    .map(([code, name]) => ({ code, name, protocols: [MQTT_PROTOCOL] }))
];
const selectedType = ref('');
const auth = useAuthStore();
const canOperate = computed(() => auth.hasPermission('devices.op'));
const canReadBrokers = computed(() => auth.hasPermission('interfaces.read'));
const canEditBrokers = computed(() => auth.hasPermission('interfaces.op'));
const filters = reactive({ keyword: '', type_code: '', channel: '', region: '', vendor: '', connectivity: '', enabled: '', sort: 'priority' });
const options = ref({ types: [], channels: [], regions: [], vendors: [] });
const filterTypes = computed(() => {
  const items = options.value.type_options || [];
  return items.some(item => item.code === 'weather_sensor') ? items
    : [...items, { code: 'weather_sensor', name: '天气传感器' }];
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
  { label: '设备总数', value: overview.value.total, icon: 'total', note: '当前权限范围内设备' },
  { label: '在线设备', value: overview.value.online, tone: 'green', icon: 'online', note: `在线率 ${rate(overview.value.online)}` },
  { label: '离线设备', value: overview.value.offline, tone: 'amber', icon: 'offline', note: `离线率 ${rate(overview.value.offline)}` },
  { label: '异常设备', value: overview.value.abnormal, tone: 'red', icon: 'abnormal', note: `另有 ${overview.value.unknown || 0} 台状态未知` },
  { label: '告警中设备', value: overview.value.alarm, tone: 'amber', icon: 'alarm', note: '包含在设备状态统计内' },
  { label: '接入厂家数', value: overview.value.vendor_count, tone: 'purple', icon: 'vendor', note: `设备型号 ${overview.value.model_count ?? '—'} 种` }
]);

const deviceDialog = reactive({ visible: false, saving: false, opening: false, editing: false, row: null, current: null, optionsError: '' });
const deviceFormRef = ref();
const deviceForm = reactive({
  protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null, allowed_cidrs: '',
  recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false, device_address: 1,
  wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'live', scope_key: '',
  device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: '', address: '', version: null
});
const isWeather = computed(() => selectedType.value === 'weather_sensor');
const availableProtocols = computed(() => (deviceTypes.find(type => type.code === selectedType.value)?.protocols || [])
  .map(code => protocols.value.find(item => item.protocol_code === code)).filter(Boolean));
const selectedBroker = computed(() => brokers.value.find(item => item.broker_id === deviceForm.broker_id));
const brokers = ref([]);
const scopes = ref([]);
const availableChannels = computed(() => brokers.value.filter(item => item.enabled && item.source_mode === 'live'
  && `${item.owner_org_id}/${item.district_id}` === deviceForm.scope_key));
const isMqtt = computed(() => deviceForm.protocol_code === MQTT_PROTOCOL);
const isEo = computed(() => deviceForm.protocol_code === EO_PROTOCOL);
const isMqttTransport = computed(() => isMqtt.value || isEo.value);
const isRadar = computed(() => deviceForm.protocol_code === 'RADAR_TCP_V3_0_0');
const isCountermeasure = computed(() => deviceForm.protocol_code === 'COUNTERMEASURE_TCP_4CH_V2_0');

const brokerDialog = reactive({ visible: false, loading: false, items: [] });
const brokerEditor = reactive({ visible: false, saving: false, editing: null });
const brokerFormRef = ref();
const brokerForm = reactive({ name: '', host: '', port: 8883, tls: true, source_mode: 'live', scope_key: '', username: '', credential_ref: '', allowed_cidrs: '' });

function listParams() {
  return { ...filters, enabled: filters.enabled === '' ? '' : filters.enabled === 'true', page: table.page, size: table.size };
}

async function loadOverview() {
  const sequence = ++overviewSequence;
  try {
    const summary = await deviceApi.overview();
    if (alive && sequence === overviewSequence) overview.value = summary;
  } catch (e) { if (alive && sequence === overviewSequence && !error.value) error.value = e.message; }
}

async function loadDetail(id) {
  const sequence = ++detailSequence;
  detailError.value = '';
  if (!id) { detail.value = null; detailLoading.value = false; return; }
  if (detail.value?.device?.device_id !== id) detail.value = null;
  detailLoading.value = true;
  try {
    const record = await deviceApi.detail(id);
    if (!alive || sequence !== detailSequence) return;
    detail.value = record;
  } catch (e) { if (alive && sequence === detailSequence) { detail.value = null; detailError.value = e.message || '设备档案加载失败'; } }
  finally { if (sequence === detailSequence) detailLoading.value = false; }
}

async function loadList(keepSelection = true, refreshOverview = true) {
  const sequence = ++listSequence;
  loading.value = true; error.value = '';
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
    await loadDetail(selectedId.value);
  } catch (e) { if (alive && sequence === listSequence) error.value = e.message || '设备台账加载失败'; }
  finally { await summaryRequest; if (sequence === listSequence) loading.value = false; }
}

const route = useRoute();
if (route.query.type === 'weather_sensor') filters.type_code = 'weather_sensor';

async function bootstrap() {
  loading.value = true; error.value = '';
  const sequence = ++overviewSequence;
  try {
    const [filterOptions, summary, protocolList] = await Promise.all([deviceApi.options(), deviceApi.overview(), integrationApi.protocols()]);
    if (!alive) return;
    options.value = filterOptions; protocols.value = protocolList;
    if (sequence === overviewSequence) overview.value = summary;
    await loadList(false, false);
  } catch (e) { error.value = e.message || '设备管理数据加载失败'; loading.value = false; }
}

function search() { table.page = 1; void loadList(false); }
function reset() { Object.assign(filters, { keyword: '', type_code: '', channel: '', region: '', vendor: '', connectivity: '', enabled: '', sort: 'priority' }); search(); }
function selectRow(row) { selectedId.value = row.device_id; loadDetail(row.device_id); }

function resetDeviceForm() {
  Object.assign(deviceForm, { protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null,
    allowed_cidrs: '', recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false,
    device_address: 1, wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'live',
    scope_key: '', device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: '', address: '', version: null });
}

function clearProtocolFields() {
  Object.assign(deviceForm, { host: '', port: null, allowed_cidrs: '', recognition_code_ref: '', rtk_enabled: false,
    coordinate_transform_enabled: false, device_address: 1, wire_encoding: 'AUTO', poll_interval_millis: 5000,
    broker_id: '', source_mode: 'live', provider_code: '', external_device_id: '', edge_id: '' });
  nextTick(() => deviceFormRef.value?.clearValidate());
}
function selectDeviceType(type) {
  if (deviceDialog.editing || deviceDialog.saving || selectedType.value === type.code) return;
  selectedType.value = type.code; clearProtocolFields();
  deviceForm.device_type_abbr = type.code;
  deviceForm.protocol_code = availableProtocols.value[0]?.protocol_code || '';
}
function selectBroker() {
  if (!selectedBroker.value || deviceDialog.editing) return;
  deviceForm.source_mode = selectedBroker.value.source_mode;
}
function matchAccessChannel() {
  if (!isMqttTransport.value || deviceDialog.editing) return;
  if (!deviceForm.scope_key && scopes.value.length === 1) {
    deviceForm.scope_key = `${scopes.value[0].org_id}/${scopes.value[0].district_id}`;
  }
  if (!availableChannels.value.some(item => item.broker_id === deviceForm.broker_id)) {
    deviceForm.broker_id = availableChannels.value.length === 1 ? availableChannels.value[0].broker_id : '';
  }
  selectBroker();
}
async function refreshAccessOptions() {
  deviceDialog.optionsError = '';
  try {
    const [brokerOptions, scopeOptions] = await Promise.all([mqttApi.options(), mqttApi.scopes()]);
    brokers.value = brokerOptions; scopes.value = scopeOptions;
  } catch (e) { deviceDialog.optionsError = e.message || '连接及单位范围读取失败，请重试'; }
}

async function openDevice(row = null) {
  if (!canOperate.value || deviceDialog.opening) return;
  deviceDialog.opening = true; selectedType.value = '';
  deviceDialog.editing = Boolean(row); deviceDialog.row = row; deviceDialog.current = null; resetDeviceForm();
  try {
    const [, current] = await Promise.all([
      refreshAccessOptions(), row ? (row.device_type_code === 'weather_sensor' ? weatherSensorsApi.get(row.device_id) : deviceApi.detail(row.device_id)) : Promise.resolve(null)
    ]);
    deviceDialog.current = current;
    if (row?.device_type_code === 'weather_sensor') {
      selectedType.value = 'weather_sensor';
      Object.assign(deviceForm, current, { scope_key: `${current.owner_org_id}/${current.district_id}` });
    } else if (current) {
      const connection = current.connection || {}, protocol = current.protocol_configuration || {};
      Object.assign(deviceForm, {
        protocol_code: current.protocol_code || '', device_no: current.device?.device_no || '', name: current.device?.name || '',
        vendor: current.vendor || '', region_name: current.region_name || '', model: current.model || '', address: current.address || '',
        host: connection.host || '', port: connection.port ?? null, allowed_cidrs: current.allowed_cidrs || '',
        recognition_code_ref: protocol.recognition_code_ref || '', rtk_enabled: Boolean(protocol.rtk_enabled),
        coordinate_transform_enabled: Boolean(protocol.coordinate_transform_enabled), device_address: protocol.device_address || 1,
        wire_encoding: protocol.wire_encoding || 'AUTO', poll_interval_millis: protocol.poll_interval_millis || 5000
      });
      if ([MQTT_PROTOCOL, EO_PROTOCOL].includes(current.protocol_code)) {
        const status = await deviceApi.protocolStatus(row.device_id);
        const mqtt = status?.details || {};
        const broker = brokers.value.find(item => item.broker_id === mqtt.broker_id);
        Object.assign(deviceForm, mqtt, { model: current.model || '', scope_key: broker ? `${broker.owner_org_id}/${broker.district_id}` : '' });
      }
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

function mqttPayload(values, version) {
  const broker = brokers.value.find(item => item.broker_id === values.broker_id);
  const [owner_org_id, district_id] = values.scope_key.split('/');
  if (!broker || broker.source_mode !== values.source_mode || broker.owner_org_id !== owner_org_id || broker.district_id !== district_id) throw new Error('接入通道与设备的数据来源、所属单位及区域不一致，请重新选择。');
  if (!deviceDialog.editing && !availableChannels.value.some(item => item.broker_id === broker.broker_id)) throw new Error('该设备暂无可用接入通道，请联系管理员配置。');
  const common = { protocol_code: values.protocol_code, broker_id: broker.broker_id, external_device_id: values.external_device_id.trim(),
    source_mode: values.source_mode, owner_org_id, district_id, device_no: values.device_no.trim(), name: values.name.trim(),
    vendor: values.vendor || null, model: values.model || null, version };
  return values.protocol_code === EO_PROTOCOL ? { ...common, edge_id: values.edge_id.trim() }
    : { ...common, provider_code: values.provider_code.trim(), device_type_abbr: values.device_type_abbr };
}

async function saveDevice() {
  if (!canOperate.value || deviceDialog.saving || !selectedType.value) return;
  if (!isWeather.value && !availableProtocols.value.some(item => item.protocol_code === deviceForm.protocol_code)) return;
  if ((isWeather.value || isMqttTransport.value) && deviceDialog.optionsError) return;
  if (!await deviceFormRef.value.validate().catch(() => false) || deviceDialog.saving) return;
  deviceDialog.saving = true;
  const current = deviceDialog.current;
  const key = newIdempotencyKey('device-form');
  try {
    let saved;
    if (isWeather.value) {
      const [owner_org_id, district_id] = deviceForm.scope_key.split('/');
      const payload = { device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), vendor: deviceForm.vendor?.trim() || null,
        model: deviceForm.model?.trim() || null, address: deviceForm.address?.trim() || null, owner_org_id, district_id, version: deviceForm.version };
      const sensor = deviceDialog.editing ? await weatherSensorsApi.update(deviceDialog.row.device_id, payload) : await weatherSensorsApi.create(payload);
      saved = { device: sensor };
    } else if (isMqttTransport.value) {
      const payload = mqttPayload(deviceForm, current?.device?.version);
      saved = deviceDialog.editing ? await deviceApi.update(deviceDialog.row.device_id, payload, key) : await deviceApi.onboard(payload, key);
    } else if (deviceDialog.editing) {
      saved = await deviceApi.update(deviceDialog.row.device_id, {
        version: current.device.version, source_id: current.source_id, external_device_id: current.external_device_id,
        device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), device_type_code: current.device.device_type_code,
        device_type_name: current.device.device_type_name, channel: current.device.channel, vendor: deviceForm.vendor || null,
        model: deviceForm.model || null, owner_name: current.owner_name || null, region_name: deviceForm.region_name || null,
        address: deviceForm.address || null, allowed_cidrs: deviceForm.allowed_cidrs.trim(), connection: tcpConnection(deviceForm, current),
        protocol_configuration: { login_role: 'DATA', recognition_code_ref: deviceForm.recognition_code_ref || null,
          rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
          device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }
      }, key);
    } else {
      saved = await deviceApi.onboard({ protocol_code: deviceForm.protocol_code, device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(),
        host: deviceForm.host.trim(), port: deviceForm.port, allowed_cidrs: deviceForm.allowed_cidrs.trim(), vendor: deviceForm.vendor || null,
        region_name: deviceForm.region_name || null, model: deviceForm.model || null, address: deviceForm.address || null, recognition_code_ref: deviceForm.recognition_code_ref || null,
        rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
        device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }, key);
    }
    selectedId.value = saved.device.device_id; deviceDialog.visible = false;
    ElMessage.success(isWeather.value ? '天气传感器档案已保存，待接入' : deviceDialog.editing ? '设备已更新' : '接入配置已保存，请确认设备连接及上报状态');
    await loadList(true);
  } catch (e) { ElMessage.error(e.message); if (e.code === 'VERSION_CONFLICT') await loadList(); }
  finally { deviceDialog.saving = false; }
}

async function toggleDevice(row) {
  if (!canOperate.value) return;
  try {
    const { value } = await ElMessageBox.prompt(`${row.enabled ? '停用后会断开协议连接，并拒绝新建调测任务。' : '启用后会按配置重新建立协议连接。'}请输入操作原因：`, `${row.enabled ? '停用' : '启用'}设备 · ${row.device_no}`, { inputType: 'textarea', inputValidator: value => value?.trim().length >= 2 || '原因至少填写 2 个字符', confirmButtonText: '确认', cancelButtonText: '取消' });
    await deviceApi.setEnabled(row.device_id, { enabled: !row.enabled, version: row.version, reason: value.trim() });
    ElMessage.success(`设备已${row.enabled ? '停用' : '启用'}，审计记录已写入`); await loadList();
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e)); }
}

async function deleteDevice(row) {
  if (!canOperate.value || deletingId.value) return;
  if (row.enabled) { ElMessage.warning('请先停用设备，再执行删除'); return; }
  deletingId.value = row.device_id;
  try {
    const { value } = await ElMessageBox.prompt(`确认删除设备“${row.name}”（${row.device_no}）？删除后将从台账和监控列表移除，保留历史记录及设备编号，不能重新启用。请输入删除原因：`, '删除设备', {
      type: 'warning', inputType: 'textarea', confirmButtonText: '确认删除', cancelButtonText: '取消',
      inputValidator: value => (value?.trim().length >= 2 && value.trim().length <= 500) || '删除原因需填写 2–500 个字符'
    });
    await deviceApi.remove(row.device_id, { version: row.version, reason: value.trim() });
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

async function openBrokers() {
  if (!canReadBrokers.value) return;
  brokerDialog.visible = true; brokerDialog.loading = true;
  try {
    const [items, scopeRows] = await Promise.all([mqttApi.list(), scopes.value.length ? Promise.resolve(scopes.value) : mqttApi.scopes().catch(() => [])]);
    brokerDialog.items = items; scopes.value = scopeRows;
  }
  catch (e) { ElMessage.error(e.message); }
  finally { brokerDialog.loading = false; }
}
function openBrokerEditor(row = null) {
  if (row && row.source_mode !== 'live') return;
  brokerEditor.editing = row; Object.assign(brokerForm, row ? { ...row, scope_key: `${row.owner_org_id}/${row.district_id}` }
    : { name: '', host: '', port: 8883, tls: true, source_mode: 'live', scope_key: '', username: '', credential_ref: '', allowed_cidrs: '' });
  brokerEditor.visible = true;
}
async function saveBroker() {
  if (brokerForm.source_mode !== 'live') return;
  await brokerFormRef.value.validate(); brokerEditor.saving = true;
  try {
    const [owner_org_id, district_id] = brokerForm.scope_key.split('/');
    const body = { name: brokerForm.name.trim(), host: brokerForm.host.trim(), port: brokerForm.port, tls: brokerForm.tls,
      username: brokerForm.username?.trim() || null, credential_ref: brokerForm.credential_ref?.trim() || null,
      allowed_cidrs: brokerForm.allowed_cidrs.trim(), source_mode: brokerForm.source_mode, owner_org_id, district_id, version: brokerEditor.editing?.version };
    if (brokerEditor.editing) await mqttApi.update(brokerEditor.editing.broker_id, body); else await mqttApi.create(body);
    brokerEditor.visible = false; ElMessage.success('MQTT 配置已保存，当前处于停用状态'); await openBrokers();
    if (deviceDialog.visible) await refreshAccessOptions();
  } catch (e) { ElMessage.error(e.message); }
  finally { brokerEditor.saving = false; }
}
async function toggleBroker(row) {
  if (!row.enabled && row.source_mode !== 'live') return;
  try { await ElMessageBox.confirm(row.enabled ? '停用将停止该连接下所有设备的报文接收。' : '启用后后端会连接服务器并订阅已登记设备。', `${row.enabled ? '停用' : '启用'} MQTT 连接`, { type: 'warning' });
    await mqttApi.setEnabled(row.broker_id, { enabled: !row.enabled, version: row.version }); await openBrokers();
    if (deviceDialog.visible) await refreshAccessOptions(); }
  catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e)); }
}

watch(() => [selectedType.value, deviceForm.protocol_code, deviceForm.scope_key, brokers.value, scopes.value], matchAccessChannel);
watch(() => [filters.type_code, filters.channel, filters.region, filters.vendor, filters.connectivity, filters.enabled, filters.sort], search);
onMounted(bootstrap);
onBeforeUnmount(() => { alive = false; detailSequence++; });
</script>

<template>
  <section class="page-stack operation-page devices-reference">
    <PageHeader title="设备管理" description="设备台账、接入配置与运行状态统一管理。">
      <el-button v-if="canReadBrokers" @click="openBrokers">接入配置</el-button>
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
          <el-table-column prop="device_no" label="设备编号" min-width="135" fixed />
          <el-table-column prop="name" label="设备名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型 / 通道" min-width="125"><template #default="{row}">{{ display(row.device_type_name) }}<span class="cell-secondary">{{ display(row.channel) }}</span></template></el-table-column>
          <el-table-column label="产权单位 / 位置" min-width="170"><template #default="{row}">{{ display(row.owner_name) }}<span class="cell-secondary">{{ row.address || row.region_name || '未登记位置' }}</span></template></el-table-column>
          <el-table-column label="型号 / 供应商" min-width="145"><template #default="{row}">{{ display(row.model) }}<span class="cell-secondary">{{ display(row.vendor) }}</span></template></el-table-column>
          <el-table-column label="状态 / 健康" width="105"><template #default="{row}"><el-tag :type="statusType(row.connectivity)" effect="plain">{{ statusText(row.connectivity) }}</el-tag><span class="cell-secondary">{{ healthText(row.health_code) }}</span></template></el-table-column>
          <el-table-column prop="last_heartbeat_at" label="最后心跳" min-width="165"><template #default="{row}"><span class="mono">{{ formatTime(row.last_heartbeat_at) }}</span></template></el-table-column>
          <el-table-column label="状态" width="76"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'" effect="plain">{{ row.enabled?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="175" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!canOperate || Boolean(deletingId)" @click.stop="openDevice(row)">编辑</el-button><el-button link :type="row.enabled?'danger':'success'" :disabled="!canOperate || Boolean(deletingId) || (row.device_type_code==='weather_sensor' && !row.enabled)" @click.stop="toggleDevice(row)">{{ row.enabled?'停用':'启用' }}</el-button><el-button link type="danger" :disabled="!canOperate || Boolean(deletingId)" :loading="deletingId === row.device_id" @click.stop="deleteDevice(row)">删除</el-button></template></el-table-column>
        </el-table></div>
        <div class="pagination-row"><span>共 {{ table.total }} 台</span><el-pagination v-model:current-page="table.page" v-model:page-size="table.size" :page-sizes="[10,20,50,100]" layout="sizes, prev, pager, next" :total="table.total" @current-change="loadList(false)" @size-change="table.page=1;loadList(false)" /></div>
      </el-card>

      <DeviceCatalogPreview :detail="detail" :loading="detailLoading" :error="detailError" @refresh="loadDetail(selectedId)" />
    </div>

    <el-dialog v-model="deviceDialog.visible" :title="deviceDialog.editing ? `编辑设备 · ${deviceForm.device_no}` : '接入设备'" width="min(990px, 94vw)" top="5vh" class="device-access-dialog" destroy-on-close :close-on-click-modal="!deviceDialog.saving" :close-on-press-escape="!deviceDialog.saving" :show-close="!deviceDialog.saving">
      <el-form ref="deviceFormRef" :model="deviceForm" label-position="top" :disabled="deviceDialog.saving" class="access-form">
        <section class="access-section">
          <div class="access-section-title"><span>1</span><h3>选择设备类型</h3><small>{{ deviceDialog.editing ? '接入身份创建后不可修改' : '不同类型展示对应配置' }}</small></div>
          <div class="access-type-grid" role="group" aria-label="设备类型">
            <button v-for="item in deviceTypes" :key="item.code" type="button" class="access-type" :class="{ selected: selectedType === item.code }" :aria-pressed="selectedType === item.code" :disabled="deviceDialog.editing || deviceDialog.saving" @click="selectDeviceType(item)">{{ item.name }}</button>
          </div>
        </section>
        <template v-if="selectedType">
          <section class="access-section">
            <div class="access-section-title"><span>2</span><h3>设备基本资料</h3><small>* 为必填项</small></div>
            <div class="access-basic-grid">
              <el-form-item label="设备编号" prop="device_no" :rules="[{required:true,whitespace:true,message:'请输入设备编号'}]"><el-input v-model="deviceForm.device_no" :disabled="deviceDialog.editing" maxlength="64" placeholder="请输入唯一设备编号" /></el-form-item>
              <el-form-item label="设备名称" prop="name" :rules="[{required:true,whitespace:true,message:'请输入设备名称'}]"><el-input v-model="deviceForm.name" maxlength="128" placeholder="请输入设备名称" /></el-form-item>
              <el-form-item label="供应商"><el-input v-model="deviceForm.vendor" maxlength="128" placeholder="请输入供应商" /></el-form-item>
              <el-form-item label="型号"><el-input v-model="deviceForm.model" maxlength="128" placeholder="请输入设备型号" /></el-form-item>
              <el-form-item v-if="isWeather || isMqttTransport" label="所属单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择所属单位及区域'}]"><el-select v-model="deviceForm.scope_key" :disabled="deviceDialog.editing" placeholder="请选择单位及区域"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
              <el-form-item v-else label="所属区域"><el-input v-model="deviceForm.region_name" placeholder="请输入所属区域" /></el-form-item>
              <el-form-item v-if="!isMqttTransport" label="安装位置"><el-input v-model="deviceForm.address" maxlength="256" placeholder="例如：园区东门楼顶" /></el-form-item>
            </div>
            <el-alert v-if="deviceDialog.optionsError && (isWeather || isMqttTransport)" :title="deviceDialog.optionsError" type="error" :closable="false"><el-button link @click="refreshAccessOptions">重新加载连接及范围</el-button></el-alert>
          </section>
          <section class="access-section">
            <div class="access-section-title"><span>3</span><h3>{{ isWeather ? '接入状态' : '接入配置' }}</h3><el-tag v-if="isWeather" type="warning" effect="plain">协议待确认</el-tag><el-tag v-else-if="deviceForm.protocol_code" effect="plain">{{ isMqttTransport ? '平台通道' : 'TCP 直连' }}</el-tag></div>
            <el-alert v-if="isWeather" title="厂家协议待确认，当前仅登记设备档案" description="天气传感器统一纳入设备台账。协议确认前无需填写设备地址、端口或 MQTT 连接，登记后保持停用与待接入。" type="warning" :closable="false" show-icon />
            <template v-else>
              <div class="access-protocol-row"><el-form-item label="接入协议" prop="protocol_code" :rules="[{required:true,message:'请选择接入协议'}]"><el-select v-model="deviceForm.protocol_code" :disabled="deviceDialog.editing || availableProtocols.length === 1" @change="clearProtocolFields"><el-option v-for="item in availableProtocols" :key="item.protocol_code" :label="`${item.name} · v${item.version}`" :value="item.protocol_code" /></el-select></el-form-item><span class="muted">仅展示当前设备类型已支持的协议</span></div>
              <el-alert v-if="!availableProtocols.length" title="当前没有可用的接入协议，请刷新后重试" type="warning" :closable="false" />
              <div v-else class="form-grid access-connection-grid">
                <template v-if="isMqttTransport">
                  <el-form-item label="接入通道" prop="broker_id" :rules="[{required:true,message:'请选择可用接入通道'}]">
                    <template v-if="deviceDialog.editing"><span>{{ selectedBroker?.name || '原接入通道' }}</span><span class="access-field-note">接入通道创建后不可修改{{ selectedBroker && !selectedBroker.enabled ? '，当前已停用' : '' }}{{ deviceForm.source_mode === 'replay' ? '（历史模拟通道）' : '' }}</span></template>
                    <el-select v-else-if="availableChannels.length > 1" v-model="deviceForm.broker_id" placeholder="请选择接入通道" @change="selectBroker"><el-option v-for="item in availableChannels" :key="item.broker_id" :label="item.name" :value="item.broker_id" /></el-select>
                    <template v-else-if="availableChannels.length === 1"><span>{{ selectedBroker?.name }}</span><span class="access-field-note">已自动匹配所属单位及区域的接入通道</span></template>
                    <el-alert v-else-if="deviceForm.scope_key" title="该设备暂无可用接入通道，请联系管理员配置" type="warning" :closable="false" />
                    <span v-else class="access-field-note">请先选择所属单位及区域</span>
                  </el-form-item>
                  <el-form-item label="外部设备编号" prop="external_device_id" :rules="[{required:true,whitespace:true,message:'请输入外部设备编号'}]"><el-input v-model="deviceForm.external_device_id" :disabled="deviceDialog.editing" placeholder="填写厂家设备 ID" /></el-form-item>
                  <el-form-item v-if="isMqtt" label="提供方编码" prop="provider_code" :rules="[{required:true,whitespace:true,message:'请输入提供方编码'}]"><el-input v-model="deviceForm.provider_code" :disabled="deviceDialog.editing" placeholder="填写厂家提供的编码" /></el-form-item>
                  <el-form-item v-if="isEo" label="边缘中心 ID" prop="edge_id" :rules="[{required:true,whitespace:true,message:'请输入边缘中心 ID'}]"><el-input v-model="deviceForm.edge_id" :disabled="deviceDialog.editing" placeholder="填写协议中的 edgeId" /></el-form-item>
                </template>
                <template v-else>
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
      <template #footer><div class="access-footer"><span>{{ isWeather ? '仅保存设备档案，保持停用与待接入。' : '配置保存后，以实际连接与有效报文确认状态。' }}</span><div><el-button :disabled="deviceDialog.saving" @click="deviceDialog.visible=false">取消</el-button><el-button type="primary" :loading="deviceDialog.saving" :disabled="!selectedType || (!isWeather && !deviceForm.protocol_code) || Boolean(deviceDialog.optionsError && (isWeather || isMqttTransport)) || (isMqttTransport && !deviceDialog.editing && !availableChannels.length)" @click="saveDevice">{{ isWeather ? '保存档案' : deviceDialog.editing ? '保存' : '保存接入配置' }}</el-button></div></div></template>
    </el-dialog>

    <el-dialog v-model="brokerDialog.visible" title="MQTT 连接管理" width="min(820px, 94vw)" append-to-body>
      <p class="form-note">连接成功只表示报文通道可用；设备收到有效工参后才显示在线。修改配置前请先停用。</p>
      <div class="table-toolbar"><span>共 {{ brokerDialog.items.length }} 条连接</span><el-button type="primary" :disabled="!canEditBrokers" @click="openBrokerEditor()">新增连接</el-button></div>
      <div v-loading="brokerDialog.loading"><el-empty v-if="!brokerDialog.items.length" description="尚未配置 MQTT 连接" />
        <div v-for="item in brokerDialog.items" :key="item.broker_id" class="connection-card"><b>{{ item.name }}</b><span>{{ item.source_mode==='replay'?'模拟回放':'真实来源' }} · {{ item.host }}:{{ item.port }}</span><el-tag :type="item.enabled?'success':'info'" effect="plain">{{ item.enabled?'启用':'停用' }} · {{ item.connection_state }}</el-tag><span class="inline-actions"><el-button link :disabled="!canEditBrokers||item.enabled||item.source_mode !== 'live'" @click="openBrokerEditor(item)">编辑</el-button><el-button link :disabled="!canEditBrokers || (!item.enabled && item.source_mode !== 'live')" @click="toggleBroker(item)">{{ item.enabled?'停用':'启用' }}</el-button></span></div>
      </div>
    </el-dialog>

    <el-dialog v-model="brokerEditor.visible" :title="brokerEditor.editing?'编辑 MQTT 连接':'新增 MQTT 连接'" width="min(720px, 94vw)" append-to-body>
      <p class="form-note">凭据填写 `env:环境变量名`，禁止填写密码本身；新增连接默认停用。</p>
      <el-form ref="brokerFormRef" :model="brokerForm" label-position="top" class="form-grid">
        <el-form-item label="连接名称" prop="name" :rules="[{required:true,message:'请输入名称'}]"><el-input v-model="brokerForm.name" /></el-form-item>
        <el-form-item label="服务器地址" prop="host" :rules="[{required:true,message:'请输入地址'}]"><el-input v-model="brokerForm.host" /></el-form-item>
        <el-form-item label="端口" prop="port" :rules="[{required:true,message:'请输入端口'}]"><el-input-number v-model="brokerForm.port" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="TLS"><el-switch v-model="brokerForm.tls" active-text="启用" inactive-text="关闭" /></el-form-item>
        <el-form-item label="数据来源" prop="source_mode" :rules="[{required:true,message:'请选择来源'}]"><el-select v-model="brokerForm.source_mode" :disabled="Boolean(brokerEditor.editing)"><el-option v-if="brokerEditor.editing?.source_mode === 'replay'" label="历史模拟回放（只读）" value="replay" /><el-option label="真实来源，待联调" value="live" /></el-select></el-form-item>
        <el-form-item label="单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择范围'}]"><el-select v-model="brokerForm.scope_key" :disabled="Boolean(brokerEditor.editing)"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
        <el-form-item label="用户名"><el-input v-model="brokerForm.username" /></el-form-item>
        <el-form-item label="密码凭据引用"><el-input v-model="brokerForm.credential_ref" placeholder="env:MQTT_PASSWORD" /></el-form-item>
        <el-form-item class="wide" label="允许的服务器网段 CIDR" prop="allowed_cidrs" :rules="[{required:true,message:'请输入网段'}]"><el-input v-model="brokerForm.allowed_cidrs" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="brokerEditor.visible=false">取消</el-button><el-button type="primary" :loading="brokerEditor.saving" @click="saveBroker">保存</el-button></template>
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
:global(.device-access-dialog) { display: flex; flex-direction: column; max-height: 90vh; }
:global(.device-access-dialog .el-dialog__body) { overflow-y: auto; min-height: 0; }
:global(.device-access-dialog .el-dialog__header), :global(.device-access-dialog .el-dialog__footer) { flex-shrink: 0; }
.access-section { padding: 16px 0; border-bottom: 1px solid var(--admin-border); }
.access-section:first-child { padding-top: 0; }
.access-section:last-child { border-bottom: 0; }
.access-section-title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.access-section-title > span { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 6px; color: var(--admin-primary); background: var(--admin-primary-soft); font-weight: 700; font-size: 12px; }
.access-section-title h3 { margin: 0; font-size: 15px; }
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
.access-protocol-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: center; }
.access-field-note { display: block; width: 100%; font-size: 12px; color: var(--admin-muted); line-height: 1.6; }
.access-connection-grid .wide { grid-column: 1 / -1; }
.access-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.access-footer > span { font-size: 12px; color: var(--admin-muted); text-align: left; }
.access-footer > div { margin-left: auto; white-space: nowrap; }
@media (max-width: 700px) {
  .access-type-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .access-basic-grid, .access-protocol-row { grid-template-columns: minmax(0, 1fr); }
  .access-protocol-row > .muted { display: none; }
}
@media (max-width: 1100px) { .devices-workspace { grid-template-columns: minmax(0, 1fr); } }
</style>
