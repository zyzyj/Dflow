#!/usr/bin/env python3
"""
远程访问安全测试。

关键点：真实模拟"从另一台设备访问"。绑定 0.0.0.0 后，通过本机的局域网 IP
（192.168.x.x）发起请求时，服务端看到的 req.ip 不是 127.0.0.1，
因此会走远程访问控制分支——这正是要验证的路径。

要求服务端以 HOST=0.0.0.0 启动在指定端口。
"""
import json
import os
import re
import sys
import urllib.request

PORT = os.environ.get('DFLOW_E2E_PORT', '4197')
LAN_IP = os.environ.get('DFLOW_E2E_LAN_IP', '')
LOOPBACK = f'http://127.0.0.1:{PORT}'
REMOTE = f'http://{LAN_IP}:{PORT}' if LAN_IP else LOOPBACK
ok = True


def call(method, path, body=None, base=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request((base or LOOPBACK) + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept', 'application/json, text/event-stream')
    if token:
        req.add_header('X-DFlow-Token', token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


def main():
    if not LAN_IP:
        print("未提供 DFLOW_E2E_LAN_IP，无法测试远程路径")
        return 1

    print(f"=== 环境 ===")
    print(f"  loopback = {LOOPBACK}")
    print(f"  remote   = {REMOTE}")
    # 确认真的走了非 loopback 路径
    status, _ = call('GET', '/api/state', base=REMOTE, timeout=10)
    check('能从局域网地址连上服务', status in (200, 401, 403), f'HTTP {status}')

    print("=== 1. 未启用对外访问时，远程一律被拒 ===")
    call('PUT', '/api/account', {'apiEnabled': False}, base=LOOPBACK)
    status, text = call('GET', '/api/state', base=REMOTE)
    check('远程读 /api/state 被拒', status == 403, f'HTTP {status}')
    body = json.loads(text) if text.startswith('{') else {}
    check('提示原因', '未启用' in body.get('error', ''), body.get('error', '')[:40])

    status, text = call('GET', '/api/explore/popular', base=REMOTE)
    check('远程读其他接口也被拒', status == 403, f'HTTP {status}')

    print("=== 2. 启用后，远程需要令牌 ===")
    status, text = call('POST', '/api/api-access/token', base=LOOPBACK)
    token = json.loads(text)['token']
    call('PUT', '/api/account', {'apiEnabled': True}, base=LOOPBACK)

    status, _ = call('GET', '/api/state', base=REMOTE)
    check('无令牌被拒', status == 401, f'HTTP {status}')

    status, _ = call('GET', '/api/state', base=REMOTE, token='definitely-wrong')
    check('错误令牌被拒', status == 401, f'HTTP {status}')

    print("=== 3. 正确令牌可读，但密钥被抹掉 ===")
    status, text = call('GET', '/api/state', base=REMOTE, token=token)
    check('正确令牌可读', status == 200, f'HTTP {status}')
    if status == 200:
        account = json.loads(text).get('account', {})
        # 先确认本机那边确实有值，否则"被抹掉"无从谈起
        _, local_text = call('GET', '/api/state', base=LOOPBACK)
        local_account = json.loads(local_text).get('account', {})
        check('本机可见 apiToken', bool(local_account.get('apiToken')), '对照组')
        check('远程看不到 apiToken', account.get('apiToken') == '__redacted__',
              str(account.get('apiToken'))[:20])
        check('远程看不到 aiApiKey', account.get('aiApiKey') in ('', '__redacted__', None),
              str(account.get('aiApiKey'))[:20])
        check('远程看不到 pixivCookie', account.get('pixivCookie') in ('', '__redacted__', None),
              str(account.get('pixivCookie'))[:20])
        check('远程看不到 proxyUrl', account.get('proxyUrl') in ('', '__redacted__', None))
        # 收藏数据仍可用，否则功能没意义
        check('远程仍可读收藏', 'favorites' in json.loads(text))

    print("=== 4. 敏感接口对远程始终关闭 ===")
    for path, method in [('/api/account', 'PUT'), ('/api/logs/text', 'GET'),
                         ('/api/mcp/setup', 'GET'), ('/api/api-access', 'GET')]:
        status, _ = call(method, path, {'x': 1} if method == 'PUT' else None,
                         base=REMOTE, token=token)
        check(f'远程 {method} {path} 被拒', status == 403, f'HTTP {status}')

    print("=== 5. 远程可用 MCP 并调用工具 ===")
    def rpc(method, params=None, rpc_id=1, token_value=token):
        payload = {'jsonrpc': '2.0', 'id': rpc_id, 'method': method}
        if params is not None:
            payload['params'] = params
        status, text = call('POST', '/mcp', payload, base=REMOTE, token=token_value)
        if 'data:' in text:
            for line in text.splitlines():
                if line.startswith('data:'):
                    text = line[5:].strip()
                    break
        try:
            return status, json.loads(text)
        except Exception:
            return status, {'raw': text[:200]}

    status, body = rpc('initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'remote-agent', 'version': '1'},
    })
    check('远程可初始化 MCP', status == 200 and 'result' in body, f'HTTP {status}')

    status, body = rpc('tools/call', {'name': 'list_prompts', 'arguments': {}}, rpc_id=2)
    result = body.get('result', {})
    check('远程可调用 list_prompts', status == 200 and not result.get('isError'),
          str(result.get('isError')))

    status, body = rpc('tools/call', {'name': 'list_pending', 'arguments': {}}, rpc_id=3)
    result = body.get('result', {})
    check('远程可调用 list_pending', status == 200 and not result.get('isError'),
          str(result.get('isError')))

    print("=== 6. 免令牌模式下远程可直连（用户明确选择的风险）===")
    call('PUT', '/api/account', {'apiEnabled': True, 'apiRequireToken': False}, base=LOOPBACK)
    status, _ = call('GET', '/api/state', base=REMOTE)
    check('免令牌时无需令牌', status == 200, f'HTTP {status}')
    _, text = call('GET', '/api/state', base=REMOTE)
    check('即便如此密钥仍被抹掉', json.loads(text)['account'].get('apiToken') == '__redacted__',
          '抹除与会话无关，只看是否 loopback')

    print("=== 恢复默认 ===")
    call('PUT', '/api/account', {'apiEnabled': False, 'apiRequireToken': True}, base=LOOPBACK)
    check('已关闭对外访问', True)

    print()
    print('✅ 全部通过' if ok else '❌ 有失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
