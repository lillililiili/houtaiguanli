<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import * as echarts from 'echarts';
import PageHeader from '@/components/PageHeader.vue';
import MetricCards from '@/components/MetricCards.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { deviceApi } from '@/api/devices.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';

const filters = reactive({ keyword: '', channel: '', type_code: '' });
const overview = ref({ total: 0, online: 0, offline: 0, abnormal: 0, unknown: 0, alarm: 0 });
const tree = ref([]);
const incidents = ref([]);
const selectedId = ref('');
const state = ref(null);
const history = ref([]);
const events = ref([]);
const eventSeq = ref(0);
const metricCode = ref('link_latency_ms');
const protocolStatus = ref(null);
const radarTargets = ref([]);
const loading = ref(false);
const selectedLoading = ref(false);
const paused = ref(false);
const error = ref('');
const chartEl = ref();
let chart;
let aggregateTimer;
let selectedTimer;
let aggregateInFlight = false;
let selectedInFlight = false;
let selectedPending = false;
let alive = true;

const selected = computed(() => tree.value.find(item => item.device_id === selectedId.value));
const groups = computed(() => Object.entries(tree.value.reduce((result, item) => {
  (result[item.channel || '未分组'] ||= []).push(item); return result;
}, {})));
const metrics = computed(() => [
  { label: '设备总数', value: overview.value.total }, { label: '在线', value: overview.value.online, tone: 'green' },
  { label: '离线', value: overview.value.offline, tone: 'amber' }, { label: '异常', value: overview.value.abnormal, tone: 'red' },
  { label: '告警中', value: overview.value.alarm, tone: 'purple' }
]);
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
  try {
    const [summary, deviceTree, incidentPage] = await Promise.all([deviceApi.overview(), deviceApi.tree(filters), deviceApi.incidents({ page: 1, size: 20, stage: 'PENDING' })]);
    if (!alive) return;
    overview.value = summary; tree.value = deviceTree.items || []; incidents.value = incidentPage.items || [];
    if (!tree.value.some(item => item.device_id === selectedId.value)) selectedId.value = tree.value[0]?.device_id || '';
    error.value = '';
  } catch (e) { error.value = e.message || '实时监测数据加载失败'; }
  finally { loading.value = false; aggregateInFlight = false; }
}

async function loadSelected(showBusy = false) {
  if (!selectedId.value || paused.value) return;
  if (selectedInFlight) { selectedPending = true; return; }
  selectedInFlight = true; if (showBusy) selectedLoading.value = true;
  const deviceId = selectedId.value; const requestedMetric = metricCode.value;
  try {
    const [deviceState, points, eventPage, protocol, targetPage] = await Promise.all([
      deviceApi.state(deviceId), deviceApi.history(deviceId, { metric_code: requestedMetric, limit: 120 }),
      deviceApi.events({ device_id: deviceId, after_seq: eventSeq.value, limit: 100 }), deviceApi.protocolStatus(deviceId),
      selected.value?.protocol_code === 'RADAR_TCP_V3_0_0' ? deviceApi.targets({ device_id: deviceId, active: true, page: 1, size: 20 }) : Promise.resolve({ items: [] })
    ]);
    if (!alive || deviceId !== selectedId.value || requestedMetric !== metricCode.value) return;
    state.value = deviceState; history.value = points.points || []; protocolStatus.value = protocol; radarTargets.value = targetPage.items || [];
    const merged = new Map([...events.value, ...(eventPage.items || [])].map(item => [item.event_seq, item]));
    events.value = [...merged.values()].sort((a, b) => a.event_seq - b.event_seq).slice(-100); eventSeq.value = eventPage.next_seq || eventSeq.value;
    error.value = ''; await nextTick(); paintChart();
  } catch (e) { error.value = e.message || '所选设备状态加载失败'; }
  finally {
    selectedLoading.value = false; selectedInFlight = false;
    if (selectedPending) { selectedPending = false; void loadSelected(); }
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
  selectedId.value = item.device_id; state.value = null; history.value = []; events.value = []; eventSeq.value = 0; protocolStatus.value = null; radarTargets.value = [];
}
async function applyFilters() { paused.value = false; await loadAggregate(true); await loadSelected(true); }
function togglePause() { paused.value = !paused.value; if (!paused.value) applyFilters(); }
function resizeChart() { chart?.resize(); }

watch(selectedId, () => { if (selectedId.value) loadSelected(true); });
watch(metricCode, () => { history.value = []; loadSelected(true); });
onMounted(async () => {
  await loadAggregate(true); await loadSelected(true);
  aggregateTimer = window.setInterval(loadAggregate, 10000); selectedTimer = window.setInterval(loadSelected, 2000);
  window.addEventListener('resize', resizeChart);
});
onBeforeUnmount(() => { alive = false; clearInterval(aggregateTimer); clearInterval(selectedTimer); window.removeEventListener('resize', resizeChart); chart?.dispose(); });
</script>

<template>
  <section class="page-stack">
    <PageHeader title="设备实时监测" description="总览每 10 秒、当前设备每 2 秒增量刷新；暂停后不再发起轮询。">
      <el-tag :type="paused?'warning':'success'" effect="plain">{{ paused?'刷新已暂停':'实时刷新中' }}</el-tag>
      <el-button @click="togglePause">{{ paused?'继续刷新':'暂停刷新' }}</el-button>
    </PageHeader>
    <MetricCards :items="metrics" />
    <ErrorAlert :message="error" @retry="applyFilters" />
    <div class="monitor-layout">
      <el-card v-loading="loading">
        <template #header><div class="table-toolbar"><b>设备树</b><span class="muted">{{ tree.length }} 台</span></div></template>
        <el-input v-model="filters.keyword" clearable placeholder="设备编号或名称" @keyup.enter="applyFilters"><template #append><el-button @click="applyFilters">筛选</el-button></template></el-input>
        <div class="device-tree-list">
          <section v-for="[name,items] in groups" :key="name" class="device-tree-group"><h3>{{ name }} · {{ items.length }}</h3>
            <button v-for="item in items" :key="item.device_id" type="button" class="device-tree-item" :class="{active:item.device_id===selectedId}" @click="selectDevice(item)"><span class="device-tree-copy"><b>{{ item.name }}</b><small class="mono">{{ item.device_no }}</small></span><el-tag class="device-tree-status" size="small" :type="statusType(item.connectivity)" effect="plain">{{ statusText(item.connectivity) }}</el-tag></button>
          </section><el-empty v-if="!loading&&!tree.length" description="没有匹配的设备" />
        </div>
      </el-card>

      <div class="monitor-column">
        <el-card v-loading="selectedLoading">
          <template #header><div class="table-toolbar"><b>实时状态</b><span class="mono muted">{{ selected?.device_no || '未选择设备' }}</span></div></template>
          <el-empty v-if="!state" description="请选择设备查看状态" />
          <template v-else>
            <div class="state-hero"><article><small>连接状态</small><strong>{{ statusText(state.connectivity) }}</strong></article><article><small>健康状态</small><strong>{{ healthText(state.health_code) }}</strong></article><article><small>最后心跳</small><strong class="mono">{{ formatTime(state.last_heartbeat_at) }}</strong></article></div>
            <el-alert v-if="state.connectivity==='OFFLINE'" title="设备离线；曲线仅展示离线前的历史上报，不补零。" type="warning" :closable="false" />
            <div v-else class="metric-values"><article v-for="item in state.metrics||[]" :key="item.code"><small>{{ metricLabel(item) }}</small><b>{{ metricValue(item.value) }} {{ item.unit||'' }}</b><span class="muted">{{ SOURCE_LABELS[item.source] || '来源未声明' }}</span></article></div>
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
        <el-card><template #header><div class="table-toolbar"><b>活动告警</b><span class="muted">{{ incidents.length }} 条</span></div></template>
          <div v-if="incidents.length" class="event-feed"><button v-for="item in incidents" :key="item.incident_id" type="button" class="device-tree-item" @click="selectedId=item.device_id"><span class="device-tree-copy"><b>{{ item.device_name }}</b><small>{{ item.reason }}</small></span><el-tag class="device-tree-status" :type="['HIGH','CRITICAL'].includes(item.severity)?'danger':'warning'" effect="plain">{{ SEVERITY_LABELS[item.severity] || item.severity }}</el-tag></button></div><el-empty v-else description="当前没有活动告警" />
        </el-card>
        <el-card class="table-card"><template #header><div class="table-toolbar"><b>设备事件流</b><span class="muted">按序号增量拉取</span></div></template>
          <div v-if="events.length" class="event-feed"><article v-for="item in [...events].reverse()" :key="item.event_seq" class="event-item"><b>{{ EVENT_LABELS[item.event_type] || item.event_type || '设备事件' }}</b><p>{{ item.message }}</p><time>#{{ item.event_seq }} · {{ formatTime(item.occurred_at) }}</time></article></div><el-empty v-else description="暂无设备事件" />
        </el-card>
      </div>
    </div>
  </section>
</template>
