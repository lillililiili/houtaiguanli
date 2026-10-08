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
  report_scope: params.source_mode || 'live', available_source_modes: ['live'],
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
      expect.objectContaining({ report_category: 'DEVICE_OPERATIONS', period_type: 'MONTHLY', source_mode: 'live' }),
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
  it('正式预览拒绝测试来源，不能导出或把异常当作零记录', async () => {
    await mount();
    expect(pending[0].params.source_mode).toBe('live');
    pending[0].resolve({ ...makePreview(pending[0].params), simulated: true, source_mode: 'mixed' });
    await settle();
    expect(host.textContent).toContain('返回数据不符合正式统计口径');
    expect(host.textContent).not.toContain('当前统计范围暂无业务记录');
    expect(button('导出 Excel').disabled).toBe(true);
  });
  it('文件名同时包含类型、周期和区间', () => {
    expect(businessReportFilename({ title: '事件处置', period_type: 'WEEKLY', from: '2025-12-29', to: '2026-01-04' }, 'xlsx'))
      .toBe('事件处置周报-2025-12-29-2026-01-04.xlsx');
  });
  it('仅服务端允许时显示模拟口径，快速切换丢弃旧响应且导出与预览一致', async () => {
    await mount();
    expect(host.textContent).not.toContain('模拟验收口径');
    pending[0].resolve({ ...makePreview(pending[0].params), available_source_modes: ['live', 'simulated'] }); await settle();
    host.querySelector('input[value="simulated"]').click(); await settle();
    expect(pending[1].params.source_mode).toBe('simulated');
    expect(button('导出 Excel').disabled).toBe(true);
    host.querySelector('input[value="live"]').click(); await settle();
    pending[2].resolve({ ...makePreview(pending[2].params), available_source_modes: ['live', 'simulated'] }); await settle();
    pending[1].resolve({ ...makePreview(pending[1].params), source_mode: 'mock', simulated: true }); await settle();
    expect(host.textContent).not.toContain('模拟验收：仅纳入');
    host.querySelector('input[value="simulated"]').click(); await settle();
    pending[3].resolve({ ...makePreview(pending[3].params), source_mode: 'simulated', simulated: true, available_source_modes: ['live', 'simulated'] }); await settle();
    expect(host.textContent).toContain('模拟验收：仅纳入');
    button('导出 Excel').click(); await settle();
    expect(businessReportApi.exportFile).toHaveBeenCalledWith(expect.objectContaining({ source_mode: 'simulated' }), 'xlsx', expect.stringContaining('模拟验收-'));
    await category('FLIGHT_VERIFICATION');
    pending[4].resolve({ ...makePreview(pending[4].params), source_mode: 'mock', simulated: true, available_source_modes: ['live', 'simulated'],
      sections: [{ key: 'plans', title: '飞行计划', accessible: true, total: 1, days: [], distributions: [] }], columns: { plans: [] } });
    await settle();
    expect(businessReportApi.details).toHaveBeenLastCalledWith(expect.objectContaining({ source_mode: 'simulated', section: 'plans' }));
  });
  it('模拟请求返回正式数据时拒绝展示和导出', async () => {
    await mount();
    pending[0].resolve({ ...makePreview(pending[0].params), available_source_modes: ['live', 'simulated'] }); await settle();
    host.querySelector('input[value="simulated"]').click(); await settle();
    pending[1].resolve({ ...makePreview(pending[1].params), report_scope: 'live' }); await settle();
    expect(host.textContent).toContain('返回数据不符合模拟验收口径');
    expect(button('导出 Excel').disabled).toBe(true);
  });
});
