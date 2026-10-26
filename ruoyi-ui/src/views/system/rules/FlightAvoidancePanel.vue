<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { automationRuleApi } from '@/api/automationRules'
import { isUncertainOutcome } from '@/services/apiClient'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'

const auth = useAuthStore(), saved = ref(null), loading = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const form = reactive({}), formRef = ref(null)
const historyVisible = ref(false), history = ref([]), historyLoading = ref(false), historyError = ref(''), historyPage = ref(1), historyTotal = ref(0)
const canManage = computed(() => saved.value?.can_manage && auth.hasPermission('responsePlans.auth'))
const rules = {
  force_land_warn_km: [{ validator: (_, value, done) => done(value < form.force_land_block_km ? new Error('提醒范围不能小于禁止反制范围') : undefined), trigger: 'change' }]
}
function accept(data) {
  saved.value = data
  Object.assign(form, { force_land_block_km: Number(data.force_land_block_km), force_land_warn_km: Number(data.force_land_warn_km),
    force_land_max_altitude_m: data.force_land_max_altitude_m, drive_away_warn_km: Number(data.drive_away_warn_km),
    drive_away_max_altitude_m: data.drive_away_max_altitude_m })
}
async function load() {
  loading.value = true; error.value = ''
  try { accept(await automationRuleApi.flightAvoidance()) }
  catch (exception) { saved.value = null; error.value = exception.message }
  finally { loading.value = false }
}
async function save() {
  if (!canManage.value || saving.value || !await formRef.value.validate().catch(() => false)) return
  saving.value = true; error.value = ''; notice.value = ''
  try { accept(await automationRuleApi.saveFlightAvoidance({ expected_version: saved.value.version, ...form })); ElMessage.success('航班避让已保存') }
  catch (exception) {
    if (isUncertainOutcome(exception) || exception.status === 409) { await load(); notice.value = '保存结果需要确认，已重新读取服务器配置，请核对后继续。' }
    else error.value = exception.message
  } finally { saving.value = false }
}
async function loadHistory(page = 1) {
  historyVisible.value = true; historyLoading.value = true; historyError.value = ''; historyPage.value = page
  try { const result = await automationRuleApi.flightAvoidanceHistory({ page, size: 20 }); history.value = result.items; historyTotal.value = result.total }
  catch (exception) { history.value = []; historyError.value = exception.message }
  finally { historyLoading.value = false }
}
const summary = value => `迫降 ${Number(value.force_land_block_km)} / ${Number(value.force_land_warn_km)} 公里、${value.force_land_max_altitude_m} 米以下 · 驱离 ${Number(value.drive_away_warn_km)} 公里、${value.drive_away_max_altitude_m} 米以下`
onMounted(load)
</script>

<template>
  <section v-loading="loading" class="flight-avoidance-panel" aria-label="航班避让">
    <div class="flight-heading"><div><h3>航班避让</h3><p>反制前检查反制设备附近的民航航班，避免影响飞机。</p></div><el-button :disabled="loading || saving" @click="loadHistory(1)">变更记录</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><template #default><el-button v-if="!saved" @click="load">重新读取</el-button></template></el-alert>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
    <el-form v-if="saved" ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="!canManage || saving || loading" @submit.prevent="save">
      <h4>迫降</h4>
      <div class="flight-grid">
        <el-form-item label="禁止反制范围"><el-input-number v-model="form.force_land_block_km" :min="0.5" :max="350" :step="1" :precision="1" /><span class="unit">公里</span></el-form-item>
        <el-form-item label="提醒范围" prop="force_land_warn_km"><el-input-number v-model="form.force_land_warn_km" :min="0.5" :max="350" :step="1" :precision="1" /><span class="unit">公里</span></el-form-item>
        <el-form-item label="航班高度"><el-input-number v-model="form.force_land_max_altitude_m" :min="100" :max="15000" :step="100" :precision="0" /><span class="unit">米以下</span></el-form-item>
      </div>
      <h4>驱离</h4>
      <div class="flight-grid">
        <div />
        <el-form-item label="提醒范围"><el-input-number v-model="form.drive_away_warn_km" :min="0.5" :max="350" :step="1" :precision="1" /><span class="unit">公里</span></el-form-item>
        <el-form-item label="航班高度"><el-input-number v-model="form.drive_away_max_altitude_m" :min="100" :max="15000" :step="100" :precision="0" /><span class="unit">米以下</span></el-form-item>
      </div>
      <div class="flight-explanation"><strong>处理方式</strong><ul>
        <li>禁止反制范围内有航班：不能反制。</li>
        <li>提醒范围内有航班：操作员确认后可以反制；不自动反制。</li>
        <li>收不到航班信息：操作员确认后可以反制；不自动反制。</li>
        <li>反制中有航班飞进禁止反制范围：自动停止反制。</li>
      </ul></div>
      <div class="flight-footer"><el-button type="primary" native-type="submit" :loading="saving" :disabled="!canManage || loading">保存</el-button></div>
    </el-form>
    <el-dialog v-model="historyVisible" title="航班避让变更记录" width="760px" class="flight-avoidance-history"><el-alert v-if="historyError" :title="historyError" type="error" :closable="false" /><el-table v-loading="historyLoading" :data="history"><el-table-column prop="version" label="版本" width="70" /><el-table-column prop="actor" label="操作人" min-width="100" /><el-table-column label="变更后" min-width="280"><template #default="{ row }">{{ summary(row.after) }}</template></el-table-column><el-table-column label="时间" min-width="155"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column></el-table><el-pagination :current-page="historyPage" :total="historyTotal" :page-size="20" layout="prev, pager, next" @current-change="loadHistory" /></el-dialog>
  </section>
</template>

<style scoped>
.flight-avoidance-panel { padding: 22px; }.flight-heading { display: flex; align-items: start; justify-content: space-between; gap: 20px; margin-bottom: 8px; }.flight-heading h3 { margin: 0 0 8px; font-size: 17px; }.flight-heading p { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; margin: 0; }
h4 { margin: 22px 0 8px; font-size: 15px; }.flight-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; max-width: 900px; }.unit { margin-left: 8px; color: var(--el-text-color-regular); white-space: nowrap; }
.flight-explanation { border: 1px solid var(--el-border-color); background: var(--el-fill-color-light); border-radius: 6px; padding: 16px 20px; margin: 10px 0 24px; max-width: 900px; line-height: 1.8; }.flight-explanation ul { margin: 8px 0 0; padding-left: 22px; }
.flight-footer { display: flex; justify-content: flex-end; border-top: 1px solid var(--el-border-color); padding-top: 18px; }.flight-avoidance-panel :deep(.el-alert) { margin-bottom: 18px; }:global(.flight-avoidance-history) { max-width: calc(100vw - 32px); }
@media(max-width: 680px) { .flight-grid { grid-template-columns: 1fr; gap: 0; }.flight-grid > div:empty { display: none; }.flight-heading { flex-direction: column; }.flight-avoidance-panel { padding: 16px; } }
</style>
