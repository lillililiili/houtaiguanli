<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ruleSetApi } from '@/api/ruleSets'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('rule:manage'))
const sets = ref([]), loading = ref(false), error = ref(''), busy = ref('')
let sequence = 0, alive = true

const paramLabel = value => ({ CONFIRMED: ['已确认参数', 'success'], DEMO: ['演示参数，未经业务方确认', 'warning'] })[value] || [value || '参数状态未知', 'info']
function versionState(version) {
  if (version.is_active) return ['生效中', 'success']
  if (version.is_shadow) return ['影子验证中', 'warning']
  return version.status_code === 'PUBLISHED' ? ['未启用', 'info'] : ['未发布', 'info']
}
function activeVersion(set) { return set.versions.find(item => item.is_active) }

async function load() {
  const current = ++sequence
  loading.value = true; error.value = ''
  try {
    const page = await ruleSetApi.list()
    const items = page?.items || []
    const versions = await Promise.all(items.map(set => ruleSetApi.versions(set.rule_set_code)))
    if (!alive || current !== sequence) return
    sets.value = items.map((set, index) => ({ ...set, versions: versions[index]?.items || [] }))
  } catch (e) { if (alive && current === sequence) error.value = e.message || '研判规则集读取失败' }
  finally { if (alive && current === sequence) loading.value = false }
}

async function activate(set, version) {
  if (!canManage.value || busy.value || !version.activation_allowed) return
  const current = activeVersion(set)
  let note
  try {
    const result = await ElMessageBox.prompt(
      `启用后，系统按“${set.name}”第 ${version.version_no} 版自动研判${current ? `，原来生效的第 ${current.version_no} 版停止使用` : ''}。${version.param_status === 'DEMO' ? '这一版是演示参数，只适合测试和演示。' : ''}请填写启用原因。`,
      `启用第 ${version.version_no} 版`,
      { confirmButtonText: '启用', cancelButtonText: '取消', inputValue: '系统准备：启用研判规则', inputValidator: value => (Boolean(value?.trim()) && value.trim().length <= 1000) || '请填写启用原因（不超过 1000 字）' })
    note = result.value.trim()
  } catch { return }
  busy.value = version.rule_set_version_id
  try {
    await ruleSetApi.activate(set.rule_set_code, { rule_set_version_id: version.rule_set_version_id, note, expected_version: set.version })
    ElMessage.success(`已启用第 ${version.version_no} 版，系统开始按这一版研判。`)
  } catch (e) {
    ElMessage.error(e.code === 'VERSION_CONFLICT' ? '规则集刚被其他人改过，已刷新，请核对后再启用。' : e.message || '启用失败，请刷新后重试。')
  } finally {
    busy.value = ''
    await load()
  }
}

onMounted(load)
onBeforeUnmount(() => { alive = false; sequence++ })
</script>

<template>
  <el-card class="rule-set-card" shadow="never">
    <template #header>
      <div class="rule-set-heading"><div><b>研判规则集</b><small>决定系统怎样自动判断飞行是否合法、空间是否有风险。没有生效版本时，系统不会自动研判。</small></div><el-button :disabled="loading" @click="load">刷新</el-button></div>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><template #default><el-button :disabled="loading" @click="load">重新读取</el-button></template></el-alert>
    <div v-loading="loading" class="rule-set-list">
      <section v-for="set in sets" :key="set.rule_set_id" class="rule-set">
        <header>
          <div><strong>{{ set.name }}</strong><small>{{ set.rule_set_code }}</small></div>
          <el-tag v-if="activeVersion(set)" type="success">当前生效：第 {{ activeVersion(set).version_no }} 版</el-tag>
          <el-tag v-else type="warning">尚未启用，系统不会按这套规则研判</el-tag>
        </header>
        <el-table :data="set.versions" empty-text="这套规则集还没有版本">
          <el-table-column label="版本" width="90"><template #default="{ row }">第 {{ row.version_no }} 版</template></el-table-column>
          <el-table-column label="说明" min-width="200"><template #default="{ row }">{{ row.description || '—' }}</template></el-table-column>
          <el-table-column label="参数" min-width="170"><template #default="{ row }"><el-tag :type="paramLabel(row.param_status)[1]" effect="plain">{{ paramLabel(row.param_status)[0] }}</el-tag></template></el-table-column>
          <el-table-column label="发布时间" min-width="160"><template #default="{ row }">{{ formatTime(row.published_at) }}</template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="versionState(row)[1]">{{ versionState(row)[0] }}</el-tag></template></el-table-column>
          <el-table-column label="操作" min-width="170"><template #default="{ row }">
            <el-button v-if="row.activation_allowed && canManage" type="primary" size="small" :loading="busy === row.rule_set_version_id" :disabled="Boolean(busy) && busy !== row.rule_set_version_id" @click="activate(set, row)">启用此版本</el-button>
            <span v-else-if="!row.is_active" class="muted block-reason">{{ row.activation_allowed ? '需要规则管理权限' : row.activation_block_reason || '不能启用' }}</span>
          </template></el-table-column>
        </el-table>
      </section>
      <el-empty v-if="!loading && !error && !sets.length" description="还没有研判规则集。规则参数要先经业务方确认，再由实施人员发布。" />
    </div>
    <p v-if="!canManage" class="muted readonly-note">当前账号只能查看研判规则集，启用需要规则管理权限。</p>
  </el-card>
</template>

<style scoped>
.rule-set-card :deep(.el-card__header) { padding: 16px 22px; }.rule-set-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.rule-set-heading > div { display: grid; gap: 5px; min-width: 0; }.rule-set-heading small,.rule-set header small { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.rule-set-list { min-height: 80px; }.rule-set + .rule-set { margin-top: 22px; }.rule-set header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.rule-set header > div { display: grid; gap: 3px; min-width: 0; }.rule-set :deep(.cell) { white-space: normal; overflow-wrap: anywhere; line-height: 1.6; }.block-reason { font-size: 12px; line-height: 1.5; }
.readonly-note { margin: 14px 0 0; font-size: 12px; }
@media (max-width: 680px) { .rule-set-heading,.rule-set header { align-items: flex-start; flex-direction: column; } }
</style>
