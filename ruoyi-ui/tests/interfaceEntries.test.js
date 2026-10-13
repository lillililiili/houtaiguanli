import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import InterfacesView from '@/views/operations/InterfacesView.vue';
import { externalInterfacesApi } from '@/api/externalInterfaces.js';

const permissions = vi.hoisted(() => ({ edit: true }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: code => code !== 'interfaces.op' || permissions.edit }) }));
vi.mock('@/api/externalInterfaces.js', () => ({ externalInterfacesApi: { get: vi.fn(), save: vi.fn() } }));
let app, host;
async function settle() { for (let i = 0; i < 25; i++) { await Promise.resolve(); await nextTick(); } }
beforeEach(() => {
  vi.clearAllMocks();
  externalInterfacesApi.get.mockImplementation(async kind => ({ kind, name: '已有接口', source_mode: 'live', version: 2, status: 'NOT_CONFIGURED', enabled: false }));
});
afterEach(() => { app?.unmount(); host?.remove(); });

it.each([true, false])('接口配置不再提供 MQTT 配置入口，其他接口按原权限使用（操作权限 %s）', async edit => {
  permissions.edit = edit;
  host = document.createElement('div'); document.body.append(host);
  app = createApp(InterfacesView); app.use(ElementPlus); app.mount(host); await settle();
  const tabs = [...host.querySelectorAll('[role=tab]')];
  expect(tabs.map(el => el.querySelector('b').textContent)).toEqual(['飞行任务输入', '天气预报', '电话通知录音']);
  expect(host.textContent).not.toContain('MQTT');
  expect(host.querySelectorAll('.mqtt-editor-dialog')).toHaveLength(0);
  tabs.find(el => el.textContent.includes('天气预报')).click(); await settle();
  expect(externalInterfacesApi.get).toHaveBeenLastCalledWith('WEATHER_FORECAST');
  expect(host.textContent).toContain('天气预报接口');
  const save = [...host.querySelectorAll('button')].find(el => el.textContent.trim() === '保存配置');
  expect(Boolean(save)).toBe(edit);
  expect(externalInterfacesApi.save).not.toHaveBeenCalled();
});
