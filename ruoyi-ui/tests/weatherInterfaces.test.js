import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import InterfacesView from '@/views/operations/InterfacesView.vue';
import { externalInterfacesApi } from '@/api/externalInterfaces.js';
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true, user: {} }) }));
vi.mock('@/api/externalInterfaces.js', () => ({ externalInterfacesApi: { get: vi.fn(), save: vi.fn() } }));
let app, host;
async function settle() { for (let i=0;i<20;i++) { await Promise.resolve(); await nextTick(); } }
beforeEach(async () => {
  vi.clearAllMocks();
  externalInterfacesApi.get.mockImplementation(async kind => ({kind,name:'天气配置',source_mode:'live',version:0,status:'NOT_CONFIGURED',enabled:false}));
  host=document.createElement('div'); document.body.append(host); app=createApp(InterfacesView);app.use(ElementPlus);app.mount(host);await settle();
});
afterEach(() => {app.unmount();host.remove();});
it('天气配置只提供真实接入，保留服务地址和凭据引用', async () => {
  [...host.querySelectorAll('[role=tab]')].find(el => el.textContent.includes('天气预报')).click(); await settle();
  expect(externalInterfacesApi.get).toHaveBeenLastCalledWith('WEATHER_FORECAST');
  expect(host.textContent).toContain('真实接入');
  expect(host.textContent).not.toContain('模拟接口');
  expect(host.textContent).toContain('服务地址');
  expect(host.textContent).toContain('凭据引用');
});

it('旧模拟配置不推导已接入；保存只能提交真实来源，不生成模拟预报', async () => {
  externalInterfacesApi.get.mockImplementation(async kind => ({ kind, name: '天气配置', source_mode: 'mock', version: 1, status: 'STALE', enabled: false }));
  externalInterfacesApi.save.mockImplementation(async (kind, body) => ({ ...body, kind, status: 'NOT_CONNECTED', enabled: false }));
  [...host.querySelectorAll('[role=tab]')].find(el => el.textContent.includes('天气预报')).click(); await settle();
  expect(host.querySelector('.interface-status').textContent).toContain('历史测试配置，不代表正式接入');
  expect(host.textContent).not.toContain('已启用（模拟）');
  [...host.querySelectorAll('button')].find(el => el.textContent.includes('保存配置')).click(); await settle();
  expect(externalInterfacesApi.save).toHaveBeenCalledWith('WEATHER_FORECAST', expect.objectContaining({ source_mode: 'live' }));
  expect(host.querySelector('.interface-status').textContent).toContain('待接入');
});
