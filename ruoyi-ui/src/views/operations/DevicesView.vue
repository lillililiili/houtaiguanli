<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import MetricCards from '@/components/MetricCards.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { deviceApi, integrationApi, mqttApi } from '@/api/devices.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
import { useAuthStore } from '@/stores/auth.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';

const MQTT_PROTOCOL = 'LINGYUN_MQTT_V8_6';
const EO_PROTOCOL = 'EO_EDGE_MQTT_20250826';
const auth = useAuthStore();
const canOperate = computed(() => auth.hasPermission('devices.op'));
const canReadBrokers = computed(() => auth.hasPermission('interfaces.read'));
const canEditBrokers = computed(() => auth.hasPermission('interfaces.op'));
const filters = reactive({ keyword: '', type_code: '', channel: '', connectivity: '', enabled: '', sort: 'priority' });
const options = ref({ types: [], channels: [], regions: [], vendors: [] });
const protocols = ref([]);
const overview = ref({ total: 0, online: 0, offline: 0, abnormal: 0, unknown: 0, alarm: 0, vendor_count: 0 });
const table = reactive({ items: [], page: 1, size: 10, total: 0 });
const selectedId = ref('');
const detail = ref(null);
const protocolStatus = ref(null);
const loading = ref(false);
const detailLoading = ref(false);
const error = ref('');
let refreshTimer;
let alive = true;
let detailSequence = 0;

const metrics = computed(() => [
  { label: '设备总数', value: overview.value.total, tone: 'blue' },
  { label: '在线设备', value: overview.value.online, tone: 'green' },
  { label: '离线设备', value: overview.value.offline, tone: 'amber' },
  { label: '异常 / 未知', value: `${overview.value.abnormal || 0} / ${overview.value.unknown || 0}`, tone: 'red' },
  { label: '告警中设备', value: overview.value.alarm, tone: 'purple' }
]);

const deviceDialog = reactive({ visible: false, saving: false, editing: false, row: null, current: null });
const deviceFormRef = ref();
const deviceForm = reactive({
  protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null, allowed_cidrs: '',
  recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false, device_address: 1,
  wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'replay', scope_key: '',
  device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: ''
});
const brokers = ref([]);
const scopes = ref([]);
const isMqtt = computed(() => deviceForm.protocol_code === MQTT_PROTOCOL);
const isEo = computed(() => deviceForm.protocol_code === EO_PROTOCOL);
const isMqttTransport = computed(() => isMqtt.value || isEo.value);
const isRadar = computed(() => deviceForm.protocol_code === 'RADAR_TCP_V3_0_0');
const isCountermeasure = computed(() => deviceForm.protocol_code === 'COUNTERMEASURE_TCP_4CH_V2_0');

const brokerDialog = reactive({ visible: false, loading: false, items: [] });
const brokerEditor = reactive({ visible: false, saving: false, editing: null });
const brokerFormRef = ref();
const brokerForm = reactive({ name: '', host: '', port: 8883, tls: true, source_mode: 'replay', scope_key: '', username: '', credential_ref: '', allowed_cidrs: '' });

function listParams() {
  return { ...filters, enabled: filters.enabled === '' ? '' : filters.enabled === 'true', page: table.page, size: table.size };
}

async function loadOverview() {
  try { overview.value = await deviceApi.overview(); }
  catch (e) { if (!error.value) error.value = e.message; }
}

async function loadDetail(id) {
  if (!id) { detail.value = null; protocolStatus.value = null; return; }
  const sequence = ++detailSequence;
  detailLoading.value = true;
  try {
    const [record, status] = await Promise.all([deviceApi.detail(id), deviceApi.protocolStatus(id)]);
    if (!alive || sequence !== detailSequence) return;
    detail.value = record; protocolStatus.value = status;
  } catch (e) { if (alive) ElMessage.error(e.message); }
  finally { if (sequence === detailSequence) detailLoading.value = false; }
}

async function loadList(keepSelection = true) {
  loading.value = true; error.value = '';
  try {
    const data = await deviceApi.list(listParams());
    if (!alive) return;
    Object.assign(table, data);
    if (!keepSelection || !data.items.some(item => item.device_id === selectedId.value)) selectedId.value = data.items[0]?.device_id || '';
    await loadDetail(selectedId.value);
  } catch (e) { error.value = e.message || '设备台账加载失败'; }
  finally { loading.value = false; }
}

async function bootstrap() {
  loading.value = true; error.value = '';
  try {
    const [filterOptions, summary, protocolList] = await Promise.all([deviceApi.options(), deviceApi.overview(), integrationApi.protocols()]);
    if (!alive) return;
    options.value = filterOptions; overview.value = summary; protocols.value = protocolList;
    await loadList(false);
  } catch (e) { error.value = e.message || '设备管理数据加载失败'; loading.value = false; }
}

function search() { table.page = 1; loadList(false); }
function reset() { Object.assign(filters, { keyword: '', type_code: '', channel: '', connectivity: '', enabled: '', sort: 'priority' }); search(); }
function selectRow(row) { selectedId.value = row.device_id; loadDetail(row.device_id); }

function resetDeviceForm() {
  Object.assign(deviceForm, { protocol_code: '', device_no: '', name: '', vendor: '', region_name: '', host: '', port: null,
    allowed_cidrs: '', recognition_code_ref: '', rtk_enabled: false, coordinate_transform_enabled: false,
    device_address: 1, wire_encoding: 'AUTO', poll_interval_millis: 5000, broker_id: '', source_mode: 'replay',
    scope_key: '', device_type_abbr: 'radar', provider_code: '', external_device_id: '', edge_id: '', model: '' });
}

async function openDevice(row = null) {
  if (!canOperate.value) return;
  deviceDialog.editing = Boolean(row); deviceDialog.row = row; deviceDialog.current = null; resetDeviceForm();
  try {
    const [brokerOptions, scopeOptions, current] = await Promise.all([
      mqttApi.options().catch(() => []), mqttApi.scopes().catch(() => []), row ? deviceApi.detail(row.device_id) : Promise.resolve(null)
    ]);
    brokers.value = brokerOptions; scopes.value = scopeOptions; deviceDialog.current = current;
    if (current) {
      const connection = current.connection || {}, protocol = current.protocol_configuration || {};
      Object.assign(deviceForm, {
        protocol_code: current.protocol_code || '', device_no: current.device?.device_no || '', name: current.device?.name || '',
        vendor: current.vendor || '', region_name: current.region_name || '', model: current.model || '',
        host: connection.host || '', port: connection.port ?? null, allowed_cidrs: current.allowed_cidrs || '',
        recognition_code_ref: protocol.recognition_code_ref || '', rtk_enabled: Boolean(protocol.rtk_enabled),
        coordinate_transform_enabled: Boolean(protocol.coordinate_transform_enabled), device_address: protocol.device_address || 1,
        wire_encoding: protocol.wire_encoding || 'AUTO', poll_interval_millis: protocol.poll_interval_millis || 5000
      });
      if ([MQTT_PROTOCOL, EO_PROTOCOL].includes(current.protocol_code)) {
        const status = await deviceApi.protocolStatus(row.device_id);
        const mqtt = status?.details || {};
        const broker = brokerOptions.find(item => item.broker_id === mqtt.broker_id);
        Object.assign(deviceForm, mqtt, { model: current.model || '', scope_key: broker ? `${broker.owner_org_id}/${broker.district_id}` : '' });
      }
    }
    deviceDialog.visible = true;
  } catch (e) { ElMessage.error(e.message || '接入配置加载失败'); }
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
  if (!broker || broker.source_mode !== values.source_mode || broker.owner_org_id !== owner_org_id || broker.district_id !== district_id) throw new Error('设备的数据来源、单位与区域须与所选 MQTT 连接一致。');
  const common = { protocol_code: values.protocol_code, broker_id: broker.broker_id, external_device_id: values.external_device_id.trim(),
    source_mode: values.source_mode, owner_org_id, district_id, device_no: values.device_no.trim(), name: values.name.trim(),
    vendor: values.vendor || null, model: values.model || null, version };
  return values.protocol_code === EO_PROTOCOL ? { ...common, edge_id: values.edge_id.trim() }
    : { ...common, provider_code: values.provider_code.trim(), device_type_abbr: values.device_type_abbr };
}

async function saveDevice() {
  await deviceFormRef.value.validate();
  deviceDialog.saving = true;
  const current = deviceDialog.current;
  const key = newIdempotencyKey('device-form');
  try {
    let saved;
    if (isMqttTransport.value) {
      const payload = mqttPayload(deviceForm, current?.device?.version);
      saved = deviceDialog.editing ? await deviceApi.update(deviceDialog.row.device_id, payload, key) : await deviceApi.onboard(payload, key);
    } else if (deviceDialog.editing) {
      saved = await deviceApi.update(deviceDialog.row.device_id, {
        version: current.device.version, source_id: current.source_id, external_device_id: current.external_device_id,
        device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(), device_type_code: current.device.device_type_code,
        device_type_name: current.device.device_type_name, channel: current.device.channel, vendor: deviceForm.vendor || null,
        model: current.model || null, owner_name: current.owner_name || null, region_name: deviceForm.region_name || null,
        address: current.address || null, allowed_cidrs: deviceForm.allowed_cidrs.trim(), connection: tcpConnection(deviceForm, current),
        protocol_configuration: { login_role: 'DATA', recognition_code_ref: deviceForm.recognition_code_ref || null,
          rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
          device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }
      }, key);
    } else {
      saved = await deviceApi.onboard({ protocol_code: deviceForm.protocol_code, device_no: deviceForm.device_no.trim(), name: deviceForm.name.trim(),
        host: deviceForm.host.trim(), port: deviceForm.port, allowed_cidrs: deviceForm.allowed_cidrs.trim(), vendor: deviceForm.vendor || null,
        region_name: deviceForm.region_name || null, recognition_code_ref: deviceForm.recognition_code_ref || null,
        rtk_enabled: deviceForm.rtk_enabled, coordinate_transform_enabled: deviceForm.coordinate_transform_enabled,
        device_address: deviceForm.device_address || 1, wire_encoding: deviceForm.wire_encoding || 'AUTO', poll_interval_millis: deviceForm.poll_interval_millis || 5000 }, key);
    }
    selectedId.value = saved.device.device_id; deviceDialog.visible = false; ElMessage.success(deviceDialog.editing ? '设备已更新' : '设备已接入');
    await Promise.all([loadList(true), loadOverview()]);
  } catch (e) { ElMessage.error(e.message); if (e.code === 'VERSION_CONFLICT') await loadList(); }
  finally { deviceDialog.saving = false; }
}

async function toggleDevice(row) {
  if (!canOperate.value) return;
  try {
    const { value } = await ElMessageBox.prompt(`${row.enabled ? '停用后会断开协议连接，并拒绝新建调测任务。' : '启用后会按配置重新建立协议连接。'}请输入操作原因：`, `${row.enabled ? '停用' : '启用'}设备 · ${row.device_no}`, { inputType: 'textarea', inputValidator: value => value?.trim().length >= 2 || '原因至少填写 2 个字符', confirmButtonText: '确认', cancelButtonText: '取消' });
    await deviceApi.setEnabled(row.device_id, { enabled: !row.enabled, version: row.version, reason: value.trim() });
    ElMessage.success(`设备已${row.enabled ? '停用' : '启用'}，审计记录已写入`); await Promise.all([loadList(), loadOverview()]);
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e)); }
}

async function openBrokers() {
  brokerDialog.visible = true; brokerDialog.loading = true;
  try {
    const [items, scopeRows] = await Promise.all([mqttApi.list(), scopes.value.length ? Promise.resolve(scopes.value) : mqttApi.scopes().catch(() => [])]);
    brokerDialog.items = items; scopes.value = scopeRows;
  }
  catch (e) { ElMessage.error(e.message); }
  finally { brokerDialog.loading = false; }
}
function openBrokerEditor(row = null) {
  brokerEditor.editing = row; Object.assign(brokerForm, row ? { ...row, scope_key: `${row.owner_org_id}/${row.district_id}` }
    : { name: '', host: '', port: 8883, tls: true, source_mode: 'replay', scope_key: '', username: '', credential_ref: '', allowed_cidrs: '' });
  brokerEditor.visible = true;
}
async function saveBroker() {
  await brokerFormRef.value.validate(); brokerEditor.saving = true;
  try {
    const [owner_org_id, district_id] = brokerForm.scope_key.split('/');
    const body = { name: brokerForm.name.trim(), host: brokerForm.host.trim(), port: brokerForm.port, tls: brokerForm.tls,
      username: brokerForm.username?.trim() || null, credential_ref: brokerForm.credential_ref?.trim() || null,
      allowed_cidrs: brokerForm.allowed_cidrs.trim(), source_mode: brokerForm.source_mode, owner_org_id, district_id, version: brokerEditor.editing?.version };
    if (brokerEditor.editing) await mqttApi.update(brokerEditor.editing.broker_id, body); else await mqttApi.create(body);
    brokerEditor.visible = false; ElMessage.success('MQTT 配置已保存，当前处于停用状态'); await openBrokers();
  } catch (e) { ElMessage.error(e.message); }
  finally { brokerEditor.saving = false; }
}
async function toggleBroker(row) {
  try { await ElMessageBox.confirm(row.enabled ? '停用将停止该连接下所有设备的报文接收。' : '启用后后端会连接服务器并订阅已登记设备。', `${row.enabled ? '停用' : '启用'} MQTT 连接`, { type: 'warning' });
    await mqttApi.setEnabled(row.broker_id, { enabled: !row.enabled, version: row.version }); await openBrokers(); }
  catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e)); }
}

watch(() => [filters.type_code, filters.channel, filters.connectivity, filters.enabled, filters.sort], search);
onMounted(() => { bootstrap(); refreshTimer = window.setInterval(() => { if (!loading.value && !document.hidden) { loadList(); loadOverview(); } }, 10000); });
onBeforeUnmount(() => { alive = false; detailSequence++; window.clearInterval(refreshTimer); });
</script>

<template>
  <section class="page-stack">
    <PageHeader title="设备管理" description="统一维护设备台账、连接身份和协议配置；密码和识别码只保存外部凭据引用。">
      <el-button v-if="canReadBrokers" @click="openBrokers">MQTT 连接</el-button>
      <el-button type="primary" :disabled="!canOperate" @click="openDevice()">接入设备</el-button>
    </PageHeader>
    <MetricCards :items="metrics" />
    <ErrorAlert :message="error" @retry="bootstrap" />
    <el-card class="filter-card">
      <el-form inline @submit.prevent="search">
        <el-form-item label="关键词"><el-input v-model="filters.keyword" clearable placeholder="设备编号或名称" @keyup.enter="search" /></el-form-item>
        <el-form-item label="设备类型"><el-select v-model="filters.type_code" clearable placeholder="全部"><el-option v-for="item in options.types" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="接入通道"><el-select v-model="filters.channel" clearable placeholder="全部"><el-option v-for="item in options.channels" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="连接状态"><el-select v-model="filters.connectivity" clearable placeholder="全部"><el-option v-for="value in ['ONLINE','OFFLINE','ABNORMAL','UNKNOWN']" :key="value" :label="statusText(value)" :value="value" /></el-select></el-form-item>
        <el-form-item label="台账状态"><el-select v-model="filters.enabled" clearable placeholder="全部"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </el-card>

    <div class="split-panel">
      <el-card class="table-card">
        <div class="table-toolbar"><span class="table-toolbar__title">设备台账</span><span class="muted">默认优先显示异常、离线和未知设备</span></div>
        <el-table v-loading="loading" :data="table.items" height="520" row-key="device_id" :row-class-name="({row}) => row.device_id===selectedId?'selected-row':''" @row-click="selectRow">
          <el-table-column prop="device_no" label="设备编号" min-width="135" fixed />
          <el-table-column prop="name" label="设备名称" min-width="160" show-overflow-tooltip />
          <el-table-column prop="device_type_name" label="类型" width="100" />
          <el-table-column prop="channel" label="接入通道" width="115" />
          <el-table-column prop="region_name" label="区域" width="105"><template #default="{row}">{{ display(row.region_name) }}</template></el-table-column>
          <el-table-column prop="connectivity" label="连接" width="84"><template #default="{row}"><el-tag :type="statusType(row.connectivity)" effect="plain">{{ statusText(row.connectivity) }}</el-tag></template></el-table-column>
          <el-table-column prop="last_heartbeat_at" label="最后心跳" min-width="165"><template #default="{row}"><span class="mono">{{ formatTime(row.last_heartbeat_at) }}</span></template></el-table-column>
          <el-table-column label="状态" width="76"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'" effect="plain">{{ row.enabled?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="120" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!canOperate" @click.stop="openDevice(row)">编辑</el-button><el-button link :type="row.enabled?'danger':'success'" :disabled="!canOperate" @click.stop="toggleDevice(row)">{{ row.enabled?'停用':'启用' }}</el-button></template></el-table-column>
        </el-table>
        <div class="pagination-row"><span>共 {{ table.total }} 台</span><el-pagination v-model:current-page="table.page" v-model:page-size="table.size" :page-sizes="[10,20,50,100]" layout="sizes, prev, pager, next" :total="table.total" @current-change="loadList(false)" @size-change="table.page=1;loadList(false)" /></div>
      </el-card>

      <el-card v-loading="detailLoading" class="detail-panel">
        <template #header><div class="table-toolbar"><span class="table-toolbar__title">设备详情</span><el-tag v-if="detail?.simulated" type="warning" effect="plain">模拟数据</el-tag></div></template>
        <el-empty v-if="!detail" description="请选择设备" />
        <template v-else>
          <h2>{{ detail.device?.name }}</h2><p class="muted mono">{{ detail.device?.device_no }} · {{ display(detail.protocol_code,'未配置协议') }}</p>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="连接状态"><el-tag :type="statusType(detail.device?.connectivity)" effect="plain">{{ statusText(detail.device?.connectivity) }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="设备类型">{{ display(detail.device?.device_type_name) }}</el-descriptions-item>
            <el-descriptions-item label="所属区域">{{ display(detail.region_name || detail.device?.region_name) }}</el-descriptions-item>
            <el-descriptions-item label="供应商 / 型号">{{ display(detail.vendor) }} / {{ display(detail.model) }}</el-descriptions-item>
            <el-descriptions-item label="来源模式">{{ detail.source_mode==='mock'||detail.source_mode==='replay'?'模拟/回放':'真实来源' }}</el-descriptions-item>
          </el-descriptions>
          <div class="detail-section"><h3>连接配置</h3><p v-if="!detail.connection_visible" class="muted">当前账号没有连接配置查看权限。</p><pre v-else class="json-block">{{ JSON.stringify(detail.connection || {}, null, 2) }}</pre></div>
          <div class="detail-section"><h3>协议状态</h3><pre class="json-block">{{ JSON.stringify(protocolStatus || {}, null, 2) }}</pre></div>
        </template>
      </el-card>
    </div>

    <el-dialog v-model="deviceDialog.visible" :title="deviceDialog.editing?`编辑设备 · ${deviceForm.device_no}`:'接入设备'" width="780px" destroy-on-close>
      <p class="form-note">TCP 设备填写现场地址和允许网段；MQTT 设备选择已配置连接。接入身份创建后不可修改。</p>
      <el-form ref="deviceFormRef" :model="deviceForm" label-position="top" class="form-grid">
        <el-form-item label="接入协议" prop="protocol_code" :rules="[{required:true,message:'请选择接入协议'}]"><el-select v-model="deviceForm.protocol_code" :disabled="deviceDialog.editing"><el-option v-for="item in protocols" :key="item.protocol_code" :label="`${item.name} · v${item.version}`" :value="item.protocol_code" /></el-select></el-form-item>
        <el-form-item label="设备编号" prop="device_no" :rules="[{required:true,message:'请输入设备编号'}]"><el-input v-model="deviceForm.device_no" :disabled="deviceDialog.editing" /></el-form-item>
        <el-form-item label="设备名称" prop="name" :rules="[{required:true,message:'请输入设备名称'}]"><el-input v-model="deviceForm.name" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="deviceForm.vendor" /></el-form-item>

        <template v-if="isMqttTransport">
          <el-form-item label="MQTT 连接" prop="broker_id" :rules="[{required:true,message:'请选择 MQTT 连接'}]"><el-select v-model="deviceForm.broker_id" :disabled="deviceDialog.editing"><el-option v-for="item in brokers" :key="item.broker_id" :label="`${item.name} · ${item.enabled?'启用':'停用'}`" :value="item.broker_id" /></el-select></el-form-item>
          <el-form-item label="数据来源" prop="source_mode" :rules="[{required:true,message:'请选择数据来源'}]"><el-select v-model="deviceForm.source_mode" :disabled="deviceDialog.editing"><el-option label="模拟回放" value="replay" /><el-option label="真实来源，待联调" value="live" /></el-select></el-form-item>
          <el-form-item label="所属单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择单位和区域'}]"><el-select v-model="deviceForm.scope_key" :disabled="deviceDialog.editing"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
          <el-form-item label="外部设备编号" prop="external_device_id" :rules="[{required:true,message:'请输入外部设备编号'}]"><el-input v-model="deviceForm.external_device_id" :disabled="deviceDialog.editing" /></el-form-item>
          <el-form-item v-if="isMqtt" label="设备类型" prop="device_type_abbr" :rules="[{required:true,message:'请选择设备类型'}]"><el-select v-model="deviceForm.device_type_abbr" :disabled="deviceDialog.editing"><el-option v-for="item in [['radar','雷达'],['5ga','5G-A'],['tdoa','TDOA'],['aoa','AOA'],['dcd','协议破解'],['rid','RemoteID'],['dec','诱骗'],['ifr','干扰'],['bsc','驱鸟炮']]" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select></el-form-item>
          <el-form-item v-if="isMqtt" label="提供方编码" prop="provider_code" :rules="[{required:true,message:'请输入提供方编码'}]"><el-input v-model="deviceForm.provider_code" :disabled="deviceDialog.editing" /></el-form-item>
          <el-form-item v-if="isEo" label="边缘中心 ID" prop="edge_id" :rules="[{required:true,message:'请输入边缘中心 ID'}]"><el-input v-model="deviceForm.edge_id" :disabled="deviceDialog.editing" /></el-form-item>
          <el-form-item label="型号"><el-input v-model="deviceForm.model" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="设备地址" prop="host" :rules="[{required:true,message:'请输入设备地址'}]"><el-input v-model="deviceForm.host" /></el-form-item>
          <el-form-item label="端口" prop="port" :rules="[{required:true,message:'请输入端口'}]"><el-input-number v-model="deviceForm.port" :min="1" :max="65535" controls-position="right" /></el-form-item>
          <el-form-item label="设备网段" prop="allowed_cidrs" :rules="[{required:true,message:'请输入允许网段'}]"><el-input v-model="deviceForm.allowed_cidrs" placeholder="例如 192.0.2.0/24" /></el-form-item>
          <el-form-item label="所属区域"><el-input v-model="deviceForm.region_name" /></el-form-item>
          <el-form-item v-if="isRadar" label="雷达识别码引用"><el-input v-model="deviceForm.recognition_code_ref" placeholder="env:RADAR_RECOGNITION_CODE" /></el-form-item>
          <el-form-item v-if="isRadar" label="雷达扩展"><el-checkbox v-model="deviceForm.rtk_enabled">采集 RTK</el-checkbox><el-checkbox v-model="deviceForm.coordinate_transform_enabled">派生经纬度</el-checkbox></el-form-item>
          <el-form-item v-if="isCountermeasure" label="反制设备地址"><el-input-number v-model="deviceForm.device_address" :min="1" :max="244" /></el-form-item>
          <el-form-item v-if="isCountermeasure" label="线缆编码"><el-select v-model="deviceForm.wire_encoding"><el-option v-for="item in ['AUTO','RAW_BYTES','ASCII_HEX_SPACED','ASCII_HEX_COMPACT']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="deviceDialog.visible=false">取消</el-button><el-button type="primary" :loading="deviceDialog.saving" @click="saveDevice">{{ deviceDialog.editing?'保存':'接入' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="brokerDialog.visible" title="MQTT 连接" width="820px">
      <p class="form-note">连接成功只表示报文通道可用；设备收到有效工参后才显示在线。修改配置前请先停用。</p>
      <div class="table-toolbar"><span>共 {{ brokerDialog.items.length }} 条连接</span><el-button type="primary" :disabled="!canEditBrokers" @click="openBrokerEditor()">新增连接</el-button></div>
      <div v-loading="brokerDialog.loading"><el-empty v-if="!brokerDialog.items.length" description="尚未配置 MQTT 连接" />
        <div v-for="item in brokerDialog.items" :key="item.broker_id" class="connection-card"><b>{{ item.name }}</b><span>{{ item.source_mode==='replay'?'模拟回放':'真实来源' }} · {{ item.host }}:{{ item.port }}</span><el-tag :type="item.enabled?'success':'info'" effect="plain">{{ item.enabled?'启用':'停用' }} · {{ item.connection_state }}</el-tag><span class="inline-actions"><el-button link :disabled="!canEditBrokers||item.enabled" @click="openBrokerEditor(item)">编辑</el-button><el-button link :disabled="!canEditBrokers" @click="toggleBroker(item)">{{ item.enabled?'停用':'启用' }}</el-button></span></div>
      </div>
    </el-dialog>

    <el-dialog v-model="brokerEditor.visible" :title="brokerEditor.editing?'编辑 MQTT 连接':'新增 MQTT 连接'" width="720px" append-to-body>
      <p class="form-note">凭据填写 `env:环境变量名`，禁止填写密码本身；新增连接默认停用。</p>
      <el-form ref="brokerFormRef" :model="brokerForm" label-position="top" class="form-grid">
        <el-form-item label="连接名称" prop="name" :rules="[{required:true,message:'请输入名称'}]"><el-input v-model="brokerForm.name" /></el-form-item>
        <el-form-item label="服务器地址" prop="host" :rules="[{required:true,message:'请输入地址'}]"><el-input v-model="brokerForm.host" /></el-form-item>
        <el-form-item label="端口" prop="port" :rules="[{required:true,message:'请输入端口'}]"><el-input-number v-model="brokerForm.port" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="TLS"><el-switch v-model="brokerForm.tls" active-text="启用" inactive-text="关闭" /></el-form-item>
        <el-form-item label="数据来源" prop="source_mode" :rules="[{required:true,message:'请选择来源'}]"><el-select v-model="brokerForm.source_mode" :disabled="Boolean(brokerEditor.editing)"><el-option label="模拟回放" value="replay" /><el-option label="真实来源，待联调" value="live" /></el-select></el-form-item>
        <el-form-item label="单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择范围'}]"><el-select v-model="brokerForm.scope_key" :disabled="Boolean(brokerEditor.editing)"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
        <el-form-item label="用户名"><el-input v-model="brokerForm.username" /></el-form-item>
        <el-form-item label="密码凭据引用"><el-input v-model="brokerForm.credential_ref" placeholder="env:MQTT_PASSWORD" /></el-form-item>
        <el-form-item class="wide" label="允许的服务器网段 CIDR" prop="allowed_cidrs" :rules="[{required:true,message:'请输入网段'}]"><el-input v-model="brokerForm.allowed_cidrs" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="brokerEditor.visible=false">取消</el-button><el-button type="primary" :loading="brokerEditor.saving" @click="saveBroker">保存</el-button></template>
    </el-dialog>
  </section>
</template>
