import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
import { newIdempotencyKey } from '@/services/apiClient.js';

export const maintenanceStates = {
  PENDING: { label: '待处理', tone: 'warning', step: 0 },
  PROCESSING: { label: '处理中', tone: '', step: 1 },
  PENDING_VERIFICATION: { label: '待恢复核验', tone: 'warning', step: 2 },
  COMPLETED: { label: '已完成', tone: 'success', step: 4 },
  LEGACY_HANDLED: { label: '历史已反馈', tone: 'info', step: -1 }
};
export const maintenanceState = task => task?.workflow_state || (task?.status === 'HANDLED' ? 'LEGACY_HANDLED' : 'PENDING');
export const maintenanceMeta = state => maintenanceStates[state] || { label: '状态未知', tone: 'info', step: -1 };
export const maintenanceLocation = task => ({ path: '/operations/commission', query: { maintenance_task_id: task.task_id, device_id: task.device_id } });

export function useMaintenanceWorkflow(taskId) {
  const workflow = ref(null), loading = ref(false), error = ref(''), busy = ref(false), actionError = ref(''), pending = ref(null);
  let alive = true, generation = 0, context = 0;
  const can = action => !error.value && !loading.value && !busy.value && !pending.value && Boolean(workflow.value?.allowed_actions?.includes(action));
  const editable = computed(() => workflow.value?.state === 'PROCESSING' && can('SAVE_PROGRESS'));
  async function load() {
    const id = taskId.value, current = ++generation;
    if (!id) { workflow.value = null; error.value = ''; loading.value = false; return null; }
    loading.value = true;
    try {
      const result = await deviceMaintenanceApi.workflow(id);
      if (!alive || current !== generation || id !== taskId.value) return null;
      if (result?.task?.task_id !== id) throw new Error('返回的运维待办不匹配，请刷新重试。');
      workflow.value = result; error.value = ''; return result;
    } catch (reason) {
      if (alive && current === generation) { workflow.value = null; error.value = reason.message || '待办读取失败'; }
      return null;
    } finally { if (alive && current === generation) loading.value = false; }
  }
  async function submit(request) {
    if (busy.value || !request || request.id !== taskId.value || request.context !== context) return null;
    busy.value = true; actionError.value = '';
    try {
      const result = await deviceMaintenanceApi.act(request.id, request.body, request.key);
      if (!alive || request.id !== taskId.value || request.context !== context) return null;
      if (result?.task?.task_id !== request.id) throw new Error('处理结果未能确认，请按原请求重试。');
      ++generation; loading.value = false; workflow.value = result; pending.value = null; error.value = '';
      window.dispatchEvent(new CustomEvent('admin:maintenance-changed'));
      return result;
    } catch (reason) {
      if (!alive || request.id !== taskId.value || request.context !== context) return null;
      actionError.value = reason.message || '处理结果未能确认';
      if (!reason.status || reason.status >= 500) {
        pending.value = request;
        actionError.value += '。结果尚未确认，请保留原请求重试。';
      } else {
        pending.value = null;
        if (reason.status === 409 || reason.status === 403 || reason.status === 404) await load();
      }
      return null;
    } finally { if (alive && request.context === context) busy.value = false; }
  }
  async function act(action, fields = {}) {
    if (!can(action)) return null;
    const request = { id: taskId.value, context, body: { ...fields, action, expected_version: workflow.value.version }, key: newIdempotencyKey('maintenance') };
    pending.value = request;
    return submit(request);
  }
  const retry = () => submit(pending.value);
  watch(taskId, () => { ++generation; ++context; busy.value = false; workflow.value = null; pending.value = null; actionError.value = ''; void load(); }, { immediate: true });
  onBeforeUnmount(() => { alive = false; generation++; });
  return { workflow, loading, error, busy, actionError, pending, editable, can, load, act, retry };
}
