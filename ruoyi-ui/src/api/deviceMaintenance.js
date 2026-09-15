import { mutation, queryString, request } from '@/services/apiClient.js';

export const deviceMaintenanceApi = {
  list: params => request({ url: `/v1/device-maintenance-tasks${queryString(params)}` }),
  handle: (id, body, key) => mutation('post', `/v1/device-maintenance-tasks/${encodeURIComponent(id)}/handling`, body, { idempotencyKey: key })
};
