#!/usr/bin/env bash
# Step 11 -- cross-compile Berkeley DB 4.8.30 for Android.
#
# ONLY needed for Bitcoin Core 28.x with legacy wallet support (--with-bdb).
# Core 29 and later dropped the legacy wallet entirely and this step does not
# apply to them; see docs/02-BUILD-NATIVE.md.
#
# ## Why this is unpleasant, in case you are debugging it
#
# BDB 4.8.30 was released in 2010. Its build system predates aarch64, predates
# Android as a target, and predates the clang that will compile it. Three things
# therefore have to be fixed before it will configure at all:
#
#   1. config.guess / config.sub in dist/ do not recognise
#      aarch64-linux-android. We overwrite them with Core's own copies.
#   2. `atomic_init` in dbinc/atomic.h collides with the C++11 <atomic> macro
#      of the same name. That is what clang_cxx_11.patch fixes -- it is Core's
#      own patch, taken verbatim from depends/patches/bdb/.
#   3. Modern clang promotes several 2010-era C sloppinesses to errors
#      (implicit function declarations, implicit int). We demote them again,
#      using exactly the flags Core's depends/packages/bdb.mk uses.
#
# The version and hash below come from Core 28.3's own depends/packages/bdb.mk.
# The ".NC" tarball is the no-crypto variant, which is what Core uses.

source "$(dirname "${BASH_SOURCE[0]}")/config.sh"
require_ndk
check_paths_sane

BDB_VERSION="4.8.30"
BDB_URL="https://download.oracle.com/berkeley-db/db-${BDB_VERSION}.NC.tar.gz"
BDB_SHA256="12edc0df75bf9abd7f82f821795bcee50f42cb2e5f76a6a281b85732798364ef"

: "${CORE28_SRC:?set CORE28_SRC to the bitcoin-28.x source tree (needed for its bdb patch and config.sub)}"

PATCH_FILE="${CORE28_SRC}/depends/patches/bdb/clang_cxx_11.patch"
[[ -f "${PATCH_FILE}" ]] || die "clang_cxx_11.patch not found at ${PATCH_FILE}"

mkdir -p "${DL_DIR}" "${SRC_DIR}"
fetch_verify "${BDB_URL}" "${DL_DIR}/db-${BDB_VERSION}.NC.tar.gz" "${BDB_SHA256}"

SRC="${SRC_DIR}/db-${BDB_VERSION}.NC"
if [[ ! -f "${SRC}/.unpacked" ]]; then
  log "unpack  db-${BDB_VERSION}.NC"
  rm -rf "${SRC}"
  tar -xf "${DL_DIR}/db-${BDB_VERSION}.NC.tar.gz" -C "${SRC_DIR}"
  touch "${SRC}/.unpacked"
fi

if [[ ! -f "${SRC}/.patched" ]]; then
  log "patch   clang_cxx_11.patch (Core's own, verbatim)"
  ( cd "${SRC}" && patch -p1 --forward < "${PATCH_FILE}" )
  # BDB's own config.guess/config.sub are from 2009 and reject the Android
  # triplet outright. Core ships current ones in depends/.
  cp -f "${CORE28_SRC}/depends/config.guess" "${CORE28_SRC}/depends/config.sub" "${SRC}/dist/"
  chmod +x "${SRC}/dist/config.guess" "${SRC}/dist/config.sub"
  touch "${SRC}/.patched"
fi

build_bdb() {
  local abi="$1" prefix bdir
  prefix="$(deps_prefix "${abi}")"
  bdir="${BUILD_DIR}/${abi}/bdb"

  if [[ -f "${prefix}/lib/libdb_cxx-4.8.a" ]]; then
    log "bdb ${abi}: up to date"; return
  fi

  log "bdb ${abi}"
  setup_abi_env "${abi}"
  rm -rf "${bdir}"; mkdir -p "${bdir}"

  # BDB insists on being configured from its own build_unix directory.
  ( cd "${bdir}" && \
    CC="${CC}" CXX="${CXX}" AR="${AR}" RANLIB="${RANLIB}" \
    CFLAGS="-O2 -fPIC -Wno-error=implicit-function-declaration -Wno-error=format-security -Wno-error=implicit-int -Wno-error=int-conversion" \
    CXXFLAGS="-O2 -fPIC" \
    "${SRC}/dist/configure" \
      --host="${TRIPLE}" \
      --build="$(bash "${SRC}/dist/config.guess")" \
      --prefix="${prefix}" \
      --disable-shared \
      --enable-cxx \
      --disable-replication \
      --enable-option-checking \
      > "${bdir}/configure.log" 2>&1 ) || {
        warn "BDB configure failed. Tail of ${bdir}/configure.log:"
        tail -30 "${bdir}/configure.log" >&2
        die "bdb configure failed for ${abi}"
      }

  make -C "${bdir}" -j"$(nproc_portable)" >> "${bdir}/build.log" 2>&1 || {
    warn "BDB build failed. Tail of ${bdir}/build.log:"
    tail -40 "${bdir}/build.log" >&2
    die "bdb build failed for ${abi}"
  }
  make -C "${bdir}" install >> "${bdir}/build.log" 2>&1

  [[ -f "${prefix}/lib/libdb_cxx-4.8.a" ]] || die "libdb_cxx-4.8.a not produced for ${abi}"
  log "bdb ${abi}: $(du -h "${prefix}/lib/libdb_cxx-4.8.a" | cut -f1)"
}

for abi in ${ABIS}; do
  mkdir -p "$(deps_prefix "${abi}")"
  build_bdb "${abi}"
done

log "step 11 complete"
