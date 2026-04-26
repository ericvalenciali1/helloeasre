#!/usr/bin/env bash
# =============================================================================
# Huawei AppGallery Connect – Cancel Review Script
# =============================================================================
# Prerequisites:
#   Linux : sudo apt install curl jq
#   macOS : brew install curl jq        (no extra tools needed beyond these)
#   - A Huawei AppGallery Connect API client (Client ID + Client Secret)
#     https://developer.huawei.com/consumer/en/doc/development/AppGallery-connect-Guides/agcapi-getstarted
#
# Usage:
#   chmod +x huawei_appgallery_cancel.sh
#   ./huawei_appgallery_cancel.sh [options]
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
#   -y                    Skip the confirmation prompt (non-interactive / CI mode)
#   -h                    Show this help message
#
# Behaviour:
#   1. Obtains an OAuth2 access token.
#   2. Queries the current app status to confirm the app is actually "Under review"
#      (releaseState=2). If not, the script refuses to proceed — cancellation is
#      only valid for apps currently being reviewed by Huawei.
#   3. Displays a summary of what will be cancelled and asks for confirmation
#      (unless -y is supplied).
#   4. Calls DELETE /app-submit to cancel the review submission. The app draft
#      is returned to "Draft" state (releaseState=1) and can be edited again.
#
# Exit codes:
#   0  – review successfully cancelled; app returned to Draft
#   1  – error (API failure, wrong state, network issue)
#   2  – cancelled by user at confirmation prompt
#
# WARNING:
#   Cancelling a review resets the app to Draft state. You will need to
#   re-submit (via huawei_appgallery_submit.sh) when you are ready to
#   try the review again. Any review progress made by Huawei is discarded.
#
# Example – interactive (asks for confirmation):
#   ./huawei_appgallery_cancel.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id"
#
# Example – non-interactive / CI pipeline:
#   ./huawei_appgallery_cancel.sh -y \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id"
#
# Example – Mainland China:
#   ./huawei_appgallery_cancel.sh -g cn -l zh-CN \
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
AUTO_CONFIRM=false   # -y : skip interactive confirmation prompt

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

while getopts ":c:s:a:g:l:yh" opt; do
  case $opt in
    c) CLIENT_ID="$OPTARG" ;;
    s) CLIENT_SECRET="$OPTARG" ;;
    a) APP_ID="$OPTARG" ;;
    g) REGION="$OPTARG" ;;
    l) LANG="$OPTARG" ;;
    y) AUTO_CONFIRM=true ;;
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
  info "Step 1/3 – Obtaining access token …"

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
  success "Token obtained."
}

# ---------------------------------------------------------------------------
# Step 2 – Verify the app is under review; display a cancel summary
# ---------------------------------------------------------------------------
APP_NAME=""
VERSION_CODE=""
VERSION_NAME=""
FILE_NAME=""
FILE_SIZE=""

verify_under_review() {
  info "Step 2/3 – Verifying app is currently under review …"

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

  local release_state
  release_state=$(echo "$response" | jq -r '.appInfo.releaseState // "0"')

  APP_NAME=$(echo     "$response" | jq -r '.appInfo.appName                    // "N/A"')
  VERSION_CODE=$(echo "$response" | jq -r '.appInfo.pkgVersion[0].versionCode  // "N/A"')
  VERSION_NAME=$(echo "$response" | jq -r '.appInfo.pkgVersion[0].versionName  // "N/A"')
  FILE_NAME=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].fileName     // "N/A"')
  FILE_SIZE=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].fileSize     // ""')

  # ── State guard ───────────────────────────────────────────────────────────
  case "$release_state" in
    2)
      success "App is currently under review — cancellation is valid."
      ;;
    1)
      die "App is in Draft state (not submitted). Nothing to cancel."
      ;;
    3)
      die "Review was already rejected (state=3). Nothing to cancel. Fix issues and resubmit."
      ;;
    4)
      die "App is already Released (state=4). Cannot cancel a live release via this script."
      ;;
    7)
      die "App is approved and Scheduled for release (state=7). Cancel from the console if needed."
      ;;
    8)
      die "App is under Phased release (state=8). Use the console to manage phased rollout."
      ;;
    *)
      die "App is in state ${release_state} — cancellation is only valid for state 2 (Under review)."
      ;;
  esac

  # ── Cancel summary ────────────────────────────────────────────────────────
  echo -e ""
  echo -e "${BOLD}  ╔══════════════════════════════════════════════════╗${RESET}"
  echo -e "${BOLD}  ║              Cancel Review Summary               ║${RESET}"
  echo -e "${BOLD}  ╚══════════════════════════════════════════════════╝${RESET}"
  echo -e ""
  echo -e "  App name     : ${BOLD}${APP_NAME}${RESET}"
  echo -e "  App ID       : ${APP_ID}"
  echo -e "  Region       : ${REGION_LABEL}"
  echo -e "  versionCode  : ${BOLD}${VERSION_CODE}${RESET}"
  echo -e "  versionName  : ${VERSION_NAME}"
  echo -e "  File         : ${FILE_NAME}"
  if [[ -n "$FILE_SIZE" && "$FILE_SIZE" != "null" ]]; then
    echo -e "  Size         : $(human_size "$FILE_SIZE")"
  fi
  echo -e ""
  echo -e "  ${YELLOW}${BOLD}⚠  Cancelling this review will:${RESET}"
  echo -e "  ${YELLOW}   • Immediately halt the Huawei review process${RESET}"
  echo -e "  ${YELLOW}   • Return the app to Draft state (releaseState=1)${RESET}"
  echo -e "  ${YELLOW}   • Require you to re-submit when ready${RESET}"
  echo -e ""
}

# ---------------------------------------------------------------------------
# Confirmation prompt
# ---------------------------------------------------------------------------
confirm_cancel() {
  if [[ "$AUTO_CONFIRM" == true ]]; then
    warn "Auto-confirm enabled (-y) — proceeding without prompt."
    return 0
  fi

  echo -en "${RED}  → Are you sure you want to cancel the review? [y/N]: ${RESET}"
  local answer
  read -r answer
  case "$answer" in
    [yY]|[yY][eE][sS])
      echo ""
      ;;
    *)
      echo -e "\n${YELLOW}Review cancellation aborted by user. No changes made.${RESET}\n"
      exit 2
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Step 3 – Cancel the review (DELETE /app-submit)
# ---------------------------------------------------------------------------
cancel_review() {
  info "Step 3/3 – Cancelling review submission …"

  local response
  response=$(curl -sf -X DELETE \
    "${BASE_URL}/app-submit?appId=${APP_ID}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID") \
    || die "Failed to call the cancel-review endpoint."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo  "$response" | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "Review cancellation failed (code=$ret_code): $ret_msg"

  success "Review successfully cancelled."
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  echo -e "\n${BOLD}═══ Huawei AppGallery Connect – Cancel Review ═══${RESET}\n"

  check_deps
  resolve_region
  validate_inputs

  echo -e "  App ID  : ${BOLD}${APP_ID}${RESET}"
  echo -e "  Region  : ${BOLD}${REGION_LABEL}${RESET}"
  echo -e "  Confirm : ${BOLD}$( [[ "$AUTO_CONFIRM" == true ]] && echo "Auto (-y)" || echo "Interactive" )${RESET}\n"

  get_access_token
  verify_under_review
  confirm_cancel
  cancel_review

  echo -e ""
  echo -e "${GREEN}${BOLD}Done!${RESET} ${BOLD}${APP_NAME}${RESET} (versionCode=${VERSION_CODE}) has been returned to Draft."
  echo -e "Make any necessary changes and re-run the submit script when ready.\n"
}

main "$@"
