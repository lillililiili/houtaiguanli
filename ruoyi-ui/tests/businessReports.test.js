import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import ReportsView from '@/views/operations/ReportsView.vue';
import { businessReportApi, businessReportFilename } from '@/api/reports.js';

vi.mock('@/components/ReportChart.vue', () => ({ default: { template: '<div class="chart-stub" />' } }));
vi.mock('@/api/reports.js', async importOriginal => {
  const actual = await importOriginal();
  return { ...actual, businessReportApi: { preview: vi.fn(), details: vi.fn(), exportFile: vi.fn() } };
});
let app, host;
const pending = [];
const makePreview = (params, title = '综合运行') => ({
  ...params, title, from: '2026-09-01', to: '2026-09-16', generated_at: 1789516800000,
  period_label: '2026年9月', source_mode: 'live', simulated: false, status_note: '状态截至生成时',
  sections: [], columns: {}, labels: {}
});
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(ReportsView); app.use(ElementPlus); app.mount(host); await settle();
}
function button(text) { return [...host.querySelectorAll('button')].find(b => b.textContent.includes(text)); }
async function category(value) { host.querySelector('input[value="' + value + '"]').click(); await settle(); }
beforeEach(() => {
  pending.length = 0; vi.clearAllMocks();
  businessReportApi.preview.mockImplementation(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })));
  businessReportApi.details.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
  businessReportApi.exportFile.mockResolvedValue();
});
afterEach(() => { app?.unmount(); host?.remove(); });

describe('业务报表', () => {
  it('已加载总览后切换类型，不以新类型请求旧分区明细', async () => {
    await mount();
    pending[0].resolve({ ...makePreview(pending[0].params), sections: [
      { key: 'targets', title: '新增目标', accessible: true, total: 1, snapshot: false, days: [], distributions: [] }
    ] });
    await settle();
    await category('DEVICE_OPERATIONS');
    expect(businessReportApi.details).not.toHaveBeenCalled();
    expect(button('下载 PDF').disabled).toBe(true);
    pending[1].resolve(makePreview(pending[1].params, '设备运维')); await settle();
    expect(businessReportApi.details).not.toHaveBeenCalled();
  });
  it('类型和周期独立，快速切换丢弃旧响应，下载只使用当前筛选', async () => {
    await mount();
    expect(button('下载 PDF').disabled).toBe(true);
    await category('DEVICE_OPERATIONS');
    pending[1].resolve(makePreview(pending[1].params, '设备运维'));
    await settle();
    pending[0].resolve(makePreview(pending[0].params, '旧综合运行'));
    await settle();
    expect(button('下载 PDF').disabled).toBe(false);
    button('下载 PDF').click(); await settle();
    expect(businessReportApi.exportFile).toHaveBeenCalledWith(
      expect.objectContaining({ report_category: 'DEVICE_OPERATIONS', period_type: 'MONTHLY' }),
      'pdf', expect.stringContaining('设备运维月报'));
    expect(businessReportApi.exportFile.mock.calls[0][2]).not.toContain('旧综合');
  });
  it('失败清除旧内容并禁止导出，重试后恢复', async () => {
    await mount(); pending[0].reject(new Error('没有风险读取权限')); await settle();
    expect(host.textContent).toContain('没有风险读取权限');
    expect(button('导出 Excel').disabled).toBe(true);
    button('重新加载').click(); await settle();
    pending[1].resolve(makePreview(pending[1].params)); await settle();
    expect(host.textContent).not.toContain('没有风险读取权限');
    expect(button('导出 Excel').disabled).toBe(false);
  });
  it('文件名同时包含类型、周期和区间', () => {
    expect(businessReportFilename({ title: '事件处置', period_type: 'WEEKLY', from: '2025-12-29', to: '2026-01-04' }, 'xlsx'))
      .toBe('事件处置周报-2025-12-29-2026-01-04.xlsx');
  });
});
