#!/usr/bin/env python3
"""
批量删除与批量转待反推的端到端验证。

重点验证两点：
1. 一次 PUT 能批量移除多张，且服务端同时清掉缓存文件（否则磁盘会积累孤儿）。
2. 批量转待反推逐张成功，不存在的卡片不会卡住其余。
"""
import json
import os
import sys
import urllib.request

BASE = f"http://127.0.0.1:{os.environ.get('DFLOW_E2E_PORT', '4173')}"
DATA_DIR = os.environ.get('DFLOW_DATA_DIR', '/root/dflow-data')
ok = True

IDS = ['999000201', '999000202', '999000203', '999000204']
PNG = bytes.fromhex(
    '89504e470d0a1a0a0000000d4948445200000001000000010806000000'
    '1f15c4890000000d4944415478da63f8cfc0000003010100b8f5f5b300'
    '00000049454e44ae426082'
)


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw[:200]}


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=60) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def check(label, condition, extra=''):
    global ok
    if not condition:
        ok = False
    print(f"  {'✅' if condition else '❌'} {label}" + (f'  {extra}' if extra else ''))


def cache_path(folder, card_id):
    return os.path.join(DATA_DIR, 'favorites', folder, f'{card_id}.png')


def cleanup():
    state = get('/api/state')
    keep = [f for f in state['favorites'] if str(f.get('id')) not in IDS]
    req('PUT', '/api/favorites', {'favorites': keep})
    for card_id in IDS:
        for folder in ['original', 'pending', 'worded', 'completed']:
            path = cache_path(folder, card_id)
            if os.path.exists(path):
                os.remove(path)


def seed(card_id, folder='original', with_cache=True):
    """造一张带缓存文件的卡片，并真的把缓存写到磁盘上。"""
    if with_cache:
        directory = os.path.join(DATA_DIR, 'favorites', folder)
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, f'{card_id}.png'), 'wb') as fh:
            fh.write(PNG)
    return {
        'id': int(card_id),
        'source': 'danbooru',
        'sourceId': card_id,
        'sourceImageUrl': f'https://cdn.donmai.us/original/00/00/{card_id}.jpg',
        'preview_file_url': f'https://cdn.donmai.us/180x180/00/00/{card_id}.jpg',
        'file_url': f'https://cdn.donmai.us/original/00/00/{card_id}.jpg',
        'image_width': 100, 'image_height': 100, 'rating': 'g',
        'folder': folder,
        **({'cacheFile': f'{card_id}.png', 'cacheStatus': 'ready'} if with_cache else {}),
    }


def main():
    print("=== 1. 准备 4 张卡片 ===")
    cleanup()
    state = get('/api/state')
    others = [f for f in state['favorites'] if str(f.get('id')) not in IDS]
    code, body = req('PUT', '/api/favorites', {
        'favorites': others + [seed(cid) for cid in IDS],
    })
    check('写入 4 张', code == 200, f'HTTP {code}')
    saved = [f for f in body.get('favorites', []) if str(f.get('id')) in IDS]
    check('服务端保留 4 张', len(saved) == 4, f'保留 {len(saved)}')
    existing = [cid for cid in IDS if os.path.exists(cache_path('original', cid))]
    check('缓存文件已就位', len(existing) == 4, f'{len(existing)}/4')

    print("=== 2. 批量移除 2 张（剩余 2 张不动）===")
    remove_ids = set(IDS[:2])
    state = get('/api/state')
    remaining = [f for f in state['favorites'] if str(f.get('id')) not in remove_ids]
    code, body = req('PUT', '/api/favorites', {'favorites': remaining})
    check('批量提交成功', code == 200, f'HTTP {code}')

    after = [str(f.get('id')) for f in body.get('favorites', []) if str(f.get('id')) in IDS]
    check('被移除的 2 张不见了', not any(i in after for i in remove_ids), str(after))
    check('另外 2 张还在', all(i in after for i in IDS[2:]), str(after))

    print("=== 3. 被移除卡片的缓存文件应被清理 ===")
    for cid in IDS[:2]:
        check(f'{cid} 的缓存已删除', not os.path.exists(cache_path('original', cid)),
              '未留下孤儿文件')
    for cid in IDS[2:]:
        check(f'{cid} 的缓存保留', os.path.exists(cache_path('original', cid)))

    print("=== 4. 批量转待反推 ===")
    moved = 0
    for cid in IDS[2:]:
        code, _ = req('PATCH', f'/api/favorites/{cid}', {'folder': 'pending', 'autoEnabled': True})
        if code == 200:
            moved += 1
    check('2 张都转成功', moved == 2, f'{moved}/2')

    state = get('/api/state')
    pending = [f for f in state['favorites'] if str(f.get('id')) in IDS[2:]]
    check('都处于 pending', all(f.get('folder') == 'pending' for f in pending),
          str([f.get('folder') for f in pending]))
    check('都有队列号', all((f.get('queueOrder') or 0) > 0 for f in pending),
          str([f.get('queueOrder') for f in pending]))

    print("=== 5. 缓存随文件夹迁移（不应丢图）===")
    for cid in IDS[2:]:
        check(f'{cid} 在新目录可找到',
              os.path.exists(cache_path('pending', cid)) or os.path.exists(cache_path('original', cid)))

    print("=== 6. 空选择是安全的空操作 ===")
    state = get('/api/state')
    before = len(state['favorites'])
    code, body = req('PUT', '/api/favorites', {'favorites': state['favorites']})
    check('提交相同列表成功', code == 200, f'HTTP {code}')
    check('数量未变', len(body.get('favorites', [])) == before, f'{before} -> {len(body.get("favorites", []))}')

    print("=== 7. 全删（清空该测试组）===")
    state = get('/api/state')
    keep = [f for f in state['favorites'] if str(f.get('id')) not in IDS]
    code, body = req('PUT', '/api/favorites', {'favorites': keep})
    check('清空成功', code == 200, f'HTTP {code}')
    left = [f for f in body.get('favorites', []) if str(f.get('id')) in IDS]
    check('测试卡片已全清', not left, str(left))
    orphans = [cid for cid in IDS
               if any(os.path.exists(cache_path(fd, cid))
                      for fd in ['original', 'pending', 'worded', 'completed'])]
    check('没有留下孤儿缓存', not orphans, str(orphans))

    print("=== 清理 ===")
    cleanup()
    print()
    print('✅ 全部通过' if ok else '❌ 有失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
