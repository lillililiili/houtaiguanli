<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { unitAdministrationApi as api } from '@/api/unitAdministration'
import UnitPermissionEditor from '@/views/unit/UnitPermissionEditor.vue'
import { makePermissionDraft, permissionPayload } from '@/views/unit/permissionDraft'
import { managementDraft, managementModules } from '@/utils/managementAccess'
import UnitRoleAssignmentEditor from './UnitRoleAssignmentEditor.vue'
import { rolePlanPayload, rolePlanError } from './unitRoleAssignment'

const props = defineProps({ visible: Boolean, organization: { type: Object, default: null } })
const emit = defineEmits(['update:visible', 'saved'])
const loading = ref(false), busy = ref(false), error = ref(''), config = ref(null), requiresReload = ref(false)
const draft = reactive({ administrator_user_id: '', permissions: [], actions: [], management_permissions: managementDraft() })
const activeTab = ref('administrator')
const rolePlan = ref({ new_roles: [], assignments: {}, retire_role_codes: [] })
let sequence = 0
const visible = computed({ get: () => props.visible, set: value => emit('update:visible', value) })
async function load() {
  const current = ++sequence
  if (!props.visible || !props.organization?.org_id) return
  config.value = null; error.value = ''; loading.value = true
  try {
    const data = await api.delegation(props.organization.org_id)
    if (current !== sequence) return
    config.value = data
    draft.administrator_user_id = data.administrator_user_id || ''
    draft.permissions = makePermissionDraft(data.catalog?.permissions, data.permissions)
    draft.actions = (data.actions || []).map(item => ({ ...item }))
    draft.management_permissions = managementDraft(data.management_permissions)
    rolePlan.value = { new_roles: [], assignments: {}, retire_role_codes: [] }
    activeTab.value = 'administrator'
    requiresReload.value = false
  } catch (e) { if (current === sequence) error.value = e.message || '单位授权加载失败。' }
  finally { if (current === sequence) loading.value = false }
}
async function save() {
  const orgId = props.organization?.org_id
  if (busy.value || loading.value || requiresReload.value || !props.visible || !config.value || config.value.org_id !== orgId) return
  if (!config.value.managed && !draft.administrator_user_id) return ElMessage.warning('首次启用请指定本单位管理员。')
  const assignmentError = rolePlanError(config.value, rolePlan.value, draft)
  if (assignmentError) { activeTab.value = 'members'; return ElMessage.warning(assignmentError) }
  // 两次请求共用本次点击时的单位、版本及独立数据副本，不再读取可变表单。
  const request = {
    administrator_user_id: draft.administrator_user_id || null,
    ...permissionPayload(draft.permissions, draft.actions),
    management_permissions: draft.management_permissions.map(item => ({ ...item })),
    ...rolePlanPayload(rolePlan.value),
    expected_version: config.value.version
  }
  const current = sequence
  const isCurrent = () => current === sequence && props.visible && props.organization?.org_id === orgId
  busy.value = true; error.value = ''
  try {
    const validation = await api.preview(orgId, request)
    if (!isCurrent()) return
    if (!validation?.preview_token || validation.org_id !== orgId || validation.expected_version !== request.expected_version || !Number.isFinite(Number(validation.expires_at))) {
      requiresReload.value = true; error.value = '保存校验结果无效，请重新加载后重试。'; return
    }
    if (Number(validation.expires_at) <= Date.now()) {
      requiresReload.value = true; error.value = '保存校验已过期，请重新加载后重试。'; return
    }
    await api.apply(orgId, { ...request, preview_token: validation.preview_token })
    if (!isCurrent()) return
    ElMessage.success('单位授权已生效，受影响人员需重新登录。')
    emit('saved'); visible.value = false
  } catch (e) {
    if (!isCurrent()) return
    const snapshotChanged = ['DELEGATION_PREVIEW_STALE', 'VERSION_CONFLICT'].includes(e.code)
    requiresReload.value = snapshotChanged || ['NETWORK_ERROR', 'TIMEOUT'].includes(e.code)
    error.value = snapshotChanged
      ? '单位人员、角色或授权已变化，或保存校验已过期，请重新加载后重试。'
      : e.message || '保存失败，请重新加载后重试。'
  }
  finally { busy.value = false }
}
watch(() => [props.visible, props.organization?.org_id], load, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <el-dialog v-model="visible" :title="`单位授权 · ${organization?.name || ''}`" width="min(960px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" destroy-on-close>
    <div v-loading="loading" class="delegation-content">
      <ErrorAlert :message="error" @retry="load" />
      <template v-if="config">
        <el-alert title="保存后立即生效：管理员继承单位授权，其他人员按角色授权，数据范围均为本单位；受影响人员需重新登录。" type="info" :closable="false" show-icon />
        <el-tabs v-model="activeTab" class="delegation-tabs">
        <el-tab-pane label="管理员" name="administrator">
        <el-form label-position="top">
          <el-form-item label="单位管理员" :required="!config.managed">
            <el-select v-model="draft.administrator_user_id" clearable filterable :disabled="busy" placeholder="选择本单位前台账号">
              <el-option v-for="person in config.administrator_candidates || []" :key="person.user_id" :value="person.user_id" :label="`${person.name}（${person.account}）`" />
            </el-select>
            <small>需要新账号时，请先在用户管理新增本单位前台用户。{{ config.managed ? '清空后将撤任管理员，单位权限和本单位数据范围仍保留。' : '' }}</small>
          </el-form-item>
        </el-form>
        <p>单位管理员直接继承平台为本单位开放的全部前台权限和后台功能，无需单独配置个人角色权限。仅管理本单位，不含下级单位；审批、反制等动作仍需平台在单位权限中明确开放，并遵守申请不能自审等业务限制。</p>
        </el-tab-pane>
        <el-tab-pane label="前台权限" name="business">
        <h3>本单位人员的前台权限上限</h3>
        <UnitPermissionEditor v-model:permissions="draft.permissions" v-model:actions="draft.actions" :modules="config.catalog?.action_modules || []" :disabled="busy" />
        </el-tab-pane>
        <el-tab-pane label="后台功能" name="management">
          <p>为单位管理员开放后台运维功能，仅访问本单位数据。后台功能与前台业务权限独立。</p>
          <el-table :data="draft.management_permissions" aria-label="单位后台功能">
            <el-table-column label="后台功能" min-width="150"><template #default="{ row }">{{ managementModules.find(module => module.module_key === row.module_key)?.name }}</template></el-table-column>
            <el-table-column label="授权" min-width="170"><template #default="{ row }">
              <el-select v-model="row.level" :aria-label="`${managementModules.find(module => module.module_key === row.module_key)?.name}后台授权`" :disabled="busy">
                <el-option value="NONE" label="不开放" /><el-option value="READ" label="查看" />
                <el-option v-if="row.module_key !== 'monitoring'" value="OP" label="查看并操作" />
              </el-select>
            </template></el-table-column>
            <el-table-column label="操作范围" min-width="240"><template #default="{ row }">{{ managementModules.find(module => module.module_key === row.module_key)?.operation || '仅查看状态，不下放设备控制' }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="人员角色" name="members"><UnitRoleAssignmentEditor v-model="rolePlan" :config="config" :administrator-user-id="draft.administrator_user_id" :permissions="draft.permissions" :actions="draft.actions" :disabled="busy" /></el-tab-pane>
        </el-tabs>
      </template>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="loading || busy || !config || requiresReload" @click="save">保存并立即生效</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.delegation-content{min-height:160px}.delegation-content>.el-alert{margin-bottom:18px}.delegation-content .el-form{margin-top:18px}.delegation-content .el-select{width:100%}.delegation-content small{display:block;margin-top:7px;color:var(--admin-muted);font-size:12px;line-height:1.6}.delegation-content h3{font-size:16px;margin:20px 0 12px}
</style>
