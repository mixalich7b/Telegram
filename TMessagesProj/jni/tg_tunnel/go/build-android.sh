#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: build-android.sh <output-so>" >&2
  exit 2
fi

output_so="$1"
script_dir="$(cd "$(dirname "$0")" && pwd)"
build_dir="${TG_TUNNEL_BUILD_DIR:-"$script_dir/build"}"
tunnel_logs_enabled="${TG_TUNNEL_LOGS_ENABLED:-true}"
case "$tunnel_logs_enabled" in
  true|false) ;;
  *) echo "TG_TUNNEL_LOGS_ENABLED must be true or false" >&2; exit 2 ;;
esac

android_abi="${ANDROID_ABI:?ANDROID_ABI is required}"
case "$android_abi" in
  armeabi-v7a) goarch="arm"; goarm="7"; clang_prefix="armv7a-linux-androideabi" ;;
  arm64-v8a) goarch="arm64"; clang_prefix="aarch64-linux-android" ;;
  x86) goarch="386"; clang_prefix="i686-linux-android" ;;
  x86_64) goarch="amd64"; clang_prefix="x86_64-linux-android" ;;
  *) echo "unsupported Android ABI: $android_abi" >&2; exit 3 ;;
esac

if ! go_bin="$(command -v go)"; then
  echo "Go is required on PATH; install a version compatible with tg_tunnel/go/go.mod" >&2
  exit 4
fi

mkdir -p "$(dirname "$output_so")" "$build_dir/gomod" "$build_dir/gocache"
version_script="$script_dir/exports.map"
# The CMake dependency builds this shared sink before the Go bridge is linked.
export CGO_LDFLAGS="${CGO_LDFLAGS:-} \"-L$(dirname "$output_so")\""

export GOOS=android
export GOARCH="$goarch"
if [ -n "${goarm:-}" ]; then
  export GOARM="$goarm"
fi
export CGO_ENABLED=1
export GOMODCACHE="$build_dir/gomod"
export GOCACHE="$build_dir/gocache"
export GOTOOLCHAIN=local

android_api="${ANDROID_API:-}"
if [ -z "$android_api" ] && [ -n "${ANDROID_PLATFORM:-}" ]; then
  android_api="${ANDROID_PLATFORM#android-}"
fi
android_api="${android_api:-21}"

ndk_root="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -n "$ndk_root" ]; then
  case "$(uname -s)" in
    Darwin) host_tag_candidates=("darwin-$(uname -m)" "darwin-x86_64" "darwin-arm64") ;;
    Linux) host_tag_candidates=("linux-$(uname -m)" "linux-x86_64") ;;
    *) host_tag_candidates=() ;;
  esac
  for host_tag in "${host_tag_candidates[@]}"; do
    candidate="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin/${clang_prefix}${android_api}-clang"
    if [ -x "$candidate" ]; then
      export CC="$candidate"
      break
    fi
  done
fi

export CC="${CC:?CC is required for Android cgo builds}"

cd "$script_dir"
"$go_bin" build -tags linux -trimpath -buildvcs=false -buildmode c-shared -ldflags "-X main.tunnelLogsEnabled=${tunnel_logs_enabled} -linkmode=external -extldflags=-Wl,-soname,libtg-tunnel-go.so,--version-script=${version_script}" -o "$output_so" .
