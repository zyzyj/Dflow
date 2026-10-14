#!/usr/bin/env python3
"""
忠实复刻 TarExtractor.kt 的解析逻辑，在真实 tar 上全量验证。

为什么需要它：解包逻辑只能在设备上跑，而设备上调试一次成本很高
（构建 10 分钟 + 传输 + 安装）。这个脚本用原始字节解析 tar，不依赖
Python tarfile 的名字归一化（那会掩盖 './' 这类差异），因此能在本地
提前暴露路径校验、长文件名、符号链接等问题。
"""
import os, sys

BLOCK = 512

def read_string(buf, off, length):
    end = off
    limit = off + length
    while end < limit and buf[end] != 0:
        end += 1
    return buf[off:end].decode('utf-8', 'replace').strip()

def read_fixed_string(data, off, length):
    chunk = data[off:off + length]
    end = chunk.find(0)
    if end >= 0:
        chunk = chunk[:end]
    return chunk.decode('utf-8', 'replace')

def read_octal(buf, off, length):
    if length > 0 and (buf[off] & 0x80):
        val = 0
        for i in range(length):
            val = (val << 8) | buf[off + i]
        return val & 0x7FFFFFFFFFFFFFFF
    val = 0
    for i in range(length):
        b = buf[off + i]
        if b in (0, ord(' ')) or b < ord('0') or b > ord('7'):
            continue
        val = val * 8 + (b - ord('0'))
    return val

def safe_resolve(root, entry_name):
    cleaned = entry_name.lstrip('/')
    if cleaned.startswith('./'):
        cleaned = cleaned[2:]
    cleaned = cleaned.rstrip('/')
    if cleaned == '' or cleaned == '.':
        return root
    candidate = os.path.realpath(os.path.join(root, cleaned))
    if candidate == root or candidate.startswith(root + os.sep):
        return candidate
    return None

def resolve_link_target(root, link_location, link_name):
    if link_name == '':
        return None
    if link_name.startswith('/'):
        depth = 0
        for seg in link_name.split('/'):
            if seg in ('', '.'):
                continue
            if seg == '..':
                depth -= 1
                if depth < 0:
                    return None
            else:
                depth += 1
        return link_name
    resolved = os.path.realpath(os.path.join(os.path.dirname(link_location), link_name))
    return link_name if resolved.startswith(root + os.sep) else None

def scan(path, root='/tmp/scanroot'):
    os.makedirs(root, exist_ok=True)
    data = open(path, 'rb').read()
    # Accept either a bare tar or a gzip-compressed one: aapt2 strips the .gz
    # suffix during packaging, so the same file has two possible shapes.
    if data[:2] == b'\x1f\x8b':
        import gzip
        data = gzip.decompress(data)
    pos = 0
    extracted = []
    rejected_path, rejected_link = [], []
    unknown = {}
    pending_long = None

    while pos + BLOCK <= len(data):
        header = data[pos:pos + BLOCK]
        pos += BLOCK
        if all(b == 0 for b in header):
            if pos + BLOCK <= len(data) and all(b == 0 for b in data[pos:pos + BLOCK]):
                break
            continue
        name = read_string(header, 0, 100)
        if name == '':
            break
        prefix = read_string(header, 345, 155)
        size = read_octal(header, 124, 12)
        tf = chr(header[156])
        link_name = read_string(header, 157, 100)
        blocks = ((size + BLOCK - 1) // BLOCK) * BLOCK

        if tf == 'L':
            pending_long = read_fixed_string(data, pos, size)
            pos += blocks
            continue
        if tf == 'K':
            pos += blocks
            continue

        effective = pending_long if pending_long is not None else name
        pending_long = None
        full = f"{prefix}/{effective}" if prefix else effective

        target = safe_resolve(root, full)
        if target is None:
            rejected_path.append(full)
        elif tf == '2' and resolve_link_target(root, target, link_name) is None:
            rejected_link.append((full, link_name))
        elif tf not in ('0', '5', '2', '1', '\x00'):
            unknown[tf] = unknown.get(tf, 0) + 1
        else:
            extracted.append((tf, full, link_name))

        if tf in ('0', '\x00', '2', '1') or (tf == '5' and size):
            pos += blocks

    print(f"=== {os.path.basename(path)} ===")
    print(f"  条目总数    : {len(extracted) + len(rejected_path) + len(rejected_link)}")
    print(f"  成功解析    : {len(extracted)}")
    print(f"  路径被拒    : {len(rejected_path)}")
    for n in rejected_path[:5]:
        print(f"      {n!r}")
    print(f"  符号链接被拒: {len(rejected_link)}")
    for n, l in rejected_link[:5]:
        print(f"      {n!r} -> {l!r}")
    if unknown:
        print(f"  ⚠ 未处理类型: {unknown}")
    # 长名条目是否被正确还原
    longs = [f for (t, f, _l) in extracted if len(f) > 100]
    print(f"  超长名条目  : {len(longs)}（最长的 {max((len(f) for f in longs), default=0)} 字符）")
    ok = not rejected_path and not rejected_link and not unknown
    print(f"  结论: {'✅ 全部可解' if ok else '❌ 有问题'}")
    print()
    return ok

if __name__ == '__main__':
    targets = sys.argv[1:] or [
        '/tmp/apkx2/assets/rootfs.tar',
        '/tmp/apkx2/assets/app.tar',
    ]
    allok = True
    for t in targets:
        if not os.path.exists(t):
            print(f"跳过（不存在）: {t}")
            continue
        allok = scan(t) and allok
    sys.exit(0 if allok else 1)
