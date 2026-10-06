import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import InterfacesView from '@/views/operations/InterfacesView.vue';
import VoiceRecordingPanel from '@/views/operations/VoiceRecordingPanel.vue';
import { externalInterfacesApi } from '@/api/externalInterfaces.js';
import { voiceRecordingApi } from '@/api/voiceRecordings.js';

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true, user: {} }) }));
vi.mock('@/services/realtime.js', () => ({ useRealtimeRefresh: vi.fn() }));
vi.mock('@/api/externalInterfaces.js', () => ({ externalInterfacesApi: { get: vi.fn(), save: vi.fn() } }));
vi.mock('@/api/voiceRecordings.js', () => ({
  voiceRecordingApi: { catalog: vi.fn(), upload: vi.fn(), content: vi.fn(), setActive: vi.fn(), remove: vi.fn() }
}));
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}));

let app, host;
const NONE = { source: 'NONE', available: false, message: '没有可播放的录音：告警核实属实、短信送达后，系统不会拨打飞手电话，电话这一步直接跳过。' };
function recording(overrides = {}) {
  return {
    recording_id: 'rec-a', name: '禁飞区劝离提醒', transcript: '您已进入禁飞区，请立即降落。', original_name: 'quanli.wav',
    content_type: 'audio/wav', size_bytes: 96044, sha256: 'a'.repeat(64), duration_millis: 6000, sample_rate: 8000, channels: 1,
    uploaded_by_name: '超级管理员', uploaded_at: 1_780_000_000_000, active: false, used: false, can_delete: true, ...overrides
  };
}
function catalog(overrides = {}) {
  return { items: [], version: 0, current: NONE, voice_enabled: true, can_manage: true, max_size_bytes: 10 * 1024 * 1024, accepted_formats: ['WAV'], ...overrides };
}
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick(); } }
async function mount(component) {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(component); app.use(ElementPlus); app.mount(host); await settle();
}
function button(text, root = host) { return [...root.querySelectorAll('button')].find(el => el.textContent.trim() === text); }
// el-table 会把列模板另渲染一份隐藏副本（row 为空对象），行内按钮只在表体里找。
function rowButtons(text) { return [...host.querySelectorAll('.el-table__body button')].filter(el => el.textContent.trim() === text); }
function rowButton(text) { return rowButtons(text)[0]; }
function type(input, value) { input.value = value; input.dispatchEvent(new Event('input')); }

beforeEach(() => {
  vi.clearAllMocks();
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:recording');
  globalThis.URL.revokeObjectURL = vi.fn();
  ElMessageBox.confirm.mockResolvedValue('confirm');
  externalInterfacesApi.get.mockImplementation(async kind => ({ kind, name: '计划接口', source_mode: 'live', version: 0, status: 'NOT_CONFIGURED', enabled: false }));
  voiceRecordingApi.catalog.mockResolvedValue(catalog());
});
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; });

it('接口配置页有电话通知录音入口，切过去不再读外部接口配置', async () => {
  await mount(InterfacesView);
  expect(externalInterfacesApi.get).toHaveBeenCalledTimes(1);
  [...host.querySelectorAll('[role=tab]')].find(el => el.textContent.includes('电话通知录音')).click(); await settle();
  expect(voiceRecordingApi.catalog).toHaveBeenCalledTimes(1);
  expect(externalInterfacesApi.get).toHaveBeenCalledTimes(1);
  expect(host.textContent).toContain('电话里现在播放');
  expect(host.querySelector('.interface-workspace')).toBeNull();
});

it('没有录音时写明电话这一步会跳过', async () => {
  await mount(VoiceRecordingPanel);
  const status = host.querySelector('.voice-current');
  expect(status.textContent).toContain('没有录音');
  expect(status.textContent).toContain('电话这一步直接跳过');
  expect(status.textContent).toContain('只能上传 WAV');
  expect(button('上传录音')).toBeTruthy();
  expect(host.textContent).toContain('还没有上传录音');
});

it('选用时带上当前版本，成功后显示正在使用', async () => {
  voiceRecordingApi.catalog.mockResolvedValue(catalog({ version: 4, items: [recording()] }));
  voiceRecordingApi.setActive.mockResolvedValue(catalog({
    version: 5, active_recording_id: 'rec-a', items: [recording({ active: true, can_delete: false })],
    current: { source: 'UPLOADED', available: true, recording_id: 'rec-a', name: '禁飞区劝离提醒', message: '电话通知播放后台选用的录音“禁飞区劝离提醒”。' }
  }));
  await mount(VoiceRecordingPanel);
  rowButton('选用').click(); await settle();
  expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('禁飞区劝离提醒');
  expect(voiceRecordingApi.setActive).toHaveBeenCalledWith('rec-a', true, 4);
  expect(host.querySelector('.voice-current h3').textContent).toBe('禁飞区劝离提醒');
  expect(host.textContent).toContain('正在使用');
  expect(rowButton('停止使用')).toBeTruthy();
  expect(rowButton('删除')).toBeUndefined();
});

it.each([
  ['旧版劝离录音', '服务器启动时设置的录音“旧版劝离录音”'],
  [undefined, '电话这一步会跳过']
])('停止使用前说明之后播放什么（启动设置录音：%s）', async (startup, expected) => {
  const active = recording({ recording_id: 'rec-b', name: '夜间劝离', active: true, can_delete: false });
  voiceRecordingApi.catalog.mockResolvedValue(catalog({ version: 7, active_recording_id: 'rec-b', items: [active], startup_recording_name: startup,
    current: { source: 'UPLOADED', available: true, recording_id: 'rec-b', name: '夜间劝离', message: '电话通知播放后台选用的录音“夜间劝离”。' } }));
  voiceRecordingApi.setActive.mockResolvedValue(catalog({ version: 8, items: [recording({ recording_id: 'rec-b', name: '夜间劝离' })] }));
  await mount(VoiceRecordingPanel);
  rowButton('停止使用').click(); await settle();
  expect(ElMessageBox.confirm.mock.calls[0][0]).toContain(expected);
  expect(voiceRecordingApi.setActive).toHaveBeenCalledWith('rec-b', false, 7);
});

it('取消确认时不发请求；用过的录音不能删除', async () => {
  ElMessageBox.confirm.mockRejectedValue('cancel');
  voiceRecordingApi.catalog.mockResolvedValue(catalog({ items: [recording(), recording({ recording_id: 'rec-used', name: '用过的', used: true, can_delete: false })] }));
  await mount(VoiceRecordingPanel);
  expect(rowButtons('删除')).toHaveLength(1);
  rowButton('删除').click(); await settle();
  expect(voiceRecordingApi.remove).not.toHaveBeenCalled();
  expect(host.textContent).toContain('用过，留存备查');
});

it('只读账号只能试听，看不到上传、选用和删除', async () => {
  voiceRecordingApi.catalog.mockResolvedValue(catalog({ can_manage: false, items: [recording()] }));
  voiceRecordingApi.content.mockResolvedValue(new Blob(['RIFF'], { type: 'audio/wav' }));
  await mount(VoiceRecordingPanel);
  expect(button('上传录音')).toBeUndefined();
  expect(rowButton('选用')).toBeUndefined();
  expect(rowButton('删除')).toBeUndefined();
  expect(host.textContent).toContain('只能查看和试听');
  rowButton('试听').click(); await settle();
  expect(voiceRecordingApi.content).toHaveBeenCalledWith('rec-a');
  expect(host.querySelector('.voice-player audio').getAttribute('src')).toBe('blob:recording');
  app.unmount(); app = null;
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:recording');
});

it('上传只收 WAV，名称和录音里说的话一起提交', async () => {
  voiceRecordingApi.upload.mockResolvedValue(recording({ recording_id: 'rec-new' }));
  await mount(VoiceRecordingPanel);
  button('上传录音').click(); await settle();
  const dialog = document.querySelector('.el-dialog');
  const input = dialog.querySelector('input[type=file]');
  Object.defineProperty(input, 'files', { configurable: true, value: [new File(['ID3'], 'notice.mp3', { type: 'audio/mpeg' })] });
  input.dispatchEvent(new Event('change')); await settle();
  expect(ElMessage.error).toHaveBeenCalledWith('只能选择 WAV 格式的录音文件。');
  button('上传', dialog).click(); await settle();
  expect(voiceRecordingApi.upload).not.toHaveBeenCalled();

  const wav = new File(['RIFF....WAVE'], '夜间劝离.wav', { type: 'audio/wav' });
  Object.defineProperty(input, 'files', { configurable: true, value: [wav] });
  input.dispatchEvent(new Event('change')); await settle();
  const [name, transcript] = dialog.querySelectorAll('.voice-upload-form input, .voice-upload-form textarea');
  expect(name.value).toBe('夜间劝离');
  type(transcript, ' 您已进入管控区域，请立即降落。 '); await settle();
  button('上传', dialog).click(); await settle();
  expect(voiceRecordingApi.upload).toHaveBeenCalledWith({ file: wav, name: '夜间劝离', transcript: '您已进入管控区域，请立即降落。' }, expect.any(Function));
  expect(voiceRecordingApi.catalog).toHaveBeenCalledTimes(2);
});
