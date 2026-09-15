import { formatTime } from '@/utils/format.js';

export const informationStates = {
  RECEIVED: ['已获取', 'success'], CONFIGURED: ['已登记', 'info'], STALE: ['已过期', 'warning'],
  NOT_REPORTED: ['未上报', 'warning'], NOT_CONFIGURED: ['未登记', 'info'], INVALID: ['格式异常', 'danger'],
  REDACTED: ['无查看权限', 'info'], NOT_APPLICABLE: ['当前不适用', 'info']
};
export function informationValue(field) {
  if (field.value === null || field.value === undefined || field.value === '') return '—';
  if (field.unit === 'epoch_ms') return formatTime(field.value);
  if (typeof field.value === 'boolean') return field.value ? '是 / 开启' : '否 / 关闭';
  if (typeof field.value === 'object') return JSON.stringify(field.value, null, 2);
  return String(field.value);
}
export function informationCoverage(sections = []) {
  const required = sections.flatMap(section => section.fields || []).filter(field => field.required);
  return { required: required.length, received: required.filter(field => field.status === 'RECEIVED').length,
    missing: required.filter(field => ['NOT_REPORTED', 'INVALID'].includes(field.status)).length,
    stale: required.filter(field => field.status === 'STALE').length };
}
