import { describe, expect, it } from 'vitest';
import { healthLabel } from '@/views/operations/maintenance/presentation.js';

describe('设备异常处理：健康状态文字', () => {
  it('离线或停用时不沿用最后一次“正常”', () => {
    expect(healthLabel('GOOD', 'OFFLINE')).toBe('未知（离线）');
    expect(healthLabel('BAD', 'OFFLINE')).toBe('未知（离线）');
    expect(healthLabel('GOOD', 'DISABLED')).toBe('未知（停用）');
  });
  it('在线或异常上报时按健康上报显示，未传连接状态保持原样', () => {
    expect(healthLabel('GOOD', 'ONLINE')).toBe('正常');
    expect(healthLabel('BAD', 'ABNORMAL')).toBe('故障');
    expect(healthLabel('DEGRADED')).toBe('异常');
    expect(healthLabel(null, 'ONLINE')).toBe('待确认');
  });
});
