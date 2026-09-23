<script setup>
import { reactive, watch } from 'vue'
import RuleAirspaceSelect from './RuleAirspaceSelect.vue'
import { settingsDraft } from './ruleModel'

const props = defineProps({ modelValue: Boolean, settings: { type: Object, default: () => ({}) }, category: { type: String, required: true }, busy: Boolean, serverError: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue', 'save'])
const form = reactive(settingsDraft()), error = reactive({ message: '' }), airspaceNames = reactive({})
watch(() => props.modelValue, visible => { if (visible) { Object.assign(form, settingsDraft(props.settings)); Object.keys(airspaceNames).forEach(key => delete airspaceNames[key]); form.airspace_ids.forEach((id, index) => { airspaceNames[id] = props.settings.airspace_names?.[index] || id }); error.message = '' } }, { immediate: true })
function submit() {
  if (form.scope_mode === 'AIRSPACES' && !form.airspace_ids.length) { error.message = '请至少选择一个适用空域'; return }
  if (form.schedule_mode === 'DAILY' && (!form.start_time || !form.end_time || form.start_time === form.end_time)) { error.message = '自定义生效时间的开始和结束时间必须不同'; return }
  if (![0, 5, 15, 30].includes(Number(form.insufficient_wait_seconds))) { error.message = '数据不足等待时间只能选择 0、5、15 或 30 秒'; return }
  const payload = settingsDraft(form)
  if (payload.scope_mode === 'ALL') payload.airspace_ids = []
  payload.actions = []
  emit('save', { ...payload, insufficient_wait_seconds: Number(form.insufficient_wait_seconds) })
}
function addAirspace(option) { if (option?.id && !form.airspace_ids.includes(option.id)) form.airspace_ids.push(option.id); if (option?.id) airspaceNames[option.id] = option.name }
function removeAirspace(id) { form.airspace_ids = form.airspace_ids.filter(value => value !== id); delete airspaceNames[id] }
function requestClose() { if (!props.busy) emit('update:modelValue', false) }
</script>
<template>
  <el-dialog class="automation-rule-settings-dialog" :model-value="modelValue" title="生效设置" width="620px" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="$event ? emit('update:modelValue', true) : requestClose()">
    <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
      <el-form-item label="适用范围" required><el-radio-group v-model="form.scope_mode"><el-radio label="ALL">全部监测区域</el-radio><el-radio label="AIRSPACES">指定空域</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="form.scope_mode === 'AIRSPACES'" label="指定空域" required>
        <RuleAirspaceSelect :disabled="busy" @pick="addAirspace" />
        <div class="airspaces"><el-tag v-for="id in form.airspace_ids" :key="id" closable @close="removeAirspace(id)">{{ airspaceNames[id] }}</el-tag></div>
      </el-form-item>
      <el-form-item label="生效时间" required><el-radio-group v-model="form.schedule_mode"><el-radio label="ALL_DAY">全天</el-radio><el-radio label="DAILY">每日指定时段</el-radio></el-radio-group></el-form-item>
      <div v-if="form.schedule_mode === 'DAILY'" class="time-grid"><el-form-item label="开始时间"><el-time-select v-model="form.start_time" start="00:00" step="00:15" end="23:45" /></el-form-item><el-form-item label="结束时间"><el-time-select v-model="form.end_time" start="00:00" step="00:15" end="23:45" /></el-form-item></div>
      <p class="hint">时区固定为 Asia/Shanghai；结束时间早于开始时间时按跨午夜处理。</p>
      <el-form-item label="数据不足时继续等待"><el-select v-model="form.insufficient_wait_seconds"><el-option v-for="seconds in [0, 5, 15, 30]" :key="seconds" :label="`${seconds} 秒`" :value="seconds" /></el-select></el-form-item>
      <el-alert v-if="category === 'dispose'" title="这些条件决定前台「移送与处罚」里的「通知处罚部门」能否点击。权限、交接状态和通知渠道仍单独校验。未启用任何规则时，该按钮仍按原交接条件办理。" type="info" :closable="false" />
      <el-alert v-if="category === 'counter'" title="规则开关不替代反制授权；执行前仍由后端独立校验授权对象、动作、范围与有效期。" type="info" :closable="false" />
    </el-form>
    <el-alert v-if="error.message || serverError" :title="error.message || serverError" type="error" :closable="false" />
    <template #footer><el-button :disabled="busy" @click="requestClose">取消</el-button><el-button type="primary" :loading="busy" :disabled="busy" @click="submit">保存设置</el-button></template>
  </el-dialog>
</template>
<style scoped>
.time-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.hint { margin: -8px 0 18px; color: var(--el-text-color-secondary); font-size: 12px; overflow-wrap: anywhere; }.airspaces { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }.actions { display: grid; gap: 12px; margin: 18px 0; padding-top: 18px; border-top: 1px solid var(--el-border-color); }.actions :deep(.el-checkbox-group) { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.actions :deep(.el-checkbox) { height: auto; white-space: normal; }.time-grid :deep(.el-select) { width: 100%; }:global(.automation-rule-settings-dialog) { max-width: calc(100vw - 32px); }
@media (max-width: 520px) { .time-grid,.actions :deep(.el-checkbox-group) { grid-template-columns: 1fr; } }
</style>
