import { describe, expect, it } from 'vitest';
import { formatDeviceNo } from '@/utils/format.js';

describe('设备编号展示', () => {
  it('将地图模拟器编号显示为短码，并区分相邻编号', () => {
    expect(formatDeviceNo('map-sim-5ga-d-muse4e0p-ygwl5')).toBe('5G-A-ygwl5');
    expect(formatDeviceNo('map-sim-countermeasure-d-muse4e0p-5c8dn')).toBe('CM-5c8dn');
    expect(formatDeviceNo('map-sim-countermeasure-d-muse4e0p-5c8dn'))
      .not.toBe(formatDeviceNo('map-sim-countermeasure-d-muse4e0p-5c8dm'));
  });

  it('为旧批次编号生成短且可辨识的展示码', () => {
    expect(formatDeviceNo('sim-1003205529-4806-4')).toBe('SIM-4806-4');
  });

  it('不截断未识别的人工编号和空值语义', () => {
    expect(formatDeviceNo('RADAR-01')).toBe('RADAR-01');
    expect(formatDeviceNo('operator-device-number-20261007-001')).toBe('operator-device-number-20261007-001');
    expect(formatDeviceNo('')).toBe('—');
  });
});
