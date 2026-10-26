<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { automationRuleApi } from '@/api/automationRules'
import { isUncertainOutcome } from '@/services/apiClient'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'
import RuleAirspaceSelect from './RuleAirspaceSelect.vue'

const auth = useAuthStore(), saved = ref(null), loading = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const form = reactive({}), names = reactive({}), formRef = ref(null), historyVisible = ref(false), history = ref([]), historyLoading = ref(false), historyError = ref(''), historyPage = ref(1), historyTotal = ref(0)
const canManage = computed(() => saved.value?.can_manage && auth.hasPermission('responsePlans.auth'))
const rules = {
  max_wait_seconds: [{ type: 'number', min: 5, max: 300, required: true, message: '请输入 5 至 300 秒', trigger: 'change' }],
  airspace_ids: [{ validator: (_, value, done) => done(form.scope_mode === 'AIRSPACES' && !value?.length ? new Error('请至少选择一个空域') : undefined), trigger: 'change' }],
  prediction_enabled: [{ validator: (_, value, done) => done(!value && !form.observed_enabled ? new Error('请至少选择一个适用阶段') : undefined), trigger: 'change' }],
  end_time: [{ validator: (_, value, done) => done(form.schedule_mode === 'DAILY' && (!form.start_time || !value || value === form.start_time) ? new Error('开始与结束时间必须填写且不同') : undefined), trigger: 'change' }]
}
function accept(data) {
  saved.value = data
  Object.assign(form, data, { airspace_ids: [...data.airspace_ids] })
  Object.keys(names).forEach(key => delete names[key])
  data.airspace_ids.forEach((id, index) => { names[id] = data.airspace_names[index] || id })
}
async function load() {
  loading.value = true; error.value = ''
  try { accept(await automationRuleApi.responseOrder()) }
  catch (exception) { saved.value = null; error.value = exception.message }
  finally { loading.value = false }
}
async function save() {
  if (!canManage.value || saving.value || !await formRef.value.validate().catch(() => false)) return
  saving.value = true; error.value = ''; notice.value = ''
  const body = { expected_version: saved.value.version, enabled: form.enabled, minimum_risk: form.minimum_risk,
    prediction_enabled: form.prediction_enabled, observed_enabled: form.observed_enabled, scope_mode: form.scope_mode,
    airspace_ids: form.scope_mode === 'ALL' ? [] : [...form.airspace_ids], schedule_mode: form.schedule_mode,
    start_time: form.start_time, end_time: form.end_time, timezone: 'Asia/Shanghai', max_wait_seconds: form.max_wait_seconds }
  try { accept(await automationRuleApi.saveResponseOrder(body)); ElMessage.success('处置顺序已保存，后续判断使用新配置') }
  catch (exception) {
    if (isUncertainOutcome(exception) || exception.status === 409) { await load(); notice.value = '保存结果需要确认，已重新读取服务器配置，请核对后继续。' }
    else error.value = exception.message
  } finally { saving.value = false }
}
function addAirspace(option) { if (!form.airspace_ids.includes(option.id)) form.airspace_ids.push(option.id); names[option.id] = option.name }
async function loadHistory(page = 1) {
  historyVisible.value = true; historyLoading.value = true; historyError.value = ''; historyPage.value = page
  try { const result = await automationRuleApi.responseOrderHistory({ page, size: 20 }); history.value = result.items; historyTotal.value = result.total }
  catch (exception) { history.value = []; historyError.value = exception.message }
  finally { historyLoading.value = false }
}
onMounted(load)
</script>

<template>
  <section v-loading="loading" class="response-order-panel" aria-label="处置顺序配置">
    <div class="order-heading"><div><h3>短信并行、反制优先、电话后置</h3><p>决定核实后的办理顺序。预测及其衍生风险仍由人工核实、人工发起反制。</p></div><el-button :disabled="loading || saving" @click="loadHistory(1)">变更记录</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><template #default><el-button v-if="!saved" @click="load">重新读取</el-button></template></el-alert>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
    <el-form v-if="saved" ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="!canManage || saving || loading" @submit.prevent="save">
      <el-form-item label="启用优先反制策略"><el-switch v-model="form.enabled" active-text="已启用" inactive-text="已关闭" /></el-form-item>
      <p class="field-note">关闭或未命中时沿用通知优先流程；保存不会改变自动核实、自动反制规则的开关。</p>
      <div class="order-grid">
        <el-form-item label="适用风险等级"><el-select v-model="form.minimum_risk"><el-option label="高风险" value="HIGH" /><el-option label="中风险及以上" value="MEDIUM" /></el-select></el-form-item>
        <el-form-item label="适用阶段" prop="prediction_enabled"><div><el-checkbox v-model="form.prediction_enabled">提前预警</el-checkbox><el-checkbox v-model="form.observed_enabled">实测告警</el-checkbox></div></el-form-item>
      </div>
      <el-form-item label="适用范围"><el-radio-group v-model="form.scope_mode"><el-radio label="ALL">全部监测区域</el-radio><el-radio label="AIRSPACES">指定空域</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="form.scope_mode === 'AIRSPACES'" label="指定空域" prop="airspace_ids"><RuleAirspaceSelect :disabled="!canManage || saving" @pick="addAirspace" /><div class="airspaces"><el-tag v-for="id in form.airspace_ids" :key="id" :closable="canManage && !saving" @close="form.airspace_ids = form.airspace_ids.filter(value => value !== id)">{{ names[id] }}</el-tag></div></el-form-item>
      <el-form-item label="生效时间"><el-radio-group v-model="form.schedule_mode"><el-radio label="ALL_DAY">全天</el-radio><el-radio label="DAILY">每日指定时段</el-radio></el-radio-group></el-form-item>
      <div v-if="form.schedule_mode === 'DAILY'" class="order-grid"><el-form-item label="开始时间"><el-time-select v-model="form.start_time" start="00:00" end="23:45" step="00:15" /></el-form-item><el-form-item label="结束时间" prop="end_time"><el-time-select v-model="form.end_time" start="00:00" end="23:45" step="00:15" /></el-form-item></div>
      <p v-if="form.schedule_mode === 'DAILY'" class="field-note">按北京时间；结束时间早于开始时间时跨午夜生效。</p>
      <el-form-item label="最长优先等待（秒）" prop="max_wait_seconds"><el-input-number v-model="form.max_wait_seconds" :min="5" :max="300" :precision="0" /></el-form-item>
      <p class="field-note">从本次风险首次核实通过、依据有效且命中策略起计时。跨阶段、刷新或重启不重新计时。</p>
      <div class="order-explanation"><strong>电话何时可以拨打</strong><ul><li>设备真实启动回执已到，且风险仍持续。</li><li>明确没有可启动设备或启动失败时，转电话通知。</li><li>等待人工、审批或设备超过设定时间，也转电话通知资格检查。</li></ul><p>电话仍需符合飞手、通道、录音及当前风险条件。结果未知不当作失败，也不会盲目重拨。</p></div>
      <p class="field-note">“电话完成后等待时长”只用于通知优先分支。当前正式预测预警为高风险。</p>
      <div class="order-footer"><el-button type="primary" native-type="submit" :loading="saving" :disabled="!canManage || loading">保存处置顺序</el-button></div>
    </el-form>
    <el-dialog v-model="historyVisible" title="处置顺序变更记录" width="760px" class="response-order-history"><el-alert v-if="historyError" :title="historyError" type="error" :closable="false" /><el-table v-loading="historyLoading" :data="history"><el-table-column prop="version" label="版本" width="70" /><el-table-column prop="actor" label="操作人" min-width="100" /><el-table-column label="变更后配置" min-width="250"><template #default="{ row }">{{ row.after.enabled ? '启用' : '关闭' }} · {{ row.after.minimum_risk === 'HIGH' ? '高风险' : '中风险及以上' }} · 最长等待 {{ row.after.max_wait_seconds }} 秒<br>{{ row.after.prediction_enabled ? '提前预警 ' : '' }}{{ row.after.observed_enabled ? '实测告警' : '' }} · {{ row.after.scope_mode === 'ALL' ? '全部监测区域' : row.after.airspace_names.join('、') }}</template></el-table-column><el-table-column label="时间" min-width="155"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column></el-table><el-pagination :current-page="historyPage" :total="historyTotal" :page-size="20" layout="prev, pager, next" @current-change="loadHistory" /></el-dialog>
  </section>
</template>

<style scoped>
.response-order-panel { padding: 22px; }.order-heading { display: flex; align-items: start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }.order-heading h3 { margin: 0 0 8px; font-size: 17px; }.order-heading p,.field-note { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; margin: 0; }.field-note { margin: -7px 0 22px; }.order-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; max-width: 760px; }.order-grid :deep(.el-select) { width: 100%; }.order-explanation { border: 1px solid var(--el-border-color); background: var(--el-fill-color-light); border-radius: 6px; padding: 16px 20px; margin-bottom: 24px; max-width: 760px; line-height: 1.8; }.order-explanation ul { margin: 8px 0; padding-left: 22px; }.order-explanation p { margin: 8px 0 0; color: var(--el-text-color-secondary); }.airspaces { display: flex; flex-wrap: wrap; gap: 8px; width: 100%; margin-top: 10px; }.order-footer { display: flex; align-items: center; justify-content: flex-end; border-top: 1px solid var(--el-border-color); padding-top: 18px; }.response-order-panel :deep(.el-alert) { margin-bottom: 18px; }:global(.response-order-history) { max-width: calc(100vw - 32px); }
@media(max-width: 680px) { .order-grid { grid-template-columns: 1fr; gap: 0; }.order-heading { flex-direction: column; }.response-order-panel { padding: 16px; } }
</style>
