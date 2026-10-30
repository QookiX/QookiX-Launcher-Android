"""Rebuild ARM64 candidates; never replace packaged libraries automatically."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def strip_tool(ndk: Path) -> Path:
    """NDK 自带的 llvm-strip。

    交付物必须 strip：CMake 的 Release 默认仍带 `.debug_*`，上一轮 10 个库因此
    从 12MB 涨到 91MB（`libspirv-cross-c-shared.so` 单库 42MB，其中 36MB 是调试信息）。
    `--strip-unneeded` 不删调试节，必须用 `--strip-debug`。
    """
    for prebuilt in (ndk / 'toolchains/llvm/prebuilt').iterdir():
        for exe in ('llvm-strip.exe', 'llvm-strip'):
            tool = prebuilt / 'bin' / exe
            if tool.exists():
                return tool
    raise RuntimeError(f'llvm-strip not found under {ndk}')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--prepare', action='store_true', help='Fetch pinned sources and apply patches in a new source directory')
    parser.add_argument('--work', type=Path, default=ROOT / 'build/native-16k')
    args = parser.parse_args()
    work = args.work.resolve()
    source = work / 'src'
    source.mkdir(parents=True, exist_ok=True)
    if args.prepare:
        for item in json.loads((HERE / 'sources.json').read_text()):
            dest = source / item['name']
            if dest.exists():
                raise RuntimeError(f'Refusing to patch an existing checkout: {dest}')
            run('git', 'init', dest)
            run('git', '-C', dest, 'remote', 'add', 'origin', item['url'])
            run('git', '-C', dest, 'fetch', '--depth', '1', 'origin', item['commit'])
            run('git', '-C', dest, 'checkout', '--detach', 'FETCH_HEAD')
            if item['patch']:
                run('git', '-C', dest, 'apply', HERE / item['patch'])
    common = ['-G', 'Ninja', f'-DCMAKE_TOOLCHAIN_FILE={args.ndk.resolve().as_posix()}/build/cmake/android.toolchain.cmake',
              '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-24', '-DCMAKE_BUILD_TYPE=Release',
              '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
    projects = {
        'SDL': ['-DSDL_SHARED=ON', '-DSDL_STATIC=OFF', '-DSDL_TESTS=OFF'],
        'freetype': ['-DBUILD_SHARED_LIBS=ON'] + [f'-DFT_DISABLE_{n}=ON' for n in ['ZLIB', 'BZIP2', 'PNG', 'HARFBUZZ', 'BROTLI']],
        'openal-soft': ['-DLIBTYPE=SHARED', '-DALSOFT_UTILS=OFF', '-DALSOFT_EXAMPLES=OFF', '-DALSOFT_TESTS=OFF', '-DALSOFT_BACKEND_OBOE=OFF', '-DALSOFT_BACKEND_OPENSL=ON'],
        'spirv-cross': ['-DSPIRV_CROSS_SHARED=ON', '-DSPIRV_CROSS_CLI=OFF', '-DSPIRV_CROSS_ENABLE_TESTS=OFF'],
        'pojav-gl4es': ['-DNOX11=ON', '-DANDROID=ON', '-DGLX_STUBS=ON', '-DUSE_ANDROID_LOG=ON', '-DUSE_CCACHE=OFF'],
    }
    for name, flags in projects.items():
        build = work / (name + '-rebuild-arm64')
        run('cmake', '-S', source / name, '-B', build, *common, *flags)
        run('cmake', '--build', build, '--parallel', '4')
    bindings = work / 'bindings-rebuild-arm64'
    run('cmake', '-S', HERE, '-B', bindings, *common, f'-DNATIVE_SOURCE_ROOT={source.as_posix()}')
    run('cmake', '--build', bindings, '--parallel', '4')
    candidates = {
        'libSDL3.so': work / 'SDL-rebuild-arm64/libSDL3.so',
        'libfreetype.so': work / 'freetype-rebuild-arm64/libfreetype.so',
        'libopenal.so': work / 'openal-soft-rebuild-arm64/libopenal.so',
        'libspirv-cross-c-shared.so': work / 'spirv-cross-rebuild-arm64/libspirv-cross-c-shared.so',
        'libgl4es_114.so': source / 'pojav-gl4es/lib/libGL.so.1',
    }
    for module in ['', '_opengl', '_nanovg', '_stb', '_tinyfd', '_spng', '_vma']:
        name = f'liblwjgl{module}.so'
        candidates[name] = bindings / name
    dist = work / 'dist/arm64-v8a'
    dist.mkdir(parents=True, exist_ok=True)
    manifest = []
    strip = strip_tool(args.ndk.resolve())
    for name, candidate in candidates.items():
        raw = candidate.read_bytes()
        if raw[:6] != b'\x7fELF\x02\x01':
            raise RuntimeError(f'Not little-endian ELF64: {candidate}')
        # 先落进 dist 再 strip：源码树里的产物保持原样，交付物必须是 strip 过的。
        shutil.copy2(candidate, dist / name)
        run(strip, '--strip-debug', dist / name)
        data = (dist / name).read_bytes()
        header = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
        if header[1] != 183:
            raise RuntimeError(f'Not ARM64: {candidate}')
        aligns = []
        for i in range(header[9]):
            ph = struct.unpack_from('<IIQQQQQQ', data, header[4] + i * header[8])
            if ph[0] == 1:
                aligns.append(ph[-1])
        if not aligns or min(aligns) < 16384:
            raise RuntimeError(f'Not 16KB aligned: {candidate}')
        manifest.append({'name': name, 'sha256': hashlib.sha256(data).hexdigest(), 'load_alignment': aligns})
    (dist.parent / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Built {len(manifest)} candidates in {dist}; runtime compatibility still requires device testing.')


if __name__ == '__main__':
    main()
