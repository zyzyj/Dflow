#!/usr/bin/env python3
"""
校验 jniLibs 里 PRoot 相关二进制的依赖是否齐备。

为什么需要它：这些二进制是手工从 Termux 的 deb 包里取的，谁也没有强制检查
它们的 DT_NEEDED 是否都被打包了。漏掉一个的后果在 CI 里完全看不到——构建成功、
测试通过（测试跑在 x86 runner 上，用的是宿主 glibc，不会去读 android 的 libc），
只有用户装到手机上才炸，报的还是一句没头没尾的

    CANNOT LINK EXECUTABLE "libproot.so": library "libandroid-shmem.so" not found

libandroid-shmem.so 就是这样被漏了好几个版本。

用法：verify-native-deps.py <jniLibs 目录>
"""
import os
import struct
import sys

# Android 系统在 /system/lib64 里提供，不需要（也不能）随 APK 打包。
SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so', 'libstdc++.so',
    'libandroid.so', 'libandroid_support.so', 'libjnigraphics.so',
    'libGLESv2.so', 'libEGL.so', 'libOpenSLES.so', 'libnativewindow.so',
    'libsync.so', 'libvulkan.so', 'libcutils.so', 'libbase.so',
    'libutils.so', 'libhardware.so', 'libbinder.so', 'libui.so',
    'libgui.so', 'libhidlbase.so', 'libion.so', 'libnetd_client.so',
}

# 打包时被改名以适应 Android 的 lib*.so 规则，或需要运行时另建 soname 副本。
# 值 = 该 NEEDED 名字由哪个已打包文件提供。
ALIASES = {
    'libtalloc.so.2': 'libtalloc.so',
}


def elf_needed(blob):
    """解析 ELF64/ELF32 的 DT_NEEDED 列表。返回 (is_elf, needed)。"""
    if len(blob) < 64 or blob[:4] != b'\x7fELF':
        return False, []
    is64 = blob[4] == 2
    endian = '<' if blob[5] == 1 else '>'

    if is64:
        e_shoff = struct.unpack_from(endian + 'Q', blob, 0x28)[0]
        e_shentsize = struct.unpack_from(endian + 'H', blob, 0x3a)[0]
        e_shnum = struct.unpack_from(endian + 'H', blob, 0x3c)[0]
    else:
        e_shoff = struct.unpack_from(endian + 'I', blob, 0x20)[0]
        e_shentsize = struct.unpack_from(endian + 'H', blob, 0x2e)[0]
        e_shnum = struct.unpack_from(endian + 'H', blob, 0x30)[0]

    dyn_off = dyn_size = None
    for i in range(e_shnum):
        base = e_shoff + i * e_shentsize
        stype = struct.unpack_from(endian + 'I', blob, base + 4)[0]
        if stype == 6:  # SHT_DYNAMIC
            if is64:
                dyn_off = struct.unpack_from(endian + 'Q', blob, base + 0x18)[0]
                dyn_size = struct.unpack_from(endian + 'Q', blob, base + 0x20)[0]
            else:
                dyn_off = struct.unpack_from(endian + 'I', blob, base + 0x10)[0]
                dyn_size = struct.unpack_from(endian + 'I', blob, base + 0x14)[0]
            break

    if dyn_off is None:
        # 静态链接（loader 就是这样），没有运行期依赖。
        return True, []

    step = 16 if is64 else 8
    tag_fmt = endian + ('q' if is64 else 'i')
    val_fmt = endian + ('Q' if is64 else 'I')
    entries = []
    for i in range(dyn_size // step):
        base = dyn_off + i * step
        tag = struct.unpack_from(tag_fmt, blob, base)[0]
        val = struct.unpack_from(val_fmt, blob, base + (8 if is64 else 4))[0]
        entries.append((tag, val))

    strtab = next((v for t, v in entries if t == 5), None)
    if strtab is None:
        return True, []

    needed = []
    for tag, val in entries:
        if tag == 1:  # DT_NEEDED
            end = blob.index(b'\x00', strtab + val)
            needed.append(blob[strtab + val:end].decode('utf-8', 'replace'))
    return True, needed


def main():
    if len(sys.argv) < 2:
        print('用法: verify-native-deps.py <jniLibs 目录>')
        return 2

    directory = sys.argv[1]
    if not os.path.isdir(directory):
        print(f'❌ 目录不存在: {directory}')
        return 1

    files = sorted(os.listdir(directory))
    print(f'jniLibs: {directory}')
    for name in files:
        print(f'  {name}')

    # 建索引：既认文件名，也认它宣称的 soname。
    provided = {}
    for name in files:
        provided[name] = name

    problems = []
    print()
    for name in files:
        path = os.path.join(directory, name)
        with open(path, 'rb') as fh:
            blob = fh.read()
        is_elf, needed = elf_needed(blob)
        if not is_elf:
            continue
        print(f'{name}:')
        if not needed:
            print('    （静态链接，无运行期依赖）')
            continue
        for lib in needed:
            resolved = ALIASES.get(lib, lib)
            if resolved in provided:
                note = '' if resolved == lib else f'  ← 由 {resolved} 提供'
                print(f'    ✅ {lib}{note}')
            elif lib in SYSTEM_LIBS:
                print(f'    ✅ {lib}  （Android 系统库）')
            else:
                print(f'    ❌ {lib}  未打包')
                problems.append((name, lib))

    # 迁移检查：这些是运行时通过 LD_LIBRARY_PATH 查找的名字。
    print()
    print('运行时需要的 soname 别名：')
    for needed_name, file_name in ALIASES.items():
        if file_name in provided:
            print(f'    ✅ {needed_name}  ← {file_name}（需在 ProotLauncher 里建副本）')
        else:
            print(f'    ❌ {needed_name}  ← {file_name} 不存在')
            problems.append(('alias', needed_name))

    print()
    if problems:
        print('❌ 存在未满足的依赖：')
        for owner, lib in problems:
            print(f'    {owner} 需要 {lib}')
        return 1
    print('✅ 所有依赖均满足')
    return 0


if __name__ == '__main__':
    sys.exit(main())
