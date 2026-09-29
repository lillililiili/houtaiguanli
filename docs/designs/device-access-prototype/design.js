// 独立页面设计原型。全部资料只在内存中演示，不调用业务接口。
const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const types = [
  {id:'radar',name:'雷达',symbol:'◉',protocols:['tcp','mqtt']},
  {id:'eo',name:'光电设备',symbol:'◎',protocols:['eo']},
  {id:'counter',name:'反制设备',symbol:'⌁',protocols:['counter']},
  {id:'weather',name:'天气传感器',symbol:'☀',protocols:[]},
  {id:'5ga',name:'5G-A',symbol:'▥',protocols:['mqtt']},
  {id:'tdoa',name:'TDOA',symbol:'⋈',protocols:['mqtt']},
  {id:'aoa',name:'AOA',symbol:'∠',protocols:['mqtt']},
  {id:'dcd',name:'协议破解',symbol:'⌘',protocols:['mqtt']},
  {id:'rid',name:'RemoteID',symbol:'⊙',protocols:['mqtt']},
  {id:'other',name:'其他反制设备',symbol:'⌁',protocols:['mqtt']}
];
const protocolNames={tcp:'雷达 TCP · v3.0.0',mqtt:'凌云 MQTT · v8.6',eo:'光电边端 MQTT · 20250826',counter:'四通道控制器 TCP · v2.0'};
let selectedType='radar', selectedProtocol='tcp', selectedRow=0;
let devices=[
 {no:'WX-001',name:'东区天气传感器',type:'天气传感器',region:'东区',protocol:'协议待确认',state:'待接入',enabled:false,address:'东区观测站'},
 {no:'RD-001',name:'东区探测雷达',type:'雷达',region:'东区',protocol:'TCP 直连',state:'离线',enabled:true,address:'东区监测塔'},
 {no:'EO-001',name:'东门光电设备',type:'光电设备',region:'东区',protocol:'MQTT',state:'在线',enabled:true,address:'东门楼顶'},
 {no:'CM-001',name:'西区反制设备',type:'反制设备',region:'西区',protocol:'TCP 直连',state:'在线',enabled:true,address:'西区监测塔'},
 {no:'TD-001',name:'东区 TDOA 设备',type:'TDOA',region:'东区',protocol:'MQTT',state:'在线',enabled:true,address:'东区观测站'}
];
let brokers=[{name:'东区设备接入连接',host:'mqtt.example.com',port:'8883',scope:'东区',tls:true}];
const field=(label,name,placeholder='',required=false,extra='')=>`<label><span>${required?'<i>*</i> ':''}${label}</span><input name="${name}" placeholder="${placeholder}" ${required?'required':''} ${extra}></label>`;
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(window.toastTimer);window.toastTimer=setTimeout(()=>$('toast').hidden=true,4000);}
function renderLedger(){
 const online=devices.filter(d=>d.state==='在线').length;
 const values=[['设备总数',devices.length,'当前权限范围内设备','#1677ff'],['在线设备',online,`在线率 ${Math.round(online/devices.length*100)}%`,'#16875b'],['离线设备',devices.filter(d=>d.state==='离线').length,'需检查设备连接','#c57813'],['异常设备',0,`另有 ${devices.filter(d=>d.state==='待接入').length} 台状态未知`,'#d93d4c'],['告警中设备',0,'包含在设备状态统计内','#c57813'],['接入厂家数',0,'示例资料未登记厂家','#7457d6']];
 $('metrics').innerHTML=values.map(v=>`<div class="metric"><span class="metric-dot" style="background:${v[3]}"></span><div class="metric-label">${v[0]}</div><strong>${v[1]}</strong><small>${v[2]}</small></div>`).join('');
 const keyword=$('search').value.trim().toLowerCase();
 const rows=devices.map((d,i)=>({...d,i})).filter(d=>(!keyword||(d.no+d.name).toLowerCase().includes(keyword))&&(!$('filter-type').value||d.type===$('filter-type').value)&&(!$('filter-state').value||d.state===$('filter-state').value));
 $('count').textContent=rows.length;$('total-label').textContent=`共 ${rows.length} 台设备`;
 $('device-rows').innerHTML=rows.length?rows.map(d=>`<tr class="${d.i===selectedRow?'selected':''}"><td><button class="text-btn row-name" data-row="${d.i}">${escapeHtml(d.no)}</button><small>${escapeHtml(d.name)}</small></td><td>${escapeHtml(d.type)}<small>${escapeHtml(d.protocol)}</small></td><td>${escapeHtml(d.region)}</td><td><span class="status-pill ${d.state==='在线'?'green':d.state==='待接入'?'amber':'gray'}">${d.state}</span></td><td>${d.enabled?'启用':'停用'}</td><td><button class="text-btn" data-row="${d.i}">查看</button></td></tr>`).join(''):'<tr><td colspan="6" class="empty">暂无匹配设备，请调整筛选条件。</td></tr>';
 document.querySelectorAll('[data-row]').forEach(b=>b.onclick=()=>{selectedRow=Number(b.dataset.row);renderLedger();});
 const d=devices[selectedRow];
 $('detail').innerHTML=`<div class="panel-heading"><h2>设备详情</h2><span class="muted">设备档案</span></div><div class="detail-head"><div class="detail-icon">${d.type==='天气传感器'?'☀':'◉'}</div><h2>${escapeHtml(d.name)}</h2><p>${escapeHtml(d.no)}</p><span class="status-pill ${d.state==='在线'?'green':d.state==='待接入'?'amber':'gray'}">${d.state}</span> <span class="status-pill gray">${d.enabled?'启用':'停用'}</span></div><div class="detail-body"><h3>基本资料</h3><dl>${[['设备类型',d.type],['所属单位','示例管理单位'],['所属区域',d.region],['安装位置',d.address||'未登记'],['供应商',d.vendor||'未登记'],['型号',d.model||'未登记']].map(([k,v])=>`<div><dt>${k}</dt><dd>${escapeHtml(v)}</dd></div>`).join('')}</dl><h3>接入信息</h3><dl><div><dt>接入方式</dt><dd>${escapeHtml(d.protocol)}</dd></div><div><dt>数据来源</dt><dd>设计示例</dd></div></dl><div class="detail-note">${d.type==='天气传感器'?'已登记设备档案。待厂家协议确认并完成接入验证后，才可启用与接收气象数据。':'连接状态以有效设备报文为依据。保存配置不会直接将设备标记为在线。'}</div></div>`;
}
function renderTypes(){
 $('device-types').innerHTML=types.map(t=>`<button type="button" class="type-choice ${t.id===selectedType?'selected':''}" aria-pressed="${t.id===selectedType}" data-type="${t.id}"><span class="symbol" aria-hidden="true">${t.symbol}</span>${t.name}</button>`).join('');
 document.querySelectorAll('[data-type]').forEach(b=>b.onclick=()=>{selectedType=b.dataset.type;selectedProtocol=types.find(t=>t.id===selectedType).protocols[0];renderTypes();renderConfig();});
}
function brokerOptions(){return '<option value="">请选择已配置连接</option>'+brokers.map((b,i)=>`<option value="${i}">${escapeHtml(b.name)} · ${escapeHtml(b.scope)}</option>`).join('');}
function updateConnectionSummary(){const value=$('broker-select')?.value;const b=value!==''?brokers[Number(value)]:null;if($('connection-summary'))$('connection-summary').innerHTML=b?`<span>服务器：${escapeHtml(b.host)}:${escapeHtml(b.port)}</span><span>范围：${escapeHtml(b.scope)}</span><span>来源：真实来源</span><span>${b.tls?'TLS 加密':'未启用 TLS'}</span>`:'请选择连接后查看服务器及所属范围。';}
function renderConfig(){
 const weather=selectedType==='weather',isMqtt=['mqtt','eo'].includes(selectedProtocol),t=types.find(t=>t.id===selectedType);
 $('config-title').textContent=weather?'接入状态':'接入配置';$('protocol-badge').textContent=weather?'协议待确认':isMqtt?'MQTT 接入':'TCP 直连';$('protocol-badge').className='status-pill'+(weather?' amber':'');
 $('submit-device').textContent=weather?'保存档案':'保存接入配置';$('submit-note').textContent=weather?'仅保存设备档案，保持停用与待接入。':'保存后需完成连接与数据验证。';
 if(weather){$('config-content').innerHTML='<div class="info weather-notice"><strong>厂家协议待确认，当前仅登记设备档案</strong>天气传感器统一纳入设备台账。协议确认前无需填写 IP、端口或 MQTT 连接，登记后保持停用。</div>';return;}
 let content=`<div class="protocol-row"><label><span><i>*</i> 接入协议</span><select id="protocol-select" ${t.protocols.length===1?'disabled':''}>${t.protocols.map(p=>`<option value="${p}" ${p===selectedProtocol?'selected':''}>${protocolNames[p]}</option>`).join('')}</select></label><div class="protocol-help">${t.protocols.length===1?'已匹配当前设备类型支持的协议':'仅展示该设备类型已支持的协议'}</div></div>`;
 if(isMqtt){content+=`<div class="form-grid"><label><span class="label-line"><span><i>*</i> MQTT 连接</span><button type="button" class="text-btn" id="manage-broker" aria-label="管理 MQTT 连接">管理连接 ↗</button></span><select id="broker-select" name="broker" aria-label="MQTT 连接" required>${brokerOptions()}</select></label>${field('外部设备编号','external_id','填写厂家设备 ID',true)}<div class="connection-summary wide" id="connection-summary">请选择连接后查看服务器及所属范围。</div>${selectedProtocol==='eo'?field('边缘中心 ID','edge_id','填写协议中的 edgeId',true):field('提供方编码','provider_code','填写厂家提供的编码',true)}${selectedType==='other'?'<label><span><i>*</i> 设备子类型</span><select name="subtype"><option>诱骗</option><option>干扰</option><option>驱鸟炮</option></select></label>':''}</div><div class="info" style="margin:16px 0 0">设备的所属单位、区域及数据来源须与所选连接一致。共用连接只需配置一次。</div>`;}
 else{content+=`<div class="form-grid">${field('设备地址','host','例如：192.168.1.100',true)}${field('端口','port','请输入设备端口',true,'type="number" min="1" max="65535"')}${field('允许网段','cidr','例如：192.168.1.0/24',true)}${selectedProtocol==='tcp'?field('雷达识别码引用','recognition','如需认证，填写凭据引用'):field('控制器地址','controller_address','1–244',true,'type="number" min="1" max="244" value="1"')}${selectedProtocol==='tcp'?'<div class="checkboxes wide"><label><input type="checkbox"> 采集 RTK</label><label><input type="checkbox"> 派生经纬度</label></div>':'<label>线缆编码<select><option>AUTO · 自动识别</option><option>RAW_BYTES</option><option>ASCII_HEX_SPACED</option><option>ASCII_HEX_COMPACT</option></select></label>'}</div>`;}
 $('config-content').innerHTML=content;
 $('protocol-select').onchange=e=>{selectedProtocol=e.target.value;renderConfig();};
 if(isMqtt){$('manage-broker').onclick=openBrokers;$('broker-select').onchange=updateConnectionSummary;}
}
function openDevice(){selectedType='radar';selectedProtocol='tcp';$('device-form').reset();renderTypes();renderConfig();$('device-dialog').showModal();}
function renderBrokers(){$('broker-list').innerHTML=brokers.map(b=>`<article class="connection-card"><h3>${escapeHtml(b.name)}</h3><p>${escapeHtml(b.host)}:${escapeHtml(b.port)} · 示例管理单位 / ${escapeHtml(b.scope)}</p><span class="status-pill">真实来源</span><span class="status-pill gray">停用 · 待联调</span><span class="status-pill gray">${b.tls?'TLS 加密':'未启用 TLS'}</span></article>`).join('');}
function openBrokers(){renderBrokers();$('broker-form').hidden=true;$('broker-dialog').showModal();}
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>$(b.dataset.close).close());
$('add-device').onclick=openDevice;$('preview-open').onclick=openDevice;
$('new-broker').onclick=()=>{$('broker-form').hidden=false;$('broker-form').scrollIntoView({block:'start',behavior:'smooth'});};
$('cancel-broker').onclick=()=>{$('broker-form').hidden=true;};
$('broker-form').onsubmit=e=>{e.preventDefault();const v=Object.fromEntries(new FormData(e.target));brokers.push({name:v.broker_name,host:v.host,port:v.port,scope:v.scope,tls:v.tls==='on'});renderBrokers();$('broker-form').hidden=true;e.target.reset();const previous=$('broker-select').value;$('broker-select').innerHTML=brokerOptions();$('broker-select').value=previous;};
$('device-form').onsubmit=e=>{
 e.preventDefault();const v=Object.fromEntries(new FormData(e.target));
 if(devices.some(d=>d.no===v.device_no.trim())){e.target.elements.device_no.setCustomValidity('该设备编号已存在，请使用其他编号。');e.target.elements.device_no.reportValidity();return;}
 if(['mqtt','eo'].includes(selectedProtocol)&&brokers[Number(v.broker)].scope!==v.scope){e.target.elements.scope.setCustomValidity('所属区域须与所选 MQTT 连接一致。');e.target.elements.scope.reportValidity();return;}
 const type=types.find(t=>t.id===selectedType);devices.unshift({no:v.device_no.trim(),name:v.name.trim(),type:type.name,region:v.scope,address:v.address,vendor:v.vendor,model:v.model,protocol:selectedType==='weather'?'协议待确认':['mqtt','eo'].includes(selectedProtocol)?'MQTT':'TCP 直连',state:'待接入',enabled:false});selectedRow=0;$('search').value='';$('filter-type').value='';$('filter-state').value='';renderLedger();$('device-dialog').close();toast('已加入设计预览台账，仅在本页展示，未写入业务系统。');
};
$('device-form').addEventListener('input',()=>{for(const el of $('device-form').elements)if(el.setCustomValidity)el.setCustomValidity('');});
$('query').onclick=renderLedger;$('search').addEventListener('keydown',e=>{if(e.key==='Enter')renderLedger();});$('reset').onclick=()=>{$('search').value='';$('filter-type').value='';$('filter-state').value='';renderLedger();};
renderLedger();
if(new URLSearchParams(location.search).get('view')==='access')openDevice();
