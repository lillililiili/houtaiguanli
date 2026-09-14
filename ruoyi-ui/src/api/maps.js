import { mutation, newIdempotencyKey, request } from '@/services/apiClient.js';

export const mapApi = {
  catalog: () => request({ url: '/v1/map-packages' }),
  upload: ({ file, cityCode, cityName, reason }, onProgress) => {
    const form = new FormData();
    form.append('file', file);
    form.append('city_code', cityCode);
    form.append('city_name', cityName);
    form.append('reason', reason);
    return request({
      method: 'post', url: '/v1/map-packages', data: form, timeout: 0,
      headers: { 'Idempotency-Key': newIdempotencyKey('map-upload') },
      onUploadProgress: event => onProgress?.(event.total ? Math.round(event.loaded * 100 / event.total) : 0)
    });
  },
  activate: (packageId, expectedVersion, reason) => mutation('post', `/v1/map-packages/${packageId}/activate`, {
    expected_version: expectedVersion, reason
  }),
  rollback: (expectedVersion, reason) => mutation('post', '/v1/map-packages/rollback', {
    expected_version: expectedVersion, reason
  }),
  remove: (packageId, expectedVersion, reason) => mutation('delete', `/v1/map-packages/${packageId}`, {
    expected_version: expectedVersion, reason
  })
};
