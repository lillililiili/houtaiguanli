import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import DevicesView from '@/views/operations/DevicesView.vue';
import { deviceApi, integrationApi, mqttApi } from '@/api/devices.js';
import { weatherSensorsApi } from '@/api/externalInterfaces.js';

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
const permissions = vi.hoisted(() => ({ operate: true, connections: true, editConnections: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasMenu: () => false, hasPermission: code => code === 'interfaces.op' ? permissions.connections && permissions.editConnections : code === 'interfaces.read' ? permissions.connections : code !== 'devices.op' || permissions.operate }) }));
vi.mock('@/api/devices.js', () => ({
  deviceApi: { list: vi.fn(), options: vi.fn(), onboardingOptions: vi.fn(), overview: vi.fn(), detail: vi.fn(), protocolStatus: vi.fn(), onboard: vi.fn(), update: vi.fn() },
  integrationApi: { protocols: vi.fn() },
  mqttApi: { options: vi.fn(), scopes: vi.fn(), list: vi.fn(), create: vi.fn(), update: vi.fn(), setEnabled: vi.fn() }
}));
vi.mock('@/api/externalInterfaces.js', () => ({ weatherSensorsApi: { get: vi.fn(), create: vi.fn(), update: vi.fn() } }));
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') } }));
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
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(DevicesView); app.component('router-link',{template:'<a><slot /></a>'}); app.use(ElementPlus); app.mount(host); await settle(); }
beforeEach(() => {
  vi.clearAllMocks(); permissions.operate = true; permissions.connections = true; permissions.editConnections = true;
  deviceApi.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 10 });
  deviceApi.options.mockResolvedValue({ types: [], channels: [], regions: [], vendors: [] });
  deviceApi.overview.mockResolvedValue({ total: 0 });
  deviceApi.onboardingOptions.mockResolvedValue({ manual_radar_simulation_allowed: true });
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
afterEach(async () => {
  app?.unmount();
  // Element Plus releases the dialog scroll lock 200 ms after unmount.
  await new Promise(resolve => setTimeout(resolve, 220));
  host?.remove(); document.body.innerHTML = '';
});

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
  await click('光电设备'); expect(dialog().textContent).not.toContain('设备地址'); expect(dialog().textContent).toContain('此类型沿用现有演示配置');
  await click('天气传感器'); expect(dialog().textContent).toContain('协议待确认'); expect(dialog().textContent).not.toContain('边缘中心 ID');
  expect([...dialog().querySelectorAll('input')].some(el => el.value === '保留名称')).toBe(true);
  await click('雷达'); expect([...dialog().querySelectorAll('input')].some(el => el.value === '192.0.2.20')).toBe(false);
});
it('接入设备只展示已开放的六类设备类型', async () => {
  await mount(); await click('接入设备');
  expect([...dialog().querySelectorAll('.access-type')].map(el => el.textContent.trim())).toEqual([
    '雷达', '光电设备', '四通道反制设备', '天气传感器', '5G-A', 'TDOA'
  ]);
  for (const hiddenType of ['AOA', '协议破解', 'RemoteID', '诱骗', '干扰', '驱鸟炮']) {
    expect(dialog().textContent).not.toContain(hiddenType);
  }
});
it('天气传感器提交档案接口，不调用普通设备接入', async () => {
  await mount(); await click('接入设备'); await click('天气传感器');
  await fill('设备编号', 'WX-002'); await fill('设备名称', '气象站'); await choose('所属单位 / 区域', '单位 / 东区');
  await click('保存档案');
  expect(weatherSensorsApi.create).toHaveBeenCalledWith(expect.objectContaining({ device_no: 'WX-002', name: '气象站', owner_org_id: 'org1', district_id: 'd1' }));
  expect(deviceApi.onboard).not.toHaveBeenCalled();
});
it.each(['光电设备', '5G-A', 'TDOA'])('%s 沿用演示环境，不手动登记或新增 MQTT 连接', async type => {
  await mount(); await click('接入设备'); await click(type);
  expect(dialog().textContent).toContain('此类型沿用现有演示配置');
  expect(dialog().textContent).toContain('若缺少模拟设备或上报，请检查演示环境');
  expect(dialog().querySelectorAll('.el-form-item')).toHaveLength(0);
  expect(button('保存接入配置').disabled).toBe(true);
  for (const text of ['配置接入通道', '查看接入通道', '新增连接', '新增 MQTT 连接']) expect(button(text)).toBeUndefined();
  expect(mqttApi.options).not.toHaveBeenCalled(); expect(mqttApi.list).not.toHaveBeenCalled();
  expect(mqttApi.create).not.toHaveBeenCalled(); expect(mqttApi.setEnabled).not.toHaveBeenCalled();
  expect(deviceApi.onboard).not.toHaveBeenCalled();
});
it.each([
  ['雷达', 'RADAR_TCP_V3_0_0', 'org-radar', 'district-north', '192.0.2.20', 5001],
  ['四通道反制设备', 'COUNTERMEASURE_TCP_4CH_V2_0', 'org-counter', 'district-south', '198.51.100.30', 6002]
])('%s 接入不依赖 MQTT 通道，使用所选范围及自己的 TCP 协议参数', async (type, protocol, org, district, ip, port) => {
  permissions.connections = false;
  mqttApi.options.mockRejectedValue(new Error('MQTT 通道不可用'));
  mqttApi.scopes.mockResolvedValue([{ org_id: org, org_name: '接入单位', district_id: district, district_name: '接入区域' }]);
  await mount(); await click('接入设备'); await click(type);
  expect(dialog().textContent).not.toContain('MQTT 通道不可用');
  await fill('设备编号', `${org}-new`); await fill('设备名称', type);
  await fill('设备地址', ip); await fill('端口', String(port)); await fill('允许网段', `${ip}/32`);
  await choose('所属单位 / 区域', '接入单位 / 接入区域');
  if (type === '四通道反制设备') {
    await fill('控制器地址', '9'); await choose('线缆编码', 'ASCII_HEX_COMPACT');
  } else {
    await fill('雷达识别码引用', 'env:RADAR_TEST_CODE');
    expect([...document.querySelectorAll('.el-select-dropdown__item')].some(el => el.textContent.includes('凌云 MQTT'))).toBe(false);
  }
  await click('保存接入配置');
  expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ protocol_code: protocol, owner_org_id: org,
    district_id: district, host: ip, port, allowed_cidrs: `${ip}/32`, ...(type === '雷达'
      ? { recognition_code_ref: 'env:RADAR_TEST_CODE' } : { device_address: 9, wire_encoding: 'ASCII_HEX_COMPACT' }) }), expect.any(String));
  expect(deviceApi.onboard.mock.calls[0][0]).not.toHaveProperty('broker_id');
  expect(mqttApi.options).not.toHaveBeenCalled(); expect(mqttApi.create).not.toHaveBeenCalled();
});
it('本机模拟雷达由用户手动填写并明确提交模拟来源，切换类型清除该选择', async () => {
  await mount(); await click('接入设备'); await click('雷达');
  const radios = [...dialog().querySelectorAll('.el-radio')];
  expect(radios.find(el => el.textContent === '真实设备').classList.contains('is-checked')).toBe(true);
  radios.find(el => el.textContent === '本机模拟测试').click(); await settle();
  await fill('设备编号', 'manual-test-27'); await fill('设备名称', '手工测试雷达');
  await fill('设备地址', '127.0.0.1'); await fill('端口', '15002'); await fill('允许网段', '127.0.0.1/32');
  await choose('所属单位 / 区域', '单位 / 东区');
  await click('保存接入配置');
  expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ simulated: true, device_no: 'manual-test-27', port: 15002 }), expect.any(String));
  await click('接入设备'); await click('雷达');
  [...dialog().querySelectorAll('.el-radio')].find(el => el.textContent === '本机模拟测试').click(); await settle();
  await click('四通道反制设备'); expect(dialog().textContent).not.toContain('本机模拟测试');
  await click('雷达');
  expect([...dialog().querySelectorAll('.el-radio')].find(el => el.textContent === '真实设备').classList.contains('is-checked')).toBe(true);
});
it.each([false, 'unavailable'])('未开放模拟能力（%s）时保持真实 TCP 接入可用', async state => {
  if (state === false) deviceApi.onboardingOptions.mockResolvedValue({ manual_radar_simulation_allowed: false });
  else deviceApi.onboardingOptions.mockRejectedValue(new Error('服务暂不可用'));
  await mount(); await click('接入设备'); await click('雷达');
  expect(dialog().textContent).not.toContain('本机模拟测试');
  expect(button('保存接入配置').disabled).toBe(false);
});
it('无设备操作权限时也不显示接入配置入口', async () => {
  permissions.operate = false; await mount(); expect(button('接入设备').disabled).toBe(true);
  expect(button('接入配置')).toBeUndefined(); expect(mqttApi.list).not.toHaveBeenCalled();
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
it.each([
  ['LINGYUN_MQTT_V8_6', 'tdoa', 'replay', false],
  ['EO_EDGE_MQTT_20250826', 'eo', 'replay', true],
  ['LINGYUN_MQTT_V8_6', 'radar', 'live', true]
])('%s 已有设备编辑保留原身份、来源 %s 和原通道', async (protocol, type, source, enabled) => {
  const row = { device_id: `existing-${type}`, device_no: `${type}-OLD`, name: '原设备', version: 3 };
  deviceApi.list.mockResolvedValue({ items: [row], total: 1, page: 1, size: 10 });
  deviceApi.detail.mockResolvedValue({ device: row, protocol_code: protocol, model: 'T1' });
  mqttApi.options.mockResolvedValue([{ broker_id: 'original-broker', name: '已有通道', source_mode: source, owner_org_id: 'org1', district_id: 'd1', enabled }]);
  deviceApi.protocolStatus.mockResolvedValue({ details: { broker_id: 'original-broker', source_mode: source, device_type_abbr: type,
    provider_code: 'original-provider', edge_id: 'original-edge', external_device_id: `external-${type}` } });
  deviceApi.update.mockResolvedValue({ device: row });
  await mount(); await click('编辑'); await fill('设备名称', '修改名称');
  expect(dialog().textContent).toContain('沿用已登记通道');
  for (const label of ['外部设备编号', type === 'eo' ? '边缘中心 ID' : '提供方编码']) {
    const item = [...dialog().querySelectorAll('.el-form-item')].find(el => el.querySelector('label')?.textContent === label);
    expect(item.querySelector('input').disabled).toBe(true);
  }
  await click('保存');
  expect(deviceApi.update).toHaveBeenCalledWith(row.device_id, expect.objectContaining({ version: 3, protocol_code: protocol,
    broker_id: 'original-broker', source_mode: source, external_device_id: `external-${type}`, name: '修改名称', model: 'T1',
    ...(type === 'eo' ? { edge_id: 'original-edge' } : { provider_code: 'original-provider', device_type_abbr: type }) }), expect.any(String));
  expect(deviceApi.onboard).not.toHaveBeenCalled(); expect(mqttApi.create).not.toHaveBeenCalled(); expect(mqttApi.setEnabled).not.toHaveBeenCalled();
});
it('已有模拟设备通道读取失败时可重试，保留草稿且不能带缺失绑定保存', async () => {
  const row = { device_id: 'sim-edit', device_no: 'SIM-EDIT', name: '模拟设备', version: 2, owner_org_id: 'org1', district_id: 'd1' };
  deviceApi.list.mockResolvedValue({ items: [row], total: 1, page: 1, size: 10 });
  deviceApi.detail.mockResolvedValue({ device: row, protocol_code: 'LINGYUN_MQTT_V8_6' });
  deviceApi.protocolStatus.mockResolvedValue({ details: { broker_id: 'sim-broker', source_mode: 'replay', device_type_abbr: 'tdoa', provider_code: 'provider', external_device_id: 'external' } });
  mqttApi.options.mockRejectedValueOnce(new Error('演示通道读取失败'));
  await mount(); await click('编辑'); await fill('设备名称', '保留草稿');
  expect(dialog().textContent).toContain('演示通道读取失败'); expect(button('保存').disabled).toBe(true);
  mqttApi.options.mockResolvedValue([{ broker_id: 'sim-broker', name: '已恢复通道', source_mode: 'replay', owner_org_id: 'org1', district_id: 'd1', enabled: true }]);
  await click('重新加载接入资料');
  expect(dialog().textContent).toContain('已恢复通道'); expect(button('保存').disabled).toBe(false);
  expect([...dialog().querySelectorAll('input')].some(el => el.value === '保留草稿')).toBe(true);
  expect(deviceApi.update).not.toHaveBeenCalled();
});
it.each([['天气传感器', '保存档案'], ['雷达', '保存接入配置']])('接入范围加载失败时阻止 %s 提交，重试成功后恢复', async (type, save) => {
  mqttApi.scopes.mockRejectedValueOnce(new Error('范围读取失败'));
  await mount(); await click('接入设备'); await click(type);
  expect(dialog().textContent).toContain('范围读取失败'); expect(button(save).disabled).toBe(true);
  await click('重新加载接入资料'); expect(button(save).disabled).toBe(false);
  expect(weatherSensorsApi.create).not.toHaveBeenCalled();
});
it('TCP 接入保存型号和安装位置，连续提交只调用一次', async () => {
  let complete;
  deviceApi.onboard.mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  await mount(); await click('接入设备'); await click('雷达');
  await fill('设备编号', 'RD-2'); await fill('设备名称', '雷达'); await fill('型号', 'R2'); await fill('安装位置', '东门楼顶');
  await fill('安装经度', '118.6592400'); await fill('安装纬度', '37.4300000');
  await fill('设备地址', '192.0.2.20'); await fill('端口', '5001'); await fill('允许网段', '192.0.2.0/24');
  await choose('所属单位 / 区域', '单位 / 东区');
  button('保存接入配置').click(); button('保存接入配置').click(); await settle();
  expect(deviceApi.onboard).toHaveBeenCalledOnce();
  expect(deviceApi.onboard).toHaveBeenCalledWith(expect.objectContaining({ protocol_code: 'RADAR_TCP_V3_0_0', model: 'R2', address: '东门楼顶', host: '192.0.2.20', port: 5001, longitude: 118.65924, latitude: 37.43, owner_org_id: 'org1', district_id: 'd1' }), expect.any(String));
  complete({ device: { device_id: 'new-1' } }); await settle();
});

it('地图坐标必须成对填写，避免设备落到不完整位置', async () => {
  await mount(); await click('接入设备'); await click('雷达');
  await fill('设备编号', 'RD-3'); await fill('设备名称', '雷达'); await fill('设备地址', '192.0.2.21');
  await fill('端口', '5001'); await fill('允许网段', '192.0.2.0/24'); await fill('安装经度', '118.6592400');
  await choose('所属单位 / 区域', '单位 / 东区'); await click('保存接入配置');
  expect(deviceApi.onboard).not.toHaveBeenCalled();
});
