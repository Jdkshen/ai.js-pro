#!/usr/bin/env bash
# Build the pinned Node core on Linux/WSL; deployment is a separate reviewed step.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${ANDROID_NDK_ROOT:?Set the Linux Android NDK r26d directory}"
: "${NODE24_ARCHIVE:?Set the verified upstream node-v24.22.0.tar.xz path}"
: "${NODE24_OUTPUT_DIR:?Set an output directory outside the bundled runtime assets}"
: "${NODE24_CXX_RUNTIME:?Set the compatible 16KB libc++_shared.so from NDK r27c or newer}"
source_dir="${NODE24_SOURCE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/aijs/node-v24.22.0-full-icu}"
toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
[[ "$(uname -s)" == Linux ]] || { echo 'Use Linux/WSL with the Linux NDK.' >&2; exit 2; }
python3 "$script_dir/../check-android-16kb.py" "$NODE24_CXX_RUNTIME"
echo "41f97cff2c666f1f8293dc5f85c65e8dd605c16b43fd9febd49ede32ee1e0329  $NODE24_ARCHIVE" | sha256sum --check --status
python3 -c 'import sys; sys.argv[1].encode("ascii")' "$source_dir"
if [[ ! -e "$source_dir" ]]; then
  mkdir -p -- "$source_dir"
  tar -xJf "$NODE24_ARCHIVE" --strip-components=1 -C "$source_dir"
fi
[[ -f "$source_dir/src/node.h" ]] || { echo 'Incomplete Node source tree.' >&2; exit 2; }
python3 "$script_dir/prepare_android_source.py" "$source_dir"
export PATH="$toolchain/bin:$PATH"
export CC="$toolchain/bin/aarch64-linux-android24-clang"
export CXX="$toolchain/bin/aarch64-linux-android24-clang++"
export AR="$toolchain/bin/llvm-ar"
export CC_host="${CC_host:-gcc}" CXX_host="${CXX_host:-g++}" AR_host="${AR_host:-ar}"
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=linux OS=android android_ndk_path=$ANDROID_NDK_ROOT"
json_compiler="${NODE24_JSON_CLANGXX:-/usr/bin/clang++}"
json_version="$("$json_compiler" -dumpversion)"
[[ "${json_version%%.*}" -ge 18 ]] || { echo 'JSON unit requires clang 18 or newer.' >&2; exit 2; }
# NDK clang 17 crashes on this one translation unit. Preserve its libc++ ABI.
json_command="$json_compiler -fclang-abi-compat=17 --target=aarch64-linux-android24 --sysroot=$toolchain/sysroot -stdlib=libc++ -isystem $toolchain/sysroot/usr/include/c++/v1"
target_cxx="\$(if \$(filter %/json-stringifier.cc,\$<),$json_command,$CXX)"
cd -- "$source_dir"
ulimit -s unlimited
if [[ "${NODE24_SKIP_CONFIGURE:-0}" != 1 ]]; then
  ./configure --shared --dest-cpu=arm64 --dest-os=android --openssl-no-asm \
    --cross-compiling --without-node-snapshot --without-node-code-cache --with-intl=full-icu
fi
python3 -c 'import ast; c=ast.literal_eval(open("config.gypi").read())["variables"]; assert c["host_arch"]=="x64" and c["target_arch"]=="arm64" and c["v8_enable_i18n_support"]==1, c'
make -C out -j"${JOBS:-4}" BUILDTYPE=Release libnode \
  "CXX.target=$target_cxx" "LDFLAGS.target=${LDFLAGS:-} -Wl,-z,defs -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
mkdir -p -- "$NODE24_OUTPUT_DIR"
"$toolchain/bin/llvm-strip" --strip-unneeded -o "$NODE24_OUTPUT_DIR/libnode.so" out/Release/libnode.so
cp -- config.gypi out/node_base.target.mk LICENSE "$NODE24_OUTPUT_DIR/"
cp -- "$NODE24_CXX_RUNTIME" "$NODE24_OUTPUT_DIR/libc++_shared.so"
python3 "$script_dir/../check-android-16kb.py" "$NODE24_OUTPUT_DIR/libnode.so"
"$toolchain/bin/llvm-readelf" -h -l -d "$NODE24_OUTPUT_DIR/libnode.so" > "$NODE24_OUTPUT_DIR/libnode-elf.txt"
sha256sum "$NODE24_OUTPUT_DIR/libnode.so" "$NODE24_OUTPUT_DIR/libc++_shared.so"
echo 'NODE24_FULL_ICU_BUILD_COMPLETE'
