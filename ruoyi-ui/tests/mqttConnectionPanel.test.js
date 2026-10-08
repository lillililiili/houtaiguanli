import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import InterfacesView from '@/views/operations/InterfacesView.vue';
import { mqttApi } from '@/api/devices.js';
import { externalInterfacesApi } from '@/api/externalInterfaces.js';

const permissions = vi.hoisted(() => ({ codes: new Set() }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: code => permissions.codes.has(code), user: {} }) }));
vi.mock('@/api/externalInterfaces.js', () => ({ externalInterfacesApi: { get: vi.fn(), save: vi.fn() } }));
vi.mock('@/api/devices.js', () => ({ mqttApi: { list: vi.fn(), capabilities: vi.fn(), scopes: vi.fn(), create: vi.fn(), update: vi.fn(), setEnabled: vi.fn() } }));
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }));

let app, host;
const TEST_ENV = { source_modes: ['live', 'replay'], simulation_allowed: true };
const FORMAL_ENV = { source_modes: ['live'], simulation_allowed: false };
const replayRow = { broker_id: 'b1', name: 'local-lingyun-replay', host: '127.0.0.1', port: 1883, tls: false, username: null, credential_ref: null,
  allowed_cidrs: '127.0.0.1/32', source_mode: 'replay', owner_org_id: 'org1', district_id: 'd1', enabled: false, version: 2, connection_state: 'DISCONNECTED' };
async function settle() { for (let i = 0; i < 25; i++) { await Promise.resolve(); await nextTick(); } }
const button = text => [...document.querySelectorAll('button')].find(el => el.textContent.trim() === text);
async function click(text) { const el = button(text); expect(el, text).toBeTruthy(); el.click(); await settle(); }
const dialog = () => document.querySelector('.mqtt-editor-dialog');
async function openTab() {
  host = document.createElement('div'); document.body.append(host); app = createApp(InterfacesView); app.use(ElementPlus); app.mount(host); await settle();
  [...host.querySelectorAll('[role=tab]')].find(el => el.textContent.includes('设备数据连接')).click(); await settle();
}
async function chooseRadio(text) {
  const radio = [...dialog().querySelectorAll('.el-radio')].find(el => el.textContent.trim() === text);
  expect(radio, text).toBeTruthy(); radio.querySelector('input').click(); await settle();
}
beforeEach(() => {
  vi.clearAllMocks();
  permissions.codes = new Set(['interfaces.read', 'interfaces.op']);
  externalInterfacesApi.get.mockImplementation(async kind => ({ kind, name: '任务接口', source_mode: 'live', version: 0, status: 'NOT_CONFIGURED', enabled: false }));
  mqttApi.list.mockResolvedValue([]);
  mqttApi.capabilities.mockResolvedValue(TEST_ENV);
  mqttApi.scopes.mockResolvedValue([{ org_id: 'org1', org_name: '平台单位', district_id: 'd1', district_name: '东营区' }]);
  mqttApi.create.mockResolvedValue({ ...replayRow });
  mqttApi.setEnabled.mockResolvedValue({ ...replayRow, enabled: true, version: 3 });
  ElMessageBox.confirm.mockResolvedValue('confirm');
});
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; });

describe('接口配置里的设备数据连接', () => {
  it('切到设备数据连接才读取连接、环境能力和单位区域', async () => {
    await openTab();
    expect(externalInterfacesApi.get).toHaveBeenCalledTimes(1);
    expect(mqttApi.list).toHaveBeenCalledOnce();
    expect(mqttApi.capabilities).toHaveBeenCalledOnce();
    expect(mqttApi.scopes).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('可以建“模拟回放”连接给设备模拟器使用');
  });

  it('测试环境可一键填入本机模拟器默认值并保存回放连接', async () => {
    await openTab(); await click('新增连接');
    await chooseRadio('模拟回放（仅测试环境）');
    await click('填入本机模拟器默认值');
    await click('保存');
    expect(mqttApi.create).toHaveBeenCalledWith({ name: 'local-lingyun-replay', host: '127.0.0.1', port: 1883, tls: false, username: null, credential_ref: null,
      allowed_cidrs: '127.0.0.1/32', source_mode: 'replay', owner_org_id: 'org1', district_id: 'd1', version: null }, expect.stringMatching(/^mqtt-create-/));
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('停用状态'));
    expect(mqttApi.list).toHaveBeenCalledTimes(2);
  });

  it('启用前确认，提交当前版本号并刷新', async () => {
    mqttApi.list.mockResolvedValue([replayRow]);
    await openTab();
    expect(host.textContent).toContain('平台单位 / 东营区');
    await click('启用');
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce();
    expect(mqttApi.setEnabled).toHaveBeenCalledWith('b1', { enabled: true, version: 2 });
    expect(mqttApi.list).toHaveBeenCalledTimes(2);
  });

  it('真实设备连接缺少用户名或凭据时提示补全，不发启用请求', async () => {
    mqttApi.list.mockResolvedValue([{ ...replayRow, broker_id: 'b2', name: '现场连接', source_mode: 'live', username: 'platform', credential_ref: null }]);
    await openTab(); await click('启用');
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('用户名和密码凭据引用'));
    expect(mqttApi.setEnabled).not.toHaveBeenCalled();
  });

  it('正式环境只能建真实设备连接，历史模拟连接只能查看', async () => {
    mqttApi.capabilities.mockResolvedValue(FORMAL_ENV);
    mqttApi.list.mockResolvedValue([replayRow]);
    await openTab();
    expect(host.textContent).toContain('历史模拟连接，正式环境只能查看');
    expect(button('启用')).toBeUndefined();
    await click('新增连接');
    expect(dialog().textContent).toContain('真实设备');
    expect(dialog().textContent).not.toContain('模拟回放');
  });

  it('没有单位和区域时提示先去用户管理新建，不能新增连接', async () => {
    mqttApi.scopes.mockResolvedValue([]);
    await openTab();
    expect(host.textContent).toContain('还没有可选的单位和区域');
    expect(button('新增连接').disabled).toBe(true);
  });

  it('只有查看权限时不读取单位区域，也没有新增和启停', async () => {
    permissions.codes = new Set(['interfaces.read']);
    mqttApi.list.mockResolvedValue([replayRow]);
    await openTab();
    expect(mqttApi.scopes).not.toHaveBeenCalled();
    expect(button('新增连接')).toBeUndefined();
    expect(button('启用')).toBeUndefined();
    expect(host.textContent).toContain('当前账号只能查看连接');
  });
});
