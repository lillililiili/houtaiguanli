import { mutation, queryString, request } from '@/services/apiClient.js';

export const deviceMaintenanceApi = {
  list: params => request({ url: `/v1/device-maintenance-tasks${queryString(params)}` }),
  handle: (id, body, key) => mutation('post', `/v1/device-maintenance-tasks/${encodeURIComponent(id)}/handling`, body, { idempotencyKey: key }),
  workflow: id => request({ url: `/v1/device-maintenance-tasks/${encodeURIComponent(id)}/workflow` }),
  act: (id, body, key) => mutation('post', `/v1/device-maintenance-tasks/${encodeURIComponent(id)}/workflow/actions`, body, { idempotencyKey: key }),
  messages: params => request({ url: `/v1/device-maintenance-messages${queryString(params)}` }),
  readMessage: id => mutation('post', `/v1/device-maintenance-messages/${encodeURIComponent(id)}/read`, {})
};
