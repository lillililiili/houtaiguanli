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
it('天气页签显示模式选择，模拟模式只保留所需字段',async () => {
  [...host.querySelectorAll('[role=tab]')].find(el=>el.textContent.includes('天气预报')).click();await settle();
  expect(externalInterfacesApi.get).toHaveBeenLastCalledWith('WEATHER_FORECAST');
  expect(host.textContent).toContain('真实接入');
  [...host.querySelectorAll('label')].find(el=>el.textContent.includes('模拟接口')).click();await settle();
  expect(host.textContent).toContain('预报区域');
  expect(host.textContent).not.toContain('服务地址');
  expect(host.textContent).not.toContain('凭据引用');
});
