#!/usr/bin/env bash
# Step 00 -- download and verify Bitcoin Core's dependencies, then unpack+patch.
source "$(dirname "${BASH_SOURCE[0]}")/config.sh"

mkdir -p "${DL_DIR}" "${SRC_DIR}"

fetch_verify "${LIBEVENT_URL}" "${DL_DIR}/libevent-${LIBEVENT_VERSION}.tar.gz" "${LIBEVENT_SHA256}"
fetch_verify "${SQLITE_URL}"   "${DL_DIR}/sqlite-autoconf-${SQLITE_VERSION}.tar.gz" "${SQLITE_SHA256}"
fetch_verify "${BOOST_URL}"    "${DL_DIR}/boost-${BOOST_VERSION}-cmake.tar.gz" "${BOOST_SHA256}"

unpack() {   # unpack <tarball> <expected-dirname> <stamp>
  local tarball="$1" dirname="$2"
  if [[ -f "${SRC_DIR}/${dirname}/.unpacked" ]]; then
    log "unpacked already: ${dirname}"; return
  fi
  log "unpack  ${dirname}"
  rm -rf "${SRC_DIR}/${dirname}"
  tar -xf "${tarball}" -C "${SRC_DIR}"
  touch "${SRC_DIR}/${dirname}/.unpacked"
}

unpack "${DL_DIR}/libevent-${LIBEVENT_VERSION}.tar.gz" "libevent-${LIBEVENT_VERSION}"
unpack "${DL_DIR}/sqlite-autoconf-${SQLITE_VERSION}.tar.gz" "sqlite-autoconf-${SQLITE_VERSION}"
unpack "${DL_DIR}/boost-${BOOST_VERSION}-cmake.tar.gz" "boost-${BOOST_VERSION}"

apply_patch() {  # apply_patch <srcdir> <patchfile>
  local dir="$1" patch="$2" stamp
  stamp="${dir}/.patched-$(basename "${patch}")"
  [[ -f "${stamp}" ]] && { log "patched already: $(basename "${patch}")"; return; }
  log "patch   $(basename "${patch}") -> $(basename "${dir}")"
  ( cd "${dir}" && patch -p1 --forward < "${patch}" )
  touch "${stamp}"
}

# Both patches are copied verbatim from Bitcoin Core 30.3's depends/patches/.
# libevent: raises cmake_minimum_required to 3.5 so CMake >= 4.0 will configure it.
apply_patch "${SRC_DIR}/libevent-${LIBEVENT_VERSION}" "${PATCH_DIR}/libevent/cmake_fixups.patch"
# boost: adds BOOST_TEST_HEADERS_ONLY so no Boost.Test .cpp needs cross-compiling.
apply_patch "${SRC_DIR}/boost-${BOOST_VERSION}" "${PATCH_DIR}/boost/skip_compiled_targets.patch"

log "step 00 complete"
