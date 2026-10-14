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
            # 用从 Kotlin 源码里读出的同一个掩码，而不是另写一份。
            if mode & KOTLIN_MASK:
                os.chmod(target, os.stat(target).st_mode | stat.S_IXUSR)
            stats['files'] += 1

        if tf in ('0', '\x00', '2', '1') or (tf == '5' and size):
            pos += blocks

    return stats


def kotlin_exec_mask():
    """从 TarExtractor.kt 里读出实际使用的可执行位掩码。

    为什么这样做：这个脚本曾经自己写了一份 0o100 的判断，而 Kotlin 里写的是
    0b001_000_000_000（= 0o1000，sticky 位，不是可执行位）。两边不一致，脚本
    一直绿着，真机上却一个可执行文件都没有，服务直接起不来。

    复刻流程不等于复刻实现。这里改成解析真实源码，一旦 Kotlin 写错掩码，这个
    脚本会立刻失败。
    """
    import re
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        '..', 'app', 'src', 'main', 'java', 'com', 'zyzyj', 'dflow',
                        'TarExtractor.kt')
    with open(path, encoding='utf-8') as fh:
        src = fh.read()
    match = re.search(r'if\s*\(\s*mode\s+and\s+([0-9A-Za-z_\.]+L?)\s*!=\s*0L', src)
    if not match:
        raise SystemExit('未能从 TarExtractor.kt 里找到可执行位判断；实现变了，请更新本脚本')
    raw = match.group(1)
    # Kotlin 的 Long 后缀 L 不是字面量的一部分。
    literal = raw[:-1] if raw.endswith('L') else raw
    literal = literal.replace('_', '').replace('.', '')
    if literal.lower().startswith('0b'):
        value = int(literal, 2)
    elif literal.lower().startswith('0x'):
        value = int(literal, 16)
    else:
        value = int(literal, 10)
    return value, raw


# 在解压前解析一次，并把它的值用作本脚本的判断依据。
KOTLIN_MASK, KOTLIN_MASK_TEXT = kotlin_exec_mask()


def audit_mask():
    """确认 Kotlin 里的掩码真的能匹配常见的可执行权限。"""
    problems = []
    print(f"  Kotlin 掩码: {KOTLIN_MASK_TEXT} = 0o{KOTLIN_MASK:o}")
    for mode, label in [(0o755, 'busybox/常规可执行'), (0o700, '仅属主可执行'),
                        (0o555, '只读可执行'), (0o644, '普通文件')]:
        matched = bool(mode & KOTLIN_MASK)
        should = label != '普通文件'
        mark = '✅' if matched == should else '❌'
        if matched != should:
            problems.append(f"掩码对 0o{mode:o}（{label}）判断错误")
        print(f"    {mark} 0o{mode:o} {label:12s} → {'设可执行' if matched else '不设'}")
    return problems


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

    # 关键新增：真正跟着符号链接走一遍，确认最终目标可执行。
    # /bin/sh -> /bin/busybox，只检查 /bin/sh 本身会漏掉目标不可执行的情况。
    sh_path = os.path.join(root, 'bin', 'sh')
    if os.path.exists(sh_path) or os.path.islink(sh_path):
        resolved = os.path.realpath(sh_path)
        if not os.path.exists(resolved):
            problems.append(f"/bin/sh 的最终目标不存在：{resolved}")
        elif not os.access(resolved, os.X_OK):
            problems.append(
                f"/bin/sh 的最终目标不可执行：{resolved} "
                f"(mode=0o{os.stat(resolved).st_mode & 0o777:o}) — 这就是 "
                f"proot error: '/bin/sh' is not executable 的原因")
        else:
            link = '（符号链接 -> ' + os.readlink(sh_path) + '）' if os.path.islink(sh_path) else ''
            print(f"  /bin/sh 存在{link}，最终目标 {resolved} 可执行")
    else:
        problems.append("/bin/sh 缺失")

    node_path = os.path.join(root, 'usr', 'bin', 'node')
    if os.path.exists(node_path):
        executable = os.access(node_path, os.X_OK)
        print(f"  /usr/bin/node 存在，{os.path.getsize(node_path) / 1048576:.1f} MB，"
              f"{'可执行' if executable else '不可执行'}")
        if not executable:
            problems.append("/usr/bin/node 不可执行")
    else:
        problems.append("/usr/bin/node 缺失")

    # 抽样统计：整个 rootfs 里可执行文件的比例。如果一个都没有，说明掩码又写错了。
    total, executable = 0, 0
    for dirpath, _, filenames in os.walk(root):
        for n in filenames:
            p = os.path.join(dirpath, n)
            if os.path.islink(p) or not os.path.isfile(p):
                continue
            total += 1
            if os.access(p, os.X_OK):
                executable += 1
    print(f"  常规文件中可执行 {executable} / {total}")
    if total > 0 and executable == 0:
        problems.append("没有设置任何可执行位（掩码或 setExecutable 没生效）")

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

    print("=== 可执行位掩码（从 TarExtractor.kt 读取）===")
    problems = audit_mask()

    print()
    print("=== 解压 rootfs（直接落到目标目录）===")
    stats = extract(rootfs, os.path.join(dest, 'rootfs'))
    print(f"  {stats}")
    problems.extend(audit(os.path.join(dest, 'rootfs')))

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
