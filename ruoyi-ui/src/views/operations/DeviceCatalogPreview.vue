<script setup>
import { computed, ref, watch } from 'vue';
import { Location } from '@element-plus/icons-vue';
import DeviceInformationPanel from '@/components/DeviceInformationPanel.vue';
import { catalogInformation } from '@/utils/deviceInformationPresentation.js';
import { display, formatTime, statusText, statusType } from '@/utils/format.js';
const props = defineProps({ detail: { type: Object, default: null }, loading: Boolean, error: { type: String, default: '' } });
defineEmits(['refresh']);
const tab = ref('location');
const device = computed(() => ({ ...props.detail?.device, ...props.detail }));
const isWeather = computed(() => props.detail?.device?.device_type_code === 'weather_sensor');
const archive = computed(() => catalogInformation(props.detail));
watch(() => props.detail?.device?.device_id, () => { tab.value = isWeather.value ? 'weather' : 'location'; });
const fields = computed(() => ({
  location: [['longitude', '经度', '°'], ['latitude', '纬度', '°'], ['altitude_m', '安装高度', 'm'], ['coordinate_system', '坐标系'], ['address', '安装位置'], ['region_name', '所属区域']],
  basic: [['device_no', '设备编号'], ['device_type_name', '设备类型'], ['model', '设备型号'], ['vendor', '供应商'], ['firmware_version', '固件版本'], ['owner_name', '产权单位']],
  region: [['region_name', '所属区域'], ['owner_name', '产权单位'], ['address', '安装位置'], ['altitude_datum', '高度基准']]
}[tab.value] || []));
</script>

<template>
  <el-card v-loading="loading" class="catalog-preview">
    <template #header><div class="table-toolbar"><b>设备详情预览</b><el-button link type="primary" :disabled="!detail" @click="$emit('refresh')">刷新信息</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-else-if="!detail && !loading" description="请选择设备查看信息" />
    <template v-if="detail">
      <div class="preview-identity"><h2>{{ device.name }}</h2><el-tag :type="statusType(device.connectivity)" effect="plain">{{ isWeather && device.protocol_code==='WEATHER_PENDING' ? '待接入' : statusText(device.connectivity) }}</el-tag></div>
      <p class="muted mono">设备编号：{{ device.device_no }}</p>
      <el-tag v-if="!isWeather" size="small" :type="device.simulated || device.source_mode==='replay' ? 'warning' : 'info'" effect="plain">{{ device.simulated ? '模拟数据' : ({live:'真实链路数据', replay:'回放数据'})[device.source_mode] || '来源未登记' }}</el-tag>
      <el-tabs v-model="tab" class="preview-tabs"><el-tab-pane v-if="isWeather" label="气象观测" name="weather" /><el-tab-pane label="位置概览" name="location" /><el-tab-pane label="基础参数" name="basic" /><el-tab-pane label="接口信息" name="connection" /><el-tab-pane label="所属区域" name="region" /><el-tab-pane label="完整档案 / 厂家资料" name="archive" /></el-tabs>
      <div v-if="tab==='location'" class="location-overview"><el-icon><Location /></el-icon><b>{{ display(device.region_name) }}</b><span>{{ device.address || '尚未登记安装地址' }}</span></div>
      <dl v-if="fields.length" class="preview-fields"><template v-for="[key,label,unit] in fields" :key="key"><dt>{{ label }}</dt><dd>{{ display(device[key]) }}<span v-if="device[key] != null && unit"> {{ unit }}</span></dd></template></dl>
      <template v-if="tab==='connection'">
        <dl class="preview-fields"><dt>接入协议</dt><dd>{{ device.protocol_code==='WEATHER_PENDING' ? '待确认' : display(device.protocol_code) }}</dd><dt>协议版本</dt><dd>{{ display(device.protocol_version) }}</dd><dt>接入通道</dt><dd>{{ display(device.channel) }}</dd><dt>最后心跳</dt><dd>{{ formatTime(device.last_heartbeat_at) }}</dd></dl>
        <dl v-if="detail.connection_visible" class="preview-fields"><dt>传输方式</dt><dd>{{ display(detail.connection?.transport) }}</dd><dt>主机</dt><dd>{{ display(detail.connection?.host) }}</dd><dt>端口</dt><dd>{{ display(detail.connection?.port) }}</dd></dl>
        <p v-else class="muted">连接参数按操作权限提供。</p>
      </template>
      <el-empty v-if="tab==='weather'" description="尚无气象观测数据，设备待接入" />
      <DeviceInformationPanel v-if="tab==='archive'" purpose="catalog" :information="archive" @refresh="$emit('refresh')" />
    </template>
  </el-card>
</template>

<style scoped>
.catalog-preview { min-width: 0; }
.preview-identity { display: flex; align-items: flex-start; gap: 10px; }
.preview-identity h2 { margin: 0; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.preview-tabs { margin-top: 12px; }
.location-overview { display: flex; flex-direction: column; align-items: center; gap: 10px; background: #f7faff; border: 1px solid #e6edf6; border-radius: 6px; padding: 24px 14px; text-align: center; }
.location-overview .el-icon { font-size: 28px; color: #2876d5; }
.location-overview span { color: #728096; font-size: 12px; }
.preview-fields { display: grid; grid-template-columns: 85px minmax(0, 1fr); gap: 14px 12px; font-size: 13px; line-height: 1.6; }
.preview-fields dt { color: #7a8798; }
.preview-fields dd { margin: 0; overflow-wrap: anywhere; }
.catalog-preview :deep(.el-card__header) { background: #fff; }
</style>
