<script setup>
import { Monitor, CircleCheck, CircleClose, Warning, Bell, OfficeBuilding } from '@element-plus/icons-vue';
defineProps({ items: { type: Array, default: () => [] } });
const icons = { total: Monitor, online: CircleCheck, offline: CircleClose, abnormal: Warning, alarm: Bell, vendor: OfficeBuilding };
</script>

<template>
  <section class="operation-metrics" aria-label="设备关键指标">
    <article v-for="item in items" :key="item.label" class="operation-metric" :class="`tone-${item.tone || 'blue'}`">
      <span class="operation-metric__icon"><el-icon><component :is="icons[item.icon] || Monitor" /></el-icon></span>
      <div><span class="operation-metric__label">{{ item.label }}</span><strong>{{ item.value ?? '—' }}</strong><small v-if="item.note">{{ item.note }}</small></div>
    </article>
  </section>
</template>

<style scoped>
.operation-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.operation-metric { --metric-color: #2876d5; --metric-bg: #edf5ff; display: flex; align-items: center; gap: 12px; padding: 18px 16px; background: #fff; border: 1px solid #e4e9f0; border-radius: 8px; min-width: 0; }
.operation-metric__icon { display: grid; place-items: center; width: 42px; height: 42px; flex: none; color: var(--metric-color); background: var(--metric-bg); border-radius: 8px; font-size: 22px; }
.operation-metric__label { color: #687588; font-size: 12px; }
.operation-metric strong { display: block; color: var(--metric-color); font-size: 26px; line-height: 1.4; font-variant-numeric: tabular-nums; }
.operation-metric small { display: block; color: #8993a2; font-size: 11px; line-height: 1.5; }
.tone-green { --metric-color: #23966b; --metric-bg: #eaf8f1; }
.tone-red { --metric-color: #d55360; --metric-bg: #fff0f2; }
.tone-amber { --metric-color: #bc8424; --metric-bg: #fff7e8; }
.tone-purple { --metric-color: #8260c6; --metric-bg: #f3effb; }
@media (max-width: 1400px) { .operation-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 600px) { .operation-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .operation-metric { padding: 12px; gap: 8px; } .operation-metric__icon { width: 32px; height: 32px; } }
</style>
