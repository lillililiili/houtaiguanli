<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DirectorySelect from './organization/DirectorySelect.vue'
import SourceBindingSelect from './organization/SourceBindingSelect.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { useAuthStore } from '@/stores/auth'
import { channelTypes, configurationError, fixedRecipient, labelOf, notificationBody, notificationPurposes, requiresReload } from '@/utils/directoryConfiguration'
import { formatTime } from '@/utils/format'

const auth = useAuthStore()
const canEdit = computed(() => auth.hasPermission('notificationSettings.auth'))
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const error = ref('')
const detailError = ref('')
const saveError = ref('')
const diagnosticsError = ref('')
const blocked = ref(false)
const rows = ref([])
const page = ref(1)
const total = ref(0)
const filter = ref('')
const selectedId = ref('')
const data = ref(null)
const diagnostics = ref(null)
const diagnosticBusy = ref(false)
const creating = ref(false)
const bindingOrg = ref('')
const form = reactive({ purpose: 'PLAN_FEEDBACK', recipient_org_id: '', contact_id: '', source_binding_id: '', channel_type: 'NONE', endpoint_ref: '', enabled: false, valid_until: '' })
let listSequence = 0
let detailSequence = 0
let diagnosticSequence = 0
const singletonRecipient = computed(() => fixedRecipient(form.purpose))
const dirty = computed(() => creating.value || (data.value && JSON.stringify(notificationBody(form, data.value.version)) !== JSON.stringify(notificationBody(data.value, data.value.version))))
const availableChannels = computed(() => {
  const kinds = form.purpose === 'ADVISORY_SMS' ? ['NONE', 'MOCK', 'SMS'] : form.purpose === 'ADVISORY_VOICE' ? ['NONE', 'MOCK', 'VOICE'] : ['NONE', 'MOCK', 'API']
  return channelTypes.filter(item => kinds.includes(item.value))
})
function availability(value) { return ({ SIMULATED: '仅模拟可用', UNAVAILABLE: '当前不可用', AVAILABLE: '当前可用', READY: '当前可用', MOCK: '仅模拟可用', MOCK_ONLY: '仅模拟可用', DISABLED: '已停用', BLOCKED: '当前阻断', NOT_CONNECTED: '通道未接通', UNCONFIGURED: '尚未配置', EXPIRED: '已过有效期' })[value] || value || '尚未校验' }
function templateName(value) { return ({ PLAN_DEVICE_CHECK_V1: '计划设备检查反馈', RISK_SUPERIOR_NOTICE_V1: '风险上级通知', PILOT_ADVISORY_SMS_V1: '飞手短信提醒', PILOT_EXISTING_RECORDING_V1: '飞手录音电话', UAV_PUNISHMENT_MATERIAL_V2: '处罚移送材料', DEVICE_MAINTENANCE_NOTICE_V1: '设备运维通知' })[value] || (value ? '业务通知模板' : '尚未返回') }
function receiptRequirement(value) { return ({ DELIVERY_RECEIPT: '送达回执', DELIVERY_AND_ACKNOWLEDGEMENT: '送达回执及接收确认', DELIVERY_AND_ACK: '送达回执及接收确认', ACKNOWLEDGEMENT: '接收确认', TASK_FEEDBACK: '待办处理反馈', NONE: '未要求回执' })[value] || value || '尚未返回' }
function resetForm(row = {}) {
  Object.assign(form, { purpose: row.purpose || 'PLAN_FEEDBACK', recipient_org_id: row.recipient_org_id || '', contact_id: row.contact_id || '', source_binding_id: row.source_binding_id || '', channel_type: row.channel_type || 'NONE', endpoint_ref: row.endpoint_ref || '', enabled: row.enabled === true, valid_until: row.valid_until ? new Date(Number(row.valid_until)) : null })
  bindingOrg.value = row.org_id || row.recipient_org_id || ''
  saveError.value = ''; blocked.value = false
}
async function loadList(preferredId = selectedId.value) {
  const current = ++listSequence
  loading.value = true; error.value = ''
  try {
    const result = await directoryApi.settings({ page: page.value, size: 20, purpose: filter.value })
    if (current !== listSequence) return
    rows.value = result.items || []; total.value = result.total || 0
    const row = rows.value.find(item => item.setting_id === preferredId) || rows.value[0]
    if (row) await selectSetting(row)
    else { detailSequence++; creating.value = false; data.value = null; selectedId.value = ''; diagnostics.value = null }
  } catch (e) { if (current === listSequence) error.value = e.message || '通知对象配置加载失败。' }
  finally { if (current === listSequence) loading.value = false }
}
async function loadDiagnostics(id = selectedId.value) {
  if (!id) return
  const current = ++diagnosticSequence
  const context = detailSequence
  diagnosticBusy.value = true; diagnosticsError.value = ''
  try {
    const result = await directoryApi.diagnostics(id)
    if (current === diagnosticSequence && context === detailSequence) diagnostics.value = result
  } catch (e) { if (current === diagnosticSequence && context === detailSequence) diagnosticsError.value = e.message || '配置诊断读取失败。' }
  finally { if (current === diagnosticSequence) diagnosticBusy.value = false }
}
async function selectSetting(row) {
  if (saving.value) return
  const current = ++detailSequence
  diagnosticSequence++; diagnosticBusy.value = false
  selectedId.value = row.setting_id; creating.value = false; data.value = null; diagnostics.value = null
  detailError.value = ''; saveError.value = ''; diagnosticsError.value = ''; blocked.value = false
  detailLoading.value = true
  try {
    const detail = await directoryApi.setting(row.setting_id)
    if (current !== detailSequence) return
    data.value = detail; resetForm(detail)
    await loadDiagnostics(row.setting_id)
  } catch (e) { if (current === detailSequence) detailError.value = e.message || '通知配置详情读取失败。' }
  finally { if (current === detailSequence) detailLoading.value = false }
}
function createSetting() {
  detailSequence++; diagnosticSequence++; diagnosticBusy.value = false; detailLoading.value = false
  data.value = null; diagnostics.value = null; selectedId.value = ''; creating.value = true
  detailError.value = ''; diagnosticsError.value = ''; resetForm()
}
function changePurpose() { form.recipient_org_id = ''; form.contact_id = ''; form.source_binding_id = ''; form.channel_type = 'NONE'; form.endpoint_ref = ''; bindingOrg.value = '' }
function chooseBinding(row) { bindingOrg.value = row?.org_id || ''; form.contact_id = '' }
async function save() {
  if (!canEdit.value || blocked.value || saving.value) return
  saveError.value = ''
  if (!singletonRecipient.value && form.purpose === 'PLAN_FEEDBACK' && !form.source_binding_id) { saveError.value = '请选择该计划报送单位的来源映射。'; return }
  if (!singletonRecipient.value && form.purpose !== 'PLAN_FEEDBACK' && !form.recipient_org_id) { saveError.value = '请选择用途对应的接收单位。'; return }
  if (form.enabled && form.channel_type === 'NONE') { saveError.value = '启用前请选择通道并完成配置。'; return }
  if (['API', 'SMS', 'VOICE'].includes(form.channel_type) && !form.endpoint_ref.trim()) { saveError.value = '请填写部署端点引用；当前正式通道状态仍以诊断结果为准。'; return }
  if (/https?:\/\/|[?&](token|key|secret)=/i.test(form.endpoint_ref)) { saveError.value = '此处只填写部署端点引用，不填写网址、令牌或密钥。'; return }
  saving.value = true
  try {
    const result = await directoryApi.saveSetting(data.value?.setting_id, notificationBody(form, data.value?.version))
    ElMessage.success('配置已保存。适用性和通道状态见配置校验结果。')
    const savedId = result?.setting_id || data.value?.setting_id
    creating.value = false
    saving.value = false
    await loadList(savedId)
    if (savedId && selectedId.value !== savedId) await selectSetting({ setting_id: savedId })
  } catch (e) { saveError.value = configurationError(e); blocked.value = requiresReload(e) }
  finally { saving.value = false }
}
function reloadCurrent() { if (data.value?.setting_id || selectedId.value) selectSetting({ setting_id: data.value?.setting_id || selectedId.value }); else loadList() }
function search() { page.value = 1; loadList() }
onMounted(() => loadList())
onBeforeUnmount(() => { listSequence++; detailSequence++; diagnosticSequence++ })
</script>

<template>
  <div class="page-stack notification-settings-page">
    <PageHeader title="通知对象配置" description="按业务用途维护接收对象与渠道，保存和校验配置不会发送通知。">
      <el-button v-if="canEdit" type="primary" :disabled="saving" @click="createSetting">新增用途配置</el-button><el-button :loading="loading" :disabled="saving" @click="loadList()">刷新</el-button>
    </PageHeader>
    <ErrorAlert :message="error" @retry="loadList()" />
    <el-alert v-if="!canEdit" title="当前账号可查看配置，维护需要通知对象配置授权权限。" type="info" :closable="false" />
    <div class="notification-layout">
      <el-card class="settings-list" shadow="never">
        <el-form label-position="top"><el-form-item label="通知用途"><el-select v-model="filter" clearable :disabled="saving" placeholder="全部用途" @change="search"><el-option v-for="item in notificationPurposes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-form>
        <div v-loading="loading" class="setting-items">
          <button v-for="row in rows" :key="row.setting_id" type="button" class="setting-item" :class="{ active: row.setting_id === selectedId }" :disabled="saving" @click="selectSetting(row)">
            <strong>{{ labelOf(notificationPurposes, row.purpose) }}</strong><span>{{ fixedRecipient(row.purpose) || row.recipient_name || row.org_name || '接收对象待配置' }}</span><small>{{ row.enabled ? '已启用' : '已停用' }} · {{ availability(row.availability) }}</small>
          </button>
          <el-empty v-if="!rows.length && !loading && !error" description="暂无符合条件的通知配置" :image-size="64" />
        </div>
        <div class="list-pager"><el-button :disabled="page <= 1 || loading || saving" @click="page--; loadList()">上一页</el-button><span>第 {{ page }} 页，共 {{ total }} 项</span><el-button :disabled="page * 20 >= total || loading || saving" @click="page++; loadList()">下一页</el-button></div>
      </el-card>
      <el-card v-loading="detailLoading" class="settings-detail" shadow="never">
        <ErrorAlert :message="detailError" @retry="reloadCurrent" />
        <el-empty v-if="!data && !creating && !detailLoading && !detailError" description="选择一项通知配置查看接收对象和诊断结果。" />
        <template v-if="data || creating">
          <div class="detail-heading"><h2>{{ creating ? '新增通知对象配置' : labelOf(notificationPurposes, form.purpose) }}</h2><span v-if="data" class="muted">记录版本 {{ data.version }}</span></div>
          <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
          <el-form label-position="top" :disabled="!canEdit || saving || blocked" @submit.prevent="save">
            <el-form-item v-if="creating" label="通知用途" required><el-select v-model="form.purpose" @change="changePurpose"><el-option v-for="item in notificationPurposes.filter(item => !item.singleton)" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <template v-if="singletonRecipient">
              <div class="recipient-summary"><span>接收对象</span><strong>{{ singletonRecipient }}</strong><p v-if="form.purpose === 'RISK_NOTICE'">风险通知统一通知上级，使用这一项配置。</p><p v-else>从当前目标与计划的明确关联读取已核实飞手。身份或联系方式缺失时阻断，单位联系人不能替代。</p></div>
            </template>
            <template v-else>
              <el-form-item v-if="form.purpose === 'PLAN_FEEDBACK'" label="报送单位来源映射" required><SourceBindingSelect v-model="form.source_binding_id" :current-label="data?.source_name && data?.org_name ? `${data.source_name} · ${data.org_name}` : ''" @select="chooseBinding" /></el-form-item>
              <el-form-item v-else label="接收单位" required><DirectorySelect v-model="form.recipient_org_id" kind="organizations" :current-label="data?.org_name || data?.recipient_name || ''" @select="form.contact_id = ''" /></el-form-item>
              <el-form-item label="联系人（可不选）"><DirectorySelect v-model="form.contact_id" kind="contacts" :org-id="form.recipient_org_id || bindingOrg" :current-label="data?.contact_name || data?.contact_hint || ''" /><p class="muted">联系人须归属当前接收单位，并具备对应的计划、单位或运维联系用途。</p></el-form-item>
            </template>
            <div class="form-grid">
              <el-form-item label="通知渠道" required><el-select v-model="form.channel_type"><el-option v-for="item in availableChannels" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
              <el-form-item label="部署端点引用"><el-input v-model="form.endpoint_ref" type="textarea" autosize placeholder="填写后台部署的端点标识，不填写网址和密钥" /></el-form-item>
              <el-form-item label="有效截止时间"><el-date-picker v-model="form.valid_until" type="datetime" placeholder="留空表示未设置截止时间" /></el-form-item>
              <el-form-item label="配置状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
            </div>
            <p v-if="form.channel_type === 'MOCK'" class="form-note">模拟通道仅用于本地或测试环境的模拟、回放数据，不代表真实短信、电话或接口已送达。</p>
            <p v-else-if="['API','SMS','VOICE'].includes(form.channel_type)" class="form-note">正式通道目前未接通。填写端点引用只保存配置，不证明已经可以投递。</p>
            <div class="save-actions"><el-button v-if="canEdit && !blocked" type="primary" :disabled="!dirty" :loading="saving" @click="save">保存配置</el-button><el-button v-if="data" :disabled="saving" @click="resetForm(data)">恢复已保存内容</el-button></div>
          </el-form>
          <el-button v-if="blocked" type="primary" :disabled="saving" @click="reloadCurrent">重新读取配置并核对</el-button>
          <section v-if="data" class="diagnostics-panel">
            <div class="detail-heading"><h3>配置诊断与变更影响</h3><el-button :loading="diagnosticBusy" :disabled="saving" @click="loadDiagnostics()">校验已保存配置</el-button></div>
            <p v-if="dirty" class="muted">下方结果针对已保存版本，尚未保存的改动未参与校验。</p>
            <ErrorAlert :message="diagnosticsError" @retry="loadDiagnostics()" />
            <el-descriptions :column="2" border>
              <el-descriptions-item label="业务模板">{{ templateName(data.template_code) }}<details v-if="data.template_code" class="template-reference"><summary>模板标识</summary><code>{{ data.template_code }}</code></details></el-descriptions-item><el-descriptions-item label="模板版本">{{ data.template_version ?? '尚未返回' }}</el-descriptions-item>
              <el-descriptions-item label="回执要求" :span="2">{{ receiptRequirement(data.receipt_requirement) }}</el-descriptions-item>
              <el-descriptions-item label="配置可用性">{{ availability(diagnostics?.availability || data.availability) }}</el-descriptions-item><el-descriptions-item label="阻断原因">{{ diagnostics?.blocked_reason || data.blocked_reason || '未返回阻断原因' }}</el-descriptions-item>
              <el-descriptions-item label="受影响待发任务">{{ diagnostics?.pending_count ?? '尚未读取' }}</el-descriptions-item><el-descriptions-item label="关联计划数量">{{ diagnostics?.affected_plan_count ?? '尚未读取' }}</el-descriptions-item>
              <el-descriptions-item label="历史通知数量">{{ diagnostics?.history_count ?? data.history_count ?? '尚未读取' }}</el-descriptions-item><el-descriptions-item label="有效截止时间">{{ data.valid_until ? formatTime(data.valid_until) : '未设截止时间' }}</el-descriptions-item>
            </el-descriptions>
            <p class="form-note">修改或停用配置后，后台在发送前重新校验待发任务；不会静默替换原接收快照。已送达记录保留当时对象、时间和实际结果，仍从原业务通知记录查看。</p>
          </section>
        </template>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.template-reference{margin-top:6px;color:var(--admin-muted);font-size:12px}.template-reference summary{cursor:pointer}.template-reference code{display:block;margin-top:6px;white-space:normal;overflow-wrap:anywhere}.notification-layout{display:grid;grid-template-columns:285px minmax(0,1fr);gap:16px;align-items:start;min-width:0}.settings-list,.settings-detail{min-width:0}.setting-items{display:flex;flex-direction:column;gap:10px;min-height:140px;max-height:64vh;overflow:auto}.setting-item{display:flex;flex-direction:column;gap:7px;width:100%;border:1px solid var(--admin-border);border-radius:8px;padding:13px;background:#f8fbff;color:var(--admin-text);text-align:left;cursor:pointer;white-space:normal;overflow-wrap:anywhere}.setting-item:hover,.setting-item.active{border-color:var(--admin-primary);background:#edf5ff}.setting-item:focus-visible{outline:2px solid var(--admin-primary);outline-offset:2px}.setting-item small,.muted{font-size:12px;color:var(--admin-muted);line-height:1.6}.setting-item strong{font-size:14px}.list-pager{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between;margin-top:16px;font-size:12px}.list-pager .el-button+.el-button{margin-left:0}.detail-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}.detail-heading h2,.detail-heading h3{margin:0;white-space:normal;overflow-wrap:anywhere}.detail-heading h2{font-size:20px}.detail-heading h3{font-size:16px}.recipient-summary{padding:16px;border:1px solid #c5ddf7;border-radius:8px;background:#f4f9ff;margin-bottom:18px}.recipient-summary span,.recipient-summary strong{display:block}.recipient-summary span{font-size:12px;color:var(--admin-muted);margin-bottom:7px}.recipient-summary strong{font-size:19px}.recipient-summary p{margin:9px 0 0;color:var(--admin-muted);line-height:1.6}.save-actions{display:flex;gap:8px;flex-wrap:wrap}.diagnostics-panel{margin-top:26px;padding-top:20px;border-top:1px solid var(--admin-border)}.el-form-item .el-select,.el-form-item .el-date-editor{width:100%}:deep(.el-alert){margin:12px 0}:deep(.el-descriptions__body td),:deep(.el-alert__title),:deep(.el-form-item__label),:deep(.el-button span){white-space:normal;overflow-wrap:anywhere}.settings-detail p{white-space:normal;overflow-wrap:anywhere}.form-note{margin-top:15px}
@media(max-width:1120px){.notification-layout{grid-template-columns:245px minmax(0,1fr)}}@media(max-width:900px){.notification-layout{grid-template-columns:1fr}.setting-items{max-height:240px}}
</style>
