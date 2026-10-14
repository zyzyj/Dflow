#!/usr/bin/env python3
"""
内置 AI 反推的端到端测试：起一个假的 OpenAI 兼容服务，走完整链路。

为什么需要它：真实视觉模型要花钱、要网络、结果还不确定，无法当回归依据。
这里验证的是 DFlow 自己的逻辑——配置保存、模型列表拉取、单张反推写回、
批量串行处理、失败不中断、以及各类错误提示。
"""
import json
import os
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

DFLOW = f"http://127.0.0.1:{os.environ.get('DFLOW_E2E_PORT', '4173')}"
MOCK_PORT = int(os.environ.get('DFLOW_E2E_MOCK_PORT', '4188'))

ok = True

# ---- 假模型服务 ------------------------------------------------------------


class MockHandler(BaseHTTPRequestHandler):
    calls = []
    mode = 'normal'          # normal | fail_once | bad_key | no_models | slow
    stream = True

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.endswith('/models'):
            if MockHandler.mode == 'bad_key':
                self.send_response(401)
                self.end_headers()
                self.wfile.write(b'{"error":"invalid api key"}')
                return
            body = json.dumps({
                'object': 'list',
                'data': [
                    {'id': 'mock-vision-large', 'object': 'model'},
                    {'id': 'mock-vision-mini', 'object': 'model'},
                    {'id': 'mock-text-only', 'object': 'model'},
                ],
            }).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        payload = json.loads(self.rfile.read(length) or b'{}')
        MockHandler.calls.append(payload)

        # 第二次调用失败，用来验证失败处理
        if MockHandler.mode == 'fail_once' and len(MockHandler.calls) == 2:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b'{"error":"upstream boom"}')
            return

        has_image = any(
            isinstance(m.get('content'), list) and
            any(c.get('type') == 'image_url' for c in m['content'])
            for m in payload.get('messages', [])
        )
        text = '1girl, solo, mock prompt' if has_image else 'no image provided'
        n = len(MockHandler.calls)

        if MockHandler.stream:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for piece in [text[:8], text[8:]]:
                chunk = {'choices': [{'delta': {'content': piece}}]}
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
                self.wfile.flush()
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        else:
            body = json.dumps({
                'choices': [{'message': {'role': 'assistant', 'content': text}}],
            }).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)


def start_mock():
    server = HTTPServer(('127.0.0.1', MOCK_PORT), MockHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


# ---- 工具 ------------------------------------------------------------------


def req(method, path, body=None, base=DFLOW, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw[:200]}


def get(path, base=DFLOW, timeout=60):
    with urllib.request.urlopen(base + path, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


# ---- 测试 ------------------------------------------------------------------

TEST_IDS = ['999000101', '999000102', '999000103']


def cleanup():
    state = get('/api/state')
    keep = [f for f in state['favorites'] if str(f.get('id')) not in TEST_IDS]
    req('PUT', '/api/favorites', {'favorites': keep})


def seed_card(card_id):
    """造一张带本地缓存的待反推卡片（缓存用一个真实的小 PNG 占位）。"""
    cache_dir = os.path.join(os.environ.get('DFLOW_DATA_DIR', '/root/dflow-data'),
                             'favorites', 'pending')
    os.makedirs(cache_dir, exist_ok=True)
    # 1x1 PNG，足以通过缓存有效性检查与 base64 编码
    png = bytes.fromhex(
        '89504e470d0a1a0a0000000d4948445200000001000000010806000000'
        '1f15c4890000000d4944415478da63f8cfc0000003010100b8f5f5b300'
        '00000049454e44ae426082'
    )
    filename = f'{card_id}.png'
    with open(os.path.join(cache_dir, filename), 'wb') as fh:
        fh.write(png)
    return {
        'id': int(card_id),
        'source': 'danbooru',
        'sourceId': card_id,
        'sourceImageUrl': 'https://cdn.donmai.us/original/00/00/x.jpg',
        'preview_file_url': 'https://cdn.donmai.us/180x180/00/00/x.jpg',
        'file_url': 'https://cdn.donmai.us/original/00/00/x.jpg',
        'image_width': 100,
        'image_height': 100,
        'rating': 'g',
        'folder': 'pending',
        'cacheFile': filename,
        'cacheStatus': 'ready',
        'autoEnabled': True,
        'preset': '通用扩写',
        'reversePreset': '通用反推',
    }


def main():
    print("=== 0. 起假模型服务 ===")
    server = start_mock()
    check('mock 服务已监听', True, f'127.0.0.1:{MOCK_PORT}')

    print("=== 1. 保存 AI 配置 ===")
    code, body = req('PUT', '/api/account', {
        'aiBaseUrl': f'127.0.0.1:{MOCK_PORT}',
        'aiApiKey': 'test-key',
        'aiModel': 'mock-vision-large',
    })
    check('PUT /api/account', code == 200, f'HTTP {code}')
    acc = body.get('account', {})
    check('地址已保存', acc.get('aiBaseUrl', '').endswith(str(MOCK_PORT)), acc.get('aiBaseUrl'))
    check('模型已保存', acc.get('aiModel') == 'mock-vision-large')
    check('密钥已保存', bool(acc.get('aiApiKey')))

    print("=== 2. 拉取模型列表 ===")
    code, body = req('POST', '/api/ai/models', {'aiBaseUrl': f'127.0.0.1:{MOCK_PORT}', 'aiApiKey': 'test-key'})
    check('POST /api/ai/models', code == 200, f'HTTP {code}')
    models = body.get('models', [])
    check('返回 3 个模型', len(models) == 3, str(models))
    check('地址原样使用（不再自动补 /v1）', str(body.get('baseUrl', '')).endswith(str(MOCK_PORT)) and not str(body.get('baseUrl','')).endswith('/v1'), body.get('baseUrl'))
    check('返回实际请求地址', str(body.get('requestedUrl','')).endswith('/models'), body.get('requestedUrl'))

    print("=== 3. 密钥错误时的提示 ===")
    MockHandler.mode = 'bad_key'
    code, body = req('POST', '/api/ai/models', {'aiBaseUrl': f'127.0.0.1:{MOCK_PORT}', 'aiApiKey': 'wrong'})
    check('返回可读错误', code == 502 and '密钥' in body.get('error', ''), body.get('error'))
    MockHandler.mode = 'normal'

    print("=== 4. 准备待反推卡片 ===")
    cleanup()
    state = get('/api/state')
    seeds = [seed_card(cid) for cid in TEST_IDS]
    others = [f for f in state['favorites'] if str(f.get('id')) not in TEST_IDS]
    code, body = req('PUT', '/api/favorites', {'favorites': others + seeds})
    check('写入 3 张待反推卡片', code == 200, f'HTTP {code}')
    kept = [f for f in body.get('favorites', []) if str(f.get('id')) in TEST_IDS]
    check('服务端保留 3 张', len(kept) == 3, f'保留 {len(kept)} 张')

    print("=== 5. 单张反推 ===")
    code, body = req('POST', f'/api/ai/reverse/{TEST_IDS[0]}', {}, timeout=90)
    check('POST /api/ai/reverse', code == 200, f'HTTP {code} {body.get("error","")}')
    if code == 200:
        fav = body.get('favorite', {})
        check('提示词已写回', fav.get('prompt') == '1girl, solo, mock prompt', repr(fav.get('prompt')))
        check('已移到有词区', fav.get('folder') == 'worded', fav.get('folder'))
        check('状态为 success', fav.get('reverseStatus') == 'success')
        check('记录写入时间', bool(fav.get('promptWrittenAt')))

    print("=== 6. 发送给模型的内容是否正确 ===")
    last = MockHandler.calls[-1]
    content = last['messages'][0]['content']
    types = [c.get('type') for c in content]
    check('包含图片与文本', 'image_url' in types and 'text' in types, str(types))
    img_url = next(c['image_url']['url'] for c in content if c['type'] == 'image_url')
    check('图片是 base64 data URL', img_url.startswith('data:image/png;base64,'), img_url[:40])
    check('请求了流式', last.get('stream') is True)
    check('指定了模型', last.get('model') == 'mock-vision-large')
    prompt_text = next(c['text'] for c in content if c['type'] == 'text')
    check('提示词含反推要求', '反推要求' in prompt_text)
    check('提示词含扩写要求', '扩写要求' in prompt_text)

    print("=== 7. 非流式响应也能解析 ===")
    MockHandler.stream = False
    code, body = req('POST', f'/api/ai/reverse/{TEST_IDS[1]}', {}, timeout=90)
    check('非流式返回同样成功', code == 200 and body.get('favorite', {}).get('prompt') == '1girl, solo, mock prompt',
          f'HTTP {code} {body.get("error","")}')
    MockHandler.stream = True

    print("=== 8. 队列里没有可反推的卡片时 ===")
    # 先把剩下的卡片都反推掉，制造空队列（前面的步骤已经处理了 2 张）。
    for cid in TEST_IDS:
        st = get('/api/state')
        card = next((f for f in st['favorites'] if str(f.get('id')) == cid), None)
        if card and card.get('folder') == 'pending':
            req('POST', f'/api/ai/reverse/{cid}', {}, timeout=90)
    code, body = req('POST', '/api/ai/reverse-batch', {})
    check('给出明确拒绝', code == 409, f"HTTP {code} {body.get('error','')[:50]}")

    print("=== 9. 清理并测批量 ===")
    cleanup()
    state = get('/api/state')
    seeds = [seed_card(cid) for cid in TEST_IDS]
    req('PUT', '/api/favorites', {'favorites': [f for f in state['favorites'] if str(f.get('id')) not in TEST_IDS] + seeds})

    before = len(MockHandler.calls)
    code, body = req('POST', '/api/ai/reverse-batch', {}, timeout=30)
    check('批量接口返回 202', code == 202, f"HTTP {code} {body.get('error','')}")
    check('报告总数 3', body.get('total') == 3, str(body.get('total')))

    # 等批跑完
    for _ in range(60):
        st = get('/api/ai/batch')
        if not st.get('running'):
            break
        time.sleep(1)
    check('批量已结束', not st.get('running'))
    check('3 张全部成功', st.get('done') == 3 and st.get('failed') == 0, f"done={st.get('done')} failed={st.get('failed')}")
    check('实际调用了 3 次模型', len(MockHandler.calls) - before == 3, f'{len(MockHandler.calls) - before} 次')

    after = get('/api/state')
    moved = [f for f in after['favorites'] if str(f.get('id')) in TEST_IDS]
    check('3 张都移到了有词区', all(f.get('folder') == 'worded' for f in moved),
          str([f.get('folder') for f in moved]))

    print("=== 10. 单张失败不中断其余 ===")
    cleanup()
    state = get('/api/state')
    seeds = [seed_card(cid) for cid in TEST_IDS]
    req('PUT', '/api/favorites', {'favorites': [f for f in state['favorites'] if str(f.get('id')) not in TEST_IDS] + seeds})
    MockHandler.calls.clear()
    MockHandler.mode = 'fail_once'   # 第 2 次调用失败

    code, body = req('POST', '/api/ai/reverse-batch', {}, timeout=30)
    check('批量启动', code == 202, f'HTTP {code}')
    for _ in range(60):
        st = get('/api/ai/batch')
        if not st.get('running'):
            break
        time.sleep(1)
    check('继续跑完了剩余卡片（2 成功 1 失败）',
          st.get('done') == 2 and st.get('failed') == 1,
          f"done={st.get('done')} failed={st.get('failed')}")
    MockHandler.mode = 'normal'

    print("=== 11. 未配置时的提示 ===")
    req('PUT', '/api/account', {'aiBaseUrl': '', 'aiModel': ''})
    cleanup()
    state = get('/api/state')
    seeds = [seed_card(TEST_IDS[0])]
    req('PUT', '/api/favorites', {'favorites': [f for f in state['favorites'] if str(f.get('id')) not in TEST_IDS] + seeds})
    code, body = req('POST', f'/api/ai/reverse/{TEST_IDS[0]}', {})
    check('提示先去配置', code == 409 and 'AI' in body.get('error', ''), f"HTTP {code} {body.get('error','')}")

    print("=== 清理 ===")
    cleanup()
    left = [f for f in get('/api/state')['favorites'] if str(f.get('id')) in TEST_IDS]
    check('测试数据已清除', not left)
    # 还原缓存文件
    for cid in TEST_IDS:
        for folder in ['pending', 'worded']:
            p = os.path.join(os.environ.get('DFLOW_DATA_DIR', '/root/dflow-data'),
                             'favorites', folder, f'{cid}.png')
            if os.path.exists(p):
                os.remove(p)

    server.shutdown()
    print()
    print('✅ 全部通过' if ok else '❌ 有失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
