<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { deviceApi } from '@/api/devices.js';
import { chartOption, trendLabel, trendModel } from '@/utils/deviceTrends.js';
const props = defineProps({ deviceId: { type: String, required: true }, paused: Boolean });
const data = ref(null), error = ref(''), loading = ref(false), tab = ref('reports'), range = ref('1h'), reportCode = ref('');
const nodes = [], charts = [];
let observer, timer, generation = 0, alive = true, inFlight = false;
const reportCodes = computed(() => [...new Set((data.value?.reports || []).map(r => r.code))]);
const model = computed(() => trendModel(data.value, tab.value, reportCode.value));
const cards = computed(() => model.value.cards);
async function load(manual = false) {
  if (!props.deviceId || (!manual && (props.paused || inFlight))) return;
  const current = ++generation, id = props.deviceId, period = range.value;
  inFlight = true; loading.value = true;
  try {
    const result = await deviceApi.trends(id, { range: period });
    if (!alive || current !== generation) return;
    data.value = result; error.value = '';
    if (!reportCodes.value.includes(reportCode.value)) reportCode.value = reportCodes.value[0] || '';
  } catch (e) { if (alive && current === generation) { data.value = null; error.value = e.message || '运行趋势加载失败'; } }
  finally { if (alive && current === generation) { loading.value = false; inFlight = false; await paint(); } }
}
async function paint() {
  await nextTick();
  if (!alive) return;
  nodes.forEach((node, i) => {
    if (!node || !node.clientWidth || !node.clientHeight) return;
    charts[i] ||= echarts.init(node);
    if (data.value) charts[i].setOption(chartOption(data.value, model.value.charts[i]), true);
    else charts[i].clear();
    charts[i].resize();
  });
}
watch(() => [props.deviceId, range.value], () => { generation++; data.value = null; error.value = ''; reportCode.value = ''; void load(true); }, { immediate: true });
watch(() => props.paused, paused => { if (!paused) void load(); });
watch([tab, reportCode], paint);
onMounted(() => { if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(paint); nodes.forEach(node => node && observer.observe(node)); } timer = window.setInterval(() => load(), 10000); });
onBeforeUnmount(() => { alive = false; generation++; clearInterval(timer); observer?.disconnect(); charts.forEach(chart => chart?.dispose()); });
</script>

<template>
  <el-card class="device-trends">
    <template #header><div class="trend-toolbar"><b>设备历史趋势</b><div class="trend-actions"><el-radio-group v-model="range" size="small" aria-label="统计时间范围"><el-radio-button label="1h">近1小时</el-radio-button><el-radio-button label="24h">近24小时</el-radio-button><el-radio-button label="7d">近7天</el-radio-button></el-radio-group><el-button size="small" :loading="loading" @click="load(true)">刷新统计</el-button></div></div></template>
    <el-tabs v-model="tab" aria-label="运行统计分类"><el-tab-pane label="上报趋势" name="reports" /><el-tab-pane label="状态历史" name="state" /></el-tabs>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-show="!error">
    <div v-if="data?.simulated" class="trend-note">模拟 / 回放设备数据</div>
    <div v-if="cards.length" class="trend-cards"><article v-for="(card, i) in cards" :key="i"><small>{{ card.label }}</small><strong>{{ card.value }} <span>{{ card.unit }}</span></strong></article></div>
    <div class="trend-plots"><article v-for="(plot, i) in model.charts" :key="i"><div class="trend-plot-title"><b>{{ plot.title }}</b><el-select v-if="tab==='reports' && i===1 && reportCodes.length" v-model="reportCode" size="small" aria-label="报文类型" style="width:130px"><el-option v-for="code in reportCodes" :key="code" :value="code" :label="trendLabel(code)" /></el-select></div><small>{{ plot.unit }}</small><div class="trend-canvas-wrap" :class="{ 'is-empty': !plot.series.some(series => series.rows.some(row => row[series.field] != null)) }"><div :ref="el => nodes[i] = el" class="trend-canvas" /><p v-if="!plot.series.some(series => series.rows.some(row => row[series.field] != null))" class="trend-empty">{{ loading ? '正在加载统计…' : error ? '统计加载失败，请重试' : '该时段暂无可用记录' }}</p></div></article></div>
    <p class="trend-note">{{ model.note }} 历史仅展示已采集记录。</p>
    </div>
  </el-card>
</template>

<style scoped>
.trend-toolbar,.trend-actions,.trend-plot-title{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.trend-toolbar>b{border-left:3px solid #2685ff;padding-left:9px}.trend-cards{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0 0 14px}.trend-cards article,.trend-plots>article{border:1px solid #e2eaf5;border-radius:7px;padding:12px;min-width:0}.trend-cards small,.trend-plots small{color:#65748e;font-size:12px}.trend-cards strong{display:block;color:#142743;font-size:23px;margin-top:10px;overflow-wrap:anywhere}.trend-cards strong span{font-size:12px;font-weight:400}.trend-plots{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:12px;align-items:start}.trend-plot-title{min-height:28px;font-size:14px}.trend-canvas-wrap{position:relative}.trend-canvas{height:235px;width:100%}.is-empty .trend-canvas{height:130px}.trend-empty{position:absolute;inset:0;display:grid;place-items:center;margin:0;background:#fff;color:#8793a7;font-size:13px}.trend-note{font-size:12px;color:#77869c;line-height:1.7;margin:12px 0 0}
</style>
