<script setup>
import { ref, watch } from 'vue'
import { automationRuleApi } from '@/api/automationRules'
import { formatTime } from '@/utils/format'

const props = defineProps({ modelValue: Boolean, category: { type: String, required: true } })
const emit = defineEmits(['update:modelValue'])
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
let sequence = 0
const actionLabel = value => ({ CREATE_RULE: '新建规则', UPDATE_RULE: '编辑规则', ENABLE_RULE: '启用规则', DISABLE_RULE: '停用规则', UPDATE_SETTINGS: '修改生效设置' })[value] || value || '配置变更'
async function load(next = page.value) {
  const seq = ++sequence; page.value = next; loading.value = true; error.value = ''
  try { const data = await automationRuleApi.history(props.category, { page: next, size: 20 }); if (seq === sequence) { rows.value = data.items; total.value = data.total } }
  catch (exception) { if (seq === sequence) error.value = exception.message }
  finally { if (seq === sequence) loading.value = false }
}
watch(() => props.modelValue, visible => { if (visible) load(1); else sequence += 1 }, { immediate: true })
watch(() => props.category, () => { if (props.modelValue) load(1) })
</script>
<template>
  <el-dialog class="automation-rule-history-dialog" :model-value="modelValue" title="变更记录" width="680px" @update:model-value="emit('update:modelValue', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false"><template #default><el-button @click="load(page)">重试</el-button></template></el-alert>
    <div v-loading="loading" class="history-list">
      <article v-for="row in rows" :key="row.change_id" class="history-entry">
        <div><strong>{{ actionLabel(row.action) }}</strong><el-tag size="small">v{{ row.version }}</el-tag></div>
        <p>{{ row.actor || '未知操作者' }} · {{ formatTime(row.created_at) }}</p>
        <ul><li v-for="detail in row.details" :key="detail">{{ detail }}</li></ul>
      </article>
      <el-empty v-if="!loading && !error && !rows.length" description="暂无变更记录" />
    </div>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" :disabled="loading" @current-change="load" />
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-dialog>
</template>
<style scoped>
.history-list { min-height: 120px; }.history-entry { padding: 16px 0; border-bottom: 1px solid var(--el-border-color); }.history-entry:first-child { padding-top: 0; }.history-entry>div { display: flex; justify-content: space-between; gap: 12px; }.history-entry p,.history-entry ul { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }.history-entry ul { padding-left: 20px; }:global(.automation-rule-history-dialog) { max-width: calc(100vw - 32px); }
</style>
