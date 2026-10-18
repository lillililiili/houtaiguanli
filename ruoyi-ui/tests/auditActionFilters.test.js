import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import AuditView from '@/views/system/AuditView.vue';
import { systemApi } from '@/api/system';

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }));
vi.mock('@/api/system', () => ({ systemApi: { audits: vi.fn(), roles: vi.fn(), audit: vi.fn(), auditCsv: vi.fn() } }));
let app, host;
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
beforeEach(() => {
  vi.useFakeTimers();
  vi.resetAllMocks();
  systemApi.audits.mockResolvedValue({ items: [], total: 0 });
  systemApi.roles.mockResolvedValue([]);
  systemApi.auditCsv.mockResolvedValue();
});

it.each(['127.0.0.1', '2001:db8::42'])('列表和详情显示服务端角色、菜单和实际 IP %s', async ip => {
  const row = { audit_id: 'log-test', account: 'operator', role_code: 'R-CHANGED', role_name: '调测业务角色',
    module_code: 'system', menu_code: 'commissioning', module_name: '设备接入调测', action_name: '开始调测',
    result: 'SUCCESS', ip, occurred_at: 1 };
  systemApi.audits.mockResolvedValue({ items: [row], total: 1 });
  systemApi.audit.mockResolvedValue(row);
  host = document.createElement('div'); document.body.append(host);
  app = createApp(AuditView); app.use(ElementPlus); app.mount(host); await settle();
  expect(host.textContent).toContain('调测业务角色');
  expect(host.textContent).toContain('设备接入调测');
  expect(host.textContent).toContain(ip);
  expect(host.textContent).not.toContain('本机');
  expect(systemApi.roles).not.toHaveBeenCalled();
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '详情').click(); await settle();
  const detail = document.querySelector('.el-dialog__body');
  expect(detail.textContent).toContain('调测业务角色');
  expect(detail.textContent).toContain('设备接入调测');
  expect(detail.textContent).toContain(ip);
});

it('菜单筛选、导出保持一致，历史缺失信息明确显示', async () => {
  systemApi.audits.mockResolvedValue({ items: [{ audit_id: 'legacy', account: 'operator',
    role_name: '后台用户（当前角色，历史未记录）', role_source: 'CURRENT', module_name: '设备异常处理', ip: null }], total: 1 });
  host = document.createElement('div'); document.body.append(host);
  app = createApp(AuditView); app.use(ElementPlus); app.mount(host); await settle();
  expect(host.textContent).toContain('当前角色，历史未记录');
  expect(host.textContent).toContain('未记录');
  host.querySelector('input[placeholder="全部菜单"]').click(); await settle();
  [...document.querySelectorAll('.el-select-dropdown__item')].find(x => x.textContent.trim() === '设备异常处理').click(); await settle();
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '查询').click(); await settle();
  expect(systemApi.audits).toHaveBeenLastCalledWith(expect.objectContaining({ module: 'maintenance' }));
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '导出 CSV').click(); await settle();
  expect(systemApi.auditCsv).toHaveBeenLastCalledWith(expect.objectContaining({ module: 'maintenance' }));
});
afterEach(async () => {
  app?.unmount();
  // Drain Element Plus table layout and dialog timers before jsdom is disposed.
  await vi.runOnlyPendingTimersAsync();
  host?.remove();
  document.body.innerHTML = '';
  vi.useRealTimers();
});

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

it('默认隐藏系统自动记录，勾选后请求包含；结果与动作显示中文，详情保留原代码', async () => {
  const row = { audit_id: 'auto', account: 'AUTO_SMS', action: 'brand_new_action', action_name: '其他操作',
    result: 'FAILED', result_name: '失败', object_type: 'uav_event', object_type_name: '无人机事件', occurred_at: 1 };
  systemApi.audits.mockResolvedValue({ items: [row], total: 1 });
  systemApi.audit.mockResolvedValue(row);
  host = document.createElement('div'); document.body.append(host);
  app = createApp(AuditView); app.use(ElementPlus); app.mount(host); await settle();
  expect(systemApi.audits).toHaveBeenLastCalledWith(expect.objectContaining({ include_automatic: '' }));
  expect(host.textContent).toContain('已隐藏系统自动记录');
  expect(host.textContent).toContain('其他操作');
  expect(host.textContent).toContain('失败');
  expect(host.textContent).not.toContain('brand_new_action');
  [...host.querySelectorAll('.el-checkbox')].find(x => x.textContent.includes('显示系统自动记录')).click(); await settle();
  expect(systemApi.audits).toHaveBeenLastCalledWith(expect.objectContaining({ include_automatic: 'true', page: 1 }));
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '导出 CSV').click(); await settle();
  expect(systemApi.auditCsv).toHaveBeenLastCalledWith(expect.objectContaining({ include_automatic: 'true' }));
  [...host.querySelectorAll('button')].find(x => x.textContent.trim() === '详情').click(); await settle();
  const detail = document.querySelector('.el-dialog__body').textContent;
  expect(detail).toContain('其他操作（原代码：brand_new_action）');
  expect(detail).toContain('无人机事件（原代码：uav_event）');
  expect(detail).toContain('失败（原代码：FAILED）');
});
