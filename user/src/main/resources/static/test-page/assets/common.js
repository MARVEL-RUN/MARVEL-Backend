'use strict';

const MR = (() => {
  const NS = 'marvelrun-user-test-v2:';
  const $ = (id) => document.getElementById(id);
  const pretty = (value) => JSON.stringify(value, null, 2);
  const copy = (value) => JSON.parse(JSON.stringify(value));

  const NAV = [
    ['index.html','대시보드'],
    ['personal-create.html','개인 신청'],
    ['group-create.html','단체 신청'],
    ['personal-lookup.html','개인 조회'],
    ['group-lookup.html','단체 조회'],
    ['personal-modify.html','개인 수정'],
    ['group-modify.html','단체 수정'],
    ['personal-cancel.html','개인 취소/환불'],
    ['group-cancel.html','단체 취소/환불'],
    ['personal-additional-payment.html','개인 추가결제'],
    ['group-additional-payment.html','단체 추가결제'],
    ['payment-retry.html','결제 재준비'],
    ['payment.html','Toss 결제/승인']
  ];

  function read(key, fallback = null) {
    try {
      const raw = sessionStorage.getItem(NS + key);
      return raw == null ? fallback : JSON.parse(raw);
    } catch {
      return fallback;
    }
  }

  function write(key, value) {
    sessionStorage.setItem(NS + key, JSON.stringify(value));
  }

  function remove(key) {
    sessionStorage.removeItem(NS + key);
  }

  function clearAll() {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(NS))
      .forEach((key) => sessionStorage.removeItem(key));
  }

  function defaultBase() {
    return /^https?:$/.test(location.protocol) ? location.origin + '/api' : 'http://localhost:8080/api';
  }

  function config() {
    return read('config', {
      baseUrl: defaultBase(),
      eventId: 'test-000000',
      paymentVariant: 'DEFAULT',
      agreementVariant: 'AGREEMENT'
    });
  }

  function saveConfig(next) {
    write('config', {
      baseUrl: String(next.baseUrl || '').trim().replace(/\/+$/, ''),
      eventId: String(next.eventId || '').trim(),
      paymentVariant: String(next.paymentVariant || 'DEFAULT').trim() || 'DEFAULT',
      agreementVariant: String(next.agreementVariant || 'AGREEMENT').trim() || 'AGREEMENT'
    });
  }

  function api(path) {
    const cfg = config();
    if (!cfg.baseUrl) throw new Error('백엔드 기본 URL을 설정하세요.');
    const url = new URL(cfg.baseUrl.replace(/\/+$/, '') + '/' + String(path).replace(/^\/+/, ''));
    if (!/^https?:$/.test(url.protocol)) throw new Error('HTTP(S) 기본 URL이 필요합니다.');
    return url.href;
  }

  function eventRoot() {
    const eventId = config().eventId;
    if (!eventId) throw new Error('대회 ID를 설정하세요.');
    return '/v1/public/events/' + encodeURIComponent(eventId);
  }

  function mask(value) {
    if (Array.isArray(value)) return value.map(mask);
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([key, val]) => [
        key,
        /password|paymentKey|clientKey|secret/i.test(key) ? '***' : mask(val)
      ]));
    }
    return value;
  }

  function report(message, isError = false) {
    const el = $('status');
    if (!el) return;
    el.className = isError ? 'error' : 'status';
    el.textContent = message;
  }

  function response(value) {
    const el = $('response');
    if (el) el.textContent = pretty(mask(value));
  }

  function record(title, data) {
    const el = $('log');
    if (!el) return;
    const before = el.textContent === '-' ? '' : el.textContent;
    el.textContent = new Date().toLocaleTimeString() + ' ' + title + '\n' + pretty(mask(data)) + '\n\n' + before;
  }

  async function request(pathOrUrl, method = 'GET', body) {
    const url = /^https?:\/\//.test(pathOrUrl) ? pathOrUrl : api(pathOrUrl);
    record(method + ' ' + url, body || {});
    const res = await fetch(url, {
      method,
      headers: body === undefined ? {} : {'Content-Type':'application/json'},
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : null; }
    catch { data = {raw: text}; }
    response(data);
    record('HTTP ' + res.status, data);
    if (!res.ok) {
      const err = new Error('HTTP ' + res.status + ' — 응답 본문을 확인하세요.');
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  function parseJsonTextarea(id = 'body') {
    const text = $(id)?.value ?? '';
    let value;
    try { value = JSON.parse(text); }
    catch (e) { throw new Error('JSON 형식 오류: ' + e.message); }
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('요청 본문은 JSON 객체여야 합니다.');
    return value;
  }

  function setJsonTextarea(value, id = 'body') {
    const el = $(id);
    if (el) el.value = pretty(value);
  }

  function nextSequence() {
    const current = Number(read('sequence', 1000)) || 1000;
    const next = current >= 9999 ? 1000 : current + 1;
    write('sequence', next);
    return next;
  }

  function identity(seq = nextSequence()) {
    const n = String(seq).padStart(4, '0');
    return {
      seq,
      name: '테스터' + n,
      phNum: '010-9' + n.slice(0,3) + '-' + n,
      email: 'tester' + n + '@example.com',
      groupName: '테스트단체' + n,
      groupLoginId: 'testgroup' + n
    };
  }

  function defaultMember(seq) {
    const it = identity(seq);
    return {
      eventCategoryId: 'tc1',
      selectedSouvenirList: [{souvenirId:'test-souvenir', selectedSize:'FREE'}],
      name: it.name,
      phNum: it.phNum,
      birth: '1990-01-01',
      gender: 'M'
    };
  }

  function personalAccess(seed) {
    return {
      name: seed?.name || '테스터1001',
      birth: seed?.birth || '1990-01-01',
      phNum: seed?.phNum || '010-9100-1001',
      password: '123456'
    };
  }

  function groupAccess(seed) {
    return {
      loginId: seed?.loginId || seed?.groupLoginId || 'testgroup1001',
      password: '123456'
    };
  }

  function storeTarget(kind, id) {
    const targets = read('targets', {});
    targets[kind] = id;
    write('targets', targets);
  }

  function target(kind) {
    return read('targets', {})[kind] || '';
  }

  function normalizeOrder(raw) {
    if (!raw) return null;
    const amount = Number(raw.amount ?? raw.paymentAmount);
    const order = {
      paymentId: raw.paymentId,
      orderId: raw.orderId,
      orderName: raw.orderName,
      amount
    };
    if (!order.paymentId || !order.orderId || !order.orderName || !Number.isFinite(amount) || amount <= 0) {
      throw new Error('결제 주문 응답 필드가 예상 계약과 다릅니다.');
    }
    return order;
  }

  function storeOrder(raw, meta = {}) {
    const order = normalizeOrder(raw);
    write('order', {
      ...order,
      baseUrl: config().baseUrl,
      eventId: config().eventId,
      sourcePage: location.pathname.split('/').pop() || 'index.html',
      createdAt: new Date().toISOString(),
      ...meta
    });
    return order;
  }

  function order() { return read('order'); }

  function clearOrder() {
    remove('order');
    remove('pendingPayment');
  }

  function storeLookup(kind, data) {
    write(kind === 'personal' ? 'personalLookup' : 'groupLookup', data);
  }

  function lookup(kind) {
    return read(kind === 'personal' ? 'personalLookup' : 'groupLookup');
  }

  function updateEndpoint(text) {
    const el = $('endpoint');
    if (el) el.textContent = text;
  }

  function endpoint(method, path) {
    updateEndpoint(method + ' ' + api(path));
  }

  function bindConfig() {
    const cfg = config();
    const base = $('globalBase');
    const eventId = $('globalEvent');
    if (!base || !eventId) return;
    base.value = cfg.baseUrl;
    eventId.value = cfg.eventId;
    const save = () => {
      saveConfig({...config(), baseUrl:base.value, eventId:eventId.value});
      report('연결 설정을 저장했습니다.');
      document.dispatchEvent(new CustomEvent('mr-config-changed'));
    };
    $('globalSave')?.addEventListener('click', save);
    $('globalClear')?.addEventListener('click', () => {
      if (!confirm('이 테스트 도구의 sessionStorage 설정·대상·주문·조회값을 모두 지울까요? 서버 데이터는 삭제되지 않습니다.')) return;
      clearAll();
      location.reload();
    });
  }

  function shell(title, subtitle) {
    const root = $('shell');
    if (!root) return;
    const current = location.pathname.split('/').pop() || 'index.html';
    root.innerHTML = `
      <div class="topbar">
        <div><div class="tag">MARVEL RUN · USER API TEST SUITE</div><h1 class="title">${escapeHtml(title)}</h1><div class="note">${escapeHtml(subtitle || '')}</div></div>
        <span class="pill">최신 user API 기준</span>
      </div>
      <div class="nav">${NAV.map(([href,label]) => `<a href="${href}"${href===current?' style="font-weight:800;border-color:#7fa8ad"':''}>${label}</a>`).join('')}</div>
      <div class="config">
        <div class="config-grid">
          <label>백엔드 기본 URL<input id="globalBase" placeholder="https://host/api"></label>
          <label>대회 ID<input id="globalEvent" placeholder="eventId"></label>
        </div>
        <div class="row-actions"><button type="button" id="globalSave" class="secondary">연결 설정 저장</button><button type="button" id="globalClear" class="danger">테스트 도구 저장값 초기화</button></div>
        <div class="note">기본 URL은 보통 <code>https://호스트/api</code>입니다. Toss 클라이언트 키와 비밀번호는 공통 설정에 저장하지 않습니다.</div>
      </div>`;
    bindConfig();
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
  }

  function setBusy(flag) {
    document.querySelectorAll('button[data-api]').forEach((el) => el.disabled = flag);
  }

  async function runApi(fn) {
    if (runApi.busy) return;
    runApi.busy = true;
    setBusy(true);
    try { await fn(); }
    catch (e) { report(e.message, true); }
    finally { setBusy(false); runApi.busy = false; }
  }

  function validateDate(value, label = '생년월일') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) throw new Error(label + '은 YYYY-MM-DD 형식이어야 합니다.');
  }

  function validateParticipant(row) {
    if (!row.eventCategoryId) throw new Error('eventCategoryId가 필요합니다.');
    if (!Array.isArray(row.selectedSouvenirList) || row.selectedSouvenirList.length === 0) throw new Error('selectedSouvenirList가 필요합니다.');
    validateDate(row.birth);
    if (!['M','F'].includes(row.gender)) throw new Error('gender는 M 또는 F여야 합니다.');
  }

  async function loadOptions() {
    const data = await request(eventRoot() + '/registration-options', 'GET');
    write('options', data);
    return data;
  }

  function options() { return read('options'); }

  function wireStandard() {
    $('format')?.addEventListener('click', () => {
      try { setJsonTextarea(parseJsonTextarea()); report('JSON을 정렬했습니다.'); }
      catch (e) { report(e.message, true); }
    });
    $('loadOptions')?.addEventListener('click', () => runApi(async () => {
      const data = await loadOptions();
      const el = $('catalog');
      if (el) el.textContent = pretty(data);
      report('종목·기념품 선택지를 조회했습니다.');
    }));
  }

  return {
    $, pretty, copy, read, write, remove, clearAll, config, saveConfig, api, eventRoot,
    mask, report, response, record, request, parseJsonTextarea, setJsonTextarea,
    nextSequence, identity, defaultMember, personalAccess, groupAccess,
    storeTarget, target, storeOrder, order, clearOrder, storeLookup, lookup,
    shell, endpoint, updateEndpoint, runApi, validateDate, validateParticipant,
    loadOptions, options, wireStandard, normalizeOrder, escapeHtml
  };
})();
