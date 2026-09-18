#!/usr/bin/env bash
# Step 20 -- cross-compile Bitcoin Core 30.3 (bitcoind + bitcoin-cli) for Android.
source "$(dirname "${BASH_SOURCE[0]}")/config.sh"
require_ndk
check_paths_sane
# Bitcoin Core's heaviest translation units (net.cpp, validation.cpp,
# txmempool.cpp) can each take ~2 GB in clang. Two in parallel is enough to get
# the build OOM-killed on an 8 GB machine -- and ninja dies silently, leaving a
# log that just stops mid-file with no error. Override with JOBS=1 if that
# happens; ninja resumes from the last completed object.
JOBS="${JOBS:-$(nproc_portable)}"

[[ -f "${BITCOIN_SRC}/CMakeLists.txt" ]] || die "Bitcoin Core source not found at:
    ${BITCOIN_SRC}
  Extract bitcoin-30.3.zip and point BITCOIN_SRC at the resulting directory:
    export BITCOIN_SRC=/path/to/bitcoin-30.3"

CORE_VER="$(sed -n 's/^ *VERSION \([0-9.]*\).*/\1/p' "${BITCOIN_SRC}/CMakeLists.txt" | head -1)"
log "Bitcoin Core source: ${BITCOIN_SRC} (version ${CORE_VER:-unknown})"
log "NDK: ${NDK} (${NDK_VERSION})  API: ${ANDROID_API_LEVEL}  STL: ${ANDROID_STL}"

build_core() {
  local abi="$1" prefix bdir
  prefix="$(deps_prefix "${abi}")"
  bdir="${BUILD_DIR}/${abi}/core"

  [[ -f "${prefix}/lib/libevent_core.a" ]] || die "deps missing for ${abi}; run 10-build-deps.sh first"

  set_cmake_android_args "${abi}"
  log "=== Bitcoin Core for ${abi} ==="

  # -g0 is deliberate. The NDK toolchain adds -g by default, and full DWARF for
  # Core's largest translation units (net.cpp, validation.cpp, txmempool.cpp)
  # pushes a single clang process past 6 GB -- enough to get OOM-killed on an
  # 8 GB machine, with ninja dying silently mid-file and no error in the log.
  # Step 30 strips the binary anyway, so the debug info was never going to ship.
  # If you need a debuggable node, build one ABI at a time on a bigger machine
  # and drop these two flags.
  #
  # Feature selection rationale -- every OFF here is a deliberate decision:
  #
  #  BUILD_GUI            Qt on a handset is unusable and adds ~40 MB. Our GUI is Compose.
  #  BUILD_TESTS/BENCH/   Test binaries would triple build time and ship nothing useful.
  #  FUZZ/TX/UTIL/
  #  WALLET_TOOL
  #  BUILD_BITCOIN_BIN    The Core 30 `bitcoin` multiplexer wrapper; we exec bitcoind directly.
  #  ENABLE_IPC           Needs Cap'n Proto + libmultiprocess cross-built. Not worth it;
  #                       we are not running the multiprocess node/gui split on mobile.
  #  ENABLE_EXTERNAL_SIGNER  Uses boost::process to spawn HWI. Android forbids exec of
  #                       arbitrary binaries from the data dir, so this could never work.
  #                       Hardware-wallet support in this app goes through PSBT instead.
  #  WITH_ZMQ             Would add libzmq to the cross-build. We use waitfornewblock and
  #                       the debug.log tailer for notifications instead. Flip to ON and add
  #                       a zeromq build step if you want ZMQ.
  #  WITH_USDT            Linux tracing. Meaningless here.
  #  WITH_CCACHE          Off for reproducibility of the shipped artifact.
  #
  # ENABLE_WALLET is ON: the whole hot-wallet feature set depends on Core's own
  # descriptor wallet, which is what SQLite is here for.
  cmake -S "${BITCOIN_SRC}" -B "${bdir}" -G Ninja \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-g0" \
    -DCMAKE_CXX_FLAGS="-g0" \
    -DBUILD_DAEMON=ON \
    -DBUILD_CLI=ON \
    -DENABLE_WALLET=ON \
    -DBUILD_GUI=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_BENCH=OFF \
    -DBUILD_FUZZ_BINARY=OFF \
    -DBUILD_TX=OFF \
    -DBUILD_UTIL=OFF \
    -DBUILD_UTIL_CHAINSTATE=OFF \
    -DBUILD_WALLET_TOOL=OFF \
    -DBUILD_BITCOIN_BIN=OFF \
    -DBUILD_KERNEL_LIB=OFF \
    -DENABLE_IPC=OFF \
    -DENABLE_EXTERNAL_SIGNER=OFF \
    -DWITH_ZMQ=OFF \
    -DWITH_USDT=OFF \
    -DWITH_CCACHE=OFF \
    -DINSTALL_MAN=OFF \
    -DWERROR=OFF \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=${MAX_PAGE_SIZE} -Wl,-z,common-page-size=${MAX_PAGE_SIZE}" \
    -DCMAKE_INSTALL_PREFIX="${BUILD_DIR}/${abi}/core-install"

  cmake --build "${bdir}" -j "${JOBS}"

  mkdir -p "${OUT_DIR}/${abi}"
  local found=0
  for name in bitcoind bitcoin-cli; do
    local path
    path="$(find "${bdir}" -type f -name "${name}" -perm -u+x | head -1)"
    [[ -n "${path}" ]] || die "build finished but ${name} was not produced for ${abi}"
    cp "${path}" "${OUT_DIR}/${abi}/${name}"
    found=$((found+1))
  done
  # Record which source tree really produced these, so step 30's provenance
  # file cannot claim the wrong one. BITCOIN_SRC and CORE28_SRC are often both
  # set at once when both Core versions are on disk.
  printf '%s\n' "${BITCOIN_SRC}" > "${OUT_DIR}/${abi}/.core-source"

  log "built ${found} binaries for ${abi}"
  ls -lh "${OUT_DIR}/${abi}"
}

for abi in ${ABIS}; do
  build_core "${abi}"
done

log "step 20 complete"
