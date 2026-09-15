<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';

const props = defineProps({
  option: { type: Object, default: () => ({}) },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
  ariaLabel: { type: String, default: '统计图表' },
  height: { type: String, default: '280px' }
});

const host = ref();
let chart;
let observer;

async function render() {
  await nextTick();
  if (!host.value || props.empty) {
    chart?.clear();
    return;
  }
  chart ||= echarts.init(host.value);
  chart.setOption({
    animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    aria: { enabled: true, decal: { show: true } },
    textStyle: {
      color: '#405069',
      fontFamily: '"Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", sans-serif'
    },
    ...props.option
  }, true);
}

watch(() => props.option, render, { deep: true });
watch(() => props.empty, render);
onMounted(() => {
  render();
  observer = new ResizeObserver(() => chart?.resize());
  if (host.value) observer.observe(host.value);
});
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <div class="report-chart-shell" :style="{ minHeight: height }">
    <div v-show="!empty" ref="host" class="report-chart" role="img" :aria-label="ariaLabel"></div>
    <el-empty v-if="empty" :description="emptyText" :image-size="72" />
  </div>
</template>

<style scoped>
.report-chart-shell{display:grid;width:100%;place-items:center;border-radius:10px;background:linear-gradient(180deg,rgba(248,251,255,.72),rgba(255,255,255,.18))}.report-chart{width:100%;height:100%;min-height:inherit}.report-chart-shell :deep(.el-empty){padding:24px 0}
</style>
