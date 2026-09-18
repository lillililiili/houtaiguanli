<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DirectoryRecordEditor from './DirectoryRecordEditor.vue'
import { directoryApi } from '@/api/organizationDirectory'
const props = defineProps({ visible: Boolean, row: { type: Object, default: null }, parent: { type: Object, default: null }, mode: { type: String, default: 'view' }, canReadDirectory: Boolean, canEditDirectory: Boolean, canEditBasic: Boolean, organizations: { type: Array, default: () => [] } })
const emit = defineEmits(['update:visible', 'saved'])
const profile = ref(null)
const targetOrgId = ref('')
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const canEditProfile = computed(() => props.canEditDirectory || props.canEditBasic)
const basicOnly = computed(() => props.mode === 'view' ? !props.canReadDirectory : !props.canReadDirectory || !props.canEditDirectory)
const title = computed(() => ({ view: '查看单位', edit: '编辑单位', create: '新增单位' }[props.mode] || '查看单位'))
const showForm = computed(() => props.mode === 'create' || Boolean(profile.value))
let sequence = 0
async function loadProfile() {
  const current = ++sequence
  loading.value = true; error.value = ''; profile.value = null
  try {
    const data = props.canReadDirectory ? await directoryApi.profile(targetOrgId.value) : props.row
    if (current !== sequence) return
    profile.value = data
  } catch (e) { if (current === sequence) error.value = e.message || '单位资料加载失败，请重试。' }
  finally { if (current === sequence) loading.value = false }
}
watch(() => [props.visible, props.row, props.mode], async () => {
  sequence++
  if (!props.visible) return
  targetOrgId.value = props.row?.org_id || ''
  profile.value = null; error.value = ''; loading.value = false
  if (props.mode !== 'create' && props.row) await loadProfile()
}, { immediate: true })
function close() { if (!busy.value) emit('update:visible', false) }
function saved(result) { profile.value = result; emit('saved', result) }
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <el-dialog :model-value="visible" :title="title" width="min(760px, calc(100vw - 32px))" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="value => !value && close()">
    <section v-loading="loading" class="unit-dialog">
      <ErrorAlert :message="error" @retry="loadProfile" />
      <DirectoryRecordEditor v-if="showForm" :key="`${mode}-${profile?.org_id || parent?.org_id || 'new'}`" embedded :readonly="mode === 'view'" kind="profile" :visible="true" :row="mode === 'create' ? null : profile" :parent="parent" :organizations="organizations" :basic-only="basicOnly" :can-edit="mode !== 'view' && canEditProfile" @update:visible="value => !value && close()" @busy="busy = $event" @saved="saved" @refresh="loadProfile" />
      <p v-if="mode === 'view' && profile && !canReadDirectory" class="muted">当前权限可查看单位基本信息，完整资料需要单位资料读取权限。</p>
    </section>
    <template v-if="mode === 'view' || !showForm" #footer>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.unit-dialog{min-height:140px;min-width:0}.muted{color:var(--admin-muted);font-size:12px;margin:5px 0;line-height:1.5}:deep(.el-alert__title){white-space:normal;overflow-wrap:anywhere;word-break:break-word}
</style>
