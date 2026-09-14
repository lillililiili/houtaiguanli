<script setup>
import { computed, reactive, ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheckFilled, Delete, MapLocation, Refresh, RefreshLeft, UploadFilled, WarningFilled } from '@element-plus/icons-vue';
import PageHeader from '@/components/PageHeader.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import MetricCards from '@/components/MetricCards.vue';
import { mapApi } from '@/api/maps.js';
import { useAuthStore } from '@/stores/auth.js';

const auth = useAuthStore();
const catalog = ref({ items: [], runtime: {} });
const loading = ref(false);
const error = ref('');
const uploadOpen = ref(false);
const uploading = ref(false);
const uploadPercent = ref(0);
const uploadFiles = ref([]);
const uploadForm = reactive({ file: null, cityCode: '370500', cityName: '东营市', reason: '' });

const runtime = computed(() => catalog.value.runtime || {});
const packages = computed(() => catalog.value.items || []);
const active = computed(() => packages.value.find(item => item.package_id === runtime.value.active_package_id));
const previous = computed(() => packages.value.find(item => item.package_id === runtime.value.previous_package_id));
const canUpload = computed(() => auth.hasPermission('map:upload'));
const canActivate = computed(() => auth.hasPermission('map:activate'));
const canDelete = computed(() => auth.hasPermission('map:delete'));
const metrics = computed(() => [
  { label: '已纳管地图', value: packages.value.length, note: `${new Set(packages.value.map(item => item.city_code)).size} 个城市` },
  { label: '当前数据版本', value: active.value?.data_version || '—', tone: active.value ? 'green' : 'amber', note: active.value?.package_name || '尚未启用' },
  { label: '瓦片级别', value: active.value ? `Z${active.value.min_zoom}–Z${active.value.max_zoom}` : '—', note: active.value ? `显示至 Z${active.value.display_max_zoom}` : '等待启用' },
  { label: '运行修订号', value: runtime.value.revision || '—', tone: runtime.value.runtime_config_ready ? 'blue' : 'amber', note: runtime.value.runtime_config_ready ? '运行指针已发布' : '沿用前台内置地图' }
]);

function dateTime(value) {
  return value ? new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(value)) : '—';
}
function bytes(value) {
  const size = Number(value || 0);
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  if (size < 1024 ** 3) return `${(size / 1024 ** 2).toFixed(1)} MiB`;
  return `${(size / 1024 ** 3).toFixed(2)} GiB`;
}
function shortHash(value) { return value ? `${value.slice(0, 10)}…${value.slice(-6)}` : '—'; }
function status(row) {
  return ({ ACTIVE: ['success', '当前启用'], VALIDATED: ['', '校验通过'], RETIRED: ['info', '历史版本'] })[row.status] || ['warning', row.status];
}

async function load() {
  loading.value = true;
  error.value = '';
  try { catalog.value = await mapApi.catalog(); }
  catch (e) { error.value = e.message || '地图配置加载失败。'; }
  finally { loading.value = false; }
}

function chooseFile(uploadFile) {
  const raw = uploadFile.raw;
  if (!raw?.name.toLowerCase().endsWith('.zip')) {
    ElMessage.error('请选择 ZIP 格式的离线地图包。');
    uploadFiles.value = [];
    uploadForm.file = null;
    return;
  }
  if (raw.size > 512 * 1024 * 1024) {
    ElMessage.error('地图包不能超过 512 MiB。');
    uploadFiles.value = [];
    uploadForm.file = null;
    return;
  }
  uploadForm.file = raw;
}
function resetUpload() {
  uploadFiles.value = [];
  uploadForm.file = null;
  uploadForm.cityCode = active.value?.city_code || '370500';
  uploadForm.cityName = active.value?.city_name || '东营市';
  uploadForm.reason = '';
  uploadPercent.value = 0;
}
async function submitUpload() {
  if (!uploadForm.file) return ElMessage.warning('请先选择离线地图 ZIP 包。');
  if (!uploadForm.cityCode.trim() || !uploadForm.cityName.trim() || !uploadForm.reason.trim()) {
    return ElMessage.warning('城市编码、城市名称和上传原因均为必填项。');
  }
  uploading.value = true;
  uploadPercent.value = 0;
  try {
    await mapApi.upload({ file: uploadForm.file, cityCode: uploadForm.cityCode.trim(), cityName: uploadForm.cityName.trim(), reason: uploadForm.reason.trim() }, value => { uploadPercent.value = value; });
    uploadPercent.value = 100;
    ElMessage.success('地图包上传并校验通过，尚未影响前台地图。');
    uploadOpen.value = false;
    resetUpload();
    await load();
  } catch (e) { ElMessage.error(e.message || '地图包上传失败。'); }
  finally { uploading.value = false; }
}

async function activate(row) {
  const businessCityCode = runtime.value.business_city_code || '370500';
  const hidesBusiness = row.city_code !== businessCityCode;
  const warning = hidesBusiness
    ? `将把全部前台地图切换到“${row.city_name}”。该地图与业务数据城市（编码 ${businessCityCode}）不同，前台业务图层会自动隐藏，数据库数据不会删除。`
    : `将把全部前台地图切换到“${row.package_name}”，前台会在 30 秒内自动刷新底图。`;
  try {
    const { value } = await ElMessageBox.prompt(warning, `启用地图 · ${row.city_name}`, {
      type: 'warning', inputType: 'textarea', inputPlaceholder: '请输入启用原因',
      inputValidator: value => Boolean(value?.trim()) || '启用原因为必填项',
      confirmButtonText: hidesBusiness ? '确认切换并隐藏业务图层' : '确认启用'
    });
    catalog.value = await mapApi.activate(row.package_id, runtime.value.version, value.trim());
    ElMessage.success('地图已全局启用，旧版本已保留用于回滚。');
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '地图启用失败。'); }
}
async function rollback() {
  if (!previous.value) return;
  try {
    const { value } = await ElMessageBox.prompt(`将把全部前台地图切回“${previous.value.package_name}”。`, '回滚地图', {
      type: 'warning', inputType: 'textarea', inputPlaceholder: '请输入回滚原因',
      inputValidator: value => Boolean(value?.trim()) || '回滚原因为必填项', confirmButtonText: '确认回滚'
    });
    catalog.value = await mapApi.rollback(runtime.value.version, value.trim());
    ElMessage.success('地图已回滚，前台会自动刷新。');
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '地图回滚失败。'); }
}
async function remove(row) {
  try {
    const { value } = await ElMessageBox.prompt(`将永久删除“${row.package_name}”的地图文件并从可用版本中移除；审计记录仍会保留。当前启用版与回滚保留版不可删除。`, '删除地图包', {
      type: 'warning', inputType: 'textarea', inputPlaceholder: '请输入删除原因',
      inputValidator: value => Boolean(value?.trim()) || '删除原因为必填项', confirmButtonText: '确认永久删除'
    });
    catalog.value = await mapApi.remove(row.package_id, row.version, value.trim());
    ElMessage.success('地图包已删除。');
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '地图包删除失败。'); }
}

onMounted(load);
</script>

<template>
  <section class="page-stack maps-page">
    <PageHeader title="地图管理" description="上传、校验、启用和回滚离线地图版本；启用后，业务前台的所有地图会自动切换到同一底图。">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="canActivate" :icon="RefreshLeft" :disabled="!previous" @click="rollback">回滚上一版</el-button>
      <el-button v-if="canUpload" type="primary" :icon="UploadFilled" @click="uploadOpen = true">上传地图包</el-button>
    </PageHeader>

    <ErrorAlert :message="error" @retry="load" />
    <el-alert v-if="active && !runtime.business_overlays_visible" type="warning" :closable="false" show-icon>
      <template #title><strong>当前底图为 {{ active.city_name }}，业务数据图层已在前台自动隐藏。</strong></template>
      现有业务数据仍安全保存在数据库中；切回城市编码 {{ runtime.business_city_code }} 的地图后会重新显示。
    </el-alert>

    <section class="map-runtime" :class="{ 'is-empty': !active }" aria-label="当前生效地图">
      <div class="runtime-mark"><el-icon><MapLocation /></el-icon></div>
      <div class="runtime-copy">
        <span class="runtime-kicker">当前生效地图</span>
        <h2>{{ active ? `${active.city_name} · ${active.package_name}` : '尚未通过后台启用地图' }}</h2>
        <p v-if="active">数据版本 {{ active.data_version }} · 覆盖范围 {{ active.bounds.join('，') }} · {{ active.coordinate_system }}</p>
        <p v-else>前台继续使用项目内置的东营离线地图，不会出现空白底图。</p>
      </div>
      <div class="runtime-health">
        <span :class="runtime.runtime_config_ready ? 'ok' : 'waiting'"><el-icon><CircleCheckFilled /></el-icon>{{ runtime.runtime_config_ready ? '运行指针已发布' : '等待首次启用' }}</span>
        <small>{{ active ? `启用于 ${dateTime(active.activated_at)}` : '上传地图包后再显式启用' }}</small>
      </div>
    </section>

    <MetricCards :items="metrics" />

    <el-card class="table-card" v-loading="loading">
      <div class="table-toolbar">
        <div><div class="table-toolbar__title">离线地图版本</div><span class="table-caption">上传只会新增待启用版本，只有“启用”操作才会影响业务前台。</span></div>
        <el-tag effect="plain">共 {{ packages.length }} 个版本</el-tag>
      </div>
      <el-table :data="packages" empty-text="暂无地图包，请先上传一个符合规范的 ZIP 包">
        <el-table-column label="状态" width="96"><template #default="{ row }"><el-tag :type="status(row)[0]" effect="light">{{ status(row)[1] }}</el-tag></template></el-table-column>
        <el-table-column label="城市 / 地图" min-width="200"><template #default="{ row }"><div class="map-name"><b>{{ row.city_name }}</b><span>{{ row.package_name }}</span></div></template></el-table-column>
        <el-table-column prop="data_version" label="数据版本" min-width="110" />
        <el-table-column label="缩放" width="100"><template #default="{ row }"><span class="mono">Z{{ row.min_zoom }}–Z{{ row.max_zoom }}</span></template></el-table-column>
        <el-table-column label="文件" min-width="130"><template #default="{ row }"><span>{{ bytes(row.size_bytes) }}</span><small class="cell-note">{{ row.file_count }} 个文件</small></template></el-table-column>
        <el-table-column label="SHA-256" min-width="155"><template #default="{ row }"><el-tooltip :content="row.archive_sha256"><span class="mono hash">{{ shortHash(row.archive_sha256) }}</span></el-tooltip></template></el-table-column>
        <el-table-column label="上传信息" min-width="170"><template #default="{ row }"><span>{{ row.uploaded_by_name }}</span><small class="cell-note">{{ dateTime(row.uploaded_at) }}</small></template></el-table-column>
        <el-table-column label="操作" width="172" fixed="right"><template #default="{ row }"><div class="inline-actions"><el-button v-if="canActivate && row.status !== 'ACTIVE'" link type="primary" @click="activate(row)">启用</el-button><el-button v-if="canDelete && row.status !== 'ACTIVE' && row.package_id !== runtime.previous_package_id" link type="danger" :icon="Delete" @click="remove(row)">删除</el-button><span v-if="row.status === 'ACTIVE'" class="active-note">全局生效中</span><span v-else-if="row.package_id === runtime.previous_package_id" class="muted">回滚保留</span></div></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="uploadOpen" title="上传离线地图包" width="620px" :show-close="!uploading" :close-on-click-modal="!uploading" :close-on-press-escape="!uploading" @closed="resetUpload">
      <el-alert type="info" :closable="false" show-icon title="上传后先做完整校验，不会立即切换前台地图。" />
      <el-form label-position="top" class="upload-form">
        <el-form-item label="离线地图 ZIP 包" required>
          <el-upload v-model:file-list="uploadFiles" class="map-uploader" drag accept=".zip,application/zip" :auto-upload="false" :limit="1" :disabled="uploading" :on-change="chooseFile">
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽 ZIP 到此处，或 <em>点击选择</em></div>
            <template #tip><div class="el-upload__tip">最大 512 MiB；根目录必须包含 manifest.json、checksums.json、style.json 和 PMTiles 文件。</div></template>
          </el-upload>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="城市编码" required><el-input v-model="uploadForm.cityCode" maxlength="32" placeholder="例如 370500" /></el-form-item>
          <el-form-item label="城市名称" required><el-input v-model="uploadForm.cityName" maxlength="64" placeholder="例如 东营市" /></el-form-item>
          <el-form-item class="wide" label="上传原因" required><el-input v-model="uploadForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明数据来源、日期或变更目的" /></el-form-item>
        </div>
        <section v-if="uploading" class="upload-progress" aria-live="polite">
          <div><b>{{ uploadPercent < 100 ? '正在上传地图包' : '上传完成，服务器正在校验' }}</b><span>{{ uploadPercent }}%</span></div>
          <el-progress :percentage="uploadPercent" :stroke-width="10" :status="uploadPercent === 100 ? 'success' : ''" />
          <p><el-icon><WarningFilled /></el-icon>校验和计算可能需要一些时间，请勿关闭页面或重复提交。</p>
        </section>
      </el-form>
      <template #footer><el-button :disabled="uploading" @click="uploadOpen = false">取消</el-button><el-button type="primary" :loading="uploading" @click="submitUpload">上传并校验</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.maps-page :deep(.metric-grid){grid-template-columns:repeat(4,minmax(0,1fr))}.map-runtime{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:16px;padding:17px 19px;border:1px solid #bbd2ef;border-left:4px solid #15803d;border-radius:9px;background:linear-gradient(105deg,#f0fdf4,#f8fbff 55%,#fff);box-shadow:0 4px 16px rgba(30,64,175,.05)}.map-runtime.is-empty{border-left-color:#d97706;background:linear-gradient(105deg,#fffbeb,#fff)}.runtime-mark{display:grid;width:48px;height:48px;place-items:center;border-radius:12px;color:#fff;background:linear-gradient(135deg,#0f766e,#2563eb);font-size:24px}.runtime-copy{min-width:0}.runtime-kicker{color:#526177;font-size:11px;font-weight:800;letter-spacing:.12em}.runtime-copy h2{margin:4px 0;color:#16233a;font-size:18px}.runtime-copy p{margin:0;color:var(--admin-muted);font-size:12px;line-height:1.55}.runtime-health{text-align:right}.runtime-health>span{display:flex;align-items:center;justify-content:flex-end;gap:5px;font-weight:700}.runtime-health .ok{color:#15803d}.runtime-health .waiting{color:#b45309}.runtime-health small{display:block;margin-top:6px;color:var(--admin-muted)}.table-caption{display:block;margin-top:4px;color:var(--admin-muted);font-size:11px}.map-name b,.map-name span,.cell-note{display:block}.map-name span,.cell-note{margin-top:3px;color:var(--admin-muted);font-size:11px}.hash{cursor:help}.active-note{color:#15803d;font-size:12px;font-weight:700}.map-uploader{width:100%}.map-uploader :deep(.el-upload){width:100%}.map-uploader :deep(.el-upload-dragger){width:100%;padding:26px 20px}.upload-form{margin-top:16px}.upload-progress{margin-top:10px;padding:13px;border:1px solid #bfdbfe;border-radius:7px;background:#eff6ff}.upload-progress>div{display:flex;justify-content:space-between;margin-bottom:9px;color:#1e3a8a}.upload-progress p{display:flex;align-items:center;gap:5px;margin:8px 0 0;color:#526177;font-size:11px}
@media(max-width:900px){.maps-page :deep(.metric-grid){grid-template-columns:repeat(2,minmax(0,1fr))}.map-runtime{grid-template-columns:auto minmax(0,1fr)}.runtime-health{grid-column:1/-1;text-align:left}.runtime-health>span{justify-content:flex-start}}
@media(max-width:640px){.maps-page :deep(.metric-grid){grid-template-columns:1fr}.map-runtime{grid-template-columns:1fr}.runtime-mark{width:42px;height:42px}}
</style>
