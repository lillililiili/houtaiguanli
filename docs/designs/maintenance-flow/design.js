(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const icons = {
    grid:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    device:'M7 3h10v18H7z M10 6h4 M11 18h2 M3 8h4 M17 8h4 M3 15h4 M17 15h4',
    monitor:'M3 4h18v13H3z M8 21h8 M12 17v4 M6 11h3l2-4 3 7 2-3h2',
    link:'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2 M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2',
    settings:'M4 7h16 M4 17h16 M8 4v6 M16 14v6',
    map:'M3 5l6-2 6 2 6-2v16l-6 2-6-2-6 2z M9 3v16 M15 5v16',
    chart:'M4 3v18h17 M8 16v-5 M13 16V7 M18 16V4',
    users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M17 4a4 4 0 0 1 0 7 M22 21v-2a4 4 0 0 0-3-4',
    bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
    tool:'M14 6l4 4 3-3a7 7 0 0 1-8 9l-6 6-5-5 6-6a7 7 0 0 1 9-8z',
    arrow:'M19 12H5 M11 6l-6 6 6 6',
    search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6',
    info:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 11v6 M12 7v1'
  };
  document.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${icons[el.dataset.icon]}"></path></svg>`; });
  let stage = 0, testStep = 0, unread = true, verification = null, entries = [], logs = [], timer, toastTimer;
  const names = ['待处理', '处理中', '待恢复核验', '已完成'];
  const tones = ['amber', 'blue', 'amber', 'green'];
  const escape = text => String(text).replace(/[&<>"']/g, value => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[value]));
  function toast(text) { clearTimeout(toastTimer); $('toast').textContent = text; $('toast').hidden = false; toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200); }
  function now() { return new Date().toLocaleTimeString('zh-CN', {hour12:false}); }
  function addEntry(title, note, time = now()) { entries.unshift({title, note, time}); drawTimeline(); }
  function drawTimeline() { $('timeline').innerHTML = entries.map(e => `<li><div class="row"><b>${escape(e.title)}</b><time>${escape(e.time)}</time></div><p>${escape(e.note)}</p></li>`).join(''); }
  function badge(id, text, tone) { $(id).textContent = text; $(id).className = `badge ${tone}`; }
  function closeNotice() { $('notice-panel').hidden = true; $('bell').setAttribute('aria-expanded', 'false'); }
  function read() { unread = false; $('unread-dot').hidden = true; $('unread-label').textContent = '暂无未读'; $('bell').setAttribute('aria-label', '运维消息，无未读'); }
  function page(name) {
    $('commission-page').hidden = name !== 'commission'; $('list-page').hidden = name !== 'list';
    $('breadcrumb').textContent = name === 'commission' ? '设备接入调测' : '设备管理';
    document.querySelectorAll('[data-page]').forEach(el => el.classList.toggle('active', el.dataset.page === name));
    closeNotice(); window.scrollTo({top:0, behavior:'instant'});
  }
  function filter() { const show = $('task-filter').value === 'all' || Number($('task-filter').value) === stage; $('task-row').hidden = !show; $('no-tasks').hidden = show; $('task-total').textContent = `共 ${show ? 1 : 0} 条`; }
  function render() {
    ['task-badge','list-status','notice-status'].forEach(id => badge(id, names[stage], tones[stage]));
    $('owner').textContent = $('list-owner').textContent = stage ? '运维管理员' : '待接手';
    $('preview-state').value = String(stage);
    $('main-action').textContent = ['开始处理','提交恢复核验','执行恢复核验','查看处理结果'][stage];
    $('main-action').disabled = testStep > 0 && testStep < 5;
    $('note').disabled = stage !== 1;
    $('save-note').disabled = stage !== 1;
    $('list-go').textContent = stage === 3 ? '查看结果' : stage ? '继续处理' : '去处理';
    $('notice-go').textContent = stage === 3 ? '查看结果 →' : '去处理 →';
    $('handling-hint').textContent = ['接手待办后可保存排查进展。','保存进展不会结束待办；完成排查后提交恢复核验。','核验未通过可继续处理，所有进展保留。','待办已完成，处理记录只读保留。'][stage];
    document.querySelectorAll('.flow li').forEach((li,i) => { li.className = i < stage ? 'done' : i === stage ? 'current' : ''; li.querySelector('span').textContent = i < stage ? '✓' : String(i+1); });
    $('recovery-summary').hidden = stage !== 3;
    $('recovery-summary').textContent = '✓ 模拟恢复核验通过，处理结论已保存并回传。';
    renderTest(); filter(); drawTimeline();
  }
  function renderTest() {
    const labels = ['创建调测任务','建立连接','保存配置','开始协议调测','调测中…','重新调测'];
    const statuses = ['未开始','待连接','已连接','待调测','调测中','模拟通过'];
    $('test-action').textContent = labels[testStep];
    $('test-action').disabled = stage !== 1 || testStep === 4;
    $('test-cancel').hidden = !(stage === 1 && testStep > 0 && testStep < 4);
    $('report-button').hidden = testStep !== 5;
    ['host','port','timeout'].forEach(id => { $(id).disabled = stage !== 1 || testStep !== 2; });
    badge('test-status', statuses[testStep], testStep === 5 ? 'green' : testStep ? 'blue' : 'gray');
    $('commission-no').textContent = testStep ? 'CT-20260927-001' : '尚未创建任务';
    $('test-task').textContent = testStep ? 'CT-20260927-001' : '尚未创建';
    $('test-start').textContent = testStep >= 4 ? '2026/09/27 09:12:00' : '—';
    $('test-duration').textContent = testStep === 5 ? '6 秒（模拟）' : '—';
    ['cs-0','cs-1','cs-2'].forEach((id,i) => $(id).classList.toggle('active', i === (testStep < 2 ? 0 : testStep === 2 ? 1 : 2)));
    $('log-count').textContent = `${logs.length} 条`;
    $('test-log').innerHTML = logs.length ? [...logs].reverse().map((text,i) => `<article class="log-entry"><time>09:12:0${logs.length-i-1}</time><b>${escape(text[0])}</b><p>${escape(text[1])}</p></article>`).join('') : '<div class="empty-log"><p>等待调测开始</p><small>连接与协议检查结果将在这里显示</small></div>';
    $('test-result').innerHTML = testStep === 5 ? '<span class="badge green">模拟通过</span><p class="hint">连接建立、协议响应及报文解析检查通过。此结果不代替设备恢复核验。</p>' : '任务完成后显示调测结论。';
    $('test-hint').textContent = stage === 0 ? '请先接手上方运维待办，再开展设备排查。' : stage === 3 ? '本次待办已结束，调测记录只读展示。' : testStep === 5 ? '调测报告已关联。请记录处理措施，再提交恢复核验。' : '连接参数仅作用于本次任务；所有操作均为模拟演示。';
    $('records').innerHTML = testStep === 5 ? '<tr><td class="code">CT-20260927-001</td><td>2026/09/27 09:12:00</td><td><span class="badge green">模拟通过</span></td><td class="code">YW-20260927-001</td><td><button class="text-button" id="record-report">查看报告</button></td></tr>' : '<tr><td colspan="5" class="empty-row">暂无联调记录，完成本次调测后自动关联</td></tr>';
    if ($('record-report')) $('record-report').onclick = report;
    $('health').textContent = stage === 3 ? '模拟恢复' : '待核查';
    $('connectivity').innerHTML = stage === 3 ? '<span class="status-dot"></span>模拟在线' : '<span class="status-dot bad"></span>异常';
  }
  function modal(title, content, actions) {
    $('modal-title').textContent = title; $('modal-content').innerHTML = content; $('modal-footer').replaceChildren();
    actions.forEach(({label, primary, action}) => { const button = document.createElement('button'); button.textContent = label; button.className = `button${primary ? ' primary' : ''}`; button.onclick = action; $('modal-footer').append(button); });
    if (!$('modal').open) $('modal').showModal();
  }
  const close = () => $('modal').close();
  function report() { modal('关联调测报告', '<p class="dialog-copy">模拟 5G-A 01 · CT-20260927-001</p><div class="check-row"><b>连接建立</b><span class="badge green">模拟通过</span></div><div class="check-row"><b>协议响应</b><span class="badge green">模拟通过</span></div><div class="check-row"><b>报文解析</b><span class="badge green">模拟通过</span></div><p class="hint">报告自动关联 YW-20260927-001。开发模拟结果不作为真实设备恢复依据。</p>', [{label:'关闭', action:close}]); }
  function startHandling() { stage = 1; read(); addEntry('开始处理', '运维管理员已接手，开始核查设备状态。'); render(); toast('已开始处理，可进行排查并保存进展'); }
  function submitVerification() {
    if (testStep > 0 && testStep < 5) return toast('请先完成或取消当前调测任务');
    modal('提交恢复核验', `<p class="dialog-copy">确认已完成排查，填写处理措施。提交后进入“待恢复核验”，待办仍未结束。</p><div class="check-row"><b>关联调测报告</b><span class="badge ${testStep === 5 ? 'green' : 'gray'}">${testStep === 5 ? '模拟通过 · 1 份' : '暂无报告'}</span></div><label class="dialog-label" for="submit-note">处理措施与核验说明 <span style="color:#d93d4c">*</span></label><textarea class="dialog-textarea" id="submit-note" maxlength="1000" placeholder="例如：已检查网络连接并恢复设备上报，申请核验当前状态。">${escape($('note').value)}</textarea><p class="form-error" id="submit-error" role="alert" hidden></p>`, [{label:'继续排查',action:close},{label:'提交恢复核验',primary:true,action:() => {
      const note = $('submit-note').value.trim();
      if (note.length < 2) { $('submit-error').textContent = '请填写至少 2 字的处理说明。'; $('submit-error').hidden = false; return; }
      addEntry('提交恢复核验', `运维管理员：${note}`); stage = 2; verification = null; $('note').value = ''; render(); close(); toast('已提交恢复核验，待办继续跟踪');
    }}]);
  }
  function recoveryDialog() {
    verification = null;
    modal('设备恢复核验', '<div class="scenario-switch"><label for="recovery-case">设计稿情境</label><select id="recovery-case"><option value="pass">模拟恢复正常</option><option value="fail">模拟设备仍异常</option></select></div><p class="dialog-copy">核对当前设备状态与本次上报异常。调测通过不会直接结束待办。</p><div id="checks"><div class="check-row"><div><b>当前连接状态</b><small>核对设备是否在线</small></div><span class="badge gray">待核验</span></div><div class="check-row"><div><b>有效状态上报</b><small>检查状态是否新鲜有效</small></div><span class="badge gray">待核验</span></div><div class="check-row"><div><b>本次异常是否恢复</b><small>核对关联异常与当前健康状态</small></div><span class="badge gray">待核验</span></div></div><p id="recovery-message" class="hint">此次操作仅演示核验流程。</p>', [{label:'稍后核验',action:close},{label:'执行核验',primary:true,action:runRecovery}]);
  }
  function runRecovery() {
    verification = $('recovery-case').value;
    $('recovery-case').disabled = true;
    $('checks').querySelectorAll('.badge').forEach((el,i) => { const passed = verification === 'pass'; el.className = `badge ${passed ? 'green' : 'red'}`; el.textContent = passed ? ['模拟在线','上报有效','模拟恢复'][i] : ['仍离线','上报已过期','异常未恢复'][i]; });
    $('recovery-message').textContent = verification === 'pass' ? '模拟核验通过。保存处理结论后，才能完成本条待办。' : '核验未通过，待办不能完成。请继续排查设备连接和上报状态。';
    addEntry(verification === 'pass' ? '模拟恢复核验通过' : '模拟恢复核验未通过', verification === 'pass' ? '设备在线、上报有效、本次异常已恢复。等待保存最终结论。' : '设备仍离线，上报已过期，需要继续处理。');
    const footer = $('modal-footer'); footer.replaceChildren();
    const back = document.createElement('button'); back.className = 'button'; back.textContent = '关闭'; back.onclick = close; footer.append(back);
    const next = document.createElement('button'); next.className = 'button primary'; next.textContent = verification === 'pass' ? '填写结论并完成' : '返回继续处理';
    next.onclick = verification === 'pass' ? completeDialog : () => { stage = 1; verification = null; addEntry('继续处理', '核验未通过，运维管理员继续排查。'); render(); close(); toast('已返回处理中，原有记录保留'); }; footer.append(next);
  }
  function completeDialog() {
    if (verification !== 'pass') return;
    modal('完成运维待办', '<p class="dialog-copy"><span class="badge green">模拟恢复核验通过</span></p><label class="dialog-label" for="final-note">最终处理结论 <span style="color:#d93d4c">*</span></label><textarea id="final-note" class="dialog-textarea" maxlength="1000" placeholder="说明故障原因、处理措施和恢复结果"></textarea><p class="form-error" id="final-error" role="alert" hidden></p><p class="hint">完成后，处理记录、关联报告与核验结果一并保留，并向业务前台反馈结果。</p>', [{label:'返回核验',action:recoveryDialog},{label:'确认完成待办',primary:true,action:() => {
      const note = $('final-note').value.trim(); if (note.length < 2) { $('final-error').textContent = '请填写至少 2 字的最终处理结论。'; $('final-error').hidden = false; return; }
      stage = 3; addEntry('待办已完成', `运维管理员：${note}`); render(); close(); toast('示例待办已完成，处理结果已回传');
    }}]);
  }
  function resultDialog() { const done = entries.find(e => e.title === '待办已完成'); modal('运维处理结果', `<div class="completion-box"><span class="completion-check">✓</span><h3>本条待办已完成</h3><p>模拟 5G-A 01 · YW-20260927-001</p></div><dl class="details"><dt>处理人</dt><dd>运维管理员</dd><dt>恢复核验</dt><dd>模拟通过</dd><dt>调测报告</dt><dd>${testStep === 5 ? 'CT-20260927-001' : '未关联'}</dd><dt>处理结论</dt><dd>${escape(done?.note || '示例处理完成')}</dd><dt>业务反馈</dt><dd>已回传关联计划（演示）</dd></dl>`, [{label:'关闭',action:close}]); }
  function testAction() {
    if (stage !== 1 || testStep === 4) return;
    if (testStep === 5) { testStep = 0; logs = []; }
    if (testStep === 0) { testStep = 1; logs.push(['已创建','调测任务已关联当前运维待办。']); }
    else if (testStep === 1) { testStep = 2; logs.push(['模拟连接成功','已连接模拟适配器，可以核对并保存参数。']); }
    else if (testStep === 2) {
      if (!$('host').value.trim() || !Number.isInteger(Number($('port').value)) || Number($('port').value) < 1 || Number($('port').value) > 65535 || Number($('timeout').value) < 100) return toast('请填写主机、有效端口和至少 100 ms 的超时');
      testStep = 3; logs.push(['配置已保存','网络及超时配置已保存至本次任务。']);
    } else if (testStep === 3) {
      testStep = 4; logs.push(['开始调测','正在模拟协议响应及报文解析检查。']);
      timer = setTimeout(() => { testStep = 5; logs.push(['模拟通过','协议响应及报文解析检查通过，报告已保存。']); addEntry('调测报告已关联','CT-20260927-001 · 模拟通过；尚未确认设备恢复。'); render(); toast('模拟调测完成，报告已关联待办'); }, 900);
    }
    render();
  }
  function reset(target = 0) {
    clearTimeout(timer); close(); stage = target; testStep = target >= 2 ? 5 : 0; verification = target === 3 ? 'pass' : null;
    unread = target === 0; $('unread-dot').hidden = !unread; $('unread-label').textContent = unread ? '1 条未读' : '暂无未读'; $('bell').setAttribute('aria-label', unread ? '运维消息，1条未读' : '运维消息，无未读');
    entries = [{title:'异常已上报',note:'业务前台生成运维待办，并向运维人员发送站内提醒。',time:'08:49:20'}];
    logs = target >= 2 ? [['模拟连接成功','已连接模拟适配器。'],['配置已保存','参数已保存至本次任务。'],['模拟通过','协议响应及报文解析检查通过。']] : [];
    if (target >= 1) addEntry('开始处理','运维管理员已接手，检查设备网络与连接状态。','09:05:12');
    if (target >= 2) { addEntry('调测报告已关联','CT-20260927-001 · 模拟通过。','09:12:06'); addEntry('提交恢复核验','已恢复模拟设备上报，等待核验原异常。','09:15:20'); }
    if (target === 3) { addEntry('模拟恢复核验通过','设备在线、上报有效、本次异常已恢复。','09:16:08'); addEntry('待办已完成','运维管理员：已检查并恢复连接，模拟状态核验通过。','09:17:00'); }
    $('note').value = ''; $('host').value = '192.0.2.10'; $('port').value = '9000'; $('timeout').value = '3000'; $('task-filter').value = 'all'; $('device-search').value = ''; search(); closeNotice(); render();
  }
  function search() { let count = 0; const query = $('device-search').value.toLowerCase().trim(); document.querySelectorAll('.device-option').forEach(el => { el.hidden = !el.textContent.toLowerCase().includes(query); if (!el.hidden) count++; }); $('search-empty').hidden = count > 0; }
  document.querySelectorAll('[data-page]').forEach(el => el.onclick = () => page(el.dataset.page));
  $('bell').onclick = () => { $('notice-panel').hidden = !$('notice-panel').hidden; $('bell').setAttribute('aria-expanded', String(!$('notice-panel').hidden)); };
  $('read-all').onclick = () => { read(); toast('消息已标记已读，待办状态保持不变'); };
  $('notice-go').onclick = () => { read(); page('commission'); };
  $('all-tasks').onclick = $('back-list').onclick = () => page('list');
  $('list-go').onclick = () => { read(); page('commission'); };
  $('task-filter').onchange = filter;
  $('refresh').onclick = () => { filter(); toast('示例待办已刷新'); };
  $('main-action').onclick = () => [startHandling, submitVerification, recoveryDialog, resultDialog][stage]();
  $('test-action').onclick = testAction;
  $('test-cancel').onclick = () => { testStep = 0; logs = []; addEntry('调测已取消','当前调测已取消，运维待办仍在处理中。'); render(); };
  $('report-button').onclick = report;
  $('save-note').onclick = () => { const note = $('note').value.trim(); if (note.length < 2) return toast('请填写至少 2 字的处理进展'); addEntry('处理进展已保存',`运维管理员：${note}`); $('note').value = ''; toast('进展已保存，待办继续处理中'); };
  $('task-detail').onclick = () => modal('运维待办上报详情','<dl class="details"><dt>待办编号</dt><dd>YW-20260927-001</dd><dt>异常设备</dt><dd>模拟 5G-A 01</dd><dt>上报时间</dt><dd>2026/09/27 08:49:20</dd><dt>上报来源</dt><dd>业务前台 · 飞行计划设备检查</dd><dt>异常说明</dt><dd>设备自动检查发现异常，尚无未关闭告警说明，请运维核查。</dd><dt>上报快照</dt><dd>连接异常、健康待核查（示例）</dd><dt>通知记录</dt><dd>08:49:20 站内提醒已生成（示例）</dd></dl>',[{label:'关闭',action:close}]);
  $('device-search').oninput = search;
  $('modal-close').onclick = close;
  $('preview-state').onchange = () => reset(Number($('preview-state').value));
  $('reset').onclick = () => { reset(); page('commission'); toast('演示已重置'); };
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeNotice(); });
  document.addEventListener('click', event => { if (!event.target.closest('.notice-wrap')) closeNotice(); });
  reset();
})();
