<script setup>
import { computed, ref, watch } from 'vue';
import { formatTime } from '@/utils/format.js';
import { informationCoverage, informationStates, informationValue } from '@/utils/deviceInformation.js';
import { deviceReferenceModels } from '@/utils/deviceReferenceModels.js';
import { informationGroups } from '@/utils/deviceInformationPresentation.js';

const props = defineProps({ information: { type: Object, default: null }, loading: Boolean, error: { type: String, default: '' }, purpose: { type: String, default: 'commission' } });
defineEmits(['refresh']);
const tab = ref(props.purpose === 'catalog' ? 'catalog' : 'protocol');
const isCommission = computed(() => props.purpose === 'commission');
const isCatalog = computed(() => props.purpose === 'catalog');
const title = computed(() => ({ monitor: '当前运行信息', commission: '接入诊断信息', catalog: '设备档案与资料' })[props.purpose]);
const onlyMissing = ref(false);
const sampleCode = ref('');
const referenceCode = ref('');
const coverage = computed(() => informationCoverage(props.information?.sections));
const selectedSample = computed(() => props.information?.sample_sections?.find(section => section.code === sampleCode.value));
const reference = computed(() => deviceReferenceModels.find(item => item.code === referenceCode.value));
const groups = computed(() => informationGroups(props.information, props.purpose, tab.value));
const notes = computed(() => isCommission.value ? props.information?.notes : (props.information?.notes || []).map(note => note.includes('MQTT 工参') ? '设备主动上报运行参数；超过 30 秒未收到有效数据时显示过期。' : note).filter(note => !note.includes('调测') && !note.includes('寄存器')));
function visibleFields(section) { return onlyMissing.value ? section.fields.filter(field => ['NOT_REPORTED', 'NOT_CONFIGURED', 'INVALID', 'STALE'].includes(field.status)) : section.fields; }
watch(() => props.information?.device_id, () => {
  onlyMissing.value = false; sampleCode.value = ''; referenceCode.value = '';
  const match = deviceReferenceModels.find(item => item.models.includes(props.information?.model?.trim().toUpperCase()));
  if (match) referenceCode.value = match.code;
}, { immediate: true });
watch(() => props.information?.sample_sections, samples => {
  if (!samples?.some(item => item.code === sampleCode.value)) sampleCode.value = samples?.[0]?.code || '';
}, { immediate: true });
</script>

<template>
  <el-card class="device-information" v-loading="loading">
    <template #header><div class="table-toolbar"><div><b>{{ title }}</b><span v-if="information" class="muted info-heading">{{ information.name }} · {{ information.device_no }}</span></div><el-button :loading="loading" @click="$emit('refresh')">刷新信息</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-if="!information && !loading && !error" description="请选择设备查看信息" />
    <template v-if="information">
      <div class="information-summary">
        <el-tag :type="information.source_mode==='live'&&!information.simulated?'info':'warning'">{{ information.simulated?'模拟数据':({live:'真实链路数据',replay:'回放数据',mock:'模拟数据'})[information.source_mode]||'来源模式未登记' }}</el-tag>
        <template v-if="isCommission"><span>协议必填项：{{ coverage.received }} / {{ coverage.required }} 项已获取</span>
        <span v-if="coverage.missing">缺失或异常 {{ coverage.missing }} 项</span><span v-if="coverage.stale">过期 {{ coverage.stale }} 项</span></template>
        <span v-if="information.generated_at" class="muted">页面读取于 {{ formatTime(information.generated_at) }}</span>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane v-if="!isCatalog" :label="isCommission ? '协议采集诊断' : '运行参数'" name="protocol" />
        <el-tab-pane v-if="isCommission || isCatalog" :label="isCatalog ? '设备档案' : '连接与协议配置'" name="catalog" />
        <el-tab-pane v-if="!isCatalog" :label="`${isCommission ? '报文字段样本' : '感知目标'}（${information.sample_sections?.length || 0}）`" name="samples" />
        <el-tab-pane v-if="isCatalog" label="厂家资料" name="reference" />
      </el-tabs>
      <template v-if="tab==='catalog'||tab==='protocol'">
        <div v-if="!isCatalog" class="information-filter"><el-checkbox v-model="onlyMissing">仅看缺失、异常或过期项</el-checkbox><span v-if="isCommission" class="muted">必填项完整不等于现场验收通过；每项保留独立来源和状态。</span><span v-else class="muted">按接收与上报时间判断数据时效，过期值保留标识。</span></div>
        <el-empty v-if="!groups.length" :description="isCommission ? '该设备尚无可用的协议诊断信息' : '尚未获取运行参数，请检查设备接入和上报情况'" />
        <section v-for="section in groups" :key="section.code" class="information-section">
          <div class="table-toolbar"><b>{{ section.title }}</b><span class="muted">{{ section.source }}<template v-if="section.received_at"> · 接收 {{ formatTime(section.received_at) }}</template><template v-if="section.observed_at"> · 上报 {{ formatTime(section.observed_at) }}</template></span></div>
          <el-descriptions v-if="isCatalog" :column="1" border size="small"><el-descriptions-item v-for="field in section.fields" :key="field.key" :label="field.label">{{ informationValue(field) }} {{ field.unit === 'epoch_ms' ? '' : field.unit }}<small v-if="field.status==='NOT_CONFIGURED'" class="muted"> 未登记</small></el-descriptions-item></el-descriptions>
          <el-table v-else :data="visibleFields(section)" size="small" border empty-text="本组无缺失、异常或过期项">
            <el-table-column label="信息项" min-width="185"><template #default="{row}">{{ row.label }}<span v-if="isCommission && row.required" class="required-marker"> *</span></template></el-table-column>
            <el-table-column label="当前值" min-width="200"><template #default="{row}"><pre v-if="row.value && typeof row.value==='object'" class="information-json">{{ informationValue(row) }}</pre><span v-else>{{ informationValue(row) }}</span><small v-if="row.unit && row.unit!=='epoch_ms'" class="muted"> {{ row.unit }}</small></template></el-table-column>
            <el-table-column label="数据状态" width="115"><template #default="{row}"><el-tag size="small" :type="informationStates[row.status]?.[1] || 'info'" effect="plain">{{ informationStates[row.status]?.[0] || row.status }}</el-tag></template></el-table-column>
            <el-table-column :label="isCommission ? '说明 / 协议字段' : '说明'" min-width="200"><template #default="{row}"><div>{{ row.note }}</div><small v-if="isCommission" class="muted mono">{{ row.key }}</small></template></el-table-column>
          </el-table>
        </section>
        <ul v-if="tab==='protocol'" class="information-notes"><li v-for="note in notes" :key="note">{{ note }}</li></ul>
      </template>
      <template v-else-if="tab==='samples'">
        <p class="muted">{{ isCommission ? '展示最近接收报文 / 活动航迹中最多 20 个目标样本，用于核对解析字段。没有目标不等于接入失败。' : '展示最近接收的目标及其探测、接收时间；标记过期的目标不能视为当前活动目标。最多展示 20 个。' }}</p>
        <el-select v-if="information.sample_sections?.length" v-model="sampleCode" placeholder="选择目标样本"><el-option v-for="sample in information.sample_sections" :key="sample.code" :label="sample.title" :value="sample.code" /></el-select>
        <el-empty v-else description="当前没有可展示的目标样本" />
        <template v-if="selectedSample"><p class="muted">{{ selectedSample.source }} · 接收 {{ formatTime(selectedSample.received_at) }} · 上报 {{ formatTime(selectedSample.observed_at) }}</p><el-table :data="selectedSample.fields" border size="small"><el-table-column prop="label" label="信息项" min-width="180" /><el-table-column label="值" min-width="180"><template #default="{row}">{{ informationValue(row) }} {{ row.unit==='epoch_ms'?'':row.unit }}</template></el-table-column><el-table-column label="状态" width="115"><template #default="{row}"><el-tag :type="informationStates[row.status]?.[1]" effect="plain">{{ informationStates[row.status]?.[0] }}</el-tag></template></el-table-column><el-table-column prop="note" label="说明" min-width="220" /></el-table></template>
      </template>
      <template v-else>
        <p class="muted">以下为提供资料中的厂家标称参数 / 证书记录，不是设备实测值。仅按档案型号精确匹配；手动选择只用于查阅，不会更改设备档案。</p>
        <el-select v-model="referenceCode" placeholder="选择要查阅的资料型号" clearable style="width: min(100%, 460px)"><el-option v-for="item in deviceReferenceModels" :key="item.code" :label="item.name" :value="item.code" /></el-select>
        <template v-if="reference"><p>{{ reference.source }}</p><el-alert v-if="reference.note" :title="reference.note" type="warning" :closable="false" /><el-descriptions :column="1" border size="small" class="information-section"><el-descriptions-item v-for="field in reference.fields" :key="field.label" :label="field.label">{{ field.value }}</el-descriptions-item></el-descriptions></template>
        <el-empty v-else description="未匹配型号，请选择资料查阅" />
      </template>
    </template>
  </el-card>
</template>

<style scoped>
.info-heading { margin-left: 14px; font-weight: 400; }
.information-summary, .information-filter { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
.information-summary { margin-bottom: 12px; }
.information-section { margin-top: 18px; }
.information-section .table-toolbar { align-items: flex-start; gap: 12px; }
.information-section .table-toolbar .muted { font-size: 12px; text-align: right; }
.information-json { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 240px; overflow: auto; margin: 0; font: inherit; }
.information-notes { color: var(--el-text-color-secondary); line-height: 1.7; padding-left: 20px; }
.required-marker { color: var(--el-color-danger); }
</style>
