<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmDeletion } from '@/utils/deleteConfirmation'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { unitAdministrationApi as api } from '@/api/unitAdministration'
import UnitPermissionEditor from './UnitPermissionEditor.vue'
import UnitPermissionSummary from './UnitPermissionSummary.vue'
import { useAuthStore } from '@/stores/auth'
import { roleDisplayName, roleCopySource } from '@/utils/rolePresentation'
import { managementModules } from '@/utils/managementAccess'
import { makePermissionDraft, permissionPayload } from './permissionDraft'
import { effectivePermissionSummary } from './effectivePermissionSummary'

const loading = ref(false), error = ref(''), roles = ref([]), catalog = ref(null), query = ref('')
const selectedCode = ref(''), detail = ref(null), permissions = ref([]), actions = ref([]), saving = ref(false)
const dialog = reactive({ visible: false, busy: false, name: '', description: '', permissions: [], actions: [] })
const baseline = ref('')
const auth = useAuthStore(), roleCategory = ref('staff')
const myPermissionsReady = ref(false)
const myManagementFunctions = computed(() => managementModules.filter(module => auth.user?.management_context?.menu_keys?.includes(module.module_key) && auth.user?.management_context?.permission_codes?.includes(`${module.module_key}.read`)).map(module => ({ ...module, level: auth.user.management_context.permission_codes.includes(`${module.module_key}.op`) ? '查看并操作' : '查看' })))
const availableRoles = computed(() => roles.value.filter(row => !row.platform_maintained))
const myBusinessPermissions = computed(() => effectivePermissionSummary(auth.user, catalog.value || {}))
const filteredRoles = computed(() => availableRoles.value.filter(row => !query.value.trim() || `${roleDisplayName(row)} ${roleCopySource(row)} ${row.description || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const locked = computed(() => Boolean(detail.value?.platform_maintained))
const dirty = computed(() => JSON.stringify(permissionPayload(permissions.value, actions.value)) !== baseline.value)
let sequence = 0, alive = true
function resetDraft(row) {
  detail.value = row
  permissions.value = makePermissionDraft(catalog.value?.permissions, row?.permissions)
  actions.value = (row?.actions || []).map(item => ({ ...item }))
  baseline.value = JSON.stringify(permissionPayload(permissions.value, actions.value))
}
async function loadRole(code) {
  const current = ++sequence
  selectedCode.value = code; loading.value = true; error.value = ''
  try {
    const row = code ? await api.role(code) : null
    if (alive && current === sequence) resetDraft(row)
  } catch (e) { if (alive && current === sequence) { error.value = e.message || '角色详情加载失败。'; resetDraft(null) } }
  finally { if (alive && current === sequence) loading.value = false }
}
async function selectRole(code) {
  if (code === selectedCode.value || saving.value) return
  if (dirty.value && detail.value) {
    try { await ElMessageBox.confirm('当前权限修改尚未保存，切换后将放弃这些修改。', '切换角色', { type: 'warning', confirmButtonText: '放弃并切换' }) }
    catch { return }
  }
  await loadRole(code)
}
async function loadAll() {
  loading.value = true; error.value = ''
  try {
    const [unit, list] = await Promise.allSettled([api.catalog(), api.roles()])
    if (!alive) return
    if (unit.status === 'rejected') throw unit.reason
    catalog.value = unit.value; roles.value = list.status === 'fulfilled' ? list.value || [] : []
    if (roleCategory.value === 'mine') await loadMyPermissions()
    else await loadRole(availableRoles.value.some(row => row.role_code === selectedCode.value) ? selectedCode.value : availableRoles.value[0]?.role_code || '')
    if (list.status === 'rejected' && roleCategory.value === 'staff') error.value = list.reason.message || '人员角色加载失败。'
  } catch (e) { if (alive) { error.value = e.message || '角色加载失败。'; loading.value = false } }
}
async function loadMyPermissions() {
  const current = ++sequence
  resetDraft(null); selectedCode.value = ''; loading.value = true; error.value = ''; myPermissionsReady.value = false
  try {
    await auth.loadCurrentUser?.()
    if (alive && current === sequence) {
      myPermissionsReady.value = Boolean(catalog.value)
      if (!catalog.value) error.value = '权限目录尚未加载，请重新加载后查看。'
    }
  }
  catch (e) { if (alive && current === sequence) error.value = e.message || '当前权限读取失败，请重新加载。' }
  finally { if (alive && current === sequence) loading.value = false }
}
function openCreate() {
  roleCategory.value = 'staff'
  Object.assign(dialog, { visible: true, name: '', description: '', permissions: makePermissionDraft(catalog.value?.permissions), actions: [] })
}
async function createRole() {
  if (dialog.busy) return
  if (!dialog.name.trim()) return ElMessage.warning('请填写角色名称。')
  dialog.busy = true
  try {
    const saved = await api.createRole({ name: dialog.name.trim(), description: dialog.description.trim(), ...permissionPayload(dialog.permissions, dialog.actions) })
    selectedCode.value = saved.role_code; dialog.visible = false
    ElMessage.success('本单位角色已创建。'); await loadAll()
  } catch (e) { ElMessage.error(e.message || '角色创建失败。') }
  finally { dialog.busy = false }
}
async function savePermissions() {
  if (saving.value || locked.value || !dirty.value || !detail.value) return
  saving.value = true
  try {
    await ElMessageBox.confirm('保存后权限立即生效，使用此角色的人员需要重新登录。', `保存权限 · ${detail.value.name}`, { type: 'warning', confirmButtonText: '保存并立即生效' })
    await api.updateRolePermissions(detail.value.role_code, { expected_version: detail.value.version, ...permissionPayload(permissions.value, actions.value) })
    ElMessage.success('角色权限已保存。'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '权限保存失败。') }
  finally { saving.value = false }
}
async function editDescription() {
  if (locked.value || saving.value) return
  try {
    const { value } = await ElMessageBox.prompt('角色说明', `编辑 · ${detail.value.name}`, { inputType: 'textarea', inputValue: detail.value.description || '' })
    await api.updateRole(detail.value.role_code, { description: value, expected_version: detail.value.version })
    ElMessage.success('角色说明已保存。'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '保存失败。') }
}
async function toggleRole() {
  if (locked.value || saving.value) return
  const enable = detail.value.enabled === false
  try {
    await ElMessageBox.confirm(enable ? '启用后人员仍需具有有效账号和单位授权。' : '停用后使用此角色的人员将失去业务权限，现有会话撤销。', enable ? '启用角色' : '停用角色', { type: 'warning' })
    await api.setRoleStatus(detail.value.role_code, { enabled: enable, expected_version: detail.value.version })
    ElMessage.success(enable ? '角色已启用。' : '角色已停用。'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '状态保存失败。') }
}
async function removeRole() {
  if (locked.value || saving.value || !detail.value) return
  if (detail.value.user_count) return ElMessage.warning('请先为使用此角色的人员分配其他角色。')
  try {
    const record = await confirmDeletion('确认删除该角色？历史授权记录保留。', `删除角色 · ${detail.value.name}`)
    await api.deleteRole(detail.value.role_code, detail.value.version, record)
    ElMessage.success('角色已删除。'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '删除失败。') }
}
onMounted(loadAll)
watch(roleCategory, value => { query.value = ''; if (value === 'mine') void loadMyPermissions(); else void loadRole(availableRoles.value[0]?.role_code || '') })
onBeforeUnmount(() => { alive = false; sequence++ })
</script>

<template>
  <div class="page-stack">
    <PageHeader title="本单位角色" :description="`${catalog?.org_name || '本单位'} · 在平台开放的权限范围内配置人员角色。`"><el-button v-if="roleCategory === 'staff'" type="primary" :disabled="!catalog" @click="openCreate">新增角色</el-button></PageHeader>
    <ErrorAlert :message="error" @retry="loadAll" />
    <el-tabs v-model="roleCategory"><el-tab-pane label="人员角色" name="staff" /><el-tab-pane label="我的权限" name="mine" /></el-tabs>
    <section v-if="roleCategory === 'mine'" v-loading="loading" class="content-card my-permissions" aria-label="我的实际权限">
      <h2>当前可用功能</h2>
      <template v-if="myPermissionsReady">
      <el-alert title="以下为当前账号实际生效的权限，由平台维护。后台管理身份不会自动增加前台业务权限。" type="info" :closable="false" show-icon />
      <p class="my-scope">数据范围：{{ auth.user?.data_scope === 'OWN_ORG' ? `${auth.user?.org_name || catalog?.org_name || '本单位'}（仅本单位，不含下级单位）` : '当前范围未确认，请重新登录核对' }}</p>
      <UnitPermissionSummary :permissions="myBusinessPermissions.permissions" :actions="myBusinessPermissions.actions" :modules="catalog?.action_modules || []" />
      <section class="my-management-permissions" aria-label="我的后台权限"><h3>后台功能</h3><p>固定管理：本单位人员、本单位角色。</p><ul v-if="myManagementFunctions.length"><li v-for="module in myManagementFunctions" :key="module.module_key">{{ module.name }}：{{ module.level }}</li></ul><p v-else>尚未开通其他后台运维功能，请联系平台管理员。</p></section>
      </template>
      <p v-else-if="!loading">暂时无法核验当前权限，请重新加载后查看。</p>
    </section>
    <section v-else v-loading="loading" class="content-card unit-role-layout">
      <aside class="unit-role-list">
        <el-input v-if="roleCategory === 'staff'" v-model="query" clearable placeholder="搜索人员角色" aria-label="搜索本单位角色" />
        <nav aria-label="本单位角色列表">
          <button v-for="row in filteredRoles" :key="row.role_code" type="button" :class="{ active: selectedCode === row.role_code }" :aria-current="selectedCode === row.role_code ? 'true' : undefined" @click="selectRole(row.role_code)">
            <strong>{{ roleDisplayName(row) }}</strong><span v-if="roleCopySource(row)" class="role-copy-source">{{ roleCopySource(row) }}</span><span>{{ row.user_count || 0 }} 人 · {{ row.enabled === false ? '停用' : '启用' }}</span>
            <el-tag v-if="row.platform_maintained" type="info" size="small">平台维护</el-tag>
          </button>
        </nav>
        <el-empty v-if="!filteredRoles.length" :description="roleCategory === 'staff' ? '暂无人员角色' : '暂无前台业务角色'" :image-size="64" />
      </aside>
      <main class="unit-role-detail">
        <template v-if="detail">
          <header class="role-heading"><div><h2>{{ roleDisplayName(detail) }}</h2><p>{{ detail.description || '未填写说明' }}</p></div><el-tag :type="detail.enabled === false ? 'info' : 'success'">{{ detail.enabled === false ? '停用' : '启用' }}</el-tag></header>
          <div v-if="roleCopySource(detail)" class="role-copy-note"><strong>{{ roleCopySource(detail) }}</strong><p>来源名称仅记录创建时的信息；本角色的权限独立维护。</p></div>
          <el-alert v-if="locked" title="该角色被单位管理员使用，由平台维护。请联系平台调整管理员自身业务权限。" type="info" :closable="false" show-icon />
          <div v-else class="role-tools"><el-button @click="editDescription">编辑说明</el-button><el-button @click="toggleRole">{{ detail.enabled === false ? '启用角色' : '停用角色' }}</el-button><el-button type="danger" plain :disabled="Boolean(detail.user_count)" @click="removeRole">删除角色</el-button></div>
          <UnitPermissionSummary v-if="locked" :permissions="permissions" :actions="actions" :modules="catalog?.action_modules || []" />
          <UnitPermissionEditor v-else v-model:permissions="permissions" v-model:actions="actions" :modules="catalog?.action_modules || []" :ceiling="catalog?.permissions || []" :action-ceiling="catalog?.actions || []" :disabled="saving" />
          <footer v-if="!locked" class="role-footer"><span>{{ dirty ? '有尚未保存的权限修改' : '权限已保存' }}</span><el-button :disabled="!dirty || saving" @click="resetDraft(detail)">放弃修改</el-button><el-button type="primary" :loading="saving" :disabled="!dirty" @click="savePermissions">保存权限</el-button></footer>
        </template>
        <el-empty v-else :description="roleCategory === 'staff' ? '创建人员角色后，即可为本单位人员分配' : '管理员本人的业务授权由平台维护'" />
      </main>
    </section>
    <el-dialog v-model="dialog.visible" title="新增本单位角色" width="min(820px, 94vw)" :close-on-click-modal="!dialog.busy" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="角色名称" required><el-input v-model="dialog.name" maxlength="64" /></el-form-item>
        <el-form-item label="角色说明"><el-input v-model="dialog.description" type="textarea" maxlength="500" /></el-form-item>
      </el-form>
      <UnitPermissionEditor v-model:permissions="dialog.permissions" v-model:actions="dialog.actions" :modules="catalog?.action_modules || []" :ceiling="catalog?.permissions || []" :action-ceiling="catalog?.actions || []" :disabled="dialog.busy" />
      <template #footer><el-button :disabled="dialog.busy" @click="dialog.visible = false">取消</el-button><el-button type="primary" :loading="dialog.busy" @click="createRole">创建角色</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.my-permissions{padding:24px}.my-permissions h2{margin:0 0 16px;font-size:20px}.my-scope{margin:16px 0;color:var(--admin-text);overflow-wrap:anywhere}
.role-copy-source{line-height:1.6;overflow-wrap:anywhere}.role-copy-note{margin-bottom:16px;overflow-wrap:anywhere}.role-copy-note strong{font-size:13px;color:var(--admin-text)}.role-copy-note p{margin:6px 0 0;font-size:12px;line-height:1.6;color:var(--admin-muted)}.role-heading>div{min-width:0}.role-heading h2{overflow-wrap:anywhere}
.unit-role-layout{display:grid;grid-template-columns:260px minmax(0,1fr);min-height:540px}.unit-role-list{padding:18px;border-right:1px solid var(--admin-border)}.unit-role-list nav{display:grid;gap:8px;margin-top:16px}.unit-role-list button{display:grid;gap:7px;width:100%;padding:14px;text-align:left;background:var(--admin-card);border:1px solid var(--admin-border);border-radius:8px;color:var(--admin-text);cursor:pointer}.unit-role-list button:hover,.unit-role-list button.active{border-color:var(--admin-primary);background:var(--admin-primary-soft)}.unit-role-list button:focus-visible{outline:2px solid var(--admin-primary);outline-offset:2px}.unit-role-list strong{font-size:14px;overflow-wrap:anywhere}.unit-role-list span{font-size:12px;color:var(--admin-muted)}.unit-role-list .el-tag{justify-self:start}.unit-role-detail{padding:24px;min-width:0}.role-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.role-heading h2{font-size:19px;margin:0 0 8px}.role-heading p{color:var(--admin-muted);margin:0 0 20px;overflow-wrap:anywhere}.role-tools{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}.role-tools .el-button+.el-button{margin-left:0}.role-footer{display:flex;justify-content:flex-end;align-items:center;gap:10px;border-top:1px solid var(--admin-border);padding-top:20px;margin-top:20px;flex-wrap:wrap}.role-footer span{margin-right:auto;color:var(--admin-muted);font-size:13px}.role-footer .el-button+.el-button{margin-left:0}@media(max-width:850px){.unit-role-layout{grid-template-columns:1fr}.unit-role-list{border-right:0;border-bottom:1px solid var(--admin-border)}.unit-role-list nav{grid-template-columns:repeat(auto-fit,minmax(170px,1fr))}.unit-role-detail{padding:18px}}
</style>
