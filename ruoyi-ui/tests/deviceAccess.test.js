import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import DevicesView from '@/views/operations/DevicesView.vue';
import { deviceApi, integrationApi, mqttApi } from '@/api/devices.js';
import { weatherSensorsApi } from '@/api/externalInterfaces.js';

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
const permissions = vi.hoisted(() => ({ operate: true, connections: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: code => code.startsWith('interfaces.') ? permissions.connections : code !== 'devices.op' || permissions.operate }) }));
vi.mock('@/api/devices.js', () => ({
  deviceApi: { list: vi.fn(), options: vi.fn(), overview: vi.fn(), detail: vi.fn(), protocolStatus: vi.fn(), onboard: vi.fn(), update: vi.fn() },
  integrationApi: { protocols: vi.fn() },
  mqttApi: { options: vi.fn(), scopes: vi.fn(), list: vi.fn() }
}));
vi.mock('@/api/externalInterfaces.js', () => ({ weatherSensorsApi: { get: vi.fn(), create: vi.fn(), update: vi.fn() } }));
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn() } }));
let app, host;
async function settle() { for (let i = 0; i < 25; i++) { await Promise.resolve(); await nextTick(); } }
const button = text => [...document.querySelectorAll('button')].find(el => el.textContent.trim() === text);
const dialog = () => document.querySelector('.device-access-dialog');
async function click(text) { const el = button(text); expect(el, text).toBeTruthy(); el.click(); await settle(); }
async function fill(label, value) {
  const item = [...dialog().querySelectorAll('.el-form-item')].find(el => el.querySelector('label')?.textContent === label);
  const input = item.querySelector('input'); input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await settle();
}
async function choose(label, value) {
  const item = [...dialog().querySelectorAll('.el-form-item')].find(el => el.querySelector('label')?.textContent === label);
  item.querySelector('.el-select').click(); await settle();
  const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(el => el.textContent === value);
  expect(option).toBeTruthy(); option.click(); await settle();
}
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(DevicesView); app.use(ElementPlus); app.mount(host); await settle(); }
beforeEach(() => {
  vi.clearAllMocks(); permissions.operate = true; permissions.connections = true;
  deviceApi.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 10 });
  deviceApi.options.mockResolvedValue({ types: [], channels: [], regions: [], vendors: [] });
  deviceApi.overview.mockResolvedValue({ total: 0 });
  deviceApi.detail.mockResolvedValue({ device: { device_id: 'new-1', version: 1 } });
  deviceApi.onboard.mockResolvedValue({ device: { device_id: 'new-1' } });
  integrationApi.protocols.mockResolvedValue([
    ['LINGYUN_MQTT_V8_6', '凌云 MQTT'], ['RADAR_TCP_V3_0_0', '雷达 TCP'], ['EO_EDGE_MQTT_20250826', '光电 MQTT'], ['COUNTERMEASURE_TCP_4CH_V2_0', '四通道 TCP']
  ].map(([protocol_code, name]) => ({ protocol_code, name, version: '1' })));
  mqttApi.options.mockResolvedValue([{ broker_id: 'b1', name: '东区连接', source_mode: 'live', owner_org_id: 'org1', district_id: 'd1', enabled: true }]);
  mqttApi.scopes.mockResolvedValue([{ org_id: 'org1', org_name: '单位', district_id: 'd1', district_name: '东区' }]);
  mqttApi.list.mockResolvedValue([]);
  weatherSensorsApi.create.mockResolvedValue({ device_id: 'weather-1' });
});
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; });

it('台账类型筛选提交后端返回的代码，不能把中文名称当作代码', async () => {
  deviceApi.options.mockResolvedValue({ types: ['反制', '光电'], type_options: [
    { code: 'countermeasure', name: '反制' }, { code: 'eo', name: '光电' }
  ], channels: [], regions: [], vendors: [] });
  await mount();
  const item = [...host.querySelectorAll('.device-filters .el-form-item')].find(el => el.querySelector('label')?.textContent === '设备类型');
  for (const [label, code] of [['反制', 'countermeasure'], ['光电', 'eo'], ['天气传感器', 'weather_sensor']]) {
    item.querySelector('.el-select').click(); await settle();
    const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(el => el.textContent === label);
    expect(option).toBeTruthy(); option.click(); await settle();
    await click('查询');
    expect(deviceApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ type_code: code, page: 1 }));
  }
  await click('重置');
  expect(deviceApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ type_code: '' }));
});

it('统一入口，选择类型前不显示 TCP 参数，切换后只显示相应协议字段', async () => {
  await mount(); expect(button('登记天气传感器')).toBeUndefined(); expect(button('MQTT 连接')).toBeUndefined();
  await click('接入设备'); expect(dialog().textContent).not.toContain('设备地址');
  await click('雷达'); expect(dialog().textContent).toContain('设备地址');
  await fill('设备名称', '保留名称'); await fill('设备地址', '192.0.2.20');
  await click('光电设备'); expect(dialog().textContent).not.toContain('设备地址'); expect(dialog().textContent).toContain('边缘中心 ID');
  await click('天气传感器'); expect(dialog().textContent).toContain('协议待确认'); expect(dialog().textContent).not.toContain('边缘中心 ID');
  expect([...dialog().querySelectorAll('input')].some(el => el.value === '保留名称')).toBe(true);
  await click('雷达'); expect([...dialog().querySelectorAll('input')].some(el => el.value === '192.0.2.20')).toBe(false);
});
it('天气传感器提交档案接口，不调用普通设备接入', async () => {
  await mount(); await click('接入设备'); await click('天气传感器');
  await fill('设备编号', 'WX-002'); await fill('设备名称', '气象站'); await choose('所属单位 / 区域', '单位 / 东区');
  await click('保存档案');
  expect(weatherSensorsApi.create).toHaveBeenCalledWith(expect.objectContaining({ device_no: 'WX-002', name: '气象站', owner_org_id: 'org1', district_id: 'd1' }));
  expect(deviceApi.onboard).not.toHaveBeenCalled();
});
it('唯一真实通道自动匹配，普通表单不出现 MQTT 连接和管理入口', async () => {
  await mount(); await click('接入设备'); await click('TDOA');
  await fill('设备编号', 'TD-2'); await fill('设备名称', 'TDOA'); await fill('外部设备编号', 'external-2'); await fill('提供方编码', 'vendor');
  expect(dialog().textContent).not.toContain('MQTT 连接'); expect(dialog().textContent).not.toContain('管理连接');
  expect(dialog().textContent).toContain('已自动匹配');
  expect([...dialog().querySelectorAll('.el-form-item')].find(el => el.querySelector('label')?.textContent === '接入通道')?.querySelector('.el-select')).toBeFalsy();
  await click('保存接入配置');
  expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ device_type_abbr: 'tdoa', source_mode: 'live', owner_org_id: 'org1', district_id: 'd1', broker_id: 'b1' }), expect.any(String));
  expect(deviceApi.onboard.mock.calls[0][0]).not.toHaveProperty('host');
});
it('普通设备操作员看不到管理员接入配置入口', async () => {
  permissions.connections = false; await mount(); expect(button('接入配置')).toBeUndefined();
  await click('接入设备'); await click('TDOA'); expect(button('管理连接')).toBeUndefined();
});
it('管理员独立维护连接，有设备操作权限时也保留入口', async () => {
  await mount(); await click('接入配置'); expect(mqttApi.list).toHaveBeenCalledOnce();
});
it('多个同范围通道需选择，其他范围、停用和模拟通道不混入', async () => {
  const base = { source_mode: 'live', owner_org_id: 'org1', district_id: 'd1', enabled: true };
  mqttApi.options.mockResolvedValue([
    { ...base, broker_id: 'b1', name: '东区一号' }, { ...base, broker_id: 'b2', name: '东区二号' },
    { ...base, broker_id: 'b3', name: '西区通道', district_id: 'd2' },
    { ...base, broker_id: 'b4', name: '停用通道', enabled: false },
    { ...base, broker_id: 'b5', name: '回放通道', source_mode: 'replay' }
  ]);
  await mount(); await click('接入设备'); await click('TDOA');
  expect(dialog().textContent).toContain('接入通道');
  await choose('接入通道', '东区二号');
  const options = [...document.querySelectorAll('.el-select-dropdown__item')].map(el => el.textContent);
  expect(options).not.toContain('西区通道'); expect(options).not.toContain('停用通道'); expect(options).not.toContain('回放通道');
  await fill('设备编号', 'TD-MULTI'); await fill('设备名称', '多通道设备'); await fill('外部设备编号', 'external-multi'); await fill('提供方编码', 'vendor');
  await click('保存接入配置'); expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ broker_id: 'b2' }), expect.any(String));
});
it('范围切换后清除旧通道，没有通道时阻止提交并提示联系管理员', async () => {
  mqttApi.scopes.mockResolvedValue([{ org_id: 'org1', org_name: '单位', district_id: 'd1', district_name: '东区' }, { org_id: 'org1', org_name: '单位', district_id: 'd2', district_name: '西区' }]);
  await mount(); await click('接入设备'); await click('TDOA');
  expect(dialog().textContent).toContain('请先选择所属单位及区域');
  await choose('所属单位 / 区域', '单位 / 东区'); expect(dialog().textContent).toContain('已自动匹配');
  await choose('所属单位 / 区域', '单位 / 西区');
  expect(dialog().textContent).toContain('暂无可用接入通道，请联系管理员配置'); expect(button('保存接入配置').disabled).toBe(true);
  expect(deviceApi.onboard).not.toHaveBeenCalled();
});
it('无设备操作权限时仍保留有权访问的接入配置入口', async () => {
  permissions.operate = false; await mount(); expect(button('接入设备').disabled).toBe(true);
  await click('接入配置'); expect(mqttApi.list).toHaveBeenCalledOnce();
});
it('天气档案编辑锁定编号、类型及范围，并携带原版本更新', async () => {
  const row = { device_id: 'w1', device_no: 'WX-OLD', name: '旧气象站', device_type_code: 'weather_sensor', version: 4, enabled: false };
  deviceApi.list.mockResolvedValue({ items: [row], total: 1, page: 1, size: 10 });
  deviceApi.detail.mockResolvedValue({ device: row });
  weatherSensorsApi.get.mockResolvedValue({ ...row, owner_org_id: 'org1', district_id: 'd1', model: 'M1' });
  weatherSensorsApi.update.mockResolvedValue({ ...row, version: 5 });
  await mount(); await click('编辑');
  expect([...dialog().querySelectorAll('.access-type')].every(el => el.disabled)).toBe(true);
  expect([...dialog().querySelectorAll('input')].find(el => el.value === 'WX-OLD').disabled).toBe(true);
  await fill('设备名称', '更新气象站'); await click('保存档案');
  expect(weatherSensorsApi.update).toHaveBeenCalledWith('w1', expect.objectContaining({ version: 4, device_no: 'WX-OLD', name: '更新气象站', model: 'M1', owner_org_id: 'org1', district_id: 'd1' }));
  expect(deviceApi.update).not.toHaveBeenCalled();
});
it('MQTT 编辑保留原协议身份及模拟来源，不改为真实来源', async () => {
  const row = { device_id: 'm1', device_no: 'TD-OLD', name: '原设备', version: 3 };
  deviceApi.list.mockResolvedValue({ items: [row], total: 1, page: 1, size: 10 });
  deviceApi.detail.mockResolvedValue({ device: row, protocol_code: 'LINGYUN_MQTT_V8_6', model: 'T1' });
  mqttApi.options.mockResolvedValue([{ broker_id: 'b1', name: '回放连接', source_mode: 'replay', owner_org_id: 'org1', district_id: 'd1', enabled: false }]);
  deviceApi.protocolStatus.mockResolvedValue({ details: { broker_id: 'b1', source_mode: 'replay', device_type_abbr: 'tdoa', provider_code: 'original', external_device_id: 'external-old' } });
  deviceApi.update.mockResolvedValue({ device: row });
  await mount(); await click('编辑'); await fill('设备名称', '修改名称'); await click('保存');
  expect(deviceApi.update).toHaveBeenCalledWith('m1', expect.objectContaining({ version: 3, protocol_code: 'LINGYUN_MQTT_V8_6', device_type_abbr: 'tdoa', source_mode: 'replay', external_device_id: 'external-old', provider_code: 'original', model: 'T1' }), expect.any(String));
});
it('接入范围加载失败时阻止档案提交，重试成功后恢复', async () => {
  mqttApi.scopes.mockRejectedValueOnce(new Error('范围读取失败'));
  await mount(); await click('接入设备'); await click('天气传感器');
  expect(dialog().textContent).toContain('范围读取失败'); expect(button('保存档案').disabled).toBe(true);
  await click('重新加载连接及范围'); expect(button('保存档案').disabled).toBe(false);
  expect(weatherSensorsApi.create).not.toHaveBeenCalled();
});
it('TCP 接入保存型号和安装位置，连续提交只调用一次', async () => {
  let complete;
  deviceApi.onboard.mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  await mount(); await click('接入设备'); await click('雷达');
  await fill('设备编号', 'RD-2'); await fill('设备名称', '雷达'); await fill('型号', 'R2'); await fill('安装位置', '东门楼顶');
  await fill('设备地址', '192.0.2.20'); await fill('端口', '5001'); await fill('允许网段', '192.0.2.0/24');
  button('保存接入配置').click(); button('保存接入配置').click(); await settle();
  expect(deviceApi.onboard).toHaveBeenCalledOnce();
  expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ protocol_code: 'RADAR_TCP_V3_0_0', model: 'R2', address: '东门楼顶', host: '192.0.2.20', port: 5001 }), expect.any(String));
  complete({ device: { device_id: 'new-1' } }); await settle();
});
