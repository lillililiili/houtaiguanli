<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { mqttApi } from '@/api/devices.js';
import { useAuthStore } from '@/stores/auth.js';
import { isUncertainOutcome, newIdempotencyKey } from '@/services/apiClient.js';

// 设备模拟器默认读取名为 local-lingyun-replay 的本机回放连接（与本地种子 LocalMqttSimSeeder 一致）。
const SIMULATOR_DEFAULTS = { name: 'local-lingyun-replay', host: '127.0.0.1', port: 1883, tls: false, username: '', credential_ref: '', allowed_cidrs: '127.0.0.1/32' };
const SOURCE_LABELS = { live: '真实设备', replay: '模拟回放' };
const STATE_LABELS = { CONNECTED: '已连接', CONNECTING: '连接中', DISCONNECTED: '未连接' };
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPermission('interfaces.op'));
const rows = ref([]), scopes = ref([]), loading = ref(false), error = ref(''), scopeError = ref(''), toggling = ref('');
const capabilities = ref({ source_modes: ['live'], simulation_allowed: false });
const replayAllowed = computed(() => capabilities.value.simulation_allowed === true && capabilities.value.source_modes?.includes('replay'));
const editor = reactive({ visible: false, saving: false, row: null, error: '' });
const formRef = ref();
const form = reactive(blankForm());
const scopeNames = computed(() => new Map(scopes.value.map(item => [`${item.org_id}/${item.district_id}`, `${item.org_name} / ${item.district_name}`])));
const rules = {
  name: [{ required: true, whitespace: true, message: '请输入连接名称' }],
  host: [{ required: true, whitespace: true, message: '请输入服务器地址' }, { pattern: /^[^\s/@?#]+$/, message: '只填主机名或 IP，不带协议、路径和空格' }],
  port: [{ required: true, message: '请输入端口' }],
  scope_key: [{ required: true, message: '请选择所属单位和区域' }],
  credential_ref: [{ pattern: /^(env:[A-Za-z_][A-Za-z0-9_]{0,200})?$/, message: '只能填写 env:环境变量名，不能填写密码本身' }],
  allowed_cidrs: [{ required: true, whitespace: true, message: '请输入允许连接的服务器网段' }]
};
let sequence = 0, alive = true;
// 同一份填写内容重试时沿用同一个幂等键：结果没确认时再点保存，不会建出两条连接。
let pending = { fingerprint: '', key: '' };

function blankForm() {
  return { name: '', host: '', port: 8883, tls: true, source_mode: 'live', scope_key: '', username: '', credential_ref: '', allowed_cidrs: '' };
}
function scopeLabel(row) { return scopeNames.value.get(`${row.owner_org_id}/${row.district_id}`) || `${row.owner_org_id} / ${row.district_id}`; }
function stateLabel(row) { return row.enabled ? (STATE_LABELS[row.connection_state] || '状态未知') : '未启用'; }
function replayLocked(row) { return row.source_mode !== 'live' && !replayAllowed.value; }

async function loadScopes() {
  if (!canEdit.value) return;
  try { scopes.value = await mqttApi.scopes() || []; if (alive) scopeError.value = ''; }
  catch (e) { if (alive) scopeError.value = e.message || '单位和区域读取失败'; }
}
async function load() {
  const current = ++sequence;
  loading.value = true; error.value = '';
  try {
    const [items, caps] = await Promise.all([mqttApi.list(), mqttApi.capabilities(), loadScopes()]);
    if (!alive || current !== sequence) return;
    rows.value = items || []; capabilities.value = caps || capabilities.value;
  } catch (e) { if (alive && current === sequence) error.value = e.message || '设备数据连接读取失败'; }
  finally { if (alive && current === sequence) loading.value = false; }
}
function openEditor(row = null) {
  if (!canEdit.value || (row && (row.enabled || replayLocked(row)))) return;
  Object.assign(form, row ? { name: row.name, host: row.host, port: row.port, tls: row.tls, source_mode: row.source_mode,
    scope_key: `${row.owner_org_id}/${row.district_id}`, username: row.username || '', credential_ref: row.credential_ref || '',
    allowed_cidrs: row.allowed_cidrs || '' } : blankForm());
  if (!row && scopes.value.length === 1) form.scope_key = `${scopes.value[0].org_id}/${scopes.value[0].district_id}`;
  Object.assign(editor, { visible: true, row, error: '' });
  formRef.value?.clearValidate();
}
function useSimulatorDefaults() { Object.assign(form, SIMULATOR_DEFAULTS, { source_mode: 'replay' }); }
function keyFor(body) {
  const fingerprint = JSON.stringify([editor.row?.broker_id || '', body]);
  if (pending.fingerprint !== fingerprint) pending = { fingerprint, key: newIdempotencyKey(editor.row ? 'mqtt-update' : 'mqtt-create') };
  return pending.key;
}
async function save() {
  if (editor.saving || !canEdit.value) return;
  if (!await formRef.value.validate().catch(() => false)) return;
  const [owner_org_id, district_id] = form.scope_key.split('/');
  const body = { name: form.name.trim(), host: form.host.trim(), port: form.port, tls: form.tls, username: form.username.trim() || null,
    credential_ref: form.credential_ref.trim() || null, allowed_cidrs: form.allowed_cidrs.trim(), source_mode: form.source_mode,
    owner_org_id, district_id, version: editor.row?.version ?? null };
  const key = keyFor(body), creating = !editor.row;
  editor.saving = true; editor.error = '';
  try {
    if (creating) await mqttApi.create(body, key); else await mqttApi.update(editor.row.broker_id, body, key);
    pending = { fingerprint: '', key: '' };
    editor.visible = false;
    ElMessage.success(creating ? '连接已保存，当前为停用状态；确认无误后点“启用”。' : '连接配置已保存。');
    await load();
  } catch (e) {
    if (e.code === 'IDEMPOTENCY_REPLAY') {
      pending = { fingerprint: '', key: '' }; editor.visible = false; await load();
      ElMessage.warning('这次保存之前已经提交过，列表已刷新，请核对。');
      return;
    }
    editor.error = e.message || '保存失败，请重试。';
    if (isUncertainOutcome(e)) await load();
  } finally { editor.saving = false; }
}
async function toggle(row) {
  if (!canEdit.value || toggling.value || replayLocked(row)) return;
  const enabling = !row.enabled;
  if (enabling && row.source_mode === 'live' && (!row.username || !row.credential_ref)) {
    ElMessage.warning('真实设备连接要先填写平台分配的用户名和密码凭据引用，再启用。');
    return;
  }
  try {
    await ElMessageBox.confirm(enabling ? `启用后系统会连接 ${row.host}:${row.port}，开始接收这条连接上的设备数据。` : '停用后不再接收这条连接上的设备数据，已登记的设备会显示离线。',
      `${enabling ? '启用' : '停用'}“${row.name}”`, { type: 'warning', confirmButtonText: enabling ? '启用' : '停用', cancelButtonText: '取消' });
  } catch { return; }
  toggling.value = row.broker_id;
  try {
    await mqttApi.setEnabled(row.broker_id, { enabled: enabling, version: row.version });
    ElMessage.success(enabling ? '连接已启用，连接状态稍后刷新。' : '连接已停用。');
  } catch (e) { ElMessage.error(e.message || `${enabling ? '启用' : '停用'}失败，请刷新后重试。`); }
  finally { toggling.value = ''; await load(); }
}
onMounted(load);
onBeforeUnmount(() => { alive = false; sequence++; });
</script>

<template>
  <div class="mqtt-panel">
    <el-alert type="info" :closable="false" show-icon :title="replayAllowed ? '设备通过 MQTT 连接上报数据。先在这里建好连接并启用，再到“设备管理”登记设备。当前是测试环境，可以建“模拟回放”连接给设备模拟器使用。' : '设备通过 MQTT 连接上报数据。先在这里建好连接并启用，再到“设备管理”登记设备。'" />
    <ErrorAlert :message="error" @retry="load" />
    <el-alert v-if="canEdit && scopeError" :title="scopeError" type="error" :closable="false" />
    <el-alert v-else-if="canEdit && !loading && !scopes.length" type="warning" :closable="false" show-icon title="还没有可选的单位和区域。请先到“用户管理”新增单位，并在“区域管理”中新增区域，再回来新建连接。" />
    <el-card class="mqtt-card" shadow="never">
      <div class="table-toolbar"><b>设备数据连接</b><span class="inline-actions"><el-button :disabled="loading" @click="load">刷新</el-button><el-button v-if="canEdit" type="primary" :disabled="!scopes.length" @click="openEditor()">新增连接</el-button></span></div>
      <el-table v-loading="loading" :data="rows" empty-text="还没有设备数据连接">
        <el-table-column label="连接名称" min-width="190"><template #default="{ row }"><strong>{{ row.name }}</strong><small class="muted cell-note">{{ row.host }}:{{ row.port }}{{ row.tls ? ' · TLS' : '' }}</small></template></el-table-column>
        <el-table-column label="数据来源" width="110"><template #default="{ row }"><el-tag :type="row.source_mode === 'live' ? '' : 'warning'" effect="plain">{{ SOURCE_LABELS[row.source_mode] || row.source_mode }}</el-tag></template></el-table-column>
        <el-table-column label="单位 / 区域" min-width="170"><template #default="{ row }">{{ scopeLabel(row) }}</template></el-table-column>
        <el-table-column label="状态" min-width="150"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已停用' }}</el-tag><small class="muted cell-note">{{ stateLabel(row) }}<template v-if="row.enabled && row.last_error"> · {{ row.last_error }}</template></small></template></el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="150"><template #default="{ row }">
          <!-- 根节点保持单个元素：el-table-column 会把片段里的组件按空行另渲染一份到隐藏列。 -->
          <span v-if="replayLocked(row)" class="muted">历史模拟连接，正式环境只能查看</span>
          <span v-else class="row-actions">
            <el-button link type="primary" :disabled="row.enabled || Boolean(toggling)" :title="row.enabled ? '请先停用再修改' : ''" @click="openEditor(row)">编辑</el-button>
            <el-button link :type="row.enabled ? 'danger' : 'primary'" :loading="toggling === row.broker_id" :disabled="Boolean(toggling) && toggling !== row.broker_id" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          </span>
        </template></el-table-column>
      </el-table>
      <p v-if="!canEdit" class="muted readonly-note">当前账号只能查看连接。新增、修改和启停需要接口配置的操作权限。</p>
    </el-card>

    <el-dialog v-model="editor.visible" :title="editor.row ? '编辑设备数据连接' : '新增设备数据连接'" width="min(720px, calc(100vw - 32px))" append-to-body :close-on-click-modal="!editor.saving" class="mqtt-editor-dialog">
      <el-alert type="info" :closable="false" title="密码不要直接填写，只填服务器上保存密码的环境变量名，例如 env:MQTT_PASSWORD。新建的连接默认停用，保存后再点“启用”。" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="form-grid mqtt-form" :disabled="editor.saving">
        <el-form-item label="数据来源" prop="source_mode" class="wide">
          <el-radio-group v-model="form.source_mode" :disabled="Boolean(editor.row)">
            <el-radio label="live">真实设备</el-radio>
            <el-radio v-if="replayAllowed || form.source_mode === 'replay'" label="replay">模拟回放（仅测试环境）</el-radio>
          </el-radio-group>
        </el-form-item>
        <div v-if="form.source_mode === 'replay' && !editor.row" class="wide simulator-hint">
          <span>设备模拟器默认使用名为 local-lingyun-replay、地址 127.0.0.1:1883 的回放连接。</span>
          <el-button size="small" type="primary" plain @click="useSimulatorDefaults">填入本机模拟器默认值</el-button>
        </div>
        <el-form-item label="连接名称" prop="name"><el-input v-model="form.name" maxlength="128" placeholder="例如：东营区设备 MQTT" /></el-form-item>
        <el-form-item label="所属单位 / 区域" prop="scope_key"><el-select v-model="form.scope_key" :disabled="Boolean(editor.row)" placeholder="请选择单位和区域" filterable><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
        <el-form-item label="服务器地址" prop="host"><el-input v-model="form.host" maxlength="255" placeholder="主机名或 IP，例如 10.20.1.15" /></el-form-item>
        <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" /></el-form-item>
        <el-form-item label="TLS 加密"><el-switch v-model="form.tls" active-text="启用" inactive-text="关闭" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" maxlength="128" :placeholder="form.source_mode === 'live' ? '真实设备启用前必填' : '服务器需要认证时填写'" /></el-form-item>
        <el-form-item label="密码凭据引用" prop="credential_ref"><el-input v-model="form.credential_ref" maxlength="204" :placeholder="form.source_mode === 'live' ? 'env:MQTT_PASSWORD（真实设备启用前必填）' : 'env:MQTT_PASSWORD'" /></el-form-item>
        <el-form-item label="允许连接的服务器网段" prop="allowed_cidrs"><el-input v-model="form.allowed_cidrs" maxlength="2048" placeholder="例如 10.20.0.0/16，多个用逗号分隔" /></el-form-item>
      </el-form>
      <el-alert v-if="editor.error" :title="editor.error" type="error" :closable="false" />
      <template #footer><el-button :disabled="editor.saving" @click="editor.visible = false">取消</el-button><el-button type="primary" :loading="editor.saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mqtt-panel { display:flex; flex-direction:column; gap:14px; min-width:0; }
.mqtt-panel :deep(.el-alert__title) { white-space:normal; overflow-wrap:anywhere; line-height:1.6; }
.mqtt-card .table-toolbar b { font-size:15px; }
.cell-note { display:block; margin-top:4px; font-size:12px; line-height:1.5; overflow-wrap:anywhere; }
.mqtt-card :deep(.el-table .cell) { white-space:normal; overflow-wrap:anywhere; }
.readonly-note { margin:12px 0 0; font-size:12px; }
.row-actions { display:inline-flex; flex-wrap:wrap; gap:4px 10px; }
.row-actions .el-button + .el-button { margin-left:0; }
.mqtt-form { margin-top:14px; }
.mqtt-form :deep(.el-select), .mqtt-form :deep(.el-input-number) { width:100%; }
.simulator-hint { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:-4px 0 14px; padding:10px 12px; border:1px dashed var(--admin-border-strong); border-radius:6px; color:var(--admin-muted); font-size:12px; line-height:1.6; }
@media(max-width:650px) { .mqtt-form { grid-template-columns:1fr; } .simulator-hint { flex-direction:column; align-items:flex-start; } }
</style>
