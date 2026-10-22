import { beforeEach, expect, it, vi } from 'vitest';
import { voiceRecordingApi } from '@/api/voiceRecordings.js';
import { mutation, request } from '@/services/apiClient.js';

vi.mock('@/services/apiClient.js', () => ({
  mutation: vi.fn(), request: vi.fn(), newIdempotencyKey: vi.fn(() => 'upload-once')
}));
beforeEach(() => vi.clearAllMocks());

it.each(['GENERAL', 'PREDICTED_AIRSPACE_ENTRY'])('上传真实 multipart 字段保留用途 %s', purpose => {
  const file = new File(['RIFF'], 'test.wav', { type: 'audio/wav' });
  voiceRecordingApi.upload({ file, name: '测试录音', transcript: '测试内容', purpose });
  const call = request.mock.calls[0][0];
  expect(call.method).toBe('post');
  expect(call.headers['Idempotency-Key']).toBe('upload-once');
  expect(call.data.get('purpose')).toBe(purpose);
  expect(call.data.get('file')).toBe(file);
  expect(call.data.get('transcript')).toBe('测试内容');
});

it('旧调用默认普通用途，启停沿用录音标识与全局设置版本', () => {
  voiceRecordingApi.upload({ file: new File(['RIFF'], 'old.wav'), name: '普通录音', transcript: '普通内容' });
  expect(request.mock.calls[0][0].data.get('purpose')).toBe('GENERAL');
  voiceRecordingApi.setActive('recording-z', true, 27);
  expect(mutation).toHaveBeenCalledWith('patch', '/v1/advisory-voice-recordings/recording-z/active', {
    active: true, expected_version: 27
  });
});
