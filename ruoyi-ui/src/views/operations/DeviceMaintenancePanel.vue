<script setup>
import { computed, onBeforeUnmount, ref } from 'vue';
import { ElMessage } from 'element-plus';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
import { formatTime, statusText } from '@/utils/format.js';

const items = ref([]), total = ref(0), page = ref(1), status = ref('PENDING');
const loading = ref(false), error = ref(''), selected = ref(null), note = ref(''), saving = ref(false), saveError = ref('');
const key = ref(null), open = ref(false);
const pages = computed(() => Math.max(1, Math.ceil(total.value / 10)));
const changedElsewhere = ref(false);
const notificationAttempts = computed(() => Array.isArray(selected.value?.notification_attempts) ? selected.value.notification_attempts : []);
const latestNotification = computed(() => notificationAttempts.value[0]);
const previousDeliveries = computed(() => notificationAttempts.value.slice(1).filter(attempt => attempt.delivery_status === 'DELIVERED').length);
const latestNotificationText = computed(() => latestNotification.value ? attemptStatus(latestNotification.value)
  : selected.value?.notification_delivery_status ? notificationStatus(selected.value.notification_delivery_status) : '未保存通知资料，发送情况未知');
let generation = 0, alive = true;

async function reload(force = false) {
  if (loading.value && !force) return;
  const current = ++generation;
  loading.value = true;
  try {
    const result = await deviceMaintenanceApi.list({ status: status.value, page: page.value, size: 10 });
    if (!alive || current !== generation) return;
    items.value = result.items || []; total.value = result.total; error.value = '';
    if (page.value > pages.value) { page.value = pages.value; void reload(true); }
  } catch (reason) { if (alive && current === generation) error.value = reason.message || '运维待办读取失败'; }
  finally { if (alive && current === generation) loading.value = false; }
}
function filterChanged() { page.value = 1; void reload(true); }
function changePage(value) { page.value = value; void reload(true); }
function showTask(task) {
  selected.value = task; note.value = ''; key.value = null; saveError.value = ''; changedElsewhere.value = false; open.value = true;
}
async function handle() {
  if (!selected.value?.can_handle || saving.value || changedElsewhere.value) return;
  if (note.value.trim().length < 2 || note.value.trim().length > 1000) { saveError.value = '请填写 2–1000 字的处理结果。'; return; }
  key.value ||= newIdempotencyKey('maintenance-handle');
  saving.value = true; saveError.value = '';
  try {
    const result = await deviceMaintenanceApi.handle(selected.value.task_id, { expected_version: selected.value.version, note: note.value.trim() }, key.value);
    if (!alive) return;
    selected.value = result; key.value = null; open.value = false;
    ElMessage.success('处理结果已记录。'); await reload(true);
  } catch (reason) {
    if (!alive) return;
    saveError.value = reason.message || '未确认提交结果，请使用原内容重试。';
    if (reason.code === 'MAINTENANCE_TASK_CHANGED' || reason.code === 'IDEMPOTENCY_REPLAY') {
      changedElsewhere.value = true;
      saveError.value = '该待办已有处理记录，请关闭此窗口，在“已反馈”中查看。';
    }
    await reload(true);
  } finally { if (alive) saving.value = false; }
}
function notificationStatus(value, receipt = false) {
  const labels = receipt ? { NOT_EXPECTED: '本次无需回执', PENDING: '等待回执', ACKNOWLEDGED: '已确认收到', TIMEOUT: '回执超时' } : { PENDING_DELIVERY: '待投递', SUBMITTED: '已提交渠道', DELIVERED: '已送达', FAILED: '投递失败' };
  return labels[value] || value || '尚无记录';
}
function attemptStatus(attempt) {
  if (attempt.outcome_state === 'UNKNOWN') return '通知结果未知';
  if (attempt.outcome_state === 'NOT_SENT') return '本次未发送';
  return notificationStatus(attempt.delivery_status);
}
function attemptTone(attempt) {
  if (['UNKNOWN', 'NOT_SENT'].includes(attempt.outcome_state)) return 'warning';
  return ({ DELIVERED: 'success', FAILED: 'danger', SUBMITTED: 'info', PENDING_DELIVERY: 'warning' })[attempt.delivery_status] || 'info';
}
function receiptStatus(value, outcome) {
  if (outcome === 'UNKNOWN') return '回执状态未知';
  if (outcome === 'NOT_SENT') return '尚未产生回执';
  return notificationStatus(value, true);
}
function simulatedNotification(snapshot) { return ['MOCK', 'SMS_SIMULATED', 'VOICE_SIMULATED'].includes(snapshot?.channel_type); }
function notificationChannel(snapshot) {
  return ({ NONE: '未配置', MOCK: '模拟通道', API: '系统接口', HTTP: '接口通知', SMS: '短信', VOICE: '语音电话',
    INTERNAL: '平台待办', SMS_SIMULATED: '模拟短信', VOICE_SIMULATED: '模拟语音' })[snapshot?.channel_type] || snapshot?.channel_type || '未记录';
}
function close(done) { if (!saving.value) done(); }
onBeforeUnmount(() => { alive = false; generation++; });
defineExpose({ reload });
</script>

<template>
  <el-card class="maintenance-panel">
    <template #header><div class="table-toolbar"><b>运维待办</b><div class="maintenance-tools">
      <el-select v-model="status" aria-label="待办状态" @change="filterChanged">
        <el-option label="待处理" value="PENDING" /><el-option label="已反馈" value="HANDLED" /><el-option label="全部" value="ALL" />
      </el-select><el-button :loading="loading" @click="reload(true)">刷新待办</el-button>
    </div></div></template>
    <ErrorAlert :message="error" @retry="reload(true)" />
    <p v-if="error && items.length" class="muted">刷新失败，下方保留上次读取的待办。</p>
    <el-table v-loading="loading" :data="items" empty-text="当前没有这类运维待办">
      <el-table-column label="异常设备" min-width="190"><template #default="{row}"><b>{{ row.device_name }}</b><div class="muted">{{ row.device_no }}</div><el-tag v-if="row.simulated" size="small" type="info">模拟设备</el-tag></template></el-table-column>
      <el-table-column prop="reason" label="异常说明" min-width="240" />
      <el-table-column label="上报时间" min-width="175"><template #default="{row}">{{ formatTime(row.reported_at) }}</template></el-table-column>
      <el-table-column label="状态" width="95"><template #default="{row}"><el-tag :type="row.status==='PENDING'?'warning':'success'">{{ row.status==='PENDING'?'待处理':'已反馈' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="130"><template #default="{row}"><el-button link type="primary" @click="showTask(row)">{{ row.can_handle ? '记录处理结果' : '查看待办' }}</el-button></template></el-table-column>
    </el-table>
    <div class="maintenance-pager"><span class="muted">共 {{ total }} 条</span><el-pagination :current-page="page" :page-size="10" :total="total" layout="prev, pager, next" @current-change="changePage" /></div>
    <el-dialog v-model="open" title="设备异常运维待办" width="min(680px, 94vw)" :before-close="close" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close>
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="设备">{{ selected.device_name }} · {{ selected.device_no }}</el-descriptions-item>
          <el-descriptions-item label="关联计划">{{ selected.plan_no || selected.plan_id || '无关联计划查看权限' }}</el-descriptions-item>
          <el-descriptions-item label="首次上报时连接状态">{{ statusText(selected.connectivity) }}</el-descriptions-item>
          <el-descriptions-item label="首次上报时健康状态">{{ ({GOOD:'良好',BAD:'异常',DEGRADED:'异常',UNKNOWN:'未知'})[selected.health_code] || '未知' }}</el-descriptions-item>
          <el-descriptions-item label="状态上报时间">{{ formatTime(selected.observed_at) }}</el-descriptions-item>
          <el-descriptions-item label="异常说明">{{ selected.reason }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.recipient_snapshot" label="最近通知接收单位">{{ selected.recipient_snapshot.org_name || selected.recipient_snapshot.recipient_name || '尚未确定接收单位' }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.recipient_snapshot?.contact_name" label="最近通知联系人">{{ selected.recipient_snapshot.contact_name }}<span v-if="selected.recipient_snapshot.contact_hint"> · {{ selected.recipient_snapshot.contact_hint }}</span></el-descriptions-item>
          <el-descriptions-item label="最新通知结果">{{ latestNotificationText }}<el-tag v-if="simulatedNotification(selected.recipient_snapshot)" class="notification-kind" size="small" type="info">模拟通知</el-tag><p v-if="previousDeliveries && latestNotification?.delivery_status !== 'DELIVERED'" class="muted">此前已有 {{ previousDeliveries }} 次送达记录，本次结果不改变历史送达事实。</p></el-descriptions-item>
          <el-descriptions-item v-if="selected.notification_receipt_status || latestNotification?.outcome_state === 'UNKNOWN'" label="最新通知回执">{{ receiptStatus(selected.notification_receipt_status, latestNotification?.outcome_state) }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.notification_blocked_reason" label="通知阻断原因">{{ selected.notification_blocked_reason }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.recipient_snapshot?.config_version != null" label="通知配置版本">{{ selected.recipient_snapshot.config_version }}</el-descriptions-item>
          <el-descriptions-item label="上报人 / 时间">{{ selected.reported_by_name }} · {{ formatTime(selected.reported_at) }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.handling_note" label="处理结果">{{ selected.handling_note }}<p class="muted">{{ selected.handled_by_name }} · {{ formatTime(selected.handled_at) }}</p></el-descriptions-item>
        </el-descriptions>
        <details v-if="notificationAttempts.length" :key="selected.task_id" class="maintenance-notifications">
          <summary><span class="expand-label">查看 {{ notificationAttempts.length }} 次通知记录</span><span class="collapse-label">收起通知记录</span></summary>
          <article v-for="attempt in notificationAttempts" :key="attempt.attempt_id" class="notification-attempt">
            <div class="notification-heading"><b>第 {{ attempt.attempt_no }} 次通知</b><el-tag :type="attemptTone(attempt)" size="small">{{ attemptStatus(attempt) }}</el-tag><el-tag v-if="simulatedNotification(attempt.recipient_snapshot)" size="small" type="info">模拟通知</el-tag><el-tag v-if="attempt.historical" size="small" type="info">历史资料转存</el-tag></div>
            <p v-if="attempt.historical" class="muted">沿用原待办保存的通知资料；未保存的投递与回执时间仍为未知。</p>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item :label="attempt.historical ? '原待办上报时间' : '通知提交时间'">{{ formatTime(attempt.requested_at) }}</el-descriptions-item>
              <el-descriptions-item :label="attempt.historical ? '原上报人' : '通知提交人'">{{ attempt.requested_by_name || '未记录' }}</el-descriptions-item>
              <el-descriptions-item v-if="attempt.reason" label="通知原因">{{ attempt.reason }}</el-descriptions-item>
              <template v-if="attempt.recipient_snapshot">
                <el-descriptions-item label="通知时的接收单位">{{ attempt.recipient_snapshot.org_name || attempt.recipient_snapshot.recipient_name || '未记录' }}</el-descriptions-item>
                <el-descriptions-item v-if="attempt.recipient_snapshot.contact_name" label="通知时的联系人">{{ attempt.recipient_snapshot.contact_name }}<span v-if="attempt.recipient_snapshot.contact_hint"> · {{ attempt.recipient_snapshot.contact_hint }}</span></el-descriptions-item>
                <el-descriptions-item label="通知渠道">{{ notificationChannel(attempt.recipient_snapshot) }}</el-descriptions-item>
                <el-descriptions-item v-if="attempt.recipient_snapshot.config_version != null" label="通知配置版本">{{ attempt.recipient_snapshot.config_version }}</el-descriptions-item>
                <el-descriptions-item v-if="attempt.recipient_snapshot.captured_at" label="接收资料记录时间">{{ formatTime(attempt.recipient_snapshot.captured_at) }}</el-descriptions-item>
              </template>
              <el-descriptions-item v-else label="接收资料">原记录未保存完整接收资料，不使用当前联系人补写。</el-descriptions-item>
              <el-descriptions-item v-if="attempt.submitted_at" label="渠道提交时间">{{ formatTime(attempt.submitted_at) }}</el-descriptions-item>
              <el-descriptions-item v-if="attempt.delivered_at" label="送达时间">{{ formatTime(attempt.delivered_at) }}</el-descriptions-item>
              <el-descriptions-item label="回执情况">{{ receiptStatus(attempt.receipt_status, attempt.outcome_state) }}</el-descriptions-item>
              <el-descriptions-item v-if="attempt.acknowledged_at" label="回执确认时间">{{ formatTime(attempt.acknowledged_at) }}</el-descriptions-item>
              <el-descriptions-item v-if="attempt.receipt_result" label="回执内容">{{ attempt.receipt_result }}</el-descriptions-item>
              <el-descriptions-item v-if="attempt.blocked_reason" label="未完成原因">{{ attempt.blocked_reason }}</el-descriptions-item>
            </el-descriptions>
          </article>
        </details>
        <template v-if="selected.can_handle && !changedElsewhere">
          <p class="muted">记录已做的检查、处理及后续安排。提交后待办移至“已反馈”，设备是否恢复仍以实时监测和恢复核验为准。</p>
          <el-input v-model="note" type="textarea" :rows="4" :maxlength="1000" show-word-limit :disabled="saving || !!key" placeholder="填写处理结果" aria-label="处理结果" />
          <p v-if="key && !saving" class="muted">上次提交结果尚未确认，请保留原内容重试。</p>
        </template>
        <p v-else-if="selected.status==='PENDING' && !changedElsewhere" class="muted">当前账号只能查看；处理需要设备监测操作权限。</p>
        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
      </template>
      <template #footer><el-button :disabled="saving" @click="open=false">关闭</el-button><el-button v-if="selected?.can_handle && !changedElsewhere" type="primary" :loading="saving" @click="handle">{{ key ? '重试确认结果' : '提交处理结果' }}</el-button></template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.maintenance-tools { display: flex; gap: 8px; flex-wrap: wrap; }
.maintenance-tools .el-select { width: 120px; }
.maintenance-pager { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; margin-top: 12px; gap: 8px; }
.maintenance-panel :deep(.el-table .cell) { overflow-wrap: anywhere; white-space: normal; word-break: break-word; }
.maintenance-panel :deep(.el-descriptions__content) { overflow-wrap: anywhere; white-space: pre-wrap; }
.notification-kind { margin-left: 8px; }
.maintenance-notifications { margin: 16px 0; }
.maintenance-notifications > summary { width: fit-content; max-width: 100%; color: var(--el-color-primary); cursor: pointer; line-height: 1.8; overflow-wrap: anywhere; }
.maintenance-notifications > summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.maintenance-notifications .collapse-label,.maintenance-notifications[open] .expand-label { display: none; }
.maintenance-notifications[open] .collapse-label { display: inline; }
.notification-attempt { margin-top: 14px; }
.notification-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin-bottom: 8px; }
.notification-heading :deep(.el-tag),.notification-kind { height: auto; max-width: 100%; white-space: normal; overflow-wrap: anywhere; line-height: 1.6; }
</style>
