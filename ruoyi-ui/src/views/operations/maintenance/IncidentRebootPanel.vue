<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { deviceApi } from '@/api/devices.js';
import { newIdempotencyKey } from '@/services/apiClient.js';
const props = defineProps({ incidents: { type: Array, default: () => [] }, deviceName: String, disabled: Boolean });
const emit = defineEmits(['lock', 'refresh']);
const candidates = computed(() => props.incidents.filter(item => item.allowed_actions?.includes('REBOOT')));
const visible = ref(false), selected = ref(null), reason = ref(''), busy = ref(false), pending = ref(null), error = ref(''), result = ref(null);
let alive = true;
watch([visible, busy, pending], () => emit('lock', visible.value || busy.value || Boolean(pending.value)));
function open() { if (props.disabled || busy.value || pending.value) return; selected.value = candidates.value.length === 1 ? candidates.value[0].incident_id : null; reason.value = ''; error.value = ''; result.value = null; visible.value = true; }
async function submit() {
  if (busy.value || props.disabled || !reason.value.trim() || reason.value.trim().length > 1000) return;
  if (!pending.value && !candidates.value.some(item => item.incident_id === selected.value)) { error.value = '请选择当前允许重启的关联异常。'; return; }
  if (!pending.value) pending.value = { id: selected.value, body: { reason: reason.value.trim() }, key: newIdempotencyKey('maintenance-reboot') };
  const request = pending.value; busy.value = true; error.value = '';
  try {
    const command = await deviceApi.rebootIncident(request.id, request.body, request.key);
    if (!alive) return;
    result.value = command; pending.value = null; visible.value = false;
    emit('refresh');
  } catch (failure) {
    if (!alive) return;
    error.value = failure.message || '重启请求结果未确认';
    if (failure.status && failure.status < 500) { pending.value = null; emit('refresh'); }
    else error.value += '。请按原请求重试确认，不要重复创建重启指令。';
  } finally { if (alive) busy.value = false; }
}
onBeforeUnmount(() => { alive = false; });
</script>
<template>
  <div v-if="candidates.length || result" class="incident-reboot">
    <p v-if="candidates.length">设备协议支持重启时，可按需发送。重启会暂时中断设备工作，设备恢复由系统继续核验。</p>
    <el-button v-if="candidates.length" type="warning" plain :disabled="disabled || busy || !!pending" @click="open">重启设备</el-button>
    <el-alert v-if="result" :title="`重启指令：${({QUEUED:'排队中',SENT:'已下发',ACCEPTED:'设备已接收',SUCCEEDED:'设备回执成功',FAILED:'失败',TIMED_OUT:'超时'})[result.status] || '已记录'}。设备恢复仍以系统核验结果为准。`" type="info" :closable="false" />
    <el-dialog v-model="visible" :title="`确认重启 · ${deviceName || '当前设备'}`" width="min(500px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy && !pending" :show-close="!busy && !pending">
      <el-alert title="重启会中断当前设备工作，请确认已完成必要的现场协调。" description="指令成功不代表设备异常恢复，系统将继续检查新的健康与告警上报。" type="warning" :closable="false" show-icon />
      <template v-if="candidates.length>1"><label for="maintenance-reboot-incident">关联异常（必选）</label><el-select id="maintenance-reboot-incident" v-model="selected" :disabled="busy || !!pending" placeholder="选择本次重启处理的异常"><el-option v-for="incident in candidates" :key="incident.incident_id" :value="incident.incident_id" :label="incident.reason || incident.incident_id" /></el-select></template>
      <label for="maintenance-reboot-reason">重启原因（必填）</label><el-input id="maintenance-reboot-reason" v-model="reason" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="busy || !!pending" placeholder="说明已检查的情况以及需要重启的原因" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template #footer><el-button :disabled="busy || !!pending" @click="visible=false">取消</el-button><el-button type="warning" :loading="busy" :disabled="disabled || !reason.trim() || !selected" @click="submit">{{ pending?'按原请求重试确认':'确认发送重启指令' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>.incident-reboot{margin-top:12px}.incident-reboot p{line-height:1.7}.incident-reboot label{display:block;margin:16px 0 8px}.incident-reboot :deep(.el-alert){margin-top:12px}</style>
