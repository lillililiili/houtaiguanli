<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const error = ref('')
const query = ref('')
const roles = ref([])
const catalog = ref([])
const actionCatalog = ref([])
const selectedCode = ref('')
const activePermissionTab = ref('menu')
const detail = ref(null)
const permissions = ref([])
const actionDraft = reactive({})
const actionBaseline = ref('{}')
const createDialog = reactive({ visible: false, busy: false, form: { name: '', description: '' }, permissions: [] })
const protectedCodes = new Set(['users', 'roles', 'audit', 'countermeasure'])
const levels = [{ value: 'NONE', label: '无权限' }, { value: 'READ', label: '查看' }, { value: 'OP', label: '操作' }, { value: 'AUTH', label: '授权' }]
const actionLevels = [{ value: 'NONE', label: '无' }, { value: 'READ', label: '查看' }, { value: 'OP', label: '操作' }]
const menuLabels = {
  dashboard: '数据大屏', sensing: '感知监测', statistics: '统计分析', stats: '报表管理',
  flights: '飞行活动', legality: '合法性判定', airspace: '空域与航线', alarms: '异常告警',
  risk: '空间风险', punishment: '处置处罚', countermeasure: '反制授权', devices: '设备管理',
  monitor: '设备实时监测', monitoring: '设备实时监测', commission: '设备接入调测', commissioning: '设备接入调测',
  maps: '地图管理', interfaces: '接口管理', users: '用户管理', roles: '角色管理',
  archive: '审计日志', audit: '审计日志', evidence: '证据管理'
}
const moduleLabels = { devices: '设备管理', monitoring: '实时监测', commissioning: '接入调测', maps: '地图管理', users: '用户管理', roles: '角色管理', audit: '审计日志', alarms: '告警处置', flights: '飞行计划', fusion: '融合感知', airspace: '空域管理', countermeasure: '反制处置', evidence: '证据管理', punishment: '处罚案件' }
const actionLabels = { create: '新增', read: '查看', update: '修改', delete: '删除', enable: '启用', disable: '停用', export: '导出', operate: '操作', authorize: '授权', reset_password: '重置密码' }
const canOperate = computed(() => auth.hasPermission('roles.auth'))
const locked = computed(() => detail.value?.role_code === 'ROLE-ADMIN')
const filteredRoles = computed(() => {
  const text = query.value.trim().toLowerCase()
  return roles.value.filter(item => !text || [item.name, item.role_code, item.description].some(value => String(value || '').toLowerCase().includes(text)))
})
const menuPermissions = computed(() => permissions.value.filter(item => item.route_key))
const actionPermissionCount = computed(() => actionCatalog.value.reduce((count, group) => count + group.actions.length, 0))
const dirty = computed(() => {
  if (!detail.value) return false
  return JSON.stringify(permissions.value) !== JSON.stringify(detail.value.permissions || []) || JSON.stringify(actionDraft) !== actionBaseline.value
})

function menuLabel(item) {
  return menuLabels[item.permission_code] || menuLabels[item.route_key] || item.module_name || item.name || item.permission_code
}
function permissionLimit(row, asCreate = false) {
  if (!asCreate && locked.value) return '固定权限'
  return protectedCodes.has(row.permission_code) ? '仅超级管理员' : '可配置'
}
function permissionLimitType(row, asCreate = false) {
  const text = permissionLimit(row, asCreate)
  return text === '仅超级管理员' ? 'warning' : text === '固定权限' ? 'info' : ''
}
function moduleLabel(group) { return /[\u4e00-\u9fff]/.test(group.module_name || '') ? group.module_name : (moduleLabels[group.module_code] || group.module_code) }
function actionLabel(action) {
  if (/[\u4e00-\u9fff]/.test(action.name || '')) return action.name
  const suffix = action.permission_code?.split('.').pop()
  return actionLabels[suffix] || action.permission_code
}
function isPermissionLocked(row) { return locked.value || protectedCodes.has(row.permission_code) }
function isActionLocked(action) { return locked.value || action.permission_code === 'map:activate' || ['users', 'roles', 'audit', 'countermeasure'].includes(action.permission_code?.split(':')[0]) }
function setLevel(row, level) { row.level = level; if (level === 'NONE') row.menu_enabled = false }
function setMenu(row, enabled) { row.menu_enabled = enabled; if (enabled && row.level === 'NONE') row.level = 'READ' }

async function loadRoles() {
  roles.value = await systemApi.roles()
  if (!roles.value.some(item => item.role_code === selectedCode.value)) selectedCode.value = roles.value.find(item => item.role_code === 'ROLE-ADMIN')?.role_code || roles.value[0]?.role_code || ''
}
function resetDraft(data) {
  detail.value = data
  permissions.value = (data?.permissions || []).map(item => ({ ...item }))
  Object.keys(actionDraft).forEach(key => delete actionDraft[key])
  const granted = Object.fromEntries((data?.actions || []).map(item => [item.permission_code, item.level]))
  actionCatalog.value.forEach(group => group.actions.forEach(action => { actionDraft[action.permission_code] = granted[action.permission_code] || 'NONE' }))
  actionBaseline.value = JSON.stringify(actionDraft)
}
async function loadRole(code = selectedCode.value) {
  if (!code) return resetDraft(null)
  loading.value = true
  error.value = ''
  try { resetDraft(await systemApi.role(code)) }
  catch (e) { error.value = e.message || '角色详情加载失败。' }
  finally { loading.value = false }
}
async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [permissionRows, actions] = await Promise.all([systemApi.permissions(), systemApi.permissionActions()])
    catalog.value = permissionRows || []
    actionCatalog.value = Array.isArray(actions) ? actions : (actions?.items || [])
    await loadRoles()
    await loadRole()
  } catch (e) { error.value = e.message || '角色管理数据加载失败。' }
  finally { loading.value = false }
}
function discard() { resetDraft(detail.value); ElMessage.info('已放弃未保存的权限改动。') }
async function savePermissions() {
  if (!dirty.value || locked.value) return
  try {
    await ElMessageBox.confirm('保存后权限立即生效，该角色下所有用户的旧会话会被撤销。', `保存权限 · ${detail.value.name}`, { type: 'warning', confirmButtonText: '保存并立即生效' })
    const body = {
      expected_version: detail.value.version,
      permissions: permissions.value.map(item => ({ permission_code: item.permission_code, level: item.level, menu_enabled: item.menu_enabled })),
      actions: Object.entries(actionDraft).filter(([, level]) => level !== 'AUTH').map(([permission_code, level]) => ({ permission_code, level }))
    }
    const saved = await systemApi.updateRolePermissions(detail.value.role_code, body)
    resetDraft(saved)
    await loadRoles()
    ElMessage.success('角色权限已立即生效，相关旧会话已撤销。')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '权限保存失败。') }
}

function openCreate() {
  createDialog.form = { name: '', description: '' }
  createDialog.permissions = catalog.value.map(item => ({ ...item, level: 'NONE', menu_enabled: false }))
  createDialog.visible = true
}
async function createRole() {
  if (!createDialog.form.name.trim()) return ElMessage.warning('角色名称不能为空。')
  createDialog.busy = true
  try {
    const saved = await systemApi.createRole({
      name: createDialog.form.name.trim(), description: createDialog.form.description.trim(), reason: '超级管理员直接创建角色',
      permissions: createDialog.permissions.map(item => ({ permission_code: item.permission_code, level: item.level, menu_enabled: item.menu_enabled }))
    })
    createDialog.visible = false
    await loadRoles()
    selectedCode.value = saved.role_code
    await loadRole(saved.role_code)
    ElMessage.success('角色及初始权限已创建并立即生效。')
  } catch (e) { ElMessage.error(e.message || '角色创建失败。') }
  finally { createDialog.busy = false }
}
async function editDescription() {
  try {
    const { value } = await ElMessageBox.prompt('请输入角色说明。', `编辑角色说明 · ${detail.value.name}`, { inputValue: detail.value.description || '', inputType: 'textarea' })
    const saved = await systemApi.updateRole(detail.value.role_code, { description: value, expected_version: detail.value.version })
    resetDraft(saved)
    await loadRoles()
    ElMessage.success('角色说明已保存。')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '保存失败。') }
}
async function removeRole() {
  if (detail.value.user_count) return ElMessage.warning('请先调整仍在使用该角色的用户。')
  try {
    const { value } = await ElMessageBox.prompt('删除会立即生效且不可撤销，请填写原因。', `删除角色 · ${detail.value.name}`, { inputType: 'textarea', inputPlaceholder: '删除原因', inputValidator: value => !!value.trim() || '删除原因为必填项', type: 'warning' })
    await systemApi.deleteRole(detail.value.role_code, detail.value.version, value.trim())
    selectedCode.value = 'ROLE-ADMIN'
    await loadRoles(); await loadRole()
    ElMessage.success('角色已删除。')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '删除失败。') }
}

watch(selectedCode, code => { if (code && detail.value?.role_code !== code) loadRole(code) })
onMounted(loadAll)
</script>

<template>
  <div class="page-stack page-stack--viewport">
    <PageHeader title="角色管理" description="菜单访问和业务动作分层授权；完整权限目录仍覆盖业务前台。">
      <el-button type="primary" :disabled="!canOperate" @click="openCreate">新增角色</el-button>
    </PageHeader>
    <ErrorAlert :message="error" @retry="loadAll" />
    <section v-loading="loading" class="content-card roles-layout viewport-fill">
      <aside class="role-sidebar">
        <header class="role-sidebar__header">
          <div class="role-sidebar__title">
            <div><strong>角色列表</strong><small>选择角色后配置权限</small></div>
            <span class="role-count">{{ roles.length }}</span>
          </div>
          <el-input v-model="query" clearable aria-label="搜索角色" placeholder="搜索名称、编码或说明" />
        </header>
        <nav class="role-list" aria-label="角色列表">
          <button
            v-for="role in filteredRoles"
            :key="role.role_code"
            type="button"
            :class="['role-item', { active: selectedCode === role.role_code }]"
            :aria-current="selectedCode === role.role_code ? 'true' : undefined"
            @click="selectedCode = role.role_code"
          >
            <span class="role-item__title"><strong>{{ role.name }}</strong><span :class="['role-type', { 'is-custom': !role.builtin }]">{{ role.builtin ? '内置' : '自定义' }}</span></span>
            <code>{{ role.role_code }}</code>
            <small>{{ role.user_count }} 名用户</small>
          </button>
          <el-empty v-if="!filteredRoles.length" :image-size="60" description="未找到匹配角色" />
        </nav>
      </aside>
      <main v-if="detail" class="role-detail">
        <header class="role-summary">
          <div><h2>{{ detail.name }} <el-tag v-if="detail.builtin" size="small">内置角色</el-tag></h2><p>{{ detail.description || '暂无角色说明' }}</p></div>
          <div v-if="!detail.builtin" class="role-summary__actions"><el-button :disabled="!canOperate" @click="editDescription">编辑说明</el-button><el-button type="danger" plain :disabled="!canOperate || detail.user_count>0" @click="removeRole">删除角色</el-button></div>
        </header>
        <el-tabs v-model="activePermissionTab" class="permission-tabs">
          <el-tab-pane name="menu">
            <template #label><span class="permission-tab-label"><span>菜单权限</span><span class="permission-tab-count">{{ menuPermissions.length }}</span></span></template>
            <div class="permission-intro"><div><h3>菜单权限</h3><p>配置角色可进入的菜单，以及对应模块的权限等级。</p></div></div>
            <div class="permission-matrix" role="table" aria-label="菜单权限">
              <div class="permission-matrix__head" role="row">
                <span role="columnheader">菜单入口</span>
                <span role="columnheader">权限等级</span>
                <span role="columnheader">限制</span>
              </div>
              <div v-if="!menuPermissions.length" class="permission-matrix__empty">暂无菜单权限目录</div>
              <div v-for="row in menuPermissions" :key="row.permission_code" class="permission-matrix__row" role="row">
                <div class="permission-matrix__entry" role="cell">
                  <el-checkbox :model-value="row.menu_enabled" :disabled="isPermissionLocked(row)" @change="value=>setMenu(row,value)">
                    <span class="permission-matrix__meta"><strong>{{ menuLabel(row) }}</strong><small class="code-note">{{ row.permission_code }}</small></span>
                  </el-checkbox>
                </div>
                <div class="permission-matrix__level" role="cell">
                  <span v-if="locked">全部</span>
                  <el-select v-else :model-value="row.level" :disabled="isPermissionLocked(row)" @change="value=>setLevel(row,value)">
                    <el-option v-for="level in levels" :key="level.value" :label="level.label" :value="level.value" />
                  </el-select>
                </div>
                <div class="permission-matrix__limit" role="cell">
                  <el-tag size="small" effect="plain" :type="permissionLimitType(row)">{{ permissionLimit(row) }}</el-tag>
                </div>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane name="action">
            <template #label><span class="permission-tab-label"><span>动作权限</span><span class="permission-tab-count">{{ actionPermissionCount }}</span></span></template>
            <div class="permission-intro"><div><h3>动作权限</h3><p>配置进入页面后可执行的具体业务操作。</p></div></div>
            <div v-if="actionCatalog.length" class="action-grid"><section v-for="group in actionCatalog" :key="group.module_code" class="action-group"><h4>{{ moduleLabel(group) }}</h4>
              <div v-for="action in group.actions" :key="action.permission_code" class="action-row"><span :title="action.permission_code">{{ actionLabel(action) }}</span><el-select v-model="actionDraft[action.permission_code]" :disabled="isActionLocked(action) || actionDraft[action.permission_code]==='AUTH'" size="small"><el-option v-for="level in actionLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></div>
            </section></div><el-empty v-else description="当前没有可配置的动作权限" />
          </el-tab-pane>
        </el-tabs>
        <footer class="save-bar"><span role="status" aria-live="polite">{{ dirty ? '有尚未保存的权限改动' : '当前显示已生效权限' }}</span><div class="save-actions"><el-button :disabled="!dirty" @click="discard">放弃改动</el-button><el-button type="primary" :disabled="!canOperate || !dirty || locked" @click="savePermissions">保存并立即生效</el-button></div></footer>
      </main><el-empty v-else description="请选择角色" />
    </section>

    <el-dialog v-model="createDialog.visible" title="新增自定义角色" width="860px" destroy-on-close>
      <el-form label-position="top" class="role-fields"><el-form-item label="角色名称" required><el-input v-model="createDialog.form.name" /></el-form-item><el-form-item label="角色说明"><el-input v-model="createDialog.form.description" /></el-form-item></el-form>
      <div class="permission-matrix is-dialog" role="table" aria-label="初始菜单权限">
        <div class="permission-matrix__head" role="row">
          <span role="columnheader">菜单入口</span>
          <span role="columnheader">权限等级</span>
          <span role="columnheader">限制</span>
        </div>
        <div v-for="row in createDialog.permissions.filter(item=>item.route_key)" :key="row.permission_code" class="permission-matrix__row" role="row">
          <div class="permission-matrix__entry" role="cell">
            <el-checkbox v-model="row.menu_enabled" :disabled="protectedCodes.has(row.permission_code)" @change="value=>setMenu(row,value)">
              <span class="permission-matrix__meta"><strong>{{ menuLabel(row) }}</strong><small class="code-note">{{ row.permission_code }}</small></span>
            </el-checkbox>
          </div>
          <div class="permission-matrix__level" role="cell">
            <el-select v-model="row.level" :disabled="protectedCodes.has(row.permission_code)" @change="value=>setLevel(row,value)">
              <el-option v-for="level in levels" :key="level.value" :label="level.label" :value="level.value" />
            </el-select>
          </div>
          <div class="permission-matrix__limit" role="cell">
            <el-tag size="small" effect="plain" :type="permissionLimitType(row, true)">{{ permissionLimit(row, true) }}</el-tag>
          </div>
        </div>
      </div>
      <template #footer><el-button @click="createDialog.visible=false">取消</el-button><el-button type="primary" :loading="createDialog.busy" @click="createRole">创建并立即生效</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.roles-layout{display:grid;grid-template-columns:minmax(248px,276px) minmax(0,1fr);grid-template-rows:minmax(0,1fr);min-height:0;border:1px solid var(--admin-border);border-radius:10px;overflow:hidden;background:var(--admin-card);box-shadow:var(--admin-shadow)}
.role-sidebar{display:flex;min-width:0;min-height:0;flex-direction:column;border-right:1px solid var(--admin-border);background:#f8fafc}.role-sidebar__header{flex:none;padding:16px;border-bottom:1px solid var(--admin-border);background:var(--admin-card)}.role-sidebar__title{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:12px}.role-sidebar__title strong,.role-sidebar__title small{display:block}.role-sidebar__title strong{color:var(--admin-text);font-size:15px}.role-sidebar__title small{margin-top:4px;color:var(--admin-muted);font-size:12px}.role-count{display:grid;min-width:28px;height:24px;padding:0 8px;place-items:center;border-radius:12px;color:var(--admin-primary);background:var(--admin-primary-soft);font:700 12px/1 Consolas,monospace}
.role-list{display:flex;min-height:0;flex:1;flex-direction:column;gap:8px;padding:10px;overflow:auto}.role-item{position:relative;display:grid;width:100%;min-height:82px;gap:6px;padding:11px 12px 11px 15px;border:1px solid transparent;border-radius:8px;color:var(--admin-text);background:transparent;text-align:left;cursor:pointer;transition:border-color .16s ease,background-color .16s ease,box-shadow .16s ease}.role-item::before{position:absolute;top:10px;bottom:10px;left:0;width:3px;border-radius:0 3px 3px 0;background:transparent;content:""}.role-item:hover{border-color:#bfdbfe;background:var(--admin-card);box-shadow:0 4px 12px rgba(30,64,175,.06)}.role-item:active{background:#e7f0ff}.role-item.active{border-color:#93c5fd;background:#eff6ff;box-shadow:0 5px 14px rgba(30,64,175,.09)}.role-item.active::before{background:var(--admin-secondary)}.role-item__title{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:8px}.role-item__title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}.role-item code{overflow-wrap:anywhere;color:#42526b;font:12px/1.3 Consolas,"SFMono-Regular",monospace}.role-item small{color:var(--admin-muted);font-size:12px}.role-type{flex:none;padding:2px 6px;border:1px solid #bfdbfe;border-radius:4px;color:var(--admin-primary);background:var(--admin-primary-soft);font-size:11px;line-height:1.25}.role-type.is-custom{border-color:var(--admin-border);color:var(--admin-muted);background:var(--admin-card)}
.role-detail{min-width:0;min-height:0;padding:20px;overflow:auto}.role-summary,.save-bar,.permission-intro{display:flex;align-items:center;justify-content:space-between;gap:16px}.role-summary{align-items:flex-start;padding-bottom:18px;border-bottom:1px solid var(--admin-border)}.role-summary h2,.role-summary p,.permission-intro h3,.permission-intro p{margin:0}.role-summary h2{color:var(--admin-text);font-size:20px;line-height:1.4}.role-summary p{margin-top:6px;color:var(--admin-muted);line-height:1.6}.role-summary__actions,.save-actions{display:flex;flex:none;align-items:center;gap:8px}
.permission-tabs{margin-top:4px}.permission-tabs :deep(.el-tabs__header){margin:0 0 16px}.permission-tabs :deep(.el-tabs__item){height:48px;padding:0 22px;color:var(--admin-muted);font-weight:600}.permission-tabs :deep(.el-tabs__item.is-active){color:var(--admin-primary)}.permission-tabs :deep(.el-tabs__active-bar){height:3px;border-radius:3px 3px 0 0;background:var(--admin-secondary)}.permission-tab-label{display:inline-flex;align-items:center;gap:8px}.permission-tab-count{display:grid;min-width:22px;height:20px;padding:0 6px;place-items:center;border-radius:10px;color:#526176;background:#edf2f7;font:700 11px/1 Consolas,monospace}.permission-tabs :deep(.el-tabs__item.is-active) .permission-tab-count{color:var(--admin-primary);background:var(--admin-primary-soft)}.permission-intro{align-items:flex-end;margin-bottom:12px}.permission-intro h3{color:var(--admin-text);font-size:16px}.permission-intro p{margin-top:5px;color:var(--admin-muted);font-size:12px;line-height:1.6}
.permission-matrix{overflow:hidden;border:1px solid var(--admin-border);border-radius:8px;background:var(--admin-card)}.permission-matrix.is-dialog{max-height:360px;overflow:auto}.permission-matrix.is-dialog .permission-matrix__head{position:sticky;top:0;z-index:1}
.permission-matrix__head,.permission-matrix__row{display:grid;grid-template-columns:minmax(200px,280px) 188px 132px minmax(0,1fr);align-items:center;column-gap:8px;padding:0 16px}
.permission-matrix__head{min-height:40px;border-bottom:1px solid var(--admin-border);color:#2d3f57;background:linear-gradient(180deg,#f6f9fc,#eef3f8);font-size:13px;font-weight:750}
.permission-matrix__row{min-height:58px;border-bottom:1px solid #e9eef5}.permission-matrix__row:last-child{border-bottom:0}.permission-matrix__row:hover{background:#f0f7ff}
.permission-matrix__entry{min-width:0}.permission-matrix__entry :deep(.el-checkbox){display:flex;align-items:flex-start;height:auto;white-space:normal}.permission-matrix__entry :deep(.el-checkbox__label){padding-left:8px;line-height:1.35}
.permission-matrix__meta{display:flex;flex-direction:column;gap:2px}.permission-matrix__meta strong{color:var(--admin-text);font-size:14px;font-weight:650}
.permission-matrix__level .el-select{width:100%}.permission-matrix__limit{justify-self:start}.permission-matrix__empty{padding:36px 16px;color:var(--admin-muted);text-align:center}
.code-note{display:block;margin-top:0;color:var(--admin-muted);font-family:ui-monospace,monospace;font-size:11px}.action-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}.action-group{padding:14px;border:1px solid var(--admin-border);border-radius:8px;background:#fbfcfe}.action-group h4{margin:0 0 8px;padding-bottom:10px;border-bottom:1px solid var(--admin-border);color:var(--admin-text)}.action-row{display:flex;min-height:40px;align-items:center;justify-content:space-between;gap:10px;padding:4px 0}.action-row>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.action-row .el-select{width:104px}.save-bar{position:sticky;z-index:2;bottom:-20px;margin:20px -20px -20px;padding:14px 20px;border-top:1px solid var(--admin-border);background:rgba(255,255,255,.96);box-shadow:0 -6px 16px rgba(23,32,51,.04)}.save-bar>span{margin-right:auto;color:var(--admin-muted)}.role-fields{display:grid;grid-template-columns:1fr 1fr;gap:18px}
@media(max-width:1100px){.roles-layout{grid-template-columns:1fr;grid-template-rows:auto minmax(0,1fr)}.role-sidebar{border-right:0;border-bottom:1px solid var(--admin-border)}.role-list{max-height:210px}.role-summary,.save-bar{align-items:flex-start;flex-direction:column}.save-actions{width:100%;justify-content:flex-end}.role-fields{grid-template-columns:1fr}}
@media(max-width:900px){.roles-layout{min-height:680px;grid-template-rows:auto auto}.role-list{max-height:272px}}
@media(max-width:560px){.role-detail{padding:16px}.role-summary__actions,.save-actions{width:100%;flex-wrap:wrap}.permission-tabs :deep(.el-tabs__item){padding:0 14px}.action-grid{grid-template-columns:1fr}.save-bar{bottom:-16px;margin:16px -16px -16px;padding:12px 16px}.permission-matrix__head{display:none}.permission-matrix__head,.permission-matrix__row{grid-template-columns:1fr;justify-items:start;gap:8px;padding:12px 14px}.permission-matrix__level{width:100%}}
.roles-layout{border-radius:12px;box-shadow:var(--admin-shadow)}
.role-sidebar{background:linear-gradient(rgba(238,242,246,.94),rgba(238,242,246,.94)),url('/assets/img/admin/aviation-ambient.webp') center/cover}.role-sidebar__header{background:rgba(255,255,255,.84);backdrop-filter:blur(10px)}
.role-item{border-radius:10px}.role-item:hover{border-color:#d3e3f5;background:rgba(255,255,255,.78);box-shadow:var(--admin-shadow-soft)}.role-item.active{border-color:#c5dcfa;background:#fff;box-shadow:0 7px 18px rgba(30,74,128,.1)}.role-item.active::before{background:var(--admin-primary)}
.role-type{border-color:#c6defd}.permission-tabs :deep(.el-tabs__active-bar){background:linear-gradient(90deg,var(--admin-primary),var(--admin-secondary))}.action-group{border-radius:10px;background:var(--admin-card-soft)}.save-bar{background:rgba(255,255,255,.92);backdrop-filter:blur(12px)}
</style>
