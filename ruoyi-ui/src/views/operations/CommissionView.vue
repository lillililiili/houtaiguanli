<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';
import { useAuthStore } from '@/stores/auth.js';
import { display, formatTime, statusType } from '@/utils/format.js';

const auth = useAuthStore();
const canOperate = computed(() => auth.hasPermission('commissioning.op'));
const devices = ref([]);
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
const taskSupported = computed(() => information.value?.device_id === selectedDeviceId.value && information.value?.task_supported);
const isSimulation = computed(() => Boolean(active.value?.simulated || currentDevice.value?.simulated));
const statusMeta = {
  CREATED: ['待连接', 'info'], CONNECTING: ['连接中', 'warning'], CONNECTED: ['已连接', 'success'], READY: ['待调测', 'warning'],
  RUNNING: ['调测中', 'warning'], PASSED: ['通过', 'success'], FAILED: ['失败', 'danger'], UNTESTABLE: ['不可判定', 'warning'], CANCELLED: ['已取消', 'info']
};
const statusText = (value, simulated = false) => value === 'PASSED' && simulated ? '模拟通过' : (statusMeta[value]?.[0] || display(value));
const tagType = value => statusMeta[value]?.[1] || statusType(value);
const protocolLabel = task => ({ RADAR_TCP_V3_0_0: 'T02/兼容机扫雷达 TCP', COUNTERMEASURE_TCP_4CH_V2_0: '固定式四通道网络控制器', LINGYUN_MQTT_V8_6: '凌云 MQTT V8.6', EO_EDGE_MQTT_20250826: '光电边端 MQTT' })[task?.protocol_code] || task?.protocol_code || '未配置';
const steps = ['创建任务', '建立连接', '保存配置', '执行调测', '形成报告'];
const stepIndex = computed(() => {
  const value = active.value?.status;
  if (!value || value === 'CREATED') return 0;
  if (value === 'CONNECTING') return 1;
  if (value === 'CONNECTED') return 2;
  if (value === 'READY' || value === 'RUNNING') return 3;
  return 4;
});

function resetConfig() { Object.assign(config, { transport: 'TCP', host: '', port: null, timeout_millis: 3000 }); }
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
  if (!deviceId || !auth.hasPermission('devices.op')) { resetConfig(); return; }
  try {
    const detail = await deviceApi.detail(deviceId);
    if (!alive || request !== connectionRequest || selectedDeviceId.value !== deviceId) return;
    const connection = detail.connection || {};
    Object.assign(config, { transport: connection.transport || 'TCP', host: connection.host || '', port: connection.port ?? null, timeout_millis: connection.timeout_millis ?? 3000 });
  } catch (e) { if (alive && request === connectionRequest) { resetConfig(); error.value = e.message || '连接配置读取失败'; } }
}

async function loadDevices() {
  const items = [];
  let page = 1;
  let hasMore = true;
  while (hasMore) {
    const data = await deviceApi.list({ page, size: 100, enabled: true, sort: 'device_no_asc' });
    items.push(...(data.items || []));
    hasMore = Boolean(data.items?.length) && items.length < (data.total ?? items.length);
    page++;
  }
  devices.value = items;
  if (!devices.value.some(item => item.device_id === selectedDeviceId.value)) selectedDeviceId.value = devices.value[0]?.device_id || '';
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
    error.value = '';
  } catch (e) { error.value = e.message || '任务轮询失败'; }
  finally { pollInFlight = false; }
}
async function runAction(action, success) {
  if (actionBusy.value) return;
  actionBusy.value = true; error.value = '';
  try { active.value = await action(); ElMessage.success(success); await pollActive(); }
  catch (e) { error.value = e.message || '操作失败'; ElMessage.error(error.value); }
  finally { actionBusy.value = false; }
}
async function createTask() {
  if (!selectedDeviceId.value || !taskSupported.value) return;
  await runAction(async () => {
    const task = await commissionApi.create({ device_id: selectedDeviceId.value });
    events.value = []; afterSeq.value = 0; report.value = null; await applyConnection(selectedDeviceId.value); return task;
  }, '调测任务已创建');
}
function connectTask() { runAction(() => commissionApi.connect(active.value.commission_id, active.value.version), isSimulation.value ? '正在通过模拟适配器建立连接' : '正在建立连接'); }
function saveConfig() {
  if (!config.host?.trim() || !config.port) { ElMessage.error('主机和端口为必填'); return; }
  runAction(() => commissionApi.configure(active.value.commission_id, { version: active.value.version, ...config, host: config.host.trim() }), '配置快照已保存');
}
function startTask() { runAction(() => commissionApi.start(active.value.commission_id, active.value.version), isSimulation.value ? '模拟调测已进入持久化队列' : '协议调测已进入持久化队列'); }
function cancelTask() { runAction(() => commissionApi.cancel(active.value.commission_id, active.value.version), '任务已取消'); }
async function loadExistingTask(deviceId) {
  const request = ++existingRequest;
  if (!deviceId || (active.value && !terminal.has(active.value.status))) return;
  try {
    const data = await commissionApi.list({ device_id: deviceId, page: 1, size: 5 });
    if (!alive || request !== existingRequest || selectedDeviceId.value !== deviceId) return;
    active.value = (data.items || []).find(item => !terminal.has(item.status)) || null;
    events.value = []; afterSeq.value = 0; report.value = null;
    if (active.value) await loadEvents();
  } catch (e) { if (alive && request === existingRequest) error.value = e.message || '已有任务读取失败'; }
}

watch(selectedDeviceId, id => {
  information.value = null; informationError.value = '';
  void loadInformation();
  if (active.value && !terminal.has(active.value.status)) return;
  active.value = null; events.value = []; afterSeq.value = 0; report.value = null;
  void applyConnection(id); void loadExistingTask(id);
});
onMounted(async () => {
  await bootstrap();
  if (!alive) return;
  pollTimer = window.setInterval(pollActive, 2000);
  informationTimer = window.setInterval(() => { if (!informationLoading.value) void loadInformation(); }, 10000);
});
onBeforeUnmount(() => { alive = false; ++informationRequest; window.clearInterval(pollTimer); window.clearInterval(informationTimer); });
</script>

<template>
  <section class="page-stack">
    <PageHeader title="设备接入调测" description="调测按服务端状态机逐步推进；连接成功不等于设备业务能力已通过现场验收。">
      <el-tag v-if="active" :type="tagType(active.status)" effect="plain">{{ statusText(active.status, active.simulated) }}</el-tag>
    </PageHeader>
    <ErrorAlert :message="error" @retry="bootstrap" />
    <div v-loading="loading" class="commission-information-layout">
      <el-card><template #header><div class="table-toolbar"><b>调测对象</b><span class="muted">启用设备</span></div></template>
        <el-form label-position="top">
          <el-form-item label="选择设备"><el-select v-model="selectedDeviceId" filterable :disabled="Boolean(active&&!terminal.has(active.status))"><el-option v-for="item in devices" :key="item.device_id" :label="`${item.device_no} · ${item.name}`" :value="item.device_id" /></el-select></el-form-item>
        </el-form>
        <el-empty v-if="!currentDevice" description="暂无可调测设备" />
        <p v-else class="muted">{{ currentDevice.device_type_name }} · {{ currentDevice.device_no }}</p>
        <p v-if="information && !taskSupported" class="muted">此协议使用主动上报，可在下方查看工参和接收诊断；当前不支持创建调测任务。</p>
        <div v-if="taskSupported" class="card-actions"><el-button type="primary" :disabled="!canOperate||!selectedDeviceId||Boolean(active&&!terminal.has(active.status))" :loading="actionBusy" @click="createTask">创建新任务</el-button></div>
      </el-card>

      <DeviceInformationPanel purpose="commission" :information="information" :loading="informationLoading" :error="informationError" @refresh="loadInformation" />

      <el-card v-if="taskSupported || active"><template #header><div class="table-toolbar"><b>任务进度</b><span class="mono muted">{{ active?.commission_no || '尚未创建任务' }}</span></div></template>
        <el-steps :active="stepIndex" finish-status="success" align-center><el-step v-for="item in steps" :key="item" :title="item" /></el-steps>
        <el-empty v-if="!active" description="请先选择设备并创建任务" />
        <template v-else>
          <el-descriptions :column="2" border class="detail-section"><el-descriptions-item label="当前状态"><el-tag :type="tagType(active.status)" effect="plain">{{ statusText(active.status, active.simulated) }}</el-tag></el-descriptions-item><el-descriptions-item label="接入协议">{{ protocolLabel(active) }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ formatTime(active.created_at) }}</el-descriptions-item><el-descriptions-item label="来源">{{ active.simulated?'模拟适配器':'真实设备链路' }}</el-descriptions-item></el-descriptions>
          <div class="card-actions"><el-button type="primary" :disabled="!canOperate||active.status!=='CREATED'" :loading="actionBusy" @click="connectTask">建立连接</el-button><el-button type="primary" :disabled="!canOperate||active.status!=='CONNECTED'" :loading="actionBusy" @click="saveConfig">保存配置</el-button><el-button type="primary" :disabled="!canOperate||active.status!=='READY'" :loading="actionBusy" @click="startTask">开始协议调测</el-button><el-button :disabled="!canOperate||terminal.has(active.status)" @click="cancelTask">取消任务</el-button></div>
        </template>
        <div v-if="active?.status==='CONNECTED'" class="detail-section"><h3>本次调测配置</h3><el-form :model="config" label-position="top" class="form-grid"><el-form-item label="传输方式"><el-input v-model="config.transport" disabled /></el-form-item><el-form-item label="主机"><el-input v-model="config.host" :disabled="!canOperate" /></el-form-item><el-form-item label="端口"><el-input-number v-model="config.port" :min="1" :max="65535" :disabled="!canOperate" /></el-form-item><el-form-item label="超时（ms）"><el-input-number v-model="config.timeout_millis" :min="100" :disabled="!canOperate" /></el-form-item></el-form></div>
      </el-card>

      <el-card v-if="active || events.length"><template #header><div class="table-toolbar"><b>调测事件</b><span class="muted">{{ events.length }} 条</span></div></template>
        <ul v-if="events.length" class="log-list"><li v-for="item in [...events].reverse()" :key="item.event_seq"><b>{{ item.stage_code || '任务事件' }}</b><p>{{ item.message }}</p><time>#{{ item.event_seq }} · {{ formatTime(item.occurred_at) }}{{ item.simulated?' · 模拟事件':'' }}</time></li></ul><el-empty v-else description="暂无任务事件" />
        <template v-if="report"><div class="detail-section"><h3>调测结论</h3><el-tag :type="tagType(report.status)" effect="plain">{{ statusText(report.status, report.simulated) }}</el-tag><p>{{ report.warning }}</p><div class="card-actions"><el-button type="primary" @click="reportDialog=true">查看完整报告</el-button></div></div></template>
      </el-card>
    </div>

    <el-dialog v-model="reportDialog" :title="`调测报告 · ${report?.commission_no||''}`" width="820px"><el-alert :title="report?.warning||'报告仅供在线查看。'" type="warning" show-icon :closable="false" /><pre class="json-block" style="margin-top:14px">{{ JSON.stringify(report, null, 2) }}</pre><template #footer><el-button type="primary" @click="reportDialog=false">关闭</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.commission-information-layout { display: grid; gap: 16px; min-width: 0; }
.commission-information-layout > .el-card { min-width: 0; }
.commission-information-layout .el-select { width: min(100%, 540px); }
</style>
