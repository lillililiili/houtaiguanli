<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { responsePlanApi as api } from '@/api/responsePlans'
import { formatTime } from '@/utils/format'
import ResponsePlanAirspaceSelect from './ResponsePlanAirspaceSelect.vue'

const auth = useAuthStore(), canEdit = computed(() => auth.hasPermission('responsePlans.auth'))
const rows = ref([]), total = ref(0), page = ref(1), loading = ref(false), error = ref('')
const versions = ref([]), selected = ref(null), detailLoading = ref(false), detailError = ref('')
const form = ref(null), editing = ref(''), busy = ref(false), actionError = ref('')
const space = ref(''), bindings = ref(null), bindingLoading = ref(false), bindingError = ref(''), historyPage = ref(1)
let listSeq = 0, detailSeq = 0, bindingSeq = 0
const status = value => ({ DRAFT: '草稿', PUBLISHED: '已发布', WITHDRAWN: '已停用' })[value] || '未知'
const source = value => ({ live: '正式配置', mock: '模拟配置', replay: '回放配置' })[value] || '来源未知'
const fields = [{ key: 'trigger_basis', label: '触发依据与时效要求' }, { key: 'action_steps', label: '处置行动说明' }, { key: 'manual_conditions', label: '人工介入与授权条件' }, { key: 'failure_handling', label: '失败、超时与未知情况处理' }]
const blank = () => ({ airspace_id: '', name: '', trigger_basis: '', action_steps: '', manual_conditions: '', failure_handling: '', source_mode: '', valid_from: null, valid_to: null })
async function load() {
  const seq = ++listSeq; loading.value = true; error.value = ''
  try { const data = await api.list({ page: page.value, size: 20 }); if (seq === listSeq) { rows.value = data.items; total.value = data.total } }
  catch (e) { if (seq === listSeq) error.value = e.message }
  finally { if (seq === listSeq) loading.value = false }
}
async function open(row, versionId) {
  const seq = ++detailSeq; selected.value = null; versions.value = []; form.value = null; editing.value = ''; detailError.value = ''; actionError.value = ''; detailLoading.value = true
  try {
    const data = await api.versions(row.plan_id)
    if (seq !== detailSeq) return
    versions.value = data; selected.value = data.find(v => v.version_id === versionId) || data[0]
    space.value = selected.value.airspace_id
  } catch (e) { if (seq === detailSeq) detailError.value = e.message }
  finally { if (seq === detailSeq) detailLoading.value = false }
}
function chooseVersion(id) { selected.value = versions.value.find(v => v.version_id === id); editing.value = ''; form.value = null; actionError.value = '' }
function edit(mode) {
  actionError.value = ''; editing.value = mode
  if (mode === 'create') { detailSeq += 1; detailLoading.value = false; selected.value = null; versions.value = []; detailError.value = ''; form.value = blank() }
  else { form.value = Object.fromEntries(Object.keys(blank()).map(key => [key, selected.value[key] ?? null])); form.value.expected_version = selected.value.version }
}
async function save() {
  if (!form.value.airspace_id || !form.value.name?.trim() || fields.some(f => !form.value[f.key]?.trim()) || !form.value.source_mode || form.value.valid_from == null) { actionError.value = '请填写空域、名称、四项处置说明、来源及开始时间'; return }
  form.value.valid_from = Number(form.value.valid_from)
  form.value.valid_to = form.value.valid_to == null || form.value.valid_to === '' ? null : Number(form.value.valid_to)
  if (form.value.valid_to != null && form.value.valid_to <= form.value.valid_from) { actionError.value = '结束时间必须晚于开始时间'; return }
  busy.value = true; actionError.value = ''
  try {
    const result = editing.value === 'create' ? await api.create(form.value) : editing.value === 'copy' ? await api.newVersion(selected.value.plan_id, form.value) : await api.update(selected.value.version_id, form.value)
    await open(result, result.version_id); await load(); ElMessage.success('草稿已保存')
  } catch (e) { actionError.value = `${e.message}；如请求结果未知，请刷新列表确认后再操作。` }
  finally { busy.value = false }
}
async function change(action) {
  const target = selected.value
  busy.value = true; actionError.value = ''
  try {
    const label = action === 'publish' ? '发布' : '停用'
    const { value } = await ElMessageBox.prompt(`填写${label}“${target.name}”第 ${target.revision} 版的依据。发布仅提供预案查询，不触发设备或通知。`, `${label}预案`, { inputValidator: v => !!v?.trim() && v.trim().length <= 1000 || '请填写1至1000字依据', confirmButtonText: label, cancelButtonText: '取消' })
    const result = await api[action](target.version_id, { expected_version: target.version, reason: value.trim() })
    await open(result, result.version_id); await load(); await loadBinding(); ElMessage.success(`预案已${label}`)
  } catch (e) { if (e !== 'cancel' && e !== 'close') actionError.value = e.message }
  finally { busy.value = false }
}
async function loadBinding() {
  const seq = ++bindingSeq, id = space.value; bindings.value = null; bindingError.value = ''
  if (!id) { bindingLoading.value = false; return }
  bindingLoading.value = true
  try { const data = await api.airspace(id, { page: historyPage.value, size: 10 }); if (seq === bindingSeq) bindings.value = data }
  catch (e) { if (seq === bindingSeq) bindingError.value = e.message }
  finally { if (seq === bindingSeq) bindingLoading.value = false }
}
async function bind(unbind = false) {
  const target = selected.value, id = space.value, previous = bindings.value?.current?.binding_id || null
  busy.value = true; actionError.value = ''
  try {
    const { value } = await ElMessageBox.prompt(unbind ? '填写解除关联的原因，已有历史将保留。' : `将所选空域关联到“${target.name}”第 ${target.revision} 版。已有当前关联会结束并保留历史，请填写依据。`, unbind ? '解除预案关联' : '关联已发布预案', { inputValidator: v => !!v?.trim() && v.trim().length <= 1000 || '请填写1至1000字依据', confirmButtonText: '保存关联', cancelButtonText: '取消' })
    await api.bind(id, { version_id: unbind ? null : target.version_id, expected_binding_id: previous, reason: value.trim() }); await loadBinding(); ElMessage.success('空域预案关联已更新')
  } catch (e) { if (e !== 'cancel' && e !== 'close') { actionError.value = e.message; await loadBinding() } }
  finally { busy.value = false }
}
watch(space, () => { historyPage.value = 1; loadBinding() })
onMounted(load)
onBeforeUnmount(() => { listSeq += 1; detailSeq += 1; bindingSeq += 1 })
</script>
<template>
  <div class="page-stack response-plans-page">
    <PageHeader title="处置预案" description="配置处置依据与行动说明，发布后关联到具体空域；发布内容和历史关联可追溯。" />
    <el-alert title="预案供业务页面查阅。自动通知仍按现有后端规则执行，反制仍需独立授权；发布或关联预案不会发送通知或下发指令。" type="info" :closable="false" />
    <div class="actions"><el-button :disabled="busy || loading" @click="load">刷新列表</el-button><el-button v-if="canEdit" type="primary" :disabled="busy" @click="edit('create')">新建预案</el-button><span v-else>当前账号只能查看预案</span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="预案名称" min-width="180" />
      <el-table-column prop="airspace_name" label="归属空域" min-width="160" />
      <el-table-column label="最新版本" width="100"><template #default="{ row }">第 {{ row.revision }} 版</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }">{{ status(row.status) }}</template></el-table-column>
      <el-table-column label="来源" width="110"><template #default="{ row }">{{ source(row.source_mode) }}</template></el-table-column>
      <el-table-column label="操作" width="110"><template #default="{ row }"><el-button :disabled="busy" @click="open(row)">查看版本</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" :disabled="busy" @current-change="load" />
    <p v-if="detailLoading">正在读取预案版本</p><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" />
    <el-card v-if="selected || editing">
      <div v-if="selected" class="actions">
        <el-select :model-value="selected.version_id" :disabled="busy || !!editing" aria-label="预案版本" @update:model-value="chooseVersion"><el-option v-for="v in versions" :key="v.version_id" :label="`第 ${v.revision} 版 · ${status(v.status)}`" :value="v.version_id" /></el-select>
        <template v-if="canEdit && !editing">
          <el-button v-if="selected.status === 'DRAFT'" :disabled="busy" @click="edit('update')">编辑草稿</el-button>
          <el-button v-if="selected.status === 'DRAFT'" type="primary" :disabled="busy" @click="change('publish')">发布版本</el-button>
          <el-button v-if="!versions.some(v => v.status === 'DRAFT')" :disabled="busy" @click="edit('copy')">创建新版本</el-button>
          <el-button v-if="selected.status === 'PUBLISHED'" type="danger" plain :disabled="busy" @click="change('withdraw')">停用版本</el-button>
        </template>
      </div>
      <el-form v-if="editing" label-position="top" :disabled="busy" @submit.prevent="save">
        <el-form-item label="归属空域（限制预案组织与区域范围）" required><ResponsePlanAirspaceSelect v-if="editing === 'create'" v-model="form.airspace_id" :disabled="busy" /><span v-else>{{ selected.airspace_name }}</span></el-form-item>
        <el-form-item label="预案名称" required><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label" required><el-input v-model="form[field.key]" type="textarea" :rows="3" maxlength="4000" show-word-limit /></el-form-item>
        <div class="form-grid"><el-form-item label="配置来源" required><el-select v-model="form.source_mode"><el-option label="正式配置" value="live" /><el-option label="模拟配置" value="mock" /><el-option label="回放配置" value="replay" /></el-select></el-form-item><el-form-item label="开始时间" required><el-date-picker v-model="form.valid_from" type="datetime" value-format="x" /></el-form-item><el-form-item label="结束时间（留空为长期）"><el-date-picker v-model="form.valid_to" type="datetime" value-format="x" /></el-form-item></div>
        <el-button type="primary" :loading="busy" @click="save">保存草稿</el-button><el-button :disabled="busy" @click="editing = ''; form = null">取消编辑</el-button>
      </el-form>
      <template v-else-if="selected">
        <h3>{{ selected.name }} <el-tag>{{ status(selected.status) }}</el-tag></h3><p>{{ source(selected.source_mode) }} · 第 {{ selected.revision }} 版</p>
        <p>有效期：{{ formatTime(selected.valid_from) }} 至 {{ selected.valid_to ? formatTime(selected.valid_to) : '长期有效' }}</p>
        <p>更新时间：{{ formatTime(selected.updated_at) }}</p>
        <p v-if="selected.published_at">发布人：{{ selected.published_by }} · {{ formatTime(selected.published_at) }}</p>
        <p v-if="selected.withdrawn_at">停用时间：{{ formatTime(selected.withdrawn_at) }} · 原因：{{ selected.withdrawn_reason }}</p>
        <section v-for="field in fields" :key="field.key"><h4>{{ field.label }}</h4><p class="content">{{ selected[field.key] }}</p></section>
      </template>
    </el-card>
    <el-card v-if="!editing">
      <h3>空域预案关联</h3><p>选择空域查看当前关联及历史；只能关联同一组织及区域的已发布版本。</p>
      <ResponsePlanAirspaceSelect v-model="space" :selected-name="space === selected?.airspace_id ? selected.airspace_name : ''" :disabled="busy" />
      <p v-if="bindingLoading">正在读取空域关联</p>
      <el-alert v-if="bindingError" :title="bindingError" type="error" :closable="false" />
      <el-button v-if="space" :disabled="busy || bindingLoading" @click="loadBinding">刷新关联</el-button>
      <template v-if="bindings">
        <p v-if="bindings.current">当前关联：{{ bindings.current.plan.name }} · 第 {{ bindings.current.plan.revision }} 版<br>{{ bindings.current.applicability_reason }}<br>关联人：{{ bindings.current.bound_by }} · {{ formatTime(bindings.current.bound_at) }}<br>关联依据：{{ bindings.current.reason }}</p>
        <p v-else>此空域尚未关联处置预案</p>
        <div v-if="canEdit" class="actions"><el-button v-if="selected?.status === 'PUBLISHED'" type="primary" :disabled="busy || bindings.current?.plan.version_id === selected.version_id" @click="bind()">关联所选预案版本</el-button><el-button v-if="bindings.current" :disabled="busy" @click="bind(true)">解除当前关联</el-button></div>
        <h4>关联历史（{{ bindings.history.total }} 条）</h4>
        <p v-for="item in bindings.history.items" :key="item.binding_id">{{ item.plan.name }} · 第 {{ item.plan.revision }} 版<br>{{ formatTime(item.bound_at) }} 至 {{ formatTime(item.ended_at) }}<br>{{ item.bound_by }} 关联：{{ item.reason }}<br>{{ item.ended_by }} 解除：{{ item.end_reason }}</p>
        <el-pagination v-if="bindings.history.total > 10" v-model:current-page="historyPage" :page-size="10" :total="bindings.history.total" layout="prev, pager, next" :disabled="busy" @current-change="loadBinding" />
      </template>
    </el-card>
  </div>
</template>
<style scoped>
.response-plans-page { min-width: 0; }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 16px; }
.content { white-space: pre-wrap; }
p, h3, h4 { overflow-wrap: anywhere; line-height: 1.7; }
.response-plans-page :deep(.cell) { white-space: normal; overflow-wrap: anywhere; }
.response-plans-page :deep(.el-card__body) { display: grid; gap: 12px; }
.response-plans-page :deep(.el-input), .response-plans-page :deep(.el-select) { max-width: 100%; }
</style>
