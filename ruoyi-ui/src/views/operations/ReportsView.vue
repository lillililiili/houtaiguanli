<script setup>
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Download, RefreshRight, Calendar } from '@element-plus/icons-vue';
import PageHeader from '@/components/PageHeader.vue';
import MetricCards from '@/components/MetricCards.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import ReportChart from '@/components/ReportChart.vue';
import ReportDetailTable from '@/components/ReportDetailTable.vue';
import { businessReportApi, businessReportFilename, createLatestRequestGuard } from '@/api/reports.js';
import { isFuturePickerDate, normalizeAnchor, pickerValue, shanghaiToday } from '@/utils/reportPeriods.js';

const categories = [
  { value: 'OVERVIEW', label: '综合运行', description: '目标、告警、风险与飞行活动的整体概览' },
  { value: 'DEVICE_OPERATIONS', label: '设备运维', description: '设备当前状态与周期内运维待办' },
  { value: 'ALARM_RISK', label: '告警与风险', description: '告警和风险独立统计，关注等级与处理进展' },
  { value: 'FLIGHT_VERIFICATION', label: '飞行计划与核验', description: '计划执行状态与最新核验结果' },
  { value: 'EVENT_DISPOSAL', label: '事件处置', description: '事件、处置授权、执行结果与交接记录' }
];
const periods = [{ value: 'DAILY', label: '日报' }, { value: 'WEEKLY', label: '周报' }, { value: 'MONTHLY', label: '月报' }];
const category = ref('OVERVIEW'), period = ref('MONTHLY'), selectedDate = ref(pickerValue('MONTHLY'));
const preview = ref(null), loading = ref(false), exporting = ref(''), error = ref(''), refresh = ref(0);
const guard = createLatestRequestGuard();
const datePickerType = computed(() => period.value === 'MONTHLY' ? 'month' : 'date');
const dateFormat = computed(() => period.value === 'MONTHLY' ? 'YYYY-MM' : 'YYYY-MM-DD');
const anchor = computed(() => { try { return normalizeAnchor(period.value, selectedDate.value); } catch { return ''; } });
const params = computed(() => ({ report_category: category.value, period_type: period.value, anchor_date: anchor.value }));
const queryKey = computed(() => JSON.stringify(params.value));
const ready = computed(() => !loading.value && !error.value && preview.value &&
  preview.value.report_category === category.value && preview.value.period_type === period.value && preview.value.anchor_date === anchor.value);
const description = computed(() => categories.find(item => item.value === category.value).description);
const sections = computed(() => preview.value?.sections || []);
const allowed = computed(() => sections.value.filter(s => s.accessible));
const empty = computed(() => allowed.value.length > 0 && allowed.value.every(s => s.total === 0));
const tones = ['blue', 'red', 'purple', 'green', 'amber'];
const metrics = computed(() => {
  const result = sections.value.map((s, i) => ({ label: s.title, value: s.accessible ? s.total.toLocaleString('zh-CN') : '无权限',
    note: s.snapshot ? '当前设备快照' : s.basis, tone: tones[i % tones.length] }));
  if (category.value !== 'OVERVIEW') {
    for (const s of allowed.value) {
      const counts = s.distributions.find(d => d.key === 'state')?.items || [];
      for (const count of counts.slice(0, 2)) result.push({ label: `${s.title} · ${label(count.name, s.key, 'state')}`, value: count.value.toLocaleString('zh-CN'), tone: 'blue', note: '状态截至生成时' });
    }
  }
  return result;
});
const palette = ['#1677ff', '#d93d4c', '#7457d6', '#16875b', '#c57813'];
function label(value, section, field) { return preview.value?.labels?.[`${section}.${field}.${value}`] || preview.value?.labels?.[value] || value || '未知'; }
function generated(value) {
  return value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium', hour12: false }).format(new Date(value)) : '—';
}
function trend(sources) {
  const plotted = sources.filter(s => !s.snapshot && s.accessible);
  const days = plotted[0]?.days || [];
  return {
    color: palette, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { type: 'scroll', top: 0 },
    grid: { left: 15, right: 20, top: 45, bottom: 15, containLabel: true },
    xAxis: { type: 'category', data: days.map(d => d.date.slice(5)), boundaryGap: days.length === 1 },
    yAxis: { type: 'value', minInterval: 1 },
    series: plotted.map(s => ({ name: s.title, type: days.length === 1 ? 'bar' : 'line', barMaxWidth: 40,
      symbolSize: 6, data: s.days.map(d => d.value), smooth: false }))
  };
}
function bars(items, section, field) {
  return {
    color: palette, tooltip: { trigger: 'axis', renderMode: 'richText' },
    grid: { left: 15, right: 25, top: 20, bottom: 15, containLabel: true },
    xAxis: { type: 'value', minInterval: 1 }, yAxis: { type: 'category', inverse: true, data: items.map(i => label(i.name, section, field)),
      axisLabel: { width: 125, overflow: 'truncate' } },
    dataZoom: items.length > 10 ? [{ type: 'slider', yAxisIndex: 0, startValue: 0, endValue: 9, right: 0 }] : [],
    series: [{ type: 'bar', barMaxWidth: 28, label: { show: true, position: 'right' }, data: items.map(i => i.value) }]
  };
}
const regionRows = computed(() => {
  const names = new Set();
  allowed.value.forEach(s => s.distributions.find(d => d.key === 'region')?.items.forEach(i => names.add(i.name)));
  return [...names].map(name => {
    const row = { name: label(name) };
    sections.value.forEach(s => { row[s.key] = s.accessible ? (s.distributions.find(d => d.key === 'region')?.items.find(i => i.name === name)?.value || 0) : '无权限'; });
    return row;
  });
});
const dayRows = computed(() => (allowed.value.find(s => s.days.length)?.days || []).map((day, index) => {
  const row = { date: day.date };
  sections.value.forEach(s => { row[s.key] = s.accessible ? s.days[index]?.value ?? 0 : '无权限'; });
  return row;
}));
async function loadPreview() {
  const current = guard.begin();
  preview.value = null; error.value = '';
  if (!anchor.value) { loading.value = false; return; }
  loading.value = true;
  try {
    const data = await businessReportApi.preview({ ...params.value });
    if (guard.isCurrent(current)) { preview.value = data; refresh.value++; }
  } catch (e) { if (guard.isCurrent(current)) error.value = e.message || '报表加载失败，请重试。'; }
  finally { if (guard.isCurrent(current)) loading.value = false; }
}
async function exportReport(format) {
  if (!ready.value || exporting.value) return;
  const filter = { ...params.value }, filename = businessReportFilename(preview.value, format);
  exporting.value = format;
  try { await businessReportApi.exportFile(filter, format, filename); ElMessage.success(`${format === 'pdf' ? 'PDF' : 'Excel'} 报表已下载。`); }
  catch (e) { ElMessage.error(e.message || '下载失败，请重试。'); }
  finally { exporting.value = ''; }
}
watch(period, value => { selectedDate.value = pickerValue(value, shanghaiToday()); });
watch(queryKey, loadPreview, { immediate: true, flush: 'post' });
</script>

<template>
  <section class="page-stack report-page">
    <PageHeader title="报表管理" description="按业务类型和统计周期生成报表；Excel 用于数据分析，PDF 用于图文汇报。">
      <el-button :icon="RefreshRight" :loading="loading" @click="loadPreview">刷新预览</el-button>
      <el-button :icon="Download" :loading="exporting === 'xlsx'" :disabled="!ready || !!exporting" @click="exportReport('xlsx')">导出 Excel</el-button>
      <el-button type="primary" :icon="Download" :loading="exporting === 'pdf'" :disabled="!ready || !!exporting" @click="exportReport('pdf')">下载 PDF</el-button>
    </PageHeader>
    <section class="report-controls" aria-label="报表类型与周期">
      <div class="category-heading"><strong>报表类型</strong><span>{{ description }}</span></div>
      <el-radio-group v-model="category" class="category-tabs" aria-label="选择报表类型">
        <el-radio-button v-for="item in categories" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button>
      </el-radio-group>
      <div class="period-row">
        <strong>报表周期</strong>
        <el-radio-group v-model="period" aria-label="选择报表周期"><el-radio-button v-for="item in periods" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button></el-radio-group>
        <el-date-picker v-model="selectedDate" :type="datePickerType" :value-format="dateFormat" :clearable="false"
          :placeholder="period === 'WEEKLY' ? '选择周内日期' : '选择日期'" :disabled-date="date => isFuturePickerDate(date, period)" aria-label="选择统计日期" />
        <div class="period-range"><el-icon><Calendar /></el-icon><div><strong>{{ preview?.period_label || '正在计算统计区间…' }}</strong><small>{{ preview ? preview.from + ' 至 ' + preview.to : '当前周期统计至今日' }}</small></div></div>
      </div>
    </section>
    <ErrorAlert :message="error" @retry="loadPreview" />
    <div v-loading="loading" class="report-content" :aria-busy="loading" element-loading-text="正在汇总业务数据…">
      <template v-if="ready">
        <div class="report-meta"><span>数据来源 <el-tag :type="preview.source_mode === 'live' ? 'success' : 'warning'" effect="plain">{{ label(preview.source_mode) }}</el-tag></span><span>生成时间 {{ generated(preview.generated_at) }}（上海）</span></div>
        <el-alert v-if="preview.simulated" type="warning" :closable="false" show-icon title="包含模拟或回放记录，不可作为现场正式报表。" />
        <el-alert v-if="empty" type="info" :closable="false" show-icon title="当前统计范围暂无业务记录，仍可导出完整结构的报表。" />
        <p class="report-note">{{ preview.status_note }}</p>
        <MetricCards :items="metrics" />
        <template v-if="category === 'OVERVIEW'">
          <el-card><template #header><strong>每日运行趋势</strong></template><ReportChart :option="trend(sections)" :empty="!allowed.length || empty" empty-text="暂无可展示的业务数据" aria-label="每日运行趋势" /></el-card>
          <div class="overview-tables">
            <el-card><template #header><strong>区域汇总</strong></template><el-table :data="regionRows" stripe empty-text="暂无区域数据"><el-table-column prop="name" label="区域" min-width="140" /><el-table-column v-for="s in sections" :key="s.key" :prop="s.key" :label="s.title" min-width="110" /></el-table></el-card>
            <el-card><template #header><strong>按日汇总</strong></template><el-table :data="dayRows" stripe max-height="420"><el-table-column prop="date" label="日期" min-width="120" /><el-table-column v-for="s in sections" :key="s.key" :prop="s.key" :label="s.title" min-width="110" /></el-table></el-card>
          </div>
        </template>
        <template v-else>
          <section v-for="s in allowed" :key="s.key" class="business-section">
            <div class="section-heading"><h2>{{ s.title }}</h2><span>{{ s.basis }}</span></div>
            <div class="report-charts">
              <el-card v-if="!s.snapshot"><template #header><strong>每日趋势</strong></template><ReportChart :option="trend([s])" :empty="s.total === 0" :aria-label="`${s.title}每日趋势`" /></el-card>
              <el-card v-for="d in s.distributions" :key="d.key"><template #header><strong>{{ d.title }}</strong></template><ReportChart :option="bars(d.items, s.key, d.key)" :empty="!d.items.length" :aria-label="`${s.title}${d.title}`" /></el-card>
            </div>
            <ReportDetailTable :params="params" :section="s" :columns="preview.columns[s.key]" :labels="preview.labels" :refresh="refresh" />
          </section>
        </template>
        <p class="report-note">Excel 包含完整明细（最多 5 万条）；PDF 包含图表、摘要及每个明细分区最近 50 条。导出时即时生成，数据变化时可能与本次预览不同。</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.report-controls { padding:22px 24px; border:1px solid #cbdff5; border-left:4px solid #1677ff; border-radius:14px; background:var(--el-bg-color); }
.category-heading { display:flex; align-items:center; gap:20px; margin-bottom:15px; }
.category-heading span,.section-heading span { color:var(--el-text-color-secondary); font-size:13px; }
.category-tabs { display:flex; flex-wrap:wrap; gap:8px; }
.category-tabs :deep(.el-radio-button__inner) { border:1px solid #dbe5ef; border-radius:7px; padding:12px 22px; box-shadow:none; }
.period-row { display:flex; flex-wrap:wrap; gap:14px; align-items:center; margin-top:20px; padding-top:20px; border-top:1px solid #e8eef5; }
.period-row :deep(.el-date-editor) { width:175px; }
.period-range { display:flex; gap:12px; align-items:center; margin-left:auto; }
.period-range .el-icon { color:#1677ff; font-size:24px; }
.period-range small { display:block; color:var(--el-text-color-secondary); margin-top:5px; }
.report-content { min-height:220px; display:grid; gap:18px; }
.report-meta { display:flex; flex-wrap:wrap; justify-content:space-between; gap:12px; color:var(--el-text-color-secondary); font-size:13px; }
.report-note { margin:0; color:var(--el-text-color-secondary); font-size:13px; line-height:1.7; }
.report-charts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }
.business-section { display:grid; gap:16px; }
.section-heading { display:flex; flex-wrap:wrap; align-items:baseline; gap:15px; }
.section-heading h2 { margin:8px 0 0; font-size:18px; }
.overview-tables { display:grid; gap:18px; }
.report-page :deep(.el-card) { border-radius:12px; }
@media(max-width:900px) { .report-charts { grid-template-columns:1fr; } .period-range { width:100%; margin-left:0; } .category-heading { align-items:flex-start; flex-direction:column; gap:8px; } .report-controls { padding:18px; } }
</style>
