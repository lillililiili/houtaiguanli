<script setup>
import { computed, reactive, watch } from 'vue'
import { ruleDraft } from './ruleModel'

const props = defineProps({ modelValue: Boolean, rule: { type: Object, default: null }, catalog: { type: Array, default: () => [] }, categoryLabel: { type: String, required: true }, executionStatus: { type: String, default: 'UNAVAILABLE' }, executionMessage: { type: String, default: '' }, busy: Boolean, serverError: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue', 'save'])
const form = reactive(ruleDraft(null, [])), error = reactive({ message: '' })
const selected = computed(() => props.catalog.find(item => item.code === form.item_code))
const availableCatalog = computed(() => props.rule ? props.catalog : props.catalog.filter(item => !item.used))
const saveNote = computed(() => props.executionStatus === 'CONNECTED'
  ? '保存后用于下一轮自动判定；本次保存不会立即触发动作。'
  : props.executionMessage || '当前执行服务未连接，保存仅更新配置，不会触发运行时动作。')

function reset() { Object.assign(form, ruleDraft(props.rule, availableCatalog.value)); error.message = '' }
watch(() => props.modelValue, visible => { if (visible) reset() }, { immediate: true })
function selectItem(code) {
  const item = props.catalog.find(entry => entry.code === code)
  if (!item) return
  form.name = item.default_name || ''
  form.value = item.kind === 'FIXED' ? String(item.fixed_value ?? '') : ''
  if (!item.supports_hold) form.hold_seconds = 0
}
function requestClose() { if (!props.busy) emit('update:modelValue', false) }
function submit() {
  const item = selected.value
  if (!form.name.trim()) { error.message = '请填写规则名称'; return }
  if (!item) { error.message = '请选择判定项'; return }
  if (item.kind === 'NUMBER' && form.value === '') { error.message = '请填写条件数值'; return }
  if (item.kind === 'NUMBER' && (!/^\d+$/.test(form.value) || Number(form.value) < item.min_value || Number(form.value) > item.max_value)) { error.message = `${item.label}须为 ${item.min_value}～${item.max_value} 之间的整数（${item.unit}）`; return }
  if (item.kind === 'SELECT' && !form.value) { error.message = '请选择条件要求'; return }
  if (!Number.isInteger(Number(form.hold_seconds)) || Number(form.hold_seconds) < 0 || Number(form.hold_seconds) > 60) { error.message = '持续满足秒数必须为 0～60 之间的整数'; return }
  error.message = ''
  emit('save', { name: form.name.trim(), item_code: form.item_code, value: String(item.kind === 'FIXED' ? item.fixed_value : form.value), hold_seconds: Number(form.hold_seconds), enabled: form.enabled })
}
</script>
<template>
  <el-dialog class="automation-rule-editor-dialog" :model-value="modelValue" :title="`${rule ? '编辑' : '新建'}${categoryLabel}`" width="560px" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="$event ? emit('update:modelValue', true) : requestClose()">
    <p class="inherited">适用范围与生效时间沿用当前分类的生效设置。</p>
    <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
      <el-form-item label="规则名称" required><el-input v-model="form.name" maxlength="40" placeholder="根据判定项自动填写，可修改" /></el-form-item>
      <el-form-item label="判定项" required><el-select v-model="form.item_code" placeholder="请选择判定项" :disabled="Boolean(rule)" @change="selectItem"><el-option v-for="item in availableCatalog" :key="item.code" :label="item.label" :value="item.code" /></el-select><p v-if="selected?.source" class="hint">数据来源：{{ selected.source }}</p></el-form-item>
      <el-form-item label="条件要求" required><div v-if="selected?.kind === 'NUMBER'" class="condition"><span>{{ selected.operator }}</span><el-input v-model="form.value" type="number" :min="selected.min_value" :max="selected.max_value"><template #append>{{ selected.unit }}</template></el-input></div><el-select v-else-if="selected?.kind === 'SELECT'" v-model="form.value"><el-option v-for="option in selected.options" :key="option.value ?? option" :label="option.label ?? option" :value="String(option.value ?? option)" /></el-select><div v-else class="fixed-value">{{ selected?.fixed_value || '请先选择判定项' }}</div></el-form-item>
      <div v-if="selected?.supports_hold" class="hold-setting"><el-checkbox :model-value="form.hold_seconds > 0" @change="form.hold_seconds = $event ? 1 : 0">持续满足后通过</el-checkbox><el-input-number v-if="form.hold_seconds > 0" v-model="form.hold_seconds" aria-label="持续满足秒数" :min="1" :max="60" controls-position="right" /> <span v-if="form.hold_seconds > 0">秒</span><p>连续达到条件，短暂波动不通过。</p></div>
      <div class="enabled"><span>启用规则</span><el-switch v-model="form.enabled" inline-prompt active-text="启用" inactive-text="停用" /></div>
    </el-form>
    <el-alert v-if="error.message || serverError" :title="error.message || serverError" type="error" :closable="false" />
    <template #footer><span class="save-note">{{ saveNote }}</span><el-button :disabled="busy" @click="requestClose">取消</el-button><el-button type="primary" :loading="busy" :disabled="busy" @click="submit">保存</el-button></template>
  </el-dialog>
</template>
<style scoped>
.inherited { margin: -20px -20px 20px; padding: 12px 20px; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); overflow-wrap: anywhere; }.condition { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 10px; width: 100%; align-items: center; }.condition>span,.fixed-value { padding: 8px 12px; border: 1px solid var(--el-border-color); border-radius: 4px; background: var(--el-fill-color-light); }.hint,.hold-setting p { width: 100%; margin: 7px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }.hold-setting { margin-bottom: 20px; padding: 12px 14px; background: var(--el-fill-color-light); border-radius: 5px; }.hold-setting span { margin-left: 7px; }.enabled { display: flex; justify-content: space-between; align-items: center; }.save-note { float: left; max-width: 60%; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; text-align: left; overflow-wrap: anywhere; }:global(.automation-rule-editor-dialog) { max-width: calc(100vw - 32px); }.el-form :deep(.el-select) { width: 100%; }.hold-setting :deep(.el-input-number) { width: 112px; margin-left: 12px; }
@media (max-width: 520px) { .condition { grid-template-columns: 1fr; }.save-note { float: none; display: block; width: 100%; max-width: none; margin-bottom: 10px; } }
</style>
