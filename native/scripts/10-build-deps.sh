#!/usr/bin/env bash
# Step 10 -- cross-compile libevent, SQLite and Boost headers for each ABI.
source "$(dirname "${BASH_SOURCE[0]}")/config.sh"
require_ndk
check_paths_sane
JOBS="$(nproc_portable)"

build_libevent() {
  local abi="$1" prefix bdir
  prefix="$(deps_prefix "${abi}")"
  bdir="${BUILD_DIR}/${abi}/libevent"
  [[ -f "${prefix}/lib/libevent_core.a" ]] && { log "libevent ${abi}: up to date"; return; }
  set_cmake_android_args "${abi}"
  log "libevent ${abi}"
  # Flags mirror depends/packages/libevent.mk.
  cmake -S "${SRC_DIR}/libevent-${LIBEVENT_VERSION}" -B "${bdir}" -G Ninja \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DEVENT__DISABLE_BENCHMARK=ON \
    -DEVENT__DISABLE_OPENSSL=ON \
    -DEVENT__DISABLE_MBEDTLS=ON \
    -DEVENT__DISABLE_SAMPLES=ON \
    -DEVENT__DISABLE_REGRESS=ON \
    -DEVENT__DISABLE_TESTS=ON \
    -DEVENT__LIBRARY_TYPE=STATIC \
    -DCMAKE_C_FLAGS="-D_GNU_SOURCE -D_FORTIFY_SOURCE=3 -O2"
  cmake --build "${bdir}" -j "${JOBS}"
  cmake --install "${bdir}"
}

build_sqlite() {
  local abi="$1" prefix sdir
  prefix="$(deps_prefix "${abi}")"
  sdir="${SRC_DIR}/sqlite-autoconf-${SQLITE_VERSION}"
  [[ -f "${prefix}/lib/libsqlite3.a" ]] && { log "sqlite ${abi}: up to date"; return; }
  log "sqlite ${abi}"
  setup_abi_env "${abi}"
  mkdir -p "${prefix}/include" "${prefix}/lib/pkgconfig" "${BUILD_DIR}/${abi}/sqlite"

  # We compile the amalgamation directly rather than running the autoconf
  # script. Cross-configuring autotools under the NDK is fragile and buys us
  # nothing here: sqlite3.c is a single translation unit.
  #
  # The -D flags are exactly Core's, from depends/packages/sqlite.mk. They must
  # match, because several of them (notably SQLITE_OMIT_AUTOINIT and
  # SQLITE_DQS=0) change the library's API contract, and Core is written
  # against that contract.
  local defs=(
    -DSQLITE_DQS=0
    -DSQLITE_DEFAULT_MEMSTATUS=0
    -DSQLITE_OMIT_DEPRECATED
    -DSQLITE_OMIT_SHARED_CACHE
    -DSQLITE_OMIT_JSON
    -DSQLITE_LIKE_DOESNT_MATCH_BLOBS
    -DSQLITE_OMIT_DECLTYPE
    -DSQLITE_OMIT_PROGRESS_CALLBACK
    -DSQLITE_OMIT_AUTOINIT
    -DSQLITE_ENABLE_API_ARMOR
    -DSQLITE_THREADSAFE=1
    -DSQLITE_TEMP_STORE=2
  )
  "${CC}" -O2 -fPIC -c "${sdir}/sqlite3.c" \
      -o "${BUILD_DIR}/${abi}/sqlite/sqlite3.o" "${defs[@]}"
  "${AR}" rcs "${prefix}/lib/libsqlite3.a" "${BUILD_DIR}/${abi}/sqlite/sqlite3.o"
  "${RANLIB}" "${prefix}/lib/libsqlite3.a"
  cp "${sdir}/sqlite3.h" "${sdir}/sqlite3ext.h" "${prefix}/include/"

  # CMake's stock FindSQLite3 wants the header (for version parsing) and the
  # library; both are now in place. The .pc file is belt-and-braces for the
  # pkg-config fallback path.
  local ver="${SQLITE_VERSION}"
  local dotted="${ver:0:1}.$((10#${ver:1:2})).$((10#${ver:3:2}))"
  cat > "${prefix}/lib/pkgconfig/sqlite3.pc" <<PC
prefix=${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: SQLite
Description: SQL database engine
Version: ${dotted}
Libs: -L\${libdir} -lsqlite3
Cflags: -I\${includedir}
PC
}

build_boost() {
  local abi="$1" prefix bdir
  prefix="$(deps_prefix "${abi}")"
  bdir="${BUILD_DIR}/${abi}/boost"
  [[ -d "${prefix}/include/boost/multi_index" ]] && { log "boost ${abi}: up to date"; return; }
  set_cmake_android_args "${abi}"
  log "boost ${abi} (headers only)"
  # date_time is only needed by Core 28.x (wallet/rpc/util.cpp includes
  # boost/date_time/posix_time). 29+ dropped it. Including it always costs
  # nothing -- it is header-only here -- and saves a confusing mid-build
  # failure if you switch Core versions.
  #
  # Core uses Boost strictly for headers (see cmake/module/AddBoostIfNeeded.cmake:
  # "Although only Boost headers are used to build Bitcoin Core"). We still build
  # via Boost's CMake so we get a real BoostConfig.cmake, because Core does
  # find_package(Boost 1.74.0 REQUIRED CONFIG) and CONFIG mode needs it.
  cmake -S "${SRC_DIR}/boost-${BOOST_VERSION}" -B "${bdir}" -G Ninja \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBOOST_INCLUDE_LIBRARIES="multi_index;signals2;test;date_time" \
    -DBOOST_TEST_HEADERS_ONLY=ON \
    -DBOOST_ENABLE_MPI=OFF \
    -DBOOST_ENABLE_PYTHON=OFF \
    -DBOOST_INSTALL_LAYOUT=system \
    -DBUILD_TESTING=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_ICU=ON
  cmake --build "${bdir}" -j "${JOBS}"
  cmake --install "${bdir}"
}

for abi in ${ABIS}; do
  log "=== dependencies for ${abi} ==="
  mkdir -p "$(deps_prefix "${abi}")"
  build_boost    "${abi}"
  build_libevent "${abi}"
  build_sqlite   "${abi}"
done

log "step 10 complete"
