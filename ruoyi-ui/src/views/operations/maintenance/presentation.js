export function maintenanceTime(value) {
  if (value === null || value === undefined || value === '') return '未记录';
  const date = new Date(Number(value));
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}

export const connectionLabel = value => ({ ONLINE: '在线', OFFLINE: '离线', ABNORMAL: '异常上报', DISABLED: '停用', UNKNOWN: '待确认' })[value] || '待确认';
// 设备离线或停用时，最后一次健康上报已不能说明现在的状况（心跳不续期旧健康、在线不代表恢复），不再显示“正常”。
export const healthLabel = (value, connectivity) => connectivity === 'OFFLINE' ? '未知（离线）'
  : connectivity === 'DISABLED' ? '未知（停用）'
    : ({ GOOD: '正常', BAD: '故障', DEGRADED: '异常', UNKNOWN: '待确认' })[value] || '待确认';
export const alarmLabel = value => value === true ? '有告警' : value === false ? '无告警' : '待确认';
export const finishedMaintenance = state => ['COMPLETED', 'LEGACY_HANDLED'].includes(state);
export const maintenanceActionLabel = action => ({ START: '接手处理', CONFIRM_COMPLETE: '确认办结', SAVE_PROGRESS: '记录处理' })[action] || '';
export const maintenanceEventLabel = action => ({
  START: '已接手处理', SAVE_PROGRESS: '记录处理', CONFIRM_COMPLETE: '已确认办结', COMPLETE: '历史待办已完成',
  AUTO_VERIFY: '系统自动核验', AUTO_VERIFY_RECOVERY: '系统自动核验', AUTO_RECOVERY: '系统自动核验', AUTO_CHECK: '系统自动核验', AUTO_RECOVERED: '设备恢复核验通过', RECOVERY_PASSED: '设备恢复核验通过',
  RECOVERY_INVALIDATED: '恢复依据发生变化', RECURRENCE: '设备再次异常', REPORTED: '异常已上报',
  REPORT_LINKED: '关联异常上报', LINK_COMMISSION: '关联调测任务', SUBMIT_VERIFICATION: '历史提交恢复核验',
  VERIFY_RECOVERY: '历史恢复核验', RESUME: '继续处理'
})[action] || '处置状态更新';

// Values drive the display; never infer recovery from ONLINE or a completed command.
export function primaryMaintenanceAction(workflow) {
  const declared = typeof workflow?.primary_action === 'string' ? workflow.primary_action : workflow?.primary_action?.action;
  if (['START', 'CONFIRM_COMPLETE'].includes(declared)) return declared;
  if (workflow?.primary_action != null) return '';
  return ({ PENDING: 'START', READY_TO_COMPLETE: 'CONFIRM_COMPLETE' })[workflow?.state] || '';
}

export function maintenanceNextStep(workflow) {
  if (workflow?.next_step) return workflow.next_step;
  return ({
    PENDING: '接手后检查设备供电、网络和异常原因；系统会持续核验恢复情况。',
    PROCESSING: '请按当前异常原因处理。设备恢复后，系统会自动提示确认办结。',
    PENDING_VERIFICATION: '系统正在核验设备恢复情况，无需手动提交核验。',
    READY_TO_COMPLETE: '设备已恢复，核验依据已满足。确认后本次处置办结。',
    COMPLETED: '本次处置已办结，处理过程和恢复依据保留供查阅。',
    LEGACY_HANDLED: '历史记录只读保留；历史反馈不代表设备恢复。'
  })[workflow?.state] || '正在读取下一步操作。';
}
