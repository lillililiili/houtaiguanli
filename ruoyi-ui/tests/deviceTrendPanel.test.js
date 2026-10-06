import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import ElementPlus from 'element-plus';
import DeviceTrendPanel from '@/components/DeviceTrendPanel.vue';
import { deviceApi } from '@/api/devices.js';
vi.mock('@/api/devices.js', () => ({ deviceApi: { trends: vi.fn() } }));
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), clear: vi.fn(), dispose: vi.fn() })) }));
// 实时推送：记录面板订阅的重读函数，测试里按需模拟设备上报带来的 device_state 信号。
const realtime = vi.hoisted(() => ({ subscriptions: [] }));
vi.mock('@/services/realtime.js', async importOriginal => ({
  refreshFailureText: (await importOriginal()).refreshFailureText,
  useRealtimeRefresh: (topics, reload) => { realtime.subscriptions.push({ topics, reload }); return { trigger: () => {} }; }
}));
let app, host, props;
const sample = value => ({ from: 0, to: 120000, bucket_ms: 60000, protocol_code: 'RADAR_TCP_V3_0_0', metrics: [{ code: 'active_track_count', at: 0, latest: value, average: value, minimum: value, maximum: value, samples: 1 }], reports: [{ code: 'report_static', at: 0, samples: value, interval_seconds: 1 }] });
async function settle() { for (let n = 0; n < 14; n++) { await Promise.resolve(); await nextTick(); } }
/* 模拟一次 device_state 信号；返回重读结果（失败时为错误，实时刷新据此退避重试）。 */
async function signal() {
  const results = [];
  for (const subscription of realtime.subscriptions.filter(item => item.topics.includes('device_state'))) {
    try { results.push(await subscription.reload(['device_state'])); } catch (error) { results.push(error); }
  }
  await settle();
  return results;
}
async function mount() { host = document.createElement('div'); document.body.append(host); props = reactive({ deviceId: 'A', paused: false }); app = createApp({ render: () => h(DeviceTrendPanel, props) }); app.use(ElementPlus); app.mount(host); await settle(); }
beforeEach(() => { vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }); deviceApi.trends.mockResolvedValue(sample(3)); });
afterEach(() => { app?.unmount(); host?.remove(); vi.useRealTimers(); vi.resetAllMocks(); realtime.subscriptions.length = 0; });
it('范围筛选真实改变查询参数，切换 Tab 不重复拉取，暂停时推送和定时补读都不读、允许手动刷新', async () => {
  await mount();
  expect([...host.querySelectorAll('[role="tab"]')].map(el => el.textContent)).toEqual(['上报趋势', '状态历史']);
  [...host.querySelectorAll('[role="tab"]')].find(el => el.textContent === '上报趋势').click(); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(1);
  host.querySelector('input[value="24h"]').click(); await settle();
  expect(deviceApi.trends).toHaveBeenLastCalledWith('A', { range: '24h' });
  props.paused = true; await settle();
  await signal(); await vi.advanceTimersByTimeAsync(60000); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(2);
  [...host.querySelectorAll('button')].find(el => el.textContent.includes('刷新统计')).click(); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(3);
  props.paused = false; await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(4);
  await signal();
  expect(deviceApi.trends).toHaveBeenCalledTimes(5);
  expect(deviceApi.trends).toHaveBeenLastCalledWith('A', { range: '24h' });
  await vi.advanceTimersByTimeAsync(60000); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(6);
});
it('设备上报后随推送重读；自动刷新失败保留上次统计并注明，错误交给实时刷新重试', async () => {
  await mount();
  expect(host.querySelector('.trend-cards').textContent).toContain('3');
  deviceApi.trends.mockResolvedValueOnce(sample(5));
  await signal();
  expect(host.querySelector('.trend-cards').textContent).toContain('5');
  deviceApi.trends.mockRejectedValueOnce(Object.assign(new Error('系统暂时无法完成操作，请查看最新记录；仍有问题请联系管理员。'), { status: 500 }));
  const [result] = await signal();
  expect(result).toBeInstanceOf(Error);
  expect(host.textContent).toContain('自动刷新失败（服务暂时不可用），正在重试；下面是上次读到的统计。');
  expect(host.querySelector('.trend-cards').textContent).toContain('5');
  deviceApi.trends.mockResolvedValueOnce(sample(7));
  expect(await signal()).toEqual([undefined]);
  expect(host.textContent).not.toContain('自动刷新失败');
  expect(host.querySelector('.trend-cards').textContent).toContain('7');
});
it('设备趋势不显示感知统计页签，报文摘要不填充空卡片', async () => {
  deviceApi.trends.mockResolvedValue({ ...sample(0), sensing_supported: false });
  await mount();
  expect([...host.querySelectorAll('[role="tab"]')].map(el => el.textContent)).toEqual(['上报趋势', '状态历史']);
  expect(host.textContent).not.toContain('感知统计');
  expect(host.querySelectorAll('.trend-cards article')).toHaveLength(1);
  expect(host.textContent).not.toContain('其他报文类型');
  [...host.querySelectorAll('[role="tab"]')].find(el => el.textContent === '状态历史').click(); await settle();
  expect(host.querySelector('.trend-cards')).toBeNull();
});
it('迟到设备响应不会覆盖新设备，失败后清空旧读数并可恢复', async () => {
  let resolveOld;
  deviceApi.trends.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
  await mount(); props.deviceId = 'B'; await settle();
  resolveOld(sample(999)); await settle();
  expect(host.textContent).not.toContain('999');
  expect(host.querySelector('.trend-cards').textContent).toContain('3');
  deviceApi.trends.mockRejectedValueOnce(new Error('统计接口失败'));
  [...host.querySelectorAll('button')].find(el => el.textContent.includes('刷新统计')).click(); await settle();
  expect(host.textContent).toContain('统计接口失败');
  expect(host.querySelector('.trend-cards').textContent).not.toContain('3');
  [...host.querySelectorAll('button')].find(el => el.textContent.includes('刷新统计')).click(); await settle();
  expect(host.textContent).not.toContain('统计接口失败');
});
