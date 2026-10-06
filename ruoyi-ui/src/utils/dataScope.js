// 账号数据范围（ZT-14）：决定账号在告警、目标、统计、导出、证据等业务数据里能看到哪些单位，由服务端按范围过滤。
export const DEFAULT_DATA_SCOPE = 'OWN_ORG';

export const DATA_SCOPE_OPTIONS = [
  { value: 'OWN_ORG', label: '本单位', hint: '只能看到所属单位的数据。' },
  { value: 'OWN_ORG_TREE', label: '本单位及下级单位', hint: '能看到所属单位和它下面各级单位的数据。' },
  { value: 'ALL', label: '全部单位', hint: '能看到所有单位的数据，只给需要统管全局的账号。' }
];

// CUSTOM/NONE 只出现在早期审批建的账号上，这里只展示，不能再选。
const LABELS = {
  ALL: '全部单位',
  OWN_ORG: '本单位',
  OWN_ORG_TREE: '本单位及下级单位',
  CUSTOM: '指定单位和区域',
  NONE: '不能查看业务数据'
};

export function dataScopeLabel(value) {
  return LABELS[value] || '—';
}

export function isAssignableDataScope(value) {
  return DATA_SCOPE_OPTIONS.some(option => option.value === value);
}

export function dataScopeHint(value) {
  return DATA_SCOPE_OPTIONS.find(option => option.value === value)?.hint || '';
}

/** 按所属单位自动维护的范围：换单位等于换了能看的数据。 */
export function followsOrganization(value) {
  return value === 'OWN_ORG' || value === 'OWN_ORG_TREE';
}
