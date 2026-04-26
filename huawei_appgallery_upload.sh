#!/usr/bin/env bash
# =============================================================================
# Huawei AppGallery Connect – APK / AAB Upload Script
# =============================================================================
# Prerequisites:
#   Linux : sudo apt install curl jq
#   macOS : brew install curl jq        (shasum is built-in; no extra install needed)
#   - A Huawei AppGallery Connect API client (Client ID + Client Secret)
#     https://developer.huawei.com/consumer/en/doc/development/AppGallery-connect-Guides/agcapi-getstarted
#
# Usage:
#   chmod +x huawei_appgallery_upload.sh
#   ./huawei_appgallery_upload.sh [options]
#
# Options:
#   -c  <client_id>       AppGallery Connect API Client ID      (required)
#   -s  <client_secret>   AppGallery Connect API Client Secret  (required)
#   -a  <app_id>          App ID from AppGallery Connect        (required)
#   -f  <file_path>       Path to your .apk or .aab file        (required)
#   -g  <region>          Target region (default: global)
#                           global  → connect-api.cloud.huawei.com
#                           cn      → connect-api-drcn.dbankcloud.cn   (Mainland China)
#                           asia    → connect-api-dra.cloud.huawei.com (Asia Pacific)
#                           eu      → connect-api-dre.cloud.huawei.com (Europe)
#                           am      → connect-api-drru.cloud.huawei.com (Americas/Russia)
#   -l  <lang>            Language/locale (default: en-US; use zh-CN for China)
#   -n                    Pre-flight only: resolve CDN hostname and exit (no upload)
#   -h                    Show this help message
#
# Behaviour:
#   - Uploads the APK/AAB to AppGallery Connect and stops there.
#     The app is NOT submitted for review — do that manually in the console
#     or via a separate pipeline step when you are ready.
#   - Before uploading, the script probes the resolved CDN hostname with a
#     TCP connect test. If the host is unreachable (blocked by firewall) the
#     script halts immediately with exit code 1 and tells you which host to
#     whitelist. No partial upload is attempted.
#
# Pre-flight mode (-n):
#   Authenticates, calls /upload-url, extracts the dynamic CDN hostname from
#   the JSON response, prints a firewall whitelist report, and exits — without
#   uploading anything. Hand the output to your network team to whitelist the
#   host, then re-run without -n to perform the actual upload.
#
# Example – Mainland China upload:
#   ./huawei_appgallery_upload.sh -g cn -l zh-CN \
#       -c "your_client_id" -s "your_client_secret" \
#       -a "your_app_id"    -f "/path/to/app-release.aab"
#
# Example – check CDN hostname first (Europe region):
#   ./huawei_appgallery_upload.sh -n -g eu \
#       -c "your_client_id" -s "your_client_secret" \
#       -a "your_app_id"    -f "/path/to/app-release.aab"
#
# Example – global upload (no review submission):
#   ./huawei_appgallery_upload.sh \
#       -c "your_client_id" -s "your_client_secret" \
#       -a "your_app_id"    -f "/path/to/app-release.aab"
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
die()     { error "$*"; exit 1; }

# ---------------------------------------------------------------------------
# Cross-platform helpers  (macOS vs Linux)
# ---------------------------------------------------------------------------

# sha256 of a file → hex string
file_sha256() {
  if command -v sha256sum &>/dev/null; then
    sha256sum "$1" | awk '{print $1}'          # Linux (GNU coreutils)
  elif command -v shasum &>/dev/null; then
    shasum -a 256 "$1" | awk '{print $1}'      # macOS / BSD
  else
    die "No SHA-256 tool found (tried sha256sum, shasum). Please install coreutils."
  fi
}

# Human-readable byte size  (e.g. 23.4 MiB)
human_size() {
  local bytes=$1
  if command -v numfmt &>/dev/null; then
    numfmt --to=iec-i --suffix=B "$bytes" 2>/dev/null && return
  fi
  # Pure-bash fallback – works on macOS without GNU coreutils
  local units=("B" "KiB" "MiB" "GiB" "TiB")
  local idx=0
  local val=$bytes
  while (( val >= 1024 && idx < 4 )); do
    val=$(( val / 1024 ))
    (( idx++ ))
  done
  echo "${val} ${units[$idx]}"
}

# Detect OS for any future platform-specific branching
OS_TYPE="linux"
[[ "$(uname -s)" == "Darwin" ]] && OS_TYPE="macos"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CLIENT_ID=""
CLIENT_SECRET=""
APP_ID=""
FILE_PATH=""
LANG="en-US"
REGION="global"        # -g : global | cn | asia | eu | am
PREFLIGHT_ONLY=false   # -n : resolve CDN hostname then exit, no upload

# Set after resolve_region() is called
AUTH_URL=""
BASE_URL=""
REGION_LABEL=""

# Populated by get_upload_url; used by check_cdn_reachable and preflight_probe
CDN_HOST=""
CDN_CHUNK_HOST=""
CHUNK_UPLOAD_URL=""

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,1\}//'
  exit 0
}

while getopts ":c:s:a:f:l:g:nh" opt; do
  case $opt in
    c) CLIENT_ID="$OPTARG" ;;
    s) CLIENT_SECRET="$OPTARG" ;;
    a) APP_ID="$OPTARG" ;;
    f) FILE_PATH="$OPTARG" ;;
    l) LANG="$OPTARG" ;;
    g) REGION="$OPTARG" ;;
    n) PREFLIGHT_ONLY=true ;;
    h) usage ;;
    :) die "Option -$OPTARG requires an argument." ;;
    \?) die "Unknown option: -$OPTARG" ;;
  esac
done

# ---------------------------------------------------------------------------
# Region resolution – maps -g code to AUTH_URL / BASE_URL
# ---------------------------------------------------------------------------
resolve_region() {
  local base_domain
  case "${REGION,,}" in   # lowercase for case-insensitive matching
    global|"")
      base_domain="connect-api.cloud.huawei.com"
      REGION_LABEL="Global"
      ;;
    cn|china)
      base_domain="connect-api-drcn.dbankcloud.cn"
      REGION_LABEL="Mainland China"
      ;;
    asia|apac)
      base_domain="connect-api-dra.cloud.huawei.com"
      REGION_LABEL="Asia Pacific"
      ;;
    eu|europe)
      base_domain="connect-api-dre.cloud.huawei.com"
      REGION_LABEL="Europe"
      ;;
    am|americas|ru|russia)
      base_domain="connect-api-drru.cloud.huawei.com"
      REGION_LABEL="Americas / Russia"
      ;;
    *)
      die "Unknown region: '${REGION}'. Valid values: global, cn, asia, eu, am"
      ;;
  esac

  AUTH_URL="https://${base_domain}/api/oauth2/v1/token"
  BASE_URL="https://${base_domain}/api/publish/v2"
}

# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------
check_deps() {
  local missing=()
  for cmd in curl jq; do
    command -v "$cmd" &>/dev/null || missing+=("$cmd")
  done

  if [[ ${#missing[@]} -gt 0 ]]; then
    error "Missing required tools: ${missing[*]}"
    if [[ "$OS_TYPE" == "macos" ]]; then
      echo -e "  Install with Homebrew:  brew install ${missing[*]}" >&2
    else
      echo -e "  Install with apt:       sudo apt install ${missing[*]}" >&2
    fi
    exit 1
  fi

  # Warn if neither SHA-256 tool is available (caught later in file_sha256)
  if ! command -v sha256sum &>/dev/null && ! command -v shasum &>/dev/null; then
    warn "No SHA-256 tool found. Install coreutils (Linux) or ensure shasum is on PATH (macOS)."
  fi
}

validate_inputs() {
  [[ -z "$CLIENT_ID"     ]] && die "Client ID (-c) is required."
  [[ -z "$CLIENT_SECRET" ]] && die "Client Secret (-s) is required."
  [[ -z "$APP_ID"        ]] && die "App ID (-a) is required."

  # File is required for a real upload but optional for pre-flight-only mode.
  if [[ "$PREFLIGHT_ONLY" == false ]]; then
    [[ -z "$FILE_PATH" ]] && die "File path (-f) is required."
    [[ -f "$FILE_PATH" ]] || die "File not found: $FILE_PATH"

    local ext="${FILE_PATH##*.}"
    [[ "$ext" == "apk" || "$ext" == "aab" ]] \
      || die "File must be an .apk or .aab (got: .$ext)"
  else
    # In pre-flight mode we still need an extension to call /upload-url correctly.
    # Derive it from -f if supplied, otherwise default to 'apk'.
    if [[ -n "$FILE_PATH" ]]; then
      local ext="${FILE_PATH##*.}"
      [[ "$ext" == "apk" || "$ext" == "aab" ]] \
        || die "File must be an .apk or .aab (got: .$ext)"
    else
      FILE_PATH="preflight.apk"   # dummy – only the extension is used
    fi
  fi
}

# ---------------------------------------------------------------------------
# Step 1 – Obtain access token
# ---------------------------------------------------------------------------
get_access_token() {
  local step_label="${1:-Step 1}"
  info "${step_label} – Obtaining access token …"

  local body
  body=$(jq -n \
    --arg grantType "client_credentials" \
    --arg clientId   "$CLIENT_ID" \
    --arg clientSecret "$CLIENT_SECRET" \
    '{grant_type: $grantType, client_id: $clientId, client_secret: $clientSecret}')

  local response
  response=$(curl -sf -X POST "$AUTH_URL" \
    -H "Content-Type: application/json" \
    -d "$body") \
    || die "Failed to reach the Huawei OAuth2 endpoint."

  # Check for API-level error
  local ret_code
  ret_code=$(echo "$response" | jq -r '.ret.code // .error // empty')
  if [[ -n "$ret_code" && "$ret_code" != "0" ]]; then
    local ret_msg
    ret_msg=$(echo "$response" | jq -r '.ret.msg // .error_description // "unknown error"')
    die "Token request failed (code=$ret_code): $ret_msg"
  fi

  ACCESS_TOKEN=$(echo "$response" | jq -r '.access_token // empty')
  [[ -n "$ACCESS_TOKEN" ]] || die "Could not parse access_token from response:\n$response"

  TOKEN_EXPIRES_IN=$(echo "$response" | jq -r '.expires_in // "unknown"')
  success "Token obtained (expires in ${TOKEN_EXPIRES_IN}s)."
}

# ---------------------------------------------------------------------------
# Step 2 – Query app info (validate App ID & get current version)
# ---------------------------------------------------------------------------
query_app_info() {
  local step_label="${1:-Step 2}"
  info "${step_label} – Querying app info for App ID: $APP_ID …"

  local response
  response=$(curl -sf -X GET \
    "${BASE_URL}/app-info?appId=${APP_ID}&lang=${LANG}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID") \
    || die "Failed to query app info."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo "$response"  | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "App info query failed (code=$ret_code): $ret_msg"

  APP_NAME=$(echo "$response" | jq -r '.appInfo.appName // "N/A"')
  success "App found: ${BOLD}$APP_NAME${RESET}"
}

# ---------------------------------------------------------------------------
# Step 3 – Get upload URL  (also used standalone by the pre-flight probe)
# ---------------------------------------------------------------------------
get_upload_url() {
  local step_label="${1:-Step 3}"
  info "${step_label} – Requesting upload URL …"

  local file_name file_size sha256
  file_name=$(basename "$FILE_PATH")

  local response
  response=$(curl -sf -X GET \
    "${BASE_URL}/upload-url?appId=${APP_ID}&suffix=${FILE_PATH##*.}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID") \
    || die "Failed to request upload URL."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo "$response"  | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "Upload URL request failed (code=$ret_code): $ret_msg"

  UPLOAD_URL=$(echo       "$response" | jq -r '.uploadUrl       // empty')
  CHUNK_UPLOAD_URL=$(echo "$response" | jq -r '.chunkUploadUrl  // empty')
  UPLOAD_AUTH=$(echo      "$response" | jq -r '.authCode        // empty')
  OBJECT_ID=$(echo        "$response" | jq -r '.objectId        // empty')

  [[ -n "$UPLOAD_URL"  ]] || die "No uploadUrl in response:\n$response"
  [[ -n "$UPLOAD_AUTH" ]] || die "No authCode in response:\n$response"

  # ── Parse CDN hostname(s) from the returned URLs ──────────────────────────
  CDN_HOST=$(echo "$UPLOAD_URL" | awk -F'/' '{print $3}')
  CDN_CHUNK_HOST=""
  if [[ -n "$CHUNK_UPLOAD_URL" ]]; then
    CDN_CHUNK_HOST=$(echo "$CHUNK_UPLOAD_URL" | awk -F'/' '{print $3}')
  fi

  if [[ "$PREFLIGHT_ONLY" == false ]]; then
    file_size=$(wc -c < "$FILE_PATH" | tr -d ' ')
    sha256=$(file_sha256 "$FILE_PATH")
    info  "  File   : $file_name"
    info  "  Size   : $(human_size "$file_size")"
    info  "  SHA256 : $sha256"
  fi

  success "Upload URL received (CDN host: ${BOLD}${CDN_HOST}${RESET})."
}

# ---------------------------------------------------------------------------
# Step 4 – Upload the file
# ---------------------------------------------------------------------------
upload_file() {
  local step_label="${1:-Step 4}"
  info "${step_label} – Uploading ${FILE_PATH##*/} …"

  local file_name
  file_name=$(basename "$FILE_PATH")

  local response
  response=$(curl -sf -X POST "$UPLOAD_URL" \
    -H "accept: application/json" \
    -F "authCode=$UPLOAD_AUTH" \
    -F "fileCount=1" \
    -F "parseType=1" \
    -F "file=@${FILE_PATH};filename=${file_name}" \
    --progress-bar) \
    || die "File upload request failed."

  # Huawei upload service returns {"result":{"UploadFileRsp":{...}}}
  local result_code
  result_code=$(echo "$response" | jq -r '.result.UploadFileRsp.fileInfoList[0].fileDestUlr // empty')

  if [[ -z "$result_code" ]]; then
    # Try alternate shape
    local api_ret
    api_ret=$(echo "$response" | jq -r '.ret.code // empty')
    [[ "$api_ret" == "0" ]] \
      || die "Upload failed. Response:\n$response"
  fi

  FILE_DEST_URL=$(echo "$response" | \
    jq -r '.result.UploadFileRsp.fileInfoList[0].fileDestUlr // empty')

  # Some API versions return the URL inside a different field
  if [[ -z "$FILE_DEST_URL" ]]; then
    FILE_DEST_URL=$(echo "$response" | jq -r '.result.UploadFileRsp.pkgVersion[0].fileDestUlr // empty')
  fi

  [[ -n "$FILE_DEST_URL" ]] || \
    warn "Could not parse fileDestUlr – the file may still have uploaded. Raw response:\n$response"

  success "File uploaded successfully."
}

# ---------------------------------------------------------------------------
# Pre-flight probe – resolve CDN hostname without uploading anything
# ---------------------------------------------------------------------------
preflight_probe() {
  echo -e "\n${BOLD}┌─────────────────────────────────────────────────┐${RESET}"
  echo -e "${BOLD}│        PRE-FLIGHT: CDN Hostname Resolution       │${RESET}"
  echo -e "${BOLD}└─────────────────────────────────────────────────┘${RESET}\n"

  get_access_token  "Pre-flight 1/2"
  get_upload_url    "Pre-flight 2/2"

  echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
  echo -e "${BOLD}  Firewall Whitelist Report${RESET}"
  echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
  echo -e ""
  echo -e "  ${CYAN}Always required (API gateway) [${REGION_LABEL}]:${RESET}"
  echo -e "    $(echo "$AUTH_URL" | awk -F'/' '{print $3}')  :443  HTTPS"
  echo -e ""
  echo -e "  ${CYAN}Dynamically assigned CDN host(s) for THIS session:${RESET}"
  echo -e "    ${GREEN}${BOLD}${CDN_HOST}${RESET}  :443  HTTPS  ← uploadUrl host"
  if [[ -n "$CDN_CHUNK_HOST" && "$CDN_CHUNK_HOST" != "$CDN_HOST" ]]; then
    echo -e "    ${GREEN}${BOLD}${CDN_CHUNK_HOST}${RESET}  :443  HTTPS  ← chunkUploadUrl host"
  fi
  echo -e ""
  echo -e "  ${YELLOW}Note: CDN hosts rotate per session. For a stable${RESET}"
  echo -e "  ${YELLOW}allowlist, whitelist the wildcard: *.hicloud.com${RESET}"
  echo -e ""
  echo -e "  ${CYAN}Full upload URL :${RESET} $UPLOAD_URL"
  if [[ -n "$CHUNK_UPLOAD_URL" ]]; then
    echo -e "  ${CYAN}Chunk upload URL:${RESET} $CHUNK_UPLOAD_URL"
  fi
  echo -e ""
  echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"
}

# ---------------------------------------------------------------------------
# CDN reachability gate – TCP-connect probe; halts with exit 1 if blocked
# ---------------------------------------------------------------------------
check_cdn_reachable() {
  info "Step 3/4 – Probing CDN host reachability …"

  # Display the resolved host before probing so the user always sees it
  echo -e ""
  echo -e "  ${CYAN}CDN upload host  :${RESET} ${BOLD}${CDN_HOST}${RESET}  (port 443, HTTPS)"
  if [[ -n "$CDN_CHUNK_HOST" && "$CDN_CHUNK_HOST" != "$CDN_HOST" ]]; then
    echo -e "  ${CYAN}CDN chunk host   :${RESET} ${BOLD}${CDN_CHUNK_HOST}${RESET}  (port 443, HTTPS)"
  fi
  echo -e "  ${CYAN}Full upload URL  :${RESET} $UPLOAD_URL"
  echo -e ""

  # Use curl's --connect-timeout to do a pure TCP handshake (no data sent)
  # --head on a non-HTTP upload endpoint will get a rejection but that still
  # proves the host is routable and port 443 is open.
  local probe_result=0
  curl -sf --connect-timeout 10 --max-time 10 --head \
    "https://${CDN_HOST}/" -o /dev/null 2>/dev/null \
    || probe_result=$?

  # Exit codes 0 (success) or 22 (HTTP error reply) both mean the host is
  # reachable at the TCP/TLS layer.  Codes 6/7/28/35 mean DNS/routing/timeout.
  case "$probe_result" in
    0|22|35)
      success "CDN host ${BOLD}${CDN_HOST}${RESET} is reachable – proceeding with upload."
      ;;
    6)
      echo -e ""
      error  "CDN host unreachable – DNS resolution failed for: ${BOLD}${CDN_HOST}${RESET}"
      echo -e "  The host could not be resolved. Your firewall or DNS may be blocking it." >&2
      echo -e "  Ask your network team to whitelist: ${BOLD}${CDN_HOST}${RESET}  (port 443, HTTPS)" >&2
      echo -e "  Or use pre-flight mode (-n) to get the full whitelist report first.\n" >&2
      exit 1
      ;;
    7)
      echo -e ""
      error  "CDN host unreachable – TCP connection refused or blocked: ${BOLD}${CDN_HOST}${RESET}"
      echo -e "  Port 443 is blocked. Ask your network team to allow: ${BOLD}${CDN_HOST}:443${RESET}" >&2
      echo -e "  Or use pre-flight mode (-n) to get the full whitelist report first.\n" >&2
      exit 1
      ;;
    28)
      echo -e ""
      error  "CDN host unreachable – connection timed out: ${BOLD}${CDN_HOST}${RESET}"
      echo -e "  The connection attempt to port 443 timed out (10 s)." >&2
      echo -e "  Ask your network team to whitelist: ${BOLD}${CDN_HOST}:443${RESET}  HTTPS" >&2
      echo -e "  Or use pre-flight mode (-n) to get the full whitelist report first.\n" >&2
      exit 1
      ;;
    *)
      echo -e ""
      error  "CDN host probe returned an unexpected error (curl exit code: ${probe_result})"
      echo -e "  Host: ${BOLD}${CDN_HOST}${RESET}  — verify it is reachable on port 443." >&2
      echo -e "  Or use pre-flight mode (-n) to get the full whitelist report first.\n" >&2
      exit 1
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  echo -e "\n${BOLD}═══ Huawei AppGallery Connect – Upload Script ═══${RESET}\n"

  check_deps
  resolve_region
  validate_inputs

  # ── Pre-flight only mode (-n): resolve CDN host, print report, exit ───────
  if [[ "$PREFLIGHT_ONLY" == true ]]; then
    preflight_probe
    echo -e "${GREEN}${BOLD}Pre-flight complete. No file was uploaded.${RESET}"
    echo -e "Share the report above with your network/security team,"
    echo -e "then re-run without -n to perform the actual upload.\n"
    exit 0
  fi

  # ── Normal upload flow (no review submission) ─────────────────────────────
  echo -e "  App ID  : ${BOLD}$APP_ID${RESET}"
  echo -e "  File    : ${BOLD}$(basename "$FILE_PATH")${RESET}"
  echo -e "  Region  : ${BOLD}${REGION_LABEL}${RESET}"
  echo -e "  Mode    : ${BOLD}Upload only (no review submission)${RESET}\n"

  get_access_token  "Step 1/4"
  query_app_info    "Step 2/4"
  get_upload_url    "Step 3/4"   # populates CDN_HOST / CDN_CHUNK_HOST / UPLOAD_URL
  check_cdn_reachable            # sub-step of 3: halts with exit 1 if CDN host is blocked
  upload_file       "Step 4/4"

  echo -e "\n${GREEN}${BOLD}Upload complete!${RESET}"
  echo -e "The file is staged in AppGallery Connect but has NOT been submitted for review."
  echo -e "Submit for review manually in the console when ready.\n"
}

main "$@"
