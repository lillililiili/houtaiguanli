<script setup>
import { ref, watch } from 'vue'
import { automationRuleApi } from '@/api/automationRules'
import { formatTime } from '@/utils/format'

const props = defineProps({ modelValue: Boolean, category: { type: String, required: true } })
const emit = defineEmits(['update:modelValue'])
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
let sequence = 0

const runStatus = {
  PASS: ['条件已满足', 'success'], NOT_MATCHED: ['未满足条件', 'info'], WAITING: ['等待继续判定', 'warning'],
  REVIEW: ['需人工复核', 'warning'], PAUSED: ['规则判定已暂停', 'info'], OUT_OF_SCOPE: ['不在适用范围', 'info'],
  OUT_OF_SCHEDULE: ['不在生效时间', 'info'], FAILED: ['判定失败', 'danger']
}
const conditionStatus = { PASS: ['满足', 'success'], FAIL: ['不满足', 'danger'], UNKNOWN: ['数据未知', 'info'], WAITING: ['等待数据', 'warning'] }
const actionStatus = {
  SUCCEEDED: ['执行成功', 'success'], QUEUED: ['已进入执行队列', 'warning'], BLOCKED: ['已阻断', 'danger'],
  FAILED: ['执行失败', 'danger'], UNKNOWN: ['结果未知', 'info'], SIMULATED: ['模拟结果', 'warning'], ALREADY_STARTED: ['此前已启动', 'warning']
}
const sourceLabel = value => ({ live: '真实接入', mock: '模拟', replay: '回放' })[String(value || '').toLowerCase()] || value || '来源未知'
const statusText = (mapping, value) => mapping[value]?.[0] || value || '状态未知'
const statusType = (mapping, value) => mapping[value]?.[1] || 'info'
// 动作代码不直接给操作员看；未知代码统一显示“自动动作”，原始代码放在“详细信息”里（#21）。
const actionNames = { verify: '自动核实', notify: '发送通知', sms: '发送短信', voice: '拨打电话', call: '拨打电话', pilot: '飞手提醒', counter: '自动反制', countermeasure: '自动反制', dispose: '通知处罚部门', punishment: '通知处罚部门', evidence: '汇集证据', track: '持续跟踪', audit: '写入审计记录' }
const actionName = code => actionNames[String(code || '').toLowerCase()] || '自动动作'
const displayValue = value => value === null || value === undefined || value === '' ? '未提供' : String(value)

async function load(next = page.value) {
  const seq = ++sequence
  page.value = next
  loading.value = true
  error.value = ''
  try {
    const data = await automationRuleApi.runs(props.category, { page: next, size: 20 })
    if (seq === sequence) {
      rows.value = Array.isArray(data?.items) ? data.items : []
      total.value = Number(data?.total || 0)
    }
  } catch (exception) {
    if (seq === sequence) { rows.value = []; total.value = 0; error.value = exception.message }
  } finally {
    if (seq === sequence) loading.value = false
  }
}

watch(() => props.modelValue, visible => { if (visible) load(1); else sequence += 1 }, { immediate: true })
watch(() => props.category, () => { if (props.modelValue) load(1) })
</script>

<template>
  <el-dialog class="automation-rule-runs-dialog" :model-value="modelValue" title="运行记录" width="860px" @update:model-value="emit('update:modelValue', $event)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon>
      <template #default><el-button :disabled="loading" @click="load(page)">重新读取</el-button></template>
    </el-alert>
    <div v-loading="loading" class="run-list">
      <article v-for="row in rows" :key="row.run_id" class="run-entry">
        <header>
          <div><strong>{{ formatTime(row.evaluated_at) }} 判定</strong><small>{{ row.target_id ? '已关联目标' : '未关联目标' }}<template v-if="row.event_id"> · 已关联告警</template></small></div>
          <el-tag :type="statusType(runStatus, row.status)" effect="light">{{ statusText(runStatus, row.status) }}</el-tag>
        </header>
        <dl class="run-meta">
          <div><dt>规则版本</dt><dd>v{{ row.group_version }}</dd></div>
          <div><dt>观测时间</dt><dd>{{ formatTime(row.observed_at) }}</dd></div>
          <div><dt>数据来源</dt><dd>{{ sourceLabel(row.source_mode) }}</dd></div>
        </dl>
        <p v-if="row.reason" class="run-reason">{{ row.reason }}</p>
        <section v-if="row.conditions?.length">
          <h4>判定明细</h4>
          <div v-for="(condition, index) in row.conditions" :key="`${condition.name}-${index}`" class="result-row">
            <div><strong>{{ condition.name || '未命名条件' }}</strong><small>实际值：{{ displayValue(condition.actual) }}；要求：{{ displayValue(condition.expected) }}</small><p v-if="condition.reason">{{ condition.reason }}</p></div>
            <el-tag :type="statusType(conditionStatus, condition.result)" size="small">{{ statusText(conditionStatus, condition.result) }}</el-tag>
          </div>
        </section>
        <section v-if="row.actions?.length">
          <h4>动作结果</h4>
          <div v-for="(action, index) in row.actions" :key="`${action.code}-${index}`" class="result-row">
            <div><strong>{{ actionName(action.code) }}</strong><p v-if="action.reason">{{ action.reason }}</p></div>
            <el-tag :type="statusType(actionStatus, action.status)" size="small">{{ statusText(actionStatus, action.status) }}</el-tag>
          </div>
        </section>
        <p v-if="!row.conditions?.length && !row.actions?.length" class="empty-detail">本次运行未返回判定明细或动作结果。</p>
        <details class="tech-details">
          <summary>详细信息</summary>
          <p>运行编号：{{ row.run_id }}</p>
          <p v-if="row.event_id">告警编号：{{ row.event_id }}</p>
          <p v-if="row.target_id">目标编号：{{ row.target_id }}</p>
          <p v-for="(action, index) in row.actions || []" :key="`ref-${action.code}-${index}`">{{ actionName(action.code) }}：{{ action.code || '未记录代码' }}<template v-if="action.reference"> · 关联记录 {{ action.reference }}</template></p>
        </details>
      </article>
      <el-empty v-if="!loading && !error && !rows.length" description="暂无运行记录" />
    </div>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" :disabled="loading" @current-change="load" />
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.run-list { min-height: 160px; }.run-entry { padding: 18px 0; border-bottom: 1px solid var(--el-border-color); overflow-wrap: anywhere; }.run-entry:first-child { padding-top: 0; }.run-entry>header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }.run-entry header div,.result-row>div { min-width: 0; }.run-entry header small,.result-row small { display: block; margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; }.run-meta { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 10px 18px; margin: 14px 0 0; }.run-meta div { min-width: 0; }.run-meta dt { color: var(--el-text-color-secondary); font-size: 12px; }.run-meta dd { margin: 4px 0 0; }.run-reason,.result-row p,.empty-detail { margin: 10px 0 0; color: var(--el-text-color-secondary); line-height: 1.65; }.run-entry section { margin-top: 16px; }.run-entry h4 { margin: 0 0 6px; }.result-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 10px 0; border-top: 1px dashed var(--el-border-color); }.result-row p { margin-top: 5px; font-size: 12px; }.tech-details { margin-top: 12px; color: var(--el-text-color-secondary); font-size: 12px; }.tech-details summary { cursor: pointer; }.tech-details p { margin: 6px 0 0; overflow-wrap: anywhere; }:global(.automation-rule-runs-dialog) { max-width: calc(100vw - 32px); }
@media (max-width: 680px) { .run-meta { grid-template-columns: 1fr 1fr; }.run-entry>header,.result-row { align-items: flex-start; }.run-entry>header>.el-tag,.result-row>.el-tag { flex: 0 0 auto; max-width: 45%; height: auto; white-space: normal; text-align: center; } }
@media (max-width: 460px) { .run-meta { grid-template-columns: 1fr; } }
</style>
