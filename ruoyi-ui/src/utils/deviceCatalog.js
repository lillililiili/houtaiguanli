// Default device population shared by catalog, monitoring and commissioning.
export function deviceCatalogParams(filters = {}) {
  return { enabled: true, statistics_scope: true, ...filters };
}

export async function readDevicePages(load, params = {}) {
  const query = { ...params };
  const rows = new Map();
  for (let page = 1; ; page++) {
    const result = await load({ ...query, page, size: 100, sort: 'device_no_asc' });
    const before = rows.size;
    for (const row of result.items || []) rows.set(row.device_id, row);
    const total = result.total ?? rows.size;
    if (rows.size === total) return { items: [...rows.values()], total, truncated: false };
    if (rows.size === before || rows.size > total) throw new Error('设备清单读取不完整，请刷新重试');
  }
}
