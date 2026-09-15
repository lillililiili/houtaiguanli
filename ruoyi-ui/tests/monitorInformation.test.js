import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import MonitorView from '@/views/operations/MonitorView.vue';
import { deviceApi } from '@/api/devices.js';

vi.mock('echarts', () => ({ init: vi.fn() }));
vi.mock('@/api/devices.js', () => ({ deviceApi: Object.fromEntries(
  ['overview', 'tree', 'incidents', 'state', 'history', 'events', 'protocolStatus', 'targets', 'information'].map(key => [key, vi.fn()])
) }));
let app, host;
const devices = ['A', 'B'].map(id => ({ device_id: id, device_no: id, name: `设备${id}`, channel: 'MQTT' }));
const info = id => ({ device_id: id, device_no: id, name: `设备${id}`, source_mode: 'live', generated_at: 100000,
  sections: [{ code: 'work_parameters', title: `${id}工参`, source: '协议 A V8.6', fields: [
    { key: 'temperature', label: '温度', value: 0, unit: '℃', status: 'RECEIVED', required: true },
    { key: 'voltage', label: '电压', status: 'NOT_REPORTED', required: true }
  ] }], sample_sections: [], notes: [] });
async function settle() { for (let i = 0; i < 18; i++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(MonitorView); app.use(ElementPlus); app.mount(host); await settle();
}
async function click(text) { [...host.querySelectorAll('button')].find(button => button.textContent.includes(text)).click(); await settle(); }
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  deviceApi.overview.mockResolvedValue({ total: 2 });
  deviceApi.tree.mockResolvedValue({ items: devices });
  deviceApi.incidents.mockResolvedValue({ items: [] });
  deviceApi.state.mockImplementation(async id => ({ connectivity: 'ONLINE', health_code: 'GOOD', metrics: [{ code: 'temperature_c', label: `${id}温度`, value: 0 }] }));
  deviceApi.history.mockResolvedValue({ points: [] });
  deviceApi.events.mockResolvedValue({ items: [], next_seq: 0 });
  deviceApi.protocolStatus.mockResolvedValue({});
  deviceApi.information.mockImplementation(async id => info(id));
});
afterEach(() => { app?.unmount(); host?.remove(); vi.useRealTimers(); vi.resetAllMocks(); });

describe('实时监测完整设备信息', () => {
  it('首次仅获取一次完整信息，按同一轮询刷新并保留缺失字段及来源', async () => {
    await mount();
    expect(deviceApi.information).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('当前运行信息');
    expect(host.textContent).toContain('协议 A V8.6');
    expect(host.textContent).not.toContain('协议必填项');
    expect(host.textContent).toContain('未上报');
    await vi.advanceTimersByTimeAsync(2000); await settle();
    expect(deviceApi.information).toHaveBeenCalledTimes(2);
  });

  it('暂停轮询后允许手动刷新和查看另一设备，继续后恢复轮询', async () => {
    await mount(); await click('暂停刷新');
    await vi.advanceTimersByTimeAsync(4000); await settle();
    expect(deviceApi.information).toHaveBeenCalledTimes(1);
    await click('刷新信息');
    expect(deviceApi.information).toHaveBeenCalledTimes(2);
    await click('设备B');
    expect(host.textContent).toContain('B工参');
    expect(host.textContent).not.toContain('A工参');
    await click('继续刷新');
    const calls = deviceApi.information.mock.calls.length;
    await vi.advanceTimersByTimeAsync(2000); await settle();
    expect(deviceApi.information.mock.calls.length).toBeGreaterThan(calls);
  });

  it('信息获取失败清除旧读数，其他实时状态仍能显示，重试可恢复', async () => {
    await mount(); deviceApi.information.mockRejectedValueOnce(new Error('完整信息暂不可用'));
    await click('刷新信息');
    expect(host.textContent).toContain('完整信息暂不可用');
    expect(host.textContent).toContain('A温度');
    expect(host.textContent).not.toContain('A工参');
    await click('刷新信息');
    expect(host.textContent).toContain('A工参');
    expect(host.textContent).not.toContain('完整信息暂不可用');
  });

  it('切换 A→B→A 时丢弃旧请求，不出现上一轮的设备状态', async () => {
    const old = deferred(); deviceApi.information.mockReturnValueOnce(old.promise);
    await mount(); await click('设备B'); await click('设备A');
    const latest = deferred(); deviceApi.information.mockReturnValueOnce(latest.promise);
    old.resolve({ ...info('A'), sections: [{ ...info('A').sections[0], title: '旧请求工参' }] }); await settle();
    expect(host.textContent).not.toContain('旧请求工参');
    latest.resolve(info('A')); await settle();
    expect(host.textContent).toContain('A工参');
  });

  it('筛选无设备时清空完整信息、状态和事件，不残留上一设备', async () => {
    await mount(); deviceApi.tree.mockResolvedValue({ items: [] });
    await click('筛选');
    expect(host.textContent).toContain('没有匹配的设备');
    expect(host.textContent).not.toContain('A工参');
    expect(host.textContent).not.toContain('A温度');
  });
});
