<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import MaintenanceReportHistory from './maintenance/MaintenanceReportHistory.vue';
import IncidentRebootPanel from './maintenance/IncidentRebootPanel.vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
import { useAuthStore } from '@/stores/auth.js';
import { useRealtimeRefresh } from '@/services/realtime.js';
import { formatDeviceNo } from '@/utils/format.js';
import { maintenanceLocation, maintenanceMeta, maintenanceState, useMaintenanceWorkflow } from './maintenance/useMaintenanceWorkflow.js';
import { alarmLabel, connectionLabel, finishedMaintenance, healthLabel, maintenanceActionLabel, maintenanceEventLabel, maintenanceNextStep, maintenanceTime, primaryMaintenanceAction } from './maintenance/presentation.js';

const route = useRoute(), router = useRouter(), auth = useAuthStore();
const taskId = computed(() => typeof route.query.task_id === 'string' ? route.query.task_id : '');
const deviceFilter = computed(() => typeof route.query.device_id === 'string' ? route.query.device_id : '');
const model = useMaintenanceWorkflow(taskId);
const { workflow, loading, error, busy, actionError, pending, lastSuccessAt } = model;
const task = computed(() => workflow.value?.task || {});
const currentState = computed(() => workflow.value?.current_device_state || task.value.current_device_state || {});
const meta = computed(() => maintenanceMeta(workflow.value?.state));
const primary = computed(() => primaryMaintenanceAction(workflow.value));
const nextStep = computed(() => maintenanceNextStep(workflow.value));
const waitingForRecovery = computed(() => ['PENDING', 'PROCESSING', 'PENDING_VERIFICATION'].includes(workflow.value?.state));
const operatorGuidance = computed(() => workflow.value?.state === 'PENDING'
  ? '点击“接手处理”登记办理人，按当前异常检查供电、网络和设备故障，必要时联系维护人员。'
  : '按当前异常检查供电、网络和设备故障，必要时联系维护人员。处理说明可选填。');
const completed = computed(() => finishedMaintenance(workflow.value?.state));
const canDiagnose = computed(() => auth.hasMenu('commission') && auth.hasPermission('commissioning.read') && auth.hasPermission('devices.read'));
const canMonitor = computed(() => auth.hasMenu('monitor') && auth.hasPermission('monitoring.read'));
const items = ref([]), total = ref(0), page = ref(1), filter = ref('ACTIVE');
const listLoading = ref(false), listError = ref(''), listSuccessAt = ref(null);
const note = ref('');
const rebootLocked = ref(false);
const operationLocked = computed(() => busy.value || !!pending.value || rebootLocked.value);
const submittingLabel = computed(() => ({ START: '正在接手处理…', CONFIRM_COMPLETE: '正在确认办结…', SAVE_PROGRESS: '正在保存记录…' })[pending.value?.body?.action] || '正在提交…');
const canReboot = computed(() => auth.hasPermission('monitoring.op') && workflow.value?.open_incidents?.some(item => item.allowed_actions?.includes('REBOOT')));
const relatedOpen = ref([]);
const statusOptions = [
  ['ACTIVE', '未办结'], ['NOT_STARTED', '待处理'], ['PROCESSING', '处理中'],
  ['READY_TO_COMPLETE', '待确认办结'], ['COMPLETED', '已办结'], ['ALL', '全部记录']
];
const events = computed(() => [...(workflow.value?.events || [])].reverse());
const stateOutdated = computed(() => Boolean(error.value));
const relatedPlans = computed(() => workflow.value?.related_plans || []);
const relatedIncidents = computed(() => workflow.value?.related_incidents || workflow.value?.open_incidents || []);
const recoveryEvidence = computed(() => workflow.value?.recovery?.evidence);
const latestManualReport = computed(() => task.value.report_summary?.latest_manual_report);
// 任务只显示业务编号或名称，内部 ID 不给操作员看（#21）。
const planLabel = plan => plan?.plan_no || plan?.plan_name || '';
const reportPlans = item => (item.report_summary?.related_plans || []).map(planLabel).filter(Boolean).join('、');
let alive = true, listGeneration = 0, refreshInFlight = false;

async function loadList({ selectFirst = false, quiet = false, followFilter = false } = {}) {
  const generation = ++listGeneration;
  if (!quiet) listLoading.value = true;
  try {
    const result = await deviceMaintenanceApi.list({ status: filter.value || undefined, device_id: deviceFilter.value || undefined, page: page.value, size: 20 });
    if (!alive || generation !== listGeneration) return;
    items.value = result.items || []; total.value = result.total ?? 0; listError.value = ''; listSuccessAt.value = Date.now();
    const lastPage = Math.max(1, Math.ceil(total.value / 20));
    if (page.value > lastPage) { page.value = lastPage; return loadList({ selectFirst, quiet, followFilter }); }
    const scope = deviceFilter.value ? { device_id: deviceFilter.value } : {};
    // 切换筛选后右侧详情跟随列表：原选中单不在新列表里时改看新列表第一张，列表为空则清空详情（#11）。
    if (followFilter && !items.value.some(item => item.task_id === taskId.value)) await router.replace(maintenanceLocation({ task_id: items.value[0]?.task_id, ...scope }));
    else if (selectFirst && !taskId.value && items.value.length) await router.replace(maintenanceLocation({ task_id: items.value[0].task_id, ...scope }));
  } catch (reason) {
    if (alive && generation === listGeneration) {
      listError.value = reason.message || '处置单列表更新失败';
      if ([401, 403, 404].includes(reason.status)) { items.value = []; total.value = 0; }
      if (quiet) throw reason;
    }
  } finally { if (alive && generation === listGeneration) listLoading.value = false; }
}
async function refresh({ quiet = false } = {}) {
  if (refreshInFlight || busy.value || pending.value || document.hidden) return;
  refreshInFlight = true;
  try { await Promise.all([loadList({ quiet }), model.load({ quiet, rethrow: quiet })]); } finally { refreshInFlight = false; }
}
async function changeFilter() { page.value = 1; await loadList({ followFilter: true }); }
function changePage(value) { page.value = value; void loadList(); }
function openTask(item) {
  if (busy.value || pending.value || item.task_id === taskId.value) return;
  void router.push(maintenanceLocation({ task_id: item.task_id, ...(deviceFilter.value ? { device_id: deviceFilter.value } : {}) }));
}
async function action(name) {
  if (rebootLocked.value) return;
  const submittedNote = note.value.trim();
  const result = await model.act(name, ['SAVE_PROGRESS', 'CONFIRM_COMPLETE'].includes(name) ? { note: submittedNote } : {});
  if (!result) return;
  if (['SAVE_PROGRESS', 'CONFIRM_COMPLETE'].includes(name) && note.value.trim() === submittedNote) note.value = '';
  ElMessage.success(name === 'CONFIRM_COMPLETE' ? '本次处置已办结' : name === 'START' ? '已登记你为办理人，请按异常提示排查；系统持续检查恢复情况' : '处理记录已保存');
  void loadList();
}
async function retry() {
  const request = pending.value;
  const result = await model.retry();
  if (!result) return;
  if (['SAVE_PROGRESS', 'CONFIRM_COMPLETE'].includes(request?.body?.action) && note.value.trim() === request.body.note) note.value = '';
  ElMessage.success('操作结果已确认'); void loadList();
}
function diagnose() {
  if (canDiagnose.value && task.value.device_id) void router.push({ path: '/operations/commission', query: { device_id: task.value.device_id, maintenance_context_id: taskId.value } });
}
function monitor() { if (canMonitor.value && task.value.device_id) void router.push({ path: '/operations/monitor', query: { device_id: task.value.device_id, maintenance_context_id: taskId.value } }); }
async function canLeave(to) {
  if (to.path === route.path && to.query.task_id === taskId.value) return true;
  if (operationLocked.value) { ElMessage.warning('当前操作尚未结束，请先完成或确认结果。'); return false; }
  if (!note.value.trim()) return true;
  try { await ElMessageBox.confirm('处理说明尚未保存，离开将丢弃这段说明。', '保留处理说明', { confirmButtonText: '离开', cancelButtonText: '继续填写', type: 'warning' }); return true; } catch { return false; }
}
onBeforeRouteLeave(canLeave);
onBeforeRouteUpdate(canLeave);
watch(taskId, () => { note.value = ''; relatedOpen.value = []; });
watch(deviceFilter, () => { page.value = 1; void loadList({ selectFirst: true }); });
useRealtimeRefresh(['device', 'device_state'], () => refresh({ quiet: true }), { minIntervalMs: 2000 });
onMounted(() => {
  void loadList({ selectFirst: true });
});
onBeforeUnmount(() => { alive = false; ++listGeneration; });
</script>

<template>
  <section class="maintenance-workbench">
    <PageHeader title="设备异常处理" description="未接手也会自动核验恢复；“接手处理”只登记办理人，不会自动维修或重启设备。">
      <el-button :loading="listLoading || loading" :disabled="operationLocked" @click="refresh">刷新</el-button>
    </PageHeader>
    <div class="maintenance-workspace">
      <aside class="case-list" aria-label="设备异常处置单列表">
        <div class="case-list-filter">
          <label for="maintenance-status">办理状态</label>
          <el-select id="maintenance-status" v-model="filter" aria-label="办理状态" :disabled="operationLocked" @change="changeFilter"><el-option v-for="[value,label] in statusOptions" :key="value" :value="value" :label="label" /></el-select>
          <div v-if="deviceFilter" class="scope-filter">当前设备的处置单 <el-button link type="primary" @click="router.replace({path:'/operations/maintenance',query:taskId?{task_id:taskId}:{}})">查看全部设备</el-button></div>
        </div>
        <el-alert v-if="listError" :title="`列表更新失败：${listError}`" type="warning" :closable="false" show-icon />
        <div class="case-list-items" :aria-busy="listLoading">
          <button v-for="item in items" :key="item.task_id" class="case-item" :class="{selected:item.task_id===taskId}" type="button" :aria-pressed="item.task_id===taskId" :disabled="operationLocked" @click="openTask(item)">
            <span class="case-title"><b>{{ item.device_name }}</b><el-tag size="small" :type="maintenanceMeta(maintenanceState(item)).tone">{{ maintenanceMeta(maintenanceState(item)).label }}</el-tag></span>
            <span class="case-device-no">{{ formatDeviceNo(item.device_no) }}<span v-if="item.simulated"> · 模拟设备</span><span v-if="item.workflow_version!==2"> · 历史上报单</span></span>
            <span class="case-reason" :title="item.reason">{{ item.reason }}</span>
            <span v-if="item.current_device_state" class="case-state">当前：{{ connectionLabel(item.current_device_state.connectivity) }} · {{ healthLabel(item.current_device_state.health_code, item.current_device_state.connectivity) }}</span>
            <template v-if="item.report_summary?.latest_manual_report">
              <span class="case-report-origin"><el-tag size="small">人工上报</el-tag><span>{{ item.report_summary.latest_manual_report.actor_name || '上报人未记录' }}</span></span>
              <span class="case-time">最近上报 {{ maintenanceTime(item.report_summary.latest_manual_report.reported_at) }}</span>
            </template>
            <template v-else>
              <span class="case-report-origin">{{ item.report_summary?.origin==='SYSTEM'?'系统发现':item.report_summary?.origin==='USER'?'人工上报':'来源' }} · {{ item.reported_by_name || '未记录' }}</span>
              <span class="case-time">{{ item.report_summary?.origin==='SYSTEM'?'首次发现':'首次上报' }} {{ maintenanceTime(item.reported_at) }}</span>
            </template>
            <span v-if="reportPlans(item)" class="case-report-plans" :title="reportPlans(item)">关联任务 {{ reportPlans(item) }}</span>
          </button>
          <el-empty v-if="!items.length && !listLoading" :description="listError?'列表状态待确认':'当前没有这类处置单'" :image-size="64" />
        </div>
        <div class="case-pagination"><span>共 {{ total }} 份处置单</span><el-pagination small :current-page="page" :page-size="20" :total="total" layout="prev, next" @current-change="changePage" /><small v-if="listError && listSuccessAt">上次读取 {{ maintenanceTime(listSuccessAt) }}</small></div>
      </aside>

      <main class="case-detail" aria-label="设备异常办理详情">
        <div v-if="!workflow" class="case-empty"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-empty :description="loading?'正在读取处置单…':taskId?'未能读取这份处置单，请刷新或检查访问范围':'选择一份处置单开始查看'" :image-size="90" /></div>
        <template v-else>
          <div class="case-summary">
            <div class="case-heading"><div><h2>{{ task.device_name }}</h2><p>{{ formatDeviceNo(task.device_no) }} <el-tag v-if="task.simulated" size="small" type="info">模拟设备</el-tag><el-tag v-if="workflow.workflow_version!==2 && task.workflow_version!==2" size="small" type="info">历史上报单</el-tag></p></div><el-tag :type="meta.tone">{{ meta.label }}</el-tag></div>
            <p v-if="(workflow.workflow_version===2 || task.workflow_version===2) && relatedPlans.length>1" class="shared-case-hint">这台设备的多个任务上报统一在本单办理。新增上报会关联原单，不会重复新增待办；记录见“关联任务、异常与原始上报”。</p>
            <el-steps v-if="workflow.state!=='LEGACY_HANDLED'" :active="meta.step" finish-status="success" align-center class="case-steps"><el-step title="待处理" /><el-step title="处理中" /><el-step title="待确认办结" /><el-step title="已办结" /></el-steps>
          </div>
          <div class="case-scroll">
            <el-alert v-if="stateOutdated" class="stale-alert" :title="`状态待确认：${error}`" :description="`上次成功读取：${maintenanceTime(lastSuccessAt)}。更新成功前不能确认办结。`" type="warning" :closable="false" show-icon />
            <section class="case-section"><h3>发生了什么</h3><p class="anomaly-summary">{{ task.reason || '请查看原始上报记录。' }}</p><div class="case-facts"><span>{{ task.report_summary?.origin==='SYSTEM'?'首次发现':'首次上报' }} <b>{{ maintenanceTime(task.reported_at) }}</b></span><span>处理人 <b>{{ workflow.assigned_to_name || '待接手' }}</b></span><span v-if="relatedPlans.length">关联任务 <b>{{ relatedPlans.map(planLabel).filter(Boolean).join('、') || '已关联飞行任务' }}</b></span></div><div v-if="latestManualReport" class="latest-manual-report"><b>最近人工上报</b><span>{{ latestManualReport.actor_name || '上报人未记录' }} · {{ maintenanceTime(latestManualReport.reported_at) }}</span><span v-if="latestManualReport.plan_no || latestManualReport.plan_id">来自任务 {{ latestManualReport.plan_no || '（已关联飞行任务）' }}</span><small v-if="task.report_summary?.origin==='SYSTEM'">本次上报已关联到系统发现的这份处置单。</small></div></section>
            <section class="case-section"><div class="section-title"><h3>{{ completed ? '当前设备状态' : '现在什么状态' }}</h3><span :class="{outdated:stateOutdated}">{{ stateOutdated?'更新失败，以下为上次读取':`每 5 秒自动更新 · 北京时间` }}</span></div>
              <div class="current-device-state" :class="{outdated:stateOutdated}">
                <article><span>连接状态</span><strong>{{ connectionLabel(currentState.connectivity) }}</strong><small>最近心跳<br />{{ maintenanceTime(currentState.last_heartbeat_at) }}</small></article>
                <article><span>健康状态</span><strong>{{ healthLabel(currentState.health_code, currentState.connectivity) }}</strong><small>健康上报<br />{{ maintenanceTime(currentState.health_observed_at) }}</small></article>
                <article><span>设备告警</span><strong>{{ alarmLabel(currentState.has_alarm) }}</strong><small>告警上报<br />{{ maintenanceTime(currentState.alarm_observed_at) }}</small></article>
              </div>
              <div v-if="workflow.recovery" class="recovery-fact" :class="{passed:workflow.recovery.result==='PASS' && (!stateOutdated || completed)}"><b>{{ completed?'办结时的恢复依据':stateOutdated?'恢复状态待确认':workflow.recovery.result==='PASS'?'✓ 恢复核验通过':'等待恢复条件满足' }}</b><p>{{ workflow.recovery.reason || workflow.blocked_reason || '等待新的设备上报。' }}</p><small>{{ completed?'办结核验':'最近核验' }} {{ maintenanceTime(workflow.recovery.checked_at || workflow.recovery.evaluated_at) }}</small><div v-if="recoveryEvidence" class="recovery-evidence"><p>核验时：{{ connectionLabel(recoveryEvidence.connectivity) }} · {{ healthLabel(recoveryEvidence.health_code, recoveryEvidence.connectivity) }} · {{ alarmLabel(recoveryEvidence.has_alarm) }}<span v-if="recoveryEvidence.simulated"> · 模拟依据</span></p><p>心跳依据 {{ maintenanceTime(recoveryEvidence.last_heartbeat_at) }}</p><p>健康依据 {{ maintenanceTime(recoveryEvidence.health_observed_at) }}</p><p>告警依据 {{ maintenanceTime(recoveryEvidence.alarm_observed_at) }}</p></div><p v-if="completed">以上为办结时保存的依据，不随当前设备状态变化。</p></div>
            </section>
            <el-collapse v-model="relatedOpen" class="case-related">
              <el-collapse-item v-if="!completed" title="记录处理情况（选填）" name="note"><label for="maintenance-note">检查或处理说明</label><el-input id="maintenance-note" v-model="note" :disabled="operationLocked" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="可记录检查供电、网络或联系设备维护人员的情况" /><el-button :disabled="operationLocked || !model.can('SAVE_PROGRESS') || !note.trim()" :loading="busy" @click="action('SAVE_PROGRESS')">记录处理</el-button><p class="muted">不填写也可自动核验恢复；保存说明不会办结处置单。</p></el-collapse-item>
              <el-collapse-item :title="`处理记录（${events.length}）`" name="events"><el-timeline><el-timeline-item v-for="event in events" :key="event.event_id" :timestamp="maintenanceTime(event.occurred_at)"><b>{{ maintenanceEventLabel(event.action) }}</b> · {{ event.actor_name || '系统' }}<p>{{ event.note }}</p></el-timeline-item></el-timeline><p v-if="!events.length" class="muted">暂无后续处理记录。</p></el-collapse-item>
              <el-collapse-item title="关联任务、异常与原始上报" name="related"><p>上报人：{{ task.reported_by_name || '系统' }}</p><p>上报时连接：{{ connectionLabel(task.connectivity) }}；健康：{{ healthLabel(task.health_code, task.connectivity) }}</p><p v-if="task.handling_note">处理结果：{{ task.handling_note }}</p><h4>关联任务</h4><p v-for="item in relatedPlans" :key="item.plan_id">{{ [item.plan_no, item.plan_name].filter(Boolean).join(' ') || '已关联飞行任务' }}</p><p v-if="!relatedPlans.length">无可见关联任务；设备异常可独立办理。</p><h4>关联异常</h4><p v-for="item in relatedIncidents" :key="item.incident_id">{{ item.reason }} · {{ maintenanceTime(item.detected_at) }}</p><p v-if="!relatedIncidents.length">暂无可见关联异常记录。</p><MaintenanceReportHistory :reports="workflow.related_reports || []" :plans="relatedPlans" :notifications="task.notification_attempts || []" /><details class="tech-details"><summary>详细信息</summary><p>处置单内部编号：{{ task.task_id }}</p><p v-for="item in relatedPlans" :key="`plan-${item.plan_id}`">任务内部编号：{{ item.plan_id }}</p></details></el-collapse-item>
              <el-collapse-item v-if="canDiagnose || canMonitor || canReboot" title="高级诊断（按需使用）" name="diagnose"><p>查看详细运行状态、连接配置或协议调测。日常恢复无需创建调测任务。</p><div class="advanced-actions"><el-button v-if="canMonitor" :disabled="operationLocked" @click="monitor">查看设备监测</el-button><el-button v-if="canDiagnose" :disabled="operationLocked" @click="diagnose">设备接入调测</el-button></div><IncidentRebootPanel :key="taskId" :device-name="task.device_name" :incidents="workflow.open_incidents || []" :disabled="stateOutdated || busy || !!pending || completed" @lock="rebootLocked=$event" @refresh="refresh" /></el-collapse-item>
            </el-collapse>
          </div>
          <footer class="case-next-step" aria-live="polite">
            <div>
              <template v-if="waitingForRecovery && !stateOutdated">
                <b>你需要做</b><p>{{ operatorGuidance }}</p>
                <p class="system-check-guidance"><b>系统正在检查</b>：每约 5 秒核对在线心跳、健康状态、设备告警及操作状态，满足恢复条件后提示“待确认办结”。</p>
              </template>
              <template v-else><b>{{ completed?'办理结果':'下一步' }}</b><p>{{ stateOutdated?'当前状态更新失败，请刷新后继续。':nextStep }}</p></template>
              <small v-if="!completed && !auth.hasPermission('monitoring.op')">当前账号只能查看；接手和办结需要设备监测操作权限。</small>
              <p v-if="actionError" class="action-error">{{ actionError }}</p>
            </div>
            <el-button v-if="busy" type="primary" size="large" loading disabled>{{ submittingLabel }}</el-button>
            <el-button v-else-if="pending" type="primary" @click="retry">按原请求重试确认</el-button>
            <el-button v-else-if="primary && !completed" type="primary" size="large" :disabled="operationLocked || !model.can(primary)" @click="action(primary)">{{ maintenanceActionLabel(primary) }}</el-button>
            <span v-else-if="!completed" class="automatic-status">{{ stateOutdated?'状态待更新':waitingForRecovery?'等待设备恢复':'暂无可执行操作' }}</span>
          </footer>
        </template>
      </main>
    </div>
  </section>
</template>

<style scoped>
.case-heading>div{min-width:0}.tech-details{margin-top:10px;color:#7b8ba0}.tech-details summary{cursor:pointer}.shared-case-hint{font-size:12px;line-height:1.6;margin:8px 0 0;color:#42668e}
.case-report-origin{display:flex;align-items:center;gap:6px;font-size:12px;color:#35567a;overflow-wrap:anywhere}.case-report-plans{font-size:12px;color:#42668e;overflow-wrap:anywhere}.latest-manual-report{display:flex;flex-wrap:wrap;gap:6px 14px;margin-top:12px;padding:10px 12px;border-left:3px solid #409eff;background:#f0f6ff;border-radius:4px;font-size:12px;line-height:1.6;overflow-wrap:anywhere}.latest-manual-report small{flex-basis:100%;color:#687d94}
.maintenance-workbench{display:flex;flex-direction:column;gap:12px;height:calc(100dvh - 143px);min-height:470px;color:#263e5b}.maintenance-workbench :deep(.page-header){flex-shrink:0}.maintenance-workspace{display:grid;grid-template-columns:minmax(255px,30%) minmax(0,1fr);gap:14px;min-height:0;flex:1}.case-list,.case-detail{background:#fff;border:1px solid #d6e2f0;border-radius:9px;min-width:0;min-height:0;overflow:hidden;display:flex;flex-direction:column}.case-list-filter{padding:14px;border-bottom:1px solid #e7edf4;display:grid;grid-template-columns:auto minmax(0,1fr);gap:10px;align-items:center;font-size:13px}.scope-filter{grid-column:1/-1;font-size:12px;color:#687d94}.case-list-items{overflow:auto;flex:1;min-height:0;padding:8px}.case-item{width:100%;display:flex;flex-direction:column;gap:8px;text-align:left;padding:13px;border:1px solid transparent;border-bottom-color:#e8eef6;border-radius:6px;background:#fff;color:inherit;cursor:pointer}.case-item:hover{background:#f6f9fe}.case-item.selected{background:#edf5ff;border-color:#86b7fa;box-shadow:inset 3px 0 #1677ff}.case-item:focus-visible{outline:2px solid #1677ff;outline-offset:-3px}.case-title{display:flex;align-items:flex-start;justify-content:space-between;gap:8px}.case-title b{font-size:14px;overflow-wrap:anywhere}.case-device-no,.case-time{font-size:11px;color:#718298}.case-reason{font-size:12px;line-height:1.6;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#52677d}.case-state{font-size:12px;color:#42668e}.case-pagination{padding:10px 14px;border-top:1px solid #e7edf4;display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;font-size:12px;color:#6a7e95}.case-pagination small{flex-basis:100%}.case-summary{padding:14px 18px 10px;border-bottom:1px solid #e7edf4;flex-shrink:0}.case-heading{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.case-heading h2{font-size:17px;margin:0 0 6px;overflow-wrap:anywhere}.case-heading p{font-size:12px;color:#718298;margin:0;display:flex;gap:8px;flex-wrap:wrap;align-items:center}.case-steps{margin-top:13px}.case-steps :deep(.el-step__title){font-size:12px;line-height:25px}.case-steps :deep(.el-step__icon){width:23px;height:23px;font-size:12px}.case-scroll{overflow:auto;min-height:0;flex:1;padding:0 18px 14px}.case-section{padding:13px 0;border-bottom:1px solid #e7edf4}.case-section h3{font-size:14px;margin:0 0 9px}.anomaly-summary{font-size:13px;line-height:1.7;margin:0 0 9px;overflow-wrap:anywhere}.case-facts{display:flex;gap:8px 20px;flex-wrap:wrap;color:#7b8ba0;font-size:12px;line-height:1.6}.case-facts b{font-weight:400;color:#3e5875;margin-left:6px}.section-title{display:flex;align-items:baseline;justify-content:space-between;gap:8px;flex-wrap:wrap}.section-title>span{font-size:11px;color:#8190a3}.current-device-state{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.current-device-state article{background:#f5f8fc;border:1px solid #e2eaf5;border-radius:6px;padding:10px;display:flex;flex-direction:column;gap:6px}.current-device-state span{font-size:12px;color:#6b7f97}.current-device-state strong{font-size:17px;color:#273f5c}.current-device-state small{font-size:11px;line-height:1.6;color:#72849a}.recovery-fact{margin-top:10px;padding:10px 12px;border-left:3px solid #e6a23c;background:#fff9ed;border-radius:4px;font-size:12px}.recovery-fact.passed{border-color:#37a66c;background:#f0faf4}.recovery-fact p{line-height:1.6;margin:5px 0}.recovery-fact small{color:#7d8898}.case-next-step{padding:12px 18px;display:flex;align-items:center;justify-content:space-between;gap:16px;border-top:1px solid #cbdff4;background:#f0f6ff;flex-shrink:0}.case-next-step>div{min-width:0}.case-next-step b{font-size:13px}.case-next-step p{font-size:12px;line-height:1.6;margin:5px 0 0;overflow-wrap:anywhere}.case-next-step small{font-size:11px;color:#7b6994}.case-next-step>.el-button,.automatic-status{flex-shrink:0}.automatic-status{font-size:12px;color:#597292}.case-next-step .action-error{color:#c23d46}.case-empty{padding:20px}.case-related :deep(.el-collapse-item__header){font-size:12px;font-weight:600}.case-related :deep(.el-collapse-item__content){font-size:12px;color:#5c718a}.case-related p{overflow-wrap:anywhere;white-space:pre-wrap;line-height:1.7}.case-related label{display:block;margin-bottom:8px}.case-related .el-textarea{margin-bottom:10px}.case-related :deep(.el-timeline){padding-left:5px}.advanced-actions{display:flex;gap:8px;flex-wrap:wrap}.advanced-actions .el-button{margin-left:0}.outdated{opacity:.65}.stale-alert{margin-top:12px}.stale-alert :deep(.el-alert__description){line-height:1.6}@media(min-width:1600px){.maintenance-workspace{grid-template-columns:minmax(300px,28%) minmax(0,1fr)}.case-scroll{padding:0 24px 18px}.case-section{padding:18px 0}.current-device-state article{padding:14px}}@media(max-width:900px){.maintenance-workbench{height:auto;min-height:0}.maintenance-workspace{grid-template-columns:1fr}.case-list{max-height:340px}.case-detail{min-height:550px;max-height:calc(100dvh - 100px)}.case-next-step{flex-wrap:wrap}.case-next-step>.el-button{margin-left:auto}.current-device-state{gap:5px}.current-device-state article{padding:8px}.case-summary,.case-next-step{padding:12px}.case-scroll{padding:0 12px 12px}}
</style>
