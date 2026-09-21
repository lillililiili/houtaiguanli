<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { mqttApi } from '@/api/devices.js';
import { weatherSensorsApi } from '@/api/externalInterfaces.js';
const emit = defineEmits(['saved']);
const visible = ref(false), loading = ref(false), saving = ref(false), error = ref(''), editing = ref(null), scopes = ref([]), formRef = ref();
const form = reactive({ device_no:'', name:'', vendor:'', model:'', address:'', scope_key:'', version:null });
let sequence = 0;
async function open(row = null) {
  const current = ++sequence;
  visible.value = true; loading.value = true; error.value = ''; editing.value = row;
  Object.assign(form, { device_no:'', name:'', vendor:'', model:'', address:'', scope_key:'', version:null });
  try {
    const [options, record] = await Promise.all([mqttApi.scopes(), row ? weatherSensorsApi.get(row.device_id) : Promise.resolve(null)]);
    if (sequence !== current) return;
    scopes.value = options;
    if (record) Object.assign(form, record, { scope_key:`${record.owner_org_id}/${record.district_id}` });
  } catch (e) { if (sequence === current) error.value = e.message || '读取设备资料失败'; }
  finally { if (sequence === current) loading.value = false; }
}
function closed() { sequence++; }
async function save() {
  if (saving.value || loading.value || !await formRef.value.validate().catch(() => false)) return;
  const [owner_org_id, district_id] = form.scope_key.split('/');
  const body = { device_no:form.device_no.trim(), name:form.name.trim(), vendor:form.vendor?.trim() || null,
    model:form.model?.trim() || null, address:form.address?.trim() || null, owner_org_id, district_id, version:form.version };
  saving.value = true; error.value = '';
  try {
    const row = editing.value ? await weatherSensorsApi.update(editing.value.device_id, body) : await weatherSensorsApi.create(body);
    visible.value = false; ElMessage.success('天气传感器档案已保存，待接入'); emit('saved', row);
  } catch (e) { error.value = e.message || '保存结果未确认，请查看设备台账'; }
  finally { saving.value = false; }
}
defineExpose({ open });
onBeforeUnmount(() => { sequence++; });
</script>
<template>
  <el-dialog v-model="visible" :title="editing ? '编辑天气传感器' : '登记天气传感器'" width="min(720px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close @closed="closed">
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p class="sensor-note">协议待确认，登记后保持停用。</p>
      <el-form ref="formRef" :model="form" label-position="top" class="sensor-form" :disabled="saving || loading">
        <el-form-item label="设备编号" prop="device_no" :rules="[{required:true,whitespace:true,message:'请输入设备编号'}]"><el-input v-model="form.device_no" maxlength="64" :disabled="Boolean(editing)" /></el-form-item>
        <el-form-item label="设备名称" prop="name" :rules="[{required:true,whitespace:true,message:'请输入设备名称'}]"><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.vendor" maxlength="128" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" maxlength="128" /></el-form-item>
        <el-form-item class="wide" label="所属单位 / 区域" prop="scope_key" :rules="[{required:true,message:'请选择所属单位及区域'}]"><el-select v-model="form.scope_key" :disabled="Boolean(editing)"><el-option v-for="item in scopes" :key="`${item.org_id}/${item.district_id}`" :label="`${item.org_name} / ${item.district_name}`" :value="`${item.org_id}/${item.district_id}`" /></el-select></el-form-item>
        <el-form-item class="wide" label="安装位置"><el-input v-model="form.address" maxlength="256" /></el-form-item>
      </el-form>
    </div>
    <template #footer><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button v-if="error" :disabled="saving" @click="open(editing)">重新加载</el-button><el-button type="primary" :loading="saving" :disabled="loading" @click="save">保存档案</el-button></template>
  </el-dialog>
</template>
<style scoped>
.sensor-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 18px; }.wide { grid-column:1/-1; }.sensor-form :deep(.el-select) { width:100%; }.sensor-note { color:#64748b; line-height:1.6; }@media(max-width:650px) { .sensor-form { grid-template-columns:1fr; } }
</style>
