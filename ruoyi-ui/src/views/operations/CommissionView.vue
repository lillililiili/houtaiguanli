<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';
import { useAuthStore } from '@/stores/auth.js';
import './operations-reference.css';
import { display, formatTime, statusType } from '@/utils/format.js';

const auth = useAuthStore();
const canOperate = computed(() => auth.hasPermission('commissioning.op'));
const devices = ref([]);
const filters = reactive({ keyword: '', region: '', type: '' });
const records = reactive({ items: [], page: 1, size: 10, total: 0 });
const recordsLoading = ref(false);
const recordsError = ref('');
let recordsRequest = 0;
const displayedReport = ref(null);
const regions = computed(() => [...new Set(devices.value.map(item => item.region_name).filter(Boolean))]);
const types = computed(() => [...new Set(devices.value.map(item => item.device_type_name).filter(Boolean))]);
const deviceGroups = computed(() => Object.entries(devices.value.filter(item =>
  (!filters.region || item.region_name === filters.region) && (!filters.type || item.device_type_name === filters.type)
  && (!filters.keyword || `${item.device_no} ${item.name}`.toLowerCase().includes(filters.keyword.toLowerCase()))
).reduce((groups, item) => { (groups[item.region_name || '未登记区域'] ||= []).push(item); return groups; }, {})));
const selectionLocked = computed(() => actionBusy.value || Boolean(active.value && !terminal.has(active.value.status)));

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
const steps = ['设备接入', '建立连接', '参数配置', '协议调测', '结果确认'];
const stepIndex = computed(() => {
  const value = active.value?.status;
  if (!value) return 0;
  if (value === 'CREATED') return 1;
  if (value === 'CONNECTING') return 1;
  if (value === 'CONNECTED') return 2;
  if (value === 'READY' || value === 'RUNNING') return 3;
  if (value === 'PASSED') return 5;
  if (['FAILED', 'UNTESTABLE', 'CANCELLED'].includes(value)) return active.value?.started_at ? 3 : 1;
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
      if (alive && active.value?.commission_id === id) { report.value = result; void loadRecords(); }
    }
    error.value = '';
  } catch (e) { error.value = e.message || '任务轮询失败'; }
  finally { pollInFlight = false; }
}
async function runAction(action, success) {
  if (actionBusy.value) return;
  actionBusy.value = true; error.value = '';
  try { active.value = await action(); ElMessage.success(success); await pollActive(); await loadRecords(); }
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

watch(selectedDeviceId, id => {
  information.value = null; informationError.value = '';
  ++recordsRequest; records.items = []; records.total = 0; records.page = 1; recordsError.value = ''; recordsLoading.value = false;
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
  <section class="page-stack operation-page commission-reference">
    <PageHeader title="设备接入调测" description="选择设备、配置连接，查看调测结果和历史记录。">
      <el-tag v-if="active" :type="tagType(active.status)" effect="plain">{{ statusText(active.status, active.simulated) }}</el-tag>
    </PageHeader>
    <ErrorAlert :message="error" @retry="bootstrap" />
    <el-card class="commission-steps"><el-steps :active="stepIndex" :process-status="active?.status==='FAILED'?'error':'process'" :finish-status="active?.status==='CANCELLED' ? 'wait' : 'success'" align-center><el-step v-for="(item,index) in steps" :key="item" :title="item" :description="['选择设备并创建任务','建立设备通信链路','保存本次调测参数','协议响应与数据校验','查看结果与调测报告'][index]" /></el-steps></el-card>
    <div v-loading="loading" class="commission-workspace">
      <div class="commission-column">
        <el-card class="commission-selection"><template #header><div class="table-toolbar"><b>设备选择</b><span class="muted">{{ devices.length }} 台</span></div></template>
          <div class="tree-filters"><el-select v-model="filters.region" clearable placeholder="全部区域" aria-label="所属区域"><el-option v-for="item in regions" :key="item" :label="item" :value="item" /></el-select><el-select v-model="filters.type" clearable placeholder="全部类型" aria-label="设备类型"><el-option v-for="item in types" :key="item" :label="item" :value="item" /></el-select><el-input v-model="filters.keyword" clearable placeholder="搜索设备名称 / 编号" /></div>
          <div class="commission-device-list"><details v-for="[region,items] in deviceGroups" :key="region" open class="device-tree-group"><summary>{{ region }}<span>{{ items.length }}</span></summary><button v-for="item in items" :key="item.device_id" type="button" class="device-tree-item" :class="{active: item.device_id===selectedDeviceId}" :disabled="selectionLocked" @click="selectedDeviceId=item.device_id"><span class="device-tree-copy"><b>{{ item.name }}</b><small>{{ item.device_no }}</small></span></button></details><el-empty v-if="!deviceGroups.length" description="暂无匹配设备" :image-size="56" /></div>
          <p v-if="selectionLocked" class="tree-note">当前任务结束或取消后可切换设备。</p>
        </el-card>
        <el-card><template #header><b>设备信息</b></template><el-empty v-if="!currentDevice" description="暂无可调测设备" :image-size="56" /><dl v-else class="commission-details"><dt>设备名称</dt><dd>{{ currentDevice.name }}</dd><dt>设备类型</dt><dd>{{ display(currentDevice.device_type_name) }}</dd><dt>设备编号</dt><dd>{{ currentDevice.device_no }}</dd><dt>设备型号</dt><dd>{{ display(currentDevice.model) }}</dd><dt>所属区域</dt><dd>{{ display(currentDevice.region_name) }}</dd><dt>供应商</dt><dd>{{ display(currentDevice.vendor) }}</dd><dt>数据来源</dt><dd>{{ currentDevice.simulated?'模拟数据':({live:'真实链路',replay:'回放数据'})[currentDevice.source_mode] || '未登记' }}</dd></dl></el-card>
      </div>
      <el-card class="commission-config"><template #header><div class="table-toolbar"><b>参数配置</b><span class="muted">{{ active?.commission_no || '尚未创建任务' }}</span></div></template>
        <template v-if="taskSupported || active">
          <h3 class="config-section-title">网络参数</h3>
          <el-form :model="config" label-position="top" class="commission-form"><el-form-item label="主机 / IP 地址"><el-input v-model="config.host" :disabled="!canOperate || active?.status!=='CONNECTED'" placeholder="建立连接后配置" /></el-form-item><el-form-item label="端口"><el-input-number v-model="config.port" :min="1" :max="65535" :disabled="!canOperate || active?.status!=='CONNECTED'" controls-position="right" /></el-form-item><h3 class="config-section-title">接口与协议</h3><el-form-item label="传输方式"><el-input v-model="config.transport" disabled /></el-form-item><el-form-item label="接入协议"><el-input :model-value="protocolLabel(active || currentDevice)" disabled /></el-form-item><h3 class="config-section-title">通信设置</h3><el-form-item label="超时（ms）"><el-input-number v-model="config.timeout_millis" :min="100" :disabled="!canOperate || active?.status!=='CONNECTED'" controls-position="right" /></el-form-item></el-form>
          <div class="commission-action-bar">
            <el-button v-if="!active || terminal.has(active.status)" type="primary" :disabled="!canOperate || !selectedDeviceId || !taskSupported" :loading="actionBusy" @click="createTask">创建新任务</el-button>
            <el-button v-else-if="active.status==='CREATED'" type="primary" :disabled="!canOperate" :loading="actionBusy" @click="connectTask">建立连接</el-button>
            <el-button v-else-if="active.status==='CONNECTED'" type="primary" :disabled="!canOperate" :loading="actionBusy" @click="saveConfig">保存配置</el-button>
            <el-button v-else-if="active.status==='READY'" type="primary" :disabled="!canOperate" :loading="actionBusy" @click="startTask">开始协议调测</el-button>
            <el-button v-else type="primary" disabled>{{ statusText(active.status) }}</el-button>
            <el-button v-if="active && !terminal.has(active.status)" :disabled="!canOperate || actionBusy" @click="cancelTask">取消任务</el-button>
            <el-button v-if="report" @click="viewReport()">查看完整报告</el-button>
          </div>
          <p class="tree-note">配置仅保存到本次任务；调测通过不代表现场验收完成。</p>
        </template>
        <el-alert v-else-if="information && !taskSupported" title="此协议使用主动上报" description="当前不支持创建调测任务；请在下方接入诊断信息中核对工参、报文字段和接收状态。" type="info" :closable="false" show-icon />
        <el-empty v-else description="请选择设备并等待能力信息加载" :image-size="72" />
      </el-card>
      <el-card class="commission-results"><template #header><div class="table-toolbar"><b>实时调测结果</b><el-tag :type="tagType(active?.status)" effect="plain">{{ active ? statusText(active.status,active.simulated) : '未开始' }}</el-tag></div></template>
        <dl class="commission-details"><dt>当前状态</dt><dd>{{ active ? statusText(active.status,active.simulated) : '未创建任务' }}</dd><dt>开始时间</dt><dd>{{ formatTime(active?.started_at) }}</dd><dt>结束时间</dt><dd>{{ formatTime(active?.finished_at) }}</dd><dt>调测耗时</dt><dd>{{ active ? duration(active) : '—' }}</dd></dl>
        <div class="table-toolbar"><h3 class="config-section-title">测试日志</h3><span class="muted">{{ events.length }} 条</span></div>
        <div class="commission-log" aria-live="polite"><article v-for="item in [...events].reverse()" :key="item.event_seq"><time>{{ formatTime(item.occurred_at) }}</time><b>{{ item.stage_code || '任务事件' }}{{ item.simulated?' · 模拟事件':'' }}</b><p>{{ item.message }}</p></article><el-empty v-if="!events.length" description="等待调测事件" :image-size="60" /></div>
        <h3 class="config-section-title">接口响应结果</h3><template v-if="report"><el-tag :type="tagType(report.status)" effect="plain">{{ statusText(report.status,report.simulated) }}</el-tag><p class="tree-note">{{ report.warning }}</p><pre class="json-block">{{ JSON.stringify(report.results,null,2) }}</pre></template><p v-else class="tree-note">任务完成后显示服务端报告结果。</p>
      </el-card>
    </div>
    <el-card><template #header><div class="table-toolbar"><b>联调记录</b><el-button link type="primary" :loading="recordsLoading" @click="loadRecords">刷新记录</el-button></div></template>
      <ErrorAlert :message="recordsError" @retry="loadRecords" />
      <el-table v-loading="recordsLoading" :data="records.items" empty-text="当前设备暂无联调记录"><el-table-column prop="commission_no" label="任务编号" min-width="170" /><el-table-column prop="device_name" label="设备名称" min-width="150" /><el-table-column prop="device_type_name" label="设备类型" width="100" /><el-table-column label="开始时间" min-width="175"><template #default="{row}">{{ formatTime(row.started_at) }}</template></el-table-column><el-table-column label="结束时间" min-width="175"><template #default="{row}">{{ formatTime(row.finished_at) }}</template></el-table-column><el-table-column label="耗时" width="90"><template #default="{row}">{{ duration(row) }}</template></el-table-column><el-table-column label="调测结果" width="110"><template #default="{row}"><el-tag :type="tagType(row.status)" effect="plain">{{ statusText(row.status,row.simulated) }}</el-tag></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!['PASSED','FAILED','UNTESTABLE'].includes(row.status)" @click="viewReport(row)">查看报告</el-button></template></el-table-column></el-table>
      <div class="pagination-row"><span>共 {{ records.total }} 条</span><el-pagination v-model:current-page="records.page" :page-size="records.size" :total="records.total" layout="prev, pager, next" @current-change="loadRecords" /></div>
    </el-card>
    <DeviceInformationPanel purpose="commission" :information="information" :loading="informationLoading" :error="informationError" @refresh="loadInformation" />
    <el-dialog v-model="reportDialog" :title="`调测报告 · ${displayedReport?.commission_no||''}`" width="min(820px, 94vw)"><el-alert :title="displayedReport?.warning||'报告仅供在线查看。'" type="warning" show-icon :closable="false" /><pre class="json-block" style="margin-top:14px">{{ JSON.stringify(displayedReport, null, 2) }}</pre><template #footer><el-button type="primary" @click="reportDialog=false">关闭</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.commission-workspace { display: grid; grid-template-columns: 250px minmax(290px, 1fr) minmax(330px, 1.1fr); gap: 14px; align-items: start; }
.commission-column { display: grid; gap: 14px; min-width: 0; }
.commission-workspace > .el-card { min-width: 0; }
.commission-steps :deep(.el-step__title) { font-size: 14px; }
.commission-steps :deep(.el-step__description) { font-size: 12px; }
.commission-device-list { max-height: 285px; overflow: auto; }
.commission-details { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 12px; font-size: 13px; line-height: 1.6; margin: 0; }
.commission-details dt { color: #8490a1; }
.commission-details dd { margin: 0; overflow-wrap: anywhere; }
.config-section-title { font-size: 13px; color: #526780; margin: 18px 0 14px; }
.config-section-title:first-child { margin-top: 0; }
.commission-form .el-input-number { width: 100%; }
.commission-action-bar { display: flex; flex-wrap: wrap; gap: 8px; border-top: 1px solid #edf0f4; padding-top: 16px; }
.commission-action-bar .el-button { margin-left: 0; }
.commission-log { padding: 12px; background: #f8fafc; border: 1px solid #e5eaf0; border-radius: 6px; height: 240px; overflow: auto; }
.commission-log article { border-bottom: 1px solid #e7edf3; padding: 8px 0; font-size: 12px; }
.commission-log time { display: block; color: #8995a5; margin-bottom: 5px; }
.commission-log p { line-height: 1.7; margin-bottom: 0; }
@media (max-width: 1180px) { .commission-workspace { grid-template-columns: 230px minmax(0,1fr); } .commission-results { grid-column: 1 / -1; } }
@media (max-width: 720px) { .commission-workspace { grid-template-columns: minmax(0,1fr); } .commission-results { grid-column: auto; } .commission-steps { overflow: auto; } .commission-steps :deep(.el-steps) { min-width: 600px; } }
</style>
