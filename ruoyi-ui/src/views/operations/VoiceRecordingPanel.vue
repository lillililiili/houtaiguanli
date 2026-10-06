<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { UploadFilled } from '@element-plus/icons-vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { voiceRecordingApi } from '@/api/voiceRecordings.js';
import { useRealtimeRefresh } from '@/services/realtime.js';
import { formatTime } from '@/utils/format.js';

/* 接口配置 → 电话通知录音：告警核实属实、短信送达后，自动拨打飞手电话时播放这里选用的录音。 */
const catalog = ref(null);
const loading = ref(false), error = ref(''), busy = ref('');
const uploadRef = ref();
const uploadOpen = ref(false), uploading = ref(false), uploadPercent = ref(0), uploadFiles = ref([]);
const uploadForm = reactive({ file: null, name: '', transcript: '' });
const player = reactive({ id: '', name: '', url: '', loading: false, error: '' });

const items = computed(() => catalog.value?.items || []);
const current = computed(() => catalog.value?.current || {});
const canManage = computed(() => Boolean(catalog.value?.can_manage));
const maxBytes = computed(() => Number(catalog.value?.max_size_bytes) || 10 * 1024 * 1024);
const SOURCES = {
  UPLOADED: ['success', '后台选用的录音'],
  STARTUP_CONFIG: ['warning', '服务器启动时设置的录音'],
  NONE: ['danger', '未设置录音']
};
const source = computed(() => SOURCES[current.value.source] || ['info', '待确认']);

let sequence = 0;
async function load(quiet = false) {
  const run = ++sequence;
  if (!quiet) loading.value = true;
  error.value = '';
  try {
    const data = await voiceRecordingApi.catalog();
    if (run === sequence) catalog.value = data;
  } catch (e) { if (run === sequence) error.value = e.message || '读取电话通知录音失败'; }
  finally { if (run === sequence) loading.value = false; }
}

function bytes(value) {
  const size = Number(value || 0);
  const [amount, unit] = size < 1024 * 1024 ? [size / 1024, 'KiB'] : [size / 1024 / 1024, 'MiB'];
  return `${Number(amount.toFixed(1))} ${unit}`;
}
function duration(millis) {
  const seconds = Number(millis || 0) / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)} 秒`;
  return `${Math.floor(seconds / 60)} 分 ${Math.round(seconds % 60)} 秒`;
}
function state(row) {
  if (row.active) return ['success', '正在使用'];
  if (row.used) return ['info', '用过，留存备查'];
  return ['', '未使用'];
}

function chooseFile(uploadFile) {
  const raw = uploadFile.raw;
  const reject = message => { ElMessage.error(message); uploadFiles.value = []; uploadForm.file = null; };
  if (!raw || !/\.wav$/i.test(raw.name)) return reject('只能选择 WAV 格式的录音文件。');
  if (raw.size > maxBytes.value) return reject(`录音文件不能超过 ${bytes(maxBytes.value)}。`);
  uploadForm.file = raw;
  if (!uploadForm.name.trim()) uploadForm.name = raw.name.replace(/\.wav$/i, '').slice(0, 120);
}
function replaceFile(files) {
  uploadRef.value?.clearFiles();
  const [file] = files;
  if (file) uploadRef.value?.handleStart(file);
}
function openUpload() { resetUpload(); uploadOpen.value = true; }
function resetUpload() {
  uploadFiles.value = [];
  uploadForm.file = null; uploadForm.name = ''; uploadForm.transcript = '';
  uploadPercent.value = 0;
}
async function submitUpload() {
  if (uploading.value) return;
  if (!uploadForm.file) return ElMessage.warning('请先选择 WAV 录音文件。');
  if (!uploadForm.name.trim() || !uploadForm.transcript.trim()) return ElMessage.warning('录音名称和录音里说的话都要填写。');
  uploading.value = true; uploadPercent.value = 0;
  try {
    await voiceRecordingApi.upload({ file: uploadForm.file, name: uploadForm.name.trim(), transcript: uploadForm.transcript.trim() },
      value => { uploadPercent.value = value; });
    ElMessage.success('录音已上传。要让电话播放它，请在列表里点“选用”。');
    uploadOpen.value = false;
    resetUpload();
  } catch (e) { ElMessage.error(e.message || '录音上传失败，请稍后重试。'); }
  finally { uploading.value = false; await load(true); }
}

function stopPlayer() {
  if (player.url) URL.revokeObjectURL(player.url);
  Object.assign(player, { id: '', name: '', url: '', loading: false, error: '' });
}
async function play(row) {
  stopPlayer();
  Object.assign(player, { id: row.recording_id, name: row.name, loading: true });
  try {
    const blob = await voiceRecordingApi.content(row.recording_id);
    if (player.id !== row.recording_id) return;
    player.url = URL.createObjectURL(blob);
  } catch (e) {
    if (player.id === row.recording_id) player.error = e.message || '录音读取失败，无法试听。';
  } finally { if (player.id === row.recording_id) player.loading = false; }
}

async function confirm(message, title, confirmButtonText) {
  try { await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText, cancelButtonText: '取消' }); return true; }
  catch { return false; }
}
async function run(row, action, work, success) {
  if (busy.value) return;
  busy.value = `${row.recording_id}:${action}`;
  try { catalog.value = await work(); ElMessage.success(success); }
  catch (e) { ElMessage.error(e.message || '操作没有完成，请刷新后查看。'); await load(true); }
  finally { busy.value = ''; }
}
async function activate(row) {
  const ok = await confirm(`选用后，告警核实属实、短信送达后拨打飞手电话时，播放“${row.name}”。已经拨过的电话不受影响；之前拨打失败、还没补拨的电话，换录音后不能再补拨。`,
    '选用这段录音', '选用');
  if (ok) await run(row, 'activate', () => voiceRecordingApi.setActive(row.recording_id, true, catalog.value.version), `电话通知改为播放“${row.name}”。`);
}
async function deactivate(row) {
  const fallback = catalog.value?.startup_recording_name;
  const next = fallback ? `电话改为播放服务器启动时设置的录音“${fallback}”` : '没有录音可播，告警核实后电话这一步会跳过，不会拨打飞手电话';
  const ok = await confirm(`停止使用“${row.name}”后，${next}。`, '停止使用这段录音', '停止使用');
  if (ok) await run(row, 'deactivate', () => voiceRecordingApi.setActive(row.recording_id, false, catalog.value.version), '已停止使用这段录音。');
}
async function remove(row) {
  const ok = await confirm(`删除“${row.name}”后不能恢复。`, '删除录音', '删除');
  if (!ok) return;
  if (player.id === row.recording_id) stopPlayer();
  await run(row, 'remove', () => voiceRecordingApi.remove(row.recording_id), '录音已删除。');
}

useRealtimeRefresh(['voice_recording'], () => load(true), { minIntervalMs: 2_000 });
onMounted(() => load());
onBeforeUnmount(() => { sequence++; stopPlayer(); });
</script>

<template>
  <div class="voice-recordings">
    <ErrorAlert :message="error" @retry="load()" />
    <div v-loading="loading" class="voice-body">
      <el-card v-if="catalog" class="voice-current" :class="{ 'is-missing': !current.available }">
        <div class="voice-current-head">
          <div class="voice-current-copy">
            <span class="voice-kicker">电话里现在播放</span>
            <h3>{{ current.name || '没有录音' }}</h3>
            <el-tag :type="source[0]" effect="plain">{{ source[1] }}</el-tag>
          </div>
          <el-button v-if="canManage" type="primary" :icon="UploadFilled" @click="openUpload">上传录音</el-button>
        </div>
        <el-alert :title="current.message" :type="current.available ? 'success' : 'warning'" :closable="false" show-icon />
        <el-alert v-if="!catalog.voice_enabled" class="voice-note" type="info" :closable="false" show-icon
          title="自动拨打飞手电话目前没有开启（由服务器设置决定）。录音可以先上传和选用，开启后才会播放。" />
        <ul class="voice-rules">
          <li>只能上传 WAV 格式的录音，单个文件不超过 {{ bytes(maxBytes) }}；电话通道目前只能播放 WAV。</li>
          <li>选用的录音优先；没有选用时才用服务器启动时设置的录音，两样都没有时电话这一步跳过。</li>
          <li>用于过电话的录音会留着备查，不能删除。</li>
          <li v-if="!canManage">你的账号只能查看和试听；上传、选用和删除录音需要管理员操作。</li>
        </ul>
      </el-card>

      <el-card v-if="catalog" class="voice-list">
        <template #header><b>已上传的录音</b><span class="voice-count">共 {{ items.length }} 段</span></template>
        <div v-if="player.id" class="voice-player">
          <span>试听：{{ player.name }}</span>
          <span v-if="player.loading" class="muted">正在读取录音…</span>
          <span v-else-if="player.error" class="voice-player-error">{{ player.error }}</span>
          <audio v-else-if="player.url" :src="player.url" controls autoplay @error="player.error = '浏览器无法播放这段录音，请换一台电脑或浏览器再试。'" />
          <el-button link type="primary" @click="stopPlayer">关闭</el-button>
        </div>
        <el-table :data="items" row-key="recording_id" empty-text="还没有上传录音">
          <el-table-column label="录音名称" min-width="170">
            <template #default="{ row }"><b class="voice-name">{{ row.name }}</b><span class="voice-sub">{{ row.original_name }}</span></template>
          </el-table-column>
          <el-table-column label="录音里说的话" min-width="240" show-overflow-tooltip prop="transcript" />
          <el-table-column label="时长" width="100"><template #default="{ row }">{{ duration(row.duration_millis) }}</template></el-table-column>
          <el-table-column label="大小" width="100"><template #default="{ row }">{{ bytes(row.size_bytes) }}</template></el-table-column>
          <el-table-column label="上传" min-width="160">
            <template #default="{ row }"><span class="voice-name">{{ row.uploaded_by_name }}</span><span class="voice-sub">{{ formatTime(row.uploaded_at) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="state(row)[0]" effect="plain">{{ state(row)[1] }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :loading="player.id === row.recording_id && player.loading" @click="play(row)">试听</el-button>
              <template v-if="canManage">
                <el-button v-if="!row.active" link type="primary" :disabled="Boolean(busy)" :loading="busy === `${row.recording_id}:activate`" @click="activate(row)">选用</el-button>
                <el-button v-else link type="warning" :disabled="Boolean(busy)" :loading="busy === `${row.recording_id}:deactivate`" @click="deactivate(row)">停止使用</el-button>
                <el-button v-if="row.can_delete" link type="danger" :disabled="Boolean(busy)" :loading="busy === `${row.recording_id}:remove`" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="uploadOpen" title="上传电话通知录音" width="560px" :close-on-click-modal="!uploading" :close-on-press-escape="!uploading" :show-close="!uploading" @closed="resetUpload">
      <el-upload ref="uploadRef" v-model:file-list="uploadFiles" class="voice-uploader" drag accept=".wav,audio/wav,audio/x-wav,audio/wave"
        :auto-upload="false" :limit="1" :disabled="uploading" :on-change="chooseFile" :on-exceed="replaceFile" :on-remove="() => { uploadForm.file = null; }">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">把 WAV 录音拖到这里，或 <em>点击选择</em></div>
        <template #tip><div class="el-upload__tip">电话通道目前只能播放 WAV 录音，文件不超过 {{ bytes(maxBytes) }}。上传后不会马上替换正在用的录音。</div></template>
      </el-upload>
      <el-form label-position="top" class="voice-upload-form" :disabled="uploading">
        <el-form-item label="录音名称" required><el-input v-model="uploadForm.name" maxlength="120" show-word-limit placeholder="例如：禁飞区劝离提醒" /></el-form-item>
        <el-form-item label="录音里说的话" required>
          <el-input v-model="uploadForm.transcript" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="把录音内容逐字写下来，便于日后核对电话里说了什么" />
        </el-form-item>
      </el-form>
      <el-progress v-if="uploading" :percentage="uploadPercent" />
      <template #footer>
        <el-button :disabled="uploading" @click="uploadOpen = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.voice-body { display:grid; gap:16px; min-height:180px; }
.voice-current { border-left:4px solid #15803d; }
.voice-current.is-missing { border-left-color:#d97706; }
.voice-current-head { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; margin-bottom:14px; }
.voice-current-copy { min-width:0; }
.voice-kicker { color:#526177; font-size:12px; font-weight:700; }
.voice-current-copy h3 { margin:6px 0 8px; font-size:18px; color:#16233a; overflow-wrap:anywhere; }
.voice-note { margin-top:10px; }
.voice-rules { margin:14px 0 0; padding-left:18px; color:#526177; font-size:12px; line-height:1.8; }
.voice-count { margin-left:10px; color:#64748b; font-size:12px; }
.voice-name,.voice-sub { display:block; overflow-wrap:anywhere; }
.voice-sub { margin-top:3px; color:#64748b; font-size:11px; }
.voice-player { display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:12px; padding:10px 12px; border:1px solid #bfdbfe; border-radius:6px; background:#eff6ff; font-size:13px; }
.voice-player audio { height:34px; max-width:100%; }
.voice-player-error { color:#b91c1c; }
.voice-uploader { width:100%; }
.voice-uploader :deep(.el-upload),.voice-uploader :deep(.el-upload-dragger) { width:100%; }
.voice-upload-form { margin-top:16px; }
</style>
