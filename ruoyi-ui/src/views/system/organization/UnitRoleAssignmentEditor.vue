<script setup>
import { computed } from 'vue'
import UnitPermissionEditor from '@/views/unit/UnitPermissionEditor.vue'
import UnitPermissionSummary from '@/views/unit/UnitPermissionSummary.vue'
import { makePermissionDraft } from '@/views/unit/permissionDraft'
import { roleDisplayName } from '@/utils/rolePresentation'
import { existingRoleKey, newRoleKey, assignmentRole, rolePermissionDifferences, roleWithinCeiling } from './unitRoleAssignment'

const plan = defineModel({ type: Object, required: true })
const props = defineProps({ config: { type: Object, required: true }, administratorUserId: { type: String, default: '' }, permissions: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] }, disabled: Boolean })
const existing = computed(() => (props.config.unit_roles || []).filter(role => role.enabled !== false && !plan.value.retire_role_codes.includes(role.role_code)))
const copies = computed(() => (props.config.unit_roles || []).filter(role => role.copy_origin))
function addRole() {
  plan.value.new_roles.push({ client_key: crypto.randomUUID(), name: '', description: '', permissions: makePermissionDraft(props.config.catalog?.permissions), actions: [] })
}
function removeRole(key) {
  plan.value.new_roles = plan.value.new_roles.filter(role => role.client_key !== key)
  for (const id of Object.keys(plan.value.assignments)) if (plan.value.assignments[id] === newRoleKey(key)) delete plan.value.assignments[id]
}
function toggleRetire(code, checked) {
  if (props.disabled) return
  plan.value.retire_role_codes = checked ? [...new Set([...plan.value.retire_role_codes, code])] : plan.value.retire_role_codes.filter(item => item !== code)
}
function currentRole(member) { return (props.config.unit_roles || []).find(role => role.role_code === member.role_code) }
function currentLabel(member) {
  const role = currentRole(member)
  const name = role ? roleDisplayName(role) : member.role_name || '未分配角色'
  if (member.user_id === props.administratorUserId && !plan.value.retire_role_codes.includes(member.role_code)) return `${name}（保留绑定，管理员权限由单位授权决定）`
  return role ? name : `${name}（需改为本单位角色）`
}
function needsAssignment(member) { return !currentRole(member) || plan.value.retire_role_codes.includes(member.role_code) }
function differences(member) {
  const target = assignmentRole(plan.value.assignments[member.user_id], props.config.unit_roles || [], plan.value.new_roles)
  if (!target || !plan.value.retire_role_codes.includes(member.role_code)) return []
  return rolePermissionDifferences(roleWithinCeiling(currentRole(member), props.config), roleWithinCeiling(target, props), props.config.catalog)
}
</script>

<template>
  <section class="unit-role-assignment" aria-label="单位人员角色分配">
    <el-alert title="角色由你手动创建和分配，系统不会自动复制原角色。新角色初始无权限，请先在“前台权限”设置单位上限，再配置角色。" type="info" :closable="false" show-icon />
    <header class="assignment-heading"><h3>本次新建角色</h3><el-button :disabled="disabled" @click="addRole">新增单位角色</el-button></header>
    <p v-if="!plan.new_roles.length" class="assignment-help">可选择已有单位角色，也可在此新建后分配。保存成功前不会创建角色或调整人员。</p>
    <details v-for="(role, index) in plan.new_roles" :key="role.client_key" open class="new-unit-role">
      <summary>{{ role.name.trim() || `新角色 ${index + 1}` }}</summary>
      <el-form label-position="top">
        <el-form-item label="角色名称" required><el-input v-model="role.name" maxlength="64" :disabled="disabled" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="role.description" maxlength="500" :disabled="disabled" /></el-form-item>
      </el-form>
      <UnitPermissionEditor v-model:permissions="role.permissions" v-model:actions="role.actions" :modules="config.catalog?.action_modules || []" :ceiling="permissions" :action-ceiling="actions" :disabled="disabled" />
      <el-button type="danger" link :disabled="disabled" @click="removeRole(role.client_key)">移除本次新角色</el-button>
    </details>
    <h3>人员角色分配</h3>
    <p class="assignment-help">单位管理员直接继承单位授权，无需单独分配角色。其他人员（包括停用账号）使用本单位角色；离任管理员也须保留或选择有效的人员角色。</p>
    <el-table :data="config.members || []" max-height="380" empty-text="本单位暂无需要分配角色的前台人员" aria-label="人员角色分配表">
      <el-table-column label="人员" min-width="140"><template #default="{ row }"><strong>{{ row.name }}</strong><small>{{ row.account }}{{ row.status === 'DISABLED' ? ' · 停用' : '' }}</small></template></el-table-column>
      <el-table-column label="当前角色" min-width="170"><template #default="{ row }">{{ currentLabel(row) }}</template></el-table-column>
      <el-table-column label="保存后使用的角色" min-width="250"><template #default="{ row }">
        <span v-if="row.user_id === administratorUserId && !plan.retire_role_codes.includes(row.role_code)">单位管理员 · 继承单位已开放的全部权限</span>
        <el-select v-else v-model="plan.assignments[row.user_id]" clearable filterable :disabled="disabled" :aria-label="`${row.account}的单位角色`" :placeholder="needsAssignment(row) ? '必须选择本单位角色' : '保持当前角色'">
          <el-option v-for="role in existing" :key="role.role_code" :value="existingRoleKey(role.role_code)" :label="roleDisplayName(role)" />
          <el-option v-for="(role, index) in plan.new_roles" :key="role.client_key" :value="newRoleKey(role.client_key)" :label="`${role.name.trim() || `新角色 ${index + 1}`}（本次新建）`" />
        </el-select>
        <ul v-if="differences(row).length" class="permission-differences"><li v-for="text in differences(row)" :key="text">{{ text }}</li></ul>
        <small v-else-if="plan.retire_role_codes.includes(row.role_code) && currentRole(row)?.enabled === false" class="permission-differences">当前副本已停用，请先单独处理角色状态和人员分配。</small>
        <small v-else-if="plan.retire_role_codes.includes(row.role_code) && plan.assignments[row.user_id]" class="assignment-match">权限一致，可迁移后清理旧副本。</small>
      </template></el-table-column>
    </el-table>
    <template v-if="copies.length">
      <h3>清理已有自动副本</h3>
      <p class="assignment-help">按需勾选。所有使用人员必须显式改到权限一致的单位角色，保存时先迁移人员，再删除无引用副本；历史记录保留。清理时保持单位管理员和授权不变，管理员替换及权限调整请另行保存。</p>
      <div v-for="role in copies" :key="role.role_code" class="retire-role">
        <el-checkbox :model-value="plan.retire_role_codes.includes(role.role_code)" :disabled="disabled" @update:model-value="value => toggleRetire(role.role_code, value)">清理 {{ roleDisplayName(role) }}（{{ role.user_count || 0 }} 人）</el-checkbox>
        <details><summary>查看当前有效权限</summary><UnitPermissionSummary :permissions="roleWithinCeiling(role, config).permissions" :actions="roleWithinCeiling(role, config).actions" :modules="config.catalog?.action_modules || []" /></details>
      </div>
    </template>
  </section>
</template>

<style scoped>
.assignment-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:18px}.assignment-heading h3{margin:0}.assignment-help{font-size:13px;line-height:1.6;color:var(--admin-muted)}.new-unit-role,.retire-role{margin:12px 0;padding:14px;border:1px solid var(--admin-border);border-radius:8px}.new-unit-role summary,.retire-role summary{cursor:pointer;line-height:1.6;overflow-wrap:anywhere}.new-unit-role>.el-form{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.unit-role-assignment small{display:block;font-size:12px;color:var(--admin-muted);line-height:1.6}.unit-role-assignment :deep(.el-select){width:100%}.unit-role-assignment :deep(.el-table .cell){overflow-wrap:anywhere;white-space:normal}.permission-differences{padding-left:16px;color:var(--el-color-danger);font-size:12px}.unit-role-assignment .assignment-match{color:var(--el-color-success)}.retire-role details{margin-top:8px}.retire-role :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere}@media(max-width:650px){.new-unit-role>.el-form{grid-template-columns:1fr}}
</style>
