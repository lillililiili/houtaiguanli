import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import MaintenanceMessages from '@/components/MaintenanceMessages.vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
const mocks = vi.hoisted(()=>({push:vi.fn(),hasMenu:vi.fn(()=>true),hasPermission:vi.fn(()=>true)}));
vi.mock('vue-router',()=>({useRouter:()=>({push:mocks.push})}));
vi.mock('@/stores/auth.js',()=>({useAuthStore:()=>mocks}));
vi.mock('@/api/deviceMaintenance.js',()=>({deviceMaintenanceApi:{messages:vi.fn(),readMessage:vi.fn()}}));
vi.mock('@/services/realtime.js',()=>({useRealtimeRefresh:(topics,reload)=>({trigger:reload})}));
let app,host;
async function settle(){for(let i=0;i<20;i++){await Promise.resolve();await nextTick();}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(MaintenanceMessages);app.use(ElementPlus);app.mount(host);await settle();host.querySelector('button').click();await settle();}
beforeEach(()=>{vi.clearAllMocks();mocks.hasMenu.mockReturnValue(true);deviceMaintenanceApi.messages.mockResolvedValue({items:[{task_id:'T1',device_id:'D1',device_name:'目标设备',reason:'连接异常',reported_at:1000,workflow_state:'PENDING'}],page:1,size:6,total:1,unread_count:1});deviceMaintenanceApi.readMessage.mockResolvedValue({});});
afterEach(()=>{app?.unmount();host?.remove();document.body.innerHTML='';});
describe('运维消息入口',()=>{
 it('只请求待办，已读但待确认办结的消息继续显示',async()=>{
   deviceMaintenanceApi.messages.mockResolvedValue({items:[{task_id:'read-task',device_name:'已读待办设备',workflow_state:'READY_TO_COMPLETE',read_at:1000}],total:1,unread_count:0});
   await mount();
   expect(deviceMaintenanceApi.messages).toHaveBeenLastCalledWith({page:1,size:6,pending_only:true});
   expect(document.querySelectorAll('.maintenance-message-list article')).toHaveLength(1);
   expect(document.body.textContent).toContain('已读待办设备');
   expect(document.body.textContent).toContain('去处理');
   expect(document.body.textContent).not.toContain('查看结果');
   expect(document.body.textContent).toContain('仅显示待办，处理中和已办结不显示。');
   expect(host.querySelector('button').getAttribute('aria-label')).toContain('0条未读');
 });
 it.each([0,1])('接手或办结后剩余 %s 条时自动返回有效页并更新角标',async remaining=>{
   let total=7;
   deviceMaintenanceApi.messages.mockImplementation(async({page})=>({items:page===1&&total?[{task_id:'remaining-task',device_name:'仍待办设备',workflow_state:'PENDING'}]:[],total,unread_count:total}));
   await mount();
   document.querySelector('.el-pagination .btn-next').click();await settle();
   expect(deviceMaintenanceApi.messages).toHaveBeenLastCalledWith({page:2,size:6,pending_only:true});
   total=remaining;
   window.dispatchEvent(new Event('admin:maintenance-changed'));await settle();
   expect(deviceMaintenanceApi.messages).toHaveBeenLastCalledWith({page:1,size:6,pending_only:true});
   expect(document.querySelector('.el-pagination')).toBeNull();
   expect(host.querySelector('button').getAttribute('aria-label')).toContain(`${remaining}条未读`);
   expect(document.querySelectorAll('.maintenance-message-list article')).toHaveLength(remaining);
   if(!remaining)expect(document.body.textContent).toContain('暂无待办运维消息');
 });
 it('铃铛点击展开，Escape关闭，不能仅在隐藏弹层中渲染内容',async()=>{await mount();expect(host.querySelector('button').getAttribute('aria-expanded')).toBe('true');expect(document.querySelector('.maintenance-message-popover').style.display).not.toBe('none');document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));await settle();expect(host.querySelector('button').getAttribute('aria-expanded')).toBe('false');});
 it('只有点击去处理才标记已读并带待办设备定位',async()=>{await mount();expect(deviceMaintenanceApi.readMessage).not.toHaveBeenCalled();const button=[...document.querySelectorAll('button')].find(el=>el.textContent.trim()==='去处理');button.click();await settle();expect(deviceMaintenanceApi.readMessage).toHaveBeenCalledWith('T1');expect(mocks.push).toHaveBeenCalledWith({path:'/operations/maintenance',query:{task_id:'T1',device_id:'D1'}});});
 it('没有异常处理菜单权限时禁用跳转',async()=>{mocks.hasMenu.mockReturnValue(false);await mount();expect([...document.querySelectorAll('button')].find(el=>el.textContent.trim()==='去处理').disabled).toBe(true);expect(deviceMaintenanceApi.readMessage).not.toHaveBeenCalled();});
 it('请求失败显示未知，不能伪装未读数为零或保留旧消息',async()=>{deviceMaintenanceApi.messages.mockRejectedValue(new Error('消息读取失败'));await mount();expect(host.querySelector('button').getAttribute('aria-label')).toContain('未读数未知');expect(document.body.textContent).toContain('消息读取失败');expect(document.body.textContent).not.toContain('目标设备');});
});
