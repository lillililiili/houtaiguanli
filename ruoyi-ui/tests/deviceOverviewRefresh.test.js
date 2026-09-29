import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import DevicesView from '@/views/operations/DevicesView.vue';
import { deviceApi, integrationApi } from '@/api/devices.js';

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }));
vi.mock('@/api/devices.js', () => ({
  deviceApi: { list: vi.fn(), options: vi.fn(), overview: vi.fn(), detail: vi.fn() },
  integrationApi: { protocols: vi.fn() }, mqttApi: {}
}));

let app, host;
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(DevicesView); app.use(ElementPlus); app.mount(host); await settle();
}
function search() { [...host.querySelectorAll('button')].find(b => b.textContent.trim() === '查询').click(); }
function alarmCount() {
  return [...host.querySelectorAll('[aria-label="设备关键指标"] article')]
    .find(a => a.textContent.includes('告警中设备'))?.querySelector('strong')?.textContent;
}
beforeEach(() => {
  vi.resetAllMocks();
  deviceApi.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 10 });
  deviceApi.options.mockResolvedValue({ types: [], channels: [] });
  deviceApi.overview.mockResolvedValue({ total: 12, alarm: 2 });
  integrationApi.protocols.mockResolvedValue([]);
});
afterEach(() => { app?.unmount(); host?.remove(); });

it('重新查询列表时同步更新告警汇总，而非保留进页时旧数', async () => {
  await mount(); expect(alarmCount()).toBe('2');
  deviceApi.overview.mockResolvedValue({ total: 12, alarm: 3 });
  search(); await settle();
  expect(alarmCount()).toBe('3');
  expect(deviceApi.list).toHaveBeenCalledTimes(2);
});

it('较慢的旧统计响应不能覆盖更新查询的结果', async () => {
  await mount();
  let oldResponse;
  deviceApi.overview.mockImplementationOnce(() => new Promise(resolve => { oldResponse = resolve; }))
    .mockResolvedValueOnce({ total: 12, alarm: 4 });
  search(); await settle(); search(); await settle();
  expect(alarmCount()).toBe('4');
  oldResponse({ total: 12, alarm: 3 }); await settle();
  expect(alarmCount()).toBe('4');
});

it('统计加载失败显示错误，不把失败当零条', async () => {
  await mount();
  deviceApi.overview.mockRejectedValue(new Error('汇总暂不可用'));
  search(); await settle();
  expect(host.textContent).toContain('汇总暂不可用');
  expect(alarmCount()).toBe('2');
});

it('缓存末页被其他操作者删空时，分页回退同时刷新总数', async () => {
  deviceApi.list.mockResolvedValue({ items: [], total: 11, page: 1, size: 10 });
  deviceApi.overview.mockResolvedValue({ total: 11, alarm: 2 });
  await mount();
  deviceApi.list.mockResolvedValueOnce({ items: [], total: 10, page: 2, size: 10 })
    .mockResolvedValueOnce({ items: [], total: 10, page: 1, size: 10 });
  deviceApi.overview.mockResolvedValue({ total: 10, alarm: 2 });
  [...host.querySelectorAll('.el-pager li')].find(item => item.textContent.trim() === '2').click();
  await settle();
  expect(deviceApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
  const total = [...host.querySelectorAll('[aria-label="设备关键指标"] article')]
    .find(a => a.textContent.includes('设备总数')).querySelector('strong').textContent;
  expect(total).toBe('10');
});
