// ===== 可扩展存储引擎 - 可视化大屏 =====
import * as echarts from 'echarts';

// ==================== 配置 ====================
const API_BASE = ''; // Use relative path (proxied by Vite in dev, same origin in production)

// ==================== 数据 ====================
let clusterData = [];

const userData = [
  { id: 1, name: 'admin', type: 'admin', email: 'admin@storage.com', phone: '13800138000', password: '' },
  { id: 2, name: 'user01', type: 'user', email: 'user01@storage.com', phone: '13900139000', password: '' },
  { id: 3, name: 'user02', type: 'user', email: 'user02@storage.com', phone: '13700137000', password: '' },
  { id: 4, name: 'operator', type: 'admin', email: 'operator@storage.com', phone: '13600136000', password: '' },
  { id: 5, name: 'analyst', type: 'user', email: 'analyst@storage.com', phone: '13500135000', password: '' },
];

const policyData = [
  { id: 1, name: '最大连接数', value: '1000', desc: '系统允许的最大并发连接数', inputType: 'number' },
  { id: 2, name: '超时时间', value: '30', desc: '请求超时时间（秒）', inputType: 'number' },
  { id: 3, name: '缓存大小', value: '512', desc: '系统缓存大小（MB）', inputType: 'number' },
  { id: 4, name: '日志级别', value: 'INFO', desc: '系统日志记录级别', inputType: 'select', options: ['DEBUG', 'INFO', 'WARN', 'ERROR'] },
  { id: 5, name: '副本数量', value: '3', desc: '数据副本保存数量', inputType: 'number' },
  { id: 6, name: '压缩算法', value: 'LZ4', desc: '数据压缩算法类型', inputType: 'select', options: ['NONE', 'LZ4', 'SNAPPY', 'ZSTD'] },
  { id: 7, name: '最大存储容量', value: '10240', desc: '单节点最大存储容量（GB）', inputType: 'number' },
  { id: 8, name: '心跳间隔', value: '5', desc: '节点心跳检测间隔（秒）', inputType: 'number' },
];

const interfaceData = {
  rest: [
    { id: 'rest-1', name: '存储数据', url: '/api/v1/storage/save', type: 'POST', desc: '保存数据到存储引擎，支持多种数据类型',
      params: '{\n  "type": "relational",\n  "path": "/data/project/dataset",\n  "data": {...}\n}',
      response: '{\n  "code": 200,\n  "message": "success",\n  "data": { "id": "12345", "status": "saved" }\n}',
      code: 'curl -X POST http://localhost:8080/api/v1/storage/save \\\n  -H "Content-Type: application/json" \\\n  -d \'{ "type": "relational", "path": "/data/project/dataset", "data": {...} }\''
    },
    { id: 'rest-2', name: '访问数据', url: '/api/v1/storage/get', type: 'GET', desc: '根据路径获取存储的数据',
      params: '{\n  "path": "/data/project/dataset"\n}',
      response: '{\n  "code": 200,\n  "message": "success",\n  "data": {...}\n}',
      code: 'curl -X GET "http://localhost:8080/api/v1/storage/get?path=/data/project/dataset" \\\n  -H "Content-Type: application/json"'
    },
    { id: 'rest-3', name: '删除数据', url: '/api/v1/storage/delete', type: 'DELETE', desc: '根据路径删除存储的数据',
      params: '{\n  "path": "/data/project/dataset"\n}',
      response: '{\n  "code": 200,\n  "message": "success"\n}',
      code: 'curl -X DELETE "http://localhost:8080/api/v1/storage/delete?path=/data/project/dataset" \\\n  -H "Content-Type: application/json"'
    },
    { id: 'rest-4', name: '查询元数据', url: '/api/v1/metadata/query', type: 'GET', desc: '查询数据的元数据信息',
      params: '{\n  "path": "/data/project"\n}',
      response: '{\n  "code": 200,\n  "message": "success",\n  "data": { "nodes": [...], "links": [...] }\n}',
      code: 'curl -X GET "http://localhost:8080/api/v1/metadata/query?path=/data/project" \\\n  -H "Content-Type: application/json"'
    },
  ],
  java: [
    { id: 'java-1', name: 'SaveData', url: 'storage.StorageService/SaveData', type: 'gRPC', desc: 'gRPC保存数据接口',
      params: 'message SaveRequest {\n  string type = 1;\n  string path = 2;\n  bytes data = 3;\n}',
      response: 'message SaveResponse {\n  int32 code = 1;\n  string message = 2;\n  string id = 3;\n}',
      code: 'StorageServiceBlockingStub stub = StorageServiceGrpc.newBlockingStub(channel);\nSaveRequest request = SaveRequest.newBuilder()\n    .setType("relational")\n    .setPath("/data/project/dataset")\n    .setData(data)\n    .build();\nSaveResponse response = stub.saveData(request);'
    },
    { id: 'java-2', name: 'GetData', url: 'storage.StorageService/GetData', type: 'gRPC', desc: 'gRPC获取数据接口',
      params: 'message GetRequest {\n  string path = 1;\n}',
      response: 'message GetResponse {\n  int32 code = 1;\n  string message = 2;\n  bytes data = 3;\n}',
      code: 'StorageServiceBlockingStub stub = StorageServiceGrpc.newBlockingStub(channel);\nGetRequest request = GetRequest.newBuilder()\n    .setPath("/data/project/dataset")\n    .build();\nGetResponse response = stub.getData(request);'
    },
    { id: 'java-3', name: 'DeleteData', url: 'storage.StorageService/DeleteData', type: 'gRPC', desc: 'gRPC删除数据接口',
      params: 'message DeleteRequest {\n  string path = 1;\n}',
      response: 'message DeleteResponse {\n  int32 code = 1;\n  string message = 2;\n}',
      code: 'StorageServiceBlockingStub stub = StorageServiceGrpc.newBlockingStub(channel);\nDeleteRequest request = DeleteRequest.newBuilder()\n    .setPath("/data/project/dataset")\n    .build();\nDeleteResponse response = stub.deleteData(request);'
    },
  ],
  python: [
    { id: 'python-1', name: 'save_data', url: 'storage_pb2_grpc.StorageService.save_data', type: 'gRPC', desc: 'Python gRPC保存数据',
      params: 'message SaveRequest {\n  string type = 1;\n  string path = 2;\n  bytes data = 3;\n}',
      response: 'message SaveResponse {\n  int32 code = 1;\n  string message = 2;\n  string id = 3;\n}',
      code: 'import grpc\nimport storage_pb2\nimport storage_pb2_grpc\n\nchannel = grpc.insecure_channel("localhost:50051")\nstub = storage_pb2_grpc.StorageServiceStub(channel)\n\nrequest = storage_pb2.SaveRequest(\n    type="relational",\n    path="/data/project/dataset",\n    data=data\n)\nresponse = stub.save_data(request)'
    },
    { id: 'python-2', name: 'get_data', url: 'storage_pb2_grpc.StorageService.get_data', type: 'gRPC', desc: 'Python gRPC获取数据',
      params: 'message GetRequest {\n  string path = 1;\n}',
      response: 'message GetResponse {\n  int32 code = 1;\n  string message = 2;\n  bytes data = 3;\n}',
      code: 'import grpc\nimport storage_pb2\nimport storage_pb2_grpc\n\nchannel = grpc.insecure_channel("localhost:50051")\nstub = storage_pb2_grpc.StorageServiceStub(channel)\n\nrequest = storage_pb2.GetRequest(path="/data/project/dataset")\nresponse = stub.get_data(request)'
    },
    { id: 'python-3', name: 'delete_data', url: 'storage_pb2_grpc.StorageService.delete_data', type: 'gRPC', desc: 'Python gRPC删除数据',
      params: 'message DeleteRequest {\n  string path = 1;\n}',
      response: 'message DeleteResponse {\n  int32 code = 1;\n  string message = 2;\n}',
      code: 'import grpc\nimport storage_pb2\nimport storage_pb2_grpc\n\nchannel = grpc.insecure_channel("localhost:50051")\nstub = storage_pb2_grpc.StorageServiceStub(channel)\n\nrequest = storage_pb2.DeleteRequest(path="/data/project/dataset")\nresponse = stub.delete_data(request)'
    },
  ],
};

// ==================== 状态 ====================
let policyBackup = [];
let policyEditing = false;
let selectedFiles = [];
let currentInterfaceType = 'rest';
let metadataChart = null;
let topologyChart = null;
let clusterHeartbeatTimer = null;
let deployInProgress = false;

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

  // Rest/data port fields: only visible in add mode
  var restPortGroup = $('cluster-rest-port-group');
  if (restPortGroup) restPortGroup.style.display = isAdd ? '' : 'none';

  // Deploy/SSH fields: only visible in add mode
  const deployFields = $('cluster-deploy-fields');
  if (deployFields) {
    deployFields.style.display = isAdd ? '' : 'none';
  }

  if (isAdd) {
    $('cluster-ssh-user-input').value = '';
    $('cluster-ssh-password-input').value = '';
    $('cluster-deploy-dir-input').value = '~';
    $('cluster-port-input').value = '6888';
    $('cluster-rest-port-input').value = '7888';

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
    const sshPassword = $('cluster-ssh-password-input').value;
    const deployDirectory = $('cluster-deploy-dir-input').value.trim();
    const restPort = $('cluster-rest-port-input').value.trim();
    const zookeeperConnectionString = $('cluster-zk-input').value.trim();

    if (!name || !ip || !sshUsername || !sshPassword || !deployDirectory || !zookeeperConnectionString) {
      alert('请填写完整信息（SSH密码为必填）');
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
        sshPassword,
        deployDirectory,
        restPort: restPort || '7888',
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
    renderClusterTable();
    initClusterTopology();
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
  const sshPassword = $('cluster-delete-ssh-password').value;
  const deployDirectory = $('cluster-delete-deploy-dir').value.trim();

  if (!sshUsername || !sshPassword) {
    alert('请填写SSH凭据');
    return;
  }

  const btn = $('cluster-delete-modal-confirm');
  btn.disabled = true;
  btn.textContent = '停止中...';
  deployInProgress = true;
  $('cluster-delete-progress-wrap').classList.remove('hidden');

  try {
    const task = await stopClusterNode(nodeId, { sshUsername, sshPassword, deployDirectory });
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
  tbody.innerHTML = items.map(u => `
    <tr>
      <td>${u.name}</td>
      <td><span class="badge ${u.type === 'admin' ? 'badge-admin' : 'badge-user'}">${u.type === 'admin' ? '超级管理员' : '普通用户'}</span></td>
      <td title="${u.email}">${u.email}</td>
      <td>${u.phone}</td>
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
    btn.addEventListener('click', function () {
      const id = +this.dataset.id;
      if (confirm('确定要删除该用户吗？')) {
        const idx = userData.findIndex(u => u.id === id);
        if (idx >= 0) userData.splice(idx, 1);
        renderUserTable();
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

$('user-modal-save').addEventListener('click', () => {
  const name = $('user-name-input').value.trim();
  const password = $('user-password-input').value;
  const type = $('user-type-input').value;
  const email = $('user-email-input').value.trim();
  const phone = $('user-phone-input').value.trim();
  if (!name) { alert('请填写用户名'); return; }
  const editId = $('user-modal-save').dataset.editId;
  if (editId) {
    const user = userData.find(u => u.id === +editId);
    if (user) Object.assign(user, { name, type, email, phone });
  } else {
    const maxId = userData.reduce((m, u) => Math.max(m, u.id), 0);
    userData.push({ id: maxId + 1, name, type, email, phone, password });
  }
  hideModal('modal-user');
  renderUserTable();
});

// ==================== 策略管理 ====================
function renderPolicyTable() {
  const { items, page, totalPages, total } = paginate(policyData, paginationState.policy.page, PAGE_SIZE);
  paginationState.policy.page = page;
  const tbody = $('policy-table-body');
  tbody.innerHTML = items.map(p => {
    let inputHtml = '';
    if (p.inputType === 'select') {
      inputHtml = `<select class="input policy-edit-field" data-id="${p.id}" style="width:100px;padding:3px 6px;font-size:12px;">
        ${p.options.map(o => `<option value="${o}" ${o === p.value ? 'selected' : ''}>${o}</option>`).join('')}
      </select>`;
    } else {
      inputHtml = `<input type="number" class="input policy-edit-field" data-id="${p.id}" value="${p.value}" style="width:100px;padding:3px 6px;font-size:12px;">`;
    }
    return `<tr>
      <td>${p.name}</td>
      <td>
        <span class="policy-value">${p.value}</span>
        <span class="policy-input-wrap">${inputHtml}</span>
      </td>
      <td title="${p.desc}" style="max-width:150px">${p.desc}</td>
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

$('policy-save-btn').addEventListener('click', () => {
  document.querySelectorAll('.policy-edit-field').forEach(input => {
    const id = +input.dataset.id;
    const p = policyData.find(x => x.id === id);
    if (p) p.value = input.value;
  });
  exitPolicyEdit();
});

$('policy-cancel-btn').addEventListener('click', () => {
  policyBackup.forEach(backup => {
    const p = policyData.find(x => x.id === backup.id);
    if (p) p.value = backup.value;
  });
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
function initMetadataGraph() {
  const container = $('metadata-graph');
  if (!container) return;
  if (!metadataChart) {
    metadataChart = echarts.init(container);
  }
  const nodes = [
    { name: '根节点', symbolSize: 55, category: 0 },
    { name: '数据库A', symbolSize: 40, category: 0 },
    { name: '数据库B', symbolSize: 40, category: 0 },
    { name: '表-用户', symbolSize: 28, category: 1 },
    { name: '表-订单', symbolSize: 28, category: 1 },
    { name: '表-商品', symbolSize: 28, category: 1 },
    { name: '表-日志', symbolSize: 28, category: 1 },
    { name: '表-配置', symbolSize: 28, category: 1 },
    { name: '视图-统计', symbolSize: 22, category: 2 },
    { name: '视图-报表', symbolSize: 22, category: 2 },
    { name: '索引-用户ID', symbolSize: 18, category: 3 },
    { name: '索引-订单号', symbolSize: 18, category: 3 },
    { name: '索引-时间戳', symbolSize: 18, category: 3 },
  ];
  const links = [
    { source: '根节点', target: '数据库A' },
    { source: '根节点', target: '数据库B' },
    { source: '数据库A', target: '表-用户' },
    { source: '数据库A', target: '表-订单' },
    { source: '数据库A', target: '表-商品' },
    { source: '数据库B', target: '表-日志' },
    { source: '数据库B', target: '表-配置' },
    { source: '表-用户', target: '视图-统计' },
    { source: '表-订单', target: '视图-统计' },
    { source: '表-商品', target: '视图-报表' },
    { source: '表-日志', target: '视图-报表' },
    { source: '表-用户', target: '索引-用户ID' },
    { source: '表-订单', target: '索引-订单号' },
    { source: '表-日志', target: '索引-时间戳' },
  ];
  const categories = [{ name: '数据库' }, { name: '表' }, { name: '视图' }, { name: '索引' }];
  const colors = ['#00e68a', '#00cfff', '#ffa800', '#a78bfa'];
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(8,28,54,0.95)',
      borderColor: 'rgba(0,207,255,0.3)',
      textStyle: { color: '#cce4f5', fontSize: 11 },
    },
    legend: {
      data: categories.map(c => c.name),
      top: 4, left: 'center',
      textStyle: { color: '#4d7a99', fontSize: 10 },
      itemWidth: 10, itemHeight: 10,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes.map(n => ({
        ...n,
        itemStyle: { color: colors[n.category], shadowBlur: 6, shadowColor: colors[n.category] + '60' },
      })),
      links,
      categories,
      roam: true,
      draggable: true,
      label: { show: true, position: 'right', color: '#8cb8d0', fontSize: 10 },
      labelLayout: { hideOverlap: true },
      force: { repulsion: 180, gravity: 0.12, edgeLength: 90 },
      lineStyle: { color: 'rgba(100,160,200,0.2)', curveness: 0.2, width: 1.5 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
    }],
  };
  metadataChart.setOption(option, true);
}

function searchMetadataNode(keyword) {
  if (!metadataChart) { alert('请先构建网络'); return; }
  const option = metadataChart.getOption();
  const series = option.series[0];
  const colors = ['#00e68a', '#00cfff', '#ffa800', '#a78bfa'];

  // reset all
  series.data.forEach(n => {
    n.itemStyle = { color: colors[n.category], shadowBlur: 6, shadowColor: colors[n.category] + '60' };
  });
  series.links.forEach(l => {
    l.lineStyle = { color: 'rgba(100,160,200,0.2)', width: 1.5 };
  });

  const matched = series.data.find(n => n.name.toLowerCase().includes(keyword.toLowerCase()));
  if (matched) {
    matched.itemStyle = { color: '#ff4466', shadowBlur: 20, shadowColor: '#ff4466' };
    series.links.forEach(l => {
      if (l.source === matched.name || l.target === matched.name) {
        l.lineStyle = { color: '#ff4466', width: 3 };
      }
    });
    metadataChart.setOption(option);
  } else {
    metadataChart.setOption(option);
    alert('未找到匹配的节点');
  }
}

$('metadata-build-btn').addEventListener('click', () => initMetadataGraph());
$('metadata-search-btn').addEventListener('click', () => {
  const kw = $('metadata-search-input').value.trim();
  if (!kw) { alert('请输入节点名称'); return; }
  searchMetadataNode(kw);
});
$('metadata-search-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('metadata-search-btn').click();
});

// ==================== 存储服务 ====================
const uploadZone = $('storage-upload');
const fileInput = $('file-input');

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
    alert('存储成功！文件已保存到 ' + path);
    selectedFiles = [];
    renderFileList();
  } catch (e) {
    alert('存储失败: ' + e.message);
    console.error('Storage error:', e);
  } finally {
    btn.disabled = false;
    btn.textContent = '存储';
  }
});

// ==================== 访问服务 ====================
$('access-visit-btn').addEventListener('click', async () => {
  const path = $('access-path-input').value.trim();
  if (!path) { alert('请输入逻辑路径'); return; }
  const preview = $('access-preview');
  const btn = $('access-visit-btn');

  btn.disabled = true;
  btn.textContent = '访问中...';
  preview.innerHTML = '<div class="preview-placeholder">加载中...</div>';

  try {
    const response = await fetch(`${API_BASE}/access/data?logicalPath=${encodeURIComponent(path)}`);
    const result = await response.json();

    if (result.code !== 200 || !result.data) {
      $('access-data-type').textContent = '-';
      $('access-data-size').textContent = '-';
      $('access-data-time').textContent = '-';
      preview.innerHTML = '<div class="preview-placeholder">未找到该路径对应的数据</div>';
      return;
    }

    const item = result.data;
    const dataType = item.dataType;
    const previewData = item.previewData;

    // Show metadata
    const typeLabels = {
      timeseries: '时序数据', relational: '关系数据', image: '图像数据',
      document: '文档数据', keyvalue: '键值数据', directory: '目录'
    };
    $('access-data-type').textContent = typeLabels[dataType] || dataType;
    $('access-data-size').textContent = dataType === 'directory' ? '-' : formatFileSize(item.fileSize);
    $('access-data-time').textContent = item.createTime || '-';

    // Render preview based on data type
    if (dataType === 'directory') {
      renderDirectoryListing(preview, previewData, item.logicalPath);
    } else if (dataType === 'image') {
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
  } catch (e) {
    console.error('Access error:', e);
    preview.innerHTML = `<div class="preview-placeholder">访问失败: ${e.message}</div>`;
    $('access-data-type').textContent = '-';
    $('access-data-size').textContent = '-';
    $('access-data-time').textContent = '-';
  } finally {
    btn.disabled = false;
    btn.textContent = '访问';
  }
});

function renderImagePreview(container, previewData, meta) {
  if (!previewData || !previewData.base64) {
    container.innerHTML = '<div class="preview-placeholder">无法加载图像数据</div>';
    return;
  }
  const format = (meta.fileFormat || 'png').toLowerCase();
  const mimeMap = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', bmp: 'image/bmp' };
  const mime = mimeMap[format] || 'image/png';
  container.innerHTML = `<div style="text-align:center;padding:12px;overflow:auto;max-height:100%;">
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
  const maxRows = Math.min(rows.length, 50);

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
  if (rows.length > maxRows) {
    html += `<p style="color:var(--text-dim);font-size:11px;padding:4px 8px;">显示前 ${maxRows} 行，共 ${previewData.totalRows} 行</p>`;
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
  container.innerHTML = `<pre class="code-block" style="white-space:pre-wrap;font-size:11px;overflow:auto;max-height:100%;margin:0;padding:8px;">${escapeHtml(displayContent)}</pre>`;
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
    const extra = child.fileName ? ` · ${escapeHtml(child.fileName)}` : '';
    const timeInfo = child.createTime ? ` · ${escapeHtml(child.createTime)}` : '';

    html += `<div class="dir-listing-item" data-path="${pathDisplay}" 
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

  // Bind click events – navigate into the child path
  container.querySelectorAll('.dir-listing-item').forEach(item => {
    item.addEventListener('click', function() {
      const childPath = this.getAttribute('data-path');
      $('access-path-input').value = childPath;
      $('access-visit-btn').click();
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
  const path = $('access-path-input').value.trim();
  if (!path) { alert('请先访问数据'); return; }

  const btn = $('access-download-btn');
  btn.disabled = true;
  btn.textContent = '下载中...';

  try {
    const response = await fetch(`${API_BASE}/access/download?logicalPath=${encodeURIComponent(path)}`);
    if (!response.ok) {
      throw new Error('下载失败: HTTP ' + response.status);
    }

    // Get filename from Content-Disposition header
    const disposition = response.headers.get('Content-Disposition');
    let fileName = 'download';
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=["']?([^"';\n]*)["']?/);
      if (match && match[1]) {
        fileName = decodeURIComponent(match[1]);
      }
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
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
  tbody.innerHTML = data.map(item => `
    <tr>
      <td>${item.name}</td>
      <td><span class="mono">${item.url}</span></td>
      <td title="${item.desc}">${item.desc}</td>
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
  metadataChart?.resize();
});

// ==================== 初始化 ====================
async function init() {
  updateTime();
  setInterval(updateTime, 1000);

  try {
    await loadClusterNodes();
  } catch (e) {
    console.error('Load cluster nodes failed:', e);
    alert('加载节点列表失败: ' + e.message);
  }

  renderClusterTable();
  renderUserTable();
  renderPolicyTable();
  renderInterfaceTable();

  // 延迟初始化图表（等DOM渲染完成）
  requestAnimationFrame(() => {
    initClusterTopology();
    initMetadataGraph();
  });

  if (clusterHeartbeatTimer) {
    clearInterval(clusterHeartbeatTimer);
  }
  clusterHeartbeatTimer = setInterval(() => {
    if (!deployInProgress) refreshClusterView(true);
  }, 15000);

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
