import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import DevicesView from '@/views/operations/DevicesView.vue';
import { deviceApi, integrationApi } from '@/api/devices.js';

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));

const permission = vi.hoisted(() => ({ allowed: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => permission.allowed }) }));
vi.mock('@/api/devices.js', () => ({
  deviceApi: { list: vi.fn(), options: vi.fn(), overview: vi.fn(), detail: vi.fn(), protocolStatus: vi.fn(), remove: vi.fn() },
  integrationApi: { protocols: vi.fn() }, mqttApi: {}
}));
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { prompt: vi.fn() }
}));

let app, host;
const row = { device_id: 'device-1', device_no: 'DEV-001', name: '测试设备', enabled: false, version: 3 };
async function settle() {
  for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); }
}
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(DevicesView); app.use(ElementPlus); app.mount(host);
  await settle();
}
function deleteButton() { return [...host.querySelectorAll('button')].find(button => button.textContent.trim() === '删除'); }

beforeEach(() => {
  vi.clearAllMocks(); permission.allowed = true;
  deviceApi.list.mockResolvedValue({ items: [{ ...row }], total: 1, page: 1, size: 10 });
  deviceApi.options.mockResolvedValue({ types: [], channels: [] });
  deviceApi.overview.mockResolvedValue({ total: 1 });
  deviceApi.detail.mockResolvedValue({ device: row });
  deviceApi.protocolStatus.mockResolvedValue({});
  deviceApi.remove.mockResolvedValue({ device_id: row.device_id, version: 4 });
  integrationApi.protocols.mockResolvedValue([]);
  ElMessageBox.prompt.mockResolvedValue({ value: ' 设备退役 ' });
});
afterEach(() => { app?.unmount(); host?.remove(); });

describe('设备删除交互', () => {
  it('确认后携带版本和原因删除，并清空详情、刷新列表及统计', async () => {
    await mount();
    deviceApi.list.mockResolvedValue({ items: [], total: 0, page: 1, size: 10 });
    deleteButton().click(); await settle();
    expect(deviceApi.remove).toHaveBeenCalledWith('device-1', { version: 3, reason: '设备退役' });
    expect(deviceApi.list).toHaveBeenCalledTimes(2);
    expect(deviceApi.overview).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('请选择设备');
    expect(host.textContent).not.toContain('DEV-001');
  });
  it('取消确认时不发送删除请求', async () => {
    ElMessageBox.prompt.mockRejectedValue('cancel');
    await mount(); deleteButton().click(); await settle();
    expect(deviceApi.remove).not.toHaveBeenCalled();
    expect(ElMessage.error).not.toHaveBeenCalled();
  });
  it('启用设备提示先停用', async () => {
    deviceApi.list.mockResolvedValue({ items: [{ ...row, enabled: true }], total: 1, page: 1, size: 10 });
    await mount(); deleteButton().click(); await settle();
    expect(ElMessage.warning).toHaveBeenCalledWith('请先停用设备，再执行删除');
    expect(deviceApi.remove).not.toHaveBeenCalled();
  });
  it('没有操作权限时禁用删除按钮', async () => {
    permission.allowed = false;
    await mount(); expect(deleteButton().disabled).toBe(true);
  });
  it('版本冲突时显示后端原因并重新加载', async () => {
    deviceApi.remove.mockRejectedValue({ code: 'VERSION_CONFLICT', message: '请刷新后重试' });
    await mount(); deleteButton().click(); await settle();
    expect(ElMessage.error).toHaveBeenCalledWith('请刷新后重试');
    expect(deviceApi.list).toHaveBeenCalledTimes(2);
    expect(ElMessage.success).not.toHaveBeenCalled();
  });
  it('删除最后一页唯一设备后回到仍有数据的上一页', async () => {
    deviceApi.list.mockResolvedValueOnce({ items: [{ ...row }], total: 11, page: 2, size: 10 });
    await mount();
    deviceApi.list.mockResolvedValueOnce({ items: [], total: 10, page: 2, size: 10 })
      .mockResolvedValueOnce({ items: [{ ...row, device_id: 'device-2', device_no: 'DEV-002' }], total: 10, page: 1, size: 10 });
    deleteButton().click(); await settle();
    expect(deviceApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
    expect(host.textContent).toContain('DEV-002');
  });
  it('确认弹窗未关闭时阻止重复点击', async () => {
    let confirm;
    ElMessageBox.prompt.mockImplementationOnce(() => new Promise(resolve => { confirm = resolve; }));
    await mount(); deleteButton().click(); deleteButton().click(); await settle();
    expect(ElMessageBox.prompt).toHaveBeenCalledTimes(1);
    confirm({ value: '设备退役' }); await settle();
    expect(deviceApi.remove).toHaveBeenCalledTimes(1);
  });
});
