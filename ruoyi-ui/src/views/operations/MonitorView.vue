<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import * as echarts from 'echarts';
import PageHeader from '@/components/PageHeader.vue';
import OperationMetrics from './OperationMetrics.vue';
import './operations-reference.css';
import ErrorAlert from '@/components/ErrorAlert.vue';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import DeviceMaintenancePanel from './DeviceMaintenancePanel.vue';
import { deviceApi } from '@/api/devices.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';

const filters = reactive({ keyword: '', channel: '', type_code: '' });
const overview = ref({ total: 0, online: 0, offline: 0, abnormal: 0, unknown: 0, alarm: 0 });
const tree = ref([]);
const incidents = ref([]);
const incidentTotal = ref(0);
const severity = ref('');
const metricTab = ref('all');
const treeTruncated = ref(false);
const treeTotal = ref(0);
const filteredIncidents = computed(() => incidents.value.filter(item => !severity.value || item.severity === severity.value));
const selectedId = ref('');
const state = ref(null);
const history = ref([]);
const events = ref([]);
const eventSeq = ref(0);
const metricCode = ref('link_latency_ms');
const protocolStatus = ref(null);
const radarTargets = ref([]);
const information = ref(null);
const informationError = ref('');
const selectedError = ref('');
const loading = ref(false);
const selectedLoading = ref(false);
const paused = ref(false);
const error = ref('');
const chartEl = ref();
const maintenanceTasks = ref(null);
let chart;
let aggregateTimer;
let selectedTimer;
let aggregateInFlight = false;
let selectedInFlight = false;
let selectedPending = false;
let selectedGeneration = 0;
let alive = true;

const selected = computed(() => tree.value.find(item => item.device_id === selectedId.value));
const groups = computed(() => Object.entries(tree.value.reduce((result, item) => {
  (result[item.channel || '未分组'] ||= []).push(item); return result;
}, {})));
const rate = value => overview.value.total ? `${((value || 0) / overview.value.total * 100).toFixed(1)}%` : '—';
const metrics = computed(() => [
  { label: '设备总数', value: overview.value.total, icon: 'total', note: '当前权限范围内设备' },
  { label: '在线设备', value: overview.value.online, tone: 'green', icon: 'online', note: `在线率 ${rate(overview.value.online)}` },
  { label: '离线设备', value: overview.value.offline, tone: 'red', icon: 'offline', note: `离线率 ${rate(overview.value.offline)}` },
  { label: '异常设备', value: overview.value.abnormal, tone: 'amber', icon: 'abnormal', note: `异常率 ${rate(overview.value.abnormal)}` },
  { label: '告警设备', value: overview.value.alarm, tone: 'amber', icon: 'alarm', note: '包含在设备状态统计内' },
  { label: '状态未知', value: overview.value.unknown, tone: 'purple', icon: 'abnormal', note: '尚无有效状态上报' }
]);
const categoryCodes = {
  resource: ['cpu_pct', 'memory_pct', 'disk_pct', 'temperature_c', 'bandwidth_kbps'],
  signal: ['signal_dbm', 'signal_strength_dbm', 'snr_db'],
  link: ['link_latency_ms', 'packet_loss_pct', 'packet_loss_rate', 'jitter_ms']
};
const visibleMetrics = computed(() => (state.value?.metrics || []).filter(item => metricTab.value === 'all' || categoryCodes[metricTab.value]?.includes(item.code)));
function typeGroups(items) { return Object.entries(items.reduce((groups, item) => { (groups[item.device_type_name || '其他设备'] ||= []).push(item); return groups; }, {})); }

const metricOptions = computed(() => (state.value?.metrics || []).map(item => ({ value: item.code, label: `${metricLabel(item)}${item.unit ? `（${item.unit}）` : ''}` })));

const METRIC_LABELS = { link_latency_ms: '链路时延', packet_loss_pct: '丢包率', packet_loss_rate: '丢包率', signal_dbm: '信号强度', signal_strength_dbm: '信号强度', relay_state: '继电器状态字', temperature_c: '温度', cpu_pct: '处理器占用', memory_pct: '内存占用' };
const EVENT_LABELS = { CONNECTED: '设备上线', DISCONNECTED: '设备离线', STATE_CHANGED: '状态变化', METRIC_REPORTED: '指标上报', COMMAND_QUEUED: '指令排队', COMMAND_SENT: '指令下发', COMMAND_SUCCEEDED: '指令成功', COMMAND_FAILED: '指令失败', HEARTBEAT_TIMEOUT: '心跳超时' };
const SEVERITY_LABELS = { HIGH: '高', MEDIUM: '中', LOW: '低', CRITICAL: '严重' };
const SOURCE_LABELS = { 'mock-adapter': '模拟适配器', 'device-report': '设备上报', 'radar-direct': '雷达直连', mqtt: '消息接入' };
function metricLabel(item) { return item?.label || METRIC_LABELS[item?.code] || item?.code || '指标'; }
function metricValue(value) { return value === 'ON' ? '开' : value === 'OFF' ? '关' : display(value); }
function healthText(value) { return ({ GOOD: '良好', DEGRADED: '一般', BAD: '异常', UNKNOWN: '未知' })[value] || '未知'; }

async function loadAggregate(showBusy = false) {
  if (aggregateInFlight || paused.value) return;
  aggregateInFlight = true; if (showBusy) loading.value = true;
  void maintenanceTasks.value?.reload();
  try {
    const [summary, deviceTree, incidentPage] = await Promise.all([deviceApi.overview(), deviceApi.tree(filters), deviceApi.incidents({ page: 1, size: 20, stage: 'PENDING' })]);
    if (!alive) return;
    overview.value = summary; tree.value = deviceTree.items || []; treeTotal.value = deviceTree.total ?? tree.value.length; treeTruncated.value = Boolean(deviceTree.truncated); incidents.value = incidentPage.items || []; incidentTotal.value = incidentPage.total ?? incidents.value.length;
    if (!tree.value.some(item => item.device_id === selectedId.value)) selectedId.value = tree.value[0]?.device_id || '';
    error.value = '';
  } catch (e) { error.value = e.message || '实时监测数据加载失败'; }
  finally { loading.value = false; aggregateInFlight = false; }
}

async function loadSelected(showBusy = false, manual = false) {
  if (!alive || !selectedId.value || (paused.value && !manual)) return;
  if (selectedInFlight) { if (manual) selectedPending = true; return; }
  selectedInFlight = true; if (showBusy) selectedLoading.value = true;
  const deviceId = selectedId.value; const requestedMetric = metricCode.value;
  const generation = selectedGeneration;
  try {
    const [statusResult, informationResult] = await Promise.allSettled([Promise.all([
      deviceApi.state(deviceId), deviceApi.history(deviceId, { metric_code: requestedMetric, limit: 120 }),
      deviceApi.events({ device_id: deviceId, after_seq: eventSeq.value, limit: 100 }), deviceApi.protocolStatus(deviceId),
      selected.value?.protocol_code === 'RADAR_TCP_V3_0_0' ? deviceApi.targets({ device_id: deviceId, active: true, page: 1, size: 20 }) : Promise.resolve({ items: [] })
    ]), deviceApi.information(deviceId)]);
    if (!alive || generation !== selectedGeneration || deviceId !== selectedId.value || requestedMetric !== metricCode.value) return;
    information.value = informationResult.status === 'fulfilled' ? informationResult.value : null;
    informationError.value = informationResult.status === 'rejected' ? informationResult.reason?.message || '完整信息加载失败' : '';
    if (statusResult.status === 'fulfilled') {
      const [deviceState, points, eventPage, protocol, targetPage] = statusResult.value;
      state.value = deviceState; history.value = points.points || []; protocolStatus.value = protocol; radarTargets.value = targetPage.items || [];
      const merged = new Map([...events.value, ...(eventPage.items || [])].map(item => [item.event_seq, item]));
      events.value = [...merged.values()].sort((a, b) => a.event_seq - b.event_seq).slice(-100); eventSeq.value = eventPage.next_seq || eventSeq.value;
      selectedError.value = '';
    } else {
      state.value = null; history.value = []; protocolStatus.value = null; radarTargets.value = [];
      selectedError.value = statusResult.reason?.message || '所选设备状态加载失败';
    }
    await nextTick(); paintChart();
  }
  finally {
    selectedLoading.value = false; selectedInFlight = false;
    if (selectedPending) { selectedPending = false; void loadSelected(true, true); }
  }
}

function paintChart() {
  if (!chartEl.value || !history.value.length) { chart?.clear(); return; }
  if (!chartEl.value.clientWidth || !chartEl.value.clientHeight) {
    window.requestAnimationFrame(() => { if (alive) paintChart(); });
    return;
  }
  chart ||= echarts.init(chartEl.value);
  const metric = state.value?.metrics?.find(item => item.code === metricCode.value);
  chart.setOption({ animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches, color: ['#1677ff'],
    grid: { top: 30, right: 18, bottom: 28, left: 48 }, tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', boundaryGap: false, data: history.value.map(item => new Date(item.received_at).toLocaleTimeString('zh-CN', { hour12: false })) },
    yAxis: { type: 'value', name: metric?.unit || '', splitLine: { lineStyle: { color: '#e6edf5' } } },
    series: [{ name: metricLabel(metric), type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: .1 }, data: history.value.map(item => item.value) }]
  }, true);
}

function selectDevice(item) {
  selectedId.value = item.device_id;
}
async function applyFilters() {
  paused.value = false;
  const previousId = selectedId.value;
  await loadAggregate(true);
  if (previousId === selectedId.value) await loadSelected(true, true);
}
function togglePause() { paused.value = !paused.value; if (!paused.value) applyFilters(); }
function resizeChart() { chart?.resize(); }

watch(selectedId, () => {
  selectedGeneration++;
  state.value = null; history.value = []; events.value = []; eventSeq.value = 0;
  protocolStatus.value = null; radarTargets.value = []; information.value = null;
  informationError.value = ''; selectedError.value = ''; chart?.clear();
  if (selectedId.value) loadSelected(true, true);
});
watch(metricCode, () => { history.value = []; loadSelected(true, true); });
onMounted(async () => {
  await loadAggregate(true);
  if (!alive) return;
  aggregateTimer = window.setInterval(loadAggregate, 10000); selectedTimer = window.setInterval(loadSelected, 2000);
  window.addEventListener('resize', resizeChart);
});
onBeforeUnmount(() => { alive = false; clearInterval(aggregateTimer); clearInterval(selectedTimer); window.removeEventListener('resize', resizeChart); chart?.dispose(); });
</script>

<template>
  <section class="page-stack operation-page monitor-reference">
    <PageHeader title="设备实时监测" description="总览每 10 秒、当前设备每 2 秒增量刷新；暂停后不再发起轮询。">
      <el-tag :type="paused?'warning':'success'" effect="plain">{{ paused?'刷新已暂停':'实时刷新中' }}</el-tag>
      <el-button @click="togglePause">{{ paused?'继续刷新':'暂停刷新' }}</el-button>
    </PageHeader>
    <OperationMetrics :items="metrics" />
    <ErrorAlert :message="error" @retry="applyFilters" />
    <ErrorAlert :message="selectedError" @retry="loadSelected(true, true)" />
    <div class="monitor-layout">
      <el-card v-loading="loading">
        <template #header><div class="table-toolbar"><b>设备分类与状态</b><span class="muted">{{ tree.length }} 台</span></div></template>
        <el-input v-model="filters.keyword" clearable placeholder="设备编号或名称" @keyup.enter="applyFilters"><template #append><el-button @click="applyFilters">筛选</el-button></template></el-input>
        <div class="device-tree-list">
          <details v-for="[name,items] in groups" :key="name" open class="device-tree-group"><summary>{{ name }}<span>{{ items.length }}</span></summary>
            <details v-for="[type,members] in typeGroups(items)" :key="type" open class="device-type-group"><summary>{{ type }}<span>{{ members.length }}</span></summary>
              <button v-for="item in members" :key="item.device_id" type="button" class="device-tree-item" :class="{active:item.device_id===selectedId}" @click="selectDevice(item)"><span class="device-tree-copy"><b>{{ item.name }}</b><small class="mono">{{ item.device_no }}</small></span><el-tag class="device-tree-status" size="small" :type="statusType(item.connectivity)" effect="plain">{{ statusText(item.connectivity) }}</el-tag></button>
            </details>
          </details><el-empty v-if="!loading&&!tree.length" description="没有匹配的设备" />
        </div>
        <p class="tree-note">{{ treeTruncated ? `显示前 ${tree.length} / ${treeTotal} 台，请通过搜索缩小范围` : `共 ${tree.length} 台 · 展开类型查看设备` }}</p>
      </el-card>

      <div class="monitor-column">
        <el-card v-loading="selectedLoading">
          <template #header><div class="table-toolbar"><b>设备运行监控</b><span class="mono muted">{{ selected?.device_no || '未选择设备' }}</span></div></template>
          <el-empty v-if="!state" description="请选择设备查看状态" />
          <template v-else>
            <div class="state-hero"><article><small>连接状态</small><strong>{{ statusText(state.connectivity) }}</strong></article><article><small>健康状态</small><strong>{{ healthText(state.health_code) }}</strong></article><article><small>最后心跳</small><strong class="mono">{{ formatTime(state.last_heartbeat_at) }}</strong></article></div>
            <el-alert v-if="state.connectivity==='OFFLINE'" title="设备离线；曲线仅展示离线前的历史上报，不补零。" type="warning" :closable="false" />
            <el-tabs v-model="metricTab" class="monitor-metric-tabs"><el-tab-pane label="全部指标" name="all" /><el-tab-pane label="资源监控" name="resource" /><el-tab-pane label="信号质量" name="signal" /><el-tab-pane label="链路质量" name="link" /></el-tabs>
            <div v-if="state.connectivity!=='OFFLINE'" class="metric-values"><article v-for="item in visibleMetrics" :key="item.code"><small>{{ metricLabel(item) }}</small><b>{{ metricValue(item.value) }} {{ item.unit||'' }}</b><span class="muted">{{ SOURCE_LABELS[item.source] || '来源未声明' }}</span></article></div>
            <p v-if="state.connectivity!=='OFFLINE' && !visibleMetrics.length" class="tree-note">设备协议尚未上报此类指标。</p>
            <div v-if="protocolStatus?.protocol_code" class="detail-section"><h3>协议状态</h3><el-tag :type="statusType(protocolStatus.connection_state)" effect="plain">{{ statusText(protocolStatus.connection_state) }}</el-tag><p v-if="protocolStatus.blocking_reason" class="danger-text">{{ protocolStatus.blocking_reason }}</p></div>
          </template>
        </el-card>

        <el-card v-if="selected?.protocol_code==='RADAR_TCP_V3_0_0'">
          <template #header><div class="table-toolbar"><b>最近活动航迹</b><span class="muted">仅展示原始坐标</span></div></template>
          <el-table :data="radarTargets" max-height="210"><el-table-column prop="external_track_id" label="航迹 ID" min-width="110" /><el-table-column prop="category_code" label="分类" width="90" /><el-table-column label="X / Y / Z（m）" min-width="170"><template #default="{row}">{{ display(row.raw_xm) }} / {{ display(row.raw_ym) }} / {{ display(row.raw_zm) }}</template></el-table-column><el-table-column prop="snr_db" label="SNR" width="75" /></el-table>
        </el-card>

        <el-card class="table-card">
          <template #header><div class="table-toolbar"><b>指标曲线</b><el-select v-model="metricCode" style="width:210px" placeholder="选择指标"><el-option v-for="item in metricOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></div></template>
          <div v-show="history.length" ref="chartEl" class="chart-box"></div><el-empty v-if="!selectedLoading&&!history.length" description="该指标暂无上报数据" />
        </el-card>
      </div>

      <div class="monitor-column">
        <el-card><template #header><div class="table-toolbar"><b>实时告警</b><span class="muted">共 {{ incidentTotal }} 条</span><el-select v-model="severity" clearable placeholder="全部级别" style="width:110px" aria-label="告警级别"><el-option v-for="(label,value) in SEVERITY_LABELS" :key="value" :label="label" :value="value" /></el-select></div></template>
          <div v-if="filteredIncidents.length" class="event-feed monitor-alarms"><button v-for="item in filteredIncidents" :key="item.incident_id" type="button" class="device-tree-item" @click="selectedId=item.device_id"><span class="device-tree-copy"><b>{{ item.device_name }}</b><small class="alarm-reason">{{ item.reason }}</small><small class="mono">{{ formatTime(item.detected_at) }}</small></span><el-tag class="device-tree-status" :type="['HIGH','CRITICAL'].includes(item.severity)?'danger':'warning'" effect="plain">{{ SEVERITY_LABELS[item.severity] || item.severity }}</el-tag></button></div><el-empty v-else :description="severity ? '当前列表中没有此级别告警' : '当前没有活动告警'" /><p v-if="incidentTotal > incidents.length" class="tree-note">当前显示最近 {{ incidents.length }} 条待处理告警。</p>
        </el-card>
      </div>
    </div>
        <el-card class="table-card"><template #header><div class="table-toolbar"><b>监测日志流</b><span class="muted">当前设备 · 按序号增量更新</span></div></template>
          <div v-if="events.length" class="event-feed"><article v-for="item in [...events].reverse()" :key="item.event_seq" class="event-item"><b>{{ EVENT_LABELS[item.event_type] || item.event_type || '设备事件' }}</b><p>{{ item.message }}</p><time>#{{ item.event_seq }} · {{ formatTime(item.occurred_at) }}</time></article></div><el-empty v-else description="暂无设备事件" />
        </el-card>
    <DeviceMaintenancePanel ref="maintenanceTasks" />
    <DeviceInformationPanel v-if="selectedId" :key="selectedId" purpose="monitor" :information="information" :loading="selectedLoading" :error="informationError" @refresh="loadSelected(true, true)" />
  </section>
</template>

<style scoped>
.monitor-reference .monitor-layout { grid-template-columns: 245px minmax(0, 1fr) 320px; min-height: 0; align-items: start; }
.monitor-reference .monitor-layout > .monitor-column:last-child { grid-column: auto; display: flex; }
.monitor-reference .device-tree-list { max-height: 640px; }
.device-type-group { padding-left: 8px; }
.monitor-reference .state-hero { grid-template-columns: repeat(2, minmax(0,1fr)); }
.monitor-reference .state-hero article:last-child { grid-column: 1 / -1; }
.monitor-reference .metric-values { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.monitor-reference .metric-values article { background: #fff; }
.monitor-metric-tabs { margin-top: 16px; }
.monitor-reference .monitor-alarms { max-height: 650px; }
.monitor-reference .alarm-reason { white-space: normal; line-height: 1.6; }
.monitor-alarms .device-tree-item { border-bottom: 1px solid #eef1f5; align-items: flex-start; padding: 12px 0; }
@media (max-width: 1150px) { .monitor-reference .monitor-layout { grid-template-columns: 220px minmax(0,1fr); } .monitor-reference .monitor-layout > .monitor-column:last-child { grid-column: 1 / -1; display: block; } }
@media (max-width: 720px) { .monitor-reference .monitor-layout { grid-template-columns: minmax(0,1fr); } .monitor-reference .monitor-layout > .monitor-column:last-child { grid-column: auto; } .monitor-reference .device-tree-list { max-height: 300px; } }
</style>
