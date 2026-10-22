<script setup>
import { computed } from 'vue'
import { permissionLevels, permissionName, permissionRank, setPermissionLevel } from './permissionDraft'
const props = defineProps({
  permissions: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] },
  modules: { type: Array, default: () => [] }, ceiling: { type: Array, default: null },
  actionCeiling: { type: Array, default: null }, disabled: Boolean
})
const emit = defineEmits(['update:permissions', 'update:actions'])
const ceilingMap = computed(() => new Map((props.ceiling || []).map(item => [item.permission_code, item])))
const allowedActions = computed(() => new Map((props.actionCeiling || []).map(item => [item.permission_code, item.level])))
const grantedActions = computed(() => new Map(props.actions.map(item => [item.permission_code, item.level])))
function maximum(row) { return props.ceiling ? ceilingMap.value.get(row.permission_code)?.level || 'NONE' : 'AUTH' }
function changeLevel(row, level) {
  if (props.disabled || permissionRank(level) > permissionRank(maximum(row))) return
  emit('update:permissions', setPermissionLevel(props.permissions, row.permission_code, level))
}
function granted(code, level) { return code === 'disposal:direct' ? level === 'OP' : ['READ', 'OP', 'AUTH'].includes(level) }
function actionAllowed(code) { return !props.actionCeiling || granted(code, allowedActions.value.get(code)) }
function changeAction(code, enabled) {
  if (props.disabled || !actionAllowed(code)) return
  const next = props.actions.filter(item => item.permission_code !== code)
  next.push({ permission_code: code, level: enabled ? 'OP' : 'NONE' })
  emit('update:actions', next)
}
function moduleName(group) { return group.module_code === 'disposal' ? '反制授权' : group.module_name || '业务动作' }
</script>

<template>
  <div class="unit-permission-editor">
    <el-tabs>
      <el-tab-pane label="前台功能">
        <el-table :data="permissions" empty-text="暂无可配置的前台功能">
          <el-table-column label="功能" min-width="150"><template #default="{ row }">{{ permissionName(row) }}</template></el-table-column>
          <el-table-column label="权限" min-width="160"><template #default="{ row }">
            <el-select :model-value="row.level" :aria-label="`${permissionName(row)}权限`" :disabled="disabled || maximum(row) === 'NONE'" @update:model-value="value => changeLevel(row, value)">
              <el-option v-for="level in permissionLevels" :key="level.value" :value="level.value" :label="level.label" :disabled="permissionRank(level.value) > permissionRank(maximum(row))" />
            </el-select>
          </template></el-table-column>
          <el-table-column v-if="ceiling" label="单位上限" min-width="100"><template #default="{ row }">{{ permissionLevels.find(item => item.value === maximum(row))?.label }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="业务动作">
        <el-empty v-if="!modules.length" description="暂无可配置的业务动作" />
        <section v-for="group in modules" :key="group.module_code" class="action-group">
          <h3>{{ moduleName(group) }}</h3>
          <div v-for="action in group.actions" :key="action.permission_code" class="action-row">
            <el-checkbox :model-value="granted(action.permission_code, grantedActions.get(action.permission_code))" :disabled="disabled || !actionAllowed(action.permission_code)" @update:model-value="value => changeAction(action.permission_code, value)">{{ action.name }}</el-checkbox>
            <span v-if="!actionAllowed(action.permission_code)" class="limit-note">单位未开放</span>
            <span v-else-if="action.permission_code === 'disposal:direct'" class="limit-note">免逐次审批，仍受业务条件和设备权限约束</span>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.unit-permission-editor{min-width:0}.action-group{margin:0 0 20px}.action-group h3{margin:0 0 10px;font-size:14px}.action-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;min-height:38px}.limit-note{color:var(--admin-muted);font-size:12px}.action-row :deep(.el-checkbox__label){white-space:normal}.unit-permission-editor :deep(.el-select){width:100%}
</style>
