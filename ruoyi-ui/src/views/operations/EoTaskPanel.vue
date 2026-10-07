<script setup>
import { computed, ref } from 'vue';
import { deviceApi } from '@/api/devices.js';
import { useAuthStore } from '@/stores/auth.js';
import { formatTime } from '@/utils/format.js';

const props = defineProps({ task: { type: Object, default: null } });
const emit = defineEmits(['refresh']);
const auth = useAuthStore();
const busy = ref(false), error = ref('');
const canEnd = computed(() => auth.hasPermission('devices.op') && ['OPEN', 'ENDING'].includes(props.task?.status));
async function endTracking() {
  if (busy.value || !canEnd.value) return;
  const taskId = props.task.task_id;
  busy.value = true; error.value = '';
  try { await deviceApi.endEoTrack(taskId); }
  catch (e) { error.value = e.message || '结束请求结果未确认，请刷新核对'; }
  finally { busy.value = false; emit('refresh'); }
}
</script>

<template>
  <section class="detail-section" aria-label="当前光电任务">
    <h3>光电任务</h3>
    <template v-if="task">
      <p>{{ task.status === 'ENDING' ? '等待结束回执，设备仍被占用' : '当前有跟踪任务占用设备' }}</p>
      <p>开始时间：{{ formatTime(task.created_at) }}</p>
      <el-button v-if="canEnd" :loading="busy" @click="endTracking">{{ task.status === 'ENDING' ? '重试结束跟踪' : '结束当前跟踪' }}</el-button>
      <p v-if="task.status === 'ENDING'">恢复设备连接后可重试；处理中不会重复下发，收到停止回执后才释放设备。</p>
      <p v-if="error" class="danger-text" role="alert">{{ error }}</p>
    </template>
    <p v-else>暂无跟踪任务</p>
  </section>
</template>
