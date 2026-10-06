import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import MonitorView from '@/views/operations/MonitorView.vue';
import { deviceApi } from '@/api/devices.js';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';

vi.mock('@/api/deviceMaintenance.js', () => ({ deviceMaintenanceApi: { list: vi.fn() } }));
// 实时推送：记录页面订阅的重读函数，测试里按需模拟后端推来的变化信号。
const realtime = vi.hoisted(() => ({ subscriptions: [] }));
vi.mock('@/services/realtime.js', async importOriginal => ({
  refreshFailureText: (await importOriginal()).refreshFailureText,
  useRealtimeRefresh: (topics, reload) => { realtime.subscriptions.push({ topics, reload }); return { trigger: () => {} }; }
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('echarts', () => ({ init: vi.fn() }));
vi.mock('@/api/devices.js', () => ({ deviceApi: Object.fromEntries(
  ['overview', 'tree', 'incidents', 'state', 'history', 'events', 'protocolStatus', 'targets', 'information', 'trends'].map(key => [key, vi.fn()])
) }));
let app, host;
const devices = ['A', 'B'].map(id => ({ device_id: id, device_no: id, name: `设备${id}`, channel: 'MQTT' }));
async function settle() { for (let i = 0; i < 18; i++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(MonitorView); app.use(createPinia()); app.use(ElementPlus); app.mount(host); await settle();
}
async function click(text) { [...host.querySelectorAll('button')].find(button => button.textContent.includes(text)).click(); await settle(); }
/* 模拟后端推来一次变化信号；返回各订阅的重读结果（失败时为错误，实时刷新据此退避重试）。 */
async function signal(topic = 'device_state') {
  const results = [];
  for (const subscription of realtime.subscriptions.filter(item => item.topics.includes(topic))) {
    try { results.push(await subscription.reload([topic])); } catch (error) { results.push(error); }
  }
  await settle();
  return results;
}
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  deviceMaintenanceApi.list.mockResolvedValue({ items: [], total: 0 });
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  deviceApi.overview.mockResolvedValue({ total: 2 });
  deviceApi.tree.mockResolvedValue({ items: devices });
  deviceApi.incidents.mockResolvedValue({ items: [] });
  deviceApi.state.mockImplementation(async id => ({ connectivity: 'ONLINE', health_code: 'GOOD', metrics: [{ code: 'temperature_c', label: `${id}温度`, value: 0 }] }));
  deviceApi.history.mockResolvedValue({ points: [] });
  deviceApi.trends.mockResolvedValue({ from: 0, to: 3600000, bucket_ms: 60000, metrics: [], reports: [] });
  deviceApi.events.mockResolvedValue({ items: [], next_seq: 0 });
  deviceApi.protocolStatus.mockResolvedValue({});
});
afterEach(() => { app?.unmount(); host?.remove(); vi.useRealTimers(); vi.resetAllMocks(); realtime.subscriptions.length = 0; });

describe('实时监测设备状态', () => {
  it('移除当前运行信息及其请求，设备运行监控随实时推送刷新、不再定时轮询', async () => {
    await mount();
    expect(deviceApi.information).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('当前运行信息');
    expect(host.textContent).not.toContain('刷新信息');
    expect(host.textContent).toContain('设备运行监控');
    expect(host.textContent).toContain('A温度');
    expect(deviceApi.state).toHaveBeenCalledTimes(1);
    expect(deviceApi.overview).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(30000); await settle();
    expect(deviceApi.state).toHaveBeenCalledTimes(1);
    expect(deviceApi.overview).toHaveBeenCalledTimes(1);
    await signal('device_state');
    expect(deviceApi.state).toHaveBeenCalledTimes(2);
    expect(deviceApi.overview).toHaveBeenCalledTimes(2);
    expect(deviceApi.information).not.toHaveBeenCalled();
  });

  it('暂停读数后设备上线离线和告警仍随推送更新，读数、事件停在暂停时刻', async () => {
    await mount(); await click('暂停读数刷新');
    expect(host.textContent).toContain('读数已暂停');
    expect(host.textContent).toContain('设备上线离线、健康状态和告警照常更新');
    const eventCalls = deviceApi.events.mock.calls.length;
    deviceApi.state.mockImplementation(async id => ({ connectivity: 'ONLINE', health_code: 'BAD', metrics: [{ code: 'temperature_c', label: `${id}新温度`, value: 9 }] }));
    deviceApi.incidents.mockResolvedValue({ items: [{ incident_id: 'i1', device_id: 'A', device_name: '设备A', reason: '温度过高', severity: 'HIGH', detected_at: 1 }], total: 1 });
    await signal('device_state');
    expect(host.querySelector('.state-hero').textContent).toContain('异常');
    expect(host.textContent).toContain('A温度');
    expect(host.textContent).not.toContain('A新温度');
    expect(host.textContent).toContain('温度过高');
    expect(deviceApi.events).toHaveBeenCalledTimes(eventCalls);
    // 设备离线：即使暂停也马上显示离线（ZT-49）。
    deviceApi.tree.mockResolvedValue({ items: devices.map(item => item.device_id === 'A' ? { ...item, connectivity: 'OFFLINE' } : item) });
    deviceApi.state.mockImplementation(async () => ({ connectivity: 'OFFLINE', health_code: 'BAD', metrics: [] }));
    await signal('device_state');
    expect(host.querySelector('.state-hero').textContent).toContain('离线');
    expect(host.querySelector('.device-tree-item.active').textContent).toContain('离线');
    expect(host.textContent).toContain('设备当前离线');
  });

  it('暂停读数后允许查看另一设备，继续后全部重读', async () => {
    await mount(); await click('暂停读数刷新');
    await click('设备B');
    expect(host.textContent).toContain('B温度');
    expect(host.textContent).not.toContain('A温度');
    deviceApi.state.mockImplementation(async id => ({ connectivity: 'ONLINE', health_code: 'GOOD', metrics: [{ code: 'temperature_c', label: `${id}新温度`, value: 1 }] }));
    await signal('device_state');
    expect(host.textContent).toContain('B温度');
    const calls = deviceApi.state.mock.calls.length;
    await click('继续读数刷新');
    expect(deviceApi.state.mock.calls.length).toBeGreaterThan(calls);
    expect(host.textContent).toContain('B新温度');
    expect(host.textContent).toContain('实时更新中');
    expect(deviceApi.information).not.toHaveBeenCalled();
  });

  it('状态获取失败清除旧读数并交给实时刷新重试，手动重试可恢复', async () => {
    await mount(); deviceApi.state.mockRejectedValueOnce(new Error('状态暂不可用'));
    const results = await signal('device_state');
    expect(results.some(result => result instanceof Error && result.message === '状态暂不可用')).toBe(true);
    expect(host.textContent).toContain('状态暂不可用');
    expect(host.textContent).not.toContain('A温度');
    await click('重新加载');
    expect(host.textContent).toContain('A温度');
    expect(host.textContent).not.toContain('状态暂不可用');
  });

  it('切换 A→B→A 时丢弃旧请求，不出现上一轮的设备状态', async () => {
    const old = deferred(); deviceApi.state.mockReturnValueOnce(old.promise);
    await mount(); await click('设备B'); await click('设备A');
    const latest = deferred(); deviceApi.state.mockReturnValueOnce(latest.promise);
    old.resolve({ metrics: [{ code: 'temperature_c', label: '旧请求温度', value: 99 }] }); await settle();
    expect(host.textContent).not.toContain('旧请求温度');
    latest.resolve({ metrics: [{ code: 'temperature_c', label: 'A温度', value: 0 }] }); await settle();
    expect(host.textContent).toContain('A温度');
  });

  it('筛选无设备时清空状态和事件，不残留上一设备', async () => {
    await mount(); deviceApi.tree.mockResolvedValue({ items: [] });
    await click('筛选');
    expect(host.textContent).toContain('没有匹配的设备');
    expect(host.textContent).not.toContain('A温度');
  });
});

const event = (seq, message = `事件${seq}`, eventType = 'REPORT_SUMMARY') => ({
  event_seq: seq, event_type: eventType, level_code: 'INFO', message, occurred_at: 100000, simulated: false
});
const eventText = () => host.querySelector('.monitor-events').textContent;

describe('设备事件独立增量读取', () => {
  it('首次最近100条按最新置顶，后续增量去重并只保留100条', async () => {
    deviceApi.events.mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, i) => event(i + 401)), next_seq: 500 });
    await mount();
    expect(deviceApi.events).toHaveBeenCalledWith({ device_id: 'A', latest: true, limit: 100 });
    expect(host.querySelector('.monitor-events .event-item').textContent).toContain('事件500');
    deviceApi.events.mockResolvedValueOnce({ items: [event(500), event(501)], next_seq: 501 });
    await signal('device_state');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 500, limit: 100 });
    expect(host.querySelectorAll('.monitor-events .event-item')).toHaveLength(100);
    expect(eventText()).not.toContain('事件401');
    expect(host.querySelector('.monitor-events .event-item').textContent).toContain('事件501');
  });

  it('首次成功但无事件也切到增量游标0，不反复获取最近记录', async () => {
    await mount();
    expect(eventText()).toContain('当前设备尚无事件');
    expect(eventText()).toContain('每 30 秒汇总');
    await signal('device_state');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 0, limit: 100 });
  });

  it('状态挂起且趋势失败时，日志仍然立即显示并持续增量刷新', async () => {
    const pending = deferred(); deviceApi.state.mockReturnValueOnce(pending.promise);
    deviceApi.trends.mockRejectedValueOnce(new Error('曲线不可用'));
    deviceApi.events.mockResolvedValueOnce({ items: [event(1, '独立日志')], next_seq: 1 });
    await mount();
    expect(eventText()).toContain('独立日志');
    deviceApi.events.mockResolvedValueOnce({ items: [event(2, '后续日志')], next_seq: 2 });
    await signal('device_state');
    expect(eventText()).toContain('后续日志');
    pending.resolve({ metrics: [] }); await settle();
  });

  it('状态和协议请求失败不丢弃已成功获取的事件', async () => {
    deviceApi.state.mockRejectedValueOnce(new Error('状态不可用'));
    deviceApi.protocolStatus.mockRejectedValueOnce(new Error('协议不可用'));
    deviceApi.events.mockResolvedValueOnce({ items: [event(1, '有效事件')], next_seq: 1 });
    await mount();
    expect(eventText()).toContain('有效事件');
    expect(eventText()).not.toContain('加载失败');
  });

  it('切换趋势分类和统计时间范围不重置或吞掉在途日志', async () => {
    deviceApi.events.mockResolvedValueOnce({ items: [event(4, '已有日志')], next_seq: 4 });
    await mount();
    const pending = deferred(); deviceApi.events.mockReturnValueOnce(pending.promise);
    void signal('device_state'); await settle();
    [...host.querySelectorAll('[role="tab"]')].find(tab => tab.textContent.includes('上报趋势')).click();
    const period = host.querySelector('.device-trends input[value="24h"]');
    period.click(); await settle();
    pending.resolve({ items: [event(5, '切换中的日志')], next_seq: 5 }); await settle();
    expect(eventText()).toContain('已有日志');
    expect(eventText()).toContain('切换中的日志');
    await signal('device_state');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 5, limit: 100 });
  });

  it('失败保留已有记录和游标，日志重试独立恢复', async () => {
    deviceApi.events.mockResolvedValueOnce({ items: [event(7, '原有事件')], next_seq: 7 });
    await mount();
    deviceApi.events.mockRejectedValueOnce(new Error('事件服务暂不可用'));
    await signal('device_state');
    expect(eventText()).toContain('原有事件');
    expect(eventText()).toContain('事件服务暂不可用');
    const stateCalls = deviceApi.state.mock.calls.length;
    deviceApi.events.mockResolvedValueOnce({ items: [event(8, '恢复事件')], next_seq: 8 });
    host.querySelector('.monitor-events button').click(); await settle();
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 7, limit: 100 });
    expect(deviceApi.state).toHaveBeenCalledTimes(stateCalls);
    expect(eventText()).toContain('恢复事件');
    expect(eventText()).not.toContain('事件服务暂不可用');
  });

  it('首次加载失败重试仍请求latest，并区分失败与未选择设备', async () => {
    deviceApi.events.mockRejectedValueOnce(new Error('日志不可用'));
    await mount();
    expect(eventText()).toContain('设备事件加载失败，请重试');
    expect(eventText()).not.toContain('当前设备尚无事件');
    host.querySelector('.monitor-events button').click(); await settle();
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', latest: true, limit: 100 });
    deviceApi.tree.mockResolvedValue({ items: [] }); await click('筛选');
    expect(eventText()).toContain('请选择设备查看事件');
  });

  it('A→B→A期间立即清空日志，不等待旧响应且丢弃同ID旧一代响应', async () => {
    const old = deferred(); deviceApi.events.mockReturnValueOnce(old.promise);
    await mount();
    deviceApi.events.mockResolvedValueOnce({ items: [event(11, 'B事件')], next_seq: 11 });
    await click('设备B');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'B', latest: true, limit: 100 });
    expect(eventText()).toContain('B事件');
    deviceApi.events.mockResolvedValueOnce({ items: [event(12, 'A新事件')], next_seq: 12 });
    await click('设备A');
    old.resolve({ items: [event(99, 'A旧事件')], next_seq: 99 }); await settle();
    expect(eventText()).toContain('A新事件');
    expect(eventText()).not.toContain('A旧事件');
    expect(eventText()).not.toContain('B事件');
    await signal('device_state');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 12, limit: 100 });
  });

  it('暂停读数后推送不再追加日志，恢复沿用游标；中文事件、级别与模拟标识可见', async () => {
    deviceApi.events.mockResolvedValueOnce({ items: [{ ...event(3, '模拟恢复上报', 'RECOVERED'), level_code: 'WARN', simulated: true }], next_seq: 3 });
    await mount();
    expect(eventText()).toContain('设备恢复在线');
    expect(eventText()).toContain('警告');
    expect(eventText()).toContain('模拟数据');
    await click('暂停读数刷新');
    const calls = deviceApi.events.mock.calls.length;
    await signal('device_state');
    expect(deviceApi.events).toHaveBeenCalledTimes(calls);
    await click('继续读数刷新');
    expect(deviceApi.events).toHaveBeenLastCalledWith({ device_id: 'A', after_seq: 3, limit: 100 });
  });
});
