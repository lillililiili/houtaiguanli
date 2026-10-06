import { mutation, newIdempotencyKey, request } from '@/services/apiClient.js';

const BASE = '/v1/advisory-voice-recordings';
const path = recordingId => `${BASE}/${encodeURIComponent(recordingId)}`;

/* 电话通知录音：上传、试听、选用、停止使用和删除未用过的录音。 */
export const voiceRecordingApi = {
  catalog: () => request({ url: BASE }),
  upload: ({ file, name, transcript }, onProgress) => {
    const form = new FormData();
    form.append('file', file);
    form.append('name', name);
    form.append('transcript', transcript);
    return request({
      method: 'post', url: BASE, data: form, timeout: 0,
      headers: { 'Idempotency-Key': newIdempotencyKey('voice-recording-upload') },
      onUploadProgress: event => onProgress?.(event.total ? Math.round(event.loaded * 100 / event.total) : 0)
    });
  },
  /* 试听要带登录凭据，所以取回音频内容后在页面里播放，不能直接把接口地址交给播放器。 */
  content: recordingId => request({ url: `${path(recordingId)}/content`, responseType: 'blob', timeout: 60000 })
    .then(response => response.data),
  setActive: (recordingId, active, expectedVersion) => mutation('patch', `${path(recordingId)}/active`, {
    active, expected_version: expectedVersion
  }),
  remove: recordingId => mutation('delete', path(recordingId))
};
