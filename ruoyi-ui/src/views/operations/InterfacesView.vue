<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import PageHeader from '@/components/PageHeader.vue';
import ErrorAlert from '@/components/ErrorAlert.vue';
import { externalInterfacesApi } from '@/api/externalInterfaces.js';
import { useAuthStore } from '@/stores/auth.js';
import { canAccessMenu } from '@/config/navigation.js';
import { formatTime } from '@/utils/format.js';
const auth = useAuthStore(), router = useRouter();
const kind = ref('FLIGHT_PLAN'), loading = ref(false), saving = ref(false), error = ref(''), saveError = ref('');
const config = ref(null), formRef = ref();
const canEdit = computed(() => auth.hasPermission('interfaces.op'));
const canViewDevices = computed(() => canAccessMenu(auth.user, 'devices'));
const isPlan = computed(() => kind.value === 'FLIGHT_PLAN');
const fields = ['name', 'source_code', 'direction', 'endpoint', 'credential_ref', 'allowed_cidrs', 'area_name', 'interval_minutes', 'validity_minutes', 'source_mode'];
const form = reactive({});
const isMock = computed(() => !isPlan.value && form.source_mode === 'mock');
const legacyMockForecast = computed(() => config.value?.source_mode === 'mock' && config.value?.status === 'STALE');
const configurationStatus = computed(() => legacyMockForecast.value ? 'SIMULATED' : config.value?.status);
const configurationEnabled = computed(() => legacyMockForecast.value || config.value?.enabled);
let sequence = 0;
function fill(row) { for (const field of fields) form[field] = row[field] ?? (field.endsWith('_minutes') ? undefined : ''); }
async function load() {
  const current = ++sequence;
  loading.value = true; error.value = ''; saveError.value = ''; config.value = null;
  try {
    const row = await externalInterfacesApi.get(kind.value);
    if (current !== sequence) return;
    if (row.kind !== kind.value) throw new Error('配置与当前接口不一致');
    config.value = row; fill(row);
  } catch (e) { if (current === sequence) error.value = e.message || '读取配置失败'; }
  finally { if (current === sequence) loading.value = false; }
}
async function select(value) { if (saving.value || kind.value === value) return; kind.value = value; await load(); }
async function save() {
  if (saving.value || !canEdit.value || !config.value) return;
  if (!await formRef.value.validate().catch(() => false)) return;
  saving.value = true; saveError.value = '';
  const body = { version: config.value.version };
  for (const field of fields) body[field] = typeof form[field] === 'string' ? form[field].trim() || null : form[field] ?? null;
  if (isPlan.value && body.direction === 'PUSH') body.endpoint = null;
  try {
    config.value = await externalInterfacesApi.save(kind.value, body); fill(config.value);
    ElMessage.success(config.value.status === 'SIMULATED' ? '模拟预报已启用' : '配置已保存，待接入');
  } catch (e) { saveError.value = e.message || '保存结果未确认，请重新加载检查'; }
  finally { saving.value = false; }
}
onMounted(load);
onBeforeUnmount(() => { sequence++; });
</script>
<template>
  <section class="page-stack interfaces-page">
    <PageHeader title="接口配置" />
    <div class="interface-choices" role="tablist" aria-label="接口类别">
      <button type="button" role="tab" :aria-selected="isPlan" :class="{ active: isPlan }" :disabled="saving" @click="select('FLIGHT_PLAN')"><b>飞行计划输入</b><span>外部计划系统</span></button>
      <button type="button" role="tab" :aria-selected="!isPlan" :class="{ active: !isPlan }" :disabled="saving" @click="select('WEATHER_FORECAST')"><b>天气预报</b><span>天气预报服务</span></button>
      <button v-if="canViewDevices" type="button" @click="router.push('/operations/devices?type=weather_sensor')"><b>天气传感器</b><span>前往设备管理 →</span></button>
    </div>
    <ErrorAlert :message="error" @retry="load" />
    <div v-loading="loading" class="interface-workspace">
      <el-card v-if="config" class="config-card">
        <template #header><b>{{ isPlan ? '飞行计划输入接口' : '天气预报接口' }}</b></template>
        <el-form ref="formRef" :model="form" :disabled="!canEdit || saving" label-position="top" class="interface-form">
          <el-form-item label="接口名称" prop="name" :rules="[{required:true, whitespace:true, message:'请输入接口名称'}]"><el-input v-model="form.name" maxlength="128" /></el-form-item>
          <el-form-item v-if="isPlan" label="来源标识"><el-input v-model="form.source_code" maxlength="64" /></el-form-item>
          <el-form-item v-if="isPlan" label="接入方式"><el-select v-model="form.direction" clearable placeholder="待确认"><el-option value="PUSH" label="外部系统推送" /><el-option value="PULL" label="定时拉取" /></el-select></el-form-item>
          <el-form-item v-if="!isPlan" label="数据模式"><el-radio-group v-model="form.source_mode"><el-radio label="live">真实接入</el-radio><el-radio label="mock">模拟接口</el-radio></el-radio-group></el-form-item>
          <el-form-item v-if="!isPlan && !isMock" label="服务商适配"><el-input model-value="待确认服务商及协议" disabled /></el-form-item>
          <el-form-item v-if="(!isPlan && !isMock) || form.direction==='PULL'" label="服务地址" class="wide"><el-input v-model="form.endpoint" maxlength="512" placeholder="https://" /></el-form-item>
          <el-form-item v-if="isPlan && form.direction==='PUSH'" label="接收地址" class="wide"><span class="muted">待确定输入协议后提供</span></el-form-item>
          <el-form-item v-if="!isMock" label="凭据引用"><el-input v-model="form.credential_ref" maxlength="128" placeholder="env:环境变量名" /><small class="field-hint">填写服务器凭据引用，不填写密钥本身。</small></el-form-item>
          <el-form-item v-if="isPlan" label="允许来源网段"><el-input v-model="form.allowed_cidrs" maxlength="512" placeholder="由对接单位提供" /></el-form-item>
          <template v-else>
            <el-form-item label="预报区域" prop="area_name" :rules="isMock ? [{required:true, whitespace:true, message:'请输入模拟预报区域'}] : []"><el-input v-model="form.area_name" maxlength="128" /></el-form-item>
            <el-form-item v-if="!isMock" label="更新间隔（分钟）"><el-input-number v-model="form.interval_minutes" :min="1" :max="10080" controls-position="right" placeholder="待确认" /></el-form-item>
            <el-form-item v-if="!isMock" label="数据有效期（分钟）"><el-input-number v-model="form.validity_minutes" :min="1" :max="10080" controls-position="right" placeholder="待确认" /></el-form-item>
          </template>
        </el-form>
        <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" class="save-error" />
        <div class="config-footer"><el-button :disabled="saving" @click="load">重新加载</el-button><el-button v-if="canEdit" type="primary" :loading="saving" @click="save">保存配置</el-button></div>
      </el-card>
      <el-card v-if="config" class="interface-status">
        <template #header><b>接入状态</b></template>
        <el-tag type="warning" effect="plain">{{ ({NOT_CONFIGURED:'未配置', SIMULATED:'模拟已启用'})[configurationStatus] || '待接入' }}</el-tag>
        <dl><dt>服务状态</dt><dd>{{ configurationEnabled ? '已启用（模拟）' : '未启用' }}</dd><template v-if="config.updated_at"><dt>配置更新</dt><dd>{{ formatTime(config.updated_at) }}</dd></template></dl>
        <el-alert :title="config.source_mode === 'mock' ? '每次保存生成覆盖发布时间起 24 小时的模拟预报。' : (isPlan ? '计划系统及输入协议待确认' : '天气服务商及接口协议待确认')" type="info" :closable="false" />
      </el-card>
    </div>
  </section>
</template>
<style scoped>
.interface-choices { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:16px; }
.interface-choices button { display:flex; flex-direction:column; text-align:left; gap:8px; padding:18px; border:1px solid #dce4ee; border-radius:6px; background:white; cursor:pointer; color:inherit; font:inherit; }
.interface-choices button.active { border-color:var(--el-color-primary); background:var(--el-color-primary-light-9); }
.interface-choices span,.field-hint { font-size:12px; color:#64748b; }
.interface-workspace { display:grid; grid-template-columns:minmax(0,2fr) minmax(250px,1fr); gap:16px; min-height:180px; align-items:start; }
.interface-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 20px; }
.interface-form .wide { grid-column:1/-1; }
.interface-form :deep(.el-select),.interface-form :deep(.el-input-number) { width:100%; }
.field-hint { display:block; line-height:1.6; margin-top:5px; }
.config-footer { display:flex; justify-content:flex-end; gap:10px; margin-top:20px; }
.interface-status p { line-height:1.7; overflow-wrap:anywhere; }
.interface-status dl { display:grid; grid-template-columns:80px minmax(0,1fr); gap:14px; font-size:13px; margin:20px 0; }
.interface-status dd { margin:0; overflow-wrap:anywhere; }.interface-status dt { color:#64748b; }
.save-error { margin-top:10px; }
@media(max-width:1000px) { .interface-workspace { grid-template-columns:1fr; } }
@media(max-width:650px) { .interface-choices,.interface-form { grid-template-columns:1fr; } }
</style>
