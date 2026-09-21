import { request, mutation } from '@/services/apiClient.js';
export const externalInterfacesApi = {
  get: kind => request({ url: `/v1/external-interface-configs/${encodeURIComponent(kind)}` }),
  save: (kind, body) => mutation('put', `/v1/external-interface-configs/${encodeURIComponent(kind)}`, body)
};
export const weatherSensorsApi = {
  get: id => request({ url: `/v1/weather-sensors/${encodeURIComponent(id)}` }),
  create: body => mutation('post', '/v1/weather-sensors', body),
  update: (id, body) => mutation('put', `/v1/weather-sensors/${encodeURIComponent(id)}`, body)
};
