#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
快速测试 - 添加10个数据源
用于快速验证API是否正常工作
"""

import requests
import time

API_URL = "http://localhost:8080/storage/sources"
TEST_COUNT = 10
SOURCE_TYPE = "filesystem"
SOURCE_IP = "127.0.0.1"
SOURCE_PORT_START = 6669
DUMMY_DIR = "C:/Users/18219/Downloads/test-A.1"
IGINX_PORT = 6888

def main():
    print(f"快速测试 - 添加 {TEST_COUNT} 个数据源")
    print("=" * 50)
    print()
    
    success = 0
    fail = 0
    
    for i in range(1, TEST_COUNT + 1):
        port = SOURCE_PORT_START + i
        payload = {
            "sourceType": SOURCE_TYPE,
            "ip": SOURCE_IP,
            "port": port,
            "dummyDir": DUMMY_DIR,
            "iginxPort": IGINX_PORT
        }
        
        try:
            response = requests.post(API_URL, json=payload, timeout=30)
            if response.status_code in [200, 201]:
                success += 1
                print(f"[{i}/{TEST_COUNT}] ✓ 成功 (Port: {port})")
            else:
                fail += 1
                print(f"[{i}/{TEST_COUNT}] ✗ 失败 (HTTP {response.status_code})")
        except Exception as e:
            fail += 1
            print(f"[{i}/{TEST_COUNT}] ✗ 异常: {e}")
        
        time.sleep(0.2)
    
    print()
    print("=" * 50)
    print(f"测试完成: 成功 {success} / 失败 {fail}")
    print("=" * 50)

if __name__ == "__main__":
    main()
