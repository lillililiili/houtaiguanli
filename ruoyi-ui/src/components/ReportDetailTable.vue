<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { businessReportApi, createLatestRequestGuard, reportLabel, reportRowMatchesScope } from '@/api/reports.js';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { formatBusinessNo, formatDeviceNo } from '@/utils/format.js';
const props = defineProps({
  params: { type: Object, required: true }, section: { type: Object, required: true },
  columns: { type: Array, required: true }, labels: { type: Object, required: true }, refresh: { type: Number, default: 0 }
});
const page = ref(1), rows = ref([]), total = ref(0), loading = ref(false), error = ref('');
const guard = createLatestRequestGuard();
const queryKey = computed(() => JSON.stringify([props.params, props.section.key, props.refresh]));
function cell(row, field) {
  const value = row[field];
  if (field === 'label' && ['targets', 'alarms', 'risks', 'events'].includes(props.section.key)) return formatBusinessNo(value) || row.id;
  // 设备编号与设备管理页同一显示写法（导出由后端 DeviceDisplayNo 按同一算法生成）。
  if (field === 'related' && ['devices', 'maintenance'].includes(props.section.key)) return formatDeviceNo(value);
  // 处置授权、事件交接的关联告警编号和告警页同一写法（去掉“告警-”前缀）。
  if (field === 'related' && ['authorizations', 'handoffs'].includes(props.section.key)) return formatBusinessNo(value) || '—';
  if (field === 'occurred_at') return value == null ? '—' : new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false
  }).format(new Date(value));
  if (['state', 'kind', 'severity', 'result', 'source_mode'].includes(field)) return reportLabel(props.labels, props.section.key, field, value);
  return value || (field === 'label' ? row.id : '—');
}
async function load() {
  const current = guard.begin();
  const filter = { ...props.params, section: props.section.key, page: page.value, size: 20 };
  loading.value = true; error.value = ''; rows.value = []; total.value = 0;
  try {
    const data = await businessReportApi.details(filter);
    if (!guard.isCurrent(current)) return;
    if (!Array.isArray(data.items) || !data.items.every(row => reportRowMatchesScope(row.source_mode, filter.source_mode)))
      throw new Error('明细数据不符合当前来源口径，已停止展示。');
    rows.value = data.items; total.value = data.total;
  } catch (e) {
    if (guard.isCurrent(current)) { error.value = e.message || '明细加载失败'; total.value = 0; }
  } finally { if (guard.isCurrent(current)) loading.value = false; }
}
watch(queryKey, () => { page.value = 1; load(); }, { immediate: true });
onBeforeUnmount(() => guard.begin());
function changePage(value) { page.value = value; load(); }
</script>
<template>
  <el-card class="report-details">
    <template #header><div class="detail-heading"><strong>{{ section.title }}明细</strong><span>共 {{ total }} 条 · 每页 20 条</span></div></template>
    <ErrorAlert :message="error" @retry="load" />
    <el-table v-loading="loading" :data="rows" stripe :aria-label="`${section.title}明细`" empty-text="当前周期暂无记录">
      <el-table-column v-for="column in columns" :key="column.field" :label="column.label" :min-width="column.field === 'note' || column.field === 'result' ? 180 : 140" show-overflow-tooltip>
        <template #default="{ row }">{{ cell(row, column.field) }}</template>
      </el-table-column>
    </el-table>
    <el-pagination :current-page="page" :page-size="20" :total="total" :disabled="loading" layout="total, prev, pager, next" @current-change="changePage" />
  </el-card>
</template>
<style scoped>
.detail-heading { display:flex; justify-content:space-between; gap:16px; }
.detail-heading span { color:var(--el-text-color-secondary); font-size:13px; }
.el-pagination { margin-top:18px; justify-content:flex-end; }
</style>
