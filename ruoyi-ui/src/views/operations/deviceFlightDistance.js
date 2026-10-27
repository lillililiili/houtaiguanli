import { reactive } from 'vue';
import { automationRuleApi } from '@/api/automationRules.js';

/* 四通道反制设备的「附近航班安全距离」（2026-10-27 用户确认）：默认用规则管理「航班避让」里的数字，
   也可以给这台设备单独设置。没有查看权限时整块不显示，没有修改权限时只能看。 */
const FIELDS = ['force_land_block_km', 'force_land_warn_km', 'force_land_max_altitude_m', 'drive_away_warn_km', 'drive_away_max_altitude_m'];

export function useFlightDistance() {
  const state = reactive({ visible: false, canManage: false, custom: false, version: null, defaults: null, values: {}, original: '' });
  let sequence = 0;

  function apply(data, defaults) {
    state.defaults = defaults;
    state.custom = data?.custom === true;
    state.version = data?.version ?? null;
    state.values = Object.fromEntries(FIELDS.map(key => [key, Number((data?.custom ? data : defaults)[key])]));
    state.original = snapshot();
  }

  /** deviceId 为空表示新登记的设备：先显示默认值。 */
  async function load(deviceId) {
    const mine = ++sequence;
    state.visible = false;
    try {
      const policy = await automationRuleApi.flightAvoidance();
      const data = deviceId ? await automationRuleApi.deviceFlightDistance(deviceId) : null;
      if (mine !== sequence) return;
      apply(data, policy);
      state.canManage = policy.can_manage === true;
      state.visible = true;
    } catch {
      // 没有查看规则的权限，或这台设备读不到：不显示这块，设备其他资料照常保存。
      if (mine === sequence) state.visible = false;
    }
  }

  function clear() { sequence++; state.visible = false; }

  function snapshot() { return JSON.stringify([state.custom, state.custom ? state.values : null]); }

  function validate() {
    if (!state.visible || !state.custom) return null;
    if (FIELDS.some(key => !Number.isFinite(state.values[key]))) return '请填写全部航班安全距离和高度';
    if (state.values.force_land_warn_km < state.values.force_land_block_km) return '迫降的提醒范围不能小于禁止反制范围';
    return null;
  }

  /** 设备保存成功后再保存距离；没改动或不能改时什么都不做。 */
  async function save(deviceId) {
    if (!state.visible || !state.canManage || snapshot() === state.original) return;
    const body = { custom: state.custom, expected_version: state.version, ...(state.custom ? state.values : {}) };
    const data = await automationRuleApi.saveDeviceFlightDistance(deviceId, body);
    apply(data, state.defaults);
  }

  function defaultText() {
    const d = state.defaults;
    if (!d) return '';
    return `迫降：禁止反制范围 ${Number(d.force_land_block_km)} 公里，提醒范围 ${Number(d.force_land_warn_km)} 公里，${d.force_land_max_altitude_m} 米以下；`
      + `驱离：提醒范围 ${Number(d.drive_away_warn_km)} 公里，${d.drive_away_max_altitude_m} 米以下`;
  }

  return { state, load, clear, validate, save, defaultText };
}
