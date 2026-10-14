#!/usr/bin/env python3
"""
验证 reverse_image 的两种模式。

模式 A（已配置视觉模型）：DFlow 自己识别，直接返回提示词。
模式 B（未配置）：返回图片 + 完整的反推/扩写说明，由调用方的模型完成。
两者都必须能用——模式 B 是「零配置也可用」的保证。
"""
import json
import os
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = os.environ.get('DFLOW_E2E_PORT', '4173')
MOCK_PORT = int(os.environ.get('DFLOW_E2E_MOCK_PORT', '4189'))
BASE = f'http://127.0.0.1:{PORT}'
ok = True

PNG_B64 = (
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
)


class MockHandler(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        payload = json.loads(self.rfile.read(length) or b'{}')
        MockHandler.calls.append(payload)
        body = json.dumps({
            'choices': [{'message': {'role': 'assistant', 'content': '1girl, solo, mocked reverse'}}],
        }).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith('/models'):
            body = json.dumps({'data': [{'id': 'mock-vision'}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept', 'application/json, text/event-stream')
    if token:
        req.add_header('X-DFlow-Token', token)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return resp.status, resp.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def rpc(method, params=None, rpc_id=1, token=None):
    payload = {'jsonrpc': '2.0', 'id': rpc_id, 'method': method}
    if params is not None:
        payload['params'] = params
    status, text = call('POST', '/mcp', payload, token=token)
    if 'data:' in text:
        for line in text.splitlines():
            if line.startswith('data:'):
                text = line[5:].strip()
                break
    try:
        return status, json.loads(text)
    except Exception:
        return status, {'raw': text[:300]}


def call_tool(name, args, token=None):
    status, body = rpc('tools/call', {'name': name, 'arguments': args}, token=token)
    result = body.get('result', {})
    return status, result


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


def main():
    print("=== 准备 mock 模型服务 ===")
    server = HTTPServer(('127.0.0.1', MOCK_PORT), MockHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    check('mock 已监听', True, f'127.0.0.1:{MOCK_PORT}')

    status, text = call('POST', '/api/api-access/token')
    token = json.loads(text)['token']
    call('PUT', '/api/account', {'apiEnabled': True, 'apiRequireToken': True}, token=None)

    print("=== 模式 B：未配置视觉模型 ===")
    call('PUT', '/api/account', {'aiBaseUrl': '', 'aiModel': ''})
    status, result = call_tool('reverse_image', {'base64': PNG_B64}, token=token)
    check('调用成功（不是错误）', status == 200 and not result.get('isError'),
          str(result.get('isError')))
    content = result.get('content', [])
    kinds = [c.get('type') for c in content]
    check('返回了图片', 'image' in kinds, str(kinds))
    check('返回了说明文本', 'text' in kinds)
    if 'text' in kinds:
        guidance = json.loads(next(c['text'] for c in content if c['type'] == 'text'))
        check('标记为 caller-model 模式', guidance.get('mode') == 'caller-model',
              guidance.get('mode'))
        check('含说明原因', 'reason' in guidance, guidance.get('reason', '')[:40])
        check('含反推预设正文', bool(guidance.get('reverseInstructions')),
              f"{len(guidance.get('reverseInstructions') or '')} 字符")
        check('含扩写预设正文', bool(guidance.get('expansionInstructions')),
              f"{len(guidance.get('expansionInstructions') or '')} 字符")
        check('含两阶段顺序', len(guidance.get('sequence', [])) >= 3,
              f"{len(guidance.get('sequence', []))} 步")
    check('未调用模型（不花钱）', len(MockHandler.calls) == 0, f"{len(MockHandler.calls)} 次")

    print("=== 模式 A：已配置视觉模型 ===")
    call('PUT', '/api/account', {
        'aiBaseUrl': f'127.0.0.1:{MOCK_PORT}',
        'aiApiKey': 'k',
        'aiModel': 'mock-vision',
    })
    before = len(MockHandler.calls)
    status, result = call_tool('reverse_image', {'base64': PNG_B64}, token=token)
    check('调用成功', status == 200 and not result.get('isError'), str(result.get('isError')))
    content = result.get('content', [])
    text_part = next((c['text'] for c in content if c.get('type') == 'text'), '{}')
    payload = json.loads(text_part)
    check('标记为 built-in 模式', payload.get('mode') == 'built-in', payload.get('mode'))
    check('返回了提示词', payload.get('prompt') == '1girl, solo, mocked reverse',
          repr(payload.get('prompt')))
    check('报告了模型名', payload.get('model') == 'mock-vision', payload.get('model'))
    check('确实调用了模型', len(MockHandler.calls) - before == 1,
          f"{len(MockHandler.calls) - before} 次")

    payload_calls = MockHandler.calls[-1]
    image_part = next(
        c for m in payload_calls.get('messages', []) for c in (m.get('content') or [])
        if isinstance(c, dict) and c.get('type') == 'image_url'
    )
    check('模型收到 base64 图片',
          image_part['image_url']['url'].startswith('data:image/png;base64,'),
          image_part['image_url']['url'][:40])
    instruction = next(
        c['text'] for m in payload_calls.get('messages', []) for c in (m.get('content') or [])
        if isinstance(c, dict) and c.get('type') == 'text'
    )
    check('指令含反推要求', '反推要求' in instruction)
    check('指令含扩写要求', '扩写要求' in instruction)

    print("=== 自定义预设生效 ===")
    status, result = call_tool('reverse_image', {
        'base64': PNG_B64,
        'customInstruction': '只输出英文标签',
    }, token=token)
    last_instruction = next(
        c['text'] for m in MockHandler.calls[-1].get('messages', [])
        for c in (m.get('content') or [])
        if isinstance(c, dict) and c.get('type') == 'text'
    )
    check('额外要求被写入指令', '只输出英文标签' in last_instruction)

    print("=== 无效输入被拒 ===")
    status, result = call_tool('reverse_image', {'base64': 'bm90LWFuLWltYWdl'}, token=token)
    check('非图片数据被拒', result.get('isError') is True,
          str(result.get('content', [{}])[0].get('text', ''))[:50])

    print("=== 清理 ===")
    call('PUT', '/api/account', {
        'apiEnabled': False, 'aiBaseUrl': '', 'aiModel': '', 'aiApiKey': '',
    })
    server.shutdown()
    print()
    print('✅ 全部通过' if ok else '❌ 有失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
