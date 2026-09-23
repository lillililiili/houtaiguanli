<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit, Plus, Setting } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { automationRuleApi } from '@/api/automationRules'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'
import RuleEditorDialog from './RuleEditorDialog.vue'
import RuleSettingsDialog from './RuleSettingsDialog.vue'
import { CATEGORIES, actionSummary, categoryMeta, conditionText, enabledCount, scopeSummary, timeSummary } from './ruleModel'
import { useAutomationRuleGroup } from './useAutomationRuleGroup'

const auth = useAuthStore()
const editorVisible = ref(false), settingsVisible = ref(false), editingRule = ref(null)
const { category, group, loading, saving, error, actionError, uncertain, recoveryRevision, load, mutate, clearActionError } = useAutomationRuleGroup()
const canManage = computed(() => Boolean(group.value?.can_manage && auth.hasPermission('responsePlans.auth')))
const count = computed(() => enabledCount(group.value))
const meta = computed(() => categoryMeta(category.value))
const catalog = computed(() => (group.value?.catalog || []).map(item => ({ ...item, used: group.value?.rules?.some(rule => rule.item_code === item.code) })))
const mayCreate = computed(() => canManage.value && catalog.value.some(item => !item.used))
const policyText = computed(() => {
  if (!count.value) {
    if (category.value === 'verify') return '未启用规则时，告警仍等待人工核实。'
    if (category.value === 'counter') return '未启用规则时，发起反制仍只检查原有授权条件。'
    return '未启用规则时，「通知处罚部门」仍按交接状态、权限和通知渠道办理。'
  }
  if (category.value === 'verify') return '适用范围内，已启用规则全部满足后，待核实告警由系统核实属实，并进入飞手通知。'
  if (category.value === 'counter') return '适用范围内，已启用规则全部满足后才可发起反制。反制仍由人工发起，并继续校验授权。'
  return '适用范围内，已启用规则全部满足后，才可点击「通知处罚部门」。'
})
const emptyText = computed(() => category.value === 'dispose' ? '暂无通知处罚规则。未启用规则时，不限制「通知处罚部门」。' : '暂无规则；未启用任何规则时该分类处于暂停配置状态。')
const executionType = computed(() => ({ CONNECTED: 'success', STARTING: 'warning', DISABLED: 'info', UNAVAILABLE: 'error' })[group.value?.execution_status] || 'warning')

function openEditor(rule = null) { clearActionError(); editingRule.value = rule; editorVisible.value = true }
function openSettings() { clearActionError(); settingsVisible.value = true }
function changeCategory(next) { if (!saving.value && !loading.value && next !== category.value) { editorVisible.value = false; settingsVisible.value = false; clearActionError(); load(next) } }
async function saveRule(payload) {
  const current = editingRule.value
  const success = await mutate(state => current ? automationRuleApi.updateRule(category.value, current.rule_id, { ...payload, expected_version: state.version }) : automationRuleApi.createRule(category.value, { ...payload, expected_version: state.version }))
  if (success) { editorVisible.value = false; ElMessage.success('规则配置已保存') }
}
async function toggleRule(rule, enabled) { if (!canManage.value) return; const success = await mutate(state => automationRuleApi.setRuleEnabled(category.value, rule.rule_id, { enabled, expected_version: state.version })); if (success) ElMessage.success(enabled ? '规则已启用' : '规则已停用') }
async function saveSettings(payload) { const success = await mutate(state => automationRuleApi.updateSettings(category.value, { ...payload, expected_version: state.version })); if (success) { settingsVisible.value = false; ElMessage.success('生效设置已保存') } }
onMounted(() => load())
watch(recoveryRevision, () => { editorVisible.value = false; settingsVisible.value = false; editingRule.value = null })
</script>
<template>
  <div class="page-stack rules-page">
    <PageHeader title="规则管理" description="这三类规则只用于前台告警到处罚。核实通过后系统核实属实；反制规则决定能否发起反制；通知处罚规则决定能否通知处罚部门。" />
    <el-alert v-if="group?.execution_message" :title="group.execution_message" :type="executionType" :closable="false" show-icon />
    <el-alert v-if="uncertain" :title="uncertain" type="warning" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><template #default><el-button :disabled="loading" @click="load(category)">重新读取</el-button></template></el-alert>
    <el-alert v-if="actionError && !editorVisible && !settingsVisible" :title="actionError" type="error" :closable="false" show-icon />
    <el-card class="rule-card" shadow="never">
      <el-tabs :model-value="category" @update:model-value="changeCategory"><el-tab-pane v-for="item in CATEGORIES" :key="item.key" :label="item.label" :name="item.key" :disabled="saving || loading" /></el-tabs>
      <template v-if="group">
        <section class="context-grid"><div><span>适用范围</span><strong>{{ scopeSummary(group.settings) }}</strong></div><div><span>生效时间</span><strong>{{ timeSummary(group.settings) }}</strong></div><div><span>预设动作</span><strong>{{ actionSummary(category, group.settings) }}</strong></div><el-button type="primary" :icon="Setting" :disabled="!canManage || saving || loading" @click="openSettings">生效设置</el-button></section>
        <section class="list-bar"><div><p :class="{ paused: !count }">{{ policyText }}</p><small>共 {{ group.rules.length }} 条 · {{ count }} 条已启用</small></div><el-button type="primary" :icon="Plus" :disabled="!mayCreate || saving" @click="openEditor()">新建规则</el-button></section>
        <el-table v-loading="loading" :data="group.rules" class="rules-table">
          <el-table-column label="规则名称" min-width="150"><template #default="{ row }"><strong>{{ row.name }}</strong><small>更新于 {{ formatTime(row.updated_at) }}<template v-if="row.updated_by"> · {{ row.updated_by }}</template></small></template></el-table-column>
          <el-table-column label="条件要求" min-width="190"><template #default="{ row }">{{ conditionText(row, group.catalog) }}</template></el-table-column>
          <el-table-column label="持续满足" min-width="100"><template #default="{ row }">{{ row.hold_seconds > 0 ? `连续 ${row.hold_seconds} 秒` : '即时判断' }}</template></el-table-column>
          <el-table-column label="配置状态" min-width="125"><template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="!canManage || saving" inline-prompt active-text="已启用" inactive-text="已停用" @change="toggleRule(row, $event)" /></template></el-table-column>
          <el-table-column label="操作" min-width="80"><template #default="{ row }"><el-button text type="primary" :icon="Edit" :disabled="!canManage || saving" @click="openEditor(row)">编辑</el-button></template></el-table-column>
          <template #empty><el-empty :description="emptyText" /></template>
        </el-table>
        <div v-loading="loading" class="rule-cards"><article v-for="row in group.rules" :key="row.rule_id"><div class="card-heading"><strong>{{ row.name }}</strong><el-switch :model-value="row.enabled" :disabled="!canManage || saving" inline-prompt active-text="已启用" inactive-text="已停用" @change="toggleRule(row, $event)" /></div><p>{{ conditionText(row, group.catalog) }}</p><p>{{ row.hold_seconds > 0 ? `持续满足：连续 ${row.hold_seconds} 秒` : '持续满足：即时判断' }}</p><small>更新于 {{ formatTime(row.updated_at) }}<template v-if="row.updated_by"> · {{ row.updated_by }}</template></small><el-button text type="primary" :icon="Edit" :disabled="!canManage || saving" @click="openEditor(row)">编辑</el-button></article><el-empty v-if="!group.rules.length" :description="emptyText" /></div>
        <footer class="table-foot">数据不足时继续补充 {{ group.settings.insufficient_wait_seconds }} 秒，仍无结论则转为异常处理，不自动通过。</footer><p v-if="!canManage" class="readonly-note">当前账号只能查看规则配置。</p>
      </template><el-skeleton v-else-if="loading" :rows="6" animated />
    </el-card>
    <RuleEditorDialog v-model="editorVisible" :rule="editingRule" :catalog="catalog" :category="category" :category-label="meta.label" :execution-status="group?.execution_status" :execution-message="group?.execution_message" :busy="saving" :server-error="actionError" @save="saveRule" />
    <RuleSettingsDialog v-if="group" v-model="settingsVisible" :settings="group.settings" :category="category" :busy="saving" :server-error="actionError" @save="saveSettings" />
  </div>
</template>
<style scoped>
.rules-page { min-width: 0; }.rule-card :deep(.el-card__body) { padding: 0; }.rule-card :deep(.el-tabs__header) { margin: 0; padding: 0 22px; }.context-grid { display: grid; grid-template-columns: minmax(150px,1fr) minmax(170px,1fr) minmax(210px,1.4fr) auto; gap: 18px; align-items: center; padding: 18px 22px; border-bottom: 1px solid var(--el-border-color); }.context-grid div { display: grid; gap: 5px; min-width: 0; }.context-grid span,.list-bar small { color: var(--el-text-color-secondary); font-size: 12px; }.context-grid strong { font-size: 13px; font-weight: 400; overflow-wrap: anywhere; }.list-bar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 20px 22px; }.list-bar p { margin: 0 0 5px; }.paused { color: var(--el-text-color-secondary); }.rules-table strong,.rules-table small { display: block; overflow-wrap: anywhere; }.rules-table small { margin-top: 5px; color: var(--el-text-color-secondary); font-size: 11px; font-weight: 400; }.rules-table :deep(.cell) { white-space: normal; overflow-wrap: anywhere; line-height: 1.65; }.rule-cards { display: none; }.table-foot,.readonly-note { margin: 0; padding: 14px 22px; color: var(--el-text-color-secondary); font-size: 12px; border-top: 1px solid var(--el-border-color); overflow-wrap: anywhere; }.readonly-note { color: var(--el-color-warning); }.rules-page :deep(.el-alert) { margin-bottom: 14px; }
@media (max-width: 900px) { .context-grid { grid-template-columns: 1fr 1fr; }.context-grid>div:nth-child(3) { grid-column: 1 / -1; } }
@media (max-width: 680px) { .context-grid { grid-template-columns: 1fr; }.context-grid>div:nth-child(3) { grid-column: auto; }.list-bar { align-items: flex-start; flex-direction: column; }.rules-table { display: none; }.rule-cards { display: block; }.rule-cards article { display: grid; gap: 8px; padding: 16px; border-top: 1px solid var(--el-border-color); overflow-wrap: anywhere; }.rule-cards .card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.rule-cards p,.rule-cards small { margin: 0; line-height: 1.6; }.rule-cards small { color: var(--el-text-color-secondary); }.rule-cards :deep(.el-button) { justify-self: start; } }
</style>
