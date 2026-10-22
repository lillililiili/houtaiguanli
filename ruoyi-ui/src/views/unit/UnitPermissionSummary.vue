<script setup>
import { computed } from 'vue'
import { permissionName, permissionLevels } from './permissionDraft'
const props = defineProps({ permissions: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] }, modules: { type: Array, default: () => [] } })
const grants = computed(() => props.permissions.filter(row => row.level !== 'NONE'))
const grantedActions = computed(() => props.actions.filter(row => row.permission_code === 'disposal:direct' ? row.level === 'OP' : ['READ', 'OP', 'AUTH'].includes(row.level)))
function actionName(code) { return props.modules.flatMap(module => module.actions || []).find(action => action.permission_code === code)?.name || '其他已授权动作' }
</script>
<template>
  <section class="permission-summary" aria-label="我的前台权限">
    <h3>前台功能</h3>
    <dl v-if="grants.length"><div v-for="row in grants" :key="row.permission_code"><dt>{{ permissionName(row) }}</dt><dd>{{ permissionLevels.find(level => level.value === row.level)?.label || '无权限' }}</dd></div></dl>
    <p v-else>尚未分配前台功能，请联系平台管理员。</p>
    <h3>业务动作</h3><ul v-if="grantedActions.length"><li v-for="row in grantedActions" :key="row.permission_code">{{ actionName(row.permission_code) }}</li></ul><p v-else>尚未分配业务动作。</p>
  </section>
</template>
<style scoped>.permission-summary{margin-top:20px}.permission-summary h3{font-size:15px}.permission-summary dl{margin:0}.permission-summary dl div{display:flex;gap:24px;padding:12px 0;border-bottom:1px solid var(--admin-border)}.permission-summary dt{min-width:160px}.permission-summary dd{margin:0}.permission-summary p{color:var(--admin-muted)}.permission-summary li{margin:8px 0}</style>
