import { expect, it, vi } from 'vitest';
import { deviceCatalogParams, readDevicePages } from '@/utils/deviceCatalog.js';

it.each([101, 501])('完整读取 %s 台设备并保持同一筛选条件', async total => {
  const rows = Array.from({ length: total }, (_, i) => ({ device_id: `device-${i}`, health_code: i % 2 ? 'BAD' : 'GOOD' }));
  const load = vi.fn(async ({ page, size }) => ({ total, items: rows.slice((page - 1) * size, page * size) }));
  const result = await readDevicePages(load, deviceCatalogParams({ channel: 'TCP' }));
  expect(result).toEqual({ items: rows, total, truncated: false });
  for (const [query] of load.mock.calls) expect(query).toMatchObject({ enabled: true, statistics_scope: true, channel: 'TCP' });
});

it('重复页或缺页不能冒充完整设备清单', async () => {
  const load = vi.fn().mockResolvedValue({ items: [{ device_id: 'same' }], total: 3 });
  await expect(readDevicePages(load)).rejects.toThrow('设备清单读取不完整');
  expect(load).toHaveBeenCalledTimes(2);
});

it('明确查看停用设备时覆盖默认启用条件', () => {
  expect(deviceCatalogParams({ enabled: false })).toEqual({ enabled: false, statistics_scope: true });
});
