import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import ElementPlus from 'element-plus';
import DeviceTrendPanel from '@/components/DeviceTrendPanel.vue';
import { deviceApi } from '@/api/devices.js';
vi.mock('@/api/devices.js', () => ({ deviceApi: { trends: vi.fn() } }));
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), clear: vi.fn(), dispose: vi.fn() })) }));
let app, host, props;
const sample = value => ({ from: 0, to: 120000, bucket_ms: 60000, protocol_code: 'RADAR_TCP_V3_0_0', metrics: [{ code: 'active_track_count', at: 0, latest: value, average: value, minimum: value, maximum: value, samples: 1 }], reports: [] });
async function settle() { for (let n = 0; n < 14; n++) { await Promise.resolve(); await nextTick(); } }
async function mount() { host = document.createElement('div'); document.body.append(host); props = reactive({ deviceId: 'A', paused: false }); app = createApp({ render: () => h(DeviceTrendPanel, props) }); app.use(ElementPlus); app.mount(host); await settle(); }
beforeEach(() => { vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }); deviceApi.trends.mockResolvedValue(sample(3)); });
afterEach(() => { app?.unmount(); host?.remove(); vi.useRealTimers(); vi.resetAllMocks(); });
it('范围筛选真实改变查询参数，切换 Tab 不重复拉取，暂停允许手动刷新', async () => {
  await mount();
  expect([...host.querySelectorAll('[role="tab"]')].map(el => el.textContent)).toEqual(['感知统计', '上报趋势', '状态历史']);
  [...host.querySelectorAll('[role="tab"]')].find(el => el.textContent === '上报趋势').click(); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(1);
  host.querySelector('input[value="24h"]').click(); await settle();
  expect(deviceApi.trends).toHaveBeenLastCalledWith('A', { range: '24h' });
  props.paused = true; await settle(); await vi.advanceTimersByTimeAsync(20000); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(2);
  [...host.querySelectorAll('button')].find(el => el.textContent.includes('刷新统计')).click(); await settle();
  expect(deviceApi.trends).toHaveBeenCalledTimes(3);
});
it('不支持感知的设备仅显示适用 Tab，报文摘要不填充空卡片', async () => {
  deviceApi.trends.mockResolvedValue({ ...sample(0), sensing_supported: false });
  await mount();
  expect([...host.querySelectorAll('[role="tab"]')].map(el => el.textContent)).toEqual(['上报趋势', '状态历史']);
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
