import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import AuditView from '@/views/system/AuditView.vue';
import { systemApi } from '@/api/system';

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }));
vi.mock('@/api/system', () => ({ systemApi: { audits: vi.fn(), roles: vi.fn(), auditCsv: vi.fn() } }));
let app, host;
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
beforeEach(() => {
  vi.resetAllMocks();
  systemApi.audits.mockResolvedValue({ items: [], total: 0 });
  systemApi.roles.mockResolvedValue([]);
  systemApi.auditCsv.mockResolvedValue();
});
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; });

it.each([
  ['登记设备', 'device_create'], ['启用设备', 'device_enable'], ['停用设备', 'device_disable'],
  ['创建调测任务', 'commission_create'], ['调测建立连接', 'commission_connect'],
  ['保存调测连接参数', 'commission_configuration'], ['开始调测', 'commission_start'],
  ['取消调测任务', 'commission_cancel']
])('%s 筛选和导出使用后端实际写入的动作 %s', async (label, code) => {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(AuditView); app.use(ElementPlus); app.mount(host); await settle();
  host.querySelector('input[placeholder="全部动作"]').click(); await settle();
  const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(x => x.textContent.trim() === label);
  expect(option, `缺少 ${label} 的筛选项`).toBeTruthy();
  option.click(); await settle();
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '查询').click(); await settle();
  expect(systemApi.audits).toHaveBeenLastCalledWith(expect.objectContaining({ action: code, page: 1 }));
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '导出 CSV').click(); await settle();
  expect(systemApi.auditCsv).toHaveBeenLastCalledWith(expect.objectContaining({ action: code }));
});
