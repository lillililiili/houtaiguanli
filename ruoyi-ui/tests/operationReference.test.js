import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus from 'element-plus';
import CommissionView from '@/views/operations/CommissionView.vue';
import DeviceCatalogPreview from '@/views/operations/DeviceCatalogPreview.vue';
import { commissionApi, deviceApi } from '@/api/devices.js';
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }));
vi.mock('@/api/devices.js', () => ({ deviceApi: { list: vi.fn(), detail: vi.fn() }, commissionApi: Object.fromEntries(['information','list','report','get','events','create','connect','configure','start'].map(key => [key,vi.fn()])) }));
let app, host;
async function settle() { for (let i=0; i<16; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(component, props) { host=document.createElement('div'); document.body.append(host); app=createApp(component,props); app.use(ElementPlus); app.mount(host); await settle(); }
function button(text) { return [...host.querySelectorAll('button')].find(item=>item.textContent.includes(text)); }
beforeEach(() => {
  vi.clearAllMocks();
  deviceApi.list.mockResolvedValue({ items: [{device_id:'a',device_no:'A',name:'甲雷达',region_name:'东区'}, {device_id:'b',device_no:'B',name:'乙雷达',region_name:'西区'}],total:2 });
  deviceApi.detail.mockResolvedValue({ connection: {host:'192.0.2.1',port:9001,transport:'TCP'} });
  commissionApi.information.mockImplementation(async id=>({device_id:id,name:id,task_supported:true,sections:[],sample_sections:[]}));
  commissionApi.list.mockImplementation(async params=>({items:[{commission_id:`task-${params.device_id}`,commission_no:`历史-${params.device_id}`,device_name:params.device_id,status:'PASSED',simulated:true,started_at:1000,finished_at:5000}],total:1}));
  commissionApi.report.mockImplementation(async id=>({commission_no:id,warning:'模拟报告',status:'PASSED',simulated:true}));
});
afterEach(()=>{ app?.unmount(); host?.remove(); document.querySelectorAll('.el-overlay').forEach(item=>item.remove()); });
describe('参考布局中的真实业务交互',()=>{
  it('设备搜索仅缩小选择列表，不创建任务或更换当前调测对象',async()=>{
    await mount(CommissionView);
    const input=host.querySelector('input[placeholder="搜索设备名称 / 编号"]'); input.value='乙'; input.dispatchEvent(new Event('input',{bubbles:true})); await settle();
    expect(host.querySelector('.commission-device-list').textContent).toContain('乙雷达');
    expect(host.querySelector('.commission-device-list').textContent).not.toContain('甲雷达');
    expect(host.querySelector('.commission-details').textContent).toContain('甲雷达');
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it('切换设备后历史记录按设备重新读取，查看历史报告不会创建调测任务',async()=>{
    await mount(CommissionView);
    expect(host.textContent).toContain('历史-a');
    button('乙雷达').click(); await settle();
    expect(host.textContent).not.toContain('历史-a');
    expect(host.textContent).toContain('历史-b');
    button('查看报告').click(); await settle();
    expect(commissionApi.report).toHaveBeenCalledWith('task-b');
    expect(document.body.textContent).toContain('模拟报告');
    expect(commissionApi.create).not.toHaveBeenCalled();
  });
  it('活动任务锁定设备选择，未连接时不可编辑配置',async()=>{
    const task={commission_id:'active-a',device_id:'a',status:'CREATED',version:1};
    commissionApi.list.mockResolvedValue({items:[task],total:1});
    commissionApi.events.mockResolvedValue({items:[],next_seq:0});
    await mount(CommissionView);
    expect(button('乙雷达').disabled).toBe(true);
    expect(host.querySelector('input[placeholder="建立连接后配置"]').disabled).toBe(true);
    expect(button('建立连接').disabled).toBe(false);
    expect(button('开始协议调测')).toBeUndefined();
  });
  it('设备预览保留零坐标、缺失标识和来源，不把未登记参数显示为正常',async()=>{
    await mount(DeviceCatalogPreview,{detail:{device:{device_id:'a',device_no:'A',name:'设备A',source_mode:'replay',connectivity:'UNKNOWN'},longitude:0,latitude:0,altitude_m:0}});
    expect(host.querySelector('.preview-fields').textContent).toMatch(/0\s*°/);
    expect(host.querySelector('.preview-fields').textContent).toMatch(/0\s*m/);
    expect(host.textContent).toContain('回放数据');
    expect(host.textContent).toContain('尚未登记安装地址');
    expect(host.textContent).toContain('未知');
  });
});
