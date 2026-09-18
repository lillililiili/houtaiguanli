<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { responsePlanApi } from '@/api/responsePlans'

defineProps({ disabled: Boolean })
const emit = defineEmits(['pick'])
const rows = ref([]), loading = ref(false), error = ref(''), keyword = ref(''), page = ref(1), total = ref(0), selected = ref('')
let sequence = 0
async function load(append = false) {
  const seq = ++sequence; loading.value = true; error.value = ''
  try {
    const data = await responsePlanApi.airspaces({ page: page.value, size: 20, keyword: keyword.value })
    if (seq === sequence) { rows.value = append ? rows.value.concat(data.items) : data.items; total.value = data.total }
  } catch (exception) { if (seq === sequence) error.value = exception.message }
  finally { if (seq === sequence) loading.value = false }
}
function search(value) { keyword.value = value; page.value = 1; load() }
function pick(id) {
  const row = rows.value.find(item => item.airspace_id === id)
  if (row) emit('pick', { id: row.airspace_id, name: row.name })
  selected.value = ''
}
function more() { page.value += 1; load(true) }
onMounted(() => load())
onBeforeUnmount(() => { sequence += 1 })
</script>
<template>
  <div class="airspace-select">
    <el-select v-model="selected" :disabled="disabled" filterable remote :remote-method="search" :loading="loading" placeholder="搜索并添加空域" @change="pick">
      <el-option v-for="row in rows" :key="row.airspace_id" :label="row.name" :value="row.airspace_id" />
    </el-select>
    <span v-if="error" role="alert">{{ error }} <el-button :disabled="loading" @click="load()">重试</el-button></span>
    <el-button v-if="rows.length < total" :disabled="loading || disabled" @click="more">加载更多空域（已列 {{ rows.length }} / {{ total }}）</el-button>
  </div>
</template>
<style scoped>
.airspace-select { display: grid; gap: 8px; width: 100%; }.airspace-select :deep(.el-select) { width: 100%; }.airspace-select span { overflow-wrap: anywhere; }
</style>
