<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRealtimeRefresh } from '@/services/realtime.js';
import PageHeader from '@/components/PageHeader.vue';
import OperationMetrics from './OperationMetrics.vue';
import './operations-reference.css';
import ErrorAlert from '@/components/ErrorAlert.vue';
import DeviceTrendPanel from '@/components/DeviceTrendPanel.vue';
import { deviceApi } from '@/api/devices.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';

const filters = reactive({ keyword: '', channel: '', type_code: '' });
const overview = ref({ total: 0, online: 0, offline: 0, abnormal: 0, unknown: 0, alarm: 0 });
const tree = ref([]);
const incidents = ref([]);
const incidentTotal = ref(0);
const severity = ref('');
const treeTruncated = ref(false);
const treeTotal = ref(0);
const filteredIncidents = computed(() => incidents.value.filter(item => !severity.value || item.severity === severity.value));
const selectedId = ref('');
const state = ref(null);
const events = ref([]);
const eventSeq = ref(0);
const eventsLoaded = ref(false);
const eventsLoading = ref(false);
const eventsError = ref('');
let eventsRequest;
const protocolStatus = ref(null);
const radarTargets = ref([]);
const selectedError = ref('');
const loading = ref(false);
const selectedLoading = ref(false);
const paused = ref(false);
const error = ref('');
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
const visibleMetrics = computed(() => state.value?.metrics || []);
function typeGroups(items) { return Object.entries(items.reduce((groups, item) => { (groups[item.device_type_name || '其他设备'] ||= []).push(item); return groups; }, {})); }


const METRIC_LABELS = { link_latency_ms: '链路时延', packet_loss_pct: '丢包率', packet_loss_rate: '丢包率', signal_dbm: '信号强度', signal_strength_dbm: '信号强度', relay_state: '继电器状态字', temperature_c: '温度', cpu_pct: '处理器占用', memory_pct: '内存占用' };
const EVENT_LABELS = {
  REPORTING_STARTED: '开始接收上报', CONNECTED: '设备上线', DISCONNECTED: '设备离线', RECOVERED: '设备恢复在线',
  STATE_CHANGED: '工作状态变化', REPORT_SUMMARY: '上报汇总', REPORT_LATE_SUMMARY: '排队报文补充汇总', METRIC_REPORTED: '指标上报', HEARTBEAT_TIMEOUT: '心跳超时',
  COMMAND_QUEUED: '指令排队', COMMAND_SENT: '指令下发', COMMAND_SUCCEEDED: '指令成功', COMMAND_FAILED: '指令失败',
  CATALOG_CREATED: '设备档案创建', CATALOG_UPDATED: '设备档案更新', CATALOG_DELETED: '设备档案删除',
  DEVICE_ENABLED: '设备启用', DEVICE_DISABLED: '设备停用', SENSING_PROFILE_UPDATED: '感知配置更新', SENSING_PROFILE_DELETED: '感知配置删除',
  REBOOT_QUEUED: '重启排队', REBOOT_SUCCEEDED: '重启成功', REBOOT_FAILED: '重启失败', REBOOT_TIMED_OUT: '重启超时',
  INCIDENT_OPENED: '运维异常发现', EO_TRACK_QUEUED: '光电跟踪排队', EO_COMMAND_TIMED_OUT: '光电指令超时',
  LINGYUN_CONTROL_QUEUED: '凌云控制排队', LINGYUN_CONTROL_SUCCEEDED: '凌云控制成功', LINGYUN_CONTROL_FAILED: '凌云控制失败', LINGYUN_CONTROL_TIMED_OUT: '凌云控制超时',
  COUNTERMEASURE_4CH_QUEUED: '四通道指令排队', COUNTERMEASURE_4CH_SUCCEEDED: '四通道指令成功', COUNTERMEASURE_4CH_FAILED: '四通道指令失败', COUNTERMEASURE_4CH_TIMED_OUT: '四通道指令超时'
};
const EVENT_LEVELS = { INFO: '信息', WARN: '警告', WARNING: '警告', ERROR: '错误', CRITICAL: '严重', DEBUG: '调试' };
function eventLevelType(level) { return ['ERROR', 'CRITICAL'].includes(level) ? 'danger' : ['WARN', 'WARNING'].includes(level) ? 'warning' : 'info'; }
const eventEmptyText = computed(() => !selectedId.value ? '请选择设备查看事件' : eventsError.value ? '设备事件加载失败，请重试' : eventsLoading.value ? '正在加载设备事件' : '当前设备尚无事件');
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
    overview.value = summary; tree.value = deviceTree.items || []; treeTotal.value = deviceTree.total ?? tree.value.length; treeTruncated.value = Boolean(deviceTree.truncated); incidents.value = incidentPage.items || []; incidentTotal.value = incidentPage.total ?? incidents.value.length;
    if (!tree.value.some(item => item.device_id === selectedId.value)) selectedId.value = tree.value[0]?.device_id || '';
    error.value = '';
  } catch (e) { error.value = e.message || '实时监测数据加载失败'; }
  finally { loading.value = false; aggregateInFlight = false; }
}

async function loadSelected(showBusy = false, manual = false) {
  if (!alive || !selectedId.value || (paused.value && !manual)) return;
  void loadEvents(manual);
  if (selectedInFlight) { if (manual) selectedPending = true; return; }
  selectedInFlight = true; if (showBusy) selectedLoading.value = true;
  const deviceId = selectedId.value;
  const generation = selectedGeneration;
  try {
    const [deviceState, protocol, targetPage] = await Promise.all([
      deviceApi.state(deviceId),
      deviceApi.protocolStatus(deviceId),
      selected.value?.protocol_code === 'RADAR_TCP_V3_0_0' ? deviceApi.targets({ device_id: deviceId, active: true, page: 1, size: 20 }) : Promise.resolve({ items: [] })
    ]);
    if (!alive || generation !== selectedGeneration || deviceId !== selectedId.value) return;
    state.value = deviceState; protocolStatus.value = protocol; radarTargets.value = targetPage.items || [];
    selectedError.value = '';
  } catch (e) {
    if (!alive || generation !== selectedGeneration || deviceId !== selectedId.value) return;
    state.value = null; protocolStatus.value = null; radarTargets.value = [];
    selectedError.value = e.message || '所选设备状态加载失败';
  }
  finally {
    selectedLoading.value = false; selectedInFlight = false;
    if (selectedPending) { selectedPending = false; void loadSelected(true, true); }
  }
}

async function loadEvents(manual = false) {
  if (!alive || !selectedId.value || (paused.value && !manual)) return;
  const deviceId = selectedId.value, generation = selectedGeneration;
  if (eventsRequest?.generation === generation) return;
  const request = { generation };
  eventsRequest = request;
  eventsLoading.value = true;
  try {
    const eventPage = await deviceApi.events({ device_id: deviceId, limit: 100,
      ...(eventsLoaded.value ? { after_seq: eventSeq.value } : { latest: true }) });
    if (!alive || generation !== selectedGeneration || deviceId !== selectedId.value) return;
    const merged = new Map([...events.value, ...(eventPage.items || [])].map(item => [item.event_seq, item]));
    events.value = [...merged.values()].sort((a, b) => a.event_seq - b.event_seq).slice(-100);
    eventSeq.value = eventPage.next_seq ?? eventSeq.value;
    eventsLoaded.value = true;
    eventsError.value = '';
  } catch (e) {
    if (alive && generation === selectedGeneration && deviceId === selectedId.value) eventsError.value = e.message || '设备事件加载失败';
  } finally {
    if (eventsRequest === request) { eventsRequest = null; eventsLoading.value = false; }
  }
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

watch(selectedId, () => {
  selectedGeneration++;
  state.value = null; events.value = []; eventSeq.value = 0;
  eventsLoaded.value = false; eventsLoading.value = false; eventsError.value = ''; eventsRequest = null;
  protocolStatus.value = null; radarTargets.value = [];
  selectedError.value = '';
  if (selectedId.value) loadSelected(true, true);
});
onMounted(async () => {
  await loadAggregate(true);
  if (!alive) return;
  aggregateTimer = window.setInterval(loadAggregate, 10000); selectedTimer = window.setInterval(loadSelected, 2000);
});
onBeforeUnmount(() => { alive = false; clearInterval(aggregateTimer); clearInterval(selectedTimer); });
// 设备或目标变化后立即重读；暂停时不刷新，定时器保留为推送不可用时的兜底。
useRealtimeRefresh(['device', 'target'], () => Promise.all([loadAggregate(), loadSelected()]), { minIntervalMs: 1_000 });
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
      <el-card v-loading="loading" class="monitor-devices">
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
            <el-alert v-if="state.connectivity==='OFFLINE'" title="设备当前离线；曲线继续按实际接收的有效报文展示，缺失数据不补零。" type="warning" :closable="false" />
            <template v-if="state.connectivity!=='OFFLINE' && visibleMetrics.length">
              <div v-if="visibleMetrics.length" class="metric-values"><article v-for="item in visibleMetrics" :key="item.code"><small>{{ metricLabel(item) }}</small><b>{{ metricValue(item.value) }} {{ item.unit||'' }}</b><span class="muted">{{ SOURCE_LABELS[item.source] || '来源未声明' }}</span></article></div>
              <p v-else class="tree-note">设备协议尚未上报此类指标。</p>
            </template>
            <div v-if="protocolStatus?.protocol_code" class="detail-section"><h3>协议状态</h3><el-tag :type="statusType(protocolStatus.connection_state)" effect="plain">{{ statusText(protocolStatus.connection_state) }}</el-tag><p v-if="protocolStatus.blocking_reason" class="danger-text">{{ protocolStatus.blocking_reason }}</p></div>
          </template>
        </el-card>

        <el-card v-if="selected?.protocol_code==='RADAR_TCP_V3_0_0'">
          <template #header><div class="table-toolbar"><b>最近活动航迹</b><span class="muted">仅展示原始坐标</span></div></template>
          <el-table :data="radarTargets" max-height="210"><el-table-column prop="external_track_id" label="航迹 ID" min-width="110" /><el-table-column prop="category_code" label="分类" width="90" /><el-table-column label="X / Y / Z（m）" min-width="170"><template #default="{row}">{{ display(row.raw_xm) }} / {{ display(row.raw_ym) }} / {{ display(row.raw_zm) }}</template></el-table-column><el-table-column prop="snr_db" label="SNR" width="75" /></el-table>
        </el-card>

        <DeviceTrendPanel v-if="selectedId" :key="selectedId" :device-id="selectedId" :paused="paused" />
      </div>

      <div class="monitor-column monitor-activity">
        <el-card><template #header><div class="table-toolbar"><b>实时告警</b><span class="muted">共 {{ incidentTotal }} 条</span><el-select v-model="severity" clearable placeholder="全部级别" style="width:110px" aria-label="告警级别"><el-option v-for="(label,value) in SEVERITY_LABELS" :key="value" :label="label" :value="value" /></el-select></div></template>
          <div v-if="filteredIncidents.length" class="event-feed monitor-alarms"><button v-for="item in filteredIncidents" :key="item.incident_id" type="button" class="device-tree-item" @click="selectedId=item.device_id"><span class="device-tree-copy"><b>{{ item.device_name }}</b><small class="alarm-reason">{{ item.reason }}</small><small class="mono">{{ formatTime(item.detected_at) }}</small></span><el-tag class="device-tree-status" :type="['HIGH','CRITICAL'].includes(item.severity)?'danger':'warning'" effect="plain">{{ SEVERITY_LABELS[item.severity] || item.severity }}</el-tag></button></div><el-empty v-else :image-size="64" :description="severity ? '当前列表中没有此级别告警' : '当前没有活动告警'" /><p v-if="incidentTotal > incidents.length" class="tree-note">当前显示最近 {{ incidents.length }} 条待处理告警。</p>
        </el-card>
        <el-card class="table-card monitor-events"><template #header><div class="table-toolbar"><b>设备事件记录</b><span class="muted">当前设备 · 按序号增量更新</span></div></template>
          <p class="tree-note">关键事件即时记录，正常上报每 30 秒汇总；显示最近 100 条。</p>
          <ErrorAlert :message="eventsError" @retry="loadEvents(true)" />
          <div v-if="events.length" class="event-feed"><article v-for="item in [...events].reverse()" :key="item.event_seq" class="event-item"><div class="event-heading"><b>{{ EVENT_LABELS[item.event_type] || item.event_type || '设备事件' }}</b><el-tag size="small" :type="eventLevelType(item.level_code)" effect="plain">{{ EVENT_LEVELS[item.level_code] || item.level_code || '级别未声明' }}</el-tag><el-tag v-if="item.simulated" size="small" type="warning" effect="plain">模拟数据</el-tag></div><p>{{ item.message }}</p><time>#{{ item.event_seq }} · {{ formatTime(item.occurred_at) }}</time></article></div><el-empty v-else :image-size="64" :description="eventEmptyText" />
        </el-card>
      </div>
    </div>
  </section>
</template>

<style scoped>
.monitor-reference .monitor-layout { grid-template-columns: 245px minmax(0, 1fr) 320px; min-height: 520px; align-items: stretch; flex: none; }
.monitor-reference .monitor-layout > .monitor-activity { grid-column: auto; display: flex; }
.monitor-devices, .monitor-activity { contain: size; min-height: 0; }
.monitor-devices, .monitor-activity > .el-card { display: flex; flex-direction: column; min-height: 0; }
.monitor-activity > .el-card { flex: 1; }
.monitor-devices :deep(.el-card__body), .monitor-activity :deep(.el-card__body) { display: flex; flex: 1; flex-direction: column; min-height: 0; }
.monitor-reference .device-tree-list { flex: 1; height: 0; min-height: 260px; max-height: none; scrollbar-gutter: stable; }
.monitor-reference .monitor-activity .event-feed { flex: 1; height: 0; min-height: 120px; max-height: none; scrollbar-gutter: stable; }
.monitor-activity .tree-note { flex-shrink: 0; margin: 0 0 12px; }
.monitor-activity .monitor-alarms + .tree-note { margin: 12px 0 0; }
.monitor-activity :deep(.el-card__header) { flex-shrink: 0; }
.monitor-events .table-toolbar .muted { font-size: 12px; font-weight: 400; }
.monitor-events .event-item { overflow-wrap: anywhere; }
.device-type-group { padding-left: 8px; }
.monitor-reference .state-hero { grid-template-columns: minmax(0,1fr) minmax(0,1fr) minmax(190px,1.5fr); }
.monitor-reference .state-hero article:last-child strong { font-size: 14px; }
.monitor-reference .metric-values { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.monitor-reference .metric-values article { background: #fff; }
.monitor-metric-tabs { margin-top: 16px; }
.chart-empty { margin: 0; padding: 4px 0; color: var(--admin-muted); font-size: 13px; line-height: 1.6; }
.event-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.monitor-reference .alarm-reason { white-space: normal; line-height: 1.6; }
.monitor-alarms .device-tree-item { border-bottom: 1px solid #eef1f5; align-items: flex-start; padding: 12px 0; }
@media (max-width: 1280px) {
  .monitor-reference .monitor-layout { grid-template-columns: 220px minmax(0,1fr); }
  .monitor-reference .monitor-layout > .monitor-activity { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); contain: none; }
  .monitor-reference .monitor-activity .event-feed { height: auto; min-height: 0; max-height: 320px; }
}
@media (max-width: 720px) {
  .monitor-reference .monitor-layout { grid-template-columns: minmax(0,1fr); }
  .monitor-devices { contain: none; }
  .monitor-reference .monitor-layout > .monitor-activity { grid-column: auto; display: flex; }
  .monitor-reference .device-tree-list { height: auto; min-height: 0; max-height: 280px; }
  .monitor-reference .state-hero { grid-template-columns: repeat(2, minmax(0,1fr)); }
  .monitor-reference .state-hero article:last-child { grid-column: 1 / -1; }
  .monitor-reference .monitor-activity .event-feed { max-height: 280px; }
}
</style>
