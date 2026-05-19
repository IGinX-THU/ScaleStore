#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
批量添加 filesystem 数据源测试脚本
使用 SSH 方式计算大小（由用户配置 SSH 账号）
"""

import time

import requests

# ==================== 配置参数 ====================
API_URL = "http://localhost:8080/storage/sources"
TOTAL_COUNT = 700
SOURCE_TYPE = "filesystem"
SOURCE_IP = "127.0.0.1"
SOURCE_PORT_START = 6669
DUMMY_DIR = "/tmp/test-data"
IGINX_PORT = 6888

SIZE_CALCULATION_STRATEGY = "ssh"  # 使用 SSH 方式计算大小
SSH_USERNAME = ""  # 必填
SSH_PASSWORD = ""  # 必填
SSH_PORT = 22

REQUEST_DELAY = 0.1  # 请求间隔（秒）
TIMEOUT = 60  # 请求超时（秒）

# ==================== 颜色输出 ====================
class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    END = '\033[0m'

def print_colored(text, color):
    """彩色打印"""
    print(f"{color}{text}{Colors.END}")

def build_payload(port):
    payload = {
        "sourceType": SOURCE_TYPE,
        "ip": SOURCE_IP,
        "port": port,
        "dummyDir": DUMMY_DIR,
        "iginxPort": IGINX_PORT,
        "sizeCalculationStrategy": SIZE_CALCULATION_STRATEGY,
    }

    if SIZE_CALCULATION_STRATEGY == "ssh":
        payload["sshUsername"] = SSH_USERNAME
        payload["sshPassword"] = SSH_PASSWORD
        payload["sshPort"] = SSH_PORT

    return payload


def add_datasource(index):
    """添加单个数据源"""
    port = SOURCE_PORT_START + index
    payload = build_payload(port)
    
    try:
        response = requests.post(
            API_URL,
            json=payload,
            timeout=TIMEOUT,
            headers={"Content-Type": "application/json"}
        )
        
        if response.status_code in [200, 201]:
            return True, response.status_code, port
        else:
            return False, response.status_code, port
            
    except requests.exceptions.Timeout:
        return False, "TIMEOUT", port
    except requests.exceptions.ConnectionError:
        return False, "CONNECTION_ERROR", port
    except Exception as e:
        return False, str(e), port

def validate_config():
    if SIZE_CALCULATION_STRATEGY == "ssh":
        if not SSH_USERNAME:
            raise ValueError("SSH_USERNAME 不能为空")
        if not SSH_PASSWORD:
            raise ValueError("SSH_PASSWORD 不能为空")


def main():
    """主函数"""
    validate_config()
    print("=" * 50)
    print("批量添加 Filesystem 数据源测试")
    print("=" * 50)
    print(f"目标数量: {TOTAL_COUNT}")
    print(f"API地址: {API_URL}")
    print(f"数据源类型: {SOURCE_TYPE}")
    print(f"大小计算方式: {SIZE_CALCULATION_STRATEGY}")
    print("=" * 50)
    print()
    
    success_count = 0
    fail_count = 0
    start_time = time.time()
    
    # 批量添加
    for i in range(1, TOTAL_COUNT + 1):
        success, status, port = add_datasource(i)
        
        if success:
            success_count += 1
            print_colored(
                f"[{i}/{TOTAL_COUNT}] ✓ 成功 (HTTP {status}, Port: {port})",
                Colors.GREEN
            )
        else:
            fail_count += 1
            print_colored(
                f"[{i}/{TOTAL_COUNT}] ✗ 失败 (Status: {status}, Port: {port})",
                Colors.RED
            )
        
        # 每10个打印进度
        if i % 10 == 0:
            progress = (i * 100.0) / TOTAL_COUNT
            print()
            print_colored(
                f"进度: {i}/{TOTAL_COUNT} ({progress:.1f}%) | "
                f"成功: {success_count} | 失败: {fail_count}",
                Colors.YELLOW
            )
            print()
        
        # 延迟
        time.sleep(REQUEST_DELAY)
    
    # 统计结果
    end_time = time.time()
    duration = end_time - start_time
    success_rate = (success_count * 100.0) / TOTAL_COUNT
    avg_time = (duration * 1000.0) / TOTAL_COUNT
    
    print()
    print("=" * 50)
    print("测试完成")
    print("=" * 50)
    print(f"总数量: {TOTAL_COUNT}")
    print(f"成功: {success_count}")
    print(f"失败: {fail_count}")
    print(f"成功率: {success_rate:.2f}%")
    print(f"总耗时: {duration:.2f} 秒")
    print(f"平均耗时: {avg_time:.2f} 毫秒/个")
    print("=" * 50)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n测试被用户中断")
    except Exception as e:
        print(f"\n\n发生错误: {e}")
