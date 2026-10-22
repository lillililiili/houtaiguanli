<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DirectoryRecordEditor from './DirectoryRecordEditor.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { contactRoles, labelOf } from '@/utils/directoryConfiguration'
import { formatTime } from '@/utils/format'

const props = defineProps({ kind: { type: String, required: true }, organization: { type: Object, required: true }, canEdit: Boolean })
const emit = defineEmits(['saved', 'busy'])
const contacts = computed(() => props.kind === 'contact')
const noun = computed(() => contacts.value ? '联系人' : '来源映射')
const filters = reactive({ keyword: '', page: 1, size: 20 })
const items = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const editor = reactive({ visible: false, row: null, readonly: true })
let sequence = 0
watch(busy, value => emit('busy', value), { flush: 'sync' })

async function load() {
  const current = ++sequence
  // 保留已知总数，避免翻页加载时分页器因 total=0 把当前页钳回第 1 页。
  items.value = []; error.value = ''; loading.value = true
  try {
    const method = contacts.value ? 'contacts' : 'bindings'
    const result = await directoryApi[method]({ org_id: props.organization.org_id, keyword: filters.keyword.trim(), page: filters.page, size: filters.size })
    if (current !== sequence) return
    items.value = result.items || []; total.value = result.total || 0
  } catch (e) { if (current === sequence) error.value = e.message || `${noun.value}读取失败，请重试。` }
  finally { if (current === sequence) loading.value = false }
}
function search() { filters.page = 1; load() }
function open(row = null, readonly = false) {
  if (busy.value || (!readonly && !props.canEdit)) return
  Object.assign(editor, { visible: true, row, readonly })
}
async function saved() { editor.visible = false; await load(); emit('saved') }
function refresh() { editor.visible = false; load() }
watch(() => [props.organization.org_id, props.kind], () => {
  sequence++; editor.visible = false; busy.value = false
  filters.keyword = ''; filters.page = 1; total.value = 0
  load()
}, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <section class="directory-panel" :aria-label="noun">
    <div class="directory-toolbar">
      <el-input v-model="filters.keyword" :disabled="busy" clearable :placeholder="contacts ? '姓名或联系电话' : '来源系统或外部单位编码'" @keyup.enter="search" @clear="search" />
      <el-button :disabled="busy || loading" @click="search">查询{{ noun }}</el-button>
      <el-button :disabled="busy || loading" @click="load">刷新{{ noun }}</el-button>
      <el-button v-if="canEdit" type="primary" :disabled="busy || loading" @click="open()">新增{{ noun }}</el-button>
    </div>
    <ErrorAlert :message="error" @retry="load" />
    <el-table v-loading="loading" :data="items" :row-key="contacts ? 'contact_id' : 'binding_id'" max-height="330" :empty-text="error ? '读取失败，请重试' : `本单位暂无${noun}`">
      <template v-if="contacts">
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column label="联系用途" min-width="130"><template #default="{ row }">{{ (row.roles || []).map(role => labelOf(contactRoles, role)).join('、') || '未填写' }}</template></el-table-column>
        <el-table-column prop="phone" label="联系电话" min-width="130"><template #default="{ row }">{{ row.phone || '未填写' }}</template></el-table-column>
        <el-table-column label="核实时间" min-width="160"><template #default="{ row }">{{ formatTime(row.verified_at) }}</template></el-table-column>
      </template>
      <template v-else>
        <el-table-column label="来源系统" min-width="150"><template #default="{ row }">{{ row.source_name || row.source_id }}</template></el-table-column>
        <el-table-column prop="external_org_code" label="外部单位编码" min-width="170" />
        <el-table-column label="对应单位" min-width="130"><template #default="{ row }">{{ row.org_name || organization.name }}</template></el-table-column>
      </template>
      <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }">
        <el-button link type="primary" :disabled="busy" @click="open(row, true)">查看</el-button>
        <el-button v-if="canEdit" link type="primary" :disabled="busy" @click="open(row)">编辑</el-button>
      </template></el-table-column>
    </el-table>
    <div class="directory-footer">
      <p>{{ canEdit ? `${noun}不再使用时可在编辑中停用，历史记录保留。` : `当前账号可查看${noun}，维护需要单位资料维护权限。` }}</p>
      <el-pagination v-model:current-page="filters.page" :page-size="filters.size" :total="total" :disabled="busy || loading" layout="total, prev, pager, next" @current-change="load" />
    </div>
    <DirectoryRecordEditor v-if="editor.visible" :key="`${kind}-${editor.row?.contact_id || editor.row?.binding_id || 'new'}`" v-model:visible="editor.visible" :kind="kind" :row="editor.row" :organization="organization" :readonly="editor.readonly" :can-edit="canEdit && !editor.readonly" lock-organization @busy="busy = $event" @saved="saved" @refresh="refresh" />
  </section>
</template>

<style scoped>
.directory-panel{min-width:0}.directory-toolbar{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.directory-toolbar .el-input{flex:1;min-width:180px}.directory-toolbar .el-button+.el-button{margin-left:0}.directory-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:12px}.directory-footer p{font-size:12px;color:var(--admin-muted);margin:0}:deep(.cell){overflow-wrap:anywhere}:deep(.el-alert){margin:12px 0}
</style>
