# 批量添加数据源测试

使用Python脚本批量添加filesystem数据源，用于测试系统在大量数据源情况下的性能和稳定性。

## 📁 文件说明

- `quick_test.py` - 快速测试（10个数据源）
- `batch_add_datasource.py` - 批量测试（700个数据源）
- `README.md` - 本文档
## 🚀 快速开始

### 1. 安装依赖

```bash
pip install requests
```

### 2. 修改配置

编辑测试文件，修改顶部的配置参数：

**quick_test.py:**
```python
API_URL = "http://localhost:8080/storage/sources"
TEST_COUNT = 10
DUMMY_DIR = "/tmp/test-data"  # 改为实际存在的目录
```

**batch_add_datasource.py:**
```python
API_URL = "http://localhost:8080/storage/sources"
TOTAL_COUNT = 700
DUMMY_DIR = "/tmp/test-data"  # 改为实际存在的目录
```

### 3. 运行测试

```bash
# 快速测试（10个数据源）
python quick_test.py

# 批量测试（700个数据源）
python batch_add_datasource.py
```

## ⚙️ 配置参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `API_URL` | 后端API地址 | `http://localhost:8080/storage/sources` |
| `TOTAL_COUNT` | 添加数据源数量 | 700 |
| `SOURCE_TYPE` | 数据源类型 | `filesystem` |
| `SOURCE_IP` | 数据源IP | `127.0.0.1` |
| `SOURCE_PORT_START` | 起始端口号 | 6669 |
| `DUMMY_DIR` | 测试目录路径 | `/tmp/test-data` |
| `IGINX_PORT` | IGinX端口 | 6888 |
| `REQUEST_DELAY` | 请求间隔（秒） | 0.1 |
| `TIMEOUT` | 请求超时（秒） | 60 |

## 📈 输出示例

```
==================================================
批量添加 Filesystem 数据源测试
==================================================
目标数量: 700
API地址: http://localhost:8080/storage/sources
数据源类型: filesystem
==================================================

[1/700] ✓ 成功 (HTTP 200, Port: 6670)
[2/700] ✓ 成功 (HTTP 200, Port: 6671)
[3/700] ✓ 成功 (HTTP 200, Port: 6672)
...

进度: 10/700 (1.4%) | 成功: 10 | 失败: 0

...

==================================================
测试完成
==================================================
总数量: 700
成功: 698
失败: 2
成功率: 99.71%
总耗时: 75.30 秒
平均耗时: 107.57 毫秒/个
==================================================
```

## 📝 测试说明

### 数据源配置

程序会为每个数据源使用不同的端口号（从 `SOURCE_PORT_START` 开始递增），模拟不同的数据源：

- 数据源 #1: 端口 6670
- 数据源 #2: 端口 6671
- 数据源 #3: 端口 6672
- ...
- 数据源 #700: 端口 7369

### 性能优化

- 每个请求之间有 0.1秒 的延迟，避免请求过快
- 连接超时：60秒
- 每10个请求打印一次进度
- 彩色输出，便于查看结果

## 📋 注意事项

1. **确保后端服务已启动**：测试前确保 ScaleStore 后端服务正在运行
2. **修改测试目录**：将 `DUMMY_DIR` 修改为实际存在的测试目录
3. **端口冲突**：确保使用的端口范围（6670-7369）不会与其他服务冲突
4. **数据库容量**：700个数据源会在数据库中创建大量记录，确保数据库有足够空间
5. **性能影响**：大量数据源可能影响系统性能，建议在测试环境运行

## 🔧 故障排查

### 连接失败

```
[1/10] ✗ 失败 (Status: CONNECTION_ERROR, Port: 6670)
```

**解决方法：**
- 检查 `API_URL` 是否正确
- 确认后端服务是否正在运行
- 检查防火墙设置

### 请求超时

```
[1/10] ✗ 失败 (Status: TIMEOUT, Port: 6670)
```

**解决方法：**
- 增加 `TIMEOUT` 的值（如改为120秒）
- 检查网络连接
- 检查后端服务是否响应缓慢

### Python依赖问题

```
ModuleNotFoundError: No module named 'requests'
```

**解决方法：**
```bash
pip install requests
# 或
pip3 install requests
```

### 端口冲突

如果端口范围与其他服务冲突，修改 `SOURCE_PORT_START` 的值：

```python
SOURCE_PORT_START = 7000  # 改为其他起始端口
```

## 🧹 清理测试数据

测试完成后，如需清理测试数据，可以：

1. **通过 Web UI 手动删除数据源**
2. **直接清理数据库中的数据源记录**
3. **重新初始化数据库**

## 🎨 自定义测试

### 修改添加数量

```python
TOTAL_COUNT = 100  # 改为100个
```

### 修改请求延迟

```python
REQUEST_DELAY = 0.5  # 改为0.5秒
```

### 使用相同的数据源配置

如果想重复添加相同的数据源（而不是递增端口），修改 `add_datasource` 函数：

```python
def add_datasource(index):
    port = SOURCE_PORT_START  # 移除 + index，使用固定端口
    # ...
```

## 💡 扩展建议

如果需要更专业的测试，可以考虑：

1. **并发测试** - 使用 `concurrent.futures` 实现多线程并发
2. **不同类型** - 测试 MySQL、PostgreSQL、IoTDB 等其他类型的数据源
3. **压力测试** - 使用 Locust 进行分布式压力测试
4. **性能监控** - 记录每个请求的响应时间，生成性能报告

## 📚 相关资源

- [Python requests 文档](https://requests.readthedocs.io/)
- [ScaleStore 项目文档](../README.md)
