<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Bell } from '@element-plus/icons-vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
import { useAuthStore } from '@/stores/auth.js';
import { formatTime } from '@/utils/format.js';
import { maintenanceLocation, maintenanceMeta } from '@/views/operations/maintenance/useMaintenanceWorkflow.js';

const auth = useAuthStore(), router = useRouter();
const visible = ref(false), loading = ref(false), error = ref(''), items = ref([]), unread = ref(null), page = ref(1), total = ref(0), opening = ref('');
const canOpen = computed(() => auth.hasMenu('commission') && auth.hasPermission('commissioning.read') && auth.hasPermission('devices.read'));
let alive = true, generation = 0, timer;
async function reload() {
  if (loading.value || opening.value) return;
  const current = ++generation; loading.value = true;
  try {
    const data = await deviceMaintenanceApi.messages({ page: page.value, size: 6 });
    if (!alive || current !== generation) return;
    items.value = data.items || []; unread.value = data.unread_count; total.value = data.total; error.value = '';
  } catch (e) {
    if (alive && current === generation) { error.value = e.message || '消息读取失败'; unread.value = null; items.value = []; }
  } finally { if (alive && current === generation) loading.value = false; }
}
async function open(item) {
  if (!canOpen.value || opening.value) return;
  opening.value = item.task_id; error.value = '';
  try {
    await deviceMaintenanceApi.readMessage(item.task_id);
    if (!alive) return;
    visible.value = false;
    await router.push(maintenanceLocation(item));
  } catch (e) { if (alive) error.value = e.message || '消息打开失败，请重试'; }
  finally { opening.value = ''; if (alive) void reload(); }
}
function changed() { if (alive) void reload(); }
function changePage(value) { page.value = value; void reload(); }
function toggle() { visible.value = !visible.value; if (visible.value) void reload(); }
function dismiss(event) {
  if (event.type === 'keydown') { if (event.key === 'Escape') visible.value = false; return; }
  if (!event.target.closest?.('.maintenance-bell, .maintenance-message-popover')) visible.value = false;
}
onMounted(() => {
  void reload();
  timer = window.setInterval(() => { if (!document.hidden) void reload(); }, 30000);
  window.addEventListener('admin:maintenance-changed', changed);
  document.addEventListener('pointerdown', dismiss);
  document.addEventListener('keydown', dismiss);
});
onBeforeUnmount(() => { alive = false; generation++; window.clearInterval(timer); window.removeEventListener('admin:maintenance-changed', changed); document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', dismiss); });
</script>

<template>
  <el-popover :visible="visible" placement="bottom-end" :width="380" trigger="click" popper-class="maintenance-message-popover">
    <template #reference><span><button class="maintenance-bell" type="button" :aria-expanded="visible" :aria-label="unread == null ? '运维消息，未读数未知' : `运维消息，${unread}条未读`" @click.stop="toggle"><el-badge :value="unread || 0" :max="99" :hidden="!unread"><el-icon :size="21"><Bell /></el-icon></el-badge></button></span></template>
    <div class="message-heading"><b>运维消息</b><el-button link type="primary" :loading="loading" @click="reload">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="!canOpen" class="muted">查看设备调测需要设备读取和接入调测页面权限。</p>
    <div v-loading="loading" class="maintenance-message-list">
      <article v-for="item in items" :key="item.task_id" :class="{unread: !item.read_at}">
        <div class="message-title"><b>{{ item.device_name }}</b><el-tag :type="maintenanceMeta(item.workflow_state).tone" size="small">{{ maintenanceMeta(item.workflow_state).label }}</el-tag></div>
        <p>{{ item.reason }}</p><div class="message-footer"><time>{{ formatTime(item.reported_at) }}</time><el-button link type="primary" :disabled="!canOpen || !!opening" :loading="opening===item.task_id" @click="open(item)">{{ item.workflow_state === 'COMPLETED' || item.workflow_state === 'LEGACY_HANDLED' ? '查看结果' : '去处理' }}</el-button></div>
      </article>
      <el-empty v-if="!loading && !items.length && !error" description="暂无运维消息" :image-size="50" />
    </div>
    <el-pagination v-if="total>6" :current-page="page" :page-size="6" :total="total" layout="prev, pager, next" @current-change="changePage" />
    <p class="message-footnote">阅读消息不代表已处理，处理进度以待办为准。</p>
  </el-popover>
</template>

<style scoped>
.maintenance-bell{width:38px;height:38px;display:grid;place-items:center;border:0;border-radius:8px;background:transparent;color:#60738c}.maintenance-bell:hover{color:var(--admin-primary);background:var(--admin-primary-soft)}
.message-heading,.message-title,.message-footer{display:flex;align-items:center;justify-content:space-between;gap:10px}.message-heading{padding-bottom:10px;border-bottom:1px solid var(--admin-border)}.message-title{align-items:flex-start}.message-title b{overflow-wrap:anywhere}.maintenance-message-list{max-height:420px;overflow:auto}.maintenance-message-list article{padding:14px 8px;border-bottom:1px solid #edf0f5}.maintenance-message-list article.unread{background:#f4f8fe}.maintenance-message-list p{font-size:12px;line-height:1.7;color:#6b7b90;overflow-wrap:anywhere}.message-footer time,.message-footnote{font-size:11px;color:#7b8b9f}.message-footnote{margin-bottom:0;line-height:1.7}
</style>
<style>.maintenance-message-popover{max-width:calc(100vw - 24px);}</style>
