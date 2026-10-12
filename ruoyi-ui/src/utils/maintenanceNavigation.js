export function legacyMaintenanceTarget(to) {
  if (to.path !== '/operations/commission' || (!to.query.maintenance_task_id && to.query.view !== 'maintenance')) return null;
  return { path: '/operations/maintenance', query: {
    ...(typeof to.query.maintenance_task_id === 'string' ? { task_id: to.query.maintenance_task_id } : {}),
    ...(typeof to.query.device_id === 'string' ? { device_id: to.query.device_id } : {})
  }, replace: true };
}
