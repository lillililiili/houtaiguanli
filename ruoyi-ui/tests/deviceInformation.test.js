import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import CommissionView from '@/views/operations/CommissionView.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';
import { informationCoverage, informationValue } from '@/utils/deviceInformation.js';
import { deviceReferenceModels } from '@/utils/deviceReferenceModels.js';

vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }));
vi.mock('@/api/devices.js', () => ({ deviceApi: { list: vi.fn(), detail: vi.fn() }, commissionApi: { information: vi.fn(), list: vi.fn(), create: vi.fn() } }));
let app, host;
async function settle() { for (let i=0; i<12; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(component, props) {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(component, props); app.use(ElementPlus); app.mount(host); await settle();
}
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); });
const information = { device_id: 'one', device_no: 'D1', name: '雷达', model: 'T02', task_supported: false,
  generated_at: 100_000, sections: [{ code: 'work_parameters', title: '上报工参', source: '协议 A', received_at: 100_000,
    fields: [{ key: 'zero', label: '零值', value: 0, required: true, status: 'RECEIVED' },
      { key: 'missing', label: '缺失字段', value: null, required: true, status: 'NOT_REPORTED' }] }], sample_sections: [], notes: [] };

describe('调测设备信息', () => {
  it('保留零值、关闭和缺失状态，覆盖率不把过期或缺失算作已获取', () => {
    expect(informationValue({ value: 0 })).toBe('0');
    expect(informationValue({ value: false })).toBe('否 / 关闭');
    expect(informationValue({ value: null })).toBe('—');
    expect(informationCoverage(information.sections)).toEqual({ required: 2, received: 1, missing: 1, stale: 0 });
  });
  it('完整信息显示来源与缺失项，筛选后仅保留问题字段', async () => {
    await mount(DeviceInformationPanel, { information });
    expect(host.textContent).toContain('1 / 2 项已获取');
    expect(host.textContent).toContain('协议 A');
    expect(host.textContent).toContain('缺失字段');
    host.querySelector('input[type="checkbox"]').click(); await settle();
    expect(host.textContent).not.toContain('零值');
    expect(host.textContent).toContain('缺失字段');
  });
  it('型号参考精确区分 GRB 与 GRF，三通道参数不套用四通道', () => {
    expect(deviceReferenceModels.find(item => item.models.includes('UAD-GRF-578D'))).toBeUndefined();
    expect(deviceReferenceModels.find(item => item.models.includes('UAD-GRB-578D'))).toBeTruthy();
    expect(JSON.stringify(deviceReferenceModels.find(item => item.models.includes('UAD-GD01')))).toContain('三通道');
  });
  it('MQTT 设备只显示上报信息，首次只请求一次，刷新失败清除旧的实时状态', async () => {
    deviceApi.list.mockResolvedValue({ items: [{ device_id: 'one', device_no: 'D1', name: '雷达' }] });
    deviceApi.detail.mockResolvedValue({ connection: {} });
    commissionApi.list.mockResolvedValue({ items: [] });
    commissionApi.information.mockResolvedValue(information);
    await mount(CommissionView);
    expect(commissionApi.information).toHaveBeenCalledTimes(1);
    expect(host.textContent).not.toContain('创建新任务');
    expect(host.textContent).toContain('接入诊断信息');
    commissionApi.information.mockRejectedValue(new Error('采集服务暂不可用'));
    [...host.querySelectorAll('button')].find(b => b.textContent.includes('刷新信息')).click(); await settle();
    expect(host.textContent).toContain('采集服务暂不可用');
    expect(host.textContent).not.toContain('1 / 2 项已获取');
  });
});
