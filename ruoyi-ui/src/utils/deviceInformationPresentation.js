const connectionCodes = ['connection', 'protocol_configuration', 'mqtt_endpoint'];
const diagnosticIdentity = ['device_id', 'source_id', 'external_device_id', 'source_code', 'source_name', 'source_mode', 'protocol_code', 'protocol_version', 'channel', 'created_at', 'updated_at', 'version'];
const runtimeKeys = {
  link: ['connectivity', 'work_state_code', 'last_heartbeat_at', 'observed_at', 'received_at', 'unknown_reason'],
  eo_heartbeat: ['timestamp', 'metadata/codeStatus', 'metadata/message', 'metadata/taskId', 'metadata/workState'],
  radar_registers: ['frequency_code', 'speed_threshold_mps', 'detection_threshold', 'rcs_filter_enabled', 'rcs_threshold_m2', 'scan_speed_deg_s', 'work_mode'],
  radar_rtk: ['latitude_deg', 'longitude_deg', 'heading_deg', 'satellite_count'],
  radar_point: ['point_count', 'summary/scan_start_deg', 'summary/scan_end_deg', 'summary/scan_direction'],
  relay: ['channels/900M', 'channels/1.5G', 'channels/2.4G', 'channels/5.8G', 'last_query_at'],
  sensing: ['object_count', 'ptTime']
};

export function informationGroups(information, purpose, tab) {
  const sections = information?.sections || [];
  if (purpose === 'catalog') return sections.filter(section => section.code === 'catalog');
  if (purpose === 'monitor') return sections.flatMap(section => {
    const keys = runtimeKeys[section.code];
    if (!keys && !['work_parameters', 'eo_camera'].includes(section.code)) return [];
    const fields = keys ? section.fields.filter(field => keys.includes(field.key))
      : section.fields.filter(field => !['providerCode', 'deviceId', 'deviceName', 'deviceType'].includes(field.key));
    return fields.length ? [{ ...section, fields }] : [];
  });
  if (tab === 'catalog') return sections.flatMap(section => {
    if (connectionCodes.includes(section.code)) return [section];
    if (section.code === 'catalog') return [{ ...section, title: '接入身份与协议', fields: section.fields.filter(field => diagnosticIdentity.includes(field.key)) }];
    return [];
  });
  return sections.filter(section => section.code !== 'catalog' && !connectionCodes.includes(section.code));
}

const archiveFields = [
  ['device_no', '设备编号'], ['name', '设备名称'], ['device_type_name', '设备类型'],
  ['vendor', '厂家'], ['model', '型号'], ['firmware_version', '固件版本'],
  ['owner_name', '所属单位'], ['region_name', '区域'], ['address', '安装地址'],
  ['longitude', '安装经度', '°'], ['latitude', '安装纬度', '°'], ['coordinate_system', '坐标系'],
  ['altitude_m', '安装高度', 'm'], ['altitude_datum', '高度基准'], ['installed_at', '安装时间', 'epoch_ms'],
  ['enabled', '台账启用']
];

// 使用设备详情的既有字段；不为档案展示额外读取监测或调测接口。
export function catalogInformation(detail) {
  if (!detail) return null;
  const values = { ...detail.device, ...detail };
  return {
    device_id: detail.device?.device_id, device_no: values.device_no, name: values.name, model: values.model,
    source_mode: detail.device?.source_mode, simulated: detail.device?.simulated,
    sections: [{ code: 'catalog', title: '设备档案', source: '平台登记信息', fields: archiveFields.map(([key, label, unit = '']) => {
      const value = values[key];
      return { key, label, unit, value, status: value === null || value === undefined || value === '' ? 'NOT_CONFIGURED' : 'CONFIGURED' };
    }) }]
  };
}
