import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import CommissionView from '@/views/operations/CommissionView.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';

vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true, hasMenu: () => true }) }));
vi.mock('@/api/devices.js', () => ({ deviceApi: { list: vi.fn(), detail: vi.fn() }, commissionApi: Object.fromEntries(['information','list','report','get','events','create','connect','configure','start','cancel'].map(key => [key,vi.fn()])) }));
vi.mock('@/api/deviceMaintenance.js', () => ({ deviceMaintenanceApi: { workflow:vi.fn(),act:vi.fn(),list:vi.fn() } }));
let app, host, router;
const flow = (id = 'todo-b', device = 'b') => ({ task:{task_id:id,device_id:device,device_name:'乙设备',reason:'连接异常',reported_at:1000},state:'PENDING',version:1,allowed_actions:['START'],events:[],commission_tasks:[] });
async function settle() { for(let i=0;i<24;i++){await Promise.resolve(); await nextTick();} }
async function mount(query = 'maintenance_task_id=todo-b&device_id=b') {
  router = createRouter({ history:createMemoryHistory(),routes:[{path:'/operations/commission',component:CommissionView},{path:'/operations/monitor',component:{template:'<p>待办列表</p>'}}] });
  await router.push(`/operations/commission?${query}`); await router.isReady();
  host=document.createElement('div');document.body.append(host);app=createApp({template:'<router-view />'});app.use(router);app.use(ElementPlus);app.mount(host);await settle();
}
beforeEach(()=>{
  vi.clearAllMocks();
  deviceApi.list.mockResolvedValue({items:[{device_id:'a',name:'甲设备',device_no:'A'},{device_id:'b',name:'乙设备',device_no:'B'}],total:2});
  deviceApi.detail.mockResolvedValue({connection:{}});
  commissionApi.list.mockResolvedValue({items:[],total:0});
  commissionApi.information.mockImplementation(async id=>({device_id:id,task_supported:true,sections:[],sample_sections:[]}));
  deviceMaintenanceApi.workflow.mockResolvedValue(flow());
  deviceMaintenanceApi.list.mockResolvedValue({items:[],total:0});
});
afterEach(()=>{app?.unmount();host?.remove();document.querySelectorAll('.el-overlay,.el-message').forEach(el=>el.remove());vi.useRealTimers();});
describe('运维待办深链到既有调测页',()=>{
  it('返回运维待办进入可查询列表，不跳到无待办组件的监测页',async()=>{
    await mount();
    [...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='返回运维待办').click();await settle();
    expect(router.currentRoute.value.path).toBe('/operations/commission');
    expect(router.currentRoute.value.query.view).toBe('maintenance');
    expect(deviceMaintenanceApi.list).toHaveBeenCalledWith({status:'ACTIVE',page:1,size:10});
    expect(host.textContent).toContain('当前没有这类运维待办');
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it('直接打开待办列表会加载列表但不选取其他设备',async()=>{
    await mount('view=maintenance');
    expect(deviceMaintenanceApi.list).toHaveBeenCalledTimes(1);
    expect(commissionApi.information).not.toHaveBeenCalled();
    expect(host.textContent).toContain('运维待办');
  });
  it.each(['cancel','poll'])('调测结束后重新读取维护守卫，不保留已消失的活动阻断 %s',async mode=>{
    vi.useFakeTimers();
    let task={commission_id:'ct-b',device_id:'b',source_mode:'live',simulated:false,status:'CREATED',version:0};
    deviceApi.list.mockResolvedValue({items:[{device_id:'b',name:'乙设备',device_no:'B',source_mode:'live',simulated:false}],total:1});
    commissionApi.list.mockImplementation(async()=>({items:[task],total:1}));
    commissionApi.get.mockImplementation(async()=>task);commissionApi.events.mockResolvedValue({items:[],next_seq:0});
    commissionApi.cancel.mockImplementation(async()=>task={...task,status:'CANCELLED',version:1});
    deviceMaintenanceApi.workflow.mockImplementation(async()=>({...flow(),state:'PROCESSING',version:2,allowed_actions:task.status==='CREATED'?['SAVE_PROGRESS']:['SAVE_PROGRESS','SUBMIT_VERIFICATION'],blocked_reason:task.status==='CREATED'?'设备仍有执行中的调测':null}));
    await mount();
    const submit=()=>[...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='提交恢复核验');
    expect(submit().disabled).toBe(true);
    if(mode==='cancel'){[...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='取消任务').click();await settle();}
    else{task={...task,status:'CANCELLED',version:1};await vi.advanceTimersByTimeAsync(2100);await settle();}
    expect(submit().disabled).toBe(false);expect(host.textContent).not.toContain('设备仍有执行中的调测');
    expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it.each([true, false])('模拟任务关联遵循当前设备明确许可 %s，关联不启动调测',async allowed=>{
    const task={commission_id:'ct-b',device_id:'b',source_mode:'mock',simulated:true,status:'CREATED',version:0};
    deviceApi.list.mockResolvedValue({items:[{device_id:'b',name:'乙设备',device_no:'B',source_mode:'mock',simulated:true}],total:1});
    commissionApi.list.mockResolvedValue({items:[task],total:1});
    commissionApi.get.mockResolvedValue(task);commissionApi.events.mockResolvedValue({items:[],next_seq:0});
    commissionApi.information.mockResolvedValue({device_id:'b',task_supported:true,simulation_allowed:allowed,sections:[],sample_sections:[]});
    const processing={...flow(),state:'PROCESSING',version:2,allowed_actions:['LINK_COMMISSION','SAVE_PROGRESS']};
    deviceMaintenanceApi.workflow.mockResolvedValue(processing);
    deviceMaintenanceApi.act.mockResolvedValue({...processing,version:3,commission_tasks:[task]});
    await mount();
    const link=[...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='关联当前调测任务');
    expect(link.disabled).toBe(!allowed);link.click();await settle();
    if(allowed)expect(deviceMaintenanceApi.act).toHaveBeenCalledWith('todo-b',{action:'LINK_COMMISSION',expected_version:2,commission_id:'ct-b'},expect.any(String));
    else expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
    expect(commissionApi.create).not.toHaveBeenCalled();expect(commissionApi.connect).not.toHaveBeenCalled();expect(commissionApi.start).not.toHaveBeenCalled();
  });
  it('直接选择待办设备，读取不会创建任务或接手待办',async()=>{
    await mount();
    expect(commissionApi.information).toHaveBeenCalledWith('b');
    expect(commissionApi.information).not.toHaveBeenCalledWith('a');
    expect(host.querySelector('.commission-details').textContent).toContain('乙设备');
    expect(commissionApi.create).not.toHaveBeenCalled(); expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
    expect([...host.querySelectorAll('button')].find(el=>el.textContent.includes('创建新任务')).disabled).toBe(true);
  });
  it('伪造不匹配的设备链接不会退回第一台设备或启用调测',async()=>{
    await mount('maintenance_task_id=todo-b&device_id=a');
    expect(host.textContent).toContain('链接中的设备与运维待办不匹配');
    expect(commissionApi.information).not.toHaveBeenCalled();
    expect(deviceApi.detail).not.toHaveBeenCalled();
  });
  it('无权读取待办时不显示其他设备作为处理目标',async()=>{
    deviceMaintenanceApi.workflow.mockRejectedValue(new Error('无权查看待办')); await mount();
    expect(host.textContent).toContain('无权查看待办'); expect(commissionApi.information).not.toHaveBeenCalled();
  });
  it('已打开待办失去范围后清除缓存设备树，重新授权后恢复同一目标',async()=>{
    await mount();
    expect(host.textContent).toContain('乙设备');
    deviceMaintenanceApi.workflow.mockRejectedValue(Object.assign(new Error('运维任务不存在或不可见'),{status:404}));
    [...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='刷新待办').click();await settle();
    expect(host.textContent).toContain('运维任务不存在或不可见');
    expect(host.textContent).not.toContain('乙设备');expect(host.textContent).not.toContain('甲设备');
    expect(commissionApi.create).not.toHaveBeenCalled();
    deviceMaintenanceApi.workflow.mockResolvedValue(flow());
    [...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='重新加载').click();await settle();
    expect(host.textContent).toContain('乙设备');expect(host.textContent).not.toContain('甲设备');
    expect(commissionApi.information).not.toHaveBeenCalledWith('a');
  });
  it('接手成功后才启用调测，MQTT 不支持调测时继续显示诊断信息',async()=>{
    commissionApi.information.mockImplementation(async id=>({device_id:id,task_supported:false,sections:[],sample_sections:[]}));
    deviceMaintenanceApi.act.mockResolvedValue({...flow(),state:'PROCESSING',version:2,allowed_actions:['SAVE_PROGRESS','SUBMIT_VERIFICATION']});
    await mount();[...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='开始处理').click();await settle();
    expect(deviceMaintenanceApi.act).toHaveBeenCalledWith('todo-b',expect.objectContaining({action:'START',expected_version:1}),expect.any(String));
    expect(host.textContent).toContain('连接状态');expect(host.textContent).not.toContain('创建新任务');expect(host.textContent).toContain('保存进展');
  });
});
