import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, ref } from 'vue';
import { useMaintenanceWorkflow } from '@/views/operations/maintenance/useMaintenanceWorkflow.js';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';

vi.mock('@/api/deviceMaintenance.js', () => ({ deviceMaintenanceApi: { workflow: vi.fn(), act: vi.fn() } }));
let app, host, model, taskId;
const data = (id, state = 'PENDING', version = 1) => ({ task: { task_id: id, device_id: `device-${id}` }, state, version, allowed_actions: ['START'], events: [] });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(id = 'A') {
  taskId = ref(id); host = document.createElement('div');
  app = createApp({ setup() { model = useMaintenanceWorkflow(taskId); return () => null; } }); app.mount(host); await settle();
}
afterEach(() => { app?.unmount(); host?.remove(); vi.resetAllMocks(); });
describe('运维状态由后端驱动', () => {
  it('读取待办不会接手、创建调测或写处理状态', async () => {
    deviceMaintenanceApi.workflow.mockResolvedValue(data('A')); await mount();
    expect(model.workflow.value.state).toBe('PENDING'); expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it('深链切换后丢弃前一待办迟到的数据', async () => {
    let finish; deviceMaintenanceApi.workflow.mockImplementation(id => id === 'A' ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(data('B')));
    await mount(); taskId.value = 'B'; await settle(); finish(data('A')); await settle();
    expect(model.workflow.value.task.task_id).toBe('B');
  });
  it('网络结果未知时重试沿用原动作、版本和幂等键', async () => {
    deviceMaintenanceApi.workflow.mockResolvedValue(data('A'));
    deviceMaintenanceApi.act.mockRejectedValueOnce({ status: 0, code: 'NETWORK_ERROR', message: '连接中断' }).mockResolvedValueOnce(data('A', 'PROCESSING', 2));
    await mount(); await model.act('START');
    expect(model.workflow.value.state).toBe('PENDING'); expect(model.pending.value).toBeTruthy();
    await model.retry();
    expect(deviceMaintenanceApi.act.mock.calls[1]).toEqual(deviceMaintenanceApi.act.mock.calls[0]);
    expect(model.workflow.value.state).toBe('PROCESSING'); expect(model.pending.value).toBeNull();
  });
  it('版本冲突刷新服务端状态，不能展示为操作成功', async () => {
    deviceMaintenanceApi.workflow.mockResolvedValueOnce(data('A')).mockResolvedValueOnce(data('A', 'PROCESSING', 2));
    deviceMaintenanceApi.act.mockRejectedValue({ status: 409, code: 'MAINTENANCE_TASK_CHANGED', message: '待办已经更新' });
    await mount(); const result = await model.act('START');
    expect(result).toBeNull(); expect(model.workflow.value.version).toBe(2); expect(model.actionError.value).toContain('待办已经更新');
  });
  it('拒绝发起后端未允许的动作', async () => {
    deviceMaintenanceApi.workflow.mockResolvedValue(data('A')); await mount(); await model.act('COMPLETE', { note: '完成' });
    expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
});
