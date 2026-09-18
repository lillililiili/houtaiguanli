<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { responsePlanApi } from '@/api/responsePlans'
const props = defineProps({ modelValue: { type: String, default: '' }, selectedName: { type: String, default: '' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const rows = ref([]), loading = ref(false), error = ref(''), keyword = ref(''), page = ref(1), total = ref(0)
let sequence = 0
const picked = ref(null)
const options = computed(() => {
  if (!props.modelValue || rows.value.some(r => r.airspace_id === props.modelValue)) return rows.value
  const name = picked.value?.airspace_id === props.modelValue ? picked.value.name : props.selectedName
  return name ? [{ airspace_id: props.modelValue, name }, ...rows.value] : rows.value
})
function choose(id) { picked.value = options.value.find(r => r.airspace_id === id); emit('update:modelValue', id) }
async function load(append = false) {
  const seq = ++sequence
  loading.value = true; error.value = ''
  try {
    const data = await responsePlanApi.airspaces({ page: page.value, size: 20, keyword: keyword.value })
    if (seq !== sequence) return
    rows.value = append ? rows.value.concat(data.items) : data.items; total.value = data.total
  } catch (e) { if (seq === sequence) error.value = e.message }
  finally { if (seq === sequence) loading.value = false }
}
function search(value) { keyword.value = value; page.value = 1; load() }
function more() { page.value += 1; load(true) }
onMounted(() => load())
onBeforeUnmount(() => { sequence += 1 })
</script>
<template>
  <div class="space-select">
    <el-select :model-value="modelValue" :disabled="disabled" filterable remote :remote-method="search" :loading="loading" placeholder="搜索并选择空域" @update:model-value="choose">
      <el-option v-for="row in options" :key="row.airspace_id" :value="row.airspace_id" :label="row.name" />
    </el-select>
    <span v-if="error" role="alert">{{ error }} <el-button :disabled="loading" @click="load()">重试</el-button></span>
    <el-button v-if="rows.length < total" :disabled="loading || disabled" @click="more">加载更多空域（已列 {{ rows.length }} / {{ total }}）</el-button>
  </div>
</template>
<style scoped>
.space-select { display: grid; gap: 8px; width: 100%; }
.space-select :deep(.el-select) { width: 100%; }
</style>
