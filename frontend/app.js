// ===== 可扩展存储引擎 - 可视化大屏 =====
import * as echarts from 'echarts';

// ==================== 数据 ====================
const clusterData = [
  { id: 1, name: 'node-master-01', ip: '192.168.1.100', port: '8080', desc: '主节点 - 集群管理与协调' },
  { id: 2, name: 'node-worker-01', ip: '192.168.1.101', port: '8080', desc: '工作节点1 - 数据存储' },
  { id: 3, name: 'node-worker-02', ip: '192.168.1.102', port: '8080', desc: '工作节点2 - 数据存储' },
  { id: 4, name: 'node-worker-03', ip: '192.168.1.103', port: '8081', desc: '工作节点3 - 计算处理' },
  { id: 5, name: 'node-worker-04', ip: '192.168.1.104', port: '8081', desc: '工作节点4 - 计算处理' },
  { id: 6, name: 'node-backup-01', ip: '192.168.1.110', port: '8080', desc: '备份节点 - 数据冗余' },
  { id: 7, name: 'node-gateway-01', ip: '192.168.1.200', port: '9090', desc: '网关节点 - 请求路由' },
  { id: 8, name: 'node-monitor-01', ip: '192.168.1.201', port: '9091', desc: '监控节点 - 状态监测' },
];

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
  const tbody = $('cluster-table-body');
  tbody.innerHTML = items.map(n => `
    <tr>
      <td title="${n.name}">${n.name}</td>
      <td>${n.ip}</td>
      <td>${n.port}</td>
      <td>
        <button class="btn-text-action view cluster-view-detail-btn" data-id="${n.id}">查看</button>
        <button class="btn-text-action edit cluster-set-btn" data-id="${n.id}">设置</button>
        <button class="btn-text-action delete cluster-delete-btn" data-id="${n.id}">删除</button>
      </td>
    </tr>
  `).join('');
  renderPagination('cluster-pagination', 'cluster', totalPages, total, renderClusterTable);
  bindClusterRowEvents();
}

function bindClusterRowEvents() {
  document.querySelectorAll('.cluster-view-detail-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const node = clusterData.find(n => n.id === +this.dataset.id);
      if (!node) return;
      openClusterModal('查看详情', node, true);
    });
  });
  document.querySelectorAll('.cluster-set-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const node = clusterData.find(n => n.id === +this.dataset.id);
      if (!node) return;
      openClusterModal('编辑节点', node, false);
    });
  });
  document.querySelectorAll('.cluster-delete-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      const id = +this.dataset.id;
      if (confirm('确定要删除该节点吗？')) {
        const idx = clusterData.findIndex(n => n.id === id);
        if (idx >= 0) clusterData.splice(idx, 1);
        renderClusterTable();
        initClusterTopology();
      }
    });
  });
}

function openClusterModal(title, data, readonly) {
  $('cluster-modal-title').textContent = title;
  $('cluster-name-input').value = data ? data.name : '';
  $('cluster-ip-input').value = data ? data.ip : '';
  $('cluster-port-input').value = data ? data.port : '';
  $('cluster-desc-input').value = data ? data.desc : '';
  const inputs = ['cluster-name-input', 'cluster-ip-input', 'cluster-port-input', 'cluster-desc-input'];
  inputs.forEach(id => $(id).disabled = readonly);
  $('cluster-modal-save').classList.toggle('hidden', readonly);
  $('cluster-modal-save').dataset.editId = data ? data.id : '';
  showModal('modal-cluster');
}

$('cluster-add-btn').addEventListener('click', () => openClusterModal('添加节点', null, false));

$('cluster-modal-cancel').addEventListener('click', () => hideModal('modal-cluster'));
$('cluster-modal-close-x').addEventListener('click', () => hideModal('modal-cluster'));

$('cluster-modal-save').addEventListener('click', () => {
  const name = $('cluster-name-input').value.trim();
  const ip = $('cluster-ip-input').value.trim();
  const port = $('cluster-port-input').value.trim();
  const desc = $('cluster-desc-input').value.trim();
  if (!name || !ip || !port) { alert('请填写完整信息'); return; }
  const editId = $('cluster-modal-save').dataset.editId;
  if (editId) {
    const node = clusterData.find(n => n.id === +editId);
    if (node) { Object.assign(node, { name, ip, port, desc }); }
  } else {
    const maxId = clusterData.reduce((m, n) => Math.max(m, n.id), 0);
    clusterData.push({ id: maxId + 1, name, ip, port, desc });
  }
  hideModal('modal-cluster');
  renderClusterTable();
  initClusterTopology();
});

// ==================== 拓扑图 ====================
function initClusterTopology() {
  const container = $('cluster-topology');
  if (!container) return;
  if (!topologyChart) {
    topologyChart = echarts.init(container);
  }
  const masters = clusterData.filter(n => n.name.includes('master'));
  const others = clusterData.filter(n => !n.name.includes('master'));
  const nodes = clusterData.map((n, i) => ({
    name: n.name,
    symbolSize: n.name.includes('master') ? 45 : 30,
    category: n.name.includes('master') ? 0 : (n.name.includes('worker') ? 1 : 2),
    itemStyle: {
      color: n.name.includes('master') ? '#00e68a' : (n.name.includes('worker') ? '#00cfff' : '#ffa800'),
      shadowBlur: 8,
      shadowColor: n.name.includes('master') ? 'rgba(0,230,138,0.4)' : 'rgba(0,207,255,0.3)',
    },
  }));
  const links = [];
  const masterName = masters.length > 0 ? masters[0].name : null;
  if (masterName) {
    others.forEach(n => links.push({ source: masterName, target: n.name }));
  }
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(8,28,54,0.95)',
      borderColor: 'rgba(0,207,255,0.3)',
      textStyle: { color: '#cce4f5', fontSize: 11 },
    },
    legend: {
      data: ['主节点', '工作节点', '其他节点'],
      top: 4, right: 4,
      textStyle: { color: '#4d7a99', fontSize: 10 },
      itemWidth: 10, itemHeight: 10,
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      links,
      categories: [{ name: '主节点' }, { name: '工作节点' }, { name: '其他节点' }],
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
fileInput.addEventListener('change', e => handleFiles(Array.from(e.target.files)));

function handleFiles(files) {
  selectedFiles = [...selectedFiles, ...files];
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

$('storage-save-btn').addEventListener('click', () => {
  const type = $('storage-type-select').value;
  const path = $('storage-path-input').value.trim();
  if (!path) { alert('请输入逻辑路径'); return; }
  if (selectedFiles.length === 0) { alert('请选择要上传的文件'); return; }
  console.log('存储数据:', { type, path, files: selectedFiles.map(f => f.name) });
  alert('存储成功！');
  selectedFiles = [];
  renderFileList();
});

// ==================== 访问服务 ====================
$('access-visit-btn').addEventListener('click', () => {
  const path = $('access-path-input').value.trim();
  if (!path) { alert('请输入逻辑路径'); return; }
  const preview = $('access-preview');

  if (path.includes('img') || path.includes('image') || path.includes('图像')) {
    $('access-data-type').textContent = '图像数据';
    $('access-data-size').textContent = '2.3 MB';
    $('access-data-time').textContent = '2026-02-25 10:30:00';
    preview.innerHTML = `<div style="text-align:center;padding:16px;">
      <div style="width:200px;height:140px;margin:0 auto;background:linear-gradient(135deg,#0a2e4a,#1a4a6a);border-radius:8px;display:flex;align-items:center;justify-content:center;border:1px solid rgba(0,180,255,0.2);">
        <span style="font-size:48px;opacity:0.4;">🖼️</span>
      </div>
      <p style="color:var(--text-dim);margin-top:8px;font-size:11px;">示例图像预览</p>
    </div>`;
  } else if (path.includes('rel') || path.includes('关系')) {
    $('access-data-type').textContent = '关系数据';
    $('access-data-size').textContent = '156 KB';
    $('access-data-time').textContent = '2026-02-25 09:15:00';
    preview.innerHTML = `<div class="table-wrapper"><table style="font-size:11px;">
      <thead><tr><th>ID</th><th>名称</th><th>类型</th><th>数值</th><th>时间</th></tr></thead>
      <tbody>
        <tr><td>1</td><td>数据项A</td><td>类型1</td><td>123.45</td><td>2026-02-25 08:00</td></tr>
        <tr><td>2</td><td>数据项B</td><td>类型2</td><td>678.90</td><td>2026-02-25 09:00</td></tr>
        <tr><td>3</td><td>数据项C</td><td>类型1</td><td>234.56</td><td>2026-02-25 10:00</td></tr>
      </tbody></table></div>`;
  } else if (path.includes('ts') || path.includes('时序')) {
    $('access-data-type').textContent = '时序数据';
    $('access-data-size').textContent = '89 KB';
    $('access-data-time').textContent = '2026-02-25 11:00:00';
    preview.innerHTML = `<div class="table-wrapper"><table style="font-size:11px;">
      <thead><tr><th>时间戳</th><th>设备ID</th><th>温度</th><th>湿度</th><th>状态</th></tr></thead>
      <tbody>
        <tr><td>2026-02-25 08:00</td><td>DEV-001</td><td>23.5°C</td><td>65%</td><td>正常</td></tr>
        <tr><td>2026-02-25 08:05</td><td>DEV-001</td><td>23.8°C</td><td>64%</td><td>正常</td></tr>
        <tr><td>2026-02-25 08:10</td><td>DEV-002</td><td>25.1°C</td><td>70%</td><td>警告</td></tr>
      </tbody></table></div>`;
  } else if (path.includes('doc') || path.includes('文档')) {
    $('access-data-type').textContent = '文档数据';
    $('access-data-size').textContent = '45 KB';
    $('access-data-time').textContent = '2026-02-25 11:20:00';
    preview.innerHTML = `<pre class="code-block" style="white-space:pre-wrap;font-size:11px;">这是一份示例文档内容。\n\n标题：可扩展存储引擎技术文档\n作者：系统管理员\n日期：2026-02-25\n\n本文档描述了存储引擎的核心架构设计，包括分布式数据存储、多模态数据管理、以及高可用集群部署方案等内容。</pre>`;
  } else if (path.includes('kv') || path.includes('键值')) {
    $('access-data-type').textContent = '键值数据';
    $('access-data-size').textContent = '12 KB';
    $('access-data-time').textContent = '2026-02-25 12:00:00';
    preview.innerHTML = `<pre class="code-block" style="white-space:pre-wrap;font-size:11px;">{\n  "config.max_connections": "1000",\n  "config.timeout": "30s",\n  "config.cache_size": "512MB",\n  "status.node_count": "8",\n  "status.uptime": "72h",\n  "version": "2.1.0"\n}</pre>`;
  } else {
    $('access-data-type').textContent = '文本数据';
    $('access-data-size').textContent = '28 KB';
    $('access-data-time').textContent = '2026-02-25 12:30:00';
    preview.innerHTML = `<pre class="code-block" style="white-space:pre-wrap;font-size:11px;">{\n  "id": "12345",\n  "type": "document",\n  "content": "示例数据内容...",\n  "metadata": {\n    "author": "system",\n    "created": "2026-02-25T12:30:00Z"\n  }\n}</pre>`;
  }
});

$('access-download-btn').addEventListener('click', () => {
  const path = $('access-path-input').value.trim();
  if (!path) { alert('请先访问数据'); return; }
  alert('数据下载已开始...');
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
function init() {
  updateTime();
  setInterval(updateTime, 1000);

  renderClusterTable();
  renderUserTable();
  renderPolicyTable();
  renderInterfaceTable();

  // 延迟初始化图表（等DOM渲染完成）
  requestAnimationFrame(() => {
    initClusterTopology();
    initMetadataGraph();
  });

  $('online-nodes').textContent = clusterData.length;
}

init();
console.log('可扩展存储引擎可视化大屏初始化完成');
