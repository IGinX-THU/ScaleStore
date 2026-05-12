// ===== 可扩展存储引擎 - 可视化大屏 =====
import * as echarts from 'echarts';

// ==================== 配置 ====================
const API_BASE = '';

// ==================== 数据 ====================
let clusterData = [];

let userData = [];
let policyData = [];

const interfaceData = {
  rest: [],
  java: [],
  python: [],
};

// ==================== 状态 ====================
let policyBackup = [];
let policyEditing = false;
let selectedFiles = [];
let currentInterfaceType = 'rest';
let currentUser = null;
let dashboardBootstrapped = false;
let metadataChart = null;
let topologyChart = null;
let clusterViewMode = 'list';
let dataSourceSummary = { totalDataSize: 0, dataSources: [] };
let clusterHeartbeatTimer = null;
let deployInProgress = false;
let metadataFullscreen = false;
let metadataQueryMode = 'system';
let agentLastNodeSnapshot = '';
let agentEventCursor = 0;
let agentEventPollTimer = null;
let metadataGraphSignature = '';
let metadataGraphNodeIds = new Set();
let metadataCurrentLogicalPath = '';
let metadataActiveSearchKeyword = '';
let metadataAutoRefreshInFlight = false;
let metadataAutoRefreshPending = false;

const DEFAULT_GRAPH_MAX_TRIPLES = 200;
const MIN_GRAPH_MAX_TRIPLES = 20;
const MAX_GRAPH_MAX_TRIPLES = 500;

const AGENT_MAX_MESSAGES = 80;

const PAGE_SIZE = 5;
const paginationState = {
  cluster: { page: 1 },
  user: { page: 1 },
  policy: { page: 1 },
};

// ==================== 工具函数 ====================
function $(id) { return document.getElementById(id); }
function showModal(id) { $(id).classList.remove('hidden'); }
function hideModal(id) { $(id).classList.add('hidden'); }

function pickRandom(items) {
  if (!Array.isArray(items) || items.length === 0) return null;
  return items[Math.floor(Math.random() * items.length)];
}

function formatClockTime(date = new Date()) {
  const pad = n => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function updateTime() {
  const now = new Date();
  const pad = n => String(n).padStart(2, '0');
  const weeks = ['日', '一', '二', '三', '四', '五', '六'];
  const str = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())} 周${weeks[now.getDay()]}`;
  $('current-time').textContent = str;
}

function paginate(data, page, size) {
  const total = data.length;
  const totalPages = Math.max(1, Math.ceil(total / size));
  const p = Math.min(Math.max(1, page), totalPages);
  return {
    items: data.slice((p - 1) * size, p * size),
    page: p,
    totalPages,
    total,
  };
}

function renderPagination(containerId, stateKey, totalPages, total, onPageChange) {
  const el = $(containerId);
  if (!el) return;
  let html = `<span class="pagination-info">共 ${total} 条</span><div class="pagination-btns">`;
  const page = paginationState[stateKey].page;
  html += `<button class="page-btn" ${page <= 1 ? 'disabled' : ''} data-page="${page - 1}">上一页</button>`;
  for (let i = 1; i <= totalPages; i++) {
    html += `<button class="page-btn ${i === page ? 'active' : ''}" data-page="${i}">${i}</button>`;
  }
  html += `<button class="page-btn" ${page >= totalPages ? 'disabled' : ''} data-page="${page + 1}">下一页</button>`;
  html += `</div>`;
  el.innerHTML = html;
  el.querySelectorAll('.page-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      if (this.disabled) return;
      paginationState[stateKey].page = parseInt(this.dataset.page);
      onPageChange();
    });
  });
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let idx = 0;
  while (size >= 1024 && idx < units.length - 1) {
    size /= 1024;
    idx++;
  }
  return `${size >= 100 || idx === 0 ? size.toFixed(0) : size.toFixed(2)} ${units[idx]}`;
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const contentType = response.headers.get('content-type') || '';
  const payload = contentType.includes('application/json') ? await response.json() : null;

  if (!response.ok) {
    const message = payload?.message || `HTTP ${response.status}`;
    throw new Error(message);
  }

  if (!payload || payload.code !== 200) {
    throw new Error(payload?.message || '请求失败');
  }

  return payload.data;
}

function mapUserFromBackend(item) {
  return {
    id: Number(item.id),
    name: item.username || '',
    type: Number(item.type) === 1 ? 'admin' : 'user',
    email: item.email || '',
    phone: item.phone || '',
    password: '',
  };
}

function buildUserPayload(editId = '') {
  const name = $('user-name-input').value.trim();
  const password = $('user-password-input').value;
  const type = $('user-type-input').value;
  const email = $('user-email-input').value.trim();
  const phone = $('user-phone-input').value.trim();

  const payload = {
    username: name,
    type: type === 'admin' ? 1 : 0,
    email,
    phone,
  };

  if (editId) {
    payload.id = Number(editId);
  }
  if (password && password.trim()) {
    payload.password = password;
  }

  return payload;
}

async function loadUsers() {
  const users = await requestJson(`${API_BASE}/config/users`);
  userData = (users || []).map(mapUserFromBackend);
}

async function createUser(payload) {
  const user = await requestJson(`${API_BASE}/config/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  return mapUserFromBackend(user);
}

async function updateUser(id, payload) {
  const user = await requestJson(`${API_BASE}/config/users/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  return mapUserFromBackend(user);
}

async function deleteUser(id) {
  await requestJson(`${API_BASE}/config/users/${id}`, {
    method: 'DELETE',
  });
}

function mapPolicyFromBackend(policy) {
  if (!policy) {
    return [];
  }

  return [
    {
      id: 'extraction-enabled',
      key: 'extractionEnabled',
      name: 'extraction.enabled',
      value: String(!!policy.extractionEnabled),
      desc: policy.extractionEnabledDesc || '元数据抽取总开关',
      inputType: 'select',
      options: ['true', 'false'],
    },
    {
      id: 'scan-interval-ms',
      key: 'extractionScanIntervalMs',
      name: 'extraction.scan-interval-ms',
      value: String(policy.extractionScanIntervalMs ?? 60000),
      desc: policy.extractionScanIntervalMsDesc || '扫描间隔（毫秒）',
      inputType: 'number',
    },
    {
      id: 'metadata-graph-max-triples',
      key: 'metadataGraphMaxTriples',
      name: 'metadata.graph-max-triples',
      value: String(policy.metadataGraphMaxTriples ?? DEFAULT_GRAPH_MAX_TRIPLES),
      desc: policy.metadataGraphMaxTriplesDesc || '知识图谱展示的最大三元组数量（20-500）',
      inputType: 'number',
      min: MIN_GRAPH_MAX_TRIPLES,
      max: MAX_GRAPH_MAX_TRIPLES,
      step: 1,
    },
  ];
}

function parsePolicyInteger(value, fallback, min, max) {
  const parsed = Number(String(value ?? '').trim());
  if (!Number.isFinite(parsed)) {
    return fallback;
  }

  const normalized = Math.trunc(parsed);
  return Math.min(max, Math.max(min, normalized));
}

function getPolicyValue(key, fallback) {
  const row = policyData.find(item => String(item.key) === String(key));
  if (!row) {
    return fallback;
  }
  return row.value;
}

function getGraphMaxTriplesLimit() {
  return parsePolicyInteger(
    getPolicyValue('metadataGraphMaxTriples', DEFAULT_GRAPH_MAX_TRIPLES),
    DEFAULT_GRAPH_MAX_TRIPLES,
    MIN_GRAPH_MAX_TRIPLES,
    MAX_GRAPH_MAX_TRIPLES
  );
}

function buildPolicyPayloadFromRows(rows) {
  const rowByKey = {};
  rows.forEach(row => {
    rowByKey[row.key] = row;
  });

  return {
    extractionEnabled: String(rowByKey.extractionEnabled?.value || 'false').toLowerCase() === 'true',
    extractionScanIntervalMs: parsePolicyInteger(rowByKey.extractionScanIntervalMs?.value, 60000, 1000, Number.MAX_SAFE_INTEGER),
    metadataGraphMaxTriples: parsePolicyInteger(rowByKey.metadataGraphMaxTriples?.value, DEFAULT_GRAPH_MAX_TRIPLES, MIN_GRAPH_MAX_TRIPLES, MAX_GRAPH_MAX_TRIPLES),
  };
}

async function loadPolicies() {
  const policy = await requestJson(`${API_BASE}/config/policies`);
  policyData = mapPolicyFromBackend(policy);
}

async function savePolicies(rows) {
  const payload = buildPolicyPayloadFromRows(rows);
  const policy = await requestJson(`${API_BASE}/config/policies`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  policyData = mapPolicyFromBackend(policy);
}

function mapRestfulApiFromBackend(item) {
  const id = Number(item.id);
  return {
    id: `rest-${id}`,
    rawId: id,
    name: item.name || '',
    url: item.url || '',
    type: item.method || '',
    desc: item.description || '',
    params: item.paramsExample || '-',
    response: item.responseExample || '-',
    code: item.curlExample || '-',
  };
}

function mapGrpcApiFromBackend(item, typePrefix) {
  const id = Number(item.id);
  return {
    id: `${typePrefix}-${id}`,
    rawId: id,
    name: item.name || '',
    url: item.url || '',
    type: item.method || 'UNARY',
    desc: item.description || '',
    params: item.paramsExample || '-',
    response: item.responseExample || '-',
    code: item.curlExample || '-',
  };
}

const HIDDEN_REST_INTERFACE_NAMES = new Set([
  '查询节点部署任务状态',
  '删除节点元数据',
  '查询服务端信息',
  '查询元数据抽取事件',
  '查询数据清单',
]);

const REST_META_ALLOWED_NAMES = new Set([
  '查询RESTful接口列表',
  '查询RESTful接口详情',
]);

function normalizeRestInterfaceItem(item) {
  if (!item) {
    return item;
  }

  if ((item.name || '').trim() === '停止节点(异步)') {
    return {
      ...item,
      name: '移除节点(异步)',
      desc: (item.desc || '').replace('停止指定节点', '移除指定节点'),
    };
  }

  return item;
}

function shouldKeepRestInterfaceItem(item) {
  const name = (item?.name || '').trim();
  if (!name) {
    return false;
  }

  if (HIDDEN_REST_INTERFACE_NAMES.has(name)) {
    return false;
  }

  const url = (item?.url || '').trim();
  if (url.startsWith('/config/interfaces/restful')) {
    return REST_META_ALLOWED_NAMES.has(name);
  }

  return true;
}

async function loadRestfulInterfaces() {
  const items = await requestJson(`${API_BASE}/config/interfaces/restful`);
  interfaceData.rest = (items || [])
    .map(mapRestfulApiFromBackend)
    .map(normalizeRestInterfaceItem)
    .filter(shouldKeepRestInterfaceItem);
}

async function loadJavaGrpcInterfaces() {
  const items = await requestJson(`${API_BASE}/config/interfaces/java-grpc`);
  interfaceData.java = (items || []).map(item => mapGrpcApiFromBackend(item, 'java'));
}

async function loadPythonGrpcInterfaces() {
  const items = await requestJson(`${API_BASE}/config/interfaces/python-grpc`);
  interfaceData.python = (items || []).map(item => mapGrpcApiFromBackend(item, 'python'));
}

function setCurrentUser(user) {
  currentUser = user;
  $('header-user-name').textContent = user?.name || '未登录';
}

function showLoginError(message) {
  const el = $('login-error');
  el.textContent = message;
  el.classList.remove('hidden');
}

function clearLoginError() {
  const el = $('login-error');
  el.textContent = '';
  el.classList.add('hidden');
}

function showLoginOverlay() {
  $('login-overlay').classList.remove('hidden');
}

function hideLoginOverlay() {
  $('login-overlay').classList.add('hidden');
}

function openProfileModal() {
  if (!currentUser) {
    return;
  }

  $('profile-username').value = currentUser.name || '';
  $('profile-type').value = currentUser.type === 'admin' ? '超级管理员' : '普通用户';
  $('profile-email').value = currentUser.email || '';
  $('profile-phone').value = currentUser.phone || '';
  showModal('modal-profile');
}

function closeProfileModal() {
  hideModal('modal-profile');
}

async function performLogin() {
  const username = $('login-username-input').value.trim();
  const password = $('login-password-input').value;

  if (!username || !password) {
    showLoginError('请输入用户名和密码');
    return;
  }

  const submitBtn = $('login-submit-btn');
  submitBtn.disabled = true;
  submitBtn.textContent = '登录中...';
  clearLoginError();

  try {
    const user = await requestJson(`${API_BASE}/config/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });

    setCurrentUser(mapUserFromBackend(user));
    $('login-password-input').value = '';
    await bootstrapDashboard();
    hideLoginOverlay();
  } catch (e) {
    showLoginError(`登录失败：${e.message}`);
    showLoginOverlay();
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = '进入平台';
  }
}

function performLogout() {
  closeProfileModal();
  setCurrentUser(null);
  clearLoginError();
  $('login-password-input').value = '';
  showLoginOverlay();
  $('header-user-menu').classList.remove('open');
  $('login-username-input').focus();
}

function bindAuthEvents() {
  const loginForm = $('login-form');
  if (loginForm) {
    loginForm.addEventListener('submit', e => {
      e.preventDefault();
      performLogin();
    });
  }

  $('header-user-btn').addEventListener('click', e => {
    e.stopPropagation();
    $('header-user-menu').classList.toggle('open');
  });

  document.addEventListener('click', () => {
    $('header-user-menu').classList.remove('open');
  });

  $('header-profile-btn').addEventListener('click', e => {
    e.preventDefault();
    e.stopPropagation();
    $('header-user-menu').classList.remove('open');
    openProfileModal();
  });

  $('header-logout-btn').addEventListener('click', e => {
    e.preventDefault();
    e.stopPropagation();
    performLogout();
  });

  $('profile-modal-close').addEventListener('click', closeProfileModal);
  $('profile-modal-close-x').addEventListener('click', closeProfileModal);
}



function setClusterViewMode(mode) {
  clusterViewMode = mode === 'graph' ? 'graph' : 'list';
  const listBtn = $('cluster-list-mode-btn');
  const graphBtn = $('cluster-graph-mode-btn');
  const listView = $('cluster-list-view');
  const graphView = $('cluster-graph-view');
  if (listBtn) listBtn.classList.toggle('active', clusterViewMode === 'list');
  if (graphBtn) graphBtn.classList.toggle('active', clusterViewMode === 'graph');
  if (listView) listView.classList.toggle('hidden', clusterViewMode !== 'list');
  if (graphView) graphView.classList.toggle('hidden', clusterViewMode !== 'graph');
  if (clusterViewMode === 'graph') {
    requestAnimationFrame(() => initClusterTopology());
  }
}

function renderDataSourceTable() {
  const tbody = $('datasource-table-body');
  if (!tbody) return;
  const items = Array.isArray(dataSourceSummary.dataSources) ? dataSourceSummary.dataSources : [];
  const countEl = $('datasource-count');
  const totalEl = $('datasource-total-size');
  if (countEl) countEl.textContent = String(items.length);
  if (totalEl) totalEl.textContent = formatBytes(dataSourceSummary.totalDataSize || 0);
  if (!items.length) {
    tbody.innerHTML = '<tr><td colspan="4" class="text-center">暂无数据源</td></tr>';
    return;
  }
  tbody.innerHTML = items.map(item => `
    <tr>
      <td>${escapeHtml(item.isDefault ? 'filesystem' : (item.type || '-'))}</td>
      <td>${escapeHtml(item.isDefault ? '系统内置' : `${item.ip || '-'}:${item.port || '-'}`)}</td>
      <td>${escapeHtml(formatBytes(item.dataSize || 0))}</td>
      <td><span class="node-status ${(item.connected === false) ? 'status-offline' : 'status-online'}">${item.connected === false ? '离线' : '在线'}</span></td>
    </tr>
  `).join('');
}

async function loadDataSourceSummary() {
  dataSourceSummary = await requestJson(`${API_BASE}/storage/datasources`);
  renderDataSourceTable();
}

async function refreshDashboardData() {
  await Promise.all([
    loadClusterNodes(),
    loadUsers(),
    loadPolicies(),
    loadRestfulInterfaces(),
    loadJavaGrpcInterfaces(),
    loadPythonGrpcInterfaces(),
    loadDataSourceSummary(),
  ]);

  renderClusterTable();
  renderUserTable();
  renderPolicyTable();
  renderInterfaceTable();
}

function bootstrapAccessRootVisit() {
  const input = $('access-path-input');
  const visitBtn = $('access-visit-btn');
  if (!input || !visitBtn) return;
  input.value = '/';
  visitBtn.click();
}

async function bootstrapDashboard() {
  await refreshDashboardData();
  bootstrapAccessRootVisit();

  if (dashboardBootstrapped) {
    if (clusterViewMode === 'graph') {
      initClusterTopology();
    }
    syncAgentPoolNodeState(true);
    return;
  }

  dashboardBootstrapped = true;
  initAgentPanel();
  setClusterViewMode('list');

  const clusterListModeBtn = $('cluster-list-mode-btn');
  const clusterGraphModeBtn = $('cluster-graph-mode-btn');
  if (clusterListModeBtn) clusterListModeBtn.addEventListener('click', () => setClusterViewMode('list'));
  if (clusterGraphModeBtn) clusterGraphModeBtn.addEventListener('click', () => setClusterViewMode('graph'));

  requestAnimationFrame(() => {
    if (clusterViewMode === 'graph') {
      initClusterTopology();
    }
    initMetadataGraph().catch(e => {
      console.error('Init metadata graph failed:', e);
    });
  });

  if (clusterHeartbeatTimer) {
    clearInterval(clusterHeartbeatTimer);
  }
  clusterHeartbeatTimer = setInterval(() => {
    if (!deployInProgress && currentUser) refreshClusterView(true);
  }, 15000);
}

// ==================== 智能体群消息流 ====================
function getAgentNodeNames() {
  const onlineNodes = clusterData.filter(n => n.status === 'ONLINE');
  const source = onlineNodes.length > 0 ? onlineNodes : clusterData;
  if (source.length === 0) {
    return ['IGinX1', 'IGinX2', 'IGinX3'];
  }
  return source.map((n, i) => {
    const name = String(n.name || '').trim();
    return name || `IGinX${i + 1}`;
  });
}

function pickAgentName() {
  return pickRandom(getAgentNodeNames()) || 'IGinX1';
}

function pushAgentMessage({ level = 'info', status = '', text = '', agentName = '', smooth = true, timestamp = null } = {}) {
  const list = $('agent-stream-list');
  if (!list || !text) return;

  const validLevels = ['running', 'success', 'warn', 'info'];
  const normalizedLevel = validLevels.includes(level) ? level : 'info';
  const defaultStatus = normalizedLevel === 'running'
    ? '进行中'
    : (normalizedLevel === 'success' ? '完成' : (normalizedLevel === 'warn' ? '失败' : '通知'));
  const statusText = status || defaultStatus;
  const lineText = agentName ? `智能体【${agentName}】${text}` : text;
  const clock = (timestamp && Number.isFinite(Number(timestamp)))
    ? formatClockTime(new Date(Number(timestamp)))
    : formatClockTime();

  const li = document.createElement('li');
  li.className = `agent-msg-item level-${normalizedLevel}`;
  li.innerHTML = `
    <span class="agent-msg-status">${escapeHtml(statusText)}</span>
    <span class="agent-msg-text">${escapeHtml(lineText)}</span>
    <span class="agent-msg-time">${escapeHtml(clock)}</span>
  `;

  list.appendChild(li);
  while (list.children.length > AGENT_MAX_MESSAGES) {
    list.removeChild(list.firstChild);
  }

  list.scrollTo({
    top: list.scrollHeight,
    behavior: smooth ? 'smooth' : 'auto',
  });
}

function sanitizeAgentEventText(text) {
  return String(text || '')
    .replace(/\s*[a-z]+(?:\s+[a-z]+)*\s+extraction by udf;\s*neo4j persisted\.?/ig, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function syncAgentPoolNodeState(force = false) {
  const snapshot = clusterData
    .map(n => `${n.name || 'IGinX'}:${n.status || 'UNKNOWN'}`)
    .join('|');

  if (!force && snapshot === agentLastNodeSnapshot) {
    return;
  }
  agentLastNodeSnapshot = snapshot;

  if (clusterData.length === 0) {
    pushAgentMessage({
      level: 'warn',
      status: '池状态',
      text: '智能体池尚未发现可用IGinX节点。',
      smooth: !force,
    });
    return;
  }

  const onlineCount = clusterData.filter(n => n.status === 'ONLINE').length;
  pushAgentMessage({
    level: onlineCount === 0 ? 'warn' : 'info',
    status: '池状态',
    text: `智能体池在线节点 ${onlineCount}/${clusterData.length}。`,
    smooth: !force,
  });
}

async function pollAgentEvents() {
  const params = new URLSearchParams();
  params.set('since', String(agentEventCursor));
  params.set('limit', '60');

  const response = await fetch(`${API_BASE}/metadata/extraction/events?${params.toString()}`);
  const result = await response.json();
  if (!response.ok || result.code !== 200 || !result.data) {
    throw new Error(result?.message || '获取智能体事件失败');
  }

  const payload = result.data;
  const events = Array.isArray(payload.events) ? payload.events.slice() : [];
  events.sort((a, b) => Number(a.seq || 0) - Number(b.seq || 0));
  const refreshHints = [];

  for (const evt of events) {
    const rawLevel = String(evt.level || '').toLowerCase();
    const level = ['running', 'success', 'warn', 'info'].includes(rawLevel) ? rawLevel : 'info';
    pushAgentMessage({
      level,
      status: evt.status || '',
      text: sanitizeAgentEventText(evt.text || ''),
      agentName: evt.agentName || '',
      timestamp: evt.timestamp,
    });

    if (isMetadataExtractionDoneEvent(evt)) {
      refreshHints.push({
        logicalPath: extractPathFromAgentEventText(evt.text || ''),
      });
    }
  }

  const latestSeq = Number(payload.latestSeq || 0);
  if (Number.isFinite(latestSeq) && latestSeq > agentEventCursor) {
    agentEventCursor = latestSeq;
  }

  if (refreshHints.length > 0) {
    queueMetadataAutoRefresh(refreshHints);
  }
}

function startAgentEventPolling() {
  if (agentEventPollTimer) {
    clearInterval(agentEventPollTimer);
  }
  pollAgentEvents().catch(e => {
    console.error('Initial agent event polling failed:', e);
  });
  agentEventPollTimer = setInterval(() => {
    pollAgentEvents().catch(e => {
      console.error('Agent event polling failed:', e);
    });
  }, 4000);
}

function clearAgentStream() {
  const list = $('agent-stream-list');
  if (list) list.innerHTML = '';
}

function initAgentPanel() {
  if (!$('agent-stream-list')) return;

  const clearBtn = $('agent-clear-btn');
  if (clearBtn && !clearBtn.dataset.bound) {
    clearBtn.dataset.bound = 'true';
    clearBtn.addEventListener('click', () => {
      clearAgentStream();
      pushAgentMessage({
        level: 'info',
        status: '通知',
        text: '智能体消息流已清空，正在继续监听新任务。',
        smooth: false,
      });
    });
  }

  clearAgentStream();
  agentEventCursor = 0;
  pushAgentMessage({
    level: 'info',
    status: '初始化',
    text: 'IGinX智能体群已接入，开始监听后端真实抽取任务。',
    smooth: false,
  });
  syncAgentPoolNodeState(true);
  startAgentEventPolling();
}

// ==================== 集群管理 ====================
function renderClusterTable() {
  const { items, page, totalPages, total } = paginate(clusterData, paginationState.cluster.page, PAGE_SIZE);
  paginationState.cluster.page = page;
  const onlineCount = clusterData.filter(n => n.status === 'ONLINE').length;
  $('online-nodes').textContent = onlineCount;
  const tbody = $('cluster-table-body');
  tbody.innerHTML = items.map(n => {
    const statusClass = n.status === 'ONLINE' ? 'status-online' : 'status-offline';
    const statusLabel = n.status === 'ONLINE' ? '在线' : '离线';
    const typeLabel = n.nodeType === 'iginx' ? 'IGinX' : (n.nodeType || 'IGinX');
    return `
    <tr>
      <td title="${n.name}">${n.name}</td>
      <td>${n.ip}</td>
      <td>${n.port}</td>
      <td><span class="node-status ${statusClass}">${statusLabel}</span></td>
      <td>
        <button class="btn-text-action view cluster-view-detail-btn" data-id="${n.id}">查看</button>
        <button class="btn-text-action edit cluster-set-btn" data-id="${n.id}">设置</button>
        <button class="btn-text-action delete cluster-delete-btn" data-id="${n.id}">删除</button>
      </td>
    </tr>`;
  }).join('');
  renderPagination('cluster-pagination', 'cluster', totalPages, total, renderClusterTable);
  bindClusterRowEvents();
}

function bindClusterRowEvents() {
  document.querySelectorAll('.cluster-view-detail-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const node = clusterData.find(n => n.id === +this.dataset.id);
      if (!node) return;
      openClusterModal('查看节点', node, 'view');
    });
  });
  document.querySelectorAll('.cluster-set-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const node = clusterData.find(n => n.id === +this.dataset.id);
      if (!node) return;
      openClusterModal('编辑节点', node, 'edit');
    });
  });
  document.querySelectorAll('.cluster-delete-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const id = +this.dataset.id;
      const node = clusterData.find(n => n.id === id);
      if (node) openDeleteModal(node);
    });
  });
}

function openClusterModal(title, data, mode) {
  // mode: 'add', 'edit', 'view'
  $('cluster-modal-title').textContent = title;
  $('cluster-name-input').value = data ? data.name : '';
  $('cluster-ip-input').value = data ? data.ip : '';
  $('cluster-port-input').value = data ? data.port : '';
  $('cluster-desc-input').value = data ? (data.description || data.desc || '') : '';

  const isView = mode === 'view';
  const isEdit = mode === 'edit';
  const isAdd = mode === 'add';

  // Name and description: editable in add and edit modes
  $('cluster-name-input').disabled = isView;
  $('cluster-desc-input').disabled = isView;

  // IP and port: only editable in add mode
  $('cluster-ip-input').disabled = !isAdd;
  $('cluster-port-input').disabled = !isAdd;

  // Deploy/SSH fields: only visible in add mode
  const deployFields = $('cluster-deploy-fields');
  if (deployFields) {
    deployFields.style.display = isAdd ? '' : 'none';
  }

  if (isAdd) {
    $('cluster-ssh-user-input').value = '';
    $('cluster-ssh-password-input').value = '';
    $('cluster-ssh-port-input').value = '22';
    $('cluster-deploy-dir-input').value = '~';
    $('cluster-python-cmd-input').value = 'python3';
    $('cluster-port-input').value = '6888';

    $('cluster-zk-input').value = '';
    // Auto-detect server IP for ZK default
    fetch(API_BASE + '/config/server-info')
      .then(function(r) { return r.json(); })
      .then(function(d) {
        if (d && d.data && d.data.ip) {
          $('cluster-zk-input').value = d.data.ip + ':2181';
        }
      })
      .catch(function() {});
  }

  // Save button
  $('cluster-modal-save').classList.toggle('hidden', isView);
  $('cluster-modal-save').dataset.editId = (isEdit && data) ? data.id : '';
  $('cluster-modal-save').dataset.mode = mode;
  $('cluster-modal-save').textContent = isAdd ? '确认' : '保存';

  // Progress section
  $('cluster-deploy-progress-wrap').classList.add('hidden');
  $('cluster-deploy-current-step').textContent = '等待开始...';
  $('cluster-deploy-log').textContent = '暂无日志';

  showModal('modal-cluster');
}

$('cluster-add-btn').addEventListener('click', () => openClusterModal('添加节点', null, 'add'));

$('cluster-modal-cancel').addEventListener('click', () => hideModal('modal-cluster'));
$('cluster-modal-close-x').addEventListener('click', () => hideModal('modal-cluster'));

$('cluster-modal-save').addEventListener('click', async () => {
  const mode = $('cluster-modal-save').dataset.mode;
  const saveBtn = $('cluster-modal-save');

  if (mode === 'edit') {
    // Edit mode: only update name and description
    const name = $('cluster-name-input').value.trim();
    const desc = $('cluster-desc-input').value.trim();
    if (!name) { alert('节点名不能为空'); return; }

    const editId = $('cluster-modal-save').dataset.editId;
    saveBtn.disabled = true;
    saveBtn.textContent = '保存中...';
    try {
      await updateClusterNode(editId, { name, description: desc });
      await refreshClusterView(true);
      hideModal('modal-cluster');
    } catch (e) {
      alert('更新失败: ' + e.message);
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = '保存';
    }
  } else {
    // Add mode: deploy new node
    const name = $('cluster-name-input').value.trim();
    const ip = $('cluster-ip-input').value.trim();
    const port = $('cluster-port-input').value.trim();
    const desc = $('cluster-desc-input').value.trim();
    const sshUsername = $('cluster-ssh-user-input').value.trim();
    const sshPort = $('cluster-ssh-port-input').value.trim();
    const sshPassword = $('cluster-ssh-password-input').value;
    const deployDirectory = $('cluster-deploy-dir-input').value.trim();
    const pythonCmd = $('cluster-python-cmd-input').value.trim();
    const zookeeperConnectionString = $('cluster-zk-input').value.trim();

    if (!name || !ip || !sshUsername || !sshPassword || !deployDirectory || !zookeeperConnectionString) {
      alert('请填写完整信息（SSH密码为必填）');
      return;
    }

    const normalizedSshPort = sshPort || '22';
    const sshPortNum = Number(normalizedSshPort);
    if (!Number.isInteger(sshPortNum) || sshPortNum < 1 || sshPortNum > 65535) {
      alert('SSH端口必须是 1-65535 的整数');
      return;
    }

    saveBtn.disabled = true;
    saveBtn.textContent = '部署中...';
    deployInProgress = true;
    try {
      $('cluster-deploy-progress-wrap').classList.remove('hidden');
      renderDeployTaskProgress({
        currentStep: '已提交部署任务，等待执行...',
        logs: ['[INIT] 已创建部署任务'],
      });

      const task = await createClusterNodeTask({
        name,
        ip,
        port: port || '6888',
        description: desc,
        sshUsername,
        sshPort: String(sshPortNum),
        sshPassword,
        deployDirectory,
        pythonCmd: pythonCmd || 'python3',
        zookeeperConnectionString,
      });

      await waitForDeployTask(task.taskId);
      await refreshClusterView(true);
      hideModal('modal-cluster');
    } catch (e) {
      alert('操作失败: ' + e.message);
    } finally {
      deployInProgress = false;
      saveBtn.disabled = false;
      saveBtn.textContent = '确认';
    }
  }
});

async function loadClusterNodes() {
  const response = await fetch(`${API_BASE}/config/nodes`);
  const result = await response.json();
  if (result.code !== 200 || !Array.isArray(result.data)) {
    throw new Error(result.message || '获取节点列表失败');
  }
  clusterData = result.data.map(item => ({
    id: item.id,
    name: item.name,
    ip: item.ip,
    port: item.port,
    desc: item.description || '',
    description: item.description || '',
    deployDirectory: item.deployDirectory || '~',
    status: item.status || 'ONLINE',
    nodeType: item.nodeType || 'iginx',
  }));
}

async function createClusterNodeTask(payload) {
  const response = await fetch(`${API_BASE}/config/nodes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const result = await response.json();
  if (!response.ok || result.code >= 400) {
    throw new Error(result.message || '新增节点失败');
  }
  if (!result.data || !result.data.taskId) {
    throw new Error('部署任务创建失败：缺少任务ID');
  }
  return result.data;
}

async function queryDeployTask(taskId) {
  const response = await fetch(`${API_BASE}/config/nodes/deploy/${encodeURIComponent(taskId)}`);
  const result = await response.json();
  if (!response.ok || result.code >= 400 || !result.data) {
    throw new Error(result.message || '查询部署任务失败');
  }
  return result.data;
}

async function waitForDeployTask(taskId) {
  while (true) {
    const task = await queryDeployTask(taskId);
    renderDeployTaskProgress(task);
    if (task.completed) {
      if (task.status !== 'SUCCESS') {
        throw new Error(task.errorMessage || '部署失败');
      }
      return task;
    }
    await sleep(1200);
  }
}

function renderDeployTaskProgress(task) {
  // Parse steps from logs: lines containing [INFO] or [STEP] are steps
  const allLogs = Array.isArray(task.logs) ? task.logs : [];
  const steps = allLogs.filter(l => l.includes('[INFO]') || l.includes('[STEP]') || l.includes('[DONE]') || l.includes('[WAIT]'));
  const knownSteps = [
    { key: '测试', label: '测试SSH连接' },
    { key: '拷贝', label: '拷贝安装包' },
    { key: '解压', label: '远程解压' },
    { key: 'ZooKeeper', label: '修改配置' },
    { key: '执行权限', label: '赋予权限' },
    { key: '启动 IGinX', label: '启动IGinX' },
    { key: '等待 IGinX', label: '等待启动' },
    { key: '校验', label: '校验集群' },
  ];

  // Build step indicator
  let stepHtml = '<div class="deploy-steps">';
  const currentStep = task.currentStep || '执行中...';
  // Find the last completed step index
  let lastDoneIdx = -1;
  knownSteps.forEach((s, i) => {
    if (steps.some(l => l.includes(s.key))) {
      lastDoneIdx = i;
    }
  });
  knownSteps.forEach((s, i) => {
    let cls = 'step-pending';
    if (task.completed && task.status === 'SUCCESS') {
      cls = 'step-done';
    } else if (i < lastDoneIdx) {
      cls = 'step-done';
    } else if (i === lastDoneIdx) {
      // If next step has started, this one is done; otherwise it's active
      const nextStarted = (i + 1 < knownSteps.length) && steps.some(l => l.includes(knownSteps[i + 1].key));
      cls = nextStarted ? 'step-done' : 'step-active';
    }
    stepHtml += '<div class="deploy-step ' + cls + '"><span class="step-num">' + (i + 1) + '</span><span class="step-label">' + s.label + '</span></div>';
  });
  stepHtml += '</div>';

  $('cluster-deploy-current-step').innerHTML = stepHtml + '<div class="deploy-current-info">' + escapeHtml(currentStep) + '</div>';
  const logEl = $('cluster-deploy-log');
  logEl.textContent = allLogs.length > 0 ? allLogs.join('\n') : '暂无日志';
  logEl.scrollTop = logEl.scrollHeight;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function refreshClusterView(silent) {
  try {
    await loadClusterNodes();
    syncAgentPoolNodeState();
    renderClusterTable();
    initClusterTopology();
    await loadDataSourceSummary();
  } catch (e) {
    if (!silent) {
      throw e;
    }
    console.error('Cluster heartbeat refresh failed:', e);
  }
}

async function updateClusterNode(id, payload) {
  const response = await fetch(`${API_BASE}/config/nodes/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: payload.name, description: payload.description || '' }),
  });
  const result = await response.json();
  if (!response.ok || result.code >= 400) {
    throw new Error(result.message || '更新节点失败');
  }
  return result.data;
}

async function deleteClusterNodeById(id) {
  const response = await fetch(`${API_BASE}/config/nodes/${id}`, {
    method: 'DELETE',
  });
  const result = await response.json();
  if (!response.ok || result.code >= 400) {
    throw new Error(result.message || '删除节点失败');
  }
}

// ==================== 节点删除 (SSH停止) ====================
function openDeleteModal(node) {
  $('cluster-delete-info').innerHTML = '确定要停止并删除节点 <strong>' + escapeHtml(node.name) + '</strong> (' + escapeHtml(node.ip) + ':' + escapeHtml(node.port) + ') 吗？此操作不可撤销。';
  $('cluster-delete-ssh-user').value = '';
  $('cluster-delete-ssh-password').value = '';
  $('cluster-delete-ssh-port').value = '22';
  $('cluster-delete-deploy-dir').value = node.deployDirectory || '~';
  $('cluster-delete-progress-wrap').classList.add('hidden');
  $('cluster-delete-current-step').textContent = '等待开始...';
  $('cluster-delete-log').textContent = '暂无日志';
  $('cluster-delete-modal-confirm').disabled = false;
  $('cluster-delete-modal-confirm').textContent = '确认删除';
  $('cluster-delete-modal-confirm').dataset.nodeId = node.id;
  showModal('modal-cluster-delete');
}

$('cluster-delete-modal-cancel').addEventListener('click', () => hideModal('modal-cluster-delete'));
$('cluster-delete-modal-close-x').addEventListener('click', () => hideModal('modal-cluster-delete'));

$('cluster-delete-modal-confirm').addEventListener('click', async () => {
  const nodeId = $('cluster-delete-modal-confirm').dataset.nodeId;
  const sshUsername = $('cluster-delete-ssh-user').value.trim();
  const sshPort = $('cluster-delete-ssh-port').value.trim();
  const sshPassword = $('cluster-delete-ssh-password').value;
  const deployDirectory = $('cluster-delete-deploy-dir').value.trim();

  if (!sshUsername || !sshPassword) {
    alert('请填写SSH凭据');
    return;
  }

  const normalizedSshPort = sshPort || '22';
  const sshPortNum = Number(normalizedSshPort);
  if (!Number.isInteger(sshPortNum) || sshPortNum < 1 || sshPortNum > 65535) {
    alert('SSH端口必须是 1-65535 的整数');
    return;
  }

  const btn = $('cluster-delete-modal-confirm');
  btn.disabled = true;
  btn.textContent = '停止中...';
  deployInProgress = true;
  $('cluster-delete-progress-wrap').classList.remove('hidden');

  try {
    const task = await stopClusterNode(nodeId, { sshUsername, sshPort: String(sshPortNum), sshPassword, deployDirectory });
    await waitForStopTask(task.taskId);
    await refreshClusterView(true);
    hideModal('modal-cluster-delete');
  } catch (e) {
    alert('停止失败: ' + e.message);
  } finally {
    deployInProgress = false;
    btn.disabled = false;
    btn.textContent = '确认删除';
  }
});

async function stopClusterNode(id, payload) {
  const response = await fetch(`${API_BASE}/config/nodes/${id}/stop`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const result = await response.json();
  if (!response.ok || result.code >= 400) {
    throw new Error(result.message || '停止节点失败');
  }
  if (!result.data || !result.data.taskId) {
    throw new Error('停止任务创建失败');
  }
  return result.data;
}

async function waitForStopTask(taskId) {
  while (true) {
    const task = await queryDeployTask(taskId);
    renderStopTaskProgress(task);
    if (task.completed) {
      if (task.status !== 'SUCCESS') {
        throw new Error(task.errorMessage || '停止失败');
      }
      return task;
    }
    await sleep(1200);
  }
}

function renderStopTaskProgress(task) {
  const allLogs = Array.isArray(task.logs) ? task.logs : [];
  const steps = allLogs.filter(l => l.includes('[INFO]') || l.includes('[STEP]') || l.includes('[DONE]') || l.includes('[WAIT]'));
  const knownSteps = [
    { key: '测试', label: '测试SSH连接' },
    { key: '停止脚本', label: '检查脚本' },
    { key: '停止 IGinX', label: '停止IGinX' },
    { key: '等待', label: '等待退出' },
    { key: '校验', label: '校验集群' },
  ];

  let stepHtml = '<div class="deploy-steps">';
  const currentStep = task.currentStep || '执行中...';
  let lastDoneIdx = -1;
  knownSteps.forEach((s, i) => {
    if (steps.some(l => l.includes(s.key))) lastDoneIdx = i;
  });
  knownSteps.forEach((s, i) => {
    let cls = 'step-pending';
    if (task.completed && task.status === 'SUCCESS') {
      cls = 'step-done';
    } else if (i < lastDoneIdx) {
      cls = 'step-done';
    } else if (i === lastDoneIdx) {
      const nextStarted = (i + 1 < knownSteps.length) && steps.some(l => l.includes(knownSteps[i + 1].key));
      cls = nextStarted ? 'step-done' : 'step-active';
    }
    stepHtml += '<div class="deploy-step ' + cls + '"><span class="step-num">' + (i + 1) + '</span><span class="step-label">' + s.label + '</span></div>';
  });
  stepHtml += '</div>';

  $('cluster-delete-current-step').innerHTML = stepHtml + '<div class="deploy-current-info">' + escapeHtml(currentStep) + '</div>';
  const logEl = $('cluster-delete-log');
  logEl.textContent = allLogs.length > 0 ? allLogs.join('\n') : '暂无日志';
  logEl.scrollTop = logEl.scrollHeight;
}

// ==================== 拓扑图 ====================
function initClusterTopology() {
  if (clusterViewMode !== 'graph') return;
  const container = $('cluster-topology');
  if (!container) return;
  if (!topologyChart) {
    topologyChart = echarts.init(container);
  }
  if (clusterData.length === 0) {
    topologyChart.clear();
    return;
  }
  // First node is considered the master (the one running the web server)
  const masterNode = clusterData.length > 0 ? clusterData[0] : null;
  const nodes = clusterData.map((n, i) => {
    const isMaster = (i === 0);
    const isOnline = n.status === 'ONLINE';
    return {
      name: n.name + '\n' + n.ip + ':' + n.port,
      symbolSize: isMaster ? 50 : 35,
      category: isMaster ? 0 : 1,
      itemStyle: {
        color: !isOnline ? '#666' : (isMaster ? '#00e68a' : '#00cfff'),
        shadowBlur: 8,
        shadowColor: !isOnline ? 'rgba(100,100,100,0.3)' : (isMaster ? 'rgba(0,230,138,0.4)' : 'rgba(0,207,255,0.3)'),
      },
    };
  });
  const links = [];
  if (masterNode && clusterData.length > 1) {
    const masterLabel = masterNode.name + '\n' + masterNode.ip + ':' + masterNode.port;
    for (let i = 1; i < clusterData.length; i++) {
      const n = clusterData[i];
      links.push({ source: masterLabel, target: n.name + '\n' + n.ip + ':' + n.port });
    }
  }
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(8,28,54,0.95)',
      borderColor: 'rgba(0,207,255,0.3)',
      textStyle: { color: '#cce4f5', fontSize: 11 },
    },
    legend: {
      data: ['主节点', 'IGinX节点'],
      top: 4, right: 4,
      textStyle: { color: '#4d7a99', fontSize: 10 },
      itemWidth: 10, itemHeight: 10,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      links,
      categories: [{ name: '主节点', itemStyle: { color: '#00e68a' } }, { name: 'IGinX节点', itemStyle: { color: '#00cfff' } }],
      roam: true,
      draggable: true,
      label: { show: true, position: 'bottom', color: '#8cb8d0', fontSize: 9 },
      force: { repulsion: 120, gravity: 0.15, edgeLength: 80 },
      lineStyle: { color: 'rgba(0,207,255,0.25)', width: 1.5, curveness: 0.15 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
    }],
  };
  topologyChart.setOption(option, true);
}

// ==================== 用户管理 ====================
function renderUserTable() {
  const { items, page, totalPages, total } = paginate(userData, paginationState.user.page, PAGE_SIZE);
  paginationState.user.page = page;
  const tbody = $('user-table-body');
  if (!items.length) {
    tbody.innerHTML = '<tr><td colspan="4" class="text-center">暂无用户数据</td></tr>';
    renderPagination('user-pagination', 'user', 1, 0, renderUserTable);
    return;
  }

  tbody.innerHTML = items.map(u => `
    <tr>
      <td>${u.name}</td>
      <td><span class="badge ${u.type === 'admin' ? 'badge-admin' : 'badge-user'}">${u.type === 'admin' ? '超级管理员' : '普通用户'}</span></td>
      <td>${u.email || '-'}</td>
      <td>
        <button class="btn-text-action view user-view-detail-btn" data-id="${u.id}">查看</button>
        <button class="btn-text-action edit user-set-btn" data-id="${u.id}">编辑</button>
        <button class="btn-text-action delete user-delete-btn" data-id="${u.id}">删除</button>
      </td>
    </tr>
  `).join('');
  renderPagination('user-pagination', 'user', totalPages, total, renderUserTable);
  bindUserRowEvents();
}

function bindUserRowEvents() {
  document.querySelectorAll('.user-view-detail-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const user = userData.find(u => u.id === +this.dataset.id);
      if (user) openUserModal('用户详情', user, true);
    });
  });
  document.querySelectorAll('.user-set-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const user = userData.find(u => u.id === +this.dataset.id);
      if (user) openUserModal('编辑用户', user, false);
    });
  });
  document.querySelectorAll('.user-delete-btn').forEach(btn => {
    btn.addEventListener('click', async function () {
      const id = +this.dataset.id;
      if (confirm('确定要删除该用户吗？')) {
        try {
          await deleteUser(id);
          await loadUsers();
          renderUserTable();
        } catch (e) {
          alert('删除失败: ' + e.message);
        }
      }
    });
  });
}

function openUserModal(title, data, readonly) {
  $('user-modal-title').textContent = title;
  $('user-name-input').value = data ? data.name : '';
  $('user-password-input').value = '';
  $('user-type-input').value = data ? data.type : 'user';
  $('user-email-input').value = data ? data.email : '';
  $('user-phone-input').value = data ? data.phone : '';
  const inputs = ['user-name-input', 'user-password-input', 'user-type-input', 'user-email-input', 'user-phone-input'];
  inputs.forEach(id => $(id).disabled = readonly);
  $('user-modal-save').classList.toggle('hidden', readonly);
  $('user-modal-save').dataset.editId = data ? data.id : '';
  showModal('modal-user');
}

$('user-add-btn').addEventListener('click', () => openUserModal('新增用户', null, false));
$('user-modal-cancel').addEventListener('click', () => hideModal('modal-user'));
$('user-modal-close-x').addEventListener('click', () => hideModal('modal-user'));

$('user-modal-save').addEventListener('click', async () => {
  const name = $('user-name-input').value.trim();
  if (!name) { alert('请填写用户名'); return; }

  const saveBtn = $('user-modal-save');
  const editId = $('user-modal-save').dataset.editId;

  saveBtn.disabled = true;
  saveBtn.textContent = '保存中...';
  try {
    const payload = buildUserPayload(editId);
    if (!editId && !payload.password) {
      throw new Error('新增用户必须设置密码');
    }

    if (editId) {
      await updateUser(Number(editId), payload);
    } else {
      await createUser(payload);
    }

    await loadUsers();
    hideModal('modal-user');
    renderUserTable();
  } catch (e) {
    alert('保存失败: ' + e.message);
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = '保存';
  }
});

// ==================== 策略管理 ====================
function renderPolicyTable() {
  const { items, page, totalPages, total } = paginate(policyData, paginationState.policy.page, PAGE_SIZE);
  paginationState.policy.page = page;
  const tbody = $('policy-table-body');
  if (!items || items.length === 0) {
    tbody.innerHTML = '<tr><td colspan="3" class="text-center">暂无策略配置</td></tr>';
    renderPagination('policy-pagination', 'policy', 1, 0, renderPolicyTable);
    return;
  }

  tbody.innerHTML = items.map(p => {
    let inputHtml = '';
    if (p.inputType === 'select') {
      inputHtml = `<select class="input policy-edit-field" data-id="${p.id}" style="width:100px;padding:3px 6px;font-size:12px;">
        ${p.options.map(o => `<option value="${o}" ${o === p.value ? 'selected' : ''}>${o}</option>`).join('')}
      </select>`;
    } else {
      inputHtml = `<input type="number" class="input policy-edit-field" data-id="${p.id}" value="${p.value}"${p.min != null ? ` min="${p.min}"` : ''}${p.max != null ? ` max="${p.max}"` : ''}${p.step != null ? ` step="${p.step}"` : ''} style="width:100px;padding:3px 6px;font-size:12px;">`;
    }
    return `<tr>
      <td>${p.name}</td>
      <td>
        <span class="policy-value">${p.value}</span>
        <span class="policy-input-wrap">${inputHtml}</span>
      </td>
      <td class="policy-desc-cell"><div class="policy-desc-scroll" title="${p.desc}">${p.desc}</div></td>
    </tr>`;
  }).join('');
  renderPagination('policy-pagination', 'policy', totalPages, total, renderPolicyTable);
  if (policyEditing) {
    tbody.closest('table').classList.add('editing');
  } else {
    tbody.closest('table').classList.remove('editing');
  }
}

$('policy-edit-btn').addEventListener('click', () => {
  policyBackup = policyData.map(p => ({ ...p }));
  policyEditing = true;
  $('policy-edit-btn').classList.add('hidden');
  $('policy-save-btn').classList.remove('hidden');
  $('policy-cancel-btn').classList.remove('hidden');
  renderPolicyTable();
});

$('policy-save-btn').addEventListener('click', async () => {
  document.querySelectorAll('.policy-edit-field').forEach(input => {
    const id = input.dataset.id;
    const p = policyData.find(x => String(x.id) === String(id));
    if (p) p.value = input.value;
  });

  const saveBtn = $('policy-save-btn');
  saveBtn.disabled = true;
  saveBtn.textContent = '保存中...';

  try {
    await savePolicies(policyData);
    exitPolicyEdit();
  } catch (e) {
    alert('策略保存失败: ' + e.message);
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = '保存';
  }
});

$('policy-cancel-btn').addEventListener('click', () => {
  policyData = policyBackup.map(p => ({ ...p }));
  exitPolicyEdit();
});

function exitPolicyEdit() {
  policyEditing = false;
  policyBackup = [];
  $('policy-edit-btn').classList.remove('hidden');
  $('policy-save-btn').classList.add('hidden');
  $('policy-cancel-btn').classList.add('hidden');
  renderPolicyTable();
}

// ==================== 元数据服务 ====================
function getGraphNodeIds(graphData) {
  const nodes = Array.isArray(graphData?.nodes) ? graphData.nodes : [];
  return nodes.map(n => String(n?.id ?? ''))
    .filter(Boolean)
    .sort();
}

function buildGraphSignature(graphData) {
  const nodePart = getGraphNodeIds(graphData).join(',');
  const links = Array.isArray(graphData?.links) ? graphData.links : [];
  const linkPart = links
    .map(l => `${String(l?.source ?? '')}->${String(l?.target ?? '')}:${String(l?.type ?? '')}:${String(l?.label ?? '')}`)
    .sort()
    .join(',');
  return `${nodePart}||${linkPart}`;
}

function calculateGraphDelta(nextGraph) {
  const nextSignature = buildGraphSignature(nextGraph);
  const nextNodeIds = new Set(getGraphNodeIds(nextGraph));
  const addedNodeIds = [];
  nextNodeIds.forEach(id => {
    if (!metadataGraphNodeIds.has(id)) {
      addedNodeIds.push(id);
    }
  });
  return {
    changed: nextSignature !== metadataGraphSignature,
    addedNodeIds,
    nextNodeIds,
    nextSignature,
  };
}

function pickFocusKeywordFromAdded(graphData, addedNodeIds, fallbackKeyword = '') {
  if (!Array.isArray(addedNodeIds) || addedNodeIds.length === 0) {
    return (fallbackKeyword || '').trim();
  }

  const rank = {
    DataAsset: 1,
    LogicalPath: 2,
    Entity: 3,
    Field: 4
  };

  const nodes = Array.isArray(graphData?.nodes) ? graphData.nodes : [];
  const candidates = nodes
    .filter(n => addedNodeIds.includes(String(n?.id ?? '')))
    .map(n => ({
      name: String(n?.name || '').trim(),
      categoryName: String(n?.categoryName || ''),
      rank: rank[String(n?.categoryName || '')] || 9,
    }))
    .filter(n => n.name && n.name !== '(unknown)')
    .sort((a, b) => a.rank - b.rank);

  if (candidates.length > 0) {
    return candidates[0].name;
  }
  return (fallbackKeyword || '').trim();
}

function updateMetadataGraphTracking(graphData, logicalPath, searchKeyword) {
  metadataGraphSignature = buildGraphSignature(graphData);
  metadataGraphNodeIds = new Set(getGraphNodeIds(graphData));
  if (logicalPath !== undefined) {
    metadataCurrentLogicalPath = String(logicalPath || '').trim();
  }
  if (searchKeyword !== undefined) {
    metadataActiveSearchKeyword = String(searchKeyword || '').trim();
  }
}

function renderAndTrackMetadataGraph(graphData, options = {}) {
  const {
    focusKeyword = '',
    focusMode = 'search',
    logicalPath = metadataCurrentLogicalPath,
    searchKeyword = metadataActiveSearchKeyword,
  } = options;
  renderMetadataGraph(graphData, focusKeyword, focusMode);
  updateMetadataGraphTracking(graphData, logicalPath, searchKeyword);
}

function extractPathFromAgentEventText(text) {
  const source = String(text || '');
  const match = source.match(/path\s*=\s*([^,\s]+)/i);
  return match ? String(match[1] || '').trim() : '';
}

function isMetadataExtractionDoneEvent(evt) {
  const status = String(evt?.status || '').toUpperCase();
  const level = String(evt?.level || '').toLowerCase();
  const text = String(evt?.text || '');
  return status === 'DONE'
    || level === 'success'
    || /Transform extraction completed/i.test(text);
}

function queueMetadataAutoRefresh(hints = []) {
  if (metadataAutoRefreshInFlight) {
    // Collapse multiple incoming events into one catch-up refresh.
    metadataAutoRefreshPending = true;
    return;
  }
  metadataAutoRefreshPending = false;
  performMetadataAutoRefresh(hints).catch(e => {
    console.error('Metadata auto refresh failed:', e);
  });
}

async function performMetadataAutoRefresh(hints = []) {
  metadataAutoRefreshInFlight = true;
  try {
    if (metadataFullscreen || !metadataChart) {
      return;
    }

    const activeSearchKeyword = String(metadataActiveSearchKeyword || '').trim();
    if (activeSearchKeyword) {
      const nextGraph = await queryMetadataBySystem({ keyword: activeSearchKeyword });
      const delta = calculateGraphDelta(nextGraph);
      if (!delta.changed) {
        return;
      }
      const hasAdded = Array.isArray(delta.addedNodeIds) && delta.addedNodeIds.length > 0;
      const focusKeyword = pickFocusKeywordFromAdded(nextGraph, delta.addedNodeIds, activeSearchKeyword);
      renderAndTrackMetadataGraph(nextGraph, {
        focusKeyword,
        focusMode: hasAdded ? 'new' : 'search',
        logicalPath: metadataCurrentLogicalPath,
        searchKeyword: activeSearchKeyword,
      });
      return;
    }

    const nextGraph = await fetchMetadataGraph(metadataCurrentLogicalPath);
    const delta = calculateGraphDelta(nextGraph);
    if (!delta.changed) {
      return;
    }

    const hintedPath = Array.isArray(hints)
      ? (hints.find(h => String(h?.logicalPath || '').trim())?.logicalPath || '')
      : '';
    let fallbackKeyword = '';
    if (hintedPath) {
      const segments = String(hintedPath).split('/').filter(Boolean);
      fallbackKeyword = segments.length > 0 ? segments[segments.length - 1] : '/';
    }

    const focusKeyword = pickFocusKeywordFromAdded(nextGraph, delta.addedNodeIds, fallbackKeyword);
    renderAndTrackMetadataGraph(nextGraph, {
      focusKeyword,
      focusMode: 'new',
      logicalPath: metadataCurrentLogicalPath,
      searchKeyword: '',
    });
  } finally {
    metadataAutoRefreshInFlight = false;
    if (metadataAutoRefreshPending) {
      metadataAutoRefreshPending = false;
      performMetadataAutoRefresh([]).catch(e => {
        console.error('Metadata catch-up refresh failed:', e);
      });
    }
  }
}

async function initMetadataGraph(logicalPath = '') {
  const graphData = await fetchMetadataGraph(logicalPath);
  renderAndTrackMetadataGraph(graphData, {
    logicalPath,
    searchKeyword: '',
  });
}

async function fetchMetadataGraph(logicalPath = '') {
  const params = new URLSearchParams();
  if (logicalPath) params.set('logicalPath', logicalPath);
  params.set('limit', String(getGraphMaxTriplesLimit()));
  const url = `${API_BASE}/metadata/graph?${params.toString()}`;

  const response = await fetch(url);
  const result = await response.json();
  if (!response.ok || result.code !== 200 || !result.data) {
    throw new Error(result?.message || '加载元数据图谱失败');
  }
  return result.data;
}

async function queryMetadataBySystem(filters) {
  const params = new URLSearchParams();
  params.set('mode', 'system');
  if (filters?.logicalPath) params.set('logicalPath', filters.logicalPath);
  if (filters?.dataType) params.set('dataType', filters.dataType);
  if (filters?.keyword) params.set('keyword', filters.keyword);

  const url = `${API_BASE}/metadata/query?${params.toString()}`;
  const response = await fetch(url);
  const result = await response.json();
  if (!response.ok || result.code !== 200 || !result.data) {
    throw new Error(result?.message || '系统参数化查询失败');
  }
  return result.data;
}

async function queryMetadataByLLM(question) {
  const params = new URLSearchParams();
  params.set('mode', 'llm');
  params.set('q', question || '');
  const url = `${API_BASE}/metadata/query?${params.toString()}`;
  const response = await fetch(url);
  const result = await response.json();
  if (!response.ok || result.code !== 200 || !result.data) {
    throw new Error(result?.message || 'LLM查询失败');
  }
  return result.data;
}

function renderMetadataGraph(graphData, focusKeyword = '', focusMode = 'search') {
  const container = $('metadata-graph');
  if (!container) return;
  if (!metadataChart) {
    metadataChart = echarts.init(container);
  }

  const nodes = (graphData?.nodes || []).map(n => ({ ...n }));
  const links = (graphData?.links || []).map(l => ({ ...l }));
  const categories = (graphData?.categories || []).map(c => ({ name: c.name }));

  const paletteByType = {
    path: '#2b6cb0',
    root: '#114a7a',
    asset: '#00a8e8',
    schema: '#00c389',
    semantic: '#ffb347',
    device: '#4fa3ff',
    point: '#31d0c6',
    other: '#5f8fb8'
  };

  const categoryColor = {};
  categories.forEach(c => {
    const name = String(c.name || 'Other');
    if (name === 'LogicalPath') categoryColor[name] = paletteByType.path;
    else if (name === 'DataAsset') categoryColor[name] = paletteByType.asset;
    else if (name === 'Field') categoryColor[name] = paletteByType.schema;
    else if (name === 'Entity') categoryColor[name] = paletteByType.semantic;
    else categoryColor[name] = paletteByType.other;
  });

  const categoryDefs = categories.map(c => ({
    name: c.name,
    itemStyle: {
      color: categoryColor[c.name] || paletteByType.other,
      borderColor: 'rgba(220, 236, 255, 0.65)',
      borderWidth: 1.1
    }
  }));

  const relationColor = {
    CONTAINS: 'rgba(104, 176, 233, 0.75)',
    HAS_DATA: '#00c389',
    HAS_FILED: '#57d6b2',
    MENTIONS: '#ffb347',
    SEMANTIC_RELATION: '#ff9a3c'
  };

  if (nodes.length === 0) {
    metadataChart.clear();
    metadataChart.setOption({
      title: {
        text: '暂无元数据图谱',
        left: 'center',
        top: 'middle',
        textStyle: { color: '#6f90a8', fontSize: 14, fontWeight: 500 }
      }
    });
    return;
  }

  const focus = (focusKeyword || '').toLowerCase();

  const styledNodes = nodes.map(n => {
    const categoryName = String(n.categoryName || 'Other');
    const isRootPath = categoryName === 'LogicalPath' && String(n.name || '') === '/';
    const baseColor = isRootPath ? paletteByType.root : (categoryColor[categoryName] || paletteByType.other);
    const rawName = String(n.name || '').trim();
    const displayName = (!rawName || rawName === '(unknown)')
      ? (categoryName === 'LogicalPath' ? '(路径节点)' : `(未命名${categoryName})`)
      : rawName;
    const matched = !!focus && displayName.toLowerCase().includes(focus);
    const symbolSize = isRootPath ? Math.max(56, Number(n.symbolSize || 42)) : Number(n.symbolSize || 26);

    const matchStyle = focusMode === 'new'
      ? {
          color: baseColor,
          shadowBlur: isRootPath ? 20 : 16,
          shadowColor: baseColor + 'cc',
          borderColor: 'rgba(255, 255, 255, 0.95)',
          borderWidth: isRootPath ? 2.8 : 2.2,
        }
      : { color: '#ff4466', shadowBlur: 22, shadowColor: '#ff4466' };

    return {
      ...n,
      name: displayName,
      symbolSize,
      itemStyle: matched
        ? matchStyle
        : {
            color: baseColor,
            shadowBlur: isRootPath ? 16 : 8,
            shadowColor: baseColor + '80',
            borderColor: 'rgba(235, 242, 249, 0.86)',
            borderWidth: isRootPath ? 2 : 1.1
          },
      label: {
        show: true,
        color: isRootPath ? '#f0f6ff' : '#dce8f5',
        fontWeight: isRootPath ? 700 : 400
      }
    };
  });

  const nodeById = {};
  styledNodes.forEach(n => {
    nodeById[String(n.id)] = n;
  });

  const styledLinks = links.map(l => {
    const src = nodeById[String(l.source)];
    const tgt = nodeById[String(l.target)];
    const matched = !!focus
      && ((src && String(src.name || '').toLowerCase().includes(focus))
      || (tgt && String(tgt.name || '').toLowerCase().includes(focus)));
    const relType = String(l.type || l.label || '').toUpperCase();
    const baseEdgeColor = relationColor[relType] || 'rgba(93, 165, 218, 0.50)';
    const semanticEdge = relType === 'SEMANTIC_RELATION';
    const relationText = extractRelationText(l.relationText != null ? l.relationText : l.label);
    return {
      ...l,
      relationText,
      lineStyle: matched
        ? (focusMode === 'new'
          ? {
              color: baseEdgeColor,
              width: semanticEdge ? 3 : 2.6,
              opacity: 1,
              type: 'solid',
            }
          : { color: '#ff4466', width: 3 })
        : {
            color: baseEdgeColor,
            curveness: semanticEdge ? 0.2 : 0.1,
            width: semanticEdge ? 2.2 : 1.5,
            opacity: 0.95,
            type: semanticEdge ? 'solid' : 'dashed'
          },
      label: {
        show: false,
        formatter: relationText,
        color: semanticEdge ? '#ffd6aa' : '#c4d6e8',
        fontSize: semanticEdge ? 11 : 10,
        backgroundColor: semanticEdge ? 'rgba(23,31,44,0.72)' : 'transparent',
        padding: semanticEdge ? [2, 4] : [0, 0],
        borderRadius: semanticEdge ? 3 : 0
      },
    };
  });

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(8,28,54,0.95)',
      borderColor: 'rgba(0,207,255,0.3)',
      textStyle: { color: '#cce4f5', fontSize: 11 },
      formatter: params => {
        if (params.dataType === 'edge') {
          const edge = params.data || {};
          const relType = String(edge.type || '').toUpperCase();
          const sourceNode = nodeById[String(edge.source)] || {};
          const targetNode = nodeById[String(edge.target)] || {};
          const sourceName = String(sourceNode.name || edge.source || '');
          const targetName = String(targetNode.name || edge.target || '');
          const relationText = extractRelationText(edge.relationText != null ? edge.relationText : edge.label);

          if (relType === 'SEMANTIC_RELATION' && relationText) {
            return [
              '<strong>语义关系</strong>',
              `关系: ${relationText}`,
              `起点: ${sourceName}`,
              `终点: ${targetName}`,
            ].join('<br/>');
          }

          return [
            '<strong>结构关系</strong>',
            `类型: ${relType || 'RELATION'}`,
            `起点: ${sourceName}`,
            `终点: ${targetName}`,
          ].join('<br/>');
        }

        const data = params.data || {};
        const p = data.properties || {};
        const rows = Object.keys(p).slice(0, 8).map(k => `${k}: ${String(p[k])}`);
        return [
          `<strong>${data.name || ''}</strong>`,
          data.categoryName ? `类型: ${data.categoryName}` : '',
          ...rows,
        ].filter(Boolean).join('<br/>');
      },
    },
    legend: {
      data: categories.map(c => c.name),
      top: 4, left: 'center',
      textStyle: { color: '#c6d4e1', fontSize: 10 },
      itemWidth: 10, itemHeight: 10,
      selectedMode: true,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      center: ['50%', '50%'],
      data: styledNodes,
      links: styledLinks,
      categories: categoryDefs,
      roam: true,
      draggable: true,
      label: { show: true, position: 'right', color: '#cde3f2', fontSize: 10 },
      labelLayout: { hideOverlap: true },
      force: { repulsion: 300, gravity: 0.05, edgeLength: 150, friction: 0.12 },
      lineStyle: { color: 'rgba(100,160,200,0.35)', curveness: 0.14, width: 1.5 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 8],
    }],
  };
  metadataChart.setOption(option, true);
  recenterMetadataGraph();
}

function recenterMetadataGraph() {
  if (!metadataChart) return;
  metadataChart.resize();
  metadataChart.setOption({
    series: [{
      center: ['50%', '50%']
    }]
  });
}

function extractRelationText(value) {
  if (value == null) {
    return '';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value).trim();
  }
  if (typeof value === 'object') {
    const candidates = ['relationText', 'relation', 'formatter', 'label', 'name', 'text', 'predicate'];
    for (const key of candidates) {
      if (value[key] != null && String(value[key]).trim()) {
        return String(value[key]).trim();
      }
    }
    try {
      return JSON.stringify(value);
    } catch (e) {
      return '';
    }
  }
  return '';
}

function searchMetadataNode(keyword) {
  if (!metadataChart) { alert('请先刷新图谱'); return; }
  const option = metadataChart.getOption();
  const series = option.series[0];
  const categoryPalette = {
    LogicalPath: '#2b6cb0',
    DataAsset: '#00a8e8',
    Field: '#00c389',
    Entity: '#ffb347',
    Node: '#5f8fb8'
  };

  const nodeMap = {};
  (series.data || []).forEach(n => {
    nodeMap[String(n.id)] = n;
  });

  const categoryByIndex = {};
  (series.data || []).forEach(n => {
    categoryByIndex[String(n.category)] = n.categoryName || categoryByIndex[String(n.category)] || 'Node';
  });

  // reset all
  series.data.forEach(n => {
    const categoryName = categoryByIndex[String(n.category)] || 'Node';
    const baseColor = categoryPalette[categoryName] || '#5f8fb8';
    n.itemStyle = { color: baseColor, shadowBlur: 8, shadowColor: baseColor + '80' };
  });
  series.links.forEach(l => {
    l.lineStyle = { color: 'rgba(93,165,218,0.32)', width: 1.4, type: 'dashed' };
  });

  const matched = series.data.find(n => String(n.name || '').toLowerCase().includes(keyword.toLowerCase()));
  if (matched) {
    matched.itemStyle = { color: '#ff4466', shadowBlur: 20, shadowColor: '#ff4466' };
    series.links.forEach(l => {
      if (String(l.source) === String(matched.id) || String(l.target) === String(matched.id)) {
        l.lineStyle = { color: '#ff4466', width: 3 };
      }
    });
    metadataChart.setOption(option);
    return true;
  } else {
    metadataChart.setOption(option);
    return false;
  }
}

function enterMetadataFullscreen() {
  const panel = document.querySelector('.panel-metadata');
  if (!panel || metadataFullscreen) return;
  metadataFullscreen = true;
  syncMetadataQueryModeUI('system');
  syncMetadataHeaderControls();
  panel.classList.add('metadata-fullscreen');
  document.body.classList.add('metadata-fullscreen-active');
  $('metadata-fullscreen-btn')?.classList.add('hidden');
  $('metadata-exit-fullscreen-btn')?.classList.remove('hidden');
  setTimeout(recenterMetadataGraph, 80);
}

function exitMetadataFullscreen() {
  const panel = document.querySelector('.panel-metadata');
  if (!panel || !metadataFullscreen) return;
  metadataFullscreen = false;
  syncMetadataHeaderControls();
  panel.classList.remove('metadata-fullscreen');
  document.body.classList.remove('metadata-fullscreen-active');
  $('metadata-fullscreen-btn')?.classList.remove('hidden');
  $('metadata-exit-fullscreen-btn')?.classList.add('hidden');
  setTimeout(recenterMetadataGraph, 80);
}

$('metadata-build-btn').addEventListener('click', async () => {
  const input = metadataFullscreen
    ? (($('metadata-path-input')?.value || '').trim())
    : (($('metadata-quick-keyword-input')?.value || '').trim());
  const logicalPath = input.startsWith('/') ? input : '';

  try {
    await initMetadataGraph(logicalPath);
  } catch (e) {
    alert('刷新图谱失败: ' + e.message);
    console.error('Refresh metadata graph error:', e);
  }
});

$('metadata-search-btn').addEventListener('click', async () => {
  const quickKeyword = ($('metadata-quick-keyword-input')?.value || '').trim();
  if (!quickKeyword && !metadataFullscreen) { alert('请输入实体关键词'); return; }

  const searchBtn = $('metadata-search-btn');
  if (!searchBtn || searchBtn.disabled) {
    return;
  }
  const originalBtnText = searchBtn.textContent || '查找';
  searchBtn.disabled = true;
  searchBtn.textContent = '查找中...';

  try {
    let graph;
    let focusKeyword = '';
    if (!metadataFullscreen) {
      // 非全屏：仅按实体关键词进行快速查询。
      graph = await queryMetadataBySystem({
        keyword: quickKeyword,
      });
      focusKeyword = quickKeyword;
    } else {
      const mode = metadataQueryMode;
      if (mode === 'llm') {
        const llmQuestion = $('metadata-llm-input')?.value.trim() || '';
        if (!llmQuestion) {
          alert('请填写LLM查询问题');
          return;
        }
        graph = await queryMetadataByLLM(llmQuestion);
        // LLM 查询不强行沿用非全屏关键词，避免历史高亮残留。
        focusKeyword = '';
      } else {
        const logicalPath = $('metadata-path-input')?.value.trim() || '';
        const dataType = $('metadata-type-input')?.value.trim() || '';
        const keyword = $('metadata-keyword-input')?.value.trim() || '';

        graph = await queryMetadataBySystem({
          logicalPath,
          dataType,
          keyword,
        });
        focusKeyword = keyword;
      }
    }

    renderAndTrackMetadataGraph(graph, {
      focusKeyword,
      focusMode: 'search',
      logicalPath: metadataCurrentLogicalPath,
      searchKeyword: metadataFullscreen ? '' : quickKeyword,
    });
    if (graph.cypher) {
      console.log('Metadata query cypher:', graph.cypher);
    }
    if (graph.strategy) {
      console.log('Metadata query strategy:', graph.strategy, graph.strategyReason || '', graph.strategyConfidence || '');
    }
  } catch (e) {
    // 查询失败时，回退到本地图高亮，至少保证交互可用。
    const fallbackKeyword = metadataFullscreen
      ? (($('metadata-keyword-input')?.value || '').trim())
      : quickKeyword;
    const hit = fallbackKeyword ? searchMetadataNode(fallbackKeyword) : false;
    if (!hit) {
      alert('语义查询失败: ' + e.message);
    }
    console.error('Metadata query error:', e);
  } finally {
    searchBtn.disabled = false;
    searchBtn.textContent = originalBtnText;
  }
});
$('metadata-quick-keyword-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('metadata-search-btn').click();
});
$('metadata-keyword-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') $('metadata-search-btn').click();
});
$('metadata-llm-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') $('metadata-search-btn').click();
});

$('metadata-fullscreen-btn').addEventListener('click', () => {
  enterMetadataFullscreen();
});

$('metadata-exit-fullscreen-btn').addEventListener('click', () => {
  exitMetadataFullscreen();
});

function syncMetadataQueryModeUI(mode) {
  metadataQueryMode = (mode || 'system').toLowerCase() === 'llm' ? 'llm' : 'system';

  const systemBtn = $('metadata-mode-system-btn');
  const llmBtn = $('metadata-mode-llm-btn');
  const systemPanel = $('metadata-system-filters');
  const llmPanel = $('metadata-llm-panel');

  if (systemBtn) systemBtn.classList.toggle('active', metadataQueryMode === 'system');
  if (llmBtn) llmBtn.classList.toggle('active', metadataQueryMode === 'llm');
  if (systemPanel) systemPanel.classList.toggle('hidden', metadataQueryMode !== 'system');
  if (llmPanel) llmPanel.classList.toggle('hidden', metadataQueryMode !== 'llm');
}

function syncMetadataHeaderControls() {
  const quickInput = $('metadata-quick-keyword-input');
  const fullscreenControls = $('metadata-fullscreen-controls');

  if (quickInput) {
    quickInput.classList.toggle('hidden', metadataFullscreen);
  }
  if (fullscreenControls) {
    fullscreenControls.classList.toggle('hidden', !metadataFullscreen);
  }
}

$('metadata-mode-system-btn')?.addEventListener('click', () => syncMetadataQueryModeUI('system'));
$('metadata-mode-llm-btn')?.addEventListener('click', () => syncMetadataQueryModeUI('llm'));
syncMetadataQueryModeUI('system');
syncMetadataHeaderControls();

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && metadataFullscreen) {
    exitMetadataFullscreen();
  }
});

// ==================== 存储服务 ====================
const uploadZone = $('storage-upload');
const fileInput = $('file-input');

const storageSourceDefaultPorts = {
  filesystem: '6669',
  mysql: '3306',
  postgres: '5432',
  iotdb: '6667',
};

function getStorageSourceLabel(sourceType) {
  const labels = {
    filesystem: 'filesystem',
    mysql: 'MySQL',
    postgres: 'PostgreSQL',
    iotdb: 'IoTDB',
  };
  return labels[sourceType] || sourceType;
}

function syncStorageSourceFormOptions(resetPort = false) {
  const sourceType = $('storage-source-type-input').value;
  const fsFields = $('storage-source-filesystem-fields');
  const authFields = $('storage-source-auth-fields');
  const portInput = $('storage-source-port-input');

  fsFields.classList.toggle('hidden', sourceType !== 'filesystem');
  authFields.classList.toggle('hidden', sourceType === 'filesystem');

  const defaultPort = storageSourceDefaultPorts[sourceType] || '';
  if (resetPort || !portInput.value.trim()) {
    portInput.value = defaultPort;
  }

  if (sourceType === 'filesystem' && !$('storage-source-iginx-port-input').value.trim()) {
    $('storage-source-iginx-port-input').value = '6888';
  }
}

function openStorageSourceModal() {
  $('storage-source-type-input').value = 'filesystem';
  $('storage-source-ip-input').value = '127.0.0.1';
  $('storage-source-port-input').value = storageSourceDefaultPorts.filesystem;
  $('storage-source-username-input').value = '';
  $('storage-source-password-input').value = '';
  $('storage-source-dummy-dir-input').value = '';
  $('storage-source-iginx-port-input').value = '6888';
  syncStorageSourceFormOptions(true);
  showModal('modal-storage-source');
}

function closeStorageSourceModal() {
  hideModal('modal-storage-source');
}

function buildStorageSourcePayload() {
  const sourceType = $('storage-source-type-input').value;
  const ip = $('storage-source-ip-input').value.trim();
  const port = Number($('storage-source-port-input').value.trim());

  if (!ip) {
    throw new Error('请输入数据源IP');
  }
  if (!Number.isFinite(port) || port <= 0) {
    throw new Error('请输入正确的端口');
  }

  const payload = {
    sourceType,
    ip,
    port,
  };

  if (sourceType === 'filesystem') {
    const dummyDir = $('storage-source-dummy-dir-input').value.trim();
    const iginxPort = Number($('storage-source-iginx-port-input').value.trim());
    if (!dummyDir) {
      throw new Error('filesystem 需要填写 dummy_dir');
    }
    if (!Number.isFinite(iginxPort) || iginxPort <= 0) {
      throw new Error('filesystem 需要填写正确的 iginx_port');
    }
    payload.dummyDir = dummyDir;
    payload.iginxPort = iginxPort;
  } else {
    const username = $('storage-source-username-input').value.trim();
    const password = $('storage-source-password-input').value;
    if (!username || !password) {
      throw new Error('请填写 username 和 password');
    }
    payload.username = username;
    payload.password = password;
  }

  return payload;
}

$('storage-source-add-btn').addEventListener('click', openStorageSourceModal);
$('storage-source-modal-cancel').addEventListener('click', closeStorageSourceModal);
$('storage-source-modal-close-x').addEventListener('click', closeStorageSourceModal);
$('storage-source-type-input').addEventListener('change', () => syncStorageSourceFormOptions(true));

$('storage-source-modal-save').addEventListener('click', async () => {
  const saveBtn = $('storage-source-modal-save');
  if (saveBtn.disabled) {
    return;
  }

  let payload;
  try {
    payload = buildStorageSourcePayload();
  } catch (e) {
    alert(e.message || '表单校验失败');
    return;
  }

  saveBtn.disabled = true;
  saveBtn.textContent = '添加中...';

  const sourceLabel = getStorageSourceLabel(payload.sourceType);
  const storageAgentName = pickAgentName();
  try {
    const result = await requestJson(`${API_BASE}/storage/sources`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    pushAgentMessage({
      level: 'success',
      status: '完成',
      agentName: storageAgentName,
      text: `新增${sourceLabel}数据源成功，已进入定时UDF抽取队列`,
    });

    await loadDataSourceSummary();
    alert('新增数据源成功');
    closeStorageSourceModal();
  } catch (e) {
    pushAgentMessage({
      level: 'warn',
      status: '失败',
      agentName: storageAgentName,
      text: `新增${sourceLabel}数据源失败：${e.message}`,
    });
    alert('新增数据源失败: ' + e.message);
  } finally {
    saveBtn.disabled = false;
    saveBtn.textContent = '确认添加';
  }
});

syncStorageSourceFormOptions(true);

// File extension accept map per data type
const acceptMap = {
  relational: '.csv,.txt',
  timeseries: '.csv,.txt',
  document:   '.json,.xml',
  image:      '.jpg,.jpeg,.png,.bmp',
  keyvalue:   '.json,.yaml,.yml',
};

function updateFileAccept() {
  const type = $('storage-type-select').value;
  fileInput.accept = acceptMap[type] || '';
  // Update hint text
  const hint = uploadZone.querySelector('.upload-hint');
  if (hint) {
    hint.textContent = '支持格式: ' + (acceptMap[type] || '所有文件').replace(/\./g, '').toUpperCase();
  }
}

// Set initial accept & update on type change
updateFileAccept();
$('storage-type-select').addEventListener('change', () => {
  updateFileAccept();
  // Clear already selected files when type changes
  if (selectedFiles.length > 0) {
    if (confirm('切换数据类型后已选文件将被清除，是否继续？')) {
      selectedFiles = [];
      renderFileList();
    }
  }
});

uploadZone.addEventListener('click', (e) => {
  if (e.target === fileInput) return;
  fileInput.click();
});
uploadZone.addEventListener('dragover', e => { e.preventDefault(); uploadZone.classList.add('dragover'); });
uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('dragover'));
uploadZone.addEventListener('drop', e => {
  e.preventDefault();
  uploadZone.classList.remove('dragover');
  handleFiles(Array.from(e.dataTransfer.files));
});
fileInput.addEventListener('change', e => {
  handleFiles(Array.from(e.target.files));
  // Reset value so the same file can be re-selected after removal/storage
  fileInput.value = '';
});

function handleFiles(files) {
  if (files.length === 0) return;
  // Only keep the last selected file (single file mode)
  selectedFiles = [files[0]];
  renderFileList();
}

function renderFileList() {
  const list = $('file-list');
  list.innerHTML = selectedFiles.map((f, i) => `
    <div class="file-item">
      <div class="file-item-info">
        <span>📄</span>
        <span class="file-item-name">${f.name}</span>
        <span class="file-item-size">(${(f.size / 1024).toFixed(1)} KB)</span>
      </div>
      <button class="file-item-remove" data-index="${i}">&times;</button>
    </div>
  `).join('');
  list.querySelectorAll('.file-item-remove').forEach(btn => {
    btn.addEventListener('click', function () {
      selectedFiles.splice(+this.dataset.index, 1);
      renderFileList();
    });
  });
}

$('storage-save-btn').addEventListener('click', async () => {
  const type = $('storage-type-select').value;
  const path = $('storage-path-input').value.trim();
  if (!path) { alert('请输入逻辑路径'); return; }
  if (selectedFiles.length === 0) { alert('请选择要上传的文件'); return; }

  const storageAgentName = pickAgentName();
  const btn = $('storage-save-btn');
  btn.disabled = true;
  btn.textContent = '存储中...';

  try {
    const formData = new FormData();
    formData.append('file', selectedFiles[0]);
    formData.append('logicalPath', path);
    formData.append('dataType', type);

    const response = await fetch(`${API_BASE}/storage`, {
      method: 'POST',
      body: formData,
    });

    const result = await response.json();
    if (result.code !== 200 && result.code !== 201) {
      throw new Error(result.message || '存储失败');
    }
    const typeLabelMap = {
      relational: '关系数据',
      timeseries: '时序数据',
      document: '文档数据',
      image: '图像数据',
      keyvalue: '键值数据',
    };
    const typeLabel = typeLabelMap[type] || type;
    pushAgentMessage({
      level: 'success',
      status: '完成',
      agentName: storageAgentName,
      text: `${typeLabel}已存储成功，路径 ${path}，等待定时UDF抽取`,
    });
    alert('存储成功！文件已保存到 ' + path);
    selectedFiles = [];
    renderFileList();
  } catch (e) {
    pushAgentMessage({
      level: 'warn',
      status: '失败',
      agentName: storageAgentName,
      text: `存储任务失败：${e.message}`,
    });
    alert('存储失败: ' + e.message);
    console.error('Storage error:', e);
  } finally {
    btn.disabled = false;
    btn.textContent = '存储';
  }
});

// ==================== 访问服务 ====================
const accessBrowserState = {
  currentFolderPath: '/',
  currentFolderChildren: [],
  currentFileName: '',
  isFileView: false,
  folderHistory: [],
};

function normalizeAccessPath(path) {
  const raw = (path || '').trim();
  if (!raw) return '/';
  let normalized = raw.startsWith('/') ? raw : `/${raw}`;
  while (normalized.length > 1 && normalized.endsWith('/')) {
    normalized = normalized.slice(0, -1);
  }
  return normalized;
}

function updateAccessBackButtonState() {
  const backBtn = $('access-back-btn');
  if (!backBtn) return;
  const canBack = accessBrowserState.isFileView || accessBrowserState.folderHistory.length > 1;
  backBtn.disabled = !canBack;
}

function setAccessInfo(typeText, sizeText, timeText) {
  $('access-data-type').textContent = typeText;
  $('access-data-size').textContent = sizeText;
  $('access-data-time').textContent = timeText;
}

function showAccessRootNoDataHint(agentName) {
  const preview = $('access-preview');
  setAccessInfo('-', '-', '-');
  preview.innerHTML = '<div class="preview-placeholder">当前没有数据，请先在“存储服务”中存储数据</div>';
  pushAgentMessage({
    level: 'info',
    status: '提示',
    agentName,
    text: '当前尚无可访问数据，请先在存储服务中写入数据',
  });
}

async function requestAccessData(logicalPath, fileName = '') {
  let url = `${API_BASE}/access/data?logicalPath=${encodeURIComponent(logicalPath)}`;
  if (fileName) {
    url += `&fileName=${encodeURIComponent(fileName)}`;
  }
  const response = await fetch(url);
  const contentType = response.headers.get('content-type') || '';
  const result = contentType.includes('application/json') ? await response.json() : null;
  return { response, result };
}

function renderAccessItem(item) {
  const preview = $('access-preview');
  const dataType = item?.dataType || '';
  const previewData = item?.previewData;

  const typeLabels = {
    timeseries: '时序数据', relational: '关系数据', image: '图像数据',
    document: '文档数据', keyvalue: '键值数据', directory: '目录'
  };

  const rawSize = Number(item?.fileSize || 0);
  const sizeText = dataType === 'directory'
    ? '-'
    : (rawSize > 0 ? formatFileSize(rawSize) : '-');

  setAccessInfo(
    typeLabels[dataType] || dataType || '-',
    sizeText,
    dataType === 'directory' ? '-' : (item?.createTime || '-')
  );

  if (dataType === 'directory') {
    renderDirectoryListing(preview, previewData, item.logicalPath || accessBrowserState.currentFolderPath);
    return;
  }

  if (dataType === 'image') {
    renderImagePreview(preview, previewData, item);
  } else if (dataType === 'timeseries' || dataType === 'relational') {
    renderTablePreview(preview, previewData, dataType);
  } else if (dataType === 'document') {
    renderDocumentPreview(preview, previewData, item);
  } else if (dataType === 'keyvalue') {
    renderKeyValuePreview(preview, previewData);
  } else {
    preview.innerHTML = '<div class="preview-placeholder">不支持预览此数据类型</div>';
  }
}

async function openFolderView(path, options = {}) {
  const { recordHistory = true, accessAgentName = pickAgentName(), showRunningMessage = true } = options;
  const folderPath = normalizeAccessPath(path);
  const preview = $('access-preview');
  const btn = $('access-visit-btn');

  btn.disabled = true;
  btn.textContent = '访问中...';
  preview.innerHTML = '<div class="preview-placeholder">加载中...</div>';

  if (showRunningMessage) {
    pushAgentMessage({
      level: 'running',
      status: '进行中',
      agentName: accessAgentName,
      text: `正在访问目录，路径 ${folderPath}`,
    });
  }

  try {
    const { response, result } = await requestAccessData(folderPath);
    if (!response.ok) {
      const message = result?.message || `HTTP ${response.status}`;
      const isNotFound = response.status === 404 || /data\s+not\s+found\s+for\s+path/i.test(message);
      if (folderPath === '/' && isNotFound) {
        showAccessRootNoDataHint(accessAgentName);
        return;
      }
      throw new Error(message);
    }

    if (!result || result.code !== 200 || !result.data) {
      if (folderPath === '/') {
        showAccessRootNoDataHint(accessAgentName);
        return;
      }
      setAccessInfo('-', '-', '-');
      preview.innerHTML = '<div class="preview-placeholder">未找到该路径对应的数据</div>';
      pushAgentMessage({
        level: 'warn',
        status: '失败',
        agentName: accessAgentName,
        text: `访问失败，路径 ${folderPath} 未找到对应数据`,
      });
      return;
    }

    const item = result.data;
    const normalizedFolder = normalizeAccessPath(item.logicalPath || folderPath);
    accessBrowserState.currentFolderPath = normalizedFolder;
    accessBrowserState.currentFolderChildren = Array.isArray(item.previewData) ? item.previewData : [];
    accessBrowserState.currentFileName = '';
    accessBrowserState.isFileView = false;

    $('access-path-input').value = normalizedFolder;

    if (recordHistory) {
      const history = accessBrowserState.folderHistory;
      if (history.length === 0 || history[history.length - 1] !== normalizedFolder) {
        history.push(normalizedFolder);
      }
    }

    renderAccessItem(item);
    updateAccessBackButtonState();

    pushAgentMessage({
      level: 'success',
      status: '完成',
      agentName: accessAgentName,
      text: `完成目录访问，路径 ${normalizedFolder}`,
    });
  } catch (e) {
    console.error('Access error:', e);
    preview.innerHTML = `<div class="preview-placeholder">访问失败: ${e.message}</div>`;
    setAccessInfo('-', '-', '-');
    pushAgentMessage({
      level: 'warn',
      status: '失败',
      agentName: accessAgentName,
      text: `访问任务失败：${e.message}`,
    });
  } finally {
    btn.disabled = false;
    btn.textContent = '访问';
    updateAccessBackButtonState();
  }
}

async function openFileInFolder(folderPath, fileName, accessAgentName = pickAgentName()) {
  const normalizedFolder = normalizeAccessPath(folderPath || accessBrowserState.currentFolderPath);
  const targetFile = (fileName || '').trim();
  if (!targetFile) {
    alert('未找到文件名，无法访问文件内容');
    return;
  }

  const preview = $('access-preview');
  preview.innerHTML = '<div class="preview-placeholder">加载中...</div>';

  pushAgentMessage({
    level: 'running',
    status: '进行中',
    agentName: accessAgentName,
    text: `正在访问文件 ${targetFile}（目录 ${normalizedFolder}）`,
  });

  try {
    const { response, result } = await requestAccessData(normalizedFolder, targetFile);
    if (!response.ok || !result || result.code !== 200 || !result.data) {
      const message = result?.message || `HTTP ${response.status}`;
      throw new Error(message);
    }

    const item = result.data;
    accessBrowserState.currentFolderPath = normalizeAccessPath(item.logicalPath || normalizedFolder);
    accessBrowserState.currentFileName = item.fileName || targetFile;
    accessBrowserState.isFileView = true;

    // Keep logical path input unchanged as current folder.
    $('access-path-input').value = accessBrowserState.currentFolderPath;

    renderAccessItem(item);
    updateAccessBackButtonState();

    pushAgentMessage({
      level: 'success',
      status: '完成',
      agentName: accessAgentName,
      text: `完成文件访问，目录 ${accessBrowserState.currentFolderPath}，文件 ${accessBrowserState.currentFileName}`,
    });
  } catch (e) {
    console.error('File access error:', e);
    preview.innerHTML = `<div class="preview-placeholder">访问文件失败: ${e.message}</div>`;
    setAccessInfo('-', '-', '-');
    pushAgentMessage({
      level: 'warn',
      status: '失败',
      agentName: accessAgentName,
      text: `文件访问失败：${e.message}`,
    });
  }
}

$('access-visit-btn').addEventListener('click', async () => {
  const path = $('access-path-input').value.trim();
  if (!path) { alert('请输入逻辑路径'); return; }
  await openFolderView(path, { recordHistory: true, accessAgentName: pickAgentName(), showRunningMessage: true });
});

$('access-back-btn').addEventListener('click', async () => {
  const accessAgentName = pickAgentName();

  if (accessBrowserState.isFileView) {
    accessBrowserState.isFileView = false;
    accessBrowserState.currentFileName = '';
    const folderItem = {
      logicalPath: accessBrowserState.currentFolderPath,
      dataType: 'directory',
      previewData: accessBrowserState.currentFolderChildren,
    };
    $('access-path-input').value = accessBrowserState.currentFolderPath;
    renderAccessItem(folderItem);
    updateAccessBackButtonState();
    pushAgentMessage({
      level: 'info',
      status: '返回',
      agentName: accessAgentName,
      text: `已返回目录 ${accessBrowserState.currentFolderPath}`,
    });
    return;
  }

  if (accessBrowserState.folderHistory.length <= 1) {
    return;
  }

  accessBrowserState.folderHistory.pop();
  const previousFolder = accessBrowserState.folderHistory[accessBrowserState.folderHistory.length - 1];
  await openFolderView(previousFolder, { recordHistory: false, accessAgentName, showRunningMessage: false });
});

updateAccessBackButtonState();

function renderImagePreview(container, previewData, meta) {
  if (!previewData || !previewData.base64) {
    container.innerHTML = '<div class="preview-placeholder">无法加载图像数据</div>';
    return;
  }
  const format = (meta.fileFormat || 'png').toLowerCase();
  const mimeMap = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', bmp: 'image/bmp' };
  const mime = mimeMap[format] || 'image/png';
  
  // 显示预览限制提示
  const previewLimitText = previewData.previewLimit || '图像数据最多预览前5MB数据';
  
  container.innerHTML = `<div style="text-align:center;padding:12px;overflow:auto;max-height:100%;">
    <div style="background:rgba(255,180,0,0.15);border:1px solid rgba(255,180,0,0.3);border-radius:4px;padding:6px 10px;margin-bottom:10px;font-size:11px;color:#ffb347;">
      ℹ️ ${escapeHtml(previewLimitText)}
    </div>
    <img src="data:${mime};base64,${previewData.base64}" 
         style="max-width:100%;max-height:280px;border-radius:6px;border:1px solid rgba(0,180,255,0.2);"
         alt="${meta.fileName || 'image'}">
    <p style="color:var(--text-dim);margin-top:8px;font-size:11px;">${meta.fileName || '图像预览'}</p>
  </div>`;
}

function renderTablePreview(container, previewData, dataType) {
  if (!previewData || !previewData.columns || !previewData.rows) {
    container.innerHTML = '<div class="preview-placeholder">无数据</div>';
    return;
  }
  const cols = previewData.columns;
  const rows = previewData.rows;
  const previewLimit = 50;
  const maxRows = Math.min(rows.length, previewLimit);

  let html = '<div class="table-wrapper" style="overflow:auto;max-height:100%;"><table style="font-size:11px;"><thead><tr>';
  cols.forEach(c => { html += `<th>${escapeHtml(c)}</th>`; });
  html += '</tr></thead><tbody>';
  for (let i = 0; i < maxRows; i++) {
    html += '<tr>';
    const row = rows[i];
    for (let j = 0; j < cols.length; j++) {
      const val = j < row.length ? row[j] : '';
      html += `<td>${escapeHtml(String(val != null ? val : ''))}</td>`;
    }
    html += '</tr>';
  }
  html += '</tbody></table>';
  if (rows.length === previewLimit) {
    html += `<p style="color:var(--text-dim);font-size:11px;padding:4px 8px;">显示前 ${maxRows} 行</p>`;
  }
  html += '</div>';
  container.innerHTML = html;
}

function renderDocumentPreview(container, content, meta) {
  if (!content) {
    container.innerHTML = '<div class="preview-placeholder">无文档内容</div>';
    return;
  }
  const format = (meta.fileFormat || '').toLowerCase();
  let displayContent = content;
  // Try to pretty-format JSON
  if (format === 'json') {
    try {
      displayContent = JSON.stringify(JSON.parse(content), null, 2);
    } catch (e) { /* keep original */ }
  }
  
  // 显示预览限制提示
  const previewLimitText = '文档数据最多预览前1MB数据';

  container.innerHTML = `
    <div style="background:rgba(255,180,0,0.15);border:1px solid rgba(255,180,0,0.3);border-radius:4px;padding:6px 10px;margin:8px;font-size:11px;color:#ffb347;">
      ℹ️ ${escapeHtml(previewLimitText)}
    </div>
    <pre class="code-block" style="white-space:pre-wrap;font-size:11px;overflow:auto;max-height:100%;margin:0;padding:8px;">${escapeHtml(displayContent)}</pre>`;
}

function renderKeyValuePreview(container, kvData) {
  if (!kvData || Object.keys(kvData).length === 0) {
    container.innerHTML = '<div class="preview-placeholder">无键值数据</div>';
    return;
  }
  let html = '<div class="table-wrapper" style="overflow:auto;max-height:100%;"><table style="font-size:11px;"><thead><tr><th>键 (Key)</th><th>值 (Value)</th></tr></thead><tbody>';
  for (const [key, value] of Object.entries(kvData)) {
    html += `<tr><td>${escapeHtml(key)}</td><td>${escapeHtml(String(value))}</td></tr>`;
  }
  html += '</tbody></table></div>';
  container.innerHTML = html;
}

/**
 * Render directory listing when a parent path is accessed.
 * Shows a clickable list of child items under the given path.
 */
function renderDirectoryListing(container, children, parentPath) {
  if (!children || !Array.isArray(children) || children.length === 0) {
    container.innerHTML = '<div class="preview-placeholder">该目录下无数据</div>';
    return;
  }
  const typeLabels = {
    timeseries: '时序数据', relational: '关系数据', image: '图像数据',
    document: '文档数据', keyvalue: '键值数据', directory: '📁 子目录'
  };
  const typeColors = {
    timeseries: '#00cfff', relational: '#00e68a', image: '#ff6b9d',
    document: '#ffa800', keyvalue: '#a78bfa', directory: '#8cb8d0'
  };

  let html = '<div style="padding:8px;overflow:auto;max-height:100%;">';
  html += `<p style="color:var(--text-dim);font-size:11px;margin-bottom:8px;">📂 路径: ${escapeHtml(parentPath)} (共 ${children.length} 项)</p>`;
  html += '<div style="display:flex;flex-direction:column;gap:4px;">';

  for (const child of children) {
    const dt = child.dataType || 'directory';
    const label = typeLabels[dt] || dt;
    const color = typeColors[dt] || '#8cb8d0';
    const icon = dt === 'directory' ? '📁' : '📄';
    const nameDisplay = escapeHtml(child.name);
    const pathDisplay = escapeHtml(child.fullPath);
    const fileToken = escapeHtml(child.fileName || child.name || '');
    const extra = child.fileName ? ` · ${escapeHtml(child.fileName)}` : '';
    const timeInfo = child.createTime ? ` · ${escapeHtml(child.createTime)}` : '';

    html += `<div class="dir-listing-item" data-path="${pathDisplay}" data-type="${escapeHtml(dt)}" data-file="${fileToken}"
      style="padding:6px 10px;background:rgba(0,40,80,0.4);border:1px solid rgba(0,180,255,0.15);border-radius:4px;cursor:pointer;transition:all 0.2s;"
      onmouseover="this.style.borderColor='rgba(0,180,255,0.5)';this.style.background='rgba(0,60,120,0.5)'"
      onmouseout="this.style.borderColor='rgba(0,180,255,0.15)';this.style.background='rgba(0,40,80,0.4)'"
    >
      <span style="font-size:12px;">${icon} <strong style="color:#cce4f5;">${nameDisplay}</strong></span>
      <span style="float:right;font-size:10px;color:${color};border:1px solid ${color};padding:0 4px;border-radius:3px;">${label}</span>
      <div style="font-size:10px;color:var(--text-dim);margin-top:2px;">${pathDisplay}${extra}${timeInfo}</div>
    </div>`;
  }

  html += '</div></div>';
  container.innerHTML = html;

  // Bind click events – directory navigates by path, file opens in current folder without changing logical path.
  container.querySelectorAll('.dir-listing-item').forEach(item => {
    item.addEventListener('click', async function() {
      const childPath = this.getAttribute('data-path');
      const type = this.getAttribute('data-type') || '';
      const fileName = this.getAttribute('data-file') || '';

      if (type === 'directory') {
        await openFolderView(childPath, { recordHistory: true, accessAgentName: pickAgentName(), showRunningMessage: true });
        return;
      }

      await openFileInFolder(parentPath, fileName, pickAgentName());
    });
  });
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}

$('access-download-btn').addEventListener('click', async () => {
  const folderPath = normalizeAccessPath(accessBrowserState.currentFolderPath || $('access-path-input').value.trim());
  const fileName = (accessBrowserState.currentFileName || '').trim();
  if (!folderPath) { alert('请先访问数据'); return; }
  if (!fileName) { alert('请先在目录中点击一个文件，再执行下载'); return; }

  const btn = $('access-download-btn');
  btn.disabled = true;
  btn.textContent = '下载中...';

  try {
    const response = await fetch(`${API_BASE}/access/download?logicalPath=${encodeURIComponent(folderPath)}&fileName=${encodeURIComponent(fileName)}`);
    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(errorText || 'HTTP ' + response.status);
    }

    // Get filename from Content-Disposition header
    const disposition = response.headers.get('Content-Disposition');
    let downloadFileName = fileName; // 使用不同的变量名避免冲突
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=["']?([^"';\n]*)["']?/);
      if (match && match[1]) {
        downloadFileName = decodeURIComponent(match[1]);
      }
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = downloadFileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  } catch (e) {
    alert('下载失败: ' + e.message);
    console.error('Download error:', e);
  } finally {
    btn.disabled = false;
    btn.textContent = '点击下载数据';
  }
});

// ==================== 多样化接口服务 ====================
function renderInterfaceTable() {
  const data = interfaceData[currentInterfaceType] || [];
  const tbody = $('interface-table-body');
  if (!data.length) {
    tbody.innerHTML = '<tr><td colspan="4" class="text-center">暂无接口数据</td></tr>';
    return;
  }

  tbody.innerHTML = data.map(item => `
    <tr>
      <td>${escapeHtml(item.name || '-')}</td>
      <td><span class="mono">${escapeHtml(item.url || '-')}</span></td>
      <td class="policy-desc-cell" title="${escapeHtml(item.desc || '-')}">${escapeHtml(item.desc || '-')}</td>
      <td><button class="btn-text-action view interface-view-btn" data-id="${item.id}" data-type="${currentInterfaceType}">查看详情</button></td>
    </tr>
  `).join('');
  bindInterfaceEvents();
}

function bindInterfaceEvents() {
  document.querySelectorAll('.interface-view-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const type = this.dataset.type;
      const id = this.dataset.id;
      const data = interfaceData[type]?.find(x => x.id === id);
      if (data) openInterfaceDrawer(data);
    });
  });
}

function openInterfaceDrawer(data) {
  $('drawer-interface-name').textContent = data.name;
  $('drawer-interface-url').textContent = data.url;
  $('drawer-interface-type').textContent = data.type;
  $('drawer-interface-desc').textContent = data.desc;
  $('drawer-interface-params').textContent = data.params;
  $('drawer-interface-response').textContent = data.response;
  $('drawer-interface-code').textContent = data.code;
  $('drawer-interface').classList.add('open');
  $('drawer-overlay').classList.remove('hidden');
}

function closeDrawer() {
  $('drawer-interface').classList.remove('open');
  $('drawer-overlay').classList.add('hidden');
}

['interface-btn-rest', 'interface-btn-java', 'interface-btn-python'].forEach(id => {
  $(id).addEventListener('click', function () {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    this.classList.add('active');
    currentInterfaceType = id.replace('interface-btn-', '');
    renderInterfaceTable();
  });
});

$('drawer-close').addEventListener('click', closeDrawer);
$('drawer-overlay').addEventListener('click', closeDrawer);

$('interface-download-btn').addEventListener('click', () => {
  const code = $('drawer-interface-code').textContent;
  const blob = new Blob([code], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'example-code.txt';
  a.click();
  URL.revokeObjectURL(url);
});

// ==================== 窗口缩放 ====================
window.addEventListener('resize', () => {
  topologyChart?.resize();
  recenterMetadataGraph();
});

// ==================== 初始化 ====================
async function init() {
  updateTime();
  setInterval(updateTime, 1000);

  bindAuthEvents();
  setCurrentUser(null);
  renderClusterTable();
  renderUserTable();
  renderPolicyTable();
  renderInterfaceTable();

  showLoginOverlay();
  $('login-username-input').focus();
}


// ==================== 密码可见性切换 ====================
document.querySelectorAll('.password-toggle').forEach(btn => {
  btn.addEventListener('click', function () {
    const targetId = this.dataset.target;
    const input = document.getElementById(targetId);
    if (!input) return;
    const isPassword = input.type === 'password';
    input.type = isPassword ? 'text' : 'password';
    this.querySelector('.eye-open').style.display = isPassword ? 'none' : '';
    this.querySelector('.eye-closed').style.display = isPassword ? '' : 'none';
  });
});

init();
console.log('可扩展存储引擎可视化大屏初始化完成');


$('cluster-list-mode-btn')?.addEventListener('click', () => setClusterViewMode('list'));
$('cluster-graph-mode-btn')?.addEventListener('click', () => setClusterViewMode('graph'));
setClusterViewMode('list');

const clusterListModeBtn = $('cluster-list-mode-btn');
const clusterGraphModeBtn = $('cluster-graph-mode-btn');
if (clusterListModeBtn) clusterListModeBtn.addEventListener('click', () => setClusterViewMode('list'));
if (clusterGraphModeBtn) clusterGraphModeBtn.addEventListener('click', () => setClusterViewMode('graph'));
setClusterViewMode('list');
