#!/usr/bin/env bash
# Shared configuration for the Bitcoin Core Node native build.
# Sourced by every 0*/1*/2*/3* script. Not meant to be run directly.

set -euo pipefail

# ---------------------------------------------------------------------------
# Versions and hashes.
#
# These are NOT invented. Every version and SHA256 below is copied verbatim
# from Bitcoin Core 30.3's own depends/packages/*.mk, i.e. the exact
# dependency set upstream builds and tests its releases against.
#   libevent -> depends/packages/libevent.mk
#   sqlite   -> depends/packages/sqlite.mk
#   boost    -> depends/packages/boost.mk
# If you bump Core, re-copy these from the new tree rather than guessing.
# ---------------------------------------------------------------------------
LIBEVENT_VERSION="2.1.12-stable"
LIBEVENT_URL="https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VERSION}/libevent-${LIBEVENT_VERSION}.tar.gz"
LIBEVENT_SHA256="92e6de1be9ec176428fd2367677e61ceffc2ee1cb119035037a27d346b0403bb"

SQLITE_VERSION="3460100"
SQLITE_URL="https://sqlite.org/2024/sqlite-autoconf-${SQLITE_VERSION}.tar.gz"
SQLITE_SHA256="67d3fe6d268e6eaddcae3727fce58fcc8e9c53869bdd07a0c61e38ddf2965071"

BOOST_VERSION="1.88.0"
BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.gz"
BOOST_SHA256="dcea50f40ba1ecfc448fdf886c0165cf3e525fef2c9e3e080b9804e8117b9694"

# ---------------------------------------------------------------------------
# Android target configuration
# ---------------------------------------------------------------------------

# API 28 (Android 9). Chosen deliberately: Core's random.cpp wants getrandom()/
# getentropy(), which bionic only exposes from API 28. Targeting 26 or 27 means
# patching Core's entropy code, which is the last file in the tree anyone should
# be patching. 28+ covers the overwhelming majority of active devices.
: "${ANDROID_API_LEVEL:=28}"

# arm64-v8a is every real phone. x86_64 is the emulator; keep it so CI and
# `adb` testing work without a physical device. armeabi-v7a is deliberately
# absent: Play has required 64-bit since 2019, and a 32-bit address space is a
# poor fit for a node's UTXO cache anyway.
: "${ABIS:=arm64-v8a x86_64}"

: "${ANDROID_STL:=c++_static}"

# Google Play requires 16 KB memory page compatibility. NDK r27+ does this by
# default for shared libraries but NOT for executables, and our node ships as
# an executable, so we force it on the link line and verify it in step 30.
: "${MAX_PAGE_SIZE:=16384}"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$(cd "${NATIVE_DIR}/.." && pwd)"
BUILD_DIR="${BCN_BUILD_DIR:-${NATIVE_DIR}/build}"
DL_DIR="${BUILD_DIR}/downloads"
SRC_DIR="${BUILD_DIR}/src"
OUT_DIR="${BUILD_DIR}/out"
PATCH_DIR="${NATIVE_DIR}/patches"
JNILIBS_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

# Where the Bitcoin Core source tree lives. Override with BITCOIN_SRC=...
# Default assumes you extracted bitcoin-30.3.zip next to BitcoinCoreNode/.
: "${BITCOIN_SRC:=${PROJECT_DIR}/../src_extract/bitcoin-30.3}"

deps_prefix() { echo "${BUILD_DIR}/$1/deps"; }

# ---------------------------------------------------------------------------
# Toolchain discovery
# ---------------------------------------------------------------------------
find_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
    echo "${ANDROID_NDK_HOME}"; return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -f "${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" ]]; then
    echo "${ANDROID_NDK_ROOT}"; return
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "${sdk}/ndk" ]]; then
    # Highest installed NDK wins.
    local best
    best="$(ls -1 "${sdk}/ndk" 2>/dev/null | sort -V | tail -1)"
    if [[ -n "${best}" && -f "${sdk}/ndk/${best}/build/cmake/android.toolchain.cmake" ]]; then
      echo "${sdk}/ndk/${best}"; return
    fi
  fi
  echo ""
}

require_ndk() {
  NDK="$(find_ndk)"
  if [[ -z "${NDK}" ]]; then
    cat >&2 <<'MSG'
ERROR: Android NDK not found.

Install it with Android Studio (SDK Manager -> SDK Tools -> NDK (Side by side),
pick r27 or newer -- r27 is the first release that handles 16 KB pages properly),
or set one of these and re-run:

    export ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018
    export ANDROID_HOME=$HOME/Library/Android/sdk
MSG
    exit 1
  fi
  local ver
  ver="$(sed -n 's/^Pkg.Revision *= *//p' "${NDK}/source.properties" 2>/dev/null || echo unknown)"
  local major="${ver%%.*}"
  if [[ "${major}" =~ ^[0-9]+$ ]] && (( major < 27 )); then
    echo "WARNING: NDK ${ver} is older than r27. 16 KB page alignment and some" >&2
    echo "         C++20 library features may not work. r27+ strongly recommended." >&2
  fi
  export NDK
  export NDK_VERSION="${ver}"
}

host_tag() {
  case "$(uname -s)" in
    Darwin) echo "darwin-x86_64" ;;   # correct on Apple Silicon too; the NDK
                                      # ships a universal/rosetta-capable set
    Linux)  echo "linux-x86_64" ;;
    *)      echo "unsupported" ;;
  esac
}

abi_triple() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86)         echo "i686-linux-android" ;;
    *) echo "ERROR: unknown ABI $1" >&2; exit 1 ;;
  esac
}

setup_abi_env() {
  local abi="$1"
  TRIPLE="$(abi_triple "${abi}")"
  TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/$(host_tag)"
  export TOOLCHAIN TRIPLE
  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  export STRIP="${TOOLCHAIN}/bin/llvm-strip"
  export READELF="${TOOLCHAIN}/bin/llvm-readelf"
  export CC="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API_LEVEL}-clang"
  export CXX="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API_LEVEL}-clang++"
  PREFIX="$(deps_prefix "${abi}")"
  export PREFIX
}

# Populates the global array CMAKE_ANDROID_ARGS.
#
# This is an array, not a string, on purpose: build paths containing spaces
# (and the enclosing project folder here is literally named "bitccoin-core
# Project") would be word-split into garbage arguments if these were expanded
# unquoted from a command substitution.
set_cmake_android_args() {
  local abi="$1" prefix
  prefix="$(deps_prefix "${abi}")"
  CMAKE_ANDROID_ARGS=(
    "-DCMAKE_TOOLCHAIN_FILE=${NDK}/build/cmake/android.toolchain.cmake"
    "-DANDROID_ABI=${abi}"
    "-DANDROID_PLATFORM=android-${ANDROID_API_LEVEL}"
    "-DANDROID_STL=${ANDROID_STL}"
    "-DCMAKE_INSTALL_PREFIX=${prefix}"
    "-DCMAKE_FIND_ROOT_PATH=${prefix}"
    "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
    "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH"
    "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH"
    "-DCMAKE_PREFIX_PATH=${prefix}"
    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
    # Android puts pthreads inside libc -- there is no separate -lpthread to
    # link. CMake's FindThreads probes for one, fails to find it, and reports
    # "Could NOT find Threads", which stops Boost's configure dead. These two
    # flags tell FindThreads the truth about bionic. Without them no CMake
    # project that calls find_package(Threads REQUIRED) will cross-compile.
    "-DTHREADS_PREFER_PTHREAD_FLAG=ON"
    "-DCMAKE_HAVE_LIBC_PTHREAD=ON"
  )
}

# A large C++ cross-build through Ninja + autotools-era helper scripts is not
# reliably space-safe no matter how careful this repo is. Rather than fail
# three hours in with an inscrutable error, refuse up front and say what to do.
check_paths_sane() {
  local bad=0 p
  for p in "${BUILD_DIR}" "${BITCOIN_SRC}" "${NDK:-}"; do
    [[ -z "${p}" ]] && continue
    case "${p}" in
      *[[:space:]]*) warn "path contains a space: ${p}"; bad=1 ;;
    esac
  done
  if (( bad )); then
    cat >&2 <<'MSG'

The native build needs space-free paths. Two ways out, either is fine:

  1. Point the build somewhere clean (recommended -- no files move):
       export BCN_BUILD_DIR="$HOME/bcn-build"
       export BITCOIN_SRC="$HOME/bitcoin-30.3"
     then re-run. Only build intermediates go there; the finished
     binaries still land in the project's app/src/main/jniLibs/.

  2. Move or rename the project folder so no ancestor has a space in it.

MSG
    exit 1
  fi
}

log()  { printf '\033[38;5;51m[bcn]\033[0m %s\n' "$*"; }
warn() { printf '\033[38;5;214m[bcn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[38;5;196m[bcn]\033[0m %s\n' "$*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

fetch_verify() {
  local url="$1" out="$2" want="$3"
  mkdir -p "$(dirname "${out}")"
  if [[ -f "${out}" ]] && [[ "$(sha256_of "${out}")" == "${want}" ]]; then
    log "cached  $(basename "${out}")"; return
  fi
  log "fetch   ${url}"
  curl -fL --retry 3 --connect-timeout 30 -o "${out}.part" "${url}"
  local got; got="$(sha256_of "${out}.part")"
  if [[ "${got}" != "${want}" ]]; then
    rm -f "${out}.part"
    die "SHA256 mismatch for ${url}
     expected ${want}
     got      ${got}
   Refusing to build. Do not work around this."
  fi
  mv "${out}.part" "${out}"
  log "verify  OK $(basename "${out}")"
}

nproc_portable() {
  if command -v nproc >/dev/null 2>&1; then nproc;
  elif command -v sysctl >/dev/null 2>&1; then sysctl -n hw.ncpu;
  else echo 4; fi
}
