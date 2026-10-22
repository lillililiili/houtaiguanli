<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmDeletion } from '@/utils/deleteConfirmation'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { unitAdministrationApi as api } from '@/api/unitAdministration'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'
import { roleDisplayName, userRoleDisplayName } from '@/utils/rolePresentation'

const auth = useAuthStore()
const loading = ref(false), error = ref(''), users = ref([]), roles = ref([]), catalog = ref(null), total = ref(0)
const filters = reactive({ keyword: '', roleCode: '', status: '', page: 1, size: 20 })
const dialog = reactive({ visible: false, busy: false, row: null, form: {} })
const reset = reactive({ visible: false, busy: false, row: null, password: '' })
const errors = reactive({})
const availableRoles = computed(() => roles.value.filter(row => row.enabled !== false))
const organizationName = computed(() => catalog.value?.org_name || auth.user?.org_name || '本单位')
let sequence = 0, alive = true
function protectedUser(row) { return row.user_id === auth.user?.user_id || row.user_id === catalog.value?.administrator_user_id }
async function loadUsers() {
  const current = ++sequence; loading.value = true; error.value = ''
  try {
    const result = await api.users({ ...filters, keyword: filters.keyword.trim() })
    if (!alive || current !== sequence) return
    users.value = result.items || []; total.value = result.total || 0
  } catch (e) { if (alive && current === sequence) error.value = e.message || '人员加载失败。' }
  finally { if (alive && current === sequence) loading.value = false }
}
async function loadAll() {
  loading.value = true; error.value = ''
  try {
    const [unit, roleRows] = await Promise.all([api.catalog(), api.roles()])
    if (!alive) return
    catalog.value = unit; roles.value = roleRows || []
    await loadUsers()
  } catch (e) { if (alive) { error.value = e.message || '单位管理加载失败。'; loading.value = false } }
}
function search() { filters.page = 1; loadUsers() }
function openUser(row = null) {
  if (row && protectedUser(row)) return
  Object.keys(errors).forEach(key => delete errors[key])
  Object.assign(dialog, { visible: true, row, form: { account: '', name: row?.name || '', phone: row?.phone || '', role_code: row?.role_code || '', temporary_password: '' } })
}
function passwordError(value, account) {
  if (value.length < 6 || value.length > 32 || !/[a-z]/.test(value) || !/[A-Z]/.test(value) || !/\d/.test(value) || !/[^\w]/.test(value)) return '临时密码须为 6–32 位，包含大小写字母、数字和特殊字符。'
  if (account && value.toLowerCase().includes(account.toLowerCase())) return '临时密码不能包含登录账号。'
  return ''
}
async function saveUser() {
  if (dialog.busy || (dialog.row && protectedUser(dialog.row))) return
  Object.keys(errors).forEach(key => delete errors[key])
  for (const [key, label] of Object.entries({ ...(!dialog.row ? { account: '登录账号' } : {}), name: '姓名', role_code: '角色' })) {
    if (!String(dialog.form[key] || '').trim()) errors[key] = `请填写${label}`
  }
  if (!dialog.row) {
    const invalid = passwordError(dialog.form.temporary_password, dialog.form.account.trim())
    if (invalid) errors.temporary_password = invalid
  }
  if (Object.keys(errors).length) return
  dialog.busy = true
  const body = { name: dialog.form.name.trim(), phone: dialog.form.phone.trim(), role_code: dialog.form.role_code, org_id: catalog.value.org_id, user_type: 'FRONTEND', data_scope: 'OWN_ORG' }
  try {
    if (dialog.row) await api.updateUser(dialog.row.user_id, { ...body, expected_version: dialog.row.version })
    else await api.createUser({ ...body, account: dialog.form.account.trim(), temporary_password: dialog.form.temporary_password })
    dialog.visible = false; ElMessage.success('人员资料已保存。'); await loadAll()
  } catch (e) { ElMessage.error(e.message || '人员保存失败。') }
  finally { dialog.busy = false }
}
async function toggleStatus(row) {
  if (protectedUser(row)) return
  const enable = row.status !== 'ACTIVE'
  try {
    await ElMessageBox.confirm(enable ? `启用账号“${row.account}”？` : `停用后“${row.account}”的现有会话将立即失效。`, enable ? '启用人员' : '停用人员', { type: 'warning' })
    await api.setUserStatus(row.user_id, { status: enable ? 'ACTIVE' : 'DISABLED', expected_version: row.version })
    ElMessage.success(enable ? '账号已启用。' : '账号已停用。'); await loadUsers()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '状态保存失败。') }
}
async function removeUser(row) {
  if (protectedUser(row)) return
  try {
    const record = await confirmDeletion(`确认删除“${row.account}”？历史业务记录保留。`, '删除人员')
    await api.deleteUser(row.user_id, row.version, record)
    ElMessage.success('人员已删除。'); await loadAll()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '删除失败。') }
}
async function resetPassword() {
  if (reset.busy || protectedUser(reset.row)) return
  const invalid = passwordError(reset.password, reset.row.account)
  if (invalid) return ElMessage.warning(invalid)
  reset.busy = true
  try {
    await api.resetPassword(reset.row.user_id, { temporary_password: reset.password, expected_version: reset.row.version })
    reset.visible = false; ElMessage.success('临时密码已设置，旧会话已撤销。'); await loadUsers()
  } catch (e) { ElMessage.error(e.message || '重置密码失败。') }
  finally { reset.busy = false }
}
onMounted(loadAll)
onBeforeUnmount(() => { alive = false; sequence++ })
</script>

<template>
  <div class="page-stack">
    <PageHeader title="本单位人员" :description="`${organizationName} · 人员固定属于本单位，数据范围为本单位。`"><el-button type="primary" :disabled="!catalog" @click="openUser()">新增人员</el-button></PageHeader>
    <ErrorAlert :message="error" @retry="loadAll" />
    <section class="content-card unit-users">
      <el-form inline class="filter-bar" @submit.prevent="search">
        <el-form-item label="人员"><el-input v-model="filters.keyword" placeholder="账号、姓名或联系电话" clearable /></el-form-item>
        <el-form-item label="角色"><el-select v-model="filters.roleCode" clearable placeholder="全部角色" @change="search"><el-option v-for="role in roles" :key="role.role_code" :label="roleDisplayName(role)" :value="role.role_code" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="filters.status" clearable placeholder="全部状态" @change="search"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="loadAll">刷新</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="users" empty-text="当前条件下暂无本单位人员">
        <el-table-column prop="account" label="账号" min-width="160" /><el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="role_name" label="业务角色" min-width="160"><template #default="{ row }">{{ userRoleDisplayName(row, roles) }}</template></el-table-column><el-table-column prop="phone" label="联系电话" min-width="130" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="最后登录" min-width="165"><template #default="{ row }">{{ row.last_login_at ? formatTime(row.last_login_at) : '从未登录' }}</template></el-table-column>
        <el-table-column label="操作" min-width="270"><template #default="{ row }">
          <el-tag v-if="protectedUser(row)" type="info">单位管理员 · 平台维护</el-tag>
          <template v-else>
            <el-button link type="primary" @click="openUser(row)">修改</el-button>
            <el-button link @click="Object.assign(reset, { visible: true, row, password: '' })">重置密码</el-button>
            <el-button link @click="toggleStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" @click="removeUser(row)">删除</el-button>
          </template>
        </template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" class="pagination" background layout="total, sizes, prev, pager, next" :total="total" :page-sizes="[10,20,50,100]" @current-change="loadUsers" @size-change="search" />
    </section>
    <el-dialog v-model="dialog.visible" :title="dialog.row ? '修改本单位人员' : '新增本单位人员'" width="min(600px, 94vw)" :close-on-click-modal="!dialog.busy" destroy-on-close>
      <el-form label-position="top" @submit.prevent="saveUser">
        <el-form-item v-if="!dialog.row" label="登录账号" required :error="errors.account"><el-input v-model="dialog.form.account" maxlength="64" /></el-form-item>
        <el-form-item label="姓名" required :error="errors.name"><el-input v-model="dialog.form.name" maxlength="64" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="dialog.form.phone" maxlength="32" /></el-form-item>
        <el-form-item label="本单位角色" required :error="errors.role_code"><el-select v-model="dialog.form.role_code" filterable><el-option v-for="role in availableRoles" :key="role.role_code" :label="roleDisplayName(role)" :value="role.role_code" /></el-select></el-form-item>
        <el-form-item v-if="!dialog.row" label="临时密码" required :error="errors.temporary_password"><el-input v-model="dialog.form.temporary_password" type="password" show-password autocomplete="new-password" maxlength="32" /></el-form-item>
        <el-alert :title="`${organizationName} · 前台用户 · 仅本单位数据`" type="info" :closable="false" />
      </el-form>
      <template #footer><el-button :disabled="dialog.busy" @click="dialog.visible = false">取消</el-button><el-button type="primary" :loading="dialog.busy" @click="saveUser">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="reset.visible" :title="`重置密码 · ${reset.row?.account || ''}`" width="min(500px, 94vw)">
      <el-alert title="旧会话立即失效，下次登录必须修改密码。" type="warning" :closable="false" />
      <el-form label-position="top"><el-form-item label="临时密码" required><el-input v-model="reset.password" type="password" show-password autocomplete="new-password" maxlength="32" /></el-form-item></el-form>
      <template #footer><el-button :disabled="reset.busy" @click="reset.visible = false">取消</el-button><el-button type="primary" :loading="reset.busy" @click="resetPassword">重置密码</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.unit-users{padding:20px}.unit-users .pagination{justify-content:flex-end;margin-top:20px}.el-dialog .el-select{width:100%}.el-dialog .el-alert{margin:12px 0}.unit-users :deep(.el-table .cell){overflow-wrap:anywhere;white-space:normal}
</style>
