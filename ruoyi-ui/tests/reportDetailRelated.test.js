import { afterEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import ReportDetailTable from '@/components/ReportDetailTable.vue';
import { businessReportApi } from '@/api/reports.js';

vi.mock('@/api/reports.js', async original => ({ ...await original(), businessReportApi: { details: vi.fn() } }));
let app, host;
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; });

it.each([
  ['authorizations', '告警-1018-006', '1018-006'],
  ['handoffs', '告警-0902-117', '0902-117']
])('%s 明细的关联告警编号和告警页同一写法', async (key, related, shown) => {
  businessReportApi.details.mockResolvedValue({ items: [{ id: 'row-1', label: 'X', related, note: '设备已确认全部关闭。', source_mode: 'live' }], total: 1 });
  host = document.createElement('div'); document.body.append(host);
  app = createApp(ReportDetailTable, { params: { source_mode: 'live' }, section: { key, title: '明细' }, labels: {},
    columns: [{ field: 'related', label: '关联告警编号' }, { field: 'note', label: '结果说明' }] });
  app.use(ElementPlus); app.mount(host); await settle();
  const body = host.querySelector('.el-table__body').textContent;
  expect(body).toContain(shown);
  expect(body).not.toContain('告警-');
  expect(host.textContent).toContain('关联告警编号');
});
