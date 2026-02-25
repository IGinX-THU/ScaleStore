# 可扩展存储引擎 - 可视化平台

这是一个基于数据可视化大屏设计的存储引擎管理界面。

## 技术栈

- **HTML5** - 页面结构
- **Tailwind CSS** - 样式框架（通过CDN引入，支持离线部署）
- **ECharts** - 数据可视化图表库
- **D3.js** - 力导向图和拓扑图
- **JavaScript (ES6+)** - 交互逻辑

## 设计特点

### 视觉设计
- **深色主题** - 背景色 #0F172A，符合数据可视化大屏风格
- **玻璃态效果** - 半透明面板，带模糊效果和微光边框
- **绿色强调色** - #22C55E，用于主要操作按钮和状态指示
- **字体** - Fira Sans（UI文本）+ Fira Code（代码显示）

### 布局结构
- **固定顶部状态栏** - 显示系统状态和实时时间
- **左侧导航栏** - 功能模块切换，带激活状态指示
- **主内容区域** - 分块式卡片布局，清晰展示各功能模块

## 功能模块

### 1. 配置服务

#### 1.1 集群管理
- ✅ 节点列表表格（节点名、IP、Port）
- ✅ 节点拓扑图（ECharts力导向图）
- ✅ 节点操作（添加、查看、编辑、删除）
- ✅ 分页支持

**DOM ID:**
- `#cluster-add-btn` - 添加节点按钮
- `.cluster-view-detail-btn` - 查看详情按钮
- `.cluster-set-btn` - 设置按钮
- `.cluster-delete-btn` - 删除按钮

#### 1.2 用户管理
- ✅ 用户列表表格（用户名、类型、邮箱、电话）
- ✅ 用户类型标签（超级管理员/普通用户）
- ✅ 用户操作（新增、查看、编辑、删除）
- ✅ 分页支持

**DOM ID:**
- `#user-add-btn` - 新增用户按钮
- `.user-view-detail-btn` - 查看详情按钮
- `.user-set-btn` - 编辑按钮
- `.user-delete-btn` - 删除按钮

#### 1.3 策略管理
- ✅ 策略配置表格（策略项、数值、描述）
- ✅ 编辑模式切换
- ✅ 数据备份与恢复
- ✅ 支持数字输入和下拉选择

**DOM ID:**
- `#policy-edit-btn` - 设置按钮
- `#policy-save-btn` - 保存按钮
- `#policy-cancel-btn` - 取消按钮

### 2. 元数据服务
- ✅ 元数据图谱（ECharts力导向图）
- ✅ 节点关系可视化
- ✅ 搜索功能（高亮节点及相关边）
- ✅ 支持拖拽、缩放

**DOM ID:**
- `#metadata-build-btn` - 构建网络按钮
- `#metadata-search-input` - 搜索输入框
- `#metadata-search-btn` - 查找按钮

### 3. 存储服务
- ✅ 数据类型选择（关系/时序/文档/图像/键值）
- ✅ 逻辑路径输入
- ✅ 文件上传（拖拽上传 + 点击上传）
- ✅ 多文件支持
- ✅ 存储统计图表（ECharts饼图）

**DOM ID:**
- `#storage-type-select` - 数据类型选择
- `#storage-path-input` - 逻辑路径输入
- `#storage-save-btn` - 存储按钮
- `#storage-upload` - 文件上传区域

### 4. 访问服务
- ✅ 逻辑路径输入
- ✅ 数据预览（根据类型自动识别）
- ✅ 多种预览模式（图像/表格/文本）
- ✅ 数据下载功能

**DOM ID:**
- `#access-path-input` - 逻辑路径输入
- `#access-visit-btn` - 访问按钮
- `#access-download-btn` - 下载按钮

### 5. 多样化接口服务
- ✅ 接口类型切换（Restful API / Java gRPC / Python gRPC）
- ✅ 接口列表表格
- ✅ 接口详情抽屉
- ✅ 示例代码下载

**DOM ID:**
- `#interface-btn-rest` - Restful API按钮
- `#interface-btn-java` - Java gRPC按钮
- `#interface-btn-python` - Python gRPC按钮
- `.interface-view-btn` - 查看详情按钮
- `#interface-download-btn` - 下载示例代码按钮

## 如何使用

### 直接打开
```bash
# 在浏览器中打开 index.html
start index.html
```

### 使用本地服务器
```bash
# 使用 Python 启动简单服务器
python -m http.server 8000

# 或使用 Node.js 的 http-server
npx http-server -p 8000
```

然后在浏览器访问 `http://localhost:8000`

## 离线部署支持

当前版本使用CDN加载资源，要实现完全离线部署，需要下载以下资源到本地：

### 1. 下载 Tailwind CSS
```bash
# 下载 Tailwind CSS Play CDN
# 注意：实际项目建议使用 Tailwind CLI 构建
```

### 2. 下载 ECharts
```bash
npm install echarts
# 然后将 node_modules/echarts/dist/echarts.min.js 复制到项目目录
```

### 3. 下载 D3.js
```bash
npm install d3
# 然后将 node_modules/d3/dist/d3.min.js 复制到项目目录
```

### 4. 下载 Google Fonts
访问以下URL并下载字体文件：
```
https://fonts.google.com/download?family=Fira+Code
https://fonts.google.com/download?family=Fira+Sans
```

将字体文件放在 `fonts/` 目录，并修改CSS中的 `@import` 为本地路径。

## 交互说明

### 集群管理
1. 点击"添加节点"打开对话框
2. 填写节点信息并保存
3. 在表格中点击"查看"/"设置"/"删除"进行操作
4. 拓扑图支持拖拽和缩放

### 用户管理
1. 点击"新增用户"打开对话框
2. 填写用户信息并选择类型
3. 在表格中进行查看、编辑、删除操作

### 策略管理
1. 点击"设置"进入编辑模式
2. 修改表格中的数值
3. 点击"保存"提交或"取消"放弃修改

### 元数据服务
1. 点击"构建网络"生成元数据图谱
2. 在搜索框输入节点名称
3. 点击"查找"高亮匹配的节点和边

### 存储服务
1. 选择数据类型
2. 输入逻辑路径
3. 拖拽或点击上传文件
4. 点击"存储"提交

### 访问服务
1. 输入逻辑路径
2. 点击"访问"获取数据预览
3. 点击"点击下载数据"下载到本地

### 接口服务
1. 点击顶部按钮切换接口类型
2. 在表格中点击"查看详情"
3. 在抽屉中查看完整接口信息
4. 点击"下载示例代码"获取代码文件

## 自动化测试支持

所有交互元素都已添加DOM ID，方便进行自动化测试。

## 浏览器兼容性

- ✅ Chrome/Edge 90+
- ✅ Firefox 88+
- ✅ Safari 14+

## 注意事项

1. 当前为前端演示版本，所有数据操作仅在控制台打印
2. 实际使用需要连接后端API
3. 图表数据为模拟数据，需要替换为真实数据源
4. 建议在大屏显示器上查看以获得最佳体验

## 后续优化建议

- [ ] 实现完整的API集成
- [ ] 添加数据刷新机制
- [ ] 增加错误处理和加载状态
- [ ] 实现真实的分页逻辑
- [ ] 添加权限控制
- [ ] 优化移动端响应式布局
- [ ] 添加深色/浅色主题切换
- [ ] 实现数据导出功能
