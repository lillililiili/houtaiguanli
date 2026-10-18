import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import MaintenanceView from '@/views/operations/MaintenanceView.vue';
import { deviceMaintenanceApi } from '@/api/deviceMaintenance.js';
import { legacyMaintenanceTarget } from '@/utils/maintenanceNavigation.js';
import { maintenanceTime } from '@/views/operations/maintenance/presentation.js';

const access = vi.hoisted(() => ({ hasPermission: vi.fn(() => true), hasMenu: vi.fn(() => true) }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => access }));
vi.mock('@/api/deviceMaintenance.js', () => ({ deviceMaintenanceApi: { workflow: vi.fn(), act: vi.fn(), list: vi.fn() } }));
const realtime = vi.hoisted(() => ({ reload: null }));
vi.mock('@/services/realtime.js', () => ({ useRealtimeRefresh: (topics, reload) => { realtime.reload = reload; return { trigger: reload }; } }));
let app, host, router, activeFlow;
const makeFlow = (id = 'case-radar', state = 'PENDING') => ({
  workflow_version: 2, task: { task_id: id, device_id: `device-${id}`, device_name: `设备 ${id}`, device_no: id, reason: '设备上报故障', workflow_version: 2, reported_at: Date.UTC(2026, 0, 2, 0) },
  state, version: 1, allowed_actions: state === 'PENDING' ? ['START'] : state === 'READY_TO_COMPLETE' ? ['CONFIRM_COMPLETE', 'SAVE_PROGRESS'] : ['SAVE_PROGRESS'],
  primary_action: state === 'PENDING' ? 'START' : state === 'READY_TO_COMPLETE' ? 'CONFIRM_COMPLETE' : null,
  current_device_state: { connectivity: 'ONLINE', health_code: 'BAD', has_alarm: true, last_heartbeat_at: Date.now(), health_observed_at: Date.now(), alarm_observed_at: Date.now() },
  recovery: { result: 'FAIL', reason: '设备仍有告警', checked_at: Date.now(), reason_codes: ['HAS_ALARM'] }, events: [], related_plans: []
});
async function settle() { for (let i = 0; i < 30; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(query = 'task_id=case-radar') {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/operations/maintenance', component: MaintenanceView },
    { path: '/operations/commission', component: { template: '<p>诊断</p>' } },
    { path: '/operations/monitor', component: { template: '<p>监测</p>' } }
  ] });
  router.beforeEach(to => legacyMaintenanceTarget(to) || true);
  await router.push(`/operations/maintenance?${query}`); await router.isReady();
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ template: '<router-view />' }); app.use(router); app.use(ElementPlus); app.mount(host); await settle();
}
const button = label => [...host.querySelectorAll('button')].find(item => item.textContent.trim() === label);
async function writeNote(text) {
  host.querySelector('.el-collapse-item__header').click(); await settle();
  const input = host.querySelector('#maintenance-note'); input.value = text; input.dispatchEvent(new Event('input', { bubbles: true })); await settle();
}
beforeEach(() => {
  vi.clearAllMocks(); access.hasPermission.mockReturnValue(true); access.hasMenu.mockReturnValue(true);
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  activeFlow = makeFlow();
  deviceMaintenanceApi.workflow.mockImplementation(async () => structuredClone(activeFlow));
  deviceMaintenanceApi.list.mockImplementation(async () => ({ items: [{ ...activeFlow.task, workflow_state: activeFlow.state, current_device_state: activeFlow.current_device_state }], total: 1 }));
});
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; vi.useRealTimers(); });

describe('设备异常工作台的两步办理', () => {
  it('没有变化不重复读取，SSE 更新保留备注且刷新按钮不转圈', async () => {
    activeFlow = makeFlow('case-other', 'PROCESSING'); await mount('task_id=case-other');
    await writeNote('待核对电源');
    const reads = deviceMaintenanceApi.workflow.mock.calls.length;
    await vi.advanceTimersByTimeAsync(60_000); await settle();
    expect(deviceMaintenanceApi.workflow).toHaveBeenCalledTimes(reads);
    let finish;
    deviceMaintenanceApi.workflow.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const updating = realtime.reload(['device']); await settle();
    expect(button('刷新').classList.contains('is-loading')).toBe(false);
    expect(host.querySelector('#maintenance-note').value).toBe('待核对电源');
    expect(host.textContent).toContain('设备 case-other');
    finish(makeFlow('case-other', 'READY_TO_COMPLETE')); await updating; await settle();
    expect(host.querySelector('#maintenance-note').value).toBe('待核对电源');
    expect(button('确认办结').disabled).toBe(false);
    expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it.each(['case-radar', 'case-sensor-other'])('首次读取只展示设备事实和接手入口，不自动写状态：%s', async id => {
    activeFlow = makeFlow(id); await mount(`task_id=${id}`);
    expect(host.textContent).toContain('发生了什么'); expect(host.textContent).toContain('现在什么状态');
    expect(button('接手处理').disabled).toBe(false); expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('提交恢复核验'); expect(host.textContent).not.toContain('执行恢复核验');
    expect(host.textContent).not.toContain('创建新任务');
  });
  it('接手后自动核验，恢复后只有确认办结，无需单独提交核验', async () => {
    deviceMaintenanceApi.act.mockImplementation(async (id, body) => {
      activeFlow = { ...makeFlow(id, body.action === 'START' ? 'PROCESSING' : 'COMPLETED'), version: activeFlow.version + 1 };
      return structuredClone(activeFlow);
    });
    await mount(); button('接手处理').click(); await settle();
    expect(host.textContent).toContain('等待设备恢复'); expect(button('确认办结')).toBeUndefined();
    activeFlow = { ...makeFlow('case-radar', 'READY_TO_COMPLETE'), version: 3, recovery: { result: 'PASS', reason: '有效上报确认健康正常且无告警' } };
    await realtime.reload(['device']); await settle();
    expect(button('确认办结').disabled).toBe(false);
    button('确认办结').click(); await settle();
    expect(deviceMaintenanceApi.act.mock.calls.map(call => call[1].action)).toEqual(['START', 'CONFIRM_COMPLETE']);
    expect(host.querySelector('.case-heading').textContent).toContain('已办结');
  });
  it('接手前已恢复可以直接办结，在线但缺少健康依据不可办结', async () => {
    activeFlow = makeFlow('case-radar', 'READY_TO_COMPLETE'); await mount();
    expect(button('接手处理')).toBeUndefined(); expect(button('确认办结').disabled).toBe(false);
    activeFlow = { ...makeFlow('case-radar', 'PENDING'), current_device_state: { connectivity: 'ONLINE' }, recovery: { result: 'UNKNOWN', reason: '缺少新的健康上报' } };
    await realtime.reload(['device']); await settle();
    expect(button('确认办结')).toBeUndefined(); expect(host.textContent).toContain('缺少新的健康上报');
    expect(host.querySelectorAll('.current-device-state strong')[1].textContent).toBe('待确认');
  });
  it.each([
    ['PENDING', 'START', '接手处理', '正在接手处理…', 'PROCESSING'],
    ['READY_TO_COMPLETE', 'CONFIRM_COMPLETE', '确认办结', '正在确认办结…', 'COMPLETED'],
    ['PROCESSING', 'SAVE_PROGRESS', '记录处理', '正在保存记录…', 'PROCESSING']
  ])('正常在途请求显示动作进度，不显示重试：%s', async (state, action, label, progress, nextState) => {
    activeFlow = makeFlow('case-radar', state);
    let complete;
    deviceMaintenanceApi.act.mockImplementation(() => new Promise(resolve => { complete = resolve; }));
    await mount();
    if (action === 'SAVE_PROGRESS') await writeNote('已检查电源。');
    button(label).click(); await settle();
    expect(button(progress).disabled).toBe(true);
    expect(button('按原请求重试确认')).toBeUndefined();
    expect(deviceMaintenanceApi.act).toHaveBeenCalledTimes(1);
    complete(makeFlow('case-radar', nextState)); await settle();
    expect(button(progress)).toBeUndefined();
    expect(button('按原请求重试确认')).toBeUndefined();
  });
  it('只有失败且结果未知时显示同键重试，重试在途恢复动作进度', async () => {
    activeFlow = makeFlow('case-radar', 'READY_TO_COMPLETE');
    deviceMaintenanceApi.act.mockRejectedValueOnce(new Error('连接中断'));
    await mount(); button('确认办结').click(); await settle();
    expect(button('按原请求重试确认').disabled).toBe(false);
    const original = deviceMaintenanceApi.act.mock.calls[0];
    let complete;
    deviceMaintenanceApi.act.mockImplementation(() => new Promise(resolve => { complete = resolve; }));
    button('按原请求重试确认').click(); await settle();
    expect(button('按原请求重试确认')).toBeUndefined();
    expect(button('正在确认办结…').disabled).toBe(true);
    expect(deviceMaintenanceApi.act.mock.calls[1]).toEqual(original);
    complete(makeFlow('case-radar', 'COMPLETED')); await settle();
    expect(host.querySelector('.case-heading').textContent).toContain('已办结');
    expect(button('按原请求重试确认')).toBeUndefined();
  });
  it('只读权限不能接手或办结，不要求设备调测权限', async () => {
    access.hasPermission.mockImplementation(code => code === 'monitoring.read');
    activeFlow.allowed_actions = []; await mount();
    expect(button('接手处理').disabled).toBe(true); expect(host.textContent).toContain('当前账号只能查看');
    expect(host.textContent).not.toContain('设备接入调测');
  });
  it.each([['ABNORMAL', '异常上报'], ['ONLINE', '在线'], ['UNKNOWN', '待确认']])('独立显示连接与健康事实，连接标签不解除后端故障阻断：%s', async (connection, label) => {
    activeFlow = makeFlow(`case-${connection.toLowerCase()}`, 'PROCESSING');
    activeFlow.current_device_state.connectivity = connection;
    activeFlow.recovery = { result: 'FAIL', reason: '设备仍有健康异常', reason_codes: ['HEALTH_NOT_GOOD'] };
    await mount(`task_id=${activeFlow.task.task_id}`);
    const facts = host.querySelectorAll('.current-device-state strong');
    expect(facts[0].textContent).toBe(label);
    expect(facts[1].textContent).toBe('故障');
    expect(host.querySelector('.recovery-fact').textContent).toContain('设备仍有健康异常');
    expect(button('确认办结')).toBeUndefined();
    expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it('后台状态改变与刷新不会丢失未保存备注或替用户提交', async () => {
    activeFlow = makeFlow('case-radar', 'PROCESSING'); await mount(); await writeNote('检查过电源，正在核对网络。');
    activeFlow = makeFlow('case-radar', 'READY_TO_COMPLETE'); await realtime.reload(['device']); await settle();
    expect(host.querySelector('#maintenance-note').value).toBe('检查过电源，正在核对网络。');
    expect(router.currentRoute.value.query.task_id).toBe('case-radar'); expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it('接手不把尚未保存的说明标成已保存，离开仍提示保留', async () => {
    deviceMaintenanceApi.act.mockResolvedValue(makeFlow('case-radar', 'PROCESSING'));
    await mount(); await writeNote('待核对的说明'); button('接手处理').click(); await settle();
    const navigation = router.push('/operations/monitor'); await settle();
    expect(document.body.textContent).toContain('处理说明尚未保存');
    [...document.querySelectorAll('button')].find(item => item.textContent.trim() === '继续填写').click();
    await navigation; await settle();
    expect(router.currentRoute.value.path).toBe('/operations/maintenance');
    expect(host.querySelector('#maintenance-note').value).toBe('待核对的说明');
  });
  it('刷新失败保留但标记旧事实，禁办结；重新成功后恢复按钮', async () => {
    activeFlow = makeFlow('case-radar', 'READY_TO_COMPLETE'); await mount();
    deviceMaintenanceApi.workflow.mockRejectedValueOnce(new Error('网络中断'));
    button('刷新').click(); await settle();
    expect(host.textContent).toContain('状态待确认：网络中断'); expect(host.textContent).toContain('上次成功读取');
    expect(button('确认办结').disabled).toBe(true);
    button('刷新').click(); await settle(); expect(button('确认办结').disabled).toBe(false);
  });
  it('办结版本冲突保留备注并展示最新异常，不显示成功', async () => {
    activeFlow = makeFlow('case-radar', 'READY_TO_COMPLETE'); await mount(); await writeNote('等待确认。');
    activeFlow = makeFlow('case-radar', 'PROCESSING');
    deviceMaintenanceApi.act.mockRejectedValue(Object.assign(new Error('设备再次异常'), { status: 409 }));
    button('确认办结').click(); await settle();
    expect(host.querySelector('#maintenance-note').value).toBe('等待确认。'); expect(host.textContent).toContain('设备再次异常');
    expect(button('确认办结')).toBeUndefined();
  });
  it('原设备/待办链接进入统一工作台，高级诊断携带同一设备与处置上下文', async () => {
    await mount(); await router.push('/operations/commission?maintenance_task_id=case-radar&device_id=device-case-radar'); await settle();
    expect(router.currentRoute.value.path).toBe('/operations/maintenance');
    expect(router.currentRoute.value.query.task_id).toBe('case-radar');
    const advanced = [...host.querySelectorAll('.el-collapse-item__header')].find(item => item.textContent.includes('高级诊断')); advanced.click(); await settle();
    button('设备接入调测').click(); await settle();
    expect(router.currentRoute.value.query).toEqual({ device_id: 'device-case-radar', maintenance_context_id: 'case-radar' });
    expect(deviceMaintenanceApi.act).not.toHaveBeenCalled();
  });
  it('未指定处置单时自动选择不会偷偷把全设备列表变成单设备列表', async () => {
    await mount(''); expect(router.currentRoute.value.query).toEqual({ task_id: 'case-radar' });
  });
  it('北京时间独立于浏览器本地时区', () => {
    expect(maintenanceTime(Date.UTC(2026, 0, 2, 0, 18))).toContain('08:18');
    expect(maintenanceTime(null)).toBe('未记录');
  });
  it.each(['case-shared-radar', 'case-shared-sensor'])('多个可见任务共用处置单时给出明确说明和前后台一致编号：%s', async id => {
    activeFlow = makeFlow(id);
    activeFlow.related_plans = [{ plan_id: `${id}-plan-a` }, { plan_id: `${id}-plan-b` }];
    await mount(`task_id=${id}`);
    expect(host.querySelector('.case-summary').textContent).not.toContain('处置单编号');
    expect(host.querySelector('.case-facts').textContent).not.toContain(`${id}-plan-a`);
    expect(host.querySelector('.tech-details').textContent).toContain(id);
    expect(host.querySelector('.shared-case-hint').textContent).toContain('不会重复新增待办');
    activeFlow.related_plans = [{ plan_id: `${id}-plan-a` }];
    await realtime.reload(['device']); await settle();
    expect(host.querySelector('.shared-case-hint')).toBeNull();
    activeFlow.workflow_version = 1; activeFlow.task.workflow_version = 1;
    activeFlow.related_plans.push({ plan_id: `${id}-plan-b` });
    await realtime.reload(['device']); await settle();
    expect(host.querySelector('.shared-case-hint')).toBeNull();
  });
  it.each(['case-radar', 'case-other-device'])('列表显示固定的人工上报时间和任务，自动核验不能改写：%s', async id => {
    activeFlow = makeFlow(id);
    const reportedAt = Date.UTC(2026, 2, 8, 3, 28, 46);
    activeFlow.task.report_summary = { origin: 'SYSTEM', latest_manual_report: { actor_name: '值班甲', reported_at: reportedAt, plan_id: 'plan-new', plan_no: 'TEST-NEW' }, related_plans: [{ plan_id: 'plan-new', plan_no: 'TEST-NEW' }] };
    deviceMaintenanceApi.list.mockImplementation(async () => ({ items: [{ ...activeFlow.task, updated_at: Date.now(), recovery: activeFlow.recovery }], total: 1 }));
    await mount(`task_id=${id}`);
    const before = host.querySelector('.case-item').textContent;
    expect(before).toContain('人工上报'); expect(before).toContain('值班甲'); expect(before).toContain('关联任务 TEST-NEW');
    expect(before).toContain('最近上报'); expect(before).toContain(maintenanceTime(reportedAt));
    expect(before).not.toContain('核验于'); expect(before).not.toContain('更新于');
    expect(host.querySelector('.latest-manual-report').textContent).toContain('本次上报已关联到系统发现的这份处置单');
    activeFlow.recovery.checked_at += 60000;
    await realtime.reload(['device']); await settle();
    expect(host.querySelector('.case-item').textContent).toBe(before);
  });
  it('系统发现和旧接口都保留首次时间，不把读取或核验当成上报', async () => {
    activeFlow.task.report_summary = { origin: 'SYSTEM', related_plans: [] };
    activeFlow.task.reported_by_name = '系统自动发现';
    await mount();
    expect(host.querySelector('.case-item').textContent).toContain('系统发现');
    expect(host.querySelector('.case-time').textContent).toContain('首次发现');
    expect(host.querySelector('.latest-manual-report')).toBeNull();
    delete activeFlow.task.report_summary;
    await realtime.reload(['device']); await settle();
    expect(host.querySelector('.case-time').textContent).toBe(`首次上报 ${maintenanceTime(activeFlow.task.reported_at)}`);
  });
  it('已办结依据保持冻结，与设备当前再次故障分开展示', async () => {
    const evidenceTime = Date.UTC(2026, 1, 3, 2, 15);
    activeFlow = { ...makeFlow('case-radar', 'COMPLETED'), recovery: { result: 'PASS', reason: '办结时设备恢复', checked_at: evidenceTime, evidence: { connectivity: 'ONLINE', health_code: 'GOOD', has_alarm: false, last_heartbeat_at: evidenceTime, health_observed_at: evidenceTime, alarm_observed_at: evidenceTime } } };
    await mount();
    expect(host.querySelector('.current-device-state').textContent).toContain('故障');
    expect(host.querySelector('.recovery-evidence').textContent).toContain('正常');
    expect(host.querySelector('.recovery-evidence').textContent).toContain('10:15');
    expect(host.textContent).toContain('以上为办结时保存的依据');
    expect(button('确认办结')).toBeUndefined(); expect(button('接手处理')).toBeUndefined();
  });
});

describe('设备异常处置单筛选', () => {
  const listItem = (id, state) => ({ ...makeFlow(id, state).task, workflow_state: state });
  async function choose(label) {
    host.querySelector('#maintenance-status').closest('.el-select').querySelector('.el-input__inner').click(); await settle();
    [...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === label).click(); await settle();
  }
  it.each([
    ['已办结', 'COMPLETED', ['case-done-1', 'case-done-2']],
    ['全部记录', 'ALL', ['case-done-3', 'case-open-9']]
  ])('选择“%s”按 %s 读取，原选中单不在新列表时右侧改看新列表第一张', async (label, status, ids) => {
    deviceMaintenanceApi.list.mockImplementation(async ({ status: requested }) => requested === status
      ? { items: ids.map(id => listItem(id, 'COMPLETED')), total: ids.length }
      : { items: [listItem('case-radar', 'PENDING')], total: 1 });
    deviceMaintenanceApi.workflow.mockImplementation(async id => structuredClone(makeFlow(id, id === 'case-radar' ? 'PENDING' : 'COMPLETED')));
    await mount('task_id=case-radar');
    await choose(label);
    expect(deviceMaintenanceApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ status, page: 1 }));
    expect(router.currentRoute.value.query.task_id).toBe(ids[0]);
    expect(host.querySelector('.case-heading h2').textContent).toBe(`设备 ${ids[0]}`);
  });
  it('选中单仍在新列表里时保持不动', async () => {
    deviceMaintenanceApi.list.mockImplementation(async () => ({ items: [listItem('case-other', 'PROCESSING'), listItem('case-radar', 'PENDING')], total: 2 }));
    await mount('task_id=case-radar');
    await choose('全部记录');
    expect(router.currentRoute.value.query.task_id).toBe('case-radar');
  });
  it('新列表为空时清空右侧详情', async () => {
    deviceMaintenanceApi.list.mockImplementation(async ({ status }) => status === 'READY_TO_COMPLETE' ? { items: [], total: 0 } : { items: [listItem('case-radar', 'PENDING')], total: 1 });
    await mount('task_id=case-radar');
    await choose('待确认办结');
    expect(router.currentRoute.value.query.task_id).toBeUndefined();
    expect(host.querySelector('.case-heading')).toBeNull();
    expect(host.textContent).toContain('选择一份处置单开始查看');
  });
});

