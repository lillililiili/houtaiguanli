<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import { systemApi } from '@/api/system'
import DirectorySelect from './DirectorySelect.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { contactRoles, organizationTypes, configurationError, requiresReload } from '@/utils/directoryConfiguration'

const props = defineProps({ visible: Boolean, embedded: Boolean, basicOnly: Boolean, parent: { type: Object, default: null }, organizations: { type: Array, default: () => [] }, kind: { type: String, required: true }, row: { type: Object, default: null }, organization: { type: Object, default: null }, canEdit: Boolean })
const emit = defineEmits(['update:visible', 'saved', 'refresh', 'busy'])

const form = reactive({})
const busy = ref(false)
watch(busy, value => emit('busy', value), { flush: 'sync' })
const error = ref('')
const blocked = ref(false)
const normalizedPhone = value => String(value || '').replace(/[ ()-]/g, '')
const phoneChanged = computed(() => props.kind === 'contact' && props.row && normalizedPhone(form.phone) !== normalizedPhone(props.row.phone))
watch(() => form.phone, (value, previous) => {
  if (props.kind !== 'contact' || normalizedPhone(value) === normalizedPhone(previous)) return
  form.verified_at = null
  form.verification_basis = ''
}, { flush: 'sync' })
const title = computed(() => `${props.row ? '编辑' : '新增'}${({ profile: '单位', contact: '联系人', binding: '来源映射' })[props.kind]}`)
watch(() => [props.visible, props.kind, props.row], () => {
  if (!props.visible) return
  const row = props.row || {}
  Object.keys(form).forEach(key => delete form[key])
  Object.assign(form, {
    name: row.name || '', org_code: row.org_code || '', parent_id: row.parent_id || (!props.row ? props.parent?.org_id : '') || '',
    organization_type: row.organization_type || 'OTHER', credit_code: row.credit_code || '', address: row.address || '', remarks: row.remarks || '', responsibilities: row.responsibilities || '',
    org_id: row.org_id || props.organization?.org_id || '', roles: [...(row.roles || [])], phone: row.phone || '', email: row.email || '', user_id: row.user_id || '',
    enabled: row.enabled !== false, valid_until: row.valid_until ? new Date(Number(row.valid_until)) : null, verified_at: row.verified_at ? new Date(Number(row.verified_at)) : null, verification_basis: row.verification_basis || '',
    source_id: row.source_id || '', external_org_code: row.external_org_code || ''
  })
  error.value = ''; blocked.value = false
}, { immediate: true })
function close() { if (!busy.value) emit('update:visible', false) }
function reload() { emit('refresh'); close() }
async function save() {
  if (!props.canEdit || busy.value || blocked.value) return
  error.value = ''
  if (props.kind !== 'binding' && !form.name.trim()) { error.value = '请填写名称。'; return }
  if (props.kind === 'contact' && (!form.org_id || !form.roles.length)) { error.value = '请选择所属单位和联系用途。'; return }
  if (props.kind === 'contact' && form.verified_at && !form.verification_basis.trim()) { error.value = '填写核实时间时，请同时说明核实依据。'; return }
  if (props.kind === 'binding' && (!form.source_id || !form.external_org_code.trim() || !form.org_id)) { error.value = '请选择来源系统、所属单位并填写来源系统中的单位编码。'; return }
  let body
  if (props.kind === 'profile') body = {
    name: form.name.trim(), parent_id: form.parent_id || null, organization_type: form.organization_type,
    credit_code: form.credit_code.trim(), address: form.address.trim(), remarks: form.remarks.trim(), responsibilities: form.responsibilities.trim(),
    ...(!props.row && form.org_code.trim() ? { org_code: form.org_code.trim() } : {})
  }
  else if (props.kind === 'contact') body = {
    org_id: form.org_id, name: form.name.trim(), roles: [...form.roles], phone: form.phone.trim(), email: form.email.trim(),
    user_id: form.user_id || null, enabled: form.enabled, valid_until: form.valid_until ? Number(form.valid_until) : null,
    verified_at: !phoneChanged.value && form.verified_at ? Number(form.verified_at) : null, verification_basis: phoneChanged.value ? '' : form.verification_basis.trim()
  }
  else body = { source_id: form.source_id, external_org_code: form.external_org_code.trim(), org_id: form.org_id, enabled: form.enabled }
  if (props.row) body.expected_version = props.row.version
  busy.value = true
  try {
    const method = { profile: 'saveProfile', contact: 'saveContact', binding: 'saveBinding' }[props.kind]
    const id = props.row?.[{ profile: 'org_id', contact: 'contact_id', binding: 'binding_id' }[props.kind]]
    const result = props.basicOnly && props.kind === 'profile'
      ? await (id ? systemApi.updateOrganization(id, { name: body.name, parent_id: body.parent_id, expected_version: body.expected_version }) : systemApi.createOrganization({ name: body.name, parent_id: body.parent_id }))
      : await directoryApi[method](id, body)
    emit('saved', result)
    emit('update:visible', false)
    ElMessage.success('资料已保存，历史通知快照保持原样。')
  } catch (e) { error.value = configurationError(e); blocked.value = requiresReload(e) }
  finally { busy.value = false }
}
</script>

<template>
  <component :is="embedded ? 'section' : ElDialog" :model-value="visible" :title="title" width="min(760px, calc(100vw - 32px))" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" class="directory-record-dialog" @update:model-value="value => !value && close()">
    <el-alert v-if="!canEdit" title="当前账号只能查看资料，保存需要单位资料维护权限。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="kind === 'contact' && row" type="info" :closable="false" :title="`已关联通知配置 ${row.notification_count ?? 0} 项，待发任务 ${row.pending_count ?? 0} 项，历史通知 ${row.history_count ?? 0} 项。停用或修改联系方式后，待发任务由后端重新校验；历史接收快照不变。`" />
    <el-alert v-if="phoneChanged" title="联系电话已修改。本次保存会清除原核验，请先保存新号码，再打开联系人记录重新核验；原通知的号码快照保持原样。" type="warning" :closable="false" show-icon />
    <el-form label-position="top" :disabled="!canEdit || busy || blocked" @submit.prevent="save">
      <div class="form-grid">
        <template v-if="kind === 'profile'">
          <el-form-item label="单位名称" required><el-input v-model="form.name" type="textarea" autosize maxlength="128" /></el-form-item>
          <el-form-item v-if="!basicOnly" label="单位类型" required><el-select v-model="form.organization_type"><el-option v-for="type in organizationTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
          <el-form-item v-if="row && !basicOnly" label="单位编码"><el-input v-model="form.org_code" :disabled="Boolean(row)" placeholder="新建时可留空，由系统生成" /></el-form-item>
          <el-form-item label="上级单位"><el-select v-if="basicOnly" v-model="form.parent_id" clearable filterable><el-option v-for="org in organizations.filter(item => item.org_id !== row?.org_id)" :key="org.org_id" :label="org.name" :value="org.org_id" /></el-select><DirectorySelect v-else v-model="form.parent_id" kind="organizations" :current-label="row?.parent_name || parent?.name || ''" /></el-form-item>
          <el-form-item v-if="!basicOnly" label="统一社会信用代码或统一标识"><el-input v-model="form.credit_code" /></el-form-item>
          <el-form-item v-if="!basicOnly" class="wide" label="单位地址"><el-input v-model="form.address" type="textarea" autosize /></el-form-item>
          <el-form-item v-if="!basicOnly && row?.responsibilities" class="wide" label="业务说明"><el-input v-model="form.responsibilities" type="textarea" :rows="3" /></el-form-item>
          <el-form-item v-if="!basicOnly" class="wide" label="备注"><el-input v-model="form.remarks" type="textarea" :rows="2" /></el-form-item>
          <p class="form-note wide">单位资料与账号分别维护，保存后复用原有单位关联。</p>
        </template>
        <template v-else-if="kind === 'contact'">
          <el-form-item label="联系人姓名" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="所属单位" required><DirectorySelect v-model="form.org_id" kind="organizations" :current-label="row?.org_name || organization?.name || ''" /></el-form-item>
          <el-form-item class="wide" label="联系用途" required><el-checkbox-group v-model="form.roles"><el-checkbox v-for="role in contactRoles" :key="role.value" :label="role.value">{{ role.label }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
          <el-form-item label="电子邮箱"><el-input v-model="form.email" /></el-form-item>
          <el-form-item label="关联现有账号（可不选）"><DirectorySelect v-model="form.user_id" kind="users" :current-label="row?.user_name || ''" /></el-form-item>
          <el-form-item label="有效截止时间"><el-date-picker v-model="form.valid_until" type="datetime" placeholder="留空表示未设置截止时间" /></el-form-item>
          <el-form-item label="联系方式核实时间"><el-date-picker v-model="form.verified_at" :disabled="Boolean(phoneChanged)" type="datetime" placeholder="尚未核实请留空" /></el-form-item>
          <el-form-item label="核实依据"><el-input v-model="form.verification_basis" :disabled="Boolean(phoneChanged)" type="textarea" :rows="2" placeholder="记录可追溯的身份及联系方式来源" /></el-form-item>
          <el-form-item label="联系人可用状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
          <p class="form-note wide">联系人独立建档，不需要先开账号。飞手身份必须有明确关联和有效联系方式；单位联络人不会作为飞手号码的默认替代。</p>
        </template>
        <template v-else>
          <el-form-item label="来源系统" required><DirectorySelect v-model="form.source_id" kind="sources" :current-label="row?.source_name || ''" /></el-form-item>
          <el-form-item label="来源系统中的单位编码" required><el-input v-model="form.external_org_code" type="textarea" autosize /></el-form-item>
          <el-form-item label="对应单位" required><DirectorySelect v-model="form.org_id" kind="organizations" :current-label="row?.org_name || organization?.name || ''" /></el-form-item>
          <el-form-item label="映射可用状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
          <p class="form-note wide">同一来源系统可以报送多个单位，按来源与单位编码精确关联。停用映射后，新关联和待发通知按后端条件校验；旧计划和通知材料不会改写。</p>
        </template>
      </div>
    </el-form>
    <div v-if="embedded" class="editor-footer"><el-button :disabled="busy" @click="close">取消</el-button>
      <el-button v-if="blocked" type="primary" @click="reload">重新加载并核对</el-button>
      <el-button v-else-if="canEdit" type="primary" :loading="busy" @click="save">保存</el-button></div>
    <template v-if="!embedded" #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button v-if="blocked" type="primary" @click="reload">重新加载并核对</el-button>
      <el-button v-else-if="canEdit" type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </component>
</template>

<style scoped>
.editor-footer{display:flex;justify-content:flex-end;gap:8px;margin-top:20px}
:deep(.el-alert){margin-bottom:16px}:deep(.el-select),:deep(.el-date-editor){width:100%}:deep(.el-form-item__content){min-width:0}:deep(.el-checkbox-group){display:flex;gap:10px;flex-wrap:wrap}:deep(.el-checkbox){height:auto;margin-right:12px;white-space:normal}:deep(.el-checkbox__label),:deep(.el-alert__title),:deep(.el-form-item__label){white-space:normal;overflow-wrap:anywhere}.form-note{margin-bottom:0}
</style>
