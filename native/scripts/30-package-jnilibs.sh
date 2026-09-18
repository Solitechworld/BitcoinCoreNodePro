#!/usr/bin/env bash
# Step 30 -- strip, rename and verify the binaries, then drop them into jniLibs.
#
# WHY THE RENAME: since Android 10 (API 29) an app may not exec() a file from
# its writable data directory (W^X). The documented exception is the read-only
# nativeLibraryDir the package installer creates. Anything the installer is to
# place there must be named lib*.so and live in lib/<abi>/ inside the APK.
# So the bitcoind ELF -- a perfectly ordinary PIE executable -- ships under the
# name libbitcoind.so. It is not a shared library and nothing dlopen()s it;
# the app exec()s it by absolute path. This is the same trick the official Tor
# Android package uses for its tor binary.
source "$(dirname "${BASH_SOURCE[0]}")/config.sh"
require_ndk

declare -A RENAME=( [bitcoind]="libbitcoind.so" [bitcoin-cli]="libbitcoincli.so" )

check_alignment() {   # check_alignment <file> <abi>
  local f="$1" abi="$2"
  # arm64 is where 16 KB pages actually ship; x86_64 emulator images use 4 KB.
  [[ "${abi}" == "arm64-v8a" ]] || return 0
  local bad
  bad="$("${READELF}" -lW "${f}" 2>/dev/null \
        | awk '$1=="LOAD" {print $NF}' \
        | grep -v -E '^0x(4000|10000|100000)$' || true)"
  if [[ -n "${bad}" ]]; then
    warn "16 KB alignment check FAILED for $(basename "${f}")"
    warn "  LOAD segment alignments found: $(echo ${bad} | tr '\n' ' ')"
    warn "  Google Play requires 16 KB page compatibility. Use NDK r27+ and keep"
    warn "  -Wl,-z,max-page-size=16384 on the link line."
    return 1
  fi
  log "16 KB alignment OK: $(basename "${f}")"
}

overall=0
for abi in ${ABIS}; do
  setup_abi_env "${abi}"
  src="${OUT_DIR}/${abi}"
  dst="${JNILIBS_DIR}/${abi}"
  [[ -d "${src}" ]] || die "no build output for ${abi}; run 20-build-core.sh first"
  mkdir -p "${dst}"

  for bin in "${!RENAME[@]}"; do
    [[ -f "${src}/${bin}" ]] || die "missing ${src}/${bin}"
    out="${dst}/${RENAME[$bin]}"
    # Keep the unstripped original for Play's native debug symbols upload.
    # Play Console wants a zip with lib/<abi>/<name>.so carrying full symbol
    # tables so native crash stacks symbolicate; once stripped here those
    # symbols are gone forever (the exact cause of the "you've not uploaded
    # debug symbols" warning on the first submission).
    # Layout must be lib/<abi>/<name>.so -- that is what Play's symbol
    # uploader expects, and what the zip step at the bottom of this file
    # globs for. It used to be native-symbols/<abi>/lib/, which the glob
    # never matched, so the zip was silently never built. Do not swap the
    # two path components back.
    symdir="${JNILIBS_DIR}/../native-symbols/lib/${abi}"
    mkdir -p "${symdir}"
    cp "${src}/${bin}" "${symdir}/${RENAME[$bin]}"
    cp "${src}/${bin}" "${out}"
    "${STRIP}" --strip-unneeded "${out}"
    chmod 0755 "${out}"
    check_alignment "${out}" "${abi}" || overall=1
    printf '  %-22s %s\n' "${RENAME[$bin]}" "$(du -h "${out}" | cut -f1)"
  done

  # Which source tree actually produced these. Written by step 20 or 21; falling
  # back to BITCOIN_SRC would be a guess, and a provenance file that guesses is
  # worse than none at all.
  core_src="$(cat "${src}/.core-source" 2>/dev/null || echo "UNKNOWN - rebuild with 20-build-core.sh or 21-build-core28.sh")"
  core_ver="$(sed -n 's/^define(_CLIENT_VERSION_MAJOR, \([0-9]*\))/\1/p' "${core_src}/configure.ac" 2>/dev/null | head -1)"
  [[ -n "${core_ver}" ]] || core_ver="$(sed -n 's/.*VERSION \([0-9][0-9.]*\).*/\1/p' "${core_src}/CMakeLists.txt" 2>/dev/null | head -1)"

  # Record provenance next to the binaries so a build is always traceable.
  cat > "${dst}/../BUILD-INFO-${abi}.txt" <<INFO
Bitcoin Core Node native payload
ABI:           ${abi}
Built:         $(date -u '+%Y-%m-%dT%H:%M:%SZ')
NDK:           ${NDK_VERSION}
API level:     ${ANDROID_API_LEVEL}
STL:           ${ANDROID_STL}
Core source:   ${core_src}
Core version:  ${core_ver:-unknown}
libevent:      ${LIBEVENT_VERSION}
sqlite:        ${SQLITE_VERSION}
boost:         ${BOOST_VERSION}
sha256 bitcoind:    $(sha256_of "${dst}/libbitcoind.so")
sha256 bitcoin-cli: $(sha256_of "${dst}/libbitcoincli.so")
INFO
done

if (( overall != 0 )); then
  die "packaging finished but alignment checks failed -- do not ship this build to Play"
fi

# Play Console → Release → App bundle explorer → "Upload debug symbols" takes
# exactly this zip: unstripped .so under lib/<abi>/. Built only when unstripped
# copies exist, so a partial run never produces a misleading artifact.
symroot="${JNILIBS_DIR}/../native-symbols"
if compgen -G "${symroot}/lib/*/*.so" >/dev/null; then
  (cd "${symroot}" && zip -qr native-debug-symbols.zip lib) &&
    log "debug symbols zip: ${symroot}/native-debug-symbols.zip"
fi
log "step 30 complete -- jniLibs populated at ${JNILIBS_DIR}"
