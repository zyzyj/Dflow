#!/usr/bin/env python3
"""MCP over HTTP 的端到端测试：认证、工具调用、密钥过滤、绑定范围。"""
import json
import os
import sys
import urllib.request

BASE = f"http://127.0.0.1:{os.environ.get('DFLOW_E2E_PORT', '4173')}"
ok = True


def call(method, path, body=None, token=None, headers=None, base=None):
    url = (base or BASE) + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept', 'application/json, text/event-stream')
    if token:
        req.add_header('X-DFlow-Token', token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode('utf-8', 'replace')
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def rpc(token, method, params=None, rpc_id=1):
    payload = {'jsonrpc': '2.0', 'id': rpc_id, 'method': method}
    if params is not None:
        payload['params'] = params
    status, text = call('POST', '/mcp', payload, token=token)
    # 可能返回 SSE 或纯 JSON
    if 'data:' in text:
        for line in text.splitlines():
            if line.startswith('data:'):
                text = line[5:].strip()
                break
    try:
        return status, json.loads(text)
    except Exception:
        return status, {'raw': text[:300]}


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


def main():
    print("=== 1. 生成令牌并启用 ===")
    status, text = call('POST', '/api/api-access/token')
    token = json.loads(text)['token']
    check('生成了令牌', len(token) >= 32, f'{len(token)} 字符')

    status, text = call('PUT', '/api/account', {'apiEnabled': True})
    account = json.loads(text)['account']
    check('对外接口已启用', account['apiEnabled'] is True)
    check('默认要求令牌', account['apiRequireToken'] is True)

    print("=== 2. 无令牌被拒 ===")
    status, body = rpc(None, 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'no-token', 'version': '1'},
    })
    check('返回 401', status == 401, f'HTTP {status}')

    print("=== 3. 错误令牌被拒 ===")
    status, body = rpc('wrong-token-abcdef', 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'bad-token', 'version': '1'},
    })
    check('返回 401', status == 401, f'HTTP {status}')

    print("=== 4. 正确令牌可初始化 ===")
    status, body = rpc(token, 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'good-token', 'version': '1'},
    })
    check('初始化成功', status == 200 and 'result' in body, f'HTTP {status}')
    server_info = body.get('result', {}).get('serverInfo', {})
    check('报告服务名', server_info.get('name') == 'dflow-http', str(server_info))

    print("=== 5. 工具列表含新增工具 ===")
    status, body = rpc(token, 'tools/list', {}, rpc_id=2)
    names = [t['name'] for t in body.get('result', {}).get('tools', [])]
    check('tools/list 成功', status == 200 and bool(names), f'{len(names)} 个工具')
    for want in ['reverse_image', 'list_prompts', 'get_prompt', 'get_image',
                 'list_pending', 'complete_pending', 'claim_next_pending']:
        check(f'含 {want}', want in names)

    print("=== 6. list_prompts 可调用 ===")
    status, body = rpc(token, 'tools/call', {'name': 'list_prompts', 'arguments': {}}, rpc_id=3)
    result = body.get('result', {})
    content = result.get('content', [{}])
    check('调用成功', status == 200 and not result.get('isError'), str(result.get('isError')))
    if content and content[0].get('text'):
        parsed = json.loads(content[0]['text'])
        check('返回 total 字段', 'total' in parsed, f"total={parsed.get('total')}")

    print("=== 7. 本机访问不过滤密钥 ===")
    status, text = call('GET', '/api/state')
    state = json.loads(text)
    check('本机能看到真实配置', state['account'].get('apiToken') not in (None, '__redacted__'),
          '本机 = 完整数据')

    print("=== 8. /api/api-access 连接信息 ===")
    status, text = call('GET', '/api/api-access')
    check('返回 200', status == 200, f'HTTP {status}')
    info = json.loads(text)
    check('含 mcp 端点', any('/mcp' in e['url'] for e in info.get('endpoints', [])))
    check('含客户端配置示例', 'mcpServers' in info.get('clientConfig', {}))

    print("=== 9. 关闭后立即失效 ===")
    call('PUT', '/api/account', {'apiEnabled': False})
    status, body = rpc(token, 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'after-off', 'version': '1'},
    })
    check('关闭后返回 404（对外接口未启用）', status == 404, f'HTTP {status}')

    print("=== 10. 免令牌模式可调用 ===")
    call('PUT', '/api/account', {'apiEnabled': True, 'apiRequireToken': False})
    status, body = rpc(None, 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'no-token-mode', 'version': '1'},
    })
    check('无令牌也能初始化', status == 200 and 'result' in body, f'HTTP {status}')

    print("=== 11. 恢复默认（启用 + 要求令牌）===")
    call('PUT', '/api/account', {'apiEnabled': False, 'apiRequireToken': True})

    print()
    print('✅ 全部通过' if ok else '❌ 有失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
