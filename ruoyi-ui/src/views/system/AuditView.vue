<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'

const auth = useAuthStore()
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const rows = ref([])
const roles = ref([])
const total = ref(0)
const detailDialog = reactive({ visible: false, loading: false, row: null })
const filters = reactive({ range: [], account: '', module: '', action: '', result: '', page: 1, size: 20 })
const moduleLabels = {
  organizations: '单位档案', notificationSettings: '通知对象配置', authentication: '认证登录', users: '用户管理', roles: '角色管理', audit: '审计日志', devices: '设备管理',
  mqtt: '设备接入', alarms: '告警事件', monitoring: '设备监测', commissioning: '设备调测', statistics: '运行统计',
  risk: '飞行风险', flights: '飞行计划', airspace: '空域规则', fusion: '融合感知', disposal: '处置授权',
  punishment: '处罚案件', evidence: '证据管理', airport: '机场基础数据', maps: '地图管理', system: '系统'
}
const actionLabels = {
  organization_profile_created: '创建单位档案', organization_profile_updated: '更新单位档案', business_contact_created: '创建联系人', business_contact_updated: '更新联系人',
  plan_source_binding_created: '创建来源映射', plan_source_binding_updated: '更新来源映射', plan_subjects_associated: '关联计划主体', notification_setting_created: '创建通知配置', notification_setting_updated: '更新通知配置',
  login_success: '登录成功', login_fail: '登录失败', logout: '退出登录', password_changed: '修改密码',
  user_created: '创建用户', user_deleted: '删除用户', user_profile_updated: '更新用户资料', user_status_changed: '变更用户状态',
  user_password_reset: '重置用户密码', user_access_updated: '调整用户角色', organization_created: '创建单位', organization_updated: '更新单位',
  organization_status_changed: '变更单位状态', role_created: '创建角色', role_description_updated: '更新角色说明',
  role_permissions_updated: '更新角色权限', role_deleted: '删除角色', audit_export_requested: '导出审计日志',
  device_created: '登记设备', device_updated: '修改设备', device_enabled: '启用设备', device_disabled: '停用设备',
  mqtt_broker_create: '登记消息接入连接', mqtt_broker_update: '修改消息接入连接', mqtt_broker_enable: '启用消息接入连接',
  mqtt_broker_disable: '停用消息接入连接', commission_task_created: '创建调测任务', commission_task_cancelled: '取消调测任务',
  eo_track_requested: '下发光电跟踪', eo_track_ended: '停止光电跟踪', alarms_exported: '导出告警列表', risks_exported: '导出风险列表',
  stats_export_requested: '导出运行报表', map_package_uploaded: '上传离线地图包',
  map_package_activated: '启用离线地图', map_package_rolled_back: '回滚离线地图', map_package_deleted: '删除离线地图包'
}
const moduleOptions = Object.entries(moduleLabels).map(([value, label]) => ({ value, label }))
const actionOptions = Object.entries(actionLabels).map(([value, label]) => ({ value, label }))
const canExport = computed(() => auth.hasPermission('audit.op'))
const canReadRoles = computed(() => auth.hasPermission('roles.read'))
const roleNames = computed(() => Object.fromEntries(roles.value.map(item => [item.role_code, item.name])))

function params(paged = true) {
  const from = filters.range?.[0]
  const to = filters.range?.[1]
  return {
    from: from ? new Date(from).getTime() : '', to: to ? new Date(to).getTime() : '',
    account: filters.account.trim(), module: filters.module, action: filters.action, result: filters.result,
    ...(paged ? { page: filters.page, size: filters.size } : {})
  }
}
function moduleText(value) { return moduleLabels[value] || moduleLabels[{ risks: 'risk', alarm: 'alarms', device: 'devices', user: 'users', role: 'roles' }[value]] || value || '—' }
function actionText(value) {
  if (!value) return '—'
  if (actionLabels[value]) return actionLabels[value]
  const match = /^(GET|POST|PUT|PATCH|DELETE)\s+(\S+)/i.exec(value)
  if (!match) return /[\u4e00-\u9fff]/.test(value) ? value : '未配置名称的操作'
  const method = { GET: '查询', POST: '提交', PUT: '更新', PATCH: '更新', DELETE: '删除' }[match[1].toUpperCase()]
  return `${method}接口`
}
function roleText(value) { return roleNames.value[value] || (value === 'ROLE-ADMIN' ? '超级管理员' : value ? '自定义角色' : '—') }
function ipText(value) { return ['127.0.0.1', '::1', '0:0:0:0:0:0:0:1', 'localhost'].includes(value) ? '本机' : (value || '—') }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, roleRows] = await Promise.all([
      systemApi.audits(params()),
      roles.value.length || !canReadRoles.value ? Promise.resolve(roles.value) : systemApi.roles().catch(() => [])
    ])
    rows.value = data.items || []
    total.value = data.total || 0
    if (!roles.value.length) roles.value = roleRows || []
  } catch (e) { error.value = e.message || '审计日志加载失败。' }
  finally { loading.value = false }
}
function search() { filters.page = 1; load() }
function reset() { Object.assign(filters, { range: [], account: '', module: '', action: '', result: '', page: 1 }); load() }
async function openDetail(row) {
  Object.assign(detailDialog, { visible: true, loading: true, row })
  try { detailDialog.row = await systemApi.audit(row.audit_id) }
  catch (e) { ElMessage.error(e.message || '审计详情加载失败。') }
  finally { detailDialog.loading = false }
}
async function exportCsv() {
  exporting.value = true
  try { await systemApi.auditCsv(params(false)); ElMessage.success('审计日志已导出。') }
  catch (e) { ElMessage.error(e.message || '导出失败。') }
  finally { exporting.value = false }
}

onMounted(load)
</script>

<template>
  <div class="page-stack page-stack--viewport">
    <PageHeader title="审计日志" description="日志只读且不可修改或删除；CSV 导出沿用当前筛选条件。">
      <el-button type="primary" :loading="exporting" :disabled="!canExport" @click="exportCsv">导出 CSV</el-button>
    </PageHeader>
    <ErrorAlert :message="error" @retry="load" />
    <section class="content-card audit-card viewport-fill">
      <el-form class="filter-bar" inline @submit.prevent="search">
        <el-form-item label="时间"><el-date-picker v-model="filters.range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" value-format="x" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="filters.account" clearable placeholder="登录账号" @keyup.enter="search" /></el-form-item>
        <el-form-item label="模块"><el-select v-model="filters.module" clearable filterable placeholder="全部模块"><el-option v-for="item in moduleOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="动作"><el-select v-model="filters.action" clearable filterable placeholder="全部动作"><el-option v-for="item in actionOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="结果"><el-select v-model="filters.result" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILURE" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <div class="table-scroll"><el-table v-loading="loading" :data="rows" height="100%" empty-text="当前条件下暂无审计日志">
        <el-table-column label="时间" min-width="172"><template #default="{row}">{{ formatTime(row.occurred_at) }}</template></el-table-column>
        <el-table-column prop="account" label="账号" min-width="120" show-overflow-tooltip /><el-table-column label="角色" min-width="120"><template #default="{row}"><span :title="row.role_code">{{ roleText(row.role_code) }}</span></template></el-table-column>
        <el-table-column label="模块" min-width="120"><template #default="{row}">{{ moduleText(row.module_code) }}</template></el-table-column>
        <el-table-column label="动作" min-width="160" show-overflow-tooltip><template #default="{row}">{{ actionText(row.action) }}</template></el-table-column>
        <el-table-column label="结果" width="82"><template #default="{row}"><el-tag :type="row.result==='SUCCESS'?'success':'danger'">{{ row.result==='SUCCESS'?'成功':'失败' }}</el-tag></template></el-table-column>
        <el-table-column label="IP" min-width="130"><template #default="{row}"><span :title="row.ip">{{ ipText(row.ip) }}</span></template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click="openDetail(row)">详情</el-button></template></el-table-column>
      </el-table></div>
      <div class="audit-footer"><span>默认按时间倒序，单次 CSV 导出上限 50,000 条。</span><el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" background layout="total, sizes, prev, pager, next" :page-sizes="[10,20,50,100]" :total="total" @current-change="load" @size-change="filters.page=1;load()" /></div>
    </section>

    <el-dialog v-model="detailDialog.visible" title="审计日志详情" width="720px">
      <div v-loading="detailDialog.loading"><el-descriptions v-if="detailDialog.row" :column="2" border>
        <el-descriptions-item label="时间">{{ formatTime(detailDialog.row.occurred_at) }}</el-descriptions-item><el-descriptions-item label="结果">{{ detailDialog.row.result==='SUCCESS'?'成功':'失败' }}</el-descriptions-item>
        <el-descriptions-item label="账号">{{ detailDialog.row.account || '—' }}</el-descriptions-item><el-descriptions-item label="角色">{{ roleText(detailDialog.row.role_code) }}</el-descriptions-item>
        <el-descriptions-item label="模块">{{ moduleText(detailDialog.row.module_code) }}</el-descriptions-item><el-descriptions-item label="动作">{{ actionText(detailDialog.row.action) }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ ipText(detailDialog.row.ip) }}</el-descriptions-item><el-descriptions-item label="客户端">{{ detailDialog.row.user_agent || '—' }}</el-descriptions-item>
        <el-descriptions-item label="详情" :span="2"><span class="detail-text">{{ detailDialog.row.detail || '—' }}</span></el-descriptions-item>
      </el-descriptions></div>
      <template #footer><el-button @click="detailDialog.visible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.audit-card{display:flex;min-height:0;flex:1;flex-direction:column;padding:16px;border-radius:12px}.filter-bar{flex:none}.filter-bar .el-input,.filter-bar .el-select{width:170px}.audit-footer{display:flex;flex:none;align-items:center;justify-content:space-between;gap:20px;padding-top:16px;color:var(--admin-muted)}.detail-text{padding:12px;border:1px solid var(--admin-border);border-radius:8px;background:var(--admin-card-soft);white-space:pre-wrap;word-break:break-word}
@media(max-width:900px){.audit-card{min-height:640px}.audit-footer{align-items:flex-start;flex-direction:column}.filter-bar .el-input,.filter-bar .el-select{width:220px}}
</style>
