import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import IncidentRebootPanel from '@/views/operations/maintenance/IncidentRebootPanel.vue';
import { deviceApi } from '@/api/devices.js';
vi.mock('@/api/devices.js', () => ({ deviceApi: { rebootIncident: vi.fn() } }));
let app, host;
async function settle() { for (let index = 0; index < 20; index++) { await Promise.resolve(); await nextTick(); } }
async function mount(incidents) { host = document.createElement('div'); document.body.append(host); app = createApp({ render: () => h(IncidentRebootPanel, { incidents, deviceName: '测试设备' }) }); app.use(ElementPlus); app.mount(host); await settle(); }
const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text);
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; vi.resetAllMocks(); });
describe('按需设备重启', () => {
  it('没有服务端协议能力就不显示重启入口，不猜测设备类型', async () => { await mount([{ incident_id: 'x', allowed_actions: ['VERIFY_RECOVERY'] }]); expect(button('重启设备')).toBeUndefined(); });
  it('打开或取消确认框均不执行；事由必填', async () => {
    await mount([{ incident_id: 'x', reason: '故障', allowed_actions: ['REBOOT'] }]); button('重启设备').click(); await settle();
    expect(button('确认发送重启指令').disabled).toBe(true); expect(deviceApi.rebootIncident).not.toHaveBeenCalled();
    button('取消').click(); await settle(); expect(deviceApi.rebootIncident).not.toHaveBeenCalled();
  });
  it('结果未知时按原事由和幂等键重试，不以指令成功代替设备恢复', async () => {
    deviceApi.rebootIncident.mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({ status: 'SUCCEEDED' });
    await mount([{ incident_id: 'independent-event', reason: '故障', allowed_actions: ['REBOOT'] }]); button('重启设备').click(); await settle();
    const reason = document.querySelector('#maintenance-reboot-reason'); reason.value = '确认现场设备允许重启'; reason.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    button('确认发送重启指令').click(); await settle(); expect(button('取消').disabled).toBe(true);
    button('按原请求重试确认').click(); await settle();
    expect(deviceApi.rebootIncident.mock.calls[1]).toEqual(deviceApi.rebootIncident.mock.calls[0]);
    expect(host.textContent).toContain('设备恢复仍以系统核验结果为准');
  });
});
