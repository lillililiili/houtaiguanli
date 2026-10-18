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
const total = ref(0)
const detailDialog = reactive({ visible: false, loading: false, row: null })
// 系统自动高频记录（模拟观测、设备状态上报、自动核验等）默认不显示，打开开关后照常列出；不删除、不停写。
const filters = reactive({ range: [], account: '', module: '', action: '', result: '', includeAutomatic: false, page: 1, size: 20 })
const moduleLabels = {
  responsePlans: '规则管理',
  notificationSettings: '通知对象配置', authentication: '认证登录', users: '用户管理', roles: '角色管理', audit: '审计日志', devices: '设备管理',
  alarms: '告警事件', monitoring: '设备实时监测', commissioning: '设备接入调测', maintenance: '设备异常处理', statistics: '报表管理', profile: '个人资料',
  risk: '飞行风险', flights: '飞行任务', airspace: '空域规则', fusion: '融合感知', disposal: '处置授权',
  punishment: '处罚案件', evidence: '证据管理', airport: '机场基础数据', maps: '地图管理', interfaces: '接口配置', system: '系统'
}
const actionLabels = {
  device_maintenance_reported: '上报设备维护任务', device_maintenance_handled: '处理设备维护任务',
  device_maintenance_notice_resent: '补发设备维护通知', device_maintenance_start: '开始处理设备运维任务',
  device_maintenance_save_progress: '保存设备运维进展', device_maintenance_submit_verification: '提交设备运维恢复核验',
  device_maintenance_verify_recovery: '核验设备运维恢复情况', device_maintenance_complete: '完成设备运维任务',
  device_maintenance_resume: '继续处理设备运维任务', device_maintenance_link_commission: '关联设备运维调测任务',
  device_maintenance_workflow_rejected: '拒绝设备运维流程操作',
  response_plan_draft_created: '新建预案草稿', response_plan_draft_updated: '修改预案草稿', response_plan_published: '发布预案', response_plan_withdrawn: '停用预案', response_plan_bound: '关联空域预案', response_plan_unbound: '解除空域预案关联',
  organization_profile_created: '创建单位档案', organization_profile_updated: '更新单位档案', business_contact_created: '创建联系人', business_contact_updated: '更新联系人',
  plan_source_binding_created: '创建来源映射', plan_source_binding_updated: '更新来源映射', plan_subjects_associated: '关联任务主体', notification_setting_created: '创建通知配置', notification_setting_updated: '更新通知配置',
  login_success: '登录成功', login_fail: '登录失败', logout: '退出登录', password_changed: '修改密码',
  user_created: '创建用户', user_deleted: '删除用户', user_profile_updated: '更新用户资料', user_status_changed: '变更用户状态',
  user_password_reset: '重置用户密码', user_access_updated: '调整用户角色', organization_created: '创建单位', organization_updated: '更新单位',
  organization_status_changed: '变更单位状态', role_created: '创建角色', role_description_updated: '更新角色说明',
  role_permissions_updated: '更新角色权限', role_deleted: '删除角色', audit_export_requested: '导出审计日志',
  device_create: '登记设备', device_update: '修改设备', device_enable: '启用设备', device_disable: '停用设备', device_delete: '删除设备', device_restore: '恢复模拟设备',
  device_created: '登记设备（历史编码）', device_updated: '修改设备（历史编码）', device_enabled: '启用设备（历史编码）', device_disabled: '停用设备（历史编码）',
  mqtt_broker_create: '登记消息接入连接', mqtt_broker_update: '修改消息接入连接', mqtt_broker_enable: '启用消息接入连接',
  mqtt_broker_disable: '停用消息接入连接', commission_create: '创建调测任务', commission_cancel: '取消调测任务',
  commission_connect: '调测建立连接', commission_configuration: '保存调测连接参数', commission_start: '开始调测',
  commission_task_created: '创建调测任务（历史编码）', commission_task_cancelled: '取消调测任务（历史编码）',
  eo_track_requested: '下发光电跟踪', eo_track_ended: '停止光电跟踪', alarms_exported: '导出告警列表', risks_exported: '导出风险列表',
  stats_export_requested: '导出运行报表', map_package_uploaded: '上传离线地图包',
  map_package_activated: '启用离线地图', map_package_rolled_back: '回滚离线地图', map_package_deleted: '删除离线地图包',
  advisory_voice_recording_uploaded: '上传电话通知录音', advisory_voice_recording_activated: '选用电话通知录音',
  advisory_voice_recording_deactivated: '停止使用电话通知录音', advisory_voice_recording_deleted: '删除电话通知录音',
  device_maintenance_created: '自动生成设备异常处置单', device_maintenance_confirm_complete: '确认办结设备异常处置单',
  device_maintenance_auto_check: '自动核验设备恢复情况（系统自动）', device_maintenance_auto_recovered: '自动核验：设备已恢复（系统自动）',
  local_normalized_observation: '接收规范化模拟观测（系统自动）', local_qa_device_status: '接收模拟设备状态（系统自动）',
  auto_sms_delivered: '自动短信已送达', auto_voice_played: '自动电话已播放', countermeasure_4ch_requested: '下发四通道反制指令',
  device_planned_offline: '模拟设备计划下线'
}
const moduleOptions = Object.entries(moduleLabels).map(([value, label]) => ({ value, label }))
const actionOptions = Object.entries(actionLabels).map(([value, label]) => ({ value, label }))
const canExport = computed(() => auth.hasPermission('audit.op'))

function params(paged = true) {
  const from = filters.range?.[0]
  const to = filters.range?.[1]
  return {
    from: from ? new Date(from).getTime() : '', to: to ? new Date(to).getTime() : '',
    account: filters.account.trim(), module: filters.module, action: filters.action, result: filters.result,
    include_automatic: filters.includeAutomatic ? 'true' : '',
    ...(paged ? { page: filters.page, size: filters.size } : {})
  }
}
function moduleText(value) { return moduleLabels[value] || moduleLabels[{ risks: 'risk', alarm: 'alarms', device: 'devices', user: 'users', role: 'roles' }[value]] || value || '—' }
function actionText(value) {
  if (!value) return '—'
  if (actionLabels[value]) return actionLabels[value]
  const match = /^(GET|POST|PUT|PATCH|DELETE)\s+(\S+)/i.exec(value)
  if (!match) return /[\u4e00-\u9fff]/.test(value) ? value : '其他操作'
  const method = { GET: '查询', POST: '提交', PUT: '更新', PATCH: '更新', DELETE: '删除' }[match[1].toUpperCase()]
  return `${method}接口`
}
// 结果、对象类型优先用后端中文名；FAILURE 与历史 FAILED 都是失败，其他未知结果不冒充失败。
function resultText(row) { return row.result_name || ({ SUCCESS: '成功', FAILURE: '失败', FAILED: '失败', DENIED: '已拒绝' })[row.result] || (row.result ? '其他结果' : '—') }
function resultType(row) { return row.result === 'SUCCESS' ? 'success' : ['FAILURE', 'FAILED', 'DENIED'].includes(row.result) ? 'danger' : 'info' }
function objectTypeText(row) { return row.object_type_name || (row.object_type ? '其他对象' : '—') }
// 详情里保留原代码，便于排查；列表只显示中文。
function withCode(text, code) { return code && text !== code ? `${text}（原代码：${code}）` : text }
function roleText(row) { return row.role_name || ({ 'ROLE-ADMIN': '超级管理员', 'ROLE-BACKEND': '后台用户' }[row.role_code]) || row.role_code || '未记录' }
function ipText(value) { return value?.trim() || '未记录' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await systemApi.audits(params())
    rows.value = data.items || []
    total.value = data.total || 0
  } catch (e) { error.value = e.message || '审计日志加载失败。' }
  finally { loading.value = false }
}
function search() { filters.page = 1; load() }
function reset() { Object.assign(filters, { range: [], account: '', module: '', action: '', result: '', includeAutomatic: false, page: 1 }); load() }
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
        <el-form-item label="菜单"><el-select v-model="filters.module" clearable filterable placeholder="全部菜单"><el-option v-for="item in moduleOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="动作"><el-select v-model="filters.action" clearable filterable placeholder="全部动作"><el-option v-for="item in actionOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="结果"><el-select v-model="filters.result" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAILURE" /></el-select></el-form-item>
        <el-form-item><el-checkbox v-model="filters.includeAutomatic" @change="search">显示系统自动记录</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <div class="table-scroll"><el-table v-loading="loading" :data="rows" height="100%" empty-text="当前条件下暂无审计日志">
        <el-table-column label="时间" min-width="172"><template #default="{row}">{{ formatTime(row.occurred_at) }}</template></el-table-column>
        <el-table-column prop="account" label="账号" min-width="120" show-overflow-tooltip /><el-table-column label="角色" min-width="180" show-overflow-tooltip><template #default="{row}"><span :title="row.display_role_code || row.role_code">{{ roleText(row) }}</span></template></el-table-column>
        <el-table-column label="菜单" min-width="140"><template #default="{row}">{{ row.module_name || moduleText(row.menu_code || row.module_code) }}</template></el-table-column>
        <el-table-column label="动作" min-width="160" show-overflow-tooltip><template #default="{row}">{{ row.action_name || actionText(row.action) }}</template></el-table-column>
        <el-table-column label="结果" width="82"><template #default="{row}"><el-tag :type="resultType(row)">{{ resultText(row) }}</el-tag></template></el-table-column>
        <el-table-column label="IP" min-width="130"><template #default="{row}"><span :title="row.ip">{{ ipText(row.ip) }}</span></template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click="openDetail(row)">详情</el-button></template></el-table-column>
      </el-table></div>
      <div class="audit-footer"><span>默认按时间倒序，单次 CSV 导出上限 50,000 条。{{ filters.includeAutomatic ? '' : '已隐藏系统自动记录（模拟观测、设备状态上报、自动核验等）。' }}</span><el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" background layout="total, sizes, prev, pager, next" :page-sizes="[10,20,50,100]" :total="total" @current-change="load" @size-change="filters.page=1;load()" /></div>
    </section>

    <el-dialog v-model="detailDialog.visible" title="审计日志详情" width="min(760px, calc(100vw - 32px))" top="8vh">
      <div v-loading="detailDialog.loading" class="audit-detail-body">
        <template v-if="detailDialog.row">
          <el-descriptions class="audit-detail-metadata" :column="2" border>
            <el-descriptions-item label="时间">{{ formatTime(detailDialog.row.occurred_at) }}</el-descriptions-item><el-descriptions-item label="结果">{{ withCode(resultText(detailDialog.row), detailDialog.row.result) }}</el-descriptions-item>
            <el-descriptions-item label="账号">{{ detailDialog.row.account || '—' }}</el-descriptions-item><el-descriptions-item label="角色">{{ roleText(detailDialog.row) }}</el-descriptions-item>
            <el-descriptions-item label="菜单">{{ detailDialog.row.module_name || moduleText(detailDialog.row.menu_code || detailDialog.row.module_code) }}</el-descriptions-item><el-descriptions-item label="动作">{{ withCode(detailDialog.row.action_name || actionText(detailDialog.row.action), detailDialog.row.action) }}</el-descriptions-item>
            <el-descriptions-item label="对象类型">{{ withCode(objectTypeText(detailDialog.row), detailDialog.row.object_type) }}</el-descriptions-item><el-descriptions-item label="对象编号"><span class="audit-object-id">{{ detailDialog.row.object_id || '—' }}</span></el-descriptions-item>
            <el-descriptions-item label="IP">{{ ipText(detailDialog.row.ip) }}</el-descriptions-item><el-descriptions-item label="客户端">{{ detailDialog.row.user_agent || '—' }}</el-descriptions-item>
          </el-descriptions>
          <section class="audit-detail-section" aria-label="操作详情">
            <h3>操作详情</h3>
            <div class="audit-detail-text">{{ detailDialog.row.detail || '—' }}</div>
          </section>
        </template>
      </div>
      <template #footer><el-button @click="detailDialog.visible=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.audit-object-id { overflow-wrap: anywhere; }
.audit-card{display:flex;min-height:0;flex:1;flex-direction:column;padding:16px;border-radius:12px}.filter-bar{flex:none}.filter-bar .el-input,.filter-bar .el-select{width:170px}.audit-footer{display:flex;flex:none;align-items:center;justify-content:space-between;gap:20px;padding-top:16px;color:var(--admin-muted)}
.audit-detail-body { max-height: calc(84vh - 150px); overflow: auto; }
.audit-detail-metadata :deep(.el-descriptions__table) { width: 100%; table-layout: fixed; }
.audit-detail-metadata :deep(.el-descriptions__label) { width: 88px; white-space: nowrap; }
.audit-detail-metadata :deep(.el-descriptions__content) { overflow-wrap: anywhere; line-height: 1.65; }
.audit-detail-section { margin-top: 20px; }
.audit-detail-section h3 { margin: 0 0 10px; color: var(--admin-text-strong); font-size: 14px; font-weight: 650; }
.audit-detail-text { padding: 14px 16px; border: 1px solid var(--admin-border); border-radius: 8px; background: var(--admin-card-soft); color: var(--admin-text); line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
@media(max-width:900px){.audit-card{min-height:640px}.audit-footer{align-items:flex-start;flex-direction:column}.filter-bar .el-input,.filter-bar .el-select{width:220px}}
</style>
