export function formatTime(value) {
  if (value === null || value === undefined || value === '') return '—';
  const date = new Date(Number(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
}

export function display(value, fallback = '—') {
  return value === null || value === undefined || value === '' ? fallback : value;
}

export function statusType(value) {
  return ({ ONLINE: 'success', GOOD: 'success', ACTIVE: 'success', PASSED: 'success', SUCCESS: 'success',
    ABNORMAL: 'danger', BAD: 'danger', FAILED: 'danger', FAILURE: 'danger', DISABLED: 'info', OFFLINE: 'info',
    UNKNOWN: 'warning', DEGRADED: 'warning', UNTESTABLE: 'warning', PROCESSING: 'warning' })[value] || 'info';
}

export function statusText(value) {
  return ({ ONLINE: '在线', OFFLINE: '离线', ABNORMAL: '异常', UNKNOWN: '未知', GOOD: '良好', DEGRADED: '一般', BAD: '异常',
    ACTIVE: '启用', DISABLED: '停用', SUCCESS: '成功', FAILURE: '失败', PASSED: '通过', FAILED: '失败',
    UNTESTABLE: '无法测试', PENDING: '待处理', PROCESSING: '处理中', CANCELLED: '已取消', CONFIGURED: '待调测', CONNECTED: '已连接', RUNNING: '调测中' })[value] || display(value);
}
