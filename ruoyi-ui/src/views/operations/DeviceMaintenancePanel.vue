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
      <el-table-column prop="reason" label="异常说明" min-width="240" show-overflow-tooltip />
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
          <el-descriptions-item label="通知时的连接状态">{{ statusText(selected.connectivity) }}</el-descriptions-item>
          <el-descriptions-item label="通知时的健康状态">{{ ({GOOD:'良好',BAD:'异常',DEGRADED:'异常',UNKNOWN:'未知'})[selected.health_code] || '未知' }}</el-descriptions-item>
          <el-descriptions-item label="状态上报时间">{{ formatTime(selected.observed_at) }}</el-descriptions-item>
          <el-descriptions-item label="异常说明">{{ selected.reason }}</el-descriptions-item>
          <el-descriptions-item label="上报人 / 时间">{{ selected.reported_by_name }} · {{ formatTime(selected.reported_at) }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.handling_note" label="处理结果">{{ selected.handling_note }}<p class="muted">{{ selected.handled_by_name }} · {{ formatTime(selected.handled_at) }}</p></el-descriptions-item>
        </el-descriptions>
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
.maintenance-panel :deep(.el-descriptions__content) { overflow-wrap: anywhere; white-space: pre-wrap; }
</style>
