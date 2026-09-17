<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import DirectorySelect from './DirectorySelect.vue'
import SourceBindingSelect from './SourceBindingSelect.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { associationText, configurationError, requiresReload } from '@/utils/directoryConfiguration'
const props = defineProps({ canEdit: Boolean })
const emit = defineEmits(['saved'])
const planId = ref('')
const planLabel = ref('')
const data = ref(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const blocked = ref(false)
const form = reactive({ source_binding_id: '', operator_org_id: '', pilot_contact_id: '', reason: '' })
let sequence = 0
async function load() {
  const current = ++sequence
  data.value = null; error.value = ''; blocked.value = false
  Object.assign(form, { source_binding_id: '', operator_org_id: '', pilot_contact_id: '', reason: '' })
  if (!planId.value) { loading.value = false; return }
  loading.value = true
  try {
    const result = await directoryApi.subjects(planId.value)
    if (current !== sequence) return
    data.value = result
    Object.assign(form, { source_binding_id: result.source_binding_id || '', operator_org_id: result.operator_org_id || '', pilot_contact_id: result.pilot_contact_id || '', reason: '' })
  } catch (e) { if (current === sequence) error.value = e.message || '计划关联读取失败。' }
  finally { if (current === sequence) loading.value = false }
}
async function save() {
  if (!props.canEdit || !data.value || blocked.value || saving.value) return
  if (!form.reason.trim()) { error.value = '请填写本次关联或调整的依据。'; return }
  const id = planId.value
  const current = sequence
  saving.value = true; error.value = ''
  try {
    await directoryApi.saveSubjects(id, { source_binding_id: form.source_binding_id || null, operator_org_id: form.operator_org_id || null, pilot_contact_id: form.pilot_contact_id || null, reason: form.reason.trim(), expected_version: data.value.version })
    if (current !== sequence) return
    ElMessage.success('计划关联已保存，原始申报文本和历史通知保持原样。')
    emit('saved'); await load()
  } catch (e) { if (current === sequence) { error.value = configurationError(e); blocked.value = requiresReload(e) } }
  finally { saving.value = false }
}
function choosePlan(option) { planLabel.value = option?.label || ''; load() }
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <section class="plan-association" v-loading="loading">
    <el-form label-position="top">
      <el-form-item label="查找当前有权维护的计划"><DirectorySelect v-model="planId" kind="plans" :disabled="saving" :current-label="planLabel" placeholder="输入计划名称或计划编号检索" @select="choosePlan" /></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><template #default><el-button :disabled="loading || saving" link type="primary" @click="load">重新读取计划并核对</el-button></template></el-alert>
    <el-empty v-if="!planId" description="选择一份计划，关联报送单位、执行单位和飞手。" />
    <template v-if="data">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="计划">{{ data.plan_no || planLabel }}</el-descriptions-item><el-descriptions-item label="关联情况">{{ associationText(data.association_status) }}</el-descriptions-item>
        <el-descriptions-item label="技术来源">{{ data.source_name || '未提供' }}</el-descriptions-item><el-descriptions-item label="当前报送单位">{{ data.reporting_org_name || '待关联' }}</el-descriptions-item>
        <el-descriptions-item label="当前执行单位">{{ data.operator_org_name || '待关联' }}</el-descriptions-item><el-descriptions-item label="当前关联飞手">{{ data.pilot_name || '未提供' }}</el-descriptions-item>
        <el-descriptions-item label="当前飞手联系方式">{{ data.pilot_contact_hint || '未关联有效联系方式' }}</el-descriptions-item><el-descriptions-item label="记录版本">{{ data.version }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" :disabled="!canEdit || saving || blocked" class="association-form" @submit.prevent="save">
        <el-form-item label="报送单位来源映射"><SourceBindingSelect v-model="form.source_binding_id" :source-id="data.source_id || ''" :current-label="data.reporting_org_name ? `${data.source_name || '来源系统'} · ${data.reporting_org_name}` : ''" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="执行单位"><DirectorySelect v-model="form.operator_org_id" kind="organizations" :current-label="data.operator_org_name || ''" @select="form.pilot_contact_id = ''" /></el-form-item>
          <el-form-item label="明确关联的飞手"><DirectorySelect v-model="form.pilot_contact_id" kind="contacts" :org-id="form.operator_org_id" :current-label="[data.pilot_name, data.pilot_contact_hint].filter(Boolean).join(' · ')" placeholder="检索具有飞手用途的联系人" /></el-form-item>
        </div>
        <el-form-item label="关联依据" required><el-input v-model="form.reason" type="textarea" :rows="2" placeholder="填写可核对的报送材料、外部编码或人工核对依据" /></el-form-item>
        <p class="form-note">报送单位由来源系统与外部单位编码的映射确定。保存后由后端校验飞手身份、联系方式和用途，不会仅凭同名自动关联或替代历史接收人。</p>
        <el-button v-if="canEdit" type="primary" :loading="saving" :disabled="blocked" @click="save">保存计划关联</el-button>
        <p v-else class="readonly-note">当前账号可查看关联，修改需要单位档案维护权限及该计划的数据权限。</p>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.plan-association{min-width:0}.association-form{margin-top:20px}.readonly-note{color:var(--admin-muted)}:deep(.el-alert){margin:12px 0}:deep(.el-descriptions__body td),:deep(.el-alert__title){overflow-wrap:anywhere;white-space:normal}
</style>
