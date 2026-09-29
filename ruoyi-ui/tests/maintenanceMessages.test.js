import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import MaintenanceMessages from '@/components/MaintenanceMessages.vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
const mocks = vi.hoisted(()=>({push:vi.fn(),hasMenu:vi.fn(()=>true),hasPermission:vi.fn(()=>true)}));
vi.mock('vue-router',()=>({useRouter:()=>({push:mocks.push})}));
vi.mock('@/stores/auth.js',()=>({useAuthStore:()=>mocks}));
vi.mock('@/api/deviceMaintenance.js',()=>({deviceMaintenanceApi:{messages:vi.fn(),readMessage:vi.fn()}}));
let app,host;
async function settle(){for(let i=0;i<20;i++){await Promise.resolve();await nextTick();}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(MaintenanceMessages);app.use(ElementPlus);app.mount(host);await settle();host.querySelector('button').click();await settle();}
beforeEach(()=>{vi.clearAllMocks();mocks.hasMenu.mockReturnValue(true);deviceMaintenanceApi.messages.mockResolvedValue({items:[{task_id:'T1',device_id:'D1',device_name:'目标设备',reason:'连接异常',reported_at:1000,workflow_state:'PENDING'}],page:1,size:6,total:1,unread_count:1});deviceMaintenanceApi.readMessage.mockResolvedValue({});});
afterEach(()=>{app?.unmount();host?.remove();document.body.innerHTML='';});
describe('运维消息入口',()=>{
 it('铃铛点击展开，Escape关闭，不能仅在隐藏弹层中渲染内容',async()=>{await mount();expect(host.querySelector('button').getAttribute('aria-expanded')).toBe('true');expect(document.querySelector('.maintenance-message-popover').style.display).not.toBe('none');document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));await settle();expect(host.querySelector('button').getAttribute('aria-expanded')).toBe('false');});
 it('只有点击去处理才标记已读并带待办设备定位',async()=>{await mount();expect(deviceMaintenanceApi.readMessage).not.toHaveBeenCalled();const button=[...document.querySelectorAll('button')].find(el=>el.textContent.trim()==='去处理');button.click();await settle();expect(deviceMaintenanceApi.readMessage).toHaveBeenCalledWith('T1');expect(mocks.push).toHaveBeenCalledWith({path:'/operations/commission',query:{maintenance_task_id:'T1',device_id:'D1'}});});
 it('没有调测菜单权限时禁用跳转',async()=>{mocks.hasMenu.mockReturnValue(false);await mount();expect([...document.querySelectorAll('button')].find(el=>el.textContent.trim()==='去处理').disabled).toBe(true);expect(deviceMaintenanceApi.readMessage).not.toHaveBeenCalled();});
 it('请求失败显示未知，不能伪装未读数为零或保留旧消息',async()=>{deviceMaintenanceApi.messages.mockRejectedValue(new Error('消息读取失败'));await mount();expect(host.querySelector('button').getAttribute('aria-label')).toContain('未读数未知');expect(document.body.textContent).toContain('消息读取失败');expect(document.body.textContent).not.toContain('目标设备');});
});
