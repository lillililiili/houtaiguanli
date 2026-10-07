import { afterEach, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import ElementPlus from 'element-plus';
import EoTaskPanel from '@/views/operations/EoTaskPanel.vue';
import { deviceApi } from '@/api/devices.js';

const permission = vi.hoisted(() => ({ allowed: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => permission.allowed }) }));
vi.mock('@/api/devices.js', () => ({ deviceApi: { endEoTrack: vi.fn() } }));
let app, host, props;
async function settle() { for (let n = 0; n < 12; n++) { await Promise.resolve(); await nextTick(); } }
async function mount(status = 'OPEN') {
  host = document.createElement('div'); document.body.append(host);
  props = reactive({ task: { task_id: crypto.randomUUID(), status, created_at: Date.now() }, onRefresh: vi.fn() });
  app = createApp({ render: () => h(EoTaskPanel, props) }); app.use(ElementPlus); app.mount(host); await settle();
}
afterEach(() => { app?.unmount(); host?.remove(); vi.resetAllMocks(); permission.allowed = true; });
it('读取不结束任务，点击提交一次且等待回读和设备回执', async () => {
  await mount(); expect(deviceApi.endEoTrack).not.toHaveBeenCalled();
  let finish; deviceApi.endEoTrack.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  host.querySelector('button').click(); host.querySelector('button').click(); await settle();
  expect(deviceApi.endEoTrack).toHaveBeenCalledExactlyOnceWith(props.task.task_id);
  finish(); await settle(); expect(props.onRefresh).toHaveBeenCalledOnce();
  expect(host.textContent).toContain('占用设备');
  props.task.status = 'ENDING'; await settle();
  expect(host.textContent).toContain('等待结束回执'); expect(host.querySelector('button').textContent).toContain('重试结束跟踪');
});
it('无操作权限不显示结束动作', async () => {
  permission.allowed = false; await mount(); expect(host.querySelector('button')).toBeNull();
});
it('失败保留占用并回读，不伪造空闲', async () => {
  await mount(); deviceApi.endEoTrack.mockRejectedValue(new Error('停止结果未知'));
  host.querySelector('button').click(); await settle();
  expect(host.textContent).toContain('停止结果未知'); expect(host.textContent).toContain('占用设备');
  expect(props.onRefresh).toHaveBeenCalledOnce();
});
it('结束待确认允许对同一任务重试，仍保留占用', async () => {
  await mount('ENDING'); deviceApi.endEoTrack.mockResolvedValue({});
  host.querySelector('button').click(); await settle();
  expect(deviceApi.endEoTrack).toHaveBeenCalledExactlyOnceWith(props.task.task_id);
  expect(host.textContent).toContain('设备仍被占用');
});
