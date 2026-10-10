<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRealtimeRefresh } from '@/services/realtime.js';
import { ElMessage, ElMessageBox } from 'element-plus';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import PageHeader from '@/components/PageHeader.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import CommissionDeviceStatus from './CommissionDeviceStatus.vue';
import DeviceMaintenancePanel from './DeviceMaintenancePanel.vue';
import MaintenanceWorkflowPanel from './maintenance/MaintenanceWorkflowPanel.vue';
import { useMaintenanceWorkflow } from './maintenance/useMaintenanceWorkflow.js';
import { commissionApi, deviceApi } from '@/api/devices.js';
import { useAuthStore } from '@/stores/auth.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
import './operations-reference.css';
import { display, formatDeviceNo, formatTime, statusType } from '@/utils/format.js';

const auth = useAuthStore();
const route = useRoute(), router = useRouter();
const maintenanceId = computed(() => typeof route?.query?.maintenance_task_id === 'string' ? route.query.maintenance_task_id : '');
const maintenanceListVisible = computed(() => !maintenanceId.value && route?.query?.view === 'maintenance');
const maintenance = useMaintenanceWorkflow(maintenanceId);
const workflow = maintenance.workflow;
const maintenanceBusy = maintenance.busy, maintenancePending = maintenance.pending;
const incidentBusy = ref(false), incidentPending = ref(null), incidentError = ref('');
const workflowLocked = computed(() => actionBusy.value || incidentBusy.value || !!incidentPending.value);
const canOperate = computed(() => auth.hasPermission('commissioning.op') && (!maintenanceId.value || (
  workflow.value?.state === 'PROCESSING' && workflow.value?.task?.device_id === selectedDeviceId.value
  && !maintenance.error.value && !maintenance.loading.value && !maintenanceBusy.value && !maintenancePending.value && !incidentBusy.value && !incidentPending.value)));
const devices = ref([]);
const filters = reactive({ keyword: '', region: '', type: '' });
const filtersExpanded = ref(true);
const records = reactive({ items: [], page: 1, size: 10, total: 0 });
const recordsLoading = ref(false);
const recordsError = ref('');
let recordsRequest = 0;
const displayedReport = ref(null);
const visibleDevices = computed(() => maintenanceId.value
  ? devices.value.filter(item => item.device_id === workflow.value?.task?.device_id)
  : devices.value);
const regions = computed(() => [...new Set(visibleDevices.value.map(item => item.region_name).filter(Boolean))]);
const types = computed(() => [...new Set(visibleDevices.value.map(item => item.device_type_name).filter(Boolean))]);
const deviceGroups = computed(() => Object.entries(visibleDevices.value.filter(item =>
  (!filters.region || item.region_name === filters.region) && (!filters.type || item.device_type_name === filters.type)
  && (!filters.keyword || `${item.device_no} ${item.name}`.toLowerCase().includes(filters.keyword.toLowerCase()))
).reduce((groups, item) => { (groups[item.region_name || '未登记区域'] ||= []).push(item); return groups; }, {})));
const selectionLocked = computed(() => Boolean(maintenanceId.value) || actionBusy.value || Boolean(active.value && !terminal.has(active.value.status)));
const commissionInProgress = computed(() => Boolean(active.value && !terminal.has(active.value.status)));
const linkedToMaintenance = computed(() => Boolean(active.value && workflow.value?.commission_tasks?.some(item => item.commission_id === active.value.commission_id)));

const selectedDeviceId = ref('');
const active = ref(null);
const events = ref([]);
const afterSeq = ref(0);
const report = ref(null);
const loading = ref(false);
const actionBusy = ref(false);
const error = ref('');
const information = ref(null);
const informationLoading = ref(false);
const informationError = ref('');
let informationRequest = 0;
let informationTimer;
const reportDialog = ref(false);
const terminal = new Set(['PASSED', 'FAILED', 'UNTESTABLE', 'CANCELLED']);
const config = reactive({ transport: 'TCP', host: '', port: null, timeout_millis: 3000 });
let pollTimer;
let pollInFlight = false;
let alive = true;
let connectionRequest = 0;
let existingRequest = 0;

const currentDevice = computed(() => devices.value.find(item => item.device_id === selectedDeviceId.value));
// MQTT 设备主动上报，平台不与设备直连：调测核对平台自身的 MQTT 会话、主题订阅和最近上报，网络参数显示平台侧 MQTT 服务器。
const isMqttDevice = item => ['LINGYUN_MQTT_V8_6', 'EO_EDGE_MQTT_20250826'].includes(item?.protocol_code);
const taskSupported = computed(() => information.value?.device_id === selectedDeviceId.value && information.value?.task_supported);
const informationNotes = computed(() => information.value?.device_id === selectedDeviceId.value
  && Array.isArray(information.value?.notes)
  ? information.value.notes.filter(note => typeof note === 'string' && note.trim()) : []);
const isTestSource = task => task?.simulated === true || ['mock', 'replay'].includes(task?.source_mode);
const isSimulation = computed(() => isTestSource(active.value) || isTestSource(currentDevice.value));
const formalDevice = computed(() => currentDevice.value?.source_mode === 'live' && !currentDevice.value?.simulated);
const simulationAllowed = computed(() => information.value?.device_id === selectedDeviceId.value
  && !informationLoading.value && !informationError.value && information.value?.simulation_allowed === true);
const deviceSourceAllowed = computed(() => formalDevice.value || (isTestSource(currentDevice.value) && simulationAllowed.value));
const taskSourceAllowed = computed(() => deviceSourceAllowed.value && (!isSimulation.value || simulationAllowed.value));
const mqttTask = computed(() => isMqttDevice(currentDevice.value) || isMqttDevice(active.value));
const mqttEndpoint = computed(() => {
  const saved = active.value?.device_id === selectedDeviceId.value ? active.value?.configuration : null;
  if (saved?.transport === 'MQTT') return saved;
  if (information.value?.device_id !== selectedDeviceId.value) return null;
  const fields = information.value?.sections?.find(section => section.code === 'mqtt_endpoint')?.fields || [];
  const value = key => { const field = fields.find(item => item.key === key); return field && field.status !== 'REDACTED' ? field.value : null; };
  return value('host') ? { host: value('host'), port: value('port'), tls: value('tls') } : null;
});
const mqttTransport = computed(() => `MQTT${mqttEndpoint.value?.tls === true ? '（TLS）' : mqttEndpoint.value?.tls === false ? '（未加密）' : ''}`);
const mqttFreshness = computed(() => ((active.value || currentDevice.value)?.protocol_code === 'EO_EDGE_MQTT_20250826'
  ? '心跳按平台心跳超时判定' : '工参与感知报文 30 秒内'));
const connectionHidden = computed(() => !mqttTask.value && !auth.hasPermission('devices.op') && !active.value?.configuration);
const statusMeta = {
  CREATED: ['待连接', 'info'], CONNECTING: ['连接中', 'warning'], CONNECTED: ['已连接', 'success'], READY: ['待调测', 'warning'],
  RUNNING: ['调测中', 'warning'], PASSED: ['通过', 'success'], FAILED: ['失败', 'danger'], UNTESTABLE: ['不可判定', 'warning'], CANCELLED: ['已取消', 'info']
};
const statusText = (value, simulated = false) => value === 'PASSED' && simulated ? '模拟通过（非正式）' : (statusMeta[value]?.[0] || display(value));
const tagType = (value, simulated = false) => simulated ? 'warning' : statusMeta[value]?.[1] || statusType(value);
const protocolLabel = task => ({ RADAR_TCP_V3_0_0: 'T02/兼容机扫雷达 TCP', COUNTERMEASURE_TCP_4CH_V2_0: '固定式四通道网络控制器', LINGYUN_MQTT_V8_6: '凌云 MQTT V8.6', EO_EDGE_MQTT_20250826: '光电边端 MQTT' })[task?.protocol_code] || task?.protocol_code || '未配置';
const steps = ['设备接入', '建立连接', '参数配置', '协议调测', '结果确认'];
const stepIndex = computed(() => {
  const value = active.value?.status;
  if (!value) return 0;
  if (value === 'CREATED') return 1;
  if (value === 'CONNECTING') return 1;
  if (value === 'CONNECTED') return 2;
  if (value === 'READY' || value === 'RUNNING') return 3;
  if (value === 'PASSED') return isSimulation.value ? 4 : 5;
  if (['FAILED', 'UNTESTABLE', 'CANCELLED'].includes(value)) return active.value?.started_at ? 3 : 1;
  return 4;
});

function resetConfig() {
  Object.assign(config, { transport: 'TCP', host: '', port: null, timeout_millis: 3000 });
}
function restoreTaskConfig() {
  if (active.value?.device_id !== selectedDeviceId.value || !active.value?.configuration) return false;
  const saved = active.value.configuration;
  Object.assign(config, { transport: saved.transport || 'TCP', host: saved.host || '', port: saved.port ?? null, timeout_millis: saved.timeout_millis ?? 3000 });
  return true;
}
async function loadInformation() {
  const id = selectedDeviceId.value;
  const request = ++informationRequest;
  if (!id) { information.value = null; informationLoading.value = false; informationError.value = ''; return; }
  informationLoading.value = true;
  try {
    const data = await commissionApi.information(id);
    if (!alive || request !== informationRequest || selectedDeviceId.value !== id) return;
    information.value = data; informationError.value = '';
  } catch (e) {
    if (alive && request === informationRequest) { information.value = null; informationError.value = e.message || '设备信息读取失败'; }
  } finally { if (request === informationRequest) informationLoading.value = false; }
}
async function applyConnection(deviceId) {
  const request = ++connectionRequest;
  if (restoreTaskConfig()) return;
  if (!deviceId || !auth.hasPermission('devices.op')) { resetConfig(); return; }
  if (isMqttDevice(currentDevice.value)) { resetConfig(); return; }
  try {
    const detail = await deviceApi.detail(deviceId);
    if (!alive || request !== connectionRequest || selectedDeviceId.value !== deviceId) return;
    if (restoreTaskConfig()) return;
    const connection = detail.connection || {};
    Object.assign(config, { transport: connection.transport || 'TCP', host: connection.host || '', port: connection.port ?? null, timeout_millis: connection.timeout_millis ?? 3000 });
  } catch (e) { if (alive && request === connectionRequest) { if (!restoreTaskConfig()) resetConfig(); error.value = e.message || '连接配置读取失败'; } }
}

async function loadDevices() {
  const items = [];
  let page = 1;
  let hasMore = true;
  while (hasMore) {
    const data = await deviceApi.list({ page, size: 100, enabled: maintenanceId.value ? undefined : true, sort: 'device_no_asc' });
    items.push(...(data.items || []));
    hasMore = Boolean(data.items?.length) && items.length < (data.total ?? items.length);
    page++;
  }
  devices.value = items;
  if (maintenanceId.value) { selectMaintenanceDevice(); return; }
  if (!devices.value.some(item => item.device_id === selectedDeviceId.value)) selectedDeviceId.value = devices.value[0]?.device_id || '';
}
function selectMaintenanceDevice() {
  if (!maintenanceId.value) return;
  const task = workflow.value?.task;
  if (!task) { selectedDeviceId.value = ''; return; }
  const target = route?.query?.device_id;
  if (target && target !== task.device_id) { selectedDeviceId.value = ''; error.value = '链接中的设备与运维待办不匹配，请从原待办重新进入。'; return; }
  if (!devices.value.some(item => item.device_id === task.device_id)) { selectedDeviceId.value = ''; error.value = '待办设备当前不可见或已删除，请核对设备访问权限。'; return; }
  if (active.value && !terminal.has(active.value.status) && active.value.device_id !== task.device_id) {
    selectedDeviceId.value = ''; error.value = '尚有另一台设备的调测任务进行中，请先完成或取消该任务。'; return;
  }
  selectedDeviceId.value = task.device_id;
  error.value = '';
}
async function refreshMaintenance() { await maintenance.load(); selectMaintenanceDevice(); }
async function maintenanceAction(action, fields = {}, done) {
  if (maintenanceBusy.value || maintenancePending.value || workflowLocked.value) return;
  if (action === 'SUBMIT_VERIFICATION' && commissionInProgress.value) { ElMessage.warning('请先完成或取消当前调测任务。'); return; }
  const taskId = maintenanceId.value;
  if (['SUBMIT_VERIFICATION','COMPLETE'].includes(action)) {
    try {
      const { value } = await ElMessageBox.prompt(action === 'COMPLETE' ? '可补充处理措施及恢复结果。完成时会再次核对当前设备状态。' : '可补充处理措施和核验说明。提交后待办仍继续跟踪。', action === 'COMPLETE' ? '完成运维待办' : '提交恢复核验', {
        inputType: 'textarea', inputPlaceholder: '处理说明（选填，最多 1000 字）', inputValidator: value => (value?.trim().length || 0) <= 1000 || '说明不能超过 1000 字', confirmButtonText: action === 'COMPLETE' ? '确认完成' : '提交核验', cancelButtonText: '取消'
      });
      if (taskId !== maintenanceId.value) return;
      fields = { note: (value || '').trim() };
    } catch { return; }
  }
  const result = await maintenance.act(action, fields);
  if (result) { done?.(); ElMessage.success(action === 'VERIFY_RECOVERY' ? '核验结果已记录，请查看恢复结论。' : '运维待办已更新。'); }
}
async function linkCommission(task = active.value) {
  if (!task || !taskSourceAllowed.value || !maintenance.can('LINK_COMMISSION')) return;
  const result = await maintenance.act('LINK_COMMISSION', { commission_id: task.commission_id });
  if (result) ElMessage.success('调测任务已关联到运维待办。');
}
function leaveMaintenance() { void router?.push({ path: '/operations/commission', query: { view: 'maintenance' } }); }
async function recoverIncident(incident) {
  if (incidentBusy.value || maintenanceBusy.value || maintenancePending.value || actionBusy.value) return;
  if (!incidentPending.value) {
    if (!workflow.value?.open_incidents?.some(item => item.incident_id === incident?.incident_id && item.allowed_actions?.includes('VERIFY_RECOVERY'))) return;
    incidentPending.value = { id: incident.incident_id, key: newIdempotencyKey('incident-recovery') };
  }
  const request = incidentPending.value;
  incidentBusy.value = true; incidentError.value = '';
  try {
    const result = await deviceApi.recoverIncident(request.id, request.key);
    if (!alive) return;
    incidentPending.value = null;
    if (result.result === 'PASS') ElMessage.success(result.reason || '该异常事件恢复核验通过');
    else ElMessage.warning(result.reason || '该异常事件尚未恢复');
    await refreshMaintenance(); await loadInformation();
  } catch (e) {
    if (!alive) return;
    incidentError.value = e.message || '事件核验结果未确认';
    if (e.status && e.status < 500) { incidentPending.value = null; await refreshMaintenance(); }
    else incidentError.value += '；请使用原请求重试确认后再继续。';
  } finally { if (alive) incidentBusy.value = false; }
}
async function bootstrap() {
  loading.value = true; error.value = '';
  try {
    const previous = selectedDeviceId.value;
    await loadDevices();
    if (previous === selectedDeviceId.value) await Promise.all([loadInformation(), applyConnection(previous), loadExistingTask(previous)]);
  }
  catch (e) { error.value = e.message || '调测数据加载失败'; }
  finally { loading.value = false; }
}
async function loadEvents() {
  if (!active.value) return;
  const id = active.value.commission_id;
  const data = await commissionApi.events(id, { after_seq: afterSeq.value, limit: 100 });
  if (active.value?.commission_id !== id) return;
  const merged = new Map([...events.value, ...(data.items || [])].map(item => [item.event_seq, item]));
  events.value = [...merged.values()].sort((a, b) => a.event_seq - b.event_seq).slice(-150);
  afterSeq.value = Math.max(afterSeq.value, data.next_seq || 0);
}
async function pollActive() {
  if (!active.value || pollInFlight) return;
  pollInFlight = true;
  const id = active.value.commission_id;
  try {
    const latest = await commissionApi.get(id);
    if (!alive || active.value?.commission_id !== id) return;
    const changed = latest.status !== active.value.status;
    active.value = latest; await loadEvents();
    if (changed && ['PASSED', 'FAILED', 'UNTESTABLE'].includes(latest.status)) {
      const result = await commissionApi.report(latest.commission_id);
      if (alive && active.value?.commission_id === id) report.value = result;
    }
    if (changed && alive && active.value?.commission_id === id) {
      void loadRecords();
      await refreshMaintenanceAfterCommission();
    }
    error.value = '';
  } catch (e) { error.value = e.message || '任务轮询失败'; }
  finally { pollInFlight = false; }
}
async function refreshMaintenanceAfterCommission() {
  if (maintenanceId.value && !maintenanceBusy.value && !maintenancePending.value) await maintenance.load();
}
async function runAction(action, success) {
  if (actionBusy.value) return;
  actionBusy.value = true; error.value = '';
  try { active.value = await action(); ElMessage.success(success); await pollActive(); await loadRecords(); await refreshMaintenanceAfterCommission(); }
  catch (e) { error.value = e.message || '操作失败'; ElMessage.error(error.value); }
  finally { actionBusy.value = false; }
}
async function createTask() {
  if (!canOperate.value || !selectedDeviceId.value || !taskSupported.value || !deviceSourceAllowed.value) return;
  await runAction(async () => {
    const task = await commissionApi.create({ device_id: selectedDeviceId.value });
    active.value = task;
    if (maintenanceId.value) await linkCommission(task);
    events.value = []; afterSeq.value = 0; report.value = null; await applyConnection(selectedDeviceId.value); return task;
  }, '调测任务已创建');
}
function connectTask() { if (!canOperate.value || !taskSourceAllowed.value) return; runAction(() => commissionApi.connect(active.value.commission_id, active.value.version), isSimulation.value ? '正在建立模拟调测连接' : '正在建立连接'); }
function saveConfig() {
  if (!canOperate.value || !taskSourceAllowed.value) return;
  if (mqttTask.value) {
    runAction(() => commissionApi.configure(active.value.commission_id, { version: active.value.version, transport: 'MQTT' }), 'MQTT 连接快照已保存');
    return;
  }
  if (!config.host?.trim() || !config.port) { ElMessage.error('主机和端口为必填'); return; }
  runAction(() => commissionApi.configure(active.value.commission_id, { version: active.value.version, ...config, host: config.host.trim() }), '配置快照已保存');
}
function startTask() { if (!canOperate.value || !taskSourceAllowed.value) return; runAction(() => commissionApi.start(active.value.commission_id, active.value.version), isSimulation.value ? '模拟调测已进入持久化队列' : '协议调测已进入持久化队列'); }
function cancelTask() { runAction(() => commissionApi.cancel(active.value.commission_id, active.value.version), '任务已取消'); }
async function loadRecords() {
  const request = ++recordsRequest;
  const id = selectedDeviceId.value;
  if (!id) { records.items = []; records.total = 0; recordsLoading.value = false; return; }
  recordsLoading.value = true; recordsError.value = '';
  try {
    const data = await commissionApi.list({ device_id: id, page: records.page, size: records.size });
    if (!alive || request !== recordsRequest || id !== selectedDeviceId.value) return;
    records.items = data.items || []; records.total = data.total ?? records.items.length;
  } catch (e) { if (alive && request === recordsRequest) recordsError.value = e.message || '联调记录加载失败'; }
  finally { if (request === recordsRequest) recordsLoading.value = false; }
}
async function viewReport(task = active.value) {
  if (!task) return;
  try {
    displayedReport.value = task.commission_id === active.value?.commission_id && report.value
      ? report.value : await commissionApi.report(task.commission_id);
    reportDialog.value = true;
  } catch (e) { ElMessage.error(e.message || '调测报告读取失败'); }
}
function duration(task) {
  if (!task.started_at || !task.finished_at) return '—';
  return `${Math.max(0, Math.round((task.finished_at - task.started_at) / 1000))} 秒`;
}
async function loadExistingTask(deviceId) {
  const request = ++existingRequest;
  if (!deviceId || (active.value && !terminal.has(active.value.status))) return;
  try {
    const data = await commissionApi.list({ device_id: deviceId, page: 1, size: 10 });
    if (!alive || request !== existingRequest || selectedDeviceId.value !== deviceId) return;
    records.items = data.items || []; records.total = data.total ?? records.items.length;
    active.value = (data.items || []).find(item => !terminal.has(item.status)) || null;
    events.value = []; afterSeq.value = 0; report.value = null;
    if (active.value) await loadEvents();
  } catch (e) { if (alive && request === existingRequest) error.value = e.message || '已有任务读取失败'; }
}

watch(active, restoreTaskConfig);
watch(selectedDeviceId, id => {
  information.value = null; informationError.value = '';
  ++recordsRequest; records.items = []; records.total = 0; records.page = 1; recordsError.value = ''; recordsLoading.value = false;
  void loadInformation();
  if (active.value && !terminal.has(active.value.status)) return;
  active.value = null; events.value = []; afterSeq.value = 0; report.value = null;
  void applyConnection(id); void loadExistingTask(id);
});
watch(() => workflow.value?.task?.device_id, selectMaintenanceDevice);
watch(maintenanceId, () => { selectedDeviceId.value = ''; error.value = ''; });
watch(maintenanceListVisible, visible => { if (!visible) void bootstrap(); });
onBeforeRouteUpdate(to => {
  if (workflowLocked.value || maintenanceBusy.value || maintenancePending.value) { ElMessage.warning('请先确认当前操作结果，再切换待办。'); return false; }
  if (commissionInProgress.value && to.query.device_id !== selectedDeviceId.value) { ElMessage.warning('当前调测尚未结束，请先完成或取消再切换设备。'); return false; }
});
onBeforeRouteLeave(() => {
  if (workflowLocked.value || maintenanceBusy.value || maintenancePending.value) { ElMessage.warning('当前操作结果尚未确认，请先重试确认。'); return false; }
});
onMounted(async () => {
  if (!maintenanceListVisible.value) await bootstrap();
  if (!alive) return;
  pollTimer = window.setInterval(() => { if (!maintenanceListVisible.value) void pollActive(); }, 2000);
  informationTimer = window.setInterval(() => { if (!maintenanceListVisible.value && !informationLoading.value) void loadInformation(); }, 10000);
});
onBeforeUnmount(() => { alive = false; ++informationRequest; window.clearInterval(pollTimer); window.clearInterval(informationTimer); });
// 设备资料或调测任务变化后重读设备列表与当前调测，设备在线状态变化后重读设备信息；
// 调测进行中不重排设备列表。定时器保留为推送不可用时的兜底。
useRealtimeRefresh(['device', 'device_state'], topics => {
  if (maintenanceListVisible.value) return undefined;
  const all = topics.includes('*');
  const tasks = [];
  if (all || topics.includes('device')) {
    tasks.push(pollActive());
    if (!commissionInProgress.value) tasks.push(loadDevices().catch(() => {}));
  }
  if ((all || topics.includes('device_state')) && !informationLoading.value) tasks.push(loadInformation());
  return Promise.all(tasks);
}, { minIntervalMs: 2_000 });
</script>

<template>
  <section class="page-stack operation-page commission-reference">
    <PageHeader title="设备接入调测" description="选择设备、配置连接，查看调测结果和历史记录。">
      <el-button v-if="maintenanceId" @click="leaveMaintenance">返回运维待办</el-button>
      <el-button v-else-if="!maintenanceListVisible && auth.hasPermission('monitoring.read')" @click="leaveMaintenance">运维待办</el-button>
      <el-button v-if="maintenanceListVisible" @click="router.push('/operations/commission')">返回设备调测</el-button>
      <el-tag v-if="active && !maintenanceListVisible" :type="tagType(active.status,isTestSource(active))" effect="plain">{{ statusText(active.status, isTestSource(active)) }}</el-tag>
    </PageHeader>
    <DeviceMaintenancePanel v-if="maintenanceListVisible" />
    <template v-else>
    <ErrorAlert :message="error" @retry="bootstrap" />
    <ErrorAlert v-if="maintenanceId" :message="maintenance.error.value" @retry="refreshMaintenance" />
    <p v-if="maintenanceId && maintenance.loading.value" class="muted">正在定位运维待办及设备…</p>
    <MaintenanceWorkflowPanel v-if="workflow" :workflow="workflow" :busy="maintenanceBusy" :pending="!!maintenancePending" :locked="workflowLocked" :action-error="maintenance.actionError.value" @action="maintenanceAction" @retry="maintenance.retry" @refresh="refreshMaintenance" @recover-incident="recoverIncident" />
    <el-alert v-if="incidentError" :title="incidentError" type="error" :closable="false" show-icon /><el-button v-if="incidentPending && !incidentBusy" :loading="incidentBusy" @click="recoverIncident()">重试确认事件核验</el-button>
    <el-card class="commission-steps"><el-steps :active="stepIndex" :process-status="active?.status==='FAILED'?'error':'process'" :finish-status="isSimulation || active?.status==='CANCELLED' ? 'wait' : 'success'" align-center><el-step v-for="(item,index) in steps" :key="item" :title="item" :description="['选择设备并创建任务','建立设备通信链路','保存本次调测参数','协议响应与数据校验','查看结果与调测报告'][index]" /></el-steps></el-card>
    <div v-loading="loading" class="commission-workspace">
      <div class="commission-column">
        <el-card class="commission-selection" :class="{ 'filters-collapsed': !filtersExpanded }"><template #header><div class="table-toolbar"><b>设备选择</b><div class="commission-selection-actions"><span class="muted">{{ visibleDevices.length }} 台</span><el-button link type="primary" size="small" :aria-expanded="filtersExpanded" aria-controls="commission-device-filters" @click="filtersExpanded = !filtersExpanded">{{ filtersExpanded ? '收起筛选' : '展开筛选' }}</el-button></div></div></template>
          <div id="commission-device-filters" v-show="filtersExpanded" class="tree-filters"><el-select v-model="filters.region" clearable placeholder="全部区域" aria-label="所属区域"><el-option v-for="item in regions" :key="item" :label="item" :value="item" /></el-select><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="item in types" :key="item" :label="item" :value="item" /></el-select><el-input v-model="filters.keyword" clearable placeholder="搜索设备名称 / 编号" /></div>
          <div class="commission-device-list"><details v-for="[region,items] in deviceGroups" :key="region" open class="device-tree-group"><summary>{{ region }}<span>{{ items.length }}</span></summary><button v-for="item in items" :key="item.device_id" type="button" class="device-tree-item" :class="{active: item.device_id===selectedDeviceId}" :disabled="selectionLocked" @click="selectedDeviceId=item.device_id"><span class="device-tree-copy"><b>{{ item.name }}</b><small :title="item.device_no">{{ formatDeviceNo(item.device_no) }}</small></span></button></details><el-empty v-if="!deviceGroups.length" description="暂无匹配设备" :image-size="56" /></div>
          <p v-if="maintenanceId && selectedDeviceId" class="tree-note">已定位运维待办设备；返回待办列表可选择其他待办。</p><p v-else-if="!maintenanceId && selectionLocked" class="tree-note">当前任务结束或取消后可切换设备。</p>
        </el-card>
        <el-card><template #header><b>设备信息</b></template><el-empty v-if="!currentDevice" description="暂无可调测设备" :image-size="56" /><dl v-else class="commission-details"><dt>设备名称</dt><dd>{{ currentDevice.name }}</dd><dt>设备类型</dt><dd>{{ display(currentDevice.device_type_name) }}</dd><dt>设备编号</dt><dd :title="currentDevice.device_no">{{ formatDeviceNo(currentDevice.device_no) }}</dd><dt>设备型号</dt><dd>{{ display(currentDevice.model) }}</dd><dt>所属区域</dt><dd>{{ display(currentDevice.region_name) }}</dd><dt>供应商</dt><dd>{{ display(currentDevice.vendor) }}</dd><dt>数据来源</dt><dd>{{ currentDevice.simulated?'模拟数据':({live:'真实链路',replay:'回放数据'})[currentDevice.source_mode] || '未登记' }}</dd></dl></el-card>
      </div>
      <el-card class="commission-config"><template #header><div class="table-toolbar"><b>参数配置</b><span class="muted">{{ active?.commission_no || '尚未创建任务' }}</span></div></template>
        <template v-if="taskSupported || active">
          <h3 class="config-section-title">网络参数</h3>
          <p v-if="mqttTask" class="tree-note">MQTT 设备主动上报：平台核对自身与 MQTT 服务器的会话、设备主题订阅和最近上报，不与设备直连，也不下发指令。</p>
          <p v-else-if="connectionHidden" class="tree-note">当前账号没有设备运维（devices.op）权限，看不到设备登记的连接参数；建立连接仍按登记参数进行，保存配置时请按现场资料填写本次调测参数。</p>
          <el-form :model="config" label-position="top" class="commission-form"><template v-if="mqttTask"><el-form-item label="MQTT 服务器地址"><el-input :model-value="mqttEndpoint?.host || ''" disabled placeholder="建立连接后显示平台 MQTT 服务器" /></el-form-item><el-form-item label="端口"><el-input :model-value="mqttEndpoint?.port == null ? '' : String(mqttEndpoint.port)" disabled placeholder="建立连接后显示" /></el-form-item></template><template v-else><el-form-item label="主机 / IP 地址"><el-input v-model="config.host" :disabled="!canOperate || active?.status!=='CONNECTED'" placeholder="建立连接后配置" /></el-form-item><el-form-item label="端口"><el-input-number v-model="config.port" :min="1" :max="65535" :disabled="!canOperate || active?.status!=='CONNECTED'" controls-position="right" /></el-form-item></template><h3 class="config-section-title">接口与协议</h3><el-form-item label="传输方式"><el-input :model-value="mqttTask ? mqttTransport : config.transport" disabled /></el-form-item><el-form-item label="接入协议"><el-input :model-value="protocolLabel(active || currentDevice)" disabled /></el-form-item><h3 class="config-section-title">通信设置</h3><el-form-item v-if="mqttTask" label="判定时效"><el-input :model-value="mqttFreshness" disabled /></el-form-item><el-form-item v-else label="超时（ms）"><el-input-number v-model="config.timeout_millis" :min="100" :disabled="!canOperate || active?.status!=='CONNECTED'" controls-position="right" /></el-form-item></el-form>
          <div class="commission-action-bar">
            <el-button v-if="maintenanceId && active && !linkedToMaintenance" :disabled="!maintenance.can('LINK_COMMISSION') || actionBusy || !taskSourceAllowed" @click="linkCommission()">关联当前调测任务</el-button>
            <el-button v-if="!active || terminal.has(active.status)" type="primary" :disabled="!canOperate || !selectedDeviceId || !taskSupported || !deviceSourceAllowed" :loading="actionBusy" @click="createTask">创建新任务</el-button>
            <el-button v-else-if="active.status==='CREATED'" type="primary" :disabled="!canOperate || !taskSourceAllowed" :loading="actionBusy" @click="connectTask">建立连接</el-button>
            <el-button v-else-if="active.status==='CONNECTED'" type="primary" :disabled="!canOperate || !taskSourceAllowed" :loading="actionBusy" @click="saveConfig">保存配置</el-button>
            <el-button v-else-if="active.status==='READY'" type="primary" :disabled="!canOperate || !taskSourceAllowed" :loading="actionBusy" @click="startTask">开始协议调测</el-button>
            <el-button v-else type="primary" disabled>{{ statusText(active.status,isTestSource(active)) }}</el-button>
            <el-button v-if="active && !terminal.has(active.status)" :disabled="!canOperate || actionBusy" @click="cancelTask">取消任务</el-button>
            <el-button v-if="report" @click="viewReport()">查看完整报告</el-button>
          </div>
          <p v-if="isSimulation && simulationAllowed" class="tree-note">当前环境允许模拟调测，结果为非正式验证，不能代替现场验收。</p>
          <p v-else-if="!taskSourceAllowed" class="tree-note">当前未获模拟调测许可，测试来源仅供查看历史报告。</p>
          <p class="tree-note">配置仅保存到本次任务；调测通过不代表现场验收完成。</p>
        </template>
        <el-alert v-else-if="information && !taskSupported" title="此协议使用主动上报" description="在右侧查看连接状态，展开设备上报参数可核对工参。" type="info" :closable="false" show-icon />
        <el-empty v-else description="请选择设备并等待能力信息加载" :image-size="72" />
        <ul v-if="informationNotes.length" class="tree-note commission-capability-notes" aria-label="协议能力说明">
          <li v-for="(note, index) in informationNotes" :key="index">{{ note }}</li>
        </ul>
        <CommissionDeviceStatus :information="information" parameters />
      </el-card>
      <el-card class="commission-results"><template #header><div class="table-toolbar"><b>实时调测结果</b><el-tag :type="tagType(active?.status,isTestSource(active))" effect="plain">{{ active ? statusText(active.status,isTestSource(active)) : '未开始' }}</el-tag></div></template>
        <div class="table-toolbar connection-heading"><h3 class="config-section-title">连接状态</h3><el-button link type="primary" :loading="informationLoading" @click="loadInformation">刷新信息</el-button></div>
        <el-alert v-if="informationError" :title="informationError" type="error" :closable="false" show-icon />
        <CommissionDeviceStatus v-else :information="information" />
        <dl v-if="active" class="commission-details task-details"><dt>当前状态</dt><dd>{{ active ? statusText(active.status,isTestSource(active)) : '未创建任务' }}</dd><dt>开始时间</dt><dd>{{ formatTime(active?.started_at) }}</dd><dt>结束时间</dt><dd>{{ formatTime(active?.finished_at) }}</dd><dt>调测耗时</dt><dd>{{ active ? duration(active) : '—' }}</dd></dl>
        <div class="table-toolbar"><h3 class="config-section-title">测试日志</h3><span class="muted">{{ events.length }} 条</span></div>
        <div class="commission-log" aria-live="polite"><article v-for="item in [...events].reverse()" :key="item.event_seq"><time>{{ formatTime(item.occurred_at) }}</time><b v-if="item.simulated">模拟事件</b><p>{{ item.message }}</p></article><el-empty v-if="!events.length" description="等待调测事件" :image-size="60" /></div>
        <h3 class="config-section-title">接口响应结果</h3><template v-if="report"><el-tag :type="tagType(report.status,isTestSource(report))" effect="plain">{{ statusText(report.status,isTestSource(report)) }}</el-tag><p class="tree-note">{{ report.warning }}</p><el-button link type="primary" @click="viewReport()">查看详细报告</el-button></template><p v-else class="tree-note">等待实际设备回执；超时或结果未知不会自动视为通过。</p>
      </el-card>
    </div>
    <MaintenanceWorkflowPanel v-if="workflow" section="progress" :workflow="workflow" :busy="maintenanceBusy" :pending="!!maintenancePending" :locked="workflowLocked" @action="maintenanceAction" @report="viewReport" />
    <el-card><template #header><div class="table-toolbar"><b>联调记录</b><el-button link type="primary" :loading="recordsLoading" @click="loadRecords">刷新记录</el-button></div></template>
      <ErrorAlert :message="recordsError" @retry="loadRecords" />
      <el-table v-loading="recordsLoading" :data="records.items" empty-text="当前设备暂无联调记录"><el-table-column prop="commission_no" label="任务编号" min-width="170" /><el-table-column prop="device_name" label="设备名称" min-width="150" /><el-table-column prop="device_type_name" label="设备类型" width="100" /><el-table-column label="开始时间" min-width="175"><template #default="{row}">{{ formatTime(row.started_at) }}</template></el-table-column><el-table-column label="结束时间" min-width="175"><template #default="{row}">{{ formatTime(row.finished_at) }}</template></el-table-column><el-table-column label="耗时" width="90"><template #default="{row}">{{ duration(row) }}</template></el-table-column><el-table-column label="调测结果" width="110"><template #default="{row}"><el-tag :type="tagType(row.status,isTestSource(row))" effect="plain">{{ statusText(row.status,isTestSource(row)) }}</el-tag></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!['PASSED','FAILED','UNTESTABLE'].includes(row.status)" @click="viewReport(row)">查看报告</el-button></template></el-table-column></el-table>
      <div class="pagination-row"><span>共 {{ records.total }} 条</span><el-pagination v-model:current-page="records.page" :page-size="records.size" :total="records.total" layout="prev, pager, next" @current-change="loadRecords" /></div>
    </el-card>
    <el-dialog v-model="reportDialog" :title="`调测报告 · ${displayedReport?.commission_no||''}`" width="min(820px, 94vw)"><el-alert :title="displayedReport?.warning||'报告仅供在线查看。'" type="warning" show-icon :closable="false" /><pre class="json-block" style="margin-top:14px">{{ JSON.stringify(displayedReport, null, 2) }}</pre><template #footer><el-button type="primary" @click="reportDialog=false">关闭</el-button></template></el-dialog>
    </template>
  </section>
</template>

<style scoped>
.commission-workspace { display: grid; grid-template-columns: 253px minmax(360px, 1fr) 320px; gap: 12px; align-items: stretch; }
.commission-column { display: grid; grid-template-rows: auto auto; align-content: start; gap: 12px; min-width: 0; }
.commission-workspace > .el-card { min-width: 0; }
.commission-steps :deep(.el-step__title) { font-size: 14px; }
.commission-steps :deep(.el-step__description) { font-size: 12px; }
.commission-device-list { max-height: 360px; overflow: auto; }
.commission-selection-actions { display: flex; align-items: center; gap: 8px; }
.commission-selection-actions .muted { font-size: 12px; white-space: nowrap; }
.commission-selection.filters-collapsed .commission-device-list { max-height: 444px; }
.commission-selection .device-tree-copy { overflow: visible; }
.commission-selection .device-tree-copy b, .commission-selection .device-tree-copy small { overflow: visible; text-overflow: clip; white-space: normal; overflow-wrap: anywhere; }
.commission-selection .tree-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.commission-selection .tree-filters > .el-input { grid-column: 1 / -1; }
.commission-details { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 8px 12px; font-size: 12px; line-height: 1.6; margin: 0; }
.commission-details dt { color: #8490a1; }
.commission-details dd { margin: 0; overflow-wrap: anywhere; }
.config-section-title { font-size: 13px; color: #526780; margin: 18px 0 14px; }
.config-section-title:first-child { margin-top: 0; }
.commission-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 20px; }
.commission-form .config-section-title { grid-column: 1 / -1; margin-top: 4px; }
.commission-form .el-form-item { min-width: 0; margin-bottom: 16px; }
.connection-heading { margin-bottom: 12px; }
.connection-heading .config-section-title { margin: 0; }
.task-details { padding-top: 12px; border-top: 1px solid #e7edf3; }
.commission-config :deep(.el-card__body) { display: flex; flex-direction: column; }
.commission-capability-notes { margin: 12px 0 0; padding-left: 20px; overflow-wrap: anywhere; }
.commission-capability-notes li + li { margin-top: 4px; }
.commission-steps :deep(.el-card__body) { padding: 14px 10px; }
.commission-reference :deep(.device-tree-item) { padding: 7px 10px; }
.commission-form .el-input-number { width: 100%; }
.commission-action-bar { display: flex; flex-wrap: wrap; gap: 8px; border-top: 1px solid #edf0f4; padding-top: 16px; }
.commission-action-bar .el-button { margin-left: 0; }
.commission-log { padding: 12px; background: #f8fafc; border: 1px solid #e5eaf0; border-radius: 6px; height: 180px; overflow: auto; }
.commission-log article { border-bottom: 1px solid #e7edf3; padding: 8px 0; font-size: 12px; }
.commission-log time { display: block; color: #8995a5; margin-bottom: 5px; }
.commission-log p { line-height: 1.7; margin-bottom: 0; }
.commission-log :deep(.el-empty) { padding: 14px 0; }
@media (max-width: 1250px) { .commission-workspace { grid-template-columns: 264.5px minmax(0,1fr); } .commission-results { grid-column: 1 / -1; } }
@media (max-width: 720px) { .commission-form { grid-template-columns: minmax(0, 1fr); } .commission-workspace { grid-template-columns: minmax(0,1fr); } .commission-results { grid-column: auto; } .commission-steps { overflow: auto; } .commission-steps :deep(.el-steps) { min-width: 600px; } }
</style>
