<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { directoryApi } from '@/api/organizationDirectory'
const props = defineProps({
  modelValue: { type: String, default: '' }, kind: { type: String, required: true },
  orgId: { type: String, default: '' }, currentLabel: { type: String, default: '' },
  disabled: Boolean, clearable: { type: Boolean, default: true }, placeholder: { type: String, default: '输入名称检索' }
})
const emit = defineEmits(['update:modelValue', 'select'])
const items = ref([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const selected = ref(null)
let sequence = 0
const displayed = computed(() => {
  const rows = [...items.value]
  const id = props.modelValue
  if (id && !rows.some(item => item.id === id)) rows.unshift({ id, label: selected.value?.id === id ? selected.value.label : props.currentLabel || id })
  return rows
})
const label = computed(() => displayed.value.find(item => item.id === props.modelValue)?.label || '')
async function search(keyword = '') {
  const current = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await directoryApi.options({ kind: props.kind, keyword, org_id: props.orgId, page: 1, size: 30 })
    if (current !== sequence) return
    items.value = result?.items || []
    total.value = result?.total || 0
  } catch (e) { if (current === sequence) error.value = e.message || '选项加载失败，请重试。' }
  finally { if (current === sequence) loading.value = false }
}
function change(value) {
  selected.value = displayed.value.find(item => item.id === value) || null
  emit('update:modelValue', value || '')
  emit('select', selected.value)
}
watch(() => [props.kind, props.orgId], () => { selected.value = null; items.value = []; search() })
onMounted(() => search())
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <div class="directory-select">
    <el-select :model-value="modelValue" :disabled="disabled" :clearable="clearable" filterable remote :remote-method="search" :loading="loading" :placeholder="placeholder" popper-class="directory-option-popper" @update:model-value="change">
      <el-option v-for="item in displayed" :key="item.id" :label="item.label" :value="item.id" />
    </el-select>
    <p v-if="label" class="selection-label">已选：{{ label }}</p>
    <p v-if="error" class="selection-error">{{ error }} <el-button link type="primary" :disabled="loading" @click="search()">重试</el-button></p>
    <p v-else-if="total > items.length" class="selection-hint">符合条件共 {{ total }} 项，输入完整名称可继续定位。</p>
  </div>
</template>

<style scoped>
.directory-select,.directory-select .el-select{width:100%;min-width:0}.selection-label,.selection-error,.selection-hint{margin:5px 0 0;line-height:1.5;white-space:normal;overflow-wrap:anywhere;font-size:12px}.selection-label,.selection-hint{color:var(--admin-muted)}.selection-error{color:var(--el-color-danger)}
:global(.directory-option-popper .el-select-dropdown__item){height:auto;min-height:34px;line-height:1.5;padding-top:8px;padding-bottom:8px;white-space:normal;overflow-wrap:anywhere;max-width:min(600px,80vw)}
</style>
