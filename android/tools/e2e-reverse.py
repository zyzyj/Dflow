#!/usr/bin/env python3
"""
端到端验证 App 会发起的每个请求，确保原生实现的操作序列在真实服务上成立。

不依赖外网：画站不可达时也能跑。因为本机常访问不了 Danbooru/Pixiv，
依赖网络的测试会时通时不通，无法作为回归依据。这里直接构造本地卡片，
验证「收藏 -> 待反推 -> 队列 -> 写提示词 -> 归档」这条服务端状态机的行为，
这正是原生 UI 要调用的全部接口。
"""
import json, os, urllib.request, time

BASE = f"http://127.0.0.1:{os.environ.get('DFLOW_E2E_PORT', '4173')}"


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8', 'replace'))
        except Exception:
            return e.code, {}


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


ok = True


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


# 用一个不会与真实帖子冲突的 id 空间，测试后清理。
TEST_ID = '999000001'

print("=== 1. 收藏一张站源卡片（App 用纯数字 id）===")
state = get('/api/state')
fav = {
    'id': int(TEST_ID),
    'source': 'danbooru',
    'sourceId': TEST_ID,
    'sourceImageUrl': 'https://cdn.donmai.us/original/00/00/test.jpg',
    'preview_file_url': 'https://cdn.donmai.us/180x180/00/00/test.jpg',
    'large_file_url': '',
    'file_url': 'https://cdn.donmai.us/original/00/00/test.jpg',
    'image_width': 800, 'image_height': 1200,
    'rating': 'g', 'folder': 'original',
}
others = [f for f in state['favorites'] if str(f.get('id')) != TEST_ID]
code, body = req('PUT', '/api/favorites', {'favorites': others + [fav]})
check('PUT /api/favorites', code == 200, f'HTTP {code}')
saved = [f for f in body.get('favorites', []) if str(f.get('id')) == TEST_ID]
check('服务端保留（纯数字 id 未被 cleanFavorite 丢弃）', bool(saved))

print("=== 2. 移到待反推（「AI」按钮）===")
code, body = req('PATCH', f'/api/favorites/{TEST_ID}', {'folder': 'pending'})
check('PATCH folder=pending', code == 200, f'HTTP {code}')
it = body.get('favorite', {})
check('folder=pending', it.get('folder') == 'pending', f"folder={it.get('folder')}")
check('分配了队列号', (it.get('queueOrder') or 0) > 0, f"queueOrder={it.get('queueOrder')}")
check('reverseStatus=idle（等缓存）', it.get('reverseStatus') == 'idle', f"status={it.get('reverseStatus')}")

print("=== 3. 队列接口（收藏页要显示的数量）===")
q = get('/api/reverse/queue')
check('队列包含这张', any(str(x.get('id')) == TEST_ID for x in q.get('items', [])),
      f"total={q['total']} eligible={q['eligible']}")

print("=== 4. 直推（无 Agent 时）===")
code, body = req('POST', f'/api/reverse/{TEST_ID}/direct', {})
sessions = get('/api/mcp/sessions')
if not sessions:
    # 无 Agent 时，服务端会先卡在缓存检查上；两者都是「不能直推」的正当理由。
    err = body.get('error', '')
    check('拒绝并说明原因', code in (409, 404) and err,
          f'HTTP {code} {err[:60]}')
else:
    check('有 Agent 在线', code in (200, 202), f'HTTP {code}')

print("=== 5. 写提示词（应自动移到有词区）===")
code, body = req('PATCH', f'/api/favorites/{TEST_ID}', {'prompt': '1girl, solo, e2e test'})
check('PATCH prompt', code == 200, f'HTTP {code}')
it = body.get('favorite', {})
check('自动移到 worded', it.get('folder') == 'worded', f"folder={it.get('folder')}")
check('reverseStatus=success', it.get('reverseStatus') == 'success', f"status={it.get('reverseStatus')}")
check('记录了写入时间', bool(it.get('promptWrittenAt')), f"promptWrittenAt={it.get('promptWrittenAt')}")

print("=== 6. 提示词不能为空（UI 应阻止）===")
code, body = req('PATCH', f'/api/favorites/{TEST_ID}', {'prompt': '   '})
check('空提示词被拒', code == 400, f"HTTP {code} {body.get('error','')[:40]}")

print("=== 7. 归档到已完成（缓存未就绪时应拒绝）===")
code, body = req('PATCH', f'/api/favorites/{TEST_ID}', {'folder': 'completed'})
if code == 200:
    check('归档成功（缓存恰好就绪）', True)
else:
    check('缓存未就绪时拒绝并给出原因', code == 409, f"HTTP {code} {body.get('error','')}")

print("=== 8. 移回原始收藏（恢复按钮）===")
code, body = req('PATCH', f'/api/favorites/{TEST_ID}', {'folder': 'original'})
check('PATCH folder=original', code == 200, f'HTTP {code}')
check('folder=original', body.get('favorite', {}).get('folder') == 'original')

print("=== 9. 网络自检接口（设置页用）===")
nt = get('/api/network/test?timeout=3000')
check('返回逐站结果', len(nt.get('results', [])) == 3,
      f"proxy={nt.get('proxy') or '无'} " + ', '.join(f"{r['name']}={'OK' if r['ok'] else 'FAIL'}" for r in nt['results']))

print("=== 10. Agent 列表接口（收藏页提示条用）===")
check('返回数组', isinstance(sessions, list), f'{len(sessions)} 个 Agent')

print("=== 清理 ===")
state = get('/api/state')
req('PUT', '/api/favorites', {'favorites': [f for f in state['favorites'] if str(f.get('id')) != TEST_ID]})
left = [f for f in get('/api/state')['favorites'] if str(f.get('id')) == TEST_ID]
check('测试数据已清除', not left)

print()
print('✅ 全部通过' if ok else '❌ 有失败项')
raise SystemExit(0 if ok else 1)
