<script setup>
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Calendar, Download, RefreshRight, TrendCharts, WarningFilled } from '@element-plus/icons-vue';
import PageHeader from '@/components/PageHeader.vue';
import MetricCards from '@/components/MetricCards.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import ReportChart from '@/components/ReportChart.vue';
import { createLatestRequestGuard, reportApi, reportFilename } from '@/api/reports.js';
import { isFuturePickerDate, normalizeAnchor, pickerValue, shanghaiToday } from '@/utils/reportPeriods.js';

const reportTypes = [
  { value: 'DAILY', label: '日报' },
  { value: 'WEEKLY', label: '周报' },
  { value: 'MONTHLY', label: '月报' }
];
const type = ref('MONTHLY');
const selectedDate = ref(pickerValue('MONTHLY'));
const preview = ref(null);
const loading = ref(false);
const exporting = ref(false);
const error = ref('');
const requestGuard = createLatestRequestGuard();

const report = computed(() => preview.value?.report || null);
const summary = computed(() => report.value?.summary || {});
const datePickerType = computed(() => type.value === 'MONTHLY' ? 'month' : 'date');
const dateValueFormat = computed(() => type.value === 'MONTHLY' ? 'YYYY-MM' : 'YYYY-MM-DD');
const datePlaceholder = computed(() => ({ DAILY: '选择日期', WEEKLY: '选择周内日期', MONTHLY: '选择月份' })[type.value]);
const currentAnchor = computed(() => {
  try { return normalizeAnchor(type.value, selectedDate.value); } catch { return ''; }
});
const previewReady = computed(() => !loading.value && !error.value && preview.value
  && preview.value.reportType === type.value && preview.value.anchorDate === currentAnchor.value);
const shortTrend = computed(() => (report.value?.days?.length || 0) < 4);
const noBusinessData = computed(() => !report.value || Object.values(summary.value).every(value => Number(value || 0) === 0));

const metrics = computed(() => [
  { label: '目标总次数', value: number(summary.value.total), note: '周期内感知目标' },
  { label: '非法飞行', value: number(summary.value.illegal), tone: 'red', note: rate(summary.value.illegal, summary.value.total) },
  { label: '处罚案件', value: number(summary.value.punish), tone: 'amber', note: '已形成案件' },
  { label: '高风险目标', value: number(summary.value.highRisk), tone: 'purple', note: rate(summary.value.highRisk, summary.value.total) },
  { label: '无人机', value: number(summary.value.uav), tone: 'green', note: '识别类型' },
  { label: '异常目标', value: number(summary.value.abnormal), tone: 'red', note: '需关注目标' }
]);

const axisText = { color: '#64748b', fontSize: 11 };
const splitLine = { lineStyle: { color: '#e6edf5' } };
const chartColors = ['#1677ff', '#d93d4c', '#c57813', '#7457d6', '#16875b', '#20a7d8'];
const trendOption = computed(() => ({
  color: chartColors,
  tooltip: { trigger: 'axis' },
  legend: { top: 0, textStyle: axisText },
  grid: { top: 44, right: 20, bottom: 28, left: 46, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: report.value?.days.map(item => item.md) || [], axisLabel: axisText, axisLine: { lineStyle: { color: '#cad7e6' } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: axisText, splitLine },
  series: [
    lineSeries('目标总次数', 'total', 'solid', 3),
    lineSeries('非法飞行', 'illegal', 'dashed', 2),
    lineSeries('处罚案件', 'punish', 'dotted', 2),
    lineSeries('高风险目标', 'highRisk', 'dashed', 2)
  ]
}));
const riskOption = computed(() => barOption(report.value?.byRisk, ['#9f2334', '#d93d4c', '#d99022', '#2f86f6', '#8c9aac']));
const typeOption = computed(() => barOption(report.value?.byType, ['#1677ff']));
const durationOption = computed(() => barOption(report.value?.byDuration));
const trackOption = computed(() => barOption(report.value?.byTrack, ['#168f84']));
const altitudeOption = computed(() => barOption(report.value?.altBands, ['#0e5ed7']));
const penaltyOption = computed(() => donutOption(report.value?.byPenalty, ['#d99022', '#d93d4c', '#1677ff']));

function lineSeries(name, key, lineType, width) {
  return {
    name, type: 'line', smooth: false, showSymbol: true, symbolSize: 5,
    lineStyle: { type: lineType, width }, emphasis: { focus: 'series' },
    data: report.value?.days.map(item => item[key]) || []
  };
}
function barOption(rows = [], colors = chartColors) {
  return {
    color: colors,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { top: 18, right: 24, bottom: 26, left: 18, containLabel: true },
    xAxis: { type: 'category', data: (rows || []).map(item => item.name), axisLabel: { ...axisText, interval: 0, hideOverlap: true }, axisLine: { lineStyle: { color: '#cad7e6' } } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: axisText, splitLine },
    series: [{ type: 'bar', barMaxWidth: 34, data: (rows || []).map((item, index) => ({ value: item.value, itemStyle: { color: colors[index % colors.length], borderRadius: [6, 6, 0, 0] } })), label: { show: true, position: 'top', color: '#33435b' } }]
  };
}
function donutOption(rows = [], colors = chartColors) {
  return {
    color: colors,
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c}（{d}%）' },
    legend: { type: 'scroll', bottom: 0, textStyle: axisText },
    series: [{
      type: 'pie', radius: ['43%', '67%'], center: ['50%', '43%'], minAngle: 3,
      avoidLabelOverlap: true, label: { show: true, formatter: '{b}\n{c}', color: '#33435b', fontSize: 11 },
      labelLine: { length: 8, length2: 6 }, data: rows || []
    }]
  };
}
function hasCounts(rows) { return (rows || []).some(item => Number(item.value || 0) > 0); }
function number(value) { return new Intl.NumberFormat('zh-CN').format(Number(value || 0)); }
function money(value) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(value || 0)); }
function rate(value, total) { return total ? `占总量 ${(Number(value || 0) * 100 / total).toFixed(1)}%` : '占总量 0%'; }
function generatedTime(value) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(value));
}
function sourceLabel(mode) {
  return ({ live: '真实数据', mock: '模拟数据', mixed: '混合数据' })[mode] || '暂无样本';
}
function sourceTag(mode) { return mode === 'live' ? 'success' : mode === 'unknown' ? 'info' : 'warning'; }
function disableFuture(time) { return isFuturePickerDate(time, type.value); }

async function loadPreview() {
  if (!currentAnchor.value) return;
  const current = requestGuard.begin();
  loading.value = true;
  error.value = '';
  try {
    const data = await reportApi.preview({ report_type: type.value, anchor_date: currentAnchor.value });
    if (!requestGuard.isCurrent(current)) return;
    preview.value = data;
  } catch (e) {
    if (!requestGuard.isCurrent(current)) return;
    preview.value = null;
    error.value = `${e.message || '报表预览加载失败。'} 请检查筛选条件或网络后重试。`;
  } finally {
    if (requestGuard.isCurrent(current)) loading.value = false;
  }
}

async function exportReport() {
  if (!previewReady.value) return;
  exporting.value = true;
  try {
    await reportApi.exportExcel({ report_type: type.value, anchor_date: currentAnchor.value }, reportFilename(preview.value));
    ElMessage.success('Excel 报表已导出。');
  } catch (e) {
    ElMessage.error(`${e.message || '导出失败。'} 请保持当前条件并重新点击导出。`);
  } finally { exporting.value = false; }
}

watch(type, value => { selectedDate.value = pickerValue(value, shanghaiToday()); });
watch([type, selectedDate], loadPreview, { immediate: true, flush: 'post' });
</script>

<template>
  <section class="page-stack report-page">
    <PageHeader title="报表管理" description="即时预览运行统计，并按日报、自然周报或自然月报导出 Excel；当前周期统计至今日。">
      <el-button :icon="RefreshRight" :loading="loading" @click="loadPreview">刷新预览</el-button>
      <el-button type="primary" :icon="Download" :loading="exporting" :disabled="!previewReady" @click="exportReport">导出 Excel</el-button>
    </PageHeader>

    <section class="report-period-card" aria-label="报表周期与数据状态">
      <div class="period-controls">
        <span class="section-kicker">报表周期</span>
        <el-radio-group v-model="type" aria-label="选择报表类型">
          <el-radio-button v-for="item in reportTypes" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button>
        </el-radio-group>
        <el-date-picker v-model="selectedDate" :type="datePickerType" :value-format="dateValueFormat" :placeholder="datePlaceholder" :disabled-date="disableFuture" :clearable="false" aria-label="选择报表日期" />
      </div>
      <div class="period-summary" aria-live="polite">
        <span class="period-icon" aria-hidden="true"><el-icon><Calendar /></el-icon></span>
        <div><small>实际统计区间</small><strong>{{ preview?.periodLabel || '正在计算…' }}</strong><span>{{ report ? `${report.from} 至 ${report.to}` : '—' }}</span></div>
      </div>
      <dl class="report-meta">
        <div><dt>数据来源</dt><dd><el-tag size="small" effect="plain" :type="sourceTag(report?.sourceMode)">{{ sourceLabel(report?.sourceMode) }}</el-tag></dd></div>
        <div><dt>生成时间</dt><dd><time :datetime="preview?.generatedAt ? new Date(preview.generatedAt).toISOString() : ''">{{ generatedTime(preview?.generatedAt) }}</time></dd></div>
      </dl>
    </section>

    <ErrorAlert :message="error" @retry="loadPreview" />
    <el-alert v-if="report?.simulated" type="warning" :closable="false" show-icon>
      <template #title><strong>当前预览含模拟数据，不可作为现场正式报表。</strong></template>
      请接入并核验真实业务数据后，再用于归档或对外报送。
    </el-alert>
    <el-alert v-else-if="report && noBusinessData" type="info" :closable="false" show-icon title="当前周期暂无业务数据，仍可导出包含完整结构和零值的 Excel 报表。" />

    <div class="report-data" v-loading="loading" element-loading-text="正在生成报表预览…">
      <template v-if="report">
        <MetricCards :items="metrics" />

        <div class="report-grid report-grid--top">
          <el-card class="chart-card chart-card--wide">
            <template #header><div class="card-heading"><div><b>每日运行趋势</b><span>目标、非法飞行、处罚和高风险</span></div><el-icon><TrendCharts /></el-icon></div></template>
            <div v-if="shortTrend" class="short-trend">
              <span class="short-trend__icon"><el-icon><TrendCharts /></el-icon></span>
              <div><b>{{ report.days.length === 1 ? '单日概览' : '数据点不足，暂不绘制趋势线' }}</b><p>当前仅 {{ report.days.length }} 个数据点。目标 {{ number(summary.total) }} 次、非法飞行 {{ number(summary.illegal) }} 次、处罚 {{ number(summary.punish) }} 起。</p></div>
            </div>
            <ReportChart v-else :option="trendOption" aria-label="每日运行趋势折线图" height="310px" />
          </el-card>
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>风险等级</b><span>各等级目标数量</span></div></div></template><ReportChart :option="riskOption" :empty="!hasCounts(report.byRisk)" empty-text="暂无风险分级数据" aria-label="风险等级柱状图" /></el-card>
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>目标类型</b><span>各识别类型目标数量</span></div></div></template><ReportChart :option="typeOption" :empty="!hasCounts(report.byType)" empty-text="暂无目标类型数据" aria-label="目标类型柱状图" /></el-card>
        </div>

        <div class="report-grid report-grid--three">
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>飞行高度</b><span>海拔高度区间（米）</span></div><em>{{ number(report.altTotal) }} 个样本</em></div></template><ReportChart :option="altitudeOption" :empty="!hasCounts(report.altBands)" empty-text="暂无高度数据" aria-label="飞行高度分布柱状图" /></el-card>
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>飞行时长</b><span>持续时长区间（分钟）</span></div></div></template><ReportChart :option="durationOption" :empty="!hasCounts(report.byDuration)" empty-text="暂无时长数据" aria-label="飞行时长分布柱状图" /></el-card>
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>轨迹长度</b><span>飞行轨迹区间（公里）</span></div></div></template><ReportChart :option="trackOption" :empty="!hasCounts(report.byTrack)" empty-text="暂无轨迹数据" aria-label="轨迹长度分布柱状图" /></el-card>
        </div>

        <div class="report-grid report-grid--bottom">
          <el-card class="table-card chart-card--wide">
            <template #header><div class="card-heading"><div><b>区域统计</b><span>按目标总量降序</span></div><em>{{ report.regions.length }} 个区域</em></div></template>
            <el-table :data="report.regions" max-height="330" empty-text="暂无区域统计数据">
              <el-table-column prop="name" label="区域" min-width="128" fixed="left" /><el-table-column label="目标总次数" min-width="104" align="right"><template #default="{row}">{{ number(row.total) }}</template></el-table-column>
              <el-table-column label="非法飞行" min-width="96" align="right"><template #default="{row}"><span class="danger-value">{{ number(row.illegal) }}</span></template></el-table-column>
              <el-table-column label="处罚案件" min-width="96" align="right"><template #default="{row}">{{ number(row.punish) }}</template></el-table-column>
              <el-table-column label="高风险" min-width="88" align="right"><template #default="{row}">{{ number(row.highRisk) }}</template></el-table-column>
            </el-table>
          </el-card>
          <el-card class="chart-card"><template #header><div class="card-heading"><div><b>处罚构成</b><span>警告、罚款与驱离</span></div></div></template><ReportChart :option="penaltyOption" :empty="!hasCounts(report.byPenalty)" empty-text="暂无处罚数据" aria-label="处罚构成环形图" /></el-card>
          <el-card class="table-card">
            <template #header><div class="card-heading"><div><b>违规主体排行</b><span>按案件数与罚款金额</span></div></div></template>
            <el-table :data="report.partners" max-height="330" empty-text="暂无违规主体数据">
              <el-table-column type="index" label="#" width="46" /><el-table-column prop="name" label="主体" min-width="125" show-overflow-tooltip />
              <el-table-column label="案件" width="70" align="right"><template #default="{row}">{{ number(row.caseCount) }}</template></el-table-column>
              <el-table-column label="罚款" min-width="100" align="right"><template #default="{row}">{{ money(row.fine) }}</template></el-table-column>
            </el-table>
          </el-card>
        </div>

        <p class="report-footnote"><el-icon><WarningFilled /></el-icon>页面图表仅用于即时预览；导出的 Excel 包含报表摘要、每日趋势、分类分布、区域与处置四张工作表。</p>
      </template>
      <el-card v-else-if="!error" class="report-placeholder"><el-skeleton :rows="8" animated /></el-card>
    </div>
  </section>
</template>

<style scoped>
.report-page{padding-bottom:4px}.report-period-card{display:grid;grid-template-columns:minmax(390px,1.2fr) minmax(300px,.85fr) minmax(275px,.8fr);align-items:center;gap:18px;padding:15px 18px;border:1px solid #cad7e8;border-left:4px solid var(--admin-primary);border-radius:9px;background:linear-gradient(102deg,#f8fbff,#fff 62%);box-shadow:0 4px 16px rgba(30,64,175,.05)}.period-controls{display:flex;align-items:center;gap:10px;min-width:0}.section-kicker{flex:none;color:#334155;font-size:12px;font-weight:700;letter-spacing:.08em}.period-controls :deep(.el-date-editor){width:158px}.period-summary{display:flex;min-width:0;align-items:center;gap:11px;padding-left:18px;border-left:1px solid var(--admin-border)}.period-icon{display:grid;width:38px;height:38px;flex:none;place-items:center;border-radius:9px;color:#fff;background:linear-gradient(135deg,#1e40af,#2563eb);font-size:18px}.period-icon>.el-icon{color:inherit;font-size:inherit}.period-summary div{min-width:0}.period-summary>div>small,.period-summary>div>strong,.period-summary>div>span{display:block}.period-summary>div>small,.report-meta dt{color:var(--admin-muted);font-size:11px}.period-summary>div>strong{margin:3px 0;color:#172033;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.period-summary>div>span{color:var(--admin-muted);font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:11px}.report-meta{display:grid;grid-template-columns:1fr;gap:8px;margin:0;padding-left:18px;border-left:1px solid var(--admin-border)}.report-meta div{display:grid;grid-template-columns:62px minmax(0,1fr);align-items:center}.report-meta dt,.report-meta dd{margin:0}.report-meta dd{color:#354258;font-size:12px;white-space:nowrap}.report-data{display:flex;min-height:420px;flex-direction:column;gap:14px}.report-page :deep(.metric-grid){grid-template-columns:repeat(6,minmax(0,1fr))}.report-grid{display:grid;gap:14px}.report-grid--top,.report-grid--bottom{grid-template-columns:minmax(480px,1.55fr) minmax(280px,.78fr) minmax(280px,.78fr)}.report-grid--three{grid-template-columns:repeat(3,minmax(0,1fr))}.chart-card{min-width:0}.chart-card--wide{min-width:0}.card-heading{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:12px}.card-heading div{min-width:0}.card-heading b,.card-heading span{display:block}.card-heading b{font-size:14px}.card-heading span{margin-top:4px;color:var(--admin-muted);font-size:11px}.card-heading>.el-icon{color:var(--admin-primary);font-size:21px}.card-heading em{color:var(--admin-primary);font-size:11px;font-style:normal;font-weight:700;white-space:nowrap}.short-trend{display:flex;min-height:310px;align-items:center;justify-content:center;gap:16px;padding:26px;border:1px dashed #bfd1e7;border-radius:8px;background:#f8fbff}.short-trend__icon{display:grid;width:52px;height:52px;flex:none;place-items:center;border-radius:14px;color:var(--admin-primary);background:#dbeafe;font-size:26px}.short-trend b{font-size:16px}.short-trend p{max-width:520px;margin:7px 0 0;color:var(--admin-muted);line-height:1.7}.danger-value{color:var(--admin-danger);font-weight:700}.report-footnote{display:flex;align-items:center;gap:6px;margin:0;color:var(--admin-muted);font-size:11px}.report-footnote .el-icon{color:var(--admin-warning)}.report-placeholder{min-height:520px}
@media(max-width:1360px){.report-period-card{grid-template-columns:minmax(380px,1.2fr) minmax(280px,.8fr)}.report-meta{grid-column:1/-1;grid-template-columns:1fr 1fr;padding-top:12px;padding-left:0;border-top:1px solid var(--admin-border);border-left:0}.report-page :deep(.metric-grid){grid-template-columns:repeat(3,minmax(0,1fr))}.report-grid--top,.report-grid--bottom{grid-template-columns:minmax(450px,1.35fr) minmax(270px,.75fr)}.report-grid--top>*:last-child,.report-grid--bottom>*:last-child{grid-column:1/-1}.report-grid--three{grid-template-columns:repeat(2,minmax(0,1fr))}.report-grid--three>*:last-child{grid-column:1/-1}}
@media(max-width:900px){.report-period-card{grid-template-columns:1fr}.period-controls{flex-wrap:wrap}.period-summary,.report-meta{padding-top:12px;padding-left:0;border-top:1px solid var(--admin-border);border-left:0}.report-grid--top,.report-grid--bottom,.report-grid--three{grid-template-columns:1fr}.report-grid--top>*:last-child,.report-grid--bottom>*:last-child,.report-grid--three>*:last-child{grid-column:auto}}
@media(max-width:640px){.report-period-card{padding:14px}.period-controls{align-items:stretch}.section-kicker{width:100%}.period-controls :deep(.el-radio-group){display:flex;width:100%}.period-controls :deep(.el-radio-button){flex:1}.period-controls :deep(.el-radio-button__inner){width:100%;padding-right:10px;padding-left:10px}.period-controls :deep(.el-date-editor){width:100%}.report-meta{grid-template-columns:1fr}.report-meta div{grid-template-columns:66px minmax(0,1fr)}.report-page :deep(.metric-grid){grid-template-columns:repeat(2,minmax(0,1fr))}.short-trend{min-height:240px;align-items:flex-start;flex-direction:column}.report-footnote{align-items:flex-start}}
.report-period-card{position:relative;overflow:hidden;border-color:#cfe0ef;border-left-color:var(--admin-primary);border-radius:12px;background:linear-gradient(102deg,rgba(247,251,255,.96),rgba(255,255,255,.94) 65%),url('/assets/img/admin/aviation-ambient.webp') center/cover;box-shadow:var(--admin-shadow-soft)}
.report-period-card::after{position:absolute;right:-42px;width:150px;height:150px;border:1px solid rgba(32,167,216,.12);border-radius:50%;box-shadow:0 0 0 24px rgba(32,167,216,.035);content:"";pointer-events:none}
.period-controls,.period-summary,.report-meta{position:relative;z-index:1}.section-kicker{color:var(--admin-text)}.period-icon{border-radius:11px;background:linear-gradient(135deg,var(--admin-secondary),var(--admin-primary));box-shadow:0 7px 16px rgba(22,119,255,.18)}.period-summary>div>strong{color:var(--admin-text-strong)}.report-meta dd{color:#405069}
.chart-card{overflow:hidden;border-color:var(--admin-border);border-radius:12px}.chart-card :deep(.el-card__header){background:linear-gradient(180deg,#fff,#fbfdff)}.short-trend{border-color:#c7d9ec;border-radius:10px;background:linear-gradient(145deg,#f6faff,#fbfdff)}.short-trend__icon{border-radius:14px;background:var(--admin-primary-soft)}
</style>
