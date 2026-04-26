#!/usr/bin/env bash
# =============================================================================
# Huawei AppGallery Connect – Submission Status Script
# =============================================================================
# Prerequisites:
#   Linux : sudo apt install curl jq
#   macOS : brew install curl jq        (no extra tools needed beyond these)
#   - A Huawei AppGallery Connect API client (Client ID + Client Secret)
#     https://developer.huawei.com/consumer/en/doc/development/AppGallery-connect-Guides/agcapi-getstarted
#
# Usage:
#   chmod +x huawei_appgallery_status.sh
#   ./huawei_appgallery_status.sh [options]
#
# Options:
#   -c  <client_id>       AppGallery Connect API Client ID      (required)
#   -s  <client_secret>   AppGallery Connect API Client Secret  (required)
#   -a  <app_id>          App ID from AppGallery Connect        (required)
#   -g  <region>          Target region (default: global)
#                           global  → connect-api.cloud.huawei.com
#                           cn      → connect-api-drcn.dbankcloud.cn   (Mainland China)
#                           asia    → connect-api-dra.cloud.huawei.com (Asia Pacific)
#                           eu      → connect-api-dre.cloud.huawei.com (Europe)
#                           am      → connect-api-drru.cloud.huawei.com (Americas/Russia)
#   -l  <lang>            Language/locale (default: en-US; use zh-CN for China)
#   -w  <seconds>         Poll interval in seconds — keep checking until status
#                         changes from "Under review" (default: 0 = check once)
#   -t  <seconds>         Total timeout for polling mode in seconds (default: 3600)
#   -j                    Output raw JSON (machine-readable, suppresses colour)
#   -h                    Show this help message
#
# Behaviour:
#   Queries GET /app-info, displays a full status report of the current draft
#   including: app name, package, versionCode, versionName, file name, release
#   state, and audit reason (if rejected).
#
#   In polling mode (-w), re-queries on the given interval until the review
#   state leaves "Under review" (state 2), then exits with:
#     0  – review passed (Released / Scheduled)
#     1  – review rejected or recalled
#     2  – timed out while still under review
#
# Release state reference:
#   1  Draft                    5  Paused               9  Phased release paused
#   2  Under review             6  Recalled             10  Phased release complete
#   3  Rejected                 7  Scheduled            11  Being delisted
#   4  Released                 8  Under phased release 12  Delisted
#
# Example – check once:
#   ./huawei_appgallery_status.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id"
#
# Example – poll every 60 s, timeout after 2 h (CI/CD gate):
#   ./huawei_appgallery_status.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -w 60 -t 7200
#
# Example – Mainland China, JSON output:
#   ./huawei_appgallery_status.sh -g cn -l zh-CN -j \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id"
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
# Cross-platform helpers
# ---------------------------------------------------------------------------
OS_TYPE="linux"
[[ "$(uname -s)" == "Darwin" ]] && OS_TYPE="macos"

human_size() {
  local bytes=$1
  if command -v numfmt &>/dev/null; then
    numfmt --to=iec-i --suffix=B "$bytes" 2>/dev/null && return
  fi
  local units=("B" "KiB" "MiB" "GiB" "TiB")
  local idx=0 val=$bytes
  while (( val >= 1024 && idx < 4 )); do val=$(( val / 1024 )); (( idx++ )); done
  echo "${val} ${units[$idx]}"
}

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CLIENT_ID=""
CLIENT_SECRET=""
APP_ID=""
LANG="en-US"
REGION="global"
POLL_INTERVAL=0      # -w : 0 = single check; >0 = poll every N seconds
POLL_TIMEOUT=3600    # -t : max seconds to poll before giving up
JSON_OUTPUT=false    # -j : emit raw JSON instead of formatted report

AUTH_URL=""
BASE_URL=""
REGION_LABEL=""
ACCESS_TOKEN=""

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,1\}//'
  exit 0
}

while getopts ":c:s:a:g:l:w:t:jh" opt; do
  case $opt in
    c) CLIENT_ID="$OPTARG" ;;
    s) CLIENT_SECRET="$OPTARG" ;;
    a) APP_ID="$OPTARG" ;;
    g) REGION="$OPTARG" ;;
    l) LANG="$OPTARG" ;;
    w) POLL_INTERVAL="$OPTARG" ;;
    t) POLL_TIMEOUT="$OPTARG" ;;
    j) JSON_OUTPUT=true ;;
    h) usage ;;
    :) die "Option -$OPTARG requires an argument." ;;
    \?) die "Unknown option: -$OPTARG" ;;
  esac
done

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
check_deps() {
  local missing=()
  for cmd in curl jq; do
    command -v "$cmd" &>/dev/null || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    error "Missing required tools: ${missing[*]}"
    [[ "$OS_TYPE" == "macos" ]] \
      && echo -e "  Install with Homebrew:  brew install ${missing[*]}" >&2 \
      || echo -e "  Install with apt:       sudo apt install ${missing[*]}" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# Input validation
# ---------------------------------------------------------------------------
validate_inputs() {
  [[ -z "$CLIENT_ID"     ]] && die "Client ID (-c) is required."
  [[ -z "$CLIENT_SECRET" ]] && die "Client Secret (-s) is required."
  [[ -z "$APP_ID"        ]] && die "App ID (-a) is required."
  [[ "$POLL_INTERVAL" =~ ^[0-9]+$ ]] || die "Poll interval (-w) must be a non-negative integer."
  [[ "$POLL_TIMEOUT"  =~ ^[0-9]+$ ]] || die "Timeout (-t) must be a non-negative integer."
}

# ---------------------------------------------------------------------------
# Region resolution
# ---------------------------------------------------------------------------
resolve_region() {
  local base_domain
  case "${REGION,,}" in
    global|"")     base_domain="connect-api.cloud.huawei.com";       REGION_LABEL="Global" ;;
    cn|china)      base_domain="connect-api-drcn.dbankcloud.cn";     REGION_LABEL="Mainland China" ;;
    asia|apac)     base_domain="connect-api-dra.cloud.huawei.com";   REGION_LABEL="Asia Pacific" ;;
    eu|europe)     base_domain="connect-api-dre.cloud.huawei.com";   REGION_LABEL="Europe" ;;
    am|americas|ru|russia) base_domain="connect-api-drru.cloud.huawei.com"; REGION_LABEL="Americas / Russia" ;;
    *) die "Unknown region: '${REGION}'. Valid values: global, cn, asia, eu, am" ;;
  esac
  AUTH_URL="https://${base_domain}/api/oauth2/v1/token"
  BASE_URL="https://${base_domain}/api/publish/v2"
}

# ---------------------------------------------------------------------------
# Step 1 – Obtain access token
# ---------------------------------------------------------------------------
get_access_token() {
  [[ "$JSON_OUTPUT" == false ]] && info "Step 1/2 – Obtaining access token …"

  local body
  body=$(jq -n \
    --arg grantType    "client_credentials" \
    --arg clientId     "$CLIENT_ID" \
    --arg clientSecret "$CLIENT_SECRET" \
    '{grant_type: $grantType, client_id: $clientId, client_secret: $clientSecret}')

  local response
  response=$(curl -sf -X POST "$AUTH_URL" \
    -H "Content-Type: application/json" \
    -d "$body") \
    || die "Failed to reach the Huawei OAuth2 endpoint."

  local ret_code
  ret_code=$(echo "$response" | jq -r '.ret.code // .error // empty')
  if [[ -n "$ret_code" && "$ret_code" != "0" ]]; then
    local ret_msg
    ret_msg=$(echo "$response" | jq -r '.ret.msg // .error_description // "unknown error"')
    die "Token request failed (code=$ret_code): $ret_msg"
  fi

  ACCESS_TOKEN=$(echo "$response" | jq -r '.access_token // empty')
  [[ -n "$ACCESS_TOKEN" ]] || die "Could not parse access_token from response."

  [[ "$JSON_OUTPUT" == false ]] && success "Token obtained."
}

# ---------------------------------------------------------------------------
# releaseState → human label + colour escape
# ---------------------------------------------------------------------------
state_label() {
  local state=$1
  case "$state" in
    1)  echo "Draft" ;;
    2)  echo "Under review" ;;
    3)  echo "Rejected" ;;
    4)  echo "Released" ;;
    5)  echo "Paused" ;;
    6)  echo "Recalled" ;;
    7)  echo "Scheduled for release" ;;
    8)  echo "Under phased release" ;;
    9)  echo "Phased release paused" ;;
    10) echo "Phased release complete" ;;
    11) echo "Being delisted" ;;
    12) echo "Delisted" ;;
    *)  echo "Unknown (state=${state})" ;;
  esac
}

state_colour() {
  local state=$1
  case "$state" in
    2|7|8)  echo "$CYAN" ;;       # in-progress states
    4|10)   echo "$GREEN" ;;      # success states
    3|6|11|12) echo "$RED" ;;     # failure / removal states
    *)      echo "$YELLOW" ;;     # everything else
  esac
}

# ---------------------------------------------------------------------------
# Step 2 – Query and display status
# Returns the raw releaseState integer via global CURRENT_STATE
# ---------------------------------------------------------------------------
CURRENT_STATE=""

query_status() {
  local silent="${1:-false}"   # true = suppress step label (used in poll loop)

  [[ "$JSON_OUTPUT" == false && "$silent" == false ]] && \
    info "Step 2/2 – Querying submission status …"

  local response
  response=$(curl -sf -X GET \
    "${BASE_URL}/app-info?appId=${APP_ID}&lang=${LANG}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID") \
    || die "Failed to query app info."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo  "$response" | jq -r '.ret.msg')
  [[ "$ret_code" == "0" ]] \
    || die "App info query failed (code=$ret_code): $ret_msg"

  # ── JSON mode: emit raw appInfo and exit ─────────────────────────────────
  if [[ "$JSON_OUTPUT" == true ]]; then
    echo "$response" | jq '.appInfo'
    CURRENT_STATE=$(echo "$response" | jq -r '.appInfo.releaseState // "0"')
    return
  fi

  # ── Parse fields ──────────────────────────────────────────────────────────
  local app_name pkg_name version_code version_name file_name file_size \
        file_type_raw release_state audit_desc update_time split_count

  app_name=$(echo     "$response" | jq -r '.appInfo.appName                       // "N/A"')
  pkg_name=$(echo     "$response" | jq -r '.appInfo.bundleId                      // "N/A"')
  version_code=$(echo "$response" | jq -r '.appInfo.pkgVersion[0].versionCode     // "N/A"')
  version_name=$(echo "$response" | jq -r '.appInfo.pkgVersion[0].versionName     // "N/A"')
  file_name=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].fileName        // "N/A"')
  file_size=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].fileSize        // ""')
  file_type_raw=$(echo "$response"| jq -r '.appInfo.pkgVersion[0].fileType        // ""')
  release_state=$(echo "$response"| jq -r '.appInfo.releaseState                  // "0"')
  audit_desc=$(echo   "$response" | jq -r '.appInfo.auditDesc                     // ""')
  update_time=$(echo  "$response" | jq -r '.appInfo.updateTime                    // ""')
  split_count=$(echo  "$response" | jq -r '.appInfo.pkgVersion | length           // 0')

  CURRENT_STATE="$release_state"

  local file_type_label
  case "$file_type_raw" in
    1) file_type_label="APK" ;;
    2) file_type_label="AAB" ;;
    5) file_type_label="RPK" ;;
    *) file_type_label="Unknown" ;;
  esac

  local label colour
  label=$(state_label  "$release_state")
  colour=$(state_colour "$release_state")

  local now_ts
  if [[ "$OS_TYPE" == "macos" ]]; then
    now_ts=$(date "+%Y-%m-%d %H:%M:%S %Z")
  else
    now_ts=$(date "+%Y-%m-%d %H:%M:%S %Z")
  fi

  # ── Formatted report ──────────────────────────────────────────────────────
  echo -e ""
  echo -e "${BOLD}  ╔══════════════════════════════════════════════════╗${RESET}"
  echo -e "${BOLD}  ║          AppGallery Submission Status            ║${RESET}"
  echo -e "${BOLD}  ╚══════════════════════════════════════════════════╝${RESET}"
  echo -e ""
  echo -e "  App name     : ${BOLD}${app_name}${RESET}"
  echo -e "  Package      : ${pkg_name}"
  echo -e "  Region       : ${REGION_LABEL}"
  echo -e "  Checked at   : ${now_ts}"
  echo -e ""
  echo -e "  ── Package ────────────────────────────────────────"
  echo -e "  versionCode  : ${BOLD}${version_code}${RESET}"
  echo -e "  versionName  : ${version_name}"
  echo -e "  File         : ${file_name}"
  echo -e "  Format       : ${file_type_label}"
  if [[ -n "$file_size" && "$file_size" != "null" ]]; then
    echo -e "  Size         : $(human_size "$file_size")"
  fi
  if (( split_count > 1 )); then
    echo -e "  ABI splits   : ${split_count}"
  fi
  if [[ -n "$update_time" && "$update_time" != "null" ]]; then
    echo -e "  Last updated : ${update_time}"
  fi
  echo -e ""
  echo -e "  ── Review Status ──────────────────────────────────"
  echo -e "  State        : ${colour}${BOLD}${label}${RESET}  (code=${release_state})"

  # Show audit rejection reason if present
  if [[ -n "$audit_desc" && "$audit_desc" != "null" && "$audit_desc" != "" ]]; then
    echo -e ""
    echo -e "  ${RED}${BOLD}Rejection reason:${RESET}"
    # Wrap long audit descriptions at 60 chars for readability
    echo "$audit_desc" | fold -s -w 60 | while IFS= read -r line; do
      echo -e "    ${line}"
    done
  fi
  echo -e ""

  # ── Guidance based on state ───────────────────────────────────────────────
  case "$release_state" in
    1) echo -e "  ${YELLOW}ℹ App is in Draft. Run the submit script to submit for review.${RESET}" ;;
    2) echo -e "  ${CYAN}⏳ Review is in progress. Use -w to poll until complete.${RESET}" ;;
    3) echo -e "  ${RED}✗ Review rejected. Check the rejection reason above, fix and resubmit.${RESET}" ;;
    4) echo -e "  ${GREEN}✓ App is live on AppGallery.${RESET}" ;;
    6) echo -e "  ${RED}✗ App has been recalled from the store.${RESET}" ;;
    7) echo -e "  ${CYAN}⏳ App is approved and scheduled for release.${RESET}" ;;
    8) echo -e "  ${GREEN}✓ Phased release is in progress.${RESET}" ;;
  esac
  echo -e ""
}

# ---------------------------------------------------------------------------
# Polling loop
# ---------------------------------------------------------------------------
poll_status() {
  local elapsed=0
  local attempt=0

  info "Polling every ${POLL_INTERVAL}s (timeout: ${POLL_TIMEOUT}s) …"
  echo -e ""

  while true; do
    (( attempt++ ))
    echo -e "${CYAN}[Poll #${attempt} — elapsed ${elapsed}s]${RESET}"

    query_status "silent"

    # Stop polling once review is no longer "Under review" (state 2)
    case "$CURRENT_STATE" in
      2)
        # Still under review — keep waiting
        if (( elapsed + POLL_INTERVAL > POLL_TIMEOUT )); then
          echo -e ""
          warn "Timeout reached (${POLL_TIMEOUT}s) — still under review."
          exit 2
        fi
        echo -e "  Still under review. Next check in ${POLL_INTERVAL}s …"
        echo -e ""
        sleep "$POLL_INTERVAL"
        (( elapsed += POLL_INTERVAL ))
        ;;
      4|7|8|10)
        # Passed / released / scheduled / phased complete
        echo -e ""
        success "Review passed! Final state: $(state_label "$CURRENT_STATE") (code=${CURRENT_STATE})"
        exit 0
        ;;
      3|6)
        # Rejected or recalled
        echo -e ""
        error "Review ended negatively. Final state: $(state_label "$CURRENT_STATE") (code=${CURRENT_STATE})"
        exit 1
        ;;
      *)
        # Any other state — stop polling, report it
        echo -e ""
        warn "Status changed to: $(state_label "$CURRENT_STATE") (code=${CURRENT_STATE}) — stopping poll."
        exit 0
        ;;
    esac
  done
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  if [[ "$JSON_OUTPUT" == false ]]; then
    echo -e "\n${BOLD}═══ Huawei AppGallery Connect – Status Check ═══${RESET}\n"
  fi

  check_deps
  resolve_region
  validate_inputs

  if [[ "$JSON_OUTPUT" == false ]]; then
    echo -e "  App ID  : ${BOLD}${APP_ID}${RESET}"
    echo -e "  Region  : ${BOLD}${REGION_LABEL}${RESET}"
    if (( POLL_INTERVAL > 0 )); then
      echo -e "  Mode    : ${BOLD}Polling every ${POLL_INTERVAL}s, timeout ${POLL_TIMEOUT}s${RESET}"
    else
      echo -e "  Mode    : ${BOLD}Single check${RESET}"
    fi
    echo -e ""
  fi

  get_access_token

  if (( POLL_INTERVAL > 0 )); then
    poll_status
  else
    query_status "false"
  fi
}

main "$@"
