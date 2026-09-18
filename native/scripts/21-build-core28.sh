#!/usr/bin/env bash
# Step 21 -- cross-compile Bitcoin Core 28.x (autotools) for Android.
#
# Use this INSTEAD of 20-build-core.sh when targeting Core 28.x. The two are
# separate scripts rather than one parameterised script because the build
# systems are genuinely different: 28.x is autotools (configure.ac, autogen.sh)
# and 29+ is CMake. Core switched in 29.
#
# ## Why you would target 28.x at all
#
# One reason only: `src/wallet/bdb.cpp`, the full read/write legacy Berkeley DB
# wallet backend. Core 29 removed it. With it you can open and keep operating a
# legacy `wallet.dat`; without it you can only migrate one, once.
#
# Note that migration works on BOTH versions -- `src/wallet/migrate.cpp` and the
# `migratewallet` RPC are present in 28.x and 30.x alike, and neither needs
# libdb. So if all you want is to IMPORT a wallet.dat, 30.x does that and this
# script is unnecessary. See docs/02-BUILD-NATIVE.md.
#
# Requires: 11-build-bdb.sh must have run first (for --with-bdb).

source "$(dirname "${BASH_SOURCE[0]}")/config.sh"
require_ndk
check_paths_sane

: "${CORE28_SRC:?set CORE28_SRC to the bitcoin-28.x source tree}"
[[ -f "${CORE28_SRC}/configure.ac" ]] || die "not an autotools Core tree: ${CORE28_SRC}
  (28.x has configure.ac; 29+ has CMakeLists.txt and needs 20-build-core.sh instead)"

CORE_VER="$(sed -n 's/^define(_CLIENT_VERSION_MAJOR, \([0-9]*\))/\1/p' "${CORE28_SRC}/configure.ac" | head -1)"
log "Bitcoin Core source: ${CORE28_SRC} (major ${CORE_VER:-?})"
log "NDK: ${NDK} (${NDK_VERSION})  API: ${ANDROID_API_LEVEL}"

# autogen only needs running once per tree.
if [[ ! -x "${CORE28_SRC}/configure" ]]; then
  log "autogen.sh (generating configure)"
  ( cd "${CORE28_SRC}" && ./autogen.sh > /dev/null 2>&1 ) || die "autogen.sh failed"
fi

build_core28() {
  local abi="$1" prefix bdir
  prefix="$(deps_prefix "${abi}")"
  bdir="${BUILD_DIR}/${abi}/core28"

  [[ -f "${prefix}/lib/libevent_core.a" ]] || die "deps missing for ${abi}; run 10-build-deps.sh"
  [[ -f "${prefix}/lib/libdb_cxx-4.8.a" ]] || die "BDB missing for ${abi}; run 11-build-bdb.sh"

  log "=== Bitcoin Core 28.x for ${abi} ==="
  setup_abi_env "${abi}"
  mkdir -p "${bdir}"

  # -g0: the NDK adds -g by default, and full DWARF on Core's largest
  # translation units pushes a single clang past 6 GB. Step 30 strips the
  # binary regardless. Same reasoning as 20-build-core.sh.
  local cflags="-O2 -g0 -fPIE"
  local ldflags="-fPIE -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -static-libstdc++"

  # PKG_CONFIG_LIBDIR (not just _PATH) so pkg-config cannot fall through to the
  # build host's /usr/lib and silently link an x86 library into an arm64 binary.
  ( cd "${bdir}" && \
    PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig" \
    PKG_CONFIG_PATH="${prefix}/lib/pkgconfig" \
    CC="${CC}" CXX="${CXX}" AR="${AR}" RANLIB="${RANLIB}" STRIP="${STRIP}" \
    CFLAGS="${cflags}" CXXFLAGS="${cflags}" LDFLAGS="${ldflags}" \
    CPPFLAGS="-I${prefix}/include" \
    BDB_CFLAGS="-I${prefix}/include" \
    BDB_LIBS="-L${prefix}/lib -ldb_cxx-4.8" \
    "${CORE28_SRC}/configure" \
      --host="${TRIPLE}" \
      --build="$(bash "${CORE28_SRC}/depends/config.guess")" \
      --prefix="${bdir}/install" \
      --with-boost="${prefix}" \
      --with-bdb \
      --with-sqlite=yes \
      --enable-wallet \
      --with-gui=no \
      --without-qrencode \
      --without-miniupnpc \
      --without-natpmp \
      --disable-zmq \
      --disable-tests \
      --disable-gui-tests \
      --disable-bench \
      --disable-fuzz \
      --disable-fuzz-binary \
      --disable-man \
      --disable-external-signer \
      > "${bdir}/configure.log" 2>&1 ) || {
        warn "configure failed. Tail of ${bdir}/configure.log:"
        tail -40 "${bdir}/configure.log" >&2
        die "Core 28 configure failed for ${abi}"
      }

  log "configure OK; compiling (this is the long part)"
  make -C "${bdir}" -j"${JOBS:-$(nproc_portable)}" > "${bdir}/build.log" 2>&1 || {
    warn "build failed. Tail of ${bdir}/build.log:"
    tail -40 "${bdir}/build.log" >&2
    die "Core 28 build failed for ${abi}"
  }

  mkdir -p "${OUT_DIR}/${abi}"
  local found=0
  for name in bitcoind bitcoin-cli; do
    local path
    path="$(find "${bdir}/src" -maxdepth 1 -type f -name "${name}" -perm -u+x | head -1)"
    [[ -n "${path}" ]] || die "build finished but ${name} was not produced for ${abi}"
    cp "${path}" "${OUT_DIR}/${abi}/${name}"
    found=$((found+1))
  done
  # Record which source tree really produced these, so step 30's provenance
  # file cannot claim the wrong one. BITCOIN_SRC and CORE28_SRC are often both
  # set at once when both Core versions are on disk.
  printf '%s\n' "${CORE28_SRC}" > "${OUT_DIR}/${abi}/.core-source"

  log "built ${found} binaries for ${abi}"
  ls -lh "${OUT_DIR}/${abi}"
}

for abi in ${ABIS}; do
  build_core28 "${abi}"
done

log "step 21 complete"
