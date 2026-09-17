<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DirectoryRecordEditor from './organization/DirectoryRecordEditor.vue'
import PlanAssociationPanel from './organization/PlanAssociationPanel.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { useAuthStore } from '@/stores/auth'
import { contactRoles, labelOf, organizationTypes } from '@/utils/directoryConfiguration'
import { formatTime } from '@/utils/format'

const auth = useAuthStore()
const canEdit = computed(() => auth.hasPermission('organizations.auth'))
const keyword = ref('')
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const detailError = ref('')
const rows = ref([])
const profile = ref(null)
const selectedId = ref('')
const page = ref(1)
const total = ref(0)
const activeTab = ref('profile')
const contacts = reactive({ items: [], page: 1, total: 0, keyword: '', loading: false, error: '' })
const bindings = reactive({ items: [], page: 1, total: 0, keyword: '', loading: false, error: '' })
const editor = reactive({ visible: false, kind: 'profile', row: null })
let listSequence = 0
let detailSequence = 0
let contactSequence = 0
let bindingSequence = 0
async function loadList() {
  const current = ++listSequence
  loading.value = true; error.value = ''
  try {
    const result = await directoryApi.profiles({ keyword: keyword.value.trim(), page: page.value, size: 20 })
    if (current !== listSequence) return
    rows.value = result.items || []; total.value = result.total || 0
    const selected = rows.value.find(item => item.org_id === selectedId.value) || rows.value[0]
    await selectProfile(selected)
  } catch (e) { if (current === listSequence) { rows.value = []; error.value = e.message || '单位档案加载失败。' } }
  finally { if (current === listSequence) loading.value = false }
}
async function selectProfile(row) {
  const current = ++detailSequence
  contactSequence++; bindingSequence++
  selectedId.value = row?.org_id || ''; profile.value = null; detailError.value = ''; detailLoading.value = false
  contacts.items = []; contacts.total = 0; contacts.page = 1; contacts.error = ''; contacts.loading = false
  bindings.items = []; bindings.total = 0; bindings.page = 1; bindings.error = ''; bindings.loading = false
  editor.visible = false
  if (!row) return
  detailLoading.value = true
  try {
    const data = await directoryApi.profile(row.org_id)
    if (current !== detailSequence) return
    profile.value = data
    if (activeTab.value === 'contacts') await loadContacts()
    if (activeTab.value === 'bindings') await loadBindings()
  } catch (e) { if (current === detailSequence) detailError.value = e.message || '单位详情加载失败。' }
  finally { if (current === detailSequence) detailLoading.value = false }
}
async function loadContacts() {
  const current = ++contactSequence
  const id = selectedId.value
  if (!id) return
  contacts.loading = true; contacts.error = ''
  try {
    const result = await directoryApi.contacts({ org_id: id, keyword: contacts.keyword.trim(), page: contacts.page, size: 20 })
    if (current !== contactSequence || id !== selectedId.value) return
    contacts.items = result.items || []; contacts.total = result.total || 0
  } catch (e) { if (current === contactSequence) { contacts.items = []; contacts.error = e.message || '联系人加载失败。' } }
  finally { if (current === contactSequence) contacts.loading = false }
}
async function loadBindings() {
  const current = ++bindingSequence
  const id = selectedId.value
  if (!id) return
  bindings.loading = true; bindings.error = ''
  try {
    const result = await directoryApi.bindings({ org_id: id, keyword: bindings.keyword.trim(), page: bindings.page, size: 20 })
    if (current !== bindingSequence || id !== selectedId.value) return
    bindings.items = result.items || []; bindings.total = result.total || 0
  } catch (e) { if (current === bindingSequence) { bindings.items = []; bindings.error = e.message || '来源映射加载失败。' } }
  finally { if (current === bindingSequence) bindings.loading = false }
}
function tabChanged() { if (activeTab.value === 'contacts') loadContacts(); if (activeTab.value === 'bindings') loadBindings() }
function search() { page.value = 1; loadList() }
async function openEditor(kind, row = null) {
  const current = detailSequence
  if (kind === 'contact' && row) {
    contacts.error = ''; contacts.loading = true
    try {
      const detail = await directoryApi.contact(row.contact_id)
      if (current !== detailSequence) return
      Object.assign(editor, { kind, row: detail, visible: true })
    } catch (e) { if (current === detailSequence) contacts.error = e.message || '联系人详情加载失败。' }
    finally { if (current === detailSequence) contacts.loading = false }
  } else Object.assign(editor, { kind, row, visible: true })
}
async function saved(result) {
  if (editor.kind === 'profile') {
    selectedId.value = result?.org_id || selectedId.value
    keyword.value = ''; page.value = 1
    await loadList()
    if (result?.org_id && selectedId.value !== result.org_id) await selectProfile(result)
  } else {
    if (editor.kind === 'contact') await loadContacts()
    else await loadBindings()
    if (selectedId.value) profile.value = await directoryApi.profile(selectedId.value).catch(() => profile.value)
  }
}
async function refreshProfileCounts() {
  const id = selectedId.value
  const current = detailSequence
  try {
    const updated = await directoryApi.profile(id)
    if (current === detailSequence) profile.value = updated
  } catch { /* 计划关联已保存，档案计数可通过刷新重新读取。 */ }
}
function refreshEditor() { if (editor.kind === 'profile') loadList(); else if (editor.kind === 'contact') loadContacts(); else loadBindings() }
onMounted(loadList)
onBeforeUnmount(() => { listSequence++; detailSequence++; contactSequence++; bindingSequence++ })
</script>

<template>
  <div class="page-stack organizations-page">
    <PageHeader title="单位档案" description="维护单位、联系人和计划的明确关联，供业务通知读取。">
      <el-button v-if="canEdit" type="primary" @click="openEditor('profile')">新增单位</el-button><el-button :loading="loading" @click="loadList">刷新</el-button>
    </PageHeader>
    <ErrorAlert :message="error" @retry="loadList" />
    <el-alert v-if="!canEdit" title="当前账号可查看档案。新增、修改及计划关联需要单位档案维护权限。" type="info" :closable="false" />
    <div class="directory-layout">
      <el-card class="unit-list" shadow="never">
        <el-form @submit.prevent="search"><el-form-item label="查找单位"><el-input v-model="keyword" clearable placeholder="单位名称或编码" @clear="search" @keyup.enter="search" /></el-form-item><el-button :loading="loading" @click="search">查找</el-button></el-form>
        <p class="muted">共 {{ total }} 个单位</p>
        <div v-loading="loading" class="unit-items">
          <button v-for="row in rows" :key="row.org_id" type="button" class="unit-item" :class="{ active: selectedId === row.org_id }" @click="selectProfile(row)">
            <strong>{{ row.name }}</strong><span>{{ labelOf(organizationTypes, row.organization_type) }}</span><small>{{ row.org_code || '未填写编码' }}</small>
          </button>
          <el-empty v-if="!loading && !rows.length && !error" description="没有符合条件的单位" :image-size="64" />
        </div>
        <div class="list-pager"><el-button :disabled="page <= 1 || loading" @click="page--; loadList()">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="page * 20 >= total || loading" @click="page++; loadList()">下一页</el-button></div>
      </el-card>
      <el-card v-loading="detailLoading" class="unit-detail" shadow="never">
        <ErrorAlert :message="detailError" @retry="selectProfile({ org_id: selectedId })" />
        <el-empty v-if="!profile && !detailLoading && !detailError" description="选择单位查看完整档案，或新增单位。" />
        <template v-if="profile">
          <div class="detail-heading"><div><h2>{{ profile.name }}</h2><p>{{ labelOf(organizationTypes, profile.organization_type) }} · {{ profile.org_code || '未填写编码' }}</p></div><el-button v-if="canEdit" @click="openEditor('profile', profile)">编辑档案</el-button></div>
          <el-tabs v-model="activeTab" @tab-change="tabChanged">
            <el-tab-pane label="单位资料" name="profile">
              <el-descriptions border :column="2">
                <el-descriptions-item label="单位名称">{{ profile.name }}</el-descriptions-item><el-descriptions-item label="单位类型">{{ labelOf(organizationTypes, profile.organization_type) }}</el-descriptions-item>
                <el-descriptions-item label="单位编码">{{ profile.org_code || '未填写' }}</el-descriptions-item><el-descriptions-item label="上级单位">{{ profile.parent_name || (profile.parent_id ? '已关联上级单位' : '无上级单位') }}</el-descriptions-item>
                <el-descriptions-item label="统一标识">{{ profile.credit_code || '未填写' }}</el-descriptions-item><el-descriptions-item label="单位地址">{{ profile.address || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="业务职责" :span="2">{{ profile.responsibilities || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="档案说明" :span="2">{{ profile.remarks || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="联系人数量">{{ profile.contact_count ?? 0 }}</el-descriptions-item><el-descriptions-item label="关联计划数量">{{ profile.plan_count ?? 0 }}</el-descriptions-item>
                <el-descriptions-item label="最近更新">{{ formatTime(profile.updated_at) }}</el-descriptions-item><el-descriptions-item label="记录版本">{{ profile.version }}</el-descriptions-item>
              </el-descriptions>
              <p class="form-note">单位建档、联系人和登录账号分别管理。单位名称更新后，旧计划申报文本和通知接收快照继续保留当时的内容。</p>
            </el-tab-pane>
            <el-tab-pane label="联系人" name="contacts">
              <div class="section-toolbar"><el-input v-model="contacts.keyword" clearable placeholder="联系人姓名、电话或用途" @clear="contacts.page=1; loadContacts()" @keyup.enter="contacts.page=1; loadContacts()" /><el-button @click="contacts.page=1; loadContacts()">查找</el-button><el-button v-if="canEdit" type="primary" @click="openEditor('contact')">新增联系人</el-button></div>
              <ErrorAlert :message="contacts.error" @retry="loadContacts" />
              <el-table v-loading="contacts.loading" :data="contacts.items" empty-text="该单位尚无联系人" row-key="contact_id">
                <el-table-column label="联系人" min-width="125"><template #default="{ row }"><strong>{{ row.name }}</strong><p class="muted">{{ row.user_name ? `已关联账号：${row.user_name}` : row.user_id ? '已关联现有账号' : '独立联系人' }}</p></template></el-table-column>
                <el-table-column label="联系用途" min-width="130"><template #default="{ row }">{{ (row.roles || []).map(role => labelOf(contactRoles, role)).join('、') || '未填写' }}</template></el-table-column>
                <el-table-column label="联系方式" min-width="155"><template #default="{ row }"><p>{{ row.phone || '未填写电话' }}</p><p v-if="row.email">{{ row.email }}</p></template></el-table-column>
                <el-table-column label="有效性" min-width="165"><template #default="{ row }"><span>{{ row.enabled ? '启用' : '停用' }}</span><p class="muted">{{ row.valid_until ? `有效至 ${formatTime(row.valid_until)}` : '未设截止时间' }}</p><p class="muted">{{ row.verified_at ? `核实于 ${formatTime(row.verified_at)}` : '联系方式尚未核实' }}</p></template></el-table-column>
                <el-table-column label="操作" width="105"><template #default="{ row }"><el-button link type="primary" @click="openEditor('contact', row)">{{ canEdit ? '查看与编辑' : '查看详情' }}</el-button></template></el-table-column>
              </el-table>
              <el-pagination v-if="contacts.total > 20" v-model:current-page="contacts.page" :page-size="20" :total="contacts.total" layout="prev, pager, next" @current-change="loadContacts" />
            </el-tab-pane>
            <el-tab-pane label="来源映射" name="bindings">
              <div class="section-toolbar"><el-input v-model="bindings.keyword" clearable placeholder="来源名称或外部单位编码" @clear="bindings.page=1; loadBindings()" @keyup.enter="bindings.page=1; loadBindings()" /><el-button @click="bindings.page=1; loadBindings()">查找</el-button><el-button v-if="canEdit" type="primary" @click="openEditor('binding')">新增来源映射</el-button></div>
              <ErrorAlert :message="bindings.error" @retry="loadBindings" />
              <el-table v-loading="bindings.loading" :data="bindings.items" empty-text="该单位尚无来源映射" row-key="binding_id">
                <el-table-column prop="source_name" label="来源系统" min-width="150" /><el-table-column prop="external_org_code" label="外部单位编码" min-width="170" /><el-table-column prop="org_name" label="对应单位" min-width="160" />
                <el-table-column label="状态" min-width="90"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
                <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="openEditor('binding', row)">{{ canEdit ? '编辑映射' : '查看映射' }}</el-button></template></el-table-column>
              </el-table>
              <el-pagination v-if="bindings.total > 20" v-model:current-page="bindings.page" :page-size="20" :total="bindings.total" layout="prev, pager, next" @current-change="loadBindings" />
              <p class="form-note">来源系统与单位编码共同决定报送单位，不能仅凭单位重名合并。修改映射不会改写已冻结的通知材料。</p>
            </el-tab-pane>
            <el-tab-pane label="计划关联" name="plans"><PlanAssociationPanel v-if="activeTab === 'plans'" :can-edit="canEdit" @saved="refreshProfileCounts" /></el-tab-pane>
          </el-tabs>
        </template>
      </el-card>
    </div>
    <DirectoryRecordEditor v-model:visible="editor.visible" :kind="editor.kind" :row="editor.row" :organization="profile" :can-edit="canEdit" @saved="saved" @refresh="refreshEditor" />
  </div>
</template>

<style scoped>
.directory-layout{display:grid;grid-template-columns:260px minmax(0,1fr);gap:16px;min-width:0;align-items:start}.unit-list,.unit-detail{min-width:0}.unit-items{display:flex;flex-direction:column;gap:8px;min-height:140px;max-height:62vh;overflow:auto}.unit-item{display:flex;flex-direction:column;gap:6px;width:100%;padding:12px;border:1px solid var(--admin-border);border-radius:8px;background:#f8fbff;text-align:left;cursor:pointer;color:var(--admin-text);white-space:normal;overflow-wrap:anywhere}.unit-item:hover,.unit-item.active{border-color:var(--admin-primary);background:#edf5ff}.unit-item:focus-visible{outline:2px solid var(--admin-primary);outline-offset:2px}.unit-item strong{font-size:14px}.unit-item span,.unit-item small,.muted{color:var(--admin-muted);font-size:12px}.muted{margin:5px 0;line-height:1.5}.list-pager{display:flex;justify-content:space-between;gap:6px;align-items:center;flex-wrap:wrap;margin-top:16px;font-size:12px}.list-pager .el-button+.el-button{margin-left:0}.detail-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.detail-heading h2{font-size:20px;margin:0;overflow-wrap:anywhere}.detail-heading p{margin:8px 0;color:var(--admin-muted)}.detail-heading .el-button{flex:none}.section-toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.section-toolbar .el-input{flex:1;min-width:180px}.section-toolbar .el-button+.el-button{margin-left:0}.form-note{margin-top:18px}.el-pagination{margin-top:14px;overflow:auto}:deep(.el-table .cell),:deep(.el-descriptions__body td),:deep(.el-alert__title){white-space:normal;overflow-wrap:anywhere;word-break:break-word;text-overflow:clip}:deep(.el-table .cell p){margin:5px 0}:deep(.el-table__body-wrapper){overflow:auto}:deep(.el-tabs__content){min-width:0}:deep(.el-button span){white-space:normal}:deep(.el-tabs__item){white-space:normal;height:auto;min-height:40px;line-height:1.6;display:inline-flex;align-items:center}
@media(max-width:1120px){.directory-layout{grid-template-columns:220px minmax(0,1fr)}}@media(max-width:900px){.directory-layout{grid-template-columns:1fr}.unit-items{max-height:240px}.detail-heading{flex-wrap:wrap}}
</style>
