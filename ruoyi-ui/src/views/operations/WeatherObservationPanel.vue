<script setup>
import { computed, ref, watch } from 'vue';
import { deviceApi } from '@/api/devices.js';
import { formatTime } from '@/utils/format.js';

const props = defineProps({ deviceId: { type: String, required: true } });
const loading = ref(false);
const error = ref('');
const information = ref(null);

const sections = computed(() => information.value?.sections || []);

async function load() {
  if (!props.deviceId) return;
  loading.value = true;
  error.value = '';
  try {
    information.value = await deviceApi.information(props.deviceId);
  } catch (cause) {
    information.value = null;
    error.value = cause?.message || '读取气象观测失败';
  } finally {
    loading.value = false;
  }
}

watch(() => props.deviceId, load, { immediate: true });
</script>

<template>
  <el-card v-loading="loading" class="weather-observation-panel" shadow="never">
    <template #header>
      <div class="table-toolbar">
        <b>气象观测</b>
        <el-button link type="primary" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-else-if="!loading && !sections.length" description="暂无气象观测数据" />
    <section v-for="section in sections" v-else :key="section.code" class="observation-section">
      <div class="table-toolbar">
        <b>{{ section.title }}</b>
        <span class="muted">{{ section.source }}<template v-if="section.received_at"> · 接收 {{ formatTime(section.received_at) }}</template></span>
      </div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="field in section.fields" :key="field.key" :label="field.label">
          {{ field.value == null || field.value === '' ? '未上报' : field.value }}
          <span v-if="field.unit && field.unit !== 'epoch_ms'" class="muted"> {{ field.unit }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </section>
  </el-card>
</template>

<style scoped>
.weather-observation-panel { margin-top: 12px; }
.observation-section + .observation-section { margin-top: 18px; }
.observation-section .table-toolbar { align-items: flex-start; gap: 12px; margin-bottom: 8px; }
.observation-section .table-toolbar .muted { font-size: 12px; text-align: right; }
</style>
