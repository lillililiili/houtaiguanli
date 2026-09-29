<script setup>
import { computed } from 'vue';
import { informationStates, informationValue } from '@/utils/deviceInformation.js';
import { informationGroups } from '@/utils/deviceInformationPresentation.js';

const props = defineProps({ information: { type: Object, default: null }, parameters: Boolean });
const parameterGroups = computed(() => informationGroups(props.information, 'monitor', 'protocol')
  .filter(section => ['work_parameters', 'eo_camera', 'radar_registers', 'radar_rtk', 'relay'].includes(section.code)));
const statusFields = computed(() => {
  const fields = (props.information?.sections || []).flatMap(section => section.fields || []);
  return ['connectivity', 'last_heartbeat_at', 'last_valid_frame_at', 'unknown_reason']
    .map(key => fields.find(field => field.key === key)).filter(Boolean);
});
const connectionLabels = { ONLINE: '在线', OFFLINE: '离线', CONNECTING: '连接中', UNKNOWN: '未知', DEGRADED: '连接异常' };
function fieldValue(field) {
  if (field.status === 'REDACTED') return '无查看权限';
  if (field.key === 'connectivity') return connectionLabels[field.value] || informationValue(field);
  return informationValue(field);
}
function warning(field) { return ['STALE', 'INVALID', 'NOT_REPORTED', 'NOT_CONFIGURED'].includes(field.status); }
</script>

<template>
  <div v-if="parameters" class="commission-parameters">
    <details v-if="parameterGroups.length" class="parameter-disclosure">
      <summary>设备上报参数 <span>查看工参与定位信息</span></summary>
      <div class="parameter-scroll">
        <section v-for="group in parameterGroups" :key="group.code">
          <h4>{{ group.title }}</h4>
          <dl class="parameter-fields">
            <div v-for="field in group.fields" :key="field.key">
              <dt>{{ field.label }}</dt>
              <dd><span>{{ fieldValue(field) }}<small v-if="field.value != null && field.unit && field.unit !== 'epoch_ms'"> {{ field.unit }}</small></span><small v-if="warning(field)" :class="['field-warning', { invalid: field.status === 'INVALID' }]">{{ informationStates[field.status]?.[0] }}</small></dd>
            </div>
          </dl>
        </section>
      </div>
    </details>
  </div>
  <dl v-else-if="statusFields.length" class="connection-fields">
    <template v-for="field in statusFields" :key="field.key">
      <dt>{{ ({ connectivity: '设备连接', last_heartbeat_at: '最近心跳', last_valid_frame_at: '最近有效数据', unknown_reason: '状态说明' })[field.key] }}</dt>
      <dd>{{ fieldValue(field) }} <small v-if="warning(field)" class="field-warning">{{ informationStates[field.status]?.[0] }}</small></dd>
    </template>
  </dl>
  <p v-else class="status-empty">{{ information ? '暂无连接状态' : '等待设备信息' }}</p>
</template>

<style scoped>
.parameter-disclosure { margin-top: 18px; border-top: 1px solid #e7edf3; }
.parameter-disclosure summary { cursor: pointer; padding: 16px 0; font-size: 13px; font-weight: 600; color: #526780; }
.parameter-disclosure summary span { float: right; font-size: 12px; font-weight: 400; color: #8490a1; }
.parameter-scroll { max-height: 260px; overflow: auto; }
.parameter-scroll h4 { margin: 0 0 12px; font-size: 13px; }
.parameter-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 20px; margin: 0 0 18px; }
.parameter-fields dt, .connection-fields dt { color: #8490a1; }
.parameter-fields dt { margin-bottom: 6px; }
.parameter-fields dd { display: flex; flex-wrap: wrap; gap: 6px 12px; }
.parameter-fields, .connection-fields { font-size: 12px; line-height: 1.6; }
.parameter-fields dd, .connection-fields dd { margin: 0; overflow-wrap: anywhere; }
.connection-fields { display: grid; grid-template-columns: 84px minmax(0, 1fr); gap: 8px 12px; margin: 0 0 16px; }
.field-warning { color: #a66b12; font-size: 12px; }
.field-warning.invalid { color: #d34444; }
.status-empty { color: #8490a1; font-size: 12px; }
@media (max-width: 720px) { .parameter-fields { grid-template-columns: minmax(0, 1fr); } }
</style>
