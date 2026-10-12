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
async function mount(query = 'maintenance_context_id=todo-b&device_id=b') {
  router = createRouter({ history:createMemoryHistory(),routes:[{path:'/operations/commission',component:CommissionView},{path:'/operations/maintenance',component:{template:'<p>设备异常处理工作台</p>'}}] });
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
  it('从处置进入调测时最近心跳与工作台统一为北京时间',async()=>{
    const at=Date.UTC(2026,0,2,7,46,11);
    commissionApi.information.mockResolvedValue({device_id:'b',task_supported:false,sections:[{code:'link',fields:[{key:'last_heartbeat_at',value:at,unit:'epoch_ms',status:'RECEIVED'}]}],sample_sections:[]});
    await mount();
    expect(host.querySelector('.connection-fields').textContent).toContain('最近心跳');
    expect(host.querySelector('.connection-fields').textContent).toContain('15:46:11');
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it('高级诊断返回同一处置单，不混入主办理流程',async()=>{
    await mount();
    expect(host.textContent).not.toContain('提交恢复核验');
    expect(host.textContent).not.toContain('开始处理');
    [...host.querySelectorAll('button')].find(el=>el.textContent.trim()==='返回本次设备异常处理').click();await settle();
    expect(router.currentRoute.value.path).toBe('/operations/maintenance');
    expect(router.currentRoute.value.query.task_id).toBe('todo-b');
    expect(commissionApi.create).not.toHaveBeenCalled();
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
    await mount('maintenance_context_id=todo-b&device_id=a');
    expect(host.textContent).toContain('链接中的设备与运维待办不匹配');
    expect(commissionApi.information).not.toHaveBeenCalled();
    expect(deviceApi.detail).not.toHaveBeenCalled();
  });
  it('无权读取待办时不显示其他设备作为处理目标',async()=>{
    deviceMaintenanceApi.workflow.mockRejectedValue(Object.assign(new Error('无权查看待办'),{status:403})); await mount();
    expect(host.textContent).toContain('无权查看待办'); expect(commissionApi.information).not.toHaveBeenCalled();
  });
  it('主动上报设备显示诊断信息，不要求创建调测任务',async()=>{
    commissionApi.information.mockImplementation(async id=>({device_id:id,task_supported:false,sections:[],sample_sections:[]}));
    deviceMaintenanceApi.workflow.mockResolvedValue({...flow(),state:'PROCESSING',version:2,allowed_actions:['SAVE_PROGRESS','LINK_COMMISSION']});
    await mount();
    expect(host.textContent).toContain('连接状态');expect(host.textContent).not.toContain('创建新任务');
    expect(host.textContent).not.toContain('保存进展');expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
});
