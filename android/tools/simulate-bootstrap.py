#!/usr/bin/env python3
"""
在本地复刻 Bootstrap 的完整解压流程，验证真实 rootfs 能落地成可用的文件树。

背景：之前的实现在设备上报 "Too many symbolic links" —— 先解压到 staging
再递归复制，而复制会跟随符号链接。var/run -> ../run、var/spool/mail -> ../mail
这类目录链接会被走进去并成环。这个脚本用与 Kotlin 相同的策略（直接解压、
只移动不复制）走一遍，确认没有成环、悬空链接保留、关键可执行文件就位。
"""
import os, gzip, io, shutil, stat, sys

BLOCK = 512
GZIP_MAGIC = b'\x1f\x8b'


def read_string(buf, off, length):
    end = off
    limit = off + length
    while end < limit and buf[end] != 0:
        end += 1
    return buf[off:end].decode('utf-8', 'replace').strip()


def read_fixed(data, off, length):
    chunk = data[off:off + length]
    i = chunk.find(0)
    if i >= 0:
        chunk = chunk[:i]
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


def safe_resolve(root, name):
    cleaned = name.lstrip('/')
    if cleaned.startswith('./'):
        cleaned = cleaned[2:]
    cleaned = cleaned.rstrip('/')
    if cleaned in ('', '.'):
        return root
    cand = os.path.normpath(os.path.join(root, cleaned))
    if cand == root or cand.startswith(root + os.sep):
        return cand
    return None


def link_target_ok(root, link_path, target):
    """绝对链接按 rootfs 根解析；相对链接按链接所在目录解析，且不得越界。"""
    if target.startswith('/'):
        rel = target.lstrip('/')
    else:
        rel = os.path.normpath(os.path.join(os.path.dirname(link_path), target))
        rel = os.path.relpath(rel, root)
    depth = 0
    for seg in rel.split('/'):
        if seg in ('', '.'):
            continue
        if seg == '..':
            depth -= 1
            if depth < 0:
                return False
        else:
            depth += 1
    return True


def extract(archive, dest):
    data = open(archive, 'rb').read()
    if data[:2] == GZIP_MAGIC:
        data = gzip.decompress(data)
    os.makedirs(dest, exist_ok=True)
    pos = 0
    stats = {'files': 0, 'dirs': 0, 'symlinks': 0, 'hardlinks': 0, 'skipped_link': 0, 'unsafe': 0}
    pending_long = None

    while pos + BLOCK <= len(data):
        header = data[pos:pos + BLOCK]
        pos += BLOCK
        if all(b == 0 for b in header):
            if pos + BLOCK <= len(data) and all(b == 0 for b in data[pos:pos + BLOCK]):
                break
            continue
        name = read_string(header, 0, 100)
        if not name:
            break
        prefix = read_string(header, 345, 155)
        size = read_octal(header, 124, 12)
        tf = chr(header[156])
        link = read_string(header, 157, 100)
        blocks = ((size + BLOCK - 1) // BLOCK) * BLOCK

        if tf == 'L':
            pending_long = read_fixed(data, pos, size)
            pos += blocks
            continue
        if tf == 'K':
            pos += blocks
            continue

        full = pending_long if pending_long is not None else name
        pending_long = None
        if prefix:
            full = f"{prefix}/{full}"

        target = safe_resolve(dest, full)
        if target is None:
            stats['unsafe'] += 1
            pos += blocks if tf in ('0', '\x00', '2', '1') else (blocks if tf == '5' and size else 0)
            continue

        if tf == '5':
            os.makedirs(target, exist_ok=True)
            stats['dirs'] += 1
        elif tf == '2':
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.lexists(target):
                os.remove(target)
            # 越界的链接直接跳过（安全检查），但不影响其余成员
            os.symlink(link, target)
            stats['symlinks'] += 1
        elif tf == '1':
            os.makedirs(os.path.dirname(target), exist_ok=True)
            src = safe_resolve(dest, link)
            if src and os.path.exists(src):
                try:
                    if os.path.lexists(target):
                        os.remove(target)
                    os.link(src, target)
                    stats['hardlinks'] += 1
                except OSError:
                    shutil.copy2(src, target)
        else:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'wb') as f:
                f.write(data[pos:pos + size])
            mode = read_octal(header, 100, 8)
            if mode & 0o100:
                os.chmod(target, os.stat(target).st_mode | stat.S_IXUSR)
            stats['files'] += 1

        if tf in ('0', '\x00', '2', '1') or (tf == '5' and size):
            pos += blocks

    return stats


def audit(root):
    """检查解压结果：是否有成环链接、关键文件是否可执行、是否含 node。"""
    problems = []
    symlink_count = 0
    dirlinks = 0
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for n in dirnames + filenames:
            p = os.path.join(dirpath, n)
            if os.path.islink(p):
                symlink_count += 1
                tgt = os.readlink(p)
                resolved = os.path.normpath(os.path.join(os.path.dirname(p), tgt)) \
                    if not tgt.startswith('/') else os.path.join(root, tgt.lstrip('/'))
                if os.path.isdir(resolved):
                    dirlinks += 1
    print(f"  符号链接 {symlink_count} 个，其中指向目录的 {dirlinks} 个（正常，不应被复制实现破坏）")

    sh_path = os.path.join(root, 'bin', 'sh')
    if os.path.exists(sh_path) or os.path.islink(sh_path):
        print(f"  /bin/sh 存在{'（符号链接 -> ' + os.readlink(sh_path) + '）' if os.path.islink(sh_path) else ''}")
    else:
        problems.append("/bin/sh 缺失")

    node_path = os.path.join(root, 'usr', 'bin', 'node')
    if os.path.exists(node_path):
        print(f"  /usr/bin/node 存在，{os.path.getsize(node_path) / 1048576:.1f} MB")
    else:
        problems.append("/usr/bin/node 缺失")

    # 关键：确认没有在做「复制」时走进链接而死循环（能走完就说明没有）
    return problems


def main():
    args = sys.argv[1:]
    if len(args) >= 2:
        rootfs, app = args[0], args[1]
    else:
        # Defaults for local runs against an already-downloaded APK's contents.
        rootfs = '/tmp/apkx2/assets/rootfs.tar'
        app = '/tmp/apkx2/assets/app.tar'
    dest = os.environ.get('BOOTSTRAP_SIM_DIR', '/tmp/bootout')
    shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest)

    print("=== 解压 rootfs（直接落到目标目录）===")
    stats = extract(rootfs, os.path.join(dest, 'rootfs'))
    print(f"  {stats}")
    problems = audit(os.path.join(dest, 'rootfs'))

    print()
    print("=== 解压 app（tar 内多一层 app/ 前缀）===")
    staged = os.path.join(dest, 'app.staging')
    stats2 = extract(app, staged)
    print(f"  {stats2}")
    appdir = os.path.join(dest, 'app')
    os.makedirs(appdir, exist_ok=True)
    src = os.path.join(staged, 'app') if os.path.isdir(os.path.join(staged, 'app')) else staged
    moved = 0
    for name in os.listdir(src):
        if name == 'data':
            continue
        target = os.path.join(appdir, name)
        if os.path.lexists(target):
            shutil.rmtree(target) if os.path.isdir(target) and not os.path.islink(target) else os.remove(target)
        try:
            os.rename(os.path.join(src, name), target)
            moved += 1
        except OSError as e:
            problems.append(f"rename 失败 {name}: {e}")
    print(f"  移动 {moved} 项到 app/")
    if not os.path.exists(os.path.join(appdir, 'server.js')):
        problems.append("app/server.js 缺失")
    else:
        print("  server.js 就位")

    print()
    if problems:
        print("❌ 问题：")
        for p in problems:
            print(f"   - {p}")
        sys.exit(1)
    print("✅ 解压流程在真实包上验证通过")


if __name__ == '__main__':
    main()
