'use strict';

(function () {
  const page = document.body.dataset.page || 'index';
  const $ = MR.$;

  const META = {
    index: ['테스트 대시보드','관리자 기능 검증용 실제 결제 데이터를 사용자 API로 준비합니다.'],
    'personal-create': ['개인 신청','개인 Registration과 최초 Payment READY 주문을 생성합니다.'],
    'group-create': ['단체 신청','Organization과 참가자 N명, 최초 Payment READY 주문을 생성합니다.'],
    'personal-lookup': ['개인 신청 확인','현재 개인 신청·결제·환불 상태를 본인확인 후 조회합니다.'],
    'group-lookup': ['단체 신청 확인','현재 단체 구성원과 결제·환불 상태를 단체장 인증으로 조회합니다.'],
    'personal-modify': ['개인 신청 수정','현재 개인 신청을 수정하고 추가결제 주문 또는 환불 결과를 확인합니다.'],
    'group-modify': ['단체 신청 수정','최종 전체 명단 기준으로 추가·수정·제거와 금융 결과를 확인합니다.'],
    'personal-cancel': ['개인 취소 / 환불','개인 참가 전체 취소를 요청합니다. 납부액이 있으면 서버 환불 흐름이 실행됩니다.'],
    'group-cancel': ['단체 취소 / 환불','단체의 현재 활성 구성원 전체를 취소하고 환불 흐름을 확인합니다.'],
    'personal-additional-payment': ['개인 추가결제 준비','관리자 수정 등으로 ADDITIONAL_PAYMENT_REQUIRED가 된 개인의 추가금 주문을 준비합니다.'],
    'group-additional-payment': ['단체 추가결제 준비','추가 납부가 필요한 확정 단체원들의 부족액을 한 주문으로 준비합니다.'],
    'payment-retry': ['결제 주문 재준비','실패한 기존 Payment를 개인/단체 본인확인 후 재준비합니다.'],
    payment: ['Toss 테스트 결제 / 승인','서버가 생성한 주문만 사용해 Toss 인증 후 백엔드 승인까지 수행합니다.']
  };

  MR.shell(...(META[page] || META.index));
  MR.wireStandard();

  function setEndpoint(method, path) {
    try { MR.endpoint(method, path); }
    catch (e) { MR.updateEndpoint(e.message); }
  }

  function payButton(show) {
    const el = $('goPay');
    if (!el) return;
    el.classList.toggle('hidden', !show);
  }

  function currentPersonalAccess() {
    return MR.read('personalAccess', MR.personalAccess());
  }

  function currentGroupAccess() {
    return MR.read('groupAccess', MR.groupAccess());
  }

  function toSelections(list) {
    return (list || []).map((s) => ({souvenirId:s.souvenirId, selectedSize:s.selectedSize}));
  }

  function personalModifyFrom(row) {
    return {
      access: currentPersonalAccess(),
      eventCategoryId: row.eventCategoryId,
      selectedSouvenirList: toSelections(row.selectedSouvenirList),
      name: row.name,
      phNum: row.phNum,
      birth: row.birth,
      gender: row.gender,
      address: row.address,
      addressDetail: row.addressDetail,
      guardianConsent: Boolean(row.guardianConsent),
      guardianName: row.guardianName,
      guardianPhNum: row.guardianPhNum,
      guardianRelationship: row.guardianRelationship,
      email: row.email
    };
  }

  function groupModifyFrom(org) {
    return {
      guardianConsent: true,
      email: org.email,
      address: org.address,
      addressDetail: org.addressDetail,
      leaderName: org.leaderName,
      leaderBirth: org.leaderBirth,
      leaderPhNum: org.leaderPhNum,
      access: currentGroupAccess(),
      registrations: (org.registrations || []).map((r) => ({
        registrationId: r.registrationId,
        eventCategoryId: r.eventCategoryId,
        selectedSouvenirList: toSelections(r.selectedSouvenirList),
        name: r.name,
        phNum: r.phNum,
        birth: r.birth,
        gender: r.gender
      }))
    };
  }

  function firstOrder(data) {
    const orders = data?.orders || [];
    if (orders.length > 1) throw new Error('응답 orders가 2건 이상입니다. 현재 예상 계약과 달라 결제를 차단했습니다.');
    return orders[0] || null;
  }

  function storeSettlementOrder(data, meta) {
    const order = firstOrder(data);
    if (!order) { payButton(false); return null; }
    const blocked = (data.refunds || []).some((r) => ['PROCESSING','UNKNOWN'].includes(r.status));
    if (blocked) {
      payButton(false);
      MR.report('환불 PROCESSING/UNKNOWN이 존재하므로 새 결제 진행을 차단했습니다. 응답 상태를 확인하세요.');
      return null;
    }
    MR.storeOrder(order, meta);
    payButton(true);
    return order;
  }

  function renderLookup(kind, rows) {
    const host = $('lookupRows');
    if (!host) return;
    if (!Array.isArray(rows) || rows.length === 0) {
      host.innerHTML = '<div class="note">조회 결과 없음</div>';
      return;
    }
    host.innerHTML = rows.map((row, i) => {
      const id = kind === 'personal' ? row.registrationId : row.organizationId;
      const name = kind === 'personal' ? row.name : row.organizationName;
      const state = row.registrationStatus || '-';
      const action = row.paymentAction || '-';
      const amount = row.totalAmount ?? '-';
      const paid = row.paidAmount ?? '-';
      return `<div class="result-row"><strong>${MR.escapeHtml(name)}</strong> <span class="pill">${MR.escapeHtml(state)}</span><br><code>${MR.escapeHtml(id)}</code><div class="small">계약 ${MR.escapeHtml(amount)} / 납부 ${MR.escapeHtml(paid)} / 후속 ${MR.escapeHtml(action)}</div><button type="button" class="secondary chooseLookup" data-index="${i}">이 항목을 현재 대상으로 사용</button></div>`;
    }).join('');
    host.querySelectorAll('.chooseLookup').forEach((btn) => btn.addEventListener('click', () => {
      const row = rows[Number(btn.dataset.index)];
      if (kind === 'personal') {
        MR.storeTarget('registrationId', row.registrationId);
        MR.write('selectedPersonalLookup', row);
      } else {
        MR.storeTarget('organizationId', row.organizationId);
        MR.write('selectedGroupLookup', row);
      }
      MR.report('현재 대상으로 저장했습니다: ' + (kind === 'personal' ? row.registrationId : row.organizationId));
    }));
  }

  function bindIdInput(id, targetKey, refresh) {
    const el = $(id);
    if (!el) return;
    el.value = MR.target(targetKey);
    el.addEventListener('input', () => {
      MR.storeTarget(targetKey, el.value.trim());
      refresh?.();
    });
  }

  function selectedPersonalRow() {
    const selected = MR.read('selectedPersonalLookup');
    if (selected) return selected;
    const rows = MR.lookup('personal');
    const id = MR.target('registrationId');
    return Array.isArray(rows) ? (rows.find((r) => r.registrationId === id) || rows[0]) : null;
  }

  function selectedGroupRow() {
    const selected = MR.read('selectedGroupLookup');
    if (selected) return selected;
    const rows = MR.lookup('group');
    const id = MR.target('organizationId');
    return Array.isArray(rows) ? (rows.find((r) => r.organizationId === id) || rows[0]) : null;
  }

  function initIndex() {
    const refresh = () => {
      const state = {
        config: MR.config(),
        registrationId: MR.target('registrationId') || null,
        organizationId: MR.target('organizationId') || null,
        paymentId: MR.target('paymentId') || null,
        preparedOrder: MR.order() || null,
        lastPaymentConfirm: MR.read('lastPaymentConfirm') || null
      };
      $('state').textContent = MR.pretty(MR.mask(state));
    };
    $('loadOptions')?.addEventListener('click', () => MR.runApi(async () => {
      const data = await MR.loadOptions();
      $('catalog').textContent = MR.pretty(data);
      MR.report('최신 신청 선택지를 조회했습니다.');
    }));
    document.addEventListener('mr-config-changed', refresh);
    refresh();
  }

  function initPersonalCreate() {
    const endpoint = () => setEndpoint('POST', MR.eventRoot() + '/registrations');
    const template = () => {
      const it = MR.identity();
      MR.setJsonTextarea({
        eventCategoryId:'tc1', selectedSouvenirList:[{souvenirId:'test-souvenir',selectedSize:'FREE'}],
        password:'123456', name:it.name, phNum:it.phNum, birth:'1990-01-01', gender:'M',
        address:'테스트 주소', addressDetail:'101호', guardianConsent:true, guardianName:'테스트보호자',
        guardianPhNum:'010-9000-0000', guardianRelationship:'보호자',
        termsEssentialAgreed:true, termsMarketingAgreed:true, termsMarketingChannelAgreed:true, email:it.email
      });
    };
    $('newTemplate').onclick = template;
    $('send').onclick = () => MR.runApi(async () => {
      const body = MR.parseJsonTextarea();
      MR.validateParticipant(body);
      const data = await MR.request(MR.eventRoot() + '/registrations','POST',body);
      MR.storeTarget('registrationId', data.registrationId);
      MR.storeTarget('paymentId', data.paymentId);
      MR.write('personalAccess', {name:body.name,birth:body.birth,phNum:body.phNum,password:body.password});
      MR.storeOrder(data,{scope:'personal', registrationId:data.registrationId, purpose:'INITIAL'});
      payButton(true);
      MR.report('개인 신청 생성 완료. 서버가 만든 최초 주문을 결제할 수 있습니다.');
    });
    document.addEventListener('mr-config-changed', endpoint);
    endpoint(); template(); payButton(Boolean(MR.order()));
  }

  function initGroupCreate() {
    const endpoint = () => setEndpoint('POST', MR.eventRoot() + '/registrations/organization');
    const template = () => {
      const count = Math.max(1, Math.min(20, Number($('memberCount').value || 2)));
      const base = MR.nextSequence();
      const leader = MR.identity(base);
      const registrations = Array.from({length:count}, (_,i) => MR.defaultMember(base + i + 1));
      MR.setJsonTextarea({
        account:{organizationName:leader.groupName,organizationLoginId:leader.groupLoginId,organizationPassword:'123456'},
        profile:{address:'테스트 주소',addressDetail:'101호',birth:'1990-01-01',phNum:leader.phNum,email:leader.email,leaderName:'단체장'+String(base),guardianConsent:true},
        registrations,
        termsEssentialAgreed:true,termsMarketingAgreed:true,termsMarketingChannelAgreed:true
      });
    };
    $('newTemplate').onclick = template;
    $('send').onclick = () => MR.runApi(async () => {
      const body = MR.parseJsonTextarea();
      if (!Array.isArray(body.registrations) || body.registrations.length === 0) throw new Error('registrations가 비어 있습니다.');
      body.registrations.forEach(MR.validateParticipant);
      const data = await MR.request(MR.eventRoot() + '/registrations/organization','POST',body);
      MR.storeTarget('organizationId', data.organizationId);
      MR.storeTarget('paymentId', data.paymentId);
      MR.write('groupAccess', {loginId:body.account.organizationLoginId,password:body.account.organizationPassword});
      MR.storeOrder(data,{scope:'group', organizationId:data.organizationId, purpose:'INITIAL'});
      payButton(true);
      MR.report('단체 신청 생성 완료. 서버가 만든 최초 주문을 결제할 수 있습니다.');
    });
    document.addEventListener('mr-config-changed', endpoint);
    endpoint(); template(); payButton(Boolean(MR.order()));
  }

  function initPersonalLookup() {
    const endpoint = () => setEndpoint('POST', MR.eventRoot() + '/registrations/lookup');
    const template = () => MR.setJsonTextarea(currentPersonalAccess());
    $('useStored').onclick = template;
    $('send').onclick = () => MR.runApi(async () => {
      const body = MR.parseJsonTextarea();
      const rows = await MR.request(MR.eventRoot() + '/registrations/lookup','POST',body);
      MR.write('personalAccess', body);
      MR.storeLookup('personal', rows);
      renderLookup('personal', rows);
      if (rows.length === 1) {
        MR.storeTarget('registrationId', rows[0].registrationId);
        MR.write('selectedPersonalLookup', rows[0]);
      }
      MR.report('개인 신청 조회 완료. 수정/취소/추가결제 페이지에서 이 인증값을 재사용할 수 있습니다.');
    });
    document.addEventListener('mr-config-changed', endpoint);
    endpoint(); template(); renderLookup('personal', MR.lookup('personal') || []);
  }

  function initGroupLookup() {
    const endpoint = () => setEndpoint('POST', MR.eventRoot() + '/organizations/lookup');
    const template = () => MR.setJsonTextarea(currentGroupAccess());
    $('useStored').onclick = template;
    $('send').onclick = () => MR.runApi(async () => {
      const body = MR.parseJsonTextarea();
      const rows = await MR.request(MR.eventRoot() + '/organizations/lookup','POST',body);
      MR.write('groupAccess', body);
      MR.storeLookup('group', rows);
      renderLookup('group', rows);
      if (rows.length === 1) {
        MR.storeTarget('organizationId', rows[0].organizationId);
        MR.write('selectedGroupLookup', rows[0]);
      }
      MR.report('단체 신청 조회 완료. 현재 활성 구성원 전체가 응답에 포함됩니다.');
    });
    document.addEventListener('mr-config-changed', endpoint);
    endpoint(); template(); renderLookup('group', MR.lookup('group') || []);
  }

  function initPersonalModify() {
    const refresh = () => {
      const id = $('registrationId').value.trim();
      if (id) setEndpoint('PATCH', MR.eventRoot() + '/registrations/' + encodeURIComponent(id));
      else MR.updateEndpoint('registrationId를 입력하세요.');
    };
    bindIdInput('registrationId','registrationId',refresh);
    $('fromLookup').onclick = () => {
      const row = selectedPersonalRow();
      if (!row) return MR.report('먼저 개인 신청 조회를 수행하세요.', true);
      $('registrationId').value = row.registrationId; MR.storeTarget('registrationId', row.registrationId); refresh();
      MR.setJsonTextarea(personalModifyFrom(row));
      MR.report('최근 조회값으로 수정 본문을 구성했습니다. 바깥쪽 필드만 원하는 값으로 변경하세요.');
    };
    $('send').onclick = () => MR.runApi(async () => {
      const id = $('registrationId').value.trim(); if (!id) throw new Error('registrationId를 입력하세요.');
      const body = MR.parseJsonTextarea(); MR.validateParticipant(body);
      const data = await MR.request(MR.eventRoot() + '/registrations/' + encodeURIComponent(id),'PATCH',body);
      MR.write('personalAccess', {name:body.name,birth:body.birth,phNum:body.phNum,password:body.access.password});
      storeSettlementOrder(data,{scope:'personal',registrationId:id,purpose:'MODIFICATION'});
      MR.report('개인 수정 완료. members / refunds / orders를 각각 확인하세요.');
    });
    document.addEventListener('mr-config-changed', refresh); refresh();
    const row = selectedPersonalRow(); MR.setJsonTextarea(row ? personalModifyFrom(row) : {access:currentPersonalAccess(),eventCategoryId:'tc1',selectedSouvenirList:[{souvenirId:'test-souvenir',selectedSize:'FREE'}],name:'테스터',phNum:'010-9000-0001',birth:'1990-01-01',gender:'M',address:'테스트 주소',addressDetail:null,guardianConsent:true,guardianName:null,guardianPhNum:null,guardianRelationship:null,email:'tester@example.com'});
    payButton(false);
  }

  function initGroupModify() {
    const refresh = () => {
      const id = $('organizationId').value.trim();
      if (id) setEndpoint('PATCH', MR.eventRoot() + '/organizations/' + encodeURIComponent(id) + '/registrations');
      else MR.updateEndpoint('organizationId를 입력하세요.');
    };
    bindIdInput('organizationId','organizationId',refresh);
    $('fromLookup').onclick = () => {
      const row = selectedGroupRow();
      if (!row) return MR.report('먼저 단체 신청 조회를 수행하세요.', true);
      $('organizationId').value = row.organizationId; MR.storeTarget('organizationId',row.organizationId); refresh();
      MR.setJsonTextarea(groupModifyFrom(row));
      MR.report('현재 활성 구성원 전체를 수정 본문으로 구성했습니다. 삭제할 사람만 목록에서 제거하고 신규는 registrationId:null로 추가하세요.');
    };
    $('send').onclick = () => MR.runApi(async () => {
      if (!$('rosterAck').checked) throw new Error('최종 전체 명단 확인란을 체크하세요.');
      const id = $('organizationId').value.trim(); if (!id) throw new Error('organizationId를 입력하세요.');
      const body = MR.parseJsonTextarea();
      if (!Array.isArray(body.registrations) || body.registrations.length === 0) throw new Error('registrations가 비어 있습니다. 전체 취소는 취소 API를 사용하세요.');
      body.registrations.forEach(MR.validateParticipant);
      if (!confirm('본문에서 빠진 기존 구성원은 제거/환불 후보가 됩니다. 이 최종 명단으로 수정할까요?')) return;
      const data = await MR.request(MR.eventRoot() + '/organizations/' + encodeURIComponent(id) + '/registrations','PATCH',body);
      MR.write('groupAccess', body.access);
      storeSettlementOrder(data,{scope:'group',organizationId:id,purpose:'MODIFICATION'});
      MR.report('단체 수정 완료. 신규/수정/제거 결과와 refunds/orders를 확인하세요.');
    });
    document.addEventListener('mr-config-changed', refresh); refresh();
    const row = selectedGroupRow(); MR.setJsonTextarea(row ? groupModifyFrom(row) : {guardianConsent:true,email:'group@example.com',address:'테스트 주소',addressDetail:null,leaderName:'테스트단체장',leaderBirth:'1990-01-01',leaderPhNum:'010-9000-0001',access:currentGroupAccess(),registrations:[]});
    payButton(false);
  }

  function initCancel(kind) {
    const isPersonal = kind === 'personal';
    const inputId = isPersonal ? 'registrationId' : 'organizationId';
    const key = isPersonal ? 'registrationId' : 'organizationId';
    const refresh = () => {
      const id = $(inputId).value.trim();
      const path = isPersonal ? '/registrations/' : '/organizations/';
      if (id) setEndpoint('POST', MR.eventRoot() + path + encodeURIComponent(id) + '/cancellation');
      else MR.updateEndpoint(inputId + '를 입력하세요.');
    };
    bindIdInput(inputId,key,refresh);
    MR.setJsonTextarea(isPersonal ? currentPersonalAccess() : currentGroupAccess());
    $('useStored').onclick = () => MR.setJsonTextarea(isPersonal ? currentPersonalAccess() : currentGroupAccess());
    $('send').onclick = () => MR.runApi(async () => {
      const id = $(inputId).value.trim(); if (!id) throw new Error(inputId + '를 입력하세요.');
      if (!confirm(isPersonal ? '이 개인 신청을 전체 취소합니다. 납부액이 있으면 환불이 실행될 수 있습니다. 진행할까요?' : '이 단체의 현재 활성 구성원을 모두 취소합니다. 환불이 실행될 수 있습니다. 진행할까요?')) return;
      const body = MR.parseJsonTextarea();
      const path = isPersonal ? '/registrations/' : '/organizations/';
      const data = await MR.request(MR.eventRoot() + path + encodeURIComponent(id) + '/cancellation','POST',body);
      MR.report('취소 요청 완료. refunds의 status가 COMPLETED인지 반드시 확인하세요.');
      if (isPersonal) MR.write('personalAccess', body); else MR.write('groupAccess', body);
      return data;
    });
    document.addEventListener('mr-config-changed', refresh); refresh();
  }

  function initAdditional(kind) {
    const isPersonal = kind === 'personal';
    const inputId = isPersonal ? 'registrationId' : 'organizationId';
    const key = isPersonal ? 'registrationId' : 'organizationId';
    const refresh = () => {
      const id = $(inputId).value.trim();
      const path = isPersonal ? '/registrations/' : '/organizations/';
      if (id) setEndpoint('POST', MR.eventRoot() + path + encodeURIComponent(id) + '/payments/additional/prepare');
      else MR.updateEndpoint(inputId + '를 입력하세요.');
    };
    bindIdInput(inputId,key,refresh);
    MR.setJsonTextarea(isPersonal ? currentPersonalAccess() : currentGroupAccess());
    $('useStored').onclick = () => MR.setJsonTextarea(isPersonal ? currentPersonalAccess() : currentGroupAccess());
    $('send').onclick = () => MR.runApi(async () => {
      const id = $(inputId).value.trim(); if (!id) throw new Error(inputId + '를 입력하세요.');
      const body = MR.parseJsonTextarea();
      const path = isPersonal ? '/registrations/' : '/organizations/';
      const data = await MR.request(MR.eventRoot() + path + encodeURIComponent(id) + '/payments/additional/prepare','POST',body);
      MR.storeTarget('paymentId', data.paymentId);
      MR.storeOrder(data,{scope:kind,[key]:id,purpose:'ADDITIONAL_PAYMENT'});
      payButton(true);
      MR.report('추가결제 주문 준비 완료. Toss 결제 페이지에서 이 주문 그대로 승인하세요.');
      if (isPersonal) MR.write('personalAccess', body); else MR.write('groupAccess', body);
    });
    document.addEventListener('mr-config-changed', refresh); refresh(); payButton(Boolean(MR.order()));
  }

  function initRetry() {
    const scope = $('scope');
    $('paymentId').value = MR.target('paymentId');
    $('registrationId').value = MR.target('registrationId');
    $('organizationId').value = MR.target('organizationId');
    const refresh = () => {
      const personal = scope.value === 'personal';
      $('personalFields').classList.toggle('hidden', !personal);
      $('groupFields').classList.toggle('hidden', personal);
      MR.setJsonTextarea(personal ? currentPersonalAccess() : currentGroupAccess());
      const target = personal ? $('registrationId').value.trim() : $('organizationId').value.trim();
      const paymentId = $('paymentId').value.trim();
      if (!target || !paymentId) return MR.updateEndpoint('대상 ID와 paymentId를 입력하세요.');
      const path = personal ? '/registrations/' + encodeURIComponent(target) : '/organizations/' + encodeURIComponent(target);
      setEndpoint('POST', MR.eventRoot() + path + '/payments/' + encodeURIComponent(paymentId) + '/retry');
    };
    scope.onchange = refresh;
    ['paymentId','registrationId','organizationId'].forEach((id) => $(id).addEventListener('input', refresh));
    $('send').onclick = () => MR.runApi(async () => {
      const personal = scope.value === 'personal';
      const target = personal ? $('registrationId').value.trim() : $('organizationId').value.trim();
      const paymentId = $('paymentId').value.trim();
      if (!target || !paymentId) throw new Error('대상 ID와 paymentId가 필요합니다.');
      const body = MR.parseJsonTextarea();
      const path = personal ? '/registrations/' + encodeURIComponent(target) : '/organizations/' + encodeURIComponent(target);
      const data = await MR.request(MR.eventRoot() + path + '/payments/' + encodeURIComponent(paymentId) + '/retry','POST',body);
      MR.storeTarget('paymentId', data.paymentId);
      MR.storeOrder(data,{scope:personal?'personal':'group',purpose:'RETRY'});
      payButton(true);
      MR.report('주문 재준비 완료. Toss 결제 페이지에서 진행하세요.');
    });
    document.addEventListener('mr-config-changed', refresh); refresh(); payButton(Boolean(MR.order()));
  }

  function initPayment() {
    const order = MR.order();
    let widgets = null, methodWidget = null, agreementWidget = null, mountedOrderId = null, callback = null;
    const cfg = MR.config();
    $('paymentVariant').value = cfg.paymentVariant;
    $('agreementVariant').value = cfg.agreementVariant;
    $('orderView').textContent = order ? MR.pretty(order) : '준비된 주문 없음';
    $('mount').disabled = !order;
    $('pay').disabled = true;

    $('saveVariants').onclick = () => {
      MR.saveConfig({...MR.config(), paymentVariant:$('paymentVariant').value, agreementVariant:$('agreementVariant').value});
      MR.report('Toss UI variant 설정을 저장했습니다. 클라이언트 키는 저장하지 않습니다.');
    };

    async function destroy() {
      try { if (methodWidget) await methodWidget.destroy(); } catch {}
      try { if (agreementWidget) await agreementWidget.destroy(); } catch {}
      widgets = methodWidget = agreementWidget = null; mountedOrderId = null;
      $('payment-methods').replaceChildren(); $('agreement').replaceChildren(); $('pay').disabled = true;
    }

    $('mount').onclick = () => MR.runApi(async () => {
      const current = MR.order(); if (!current) throw new Error('준비된 서버 주문이 없습니다.');
      const key = $('clientKey').value.trim();
      if (!key.startsWith('test_gck_')) throw new Error('Toss 테스트 결제위젯 클라이언트 키(test_gck_...)를 입력하세요.');
      if (typeof TossPayments !== 'function') throw new Error('Toss SDK를 불러오지 못했습니다. 네트워크/CSP를 확인하세요.');
      await destroy();
      widgets = TossPayments(key).widgets({customerKey:TossPayments.ANONYMOUS});
      await widgets.setAmount({currency:'KRW', value:Number(current.amount)});
      methodWidget = await widgets.renderPaymentMethods({selector:'#payment-methods',variantKey:$('paymentVariant').value.trim() || 'DEFAULT'});
      agreementWidget = await widgets.renderAgreement({selector:'#agreement',variantKey:$('agreementVariant').value.trim() || 'AGREEMENT'});
      mountedOrderId = current.orderId; $('pay').disabled = false;
      MR.report('결제수단과 약관 UI를 표시했습니다.');
    });

    $('pay').onclick = () => MR.runApi(async () => {
      const current = MR.order();
      if (!widgets || !current || mountedOrderId !== current.orderId) throw new Error('결제수단을 먼저 표시하세요.');
      if (!/^https?:$/.test(location.protocol)) throw new Error('Toss 결제 복귀를 위해 HTTP(S) 주소에서 이 파일을 열어야 합니다.');
      const success = new URL(location.href); const fail = new URL(location.href);
      success.search = ''; fail.search = '';
      success.searchParams.set('paymentResult','success'); fail.searchParams.set('paymentResult','fail');
      MR.write('pendingPayment',{...current, confirmUrl:MR.api('/v1/public/payments/confirm')});
      await widgets.requestPayment({orderId:current.orderId,orderName:current.orderName,successUrl:success.href,failUrl:fail.href});
    });

    function readCallback() {
      const q = new URLSearchParams(location.search);
      if (!q.has('paymentResult')) return;
      const pending = MR.read('pendingPayment');
      if (q.get('paymentResult') === 'fail') {
        $('callback').textContent = 'Toss 인증 실패/취소: ' + (q.get('code') || '') + ' ' + (q.get('message') || '') + '\n실패 URL만으로 서버 Payment 상태를 FAILED라고 단정하지 마세요.';
        return;
      }
      const paymentKey = q.get('paymentKey');
      const orderId = q.get('orderId');
      const amount = Number(q.get('amount'));
      if (!pending || !paymentKey || orderId !== pending.orderId || amount !== Number(pending.amount)) {
        $('callback').textContent = '저장된 서버 주문과 Toss 복귀 정보가 일치하지 않습니다. 승인하지 마세요.';
        return;
      }
      callback = {paymentKey,orderId,amount};
      $('callback').textContent = 'Toss 인증 복귀 확인\norderId=' + orderId + '\namount=' + amount.toLocaleString() + '원\n아래 백엔드 승인 버튼을 눌러야 결제가 완료됩니다.';
      $('confirm').disabled = false;
    }

    $('confirm').onclick = () => MR.runApi(async () => {
      if (!callback) throw new Error('유효한 Toss 성공 복귀 정보가 없습니다.');
      $('confirm').disabled = true;
      const data = await MR.request('/v1/public/payments/confirm','POST',callback);
      MR.write('lastPaymentConfirm', data);
      MR.storeTarget('paymentId', data.paymentId);
      if (data.registrationId) MR.storeTarget('registrationId',data.registrationId);
      if (data.organizationId) MR.storeTarget('organizationId',data.organizationId);
      $('confirmResult').textContent = MR.pretty(MR.mask(data));
      MR.report('백엔드 승인 응답을 받았습니다. processStatus / tossStatus / registrationStatus를 확인하세요.');
    });

    $('clearOrder').onclick = () => { if (confirm('현재 테스트 도구에 저장된 주문/복귀값만 지울까요? 서버 Payment는 삭제되지 않습니다.')) { MR.clearOrder(); location.reload(); } };
    setEndpoint('POST','/v1/public/payments/confirm');
    readCallback();
  }

  const INIT = {
    index:initIndex,
    'personal-create':initPersonalCreate,
    'group-create':initGroupCreate,
    'personal-lookup':initPersonalLookup,
    'group-lookup':initGroupLookup,
    'personal-modify':initPersonalModify,
    'group-modify':initGroupModify,
    'personal-cancel':() => initCancel('personal'),
    'group-cancel':() => initCancel('group'),
    'personal-additional-payment':() => initAdditional('personal'),
    'group-additional-payment':() => initAdditional('group'),
    'payment-retry':initRetry,
    payment:initPayment
  };

  INIT[page]?.();
})();
