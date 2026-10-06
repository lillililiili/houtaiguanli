<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { systemApi } from '@/api/system'
import { isUncertainOutcome, newIdempotencyKey } from '@/services/apiClient'

const props = defineProps({ visible: Boolean, canEdit: Boolean })
const emit = defineEmits(['update:visible', 'changed'])
const rows = ref([])
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const formRef = ref()
const form = reactive({ mode: '', row: null, name: '', district_code: '', error: '' })
const rules = {
  name: [{ required: true, whitespace: true, message: '请输入区域名称' }],
  district_code: [{ required: true, whitespace: true, message: '请输入区域编码' }, { pattern: /^\S+$/, message: '区域编码不能含空格' }]
}
// 同一份填写内容重试时沿用同一个幂等键：上次结果没确认时再点保存，不会多建一个区域。
let pending = { fingerprint: '', key: '' }
let sequence = 0

async function load() {
  const current = ++sequence
  loading.value = true; error.value = ''
  try {
    const data = await systemApi.districts()
    if (current === sequence) rows.value = data || []
  } catch (e) { if (current === sequence) error.value = e.message || '区域列表加载失败。' }
  finally { if (current === sequence) loading.value = false }
}
function closeForm() { Object.assign(form, { mode: '', row: null, name: '', district_code: '', error: '' }) }
function openCreate() { if (props.canEdit && !busy.value) Object.assign(form, { mode: 'create', row: null, name: '', district_code: '', error: '' }) }
function openRename(row) { if (props.canEdit && !busy.value) Object.assign(form, { mode: 'rename', row, name: row.name, district_code: row.district_code, error: '' }) }
function keyFor(fingerprint) {
  if (pending.fingerprint !== fingerprint) pending = { fingerprint, key: newIdempotencyKey(`district-${form.mode}`) }
  return pending.key
}
async function save() {
  if (busy.value || !props.canEdit || !form.mode) return
  if (!await formRef.value.validate().catch(() => false)) return
  const creating = form.mode === 'create'
  const name = form.name.trim(), code = form.district_code.trim()
  const key = keyFor(JSON.stringify([form.mode, form.row?.district_id, form.row?.version, name, code]))
  busy.value = true; form.error = ''
  try {
    if (creating) await systemApi.createDistrict({ district_code: code, name }, key)
    else await systemApi.updateDistrict(form.row.district_id, { name, expected_version: form.row.version }, key)
    pending = { fingerprint: '', key: '' }
    closeForm()
    ElMessage.success(creating ? `区域“${name}”已新建，接入设备和配置连接时可以选择。` : '区域名称已保存。')
    emit('changed')
    await load()
  } catch (e) {
    if (e.code === 'IDEMPOTENCY_REPLAY') {
      pending = { fingerprint: '', key: '' }
      closeForm(); await load()
      ElMessage.warning('这次保存之前已经提交过，列表已刷新，请核对。')
      return
    }
    if (e.code === 'VERSION_CONFLICT') {
      closeForm(); await load()
      ElMessage.warning('区域已被其他人修改，列表已刷新，请重新改名。')
      return
    }
    form.error = e.message || '保存失败，请重试。'
    if (isUncertainOutcome(e)) await load()
  } finally { busy.value = false }
}
function close() { if (!busy.value) emit('update:visible', false) }
watch(() => props.visible, value => {
  if (value) { closeForm(); load() } else sequence++
}, { immediate: true })
</script>

<template>
  <el-dialog :model-value="visible" title="区域管理" width="min(720px, calc(100vw - 32px))" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value => !value && close()">
    <section class="district-dialog">
      <el-alert type="info" :closable="false" show-icon title="区域用来划分数据范围：设备、设备数据连接和账号都按“单位 + 区域”归属。新系统要先建好单位和至少一个区域，才能接入设备。区域编码保存后不能修改，区域也不能删除。" />
      <ErrorAlert :message="error" @retry="load" />
      <div class="table-toolbar"><span class="muted">共 {{ rows.length }} 个区域</span><el-button type="primary" :disabled="!canEdit || busy || form.mode === 'create'" @click="openCreate">新增区域</el-button></div>
      <el-form v-if="form.mode" ref="formRef" :model="form" :rules="rules" label-position="top" class="district-form" @submit.prevent="save">
        <b class="district-form__title">{{ form.mode === 'create' ? '新增区域' : `修改区域名称 · ${form.row?.district_code || ''}` }}</b>
        <el-form-item label="区域名称" prop="name"><el-input v-model="form.name" maxlength="128" placeholder="例如：东营区" /></el-form-item>
        <el-form-item label="区域编码" prop="district_code"><el-input v-model="form.district_code" maxlength="64" :disabled="form.mode !== 'create'" placeholder="自定编号，不能重复，例如 DY-DYQ" /><small class="muted">编码用于区分同名区域，保存后不能修改。</small></el-form-item>
        <el-alert v-if="form.error" :title="form.error" type="error" :closable="false" class="wide" />
        <div class="form-actions wide"><el-button :disabled="busy" @click="closeForm">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存</el-button></div>
      </el-form>
      <el-table v-loading="loading" :data="rows" empty-text="还没有区域，请先新增区域">
        <el-table-column prop="name" label="区域名称" min-width="160" />
        <el-table-column prop="district_code" label="区域编码" min-width="140" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" :disabled="!canEdit || busy" @click="openRename(row)">改名</el-button></template></el-table-column>
      </el-table>
      <p v-if="!canEdit" class="muted readonly-note">当前账号只能查看区域。新增和改名需要用户管理的授权权限。</p>
    </section>
    <template #footer><el-button :disabled="busy" @click="close">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.district-dialog{display:flex;flex-direction:column;gap:12px;min-width:0}.district-dialog :deep(.el-alert__title){white-space:normal;overflow-wrap:anywhere;line-height:1.6}
.table-toolbar{margin-bottom:0}.district-form{display:grid;grid-template-columns:1fr 1fr;gap:0 16px;padding:14px 16px 4px;border:1px solid var(--admin-border);border-radius:8px;background:var(--admin-card-soft)}
.district-form__title{grid-column:1/-1;margin-bottom:10px}.district-form .wide{grid-column:1/-1;margin-bottom:12px}.district-form small{display:block;margin-top:4px;font-size:12px;line-height:1.5}
.readonly-note{margin:0;font-size:12px}
@media(max-width:640px){.district-form{grid-template-columns:1fr}}
</style>
