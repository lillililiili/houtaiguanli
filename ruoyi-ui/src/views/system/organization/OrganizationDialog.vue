<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DirectoryRecordEditor from './DirectoryRecordEditor.vue'
import PlanAssociationPanel from './PlanAssociationPanel.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { contactRoles, labelOf, organizationTypes } from '@/utils/directoryConfiguration'
import { formatTime } from '@/utils/format'
const props = defineProps({ visible: Boolean, row: { type: Object, default: null }, parent: { type: Object, default: null }, mode: { type: String, default: 'view' }, canReadDirectory: Boolean, canEditDirectory: Boolean, canEditBasic: Boolean, organizations: { type: Array, default: () => [] } })
const emit = defineEmits(['update:visible', 'saved'])
const profile = ref(null)
const targetOrgId = ref('')
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const activeTab = ref('profile')
const relationTab = ref('bindings')
const editor = reactive({ visible: false, kind: 'profile', row: null })
const contacts = reactive({ items: [], page: 1, total: 0, keyword: '', loading: false, error: '' })
const bindings = reactive({ items: [], page: 1, total: 0, keyword: '', loading: false, error: '' })
const canEdit = computed(() => props.canEditDirectory)
const canEditProfile = computed(() => props.canEditDirectory || props.canEditBasic)
const basicOnly = computed(() => !props.canReadDirectory || !props.canEditDirectory)
const title = computed(() => editor.visible ? `${editor.row ? '编辑' : '新增'}${({ profile: '单位', contact: '联系人', binding: '来源映射' })[editor.kind]}` : '查看单位')
let sequence = 0
let contactSequence = 0
let bindingSequence = 0
async function loadProfile() {
  const current = ++sequence
  loading.value = true; error.value = ''; profile.value = null
  try {
    const data = props.canReadDirectory ? await directoryApi.profile(targetOrgId.value) : props.row
    if (current !== sequence) return
    profile.value = data
    if (props.mode === 'edit' && !editor.visible) openEditor('profile', data)
  } catch (e) { if (current === sequence) error.value = e.message || '单位资料加载失败，请重试。' }
  finally { if (current === sequence) loading.value = false }
}
watch(() => [props.visible, props.row, props.mode], async () => {
  sequence++; contactSequence++; bindingSequence++
  targetOrgId.value = props.row?.org_id || ''
  editor.visible = false; profile.value = null; error.value = ''; loading.value = false
  activeTab.value = 'profile'; relationTab.value = 'bindings'
  for (const state of [contacts, bindings]) Object.assign(state, { items: [], page: 1, total: 0, keyword: '', loading: false, error: '' })
  if (!props.visible) return
  if (props.mode === 'create') openEditor('profile')
  else if (props.row) await loadProfile()
}, { immediate: true })
async function loadContacts() {
  const current = ++contactSequence
  const id = profile.value?.org_id
  if (!id) return
  contacts.loading = true; contacts.error = ''
  try {
    const result = await directoryApi.contacts({ org_id: id, keyword: contacts.keyword.trim(), page: contacts.page, size: 20 })
    if (current !== contactSequence) return
    contacts.items = result.items || []; contacts.total = result.total || 0
  } catch (e) { if (current === contactSequence) { contacts.items = []; contacts.error = e.message || '联系人加载失败。' } }
  finally { if (current === contactSequence) contacts.loading = false }
}
async function loadBindings() {
  const current = ++bindingSequence
  const id = profile.value?.org_id
  if (!id) return
  bindings.loading = true; bindings.error = ''
  try {
    const result = await directoryApi.bindings({ org_id: id, keyword: bindings.keyword.trim(), page: bindings.page, size: 20 })
    if (current !== bindingSequence) return
    bindings.items = result.items || []; bindings.total = result.total || 0
  } catch (e) { if (current === bindingSequence) { bindings.items = []; bindings.error = e.message || '来源映射加载失败。' } }
  finally { if (current === bindingSequence) bindings.loading = false }
}
function tabChanged() { if (activeTab.value === 'contacts') loadContacts(); if (activeTab.value === 'relations' && relationTab.value === 'bindings') loadBindings() }
async function openEditor(kind, row = null) {
  const current = sequence
  if (kind === 'contact' && row) {
    contacts.error = ''; contacts.loading = true
    try {
      const detail = await directoryApi.contact(row.contact_id)
      if (current !== sequence) return
      Object.assign(editor, { kind, row: detail, visible: true })
    } catch (e) { if (current === sequence) contacts.error = e.message || '联系人详情加载失败。' }
    finally { if (current === sequence) contacts.loading = false }
  } else Object.assign(editor, { kind, row, visible: true })
}
function closeEditor() { editor.visible = false; if (props.mode === 'create' && !profile.value) close() }
function close() { if (!busy.value) emit('update:visible', false) }
async function saved(result) {
  if (editor.kind === 'profile') { targetOrgId.value = result.org_id; profile.value = result; emit('saved', result); if (props.canReadDirectory) await refreshProfileCounts() }
  else if (editor.kind === 'contact') await loadContacts()
  else await loadBindings()
}
async function refreshProfileCounts() {
  const current = sequence
  try { const result = await directoryApi.profile(profile.value.org_id); if (current === sequence) profile.value = result }
  catch (e) { if (current === sequence) error.value = e.message || '最新单位资料读取失败。' }
}
function refreshEditor() { if (editor.kind === 'profile') { loadProfile() } else if (editor.kind === 'contact') loadContacts(); else loadBindings() }
onBeforeUnmount(() => { sequence++; contactSequence++; bindingSequence++ })
</script>
<template>
  <el-dialog :model-value="visible" :title="title" width="min(960px, calc(100vw - 32px))" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value => !value && close()">
    <section v-loading="loading" class="unit-dialog">
      <ErrorAlert :message="error" @retry="loadProfile" />
      <DirectoryRecordEditor v-if="editor.visible" :key="`${editor.kind}-${editor.row?.contact_id || editor.row?.org_id || editor.row?.binding_id || 'new'}`" :visible="true" embedded :kind="editor.kind" :row="editor.row" :parent="parent" :organization="profile" :organizations="organizations" :basic-only="editor.kind === 'profile' && basicOnly" :can-edit="editor.kind === 'profile' ? canEditProfile : canEdit" @update:visible="value => !value && closeEditor()" @busy="busy = $event" @saved="saved" @refresh="refreshEditor" />
      <template v-else-if="profile">
        <el-tabs v-model="activeTab" @tab-change="tabChanged">
          <el-tab-pane label="基本信息" name="profile">
            <el-descriptions border :column="2">
              <el-descriptions-item label="单位名称" :span="2">{{ profile.name }}</el-descriptions-item>
              <el-descriptions-item label="上级单位">{{ profile.parent_name || organizations.find(item => item.org_id === profile.parent_id)?.name || (profile.parent_id ? '已关联上级单位' : '无上级单位') }}</el-descriptions-item>
              <el-descriptions-item label="单位编码">{{ profile.org_code || '未填写' }}</el-descriptions-item>
              <template v-if="canReadDirectory">
                <el-descriptions-item label="单位类型">{{ labelOf(organizationTypes, profile.organization_type) }}</el-descriptions-item>
                <el-descriptions-item label="统一标识">{{ profile.credit_code || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="单位地址" :span="2">{{ profile.address || '未填写' }}</el-descriptions-item>
                <el-descriptions-item v-if="profile.responsibilities" label="业务说明" :span="2">{{ profile.responsibilities }}</el-descriptions-item>
                <el-descriptions-item label="备注" :span="2">{{ profile.remarks || '未填写' }}</el-descriptions-item>
                <el-descriptions-item v-if="profile.updated_at" label="最近更新" :span="2">{{ formatTime(profile.updated_at) }}</el-descriptions-item>
              </template>
            </el-descriptions>
            <p v-if="!canReadDirectory" class="muted">当前权限可查看单位基本信息，完整资料及联系人需要单位资料读取权限。</p>
          </el-tab-pane>
            <el-tab-pane v-if="canReadDirectory" label="联系人" name="contacts">
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
          <el-tab-pane v-if="canReadDirectory" label="关联信息" name="relations">
            <el-tabs v-model="relationTab" @tab-change="tabChanged">
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
            <el-tab-pane label="计划关联" name="plans"><PlanAssociationPanel v-if="activeTab === 'relations' && relationTab === 'plans'" :can-edit="canEdit" @saved="refreshProfileCounts" /></el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </template>
    </section>
    <template v-if="!editor.visible" #footer>
      <el-button @click="close">关闭</el-button>
      <el-button v-if="profile && canEditProfile && activeTab === 'profile'" type="primary" @click="openEditor('profile', profile)">编辑单位</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.unit-dialog{min-height:140px;min-width:0}.muted{color:var(--admin-muted);font-size:12px;margin:5px 0;line-height:1.5}.section-toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.section-toolbar .el-input{flex:1;min-width:180px}.section-toolbar .el-button+.el-button{margin-left:0}.el-pagination{margin-top:14px;overflow:auto}:deep(.el-table .cell),:deep(.el-descriptions__body td),:deep(.el-alert__title){white-space:normal;overflow-wrap:anywhere;word-break:break-word;text-overflow:clip}:deep(.el-table .cell p){margin:5px 0}:deep(.el-tabs__content){min-width:0}:deep(.el-tabs__item){white-space:normal;height:auto;min-height:40px;line-height:1.6}:deep(.el-descriptions__body){table-layout:fixed}
</style>
