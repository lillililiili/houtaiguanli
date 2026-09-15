import { afterEach, describe, expect, it } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import ElementPlus from 'element-plus';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import { catalogInformation } from '@/utils/deviceInformationPresentation.js';

let app, host, props;
const field = (key, label, value, status = 'RECEIVED') => ({ key, label, value, status, required: true });
const information = { device_id: 'A', name: '雷达 A', device_no: 'A', model: 'T02', source_mode: 'live', sections: [
  { code: 'catalog', title: '设备档案', fields: [field('vendor', '厂家', '测试厂家'), field('source_id', '来源 ID', 'source-test')] },
  { code: 'connection', title: '连接配置', fields: [field('host', '主机地址', '192.0.2.1', 'REDACTED')] },
  { code: 'mqtt', title: 'MQTT 诊断', fields: [field('static_topic', '工参主题', 'bridge/test')] },
  { code: 'work_parameters', title: '设备上报工参', fields: [field('providerCode', '提供方编码', 'test'), field('workState', '工作状态', 0), field('temperature', '温度', null, 'STALE')] },
  { code: 'radar_registers', title: '雷达检测参数', fields: [field('user_cfg0_raw', '寄存器原值', 123), field('speed_threshold_mps', '速度门限', 0)] }
], sample_sections: [{ code: 'target1', title: '目标 1', source: '设备上报', observed_at: 100, received_at: 200, fields: [field('speed', '目标速度', 0, 'STALE')] }], notes: [] };
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(purpose, data = information) {
  props = reactive({ information: data, purpose }); host = document.createElement('div'); document.body.append(host);
  app = createApp({ render: () => h(DeviceInformationPanel, props) }); app.use(ElementPlus); app.mount(host); await settle();
}
async function tab(label) { [...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes(label)).click(); await settle(); }
afterEach(() => { app?.unmount(); host?.remove(); });

describe('设备信息按页面用途展示', () => {
  it('监测只展示运行参数，保留零值与过期状态，不显示协议诊断和厂家档案', async () => {
    await mount('monitor');
    for (const text of ['当前运行信息', '速度门限', '已过期']) expect(host.textContent).toContain(text);
    for (const text of ['协议必填项', 'MQTT 诊断', '工参主题', '寄存器原值', '提供方编码', '来源 ID', '厂家资料', '说明 / 协议字段']) expect(host.textContent).not.toContain(text);
    await tab('感知目标');
    expect(host.textContent).toContain('目标速度'); expect(host.textContent).toContain('已过期');
    expect(host.textContent).toContain('不能视为当前活动目标');
  });

  it('调测保留完整度、协议字段、报文与接入配置，同时保持无权限状态', async () => {
    await mount('commission');
    for (const text of ['协议必填项', 'MQTT 诊断', '寄存器原值', 'user_cfg0_raw']) expect(host.textContent).toContain(text);
    expect(host.textContent).not.toContain('厂家资料');
    await tab('连接与协议配置');
    for (const text of ['接入身份与协议', '来源 ID', '连接配置', '无查看权限']) expect(host.textContent).toContain(text);
    expect(host.textContent).not.toContain('测试厂家');
    await tab('报文字段样本'); expect(host.textContent).toContain('用于核对解析字段');
  });

  it('设备管理使用既有详情显示完整档案，精确匹配厂家资料且切换设备清除旧型号', async () => {
    const detail = { device: { device_id: 'A', device_no: 'A', name: '雷达 A', enabled: false, source_mode: 'mock', simulated: true },
      model: 'T02', vendor: '测试厂家', firmware_version: '1.0', longitude: 0, latitude: 0, owner_name: '测试单位', region_name: '测试区域' };
    await mount('catalog', catalogInformation(detail));
    for (const text of ['设备档案与资料', '固件版本', '测试厂家', '测试单位', '测试区域', '否 / 关闭', '厂家资料']) expect(host.textContent).toContain(text);
    for (const text of ['协议必填项', '连接配置', '来源 ID', '运行参数']) expect(host.textContent).not.toContain(text);
    await tab('厂家资料'); expect(host.textContent).toContain('厂家标称参数'); expect(host.textContent).not.toContain('未匹配型号');
    props.information = catalogInformation({ device: { device_id: 'B', name: '设备 B' }, model: 'UNKNOWN' }); await settle();
    expect(host.textContent).toContain('未匹配型号');
    expect(host.textContent).not.toContain('雷达 A');
  });
});
