import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import CommissionView from '@/views/operations/CommissionView.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';

const permissions = vi.hoisted(() => ({ operate: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: key => key !== 'commissioning.op' || permissions.operate, hasMenu: () => true }) }));
vi.mock('@/api/devices.js', () => ({ deviceApi: { list: vi.fn(), detail: vi.fn() }, commissionApi: Object.fromEntries(['information', 'list', 'report', 'get', 'events', 'create', 'connect', 'configure', 'start', 'cancel'].map(key => [key, vi.fn()])) }));
let app, host, task;
const device = { device_id: 'qa', name: '本机协议模拟器', device_no: 'QA', source_mode: 'live', simulated: true };
const info = extra => ({ device_id: 'qa', task_supported: true, simulation_allowed: true, sections: [], sample_sections: [], ...extra });
async function settle() { for (let i = 0; i < 24; i++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/operations/commission', component: CommissionView }] });
  await router.push('/operations/commission?device_id=qa'); await router.isReady();
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ template: '<router-view />' }); app.use(router); app.use(ElementPlus); app.mount(host); await settle();
}
const button = text => [...host.querySelectorAll('button')].find(el => el.textContent.trim() === text);
beforeEach(() => {
  vi.clearAllMocks(); permissions.operate = true; task = null;
  deviceApi.list.mockResolvedValue({ items: [device], total: 1 });
  deviceApi.detail.mockResolvedValue({ connection: { transport: 'TCP', host: '127.0.0.1', port: 10007 } });
  commissionApi.information.mockResolvedValue(info());
  commissionApi.list.mockImplementation(async () => ({ items: task ? [task] : [], total: task ? 1 : 0 }));
  commissionApi.get.mockImplementation(async () => task);
  commissionApi.events.mockResolvedValue({ items: [], next_seq: 0 });
  for (const [action, status] of [['create', 'CREATED'], ['connect', 'CONNECTED'], ['configure', 'READY'], ['start', 'RUNNING']]) {
    commissionApi[action].mockImplementation(async () => (task = { ...device, commission_id: 'task', status, version: (task?.version ?? -1) + 1 }));
  }
});
afterEach(() => { app?.unmount(); host?.remove(); document.querySelectorAll('.el-overlay,.el-message').forEach(el => el.remove()); vi.useRealTimers(); });
describe('调测模拟来源使用服务端明确许可', () => {
  it('只读账号能看到服务端能力说明，过滤空项并按纯文本展示', async () => {
    permissions.operate = false;
    const warning = '协议 C 对 CameraStatus 支持情况表述不一致，现场支持仍需确认';
    const literal = '<img src=x onerror=alert(1)>';
    commissionApi.information.mockResolvedValue(info({ task_supported: false, notes: [warning, '', '  ', null, 42, literal] }));
    await mount();
    expect([...host.querySelectorAll('[aria-label="协议能力说明"] li')].map(el => el.textContent)).toEqual([warning, literal]);
    expect(host.querySelector('[aria-label="协议能力说明"] img')).toBeNull();
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it('切换设备立即清除旧说明，迟到响应不能串到新设备', async () => {
    let resolveOld, resolveNext;
    deviceApi.list.mockResolvedValue({ items: [device, { ...device, device_id: 'next', name: '另一台设备', device_no: 'NEXT' }], total: 2 });
    commissionApi.information.mockResolvedValueOnce(info({ notes: ['原设备说明'] }));
    await mount();
    expect(host.textContent).toContain('原设备说明');
    commissionApi.information.mockImplementation(id => new Promise(resolve => { if (id === 'qa') resolveOld = resolve; else resolveNext = resolve; }));
    button('刷新信息').click(); await settle();
    [...host.querySelectorAll('.device-tree-item')].find(el => el.textContent.includes('另一台设备')).click(); await settle();
    expect(host.textContent).not.toContain('原设备说明');
    resolveOld(info({ notes: ['迟到的原设备说明'] })); await settle();
    expect(host.textContent).not.toContain('迟到的原设备说明');
    resolveNext(info({ device_id: 'next', notes: ['新设备说明'] })); await settle();
    expect([...host.querySelectorAll('[aria-label="协议能力说明"] li')].map(el => el.textContent)).toEqual(['新设备说明']);
  });
  it('本地 QA 明确许可后能按顺序创建、连接、保存和调测，仍标记非正式', async () => {
    await mount();
    for (const [label, method] of [['创建新任务', 'create'], ['建立连接', 'connect'], ['保存配置', 'configure'], ['开始协议调测', 'start']]) {
      expect(button(label).disabled).toBe(false); button(label).click(); await settle();
      expect(commissionApi[method]).toHaveBeenCalledTimes(1);
    }
    expect(host.textContent).toContain('非正式');
    expect(host.textContent).toContain('模拟数据');
  });
  it('回放模拟器设备使用逻辑调测配置，不读取真实连接参数', async () => {
    const simulator = { ...device, source_mode: 'replay', simulated: true, protocol_code: 'LINGYUN_MQTT_V8_6' };
    deviceApi.list.mockResolvedValue({ items: [simulator], total: 1 });
    commissionApi.information.mockResolvedValue(info({ task_supported: true }));
    await mount();
    button('创建新任务').click(); await settle();
    button('建立连接').click(); await settle();
    expect(host.querySelector('input[placeholder="建立连接后配置"]').value).toBe('simulator');
    expect([...host.querySelectorAll('input')].map(el => el.value)).toContain('8766');
    button('保存配置').click(); await settle();
    expect(commissionApi.configure).toHaveBeenCalledWith('task', expect.objectContaining({ transport: 'SIMULATOR', host: 'simulator', port: 8766 }));
  });
  it.each([{ simulation_allowed: false }, { simulation_allowed: undefined }, { simulation_allowed: 'true' }, { device_id: 'other' }])('未获同设备的明确许可时禁用创建：%j', async fields => {
    commissionApi.information.mockResolvedValue(info(fields)); await mount();
    expect(button('创建新任务')?.disabled ?? true).toBe(true); button('创建新任务')?.click(); await settle();
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it.each([['CREATED', '建立连接', 'connect'], ['CONNECTED', '保存配置', 'configure'], ['READY', '开始协议调测', 'start']])('正式环境不能继续已有模拟任务 %s', async (status, label, method) => {
    task = { ...device, commission_id: 'task', status, version: 2 };
    commissionApi.information.mockResolvedValue(info({ simulation_allowed: false })); await mount();
    expect(button(label).disabled).toBe(true); button(label).click(); await settle(); expect(commissionApi[method]).not.toHaveBeenCalled();
  });
  it('读取许可失败时禁止继续调测', async () => {
    task = { ...device, commission_id: 'task', status: 'CONNECTED', version: 2 };
    commissionApi.information.mockRejectedValue(new Error('信息读取失败')); await mount();
    expect(button('保存配置').disabled).toBe(true); expect(host.textContent).toContain('信息读取失败');
  });
  it('模拟环境许可不能替代操作权限', async () => {
    permissions.operate = false; await mount(); expect(button('创建新任务').disabled).toBe(true);
  });
  it('正式 live 设备不依赖模拟许可', async () => {
    deviceApi.list.mockResolvedValue({ items: [{ ...device, simulated: false }], total: 1 });
    commissionApi.information.mockResolvedValue(info({ simulation_allowed: false })); await mount();
    expect(button('创建新任务').disabled).toBe(false);
  });
  it('恢复已保存任务时显示任务快照，不显示当前档案参数', async () => {
    task = { ...device, commission_id: 'task', status: 'READY', version: 3, configuration: { transport: 'TCP', host: '127.0.0.1', port: 10007, timeout_millis: 1200 } };
    deviceApi.detail.mockResolvedValue({ connection: { transport: 'TCP', host: '127.0.0.2', port: 10008, timeout_millis: 3000 } });
    await mount();
    expect(host.querySelector('input[placeholder="建立连接后配置"]').value).toBe('127.0.0.1');
    expect([...host.querySelectorAll('input')].map(el => el.value)).toContain('10007');
    expect([...host.querySelectorAll('input')].map(el => el.value)).toContain('1200');
  });
  it('迟到的设备档案读取不能覆盖已恢复任务的快照', async () => {
    let resolveDetail;
    deviceApi.detail.mockImplementation(() => new Promise(resolve => { resolveDetail = resolve; }));
    task = { ...device, commission_id: 'task', status: 'RUNNING', version: 4, configuration: { transport: 'TCP', host: '127.0.0.1', port: 10007, timeout_millis: 1500 } };
    await mount();
    resolveDetail({ connection: { transport: 'TCP', host: '127.0.0.2', port: 10008, timeout_millis: 3000 } }); await settle();
    expect(host.querySelector('input[placeholder="建立连接后配置"]').value).toBe('127.0.0.1');
    expect([...host.querySelectorAll('input')].map(el => el.value)).toContain('1500');
  });
  it('后台取消任务后轮询同步联调记录，不再保留旧的待连接状态', async () => {
    vi.useFakeTimers();
    task = { ...device, commission_id: 'task', commission_no: 'CT-CANCEL', status: 'CREATED', version: 0 };
    await mount();
    task = { ...task, status: 'CANCELLED', version: 1, finished_at: Date.now() };
    await vi.advanceTimersByTimeAsync(2100); await settle();
    const record = [...host.querySelectorAll('.el-table__body tr')].find(row => row.textContent.includes('CT-CANCEL'));
    expect(record.textContent).toContain('已取消');
    expect(record.textContent).not.toContain('待连接');
    expect(commissionApi.report).not.toHaveBeenCalled();
  });
});
