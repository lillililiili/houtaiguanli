<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { directoryApi } from '@/api/organizationDirectory'
const props = defineProps({ modelValue: { type: String, default: '' }, sourceId: { type: String, default: '' }, currentLabel: { type: String, default: '' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue', 'select'])
const items = ref([])
const selected = ref(null)
const loading = ref(false)
const error = ref('')
const total = ref(0)
let sequence = 0
const label = row => `${row.source_name || row.source_id} · ${row.external_org_code} · ${row.org_name || row.org_id}${row.enabled === false ? '（已停用）' : ''}`
const options = computed(() => {
  const rows = items.value.map(item => ({ ...item, label: label(item) }))
  if (props.modelValue && !rows.some(item => item.binding_id === props.modelValue)) rows.unshift({ binding_id: props.modelValue, label: selected.value?.binding_id === props.modelValue ? label(selected.value) : props.currentLabel || props.modelValue })
  return rows
})
const chosen = computed(() => options.value.find(item => item.binding_id === props.modelValue))
async function search(keyword = '') {
  const current = ++sequence
  loading.value = true; error.value = ''
  try {
    const result = await directoryApi.bindings({ page: 1, size: 30, keyword, source_id: props.sourceId })
    if (current !== sequence) return
    items.value = result?.items || []; total.value = result?.total || 0
  } catch (e) { if (current === sequence) error.value = e.message || '来源映射加载失败。' }
  finally { if (current === sequence) loading.value = false }
}
function change(id) { selected.value = items.value.find(item => item.binding_id === id) || null; emit('update:modelValue', id || ''); emit('select', selected.value) }
watch(() => props.sourceId, () => { items.value = []; selected.value = null; search() })
onMounted(() => search())
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <div class="binding-select">
    <el-select :model-value="modelValue" filterable remote clearable :disabled="disabled" :loading="loading" :remote-method="search" placeholder="输入来源、单位名称或外部编码检索" popper-class="directory-option-popper" @update:model-value="change">
      <el-option v-for="item in options" :key="item.binding_id" :label="item.label" :value="item.binding_id" :disabled="item.enabled === false" />
    </el-select>
    <p v-if="chosen">已选：{{ chosen.label }}</p>
    <p v-if="error" class="error">{{ error }} <el-button link type="primary" @click="search()">重试</el-button></p>
    <p v-else-if="total > items.length">符合条件共 {{ total }} 项，输入完整名称或外部编码可继续定位。</p>
  </div>
</template>
<style scoped>
.binding-select,.el-select{width:100%;min-width:0}p{margin:5px 0 0;line-height:1.5;white-space:normal;overflow-wrap:anywhere;color:var(--admin-muted);font-size:12px}.error{color:var(--el-color-danger)}
</style>
