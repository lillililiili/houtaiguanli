<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, OfficeBuilding, Plus } from '@element-plus/icons-vue'
import OrganizationDialog from './organization/OrganizationDialog.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { hasUserAccess, hasOrganizationAccess } from '@/config/navigation'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'

const auth = useAuthStore()
const ALL_ORGS = '__all__'
const loading = ref(false)
const error = ref('')
const users = ref([])
const roles = ref([])
const organizations = ref([])
const total = ref(0)
const selectedOrgId = ref(ALL_ORGS)
const filters = reactive({ keyword: '', roleCode: '', status: '', page: 1, size: 20 })
const userDialog = reactive({ visible: false, busy: false, mode: 'create', row: null, form: {} })
const orgDialog = reactive({ visible: false, row: null, parent: null, mode: 'view' })
const resetDialog = reactive({ visible: false, busy: false, row: null, password: '' })
const canOperate = computed(() => auth.hasPermission('users.op'))
const canReadUsers = computed(() => hasUserAccess(auth.user))
const canReadDirectory = computed(() => auth.hasPermission('organizations.read'))
const canEditDirectory = computed(() => canReadDirectory.value && auth.hasPermission('organizations.auth'))
const canEditBasic = computed(() => canReadUsers.value && auth.hasPermission('users.auth'))
const canManageOrganization = computed(() => canEditDirectory.value || canEditBasic.value)
const selectedOrg = computed(() => organizations.value.find(item => item.org_id === selectedOrgId.value))
const activeRoles = computed(() => roles.value.filter(item => item.enabled !== false))

const treeData = computed(() => {
  const nodes = new Map(organizations.value.map(item => [item.org_id, { ...item, label: item.name, children: [] }]))
  const roots = []
  nodes.forEach(node => {
    const parent = nodes.get(node.parent_id)
    if (parent && parent.org_id !== node.org_id) parent.children.push(node)
    else roots.push(node)
  })
  const sort = items => items.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')).forEach(item => sort(item.children))
  sort(roots)
  return [{ org_id: ALL_ORGS, label: '全部单位', children: roots }]
})

function statusLabel(value) { return value === 'ACTIVE' ? '启用' : '停用' }
function isAdmin(row) { return row?.role_code === 'ROLE-ADMIN' }
function passwordError(password, account = '') {
  if (password.length < 6 || password.length > 32) return '临时密码须为 6–32 位。'
  if (!/[a-z]/.test(password) || !/[A-Z]/.test(password) || !/\d/.test(password) || !/[^\w]/.test(password)) return '临时密码须包含大小写字母、数字和特殊字符。'
  if (account && password.toLowerCase().includes(account.toLowerCase())) return '临时密码不能包含登录账号。'
  return ''
}

async function loadCatalog() {
  if (canReadUsers.value) {
    const [roleRows, orgRows] = await Promise.all([systemApi.roles(), systemApi.organizations()])
    roles.value = roleRows || []; organizations.value = orgRows || []
  } else if (hasOrganizationAccess(auth.user)) {
    const all = []
    let page = 1
    let hasMore = true
    while (hasMore) {
      const result = await directoryApi.profiles({ page, size: 100 })
      all.push(...(result.items || []))
      hasMore = all.length < result.total && Boolean(result.items?.length)
      page++
    }
    organizations.value = all
  }
  if (selectedOrgId.value !== ALL_ORGS && !organizations.value.some(item => item.org_id === selectedOrgId.value)) selectedOrgId.value = ALL_ORGS
}
async function loadUsers() {
  if (!canReadUsers.value) { users.value = []; total.value = 0; loading.value = false; return }
  loading.value = true
  error.value = ''
  try {
    const result = await systemApi.users({
      keyword: filters.keyword.trim(), roleCode: filters.roleCode, status: filters.status,
      orgId: selectedOrgId.value === ALL_ORGS ? '' : selectedOrgId.value,
      page: filters.page, size: filters.size
    })
    users.value = result.items || []
    total.value = result.total || 0
  } catch (e) { error.value = e.message || '用户列表加载失败。' }
  finally { loading.value = false }
}
async function refreshAll() {
  loading.value = true
  error.value = ''
  try { await loadCatalog(); await loadUsers() }
  catch (e) { error.value = e.message || '系统管理数据加载失败。'; loading.value = false }
}
function search() { filters.page = 1; loadUsers() }
function resetFilters() { Object.assign(filters, { keyword: '', roleCode: '', status: '', page: 1 }); loadUsers() }
function selectOrg(data) { selectedOrgId.value = data.org_id; filters.page = 1; loadUsers() }

function openUser(mode, row = null) {
  userDialog.mode = mode
  userDialog.row = row
  userDialog.form = {
    account: '', name: row?.name || '', phone: row?.phone || '',
    org_id: row?.org_id || selectedOrg.value?.org_id || '',
    role_code: row?.role_code || '', temporary_password: ''
  }
  userDialog.visible = true
}
async function saveUser() {
  const form = userDialog.form
  if (!form.name?.trim() || !form.org_id || (!isAdmin(userDialog.row) && !form.role_code)) return ElMessage.warning('请完整填写姓名、所属单位和角色。')
  if (userDialog.mode === 'create') {
    if (!form.account?.trim() || !form.temporary_password) return ElMessage.warning('请填写登录账号和临时密码。')
    const invalid = passwordError(form.temporary_password, form.account.trim())
    if (invalid) return ElMessage.warning(invalid)
  }
  userDialog.busy = true
  try {
    if (userDialog.mode === 'create') {
      await systemApi.createUser({ account: form.account.trim(), name: form.name.trim(), phone: form.phone?.trim() || '', org_id: form.org_id, role_code: form.role_code, temporary_password: form.temporary_password })
    } else {
      const body = { name: form.name.trim(), phone: form.phone?.trim() || '', org_id: form.org_id, expected_version: userDialog.row.version }
      if (!isAdmin(userDialog.row)) body.role_code = form.role_code
      await systemApi.updateUser(userDialog.row.user_id, body)
    }
    userDialog.visible = false
    ElMessage.success(userDialog.mode === 'create' ? '用户已创建并立即生效。' : '用户资料已保存。')
    await loadUsers()
  } catch (e) { ElMessage.error(e.message || '用户保存失败。') }
  finally { userDialog.busy = false }
}

async function toggleStatus(row) {
  const enabling = row.status !== 'ACTIVE'
  try {
    await ElMessageBox.confirm(enabling ? `确认启用账号“${row.account}”？` : `停用后“${row.account}”的现有会话将立即失效。`, enabling ? '启用账号' : '停用账号', { type: 'warning' })
    await systemApi.setUserStatus(row.user_id, { status: enabling ? 'ACTIVE' : 'DISABLED', expected_version: row.version })
    ElMessage.success(`账号已${enabling ? '启用' : '停用'}。`)
    await loadUsers()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '状态更新失败。') }
}
function openReset(row) { Object.assign(resetDialog, { visible: true, row, password: '' }) }
async function resetPassword() {
  const invalid = passwordError(resetDialog.password, resetDialog.row.account)
  if (invalid) return ElMessage.warning(invalid)
  resetDialog.busy = true
  try {
    await systemApi.resetPassword(resetDialog.row.user_id, { temporary_password: resetDialog.password, expected_version: resetDialog.row.version })
    resetDialog.visible = false
    ElMessage.success('临时密码已设置，该用户的旧会话已撤销。')
    await loadUsers()
  } catch (e) { ElMessage.error(e.message || '密码重置失败。') }
  finally { resetDialog.busy = false }
}
async function removeUser(row) {
  try {
    const { value } = await ElMessageBox.prompt(`账号“${row.account}”将被逻辑删除，历史审计仍会保留。`, '删除用户', { inputPlaceholder: '请输入删除原因', inputValidator: value => !!value.trim() || '删除原因为必填项', type: 'warning' })
    await systemApi.deleteUser(row.user_id, row.version, value.trim())
    ElMessage.success('用户已逻辑删除，相关会话已撤销。')
    await refreshAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '删除失败。') }
}

function openOrg(row = null, parent = null, mode = row ? 'edit' : 'create') {
  if (mode !== 'view' && !canManageOrganization.value) return
  Object.assign(orgDialog, { row, parent, mode, visible: true })
}
async function orgSaved(result) {
  if (result?.org_id) selectedOrgId.value = result.org_id
  await refreshAll()
}
function handleOrgCommand(command, row) {
  if (command === 'add') openOrg(null, row)
  else if (command === 'edit') openOrg(row)
  else if (command === 'view') openOrg(row, null, 'view')
}

watch(() => [filters.roleCode, filters.status], search)
onMounted(refreshAll)
</script>

<template>
  <div class="page-stack page-stack--viewport">
    <PageHeader title="用户管理" description="单位、账号、角色与状态统一管理；唯一超级管理员受服务端保护。">
      <el-button v-if="canReadUsers" :disabled="!canOperate" type="primary" @click="openUser('create')">新增用户</el-button>
    </PageHeader>
    <ErrorAlert :message="error" @retry="refreshAll" />
    <section class="content-card user-management viewport-fill">
      <aside class="org-panel">
        <div class="org-panel__header">
          <div class="org-panel__heading"><span class="org-panel__icon"><el-icon><OfficeBuilding /></el-icon></span><span><strong>单位机构</strong><small>共 {{ organizations.length }} 个单位</small></span></div>
          <el-button type="primary" plain :icon="Plus" :disabled="!canManageOrganization" @click="openOrg()">新增</el-button>
        </div>
        <div class="org-panel__body">
          <el-tree class="org-tree" :data="treeData" node-key="org_id" default-expand-all highlight-current :expand-on-click-node="false" :current-node-key="selectedOrgId" @node-click="selectOrg">
            <template #default="{ data }">
              <span class="org-node">
                <span class="org-node__label" :title="data.label">{{ data.label }}</span>
                <el-dropdown v-if="data.org_id !== ALL_ORGS" trigger="click" @click.stop @command="command => handleOrgCommand(command, data)">
                  <button class="org-node__more" type="button" :aria-label="`管理单位 ${data.label}`" @click.stop><el-icon><MoreFilled /></el-icon></button>
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="view">查看单位</el-dropdown-item>
                  <el-dropdown-item command="add" :disabled="!canManageOrganization">新增下级单位</el-dropdown-item>
                  <el-dropdown-item command="edit" :disabled="!canManageOrganization">编辑单位</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
              </span>
            </template>
          </el-tree>
        </div>
        <p class="org-panel__hint">选择单位后，右侧仅显示该单位用户</p>
      </aside>
      <main class="table-panel">
        <el-form v-if="canReadUsers" class="filter-bar" inline @submit.prevent="search">
          <el-form-item label="用户"><el-input v-model="filters.keyword" clearable placeholder="账号、姓名或联系电话" @keyup.enter="search" /></el-form-item>
          <el-form-item label="角色"><el-select v-model="filters.roleCode" clearable placeholder="全部角色"><el-option v-for="role in roles" :key="role.role_code" :label="role.name" :value="role.role_code" /></el-select></el-form-item>
          <el-form-item label="状态"><el-select v-model="filters.status" clearable placeholder="全部状态"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="resetFilters">重置</el-button></el-form-item>
        </el-form>
        <div class="table-toolbar"><span>{{ selectedOrg ? `当前单位：${selectedOrg.name}` : '当前范围：全部单位' }}</span><div class="unit-toolbar-actions"><el-button v-if="selectedOrg" @click="openOrg(selectedOrg, null, 'view')">查看单位</el-button><el-button @click="refreshAll">刷新</el-button></div></div>
        <el-empty v-if="!canReadUsers" description="选择左侧单位后点击查看单位，维护单位资料与联系人。当前账号没有用户列表读取权限。" />
        <div v-else class="table-scroll"><el-table v-loading="loading" :data="users" height="100%" empty-text="当前条件下暂无用户">
          <el-table-column prop="account" label="账号" min-width="130" /><el-table-column prop="name" label="姓名" min-width="100" />
          <el-table-column prop="role_name" label="角色" min-width="130" /><el-table-column prop="org_name" label="单位" min-width="140" />
          <el-table-column label="状态" width="82"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="最后登录" min-width="168"><template #default="{ row }">{{ row.last_login_at ? formatTime(row.last_login_at) : '从未登录' }}</template></el-table-column>
          <el-table-column label="操作" width="276" fixed="right"><template #default="{ row }">
            <el-button link type="primary" :disabled="!canOperate" @click="openUser('edit', row)">修改</el-button>
            <el-button link :disabled="!canOperate || isAdmin(row)" @click="openReset(row)">重置密码</el-button>
            <el-button link :disabled="!canOperate || isAdmin(row)" @click="toggleStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" :disabled="!canOperate || isAdmin(row)" @click="removeUser(row)">删除</el-button>
          </template></el-table-column>
        </el-table></div>
        <el-pagination v-if="canReadUsers" v-model:current-page="filters.page" v-model:page-size="filters.size" class="pagination" background layout="total, sizes, prev, pager, next" :total="total" :page-sizes="[10,20,50,100]" @current-change="loadUsers" @size-change="filters.page=1;loadUsers()" />
      </main>
    </section>

    <el-dialog v-model="userDialog.visible" :title="userDialog.mode === 'create' ? '新增用户' : `编辑资料 · ${userDialog.row?.name || ''}`" width="620px" destroy-on-close>
      <el-form label-position="top" class="dialog-grid">
        <el-form-item v-if="userDialog.mode === 'create'" label="登录账号" required><el-input v-model="userDialog.form.account" maxlength="64" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="userDialog.form.name" maxlength="64" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="userDialog.form.phone" maxlength="32" /></el-form-item>
        <el-form-item label="所属单位" required><el-select v-model="userDialog.form.org_id" filterable><el-option v-for="org in organizations" :key="org.org_id" :label="org.name" :value="org.org_id" /></el-select></el-form-item>
        <el-form-item label="角色" required><el-select v-model="userDialog.form.role_code" :disabled="isAdmin(userDialog.row)"><el-option v-for="role in activeRoles" :key="role.role_code" :label="role.name" :value="role.role_code" :disabled="role.role_code === 'ROLE-ADMIN' && !isAdmin(userDialog.row)" /></el-select></el-form-item>
        <el-form-item v-if="userDialog.mode === 'create'" label="临时密码" required><el-input v-model="userDialog.form.temporary_password" type="password" show-password autocomplete="new-password" maxlength="32" /><small>6–32 位，包含大小写字母、数字和特殊字符。</small></el-form-item>
      </el-form>
      <template #footer><el-button @click="userDialog.visible=false">取消</el-button><el-button type="primary" :loading="userDialog.busy" @click="saveUser">保存并立即生效</el-button></template>
    </el-dialog>
    <el-dialog v-model="resetDialog.visible" :title="`重置密码 · ${resetDialog.row?.account || ''}`" width="500px">
      <el-alert title="重置后该用户的所有会话立即失效，下次登录必须修改密码。" type="warning" :closable="false" show-icon />
      <el-form label-position="top"><el-form-item label="临时密码" required><el-input v-model="resetDialog.password" type="password" show-password maxlength="32" autocomplete="new-password" /></el-form-item></el-form>
      <template #footer><el-button @click="resetDialog.visible=false">取消</el-button><el-button type="primary" :loading="resetDialog.busy" @click="resetPassword">重置密码</el-button></template>
    </el-dialog>
    <OrganizationDialog v-model:visible="orgDialog.visible" :row="orgDialog.row" :parent="orgDialog.parent" :mode="orgDialog.mode" :organizations="organizations" :can-read-directory="canReadDirectory" :can-edit-directory="canEditDirectory" :can-edit-basic="canEditBasic" @saved="orgSaved" />
  </div>
</template>

<style scoped>
.unit-toolbar-actions{display:flex;flex-wrap:wrap;gap:8px;flex:none}.unit-toolbar-actions .el-button+.el-button{margin-left:0}.table-toolbar{flex-wrap:wrap;gap:12px}:deep(.el-table .cell){white-space:normal;overflow-wrap:anywhere;text-overflow:clip}
.user-management{display:grid;grid-template-columns:292px minmax(0,1fr);min-height:0;overflow:hidden}
.org-panel{display:flex;min-width:0;min-height:0;flex-direction:column;border-right:1px solid var(--admin-border);background:linear-gradient(180deg,#fbfdff 0%,#f6f9fd 100%)}
.org-panel__header{display:flex;min-height:76px;align-items:center;justify-content:space-between;gap:12px;padding:14px 16px;border-bottom:1px solid var(--admin-border);background:#fff}
.org-panel__heading{display:flex;min-width:0;align-items:center;gap:10px}.org-panel__heading>span:last-child{min-width:0}.org-panel__heading strong,.org-panel__heading small{display:block}.org-panel__heading strong{font-size:15px}.org-panel__heading small{margin-top:3px;color:var(--admin-muted);font-size:12px}
.org-panel__icon{display:grid;width:34px;height:34px;flex:none;place-items:center;border-radius:9px;color:var(--admin-secondary);background:var(--admin-primary-soft);font-size:18px}
.org-panel__body{min-height:0;flex:1;padding:10px 9px;overflow:auto}.org-tree{background:transparent}
:deep(.org-tree .el-tree-node__content){height:auto;min-height:40px;margin:2px 0;padding-right:5px;border-radius:7px;color:var(--admin-text);transition:background-color .16s ease,color .16s ease}
:deep(.org-tree .el-tree-node__content:hover){background:#eef4fc}
:deep(.org-tree .el-tree-node.is-current>.el-tree-node__content){color:var(--admin-secondary);background:var(--admin-primary-soft);box-shadow:inset 3px 0 0 var(--admin-secondary)}
.org-node{display:flex;min-width:0;flex:1;align-items:center;gap:6px}.org-node__label{min-width:0;flex:1;white-space:normal;overflow-wrap:anywhere;line-height:1.5}
.org-node__more{display:grid;width:28px;height:28px;flex:none;place-items:center;border:0;border-radius:6px;color:var(--admin-muted);background:transparent;cursor:pointer;opacity:0;transition:opacity .16s ease,background-color .16s ease,color .16s ease}
.org-node__more:hover,.org-node__more:focus-visible{color:var(--admin-secondary);background:#fff;opacity:1}
:deep(.org-tree .el-tree-node__content:hover) .org-node__more,:deep(.org-tree .el-tree-node.is-current>.el-tree-node__content) .org-node__more{opacity:1}
.org-panel__hint{margin:0;padding:11px 16px;border-top:1px solid var(--admin-border);color:var(--admin-muted);background:#fff;font-size:12px}
.table-toolbar{display:flex;align-items:center;justify-content:space-between}.table-panel{display:flex;min-width:0;min-height:0;flex-direction:column;padding:16px}.filter-bar{flex:none}.table-toolbar{padding:4px 0 12px;color:var(--admin-muted)}.pagination{justify-content:flex-end;margin-top:16px}.dialog-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 18px}.dialog-grid small{display:block;margin-top:5px;color:var(--admin-muted)}
@media(max-width:900px){.user-management{min-height:620px;grid-template-columns:1fr}.org-panel{max-height:320px;border-right:0;border-bottom:1px solid var(--admin-border)}.org-panel__header{min-height:68px}.dialog-grid{grid-template-columns:1fr}}
.user-management{border:1px solid var(--admin-border);border-radius:12px;background:var(--admin-card);box-shadow:var(--admin-shadow);isolation:isolate}
.org-panel{background:linear-gradient(rgba(238,242,246,.94),rgba(238,242,246,.94)),url('/assets/img/admin/aviation-ambient.webp') center/cover}.org-panel__header{background:rgba(255,255,255,.82);backdrop-filter:blur(10px)}
.org-panel__icon{color:var(--admin-primary);background:var(--admin-primary-soft)}
:deep(.org-tree .el-tree-node__content:hover){background:rgba(255,255,255,.7)}
:deep(.org-tree .el-tree-node.is-current>.el-tree-node__content){color:var(--admin-primary-strong);background:#fff;box-shadow:inset 3px 0 0 var(--admin-primary),0 5px 14px rgba(30,74,128,.08)}
.org-panel__hint{background:rgba(255,255,255,.82)}.table-panel{background:#fff}.table-toolbar{border-bottom:1px solid #edf2f7}
:deep(.org-tree .el-tree-node__content){height:auto;min-height:36px;padding-top:6px;padding-bottom:6px}
</style>
