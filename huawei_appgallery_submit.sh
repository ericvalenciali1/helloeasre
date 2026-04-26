#!/usr/bin/env bash
# =============================================================================
# Huawei AppGallery Connect – App Submission Script
# =============================================================================
# Prerequisites:
#   Linux : sudo apt install curl jq
#   macOS : brew install curl jq        (no extra tools needed beyond these)
#   - A Huawei AppGallery Connect API client (Client ID + Client Secret)
#     https://developer.huawei.com/consumer/en/doc/development/AppGallery-connect-Guides/agcapi-getstarted
#
# Usage:
#   chmod +x huawei_appgallery_submit.sh
#   ./huawei_appgallery_submit.sh [options]
#
# Options:
#   -c  <client_id>       AppGallery Connect API Client ID           (required)
#   -s  <client_secret>   AppGallery Connect API Client Secret       (required)
#   -a  <app_id>          App ID from AppGallery Connect             (required)
#   -g  <region>          Target region (default: global)
#                           global  → connect-api.cloud.huawei.com
#                           cn      → connect-api-drcn.dbankcloud.cn   (Mainland China)
#                           asia    → connect-api-dra.cloud.huawei.com (Asia Pacific)
#                           eu      → connect-api-dre.cloud.huawei.com (Europe)
#                           am      → connect-api-drru.cloud.huawei.com (Americas/Russia)
#   -l  <lang>            Language/locale for app info (default: en-US; use zh-CN for China)
#   -r  <release_type>    Release type: 1=whole network, 3=by phase  (default: 1)
#   -p  <phase_percent>   Phase rollout percentage 1-100             (required if -r 3)
#   -d  <start_time>      Phased release start time, UTC             (optional, -r 3 only)
#                         Format: "YYYY-MM-DD HH:MM:SS"
#   -e  <end_time>        Phased release end time, UTC               (optional, -r 3 only)
#                         Format: "YYYY-MM-DD HH:MM:SS"
#   -q                    Query current app version/status only, do not submit
#   -h                    Show this help message
#
# Behaviour:
#   1. Obtains an OAuth2 access token using client credentials.
#   2. Queries the current app version info and displays a summary so you
#      can confirm you are submitting the right build.
#   3. Calls the app-file-info PUT endpoint to attach the uploaded package
#      to the draft version (idempotent if already attached).
#   4. Calls the app-submit POST endpoint to submit for review.
#
# This script is the second half of a two-step pipeline:
#   Step A: huawei_appgallery_upload.sh   → uploads APK/AAB, stops before review
#   Step B: huawei_appgallery_submit.sh   → submits the staged build for review
#   Use the same -g region flag in both scripts.
#
# Release types:
#   1 = Whole-network release (all users simultaneously)
#   3 = Phased release       (gradual rollout to a percentage of users)
#       Requires -p <percent>.  Optionally specify -d and -e for the rollout window.
#
# Example – Mainland China, submit to all users:
#   ./huawei_appgallery_submit.sh -g cn -l zh-CN \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id"
#
# Example – global, phased rollout to 10% of users:
#   ./huawei_appgallery_submit.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -r 3 -p 10
#
# Example – Europe, phased rollout with explicit window:
#   ./huawei_appgallery_submit.sh -g eu \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -r 3 -p 20 -d "2025-06-01 08:00:00" -e "2025-06-07 08:00:00"
#
# Example – query current status without submitting:
#   ./huawei_appgallery_submit.sh -q \
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
# Cross-platform helpers  (macOS vs Linux)
# ---------------------------------------------------------------------------

# Detect OS
OS_TYPE="linux"
[[ "$(uname -s)" == "Darwin" ]] && OS_TYPE="macos"

# Portable date-to-epoch conversion (used to validate -d / -e timestamps)
date_to_epoch() {
  local ts="$1"
  if [[ "$OS_TYPE" == "macos" ]]; then
    date -j -f "%Y-%m-%d %H:%M:%S" "$ts" "+%s" 2>/dev/null \
      || die "Invalid date format: '$ts'  Expected: YYYY-MM-DD HH:MM:SS"
  else
    date -d "$ts" "+%s" 2>/dev/null \
      || die "Invalid date format: '$ts'  Expected: YYYY-MM-DD HH:MM:SS"
  fi
}

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CLIENT_ID=""
CLIENT_SECRET=""
APP_ID=""
LANG="en-US"
REGION="global"         # -g : global | cn | asia | eu | am
RELEASE_TYPE=1          # 1=whole network, 3=phased
PHASE_PERCENT=""        # required when RELEASE_TYPE=3
PHASE_START_TIME=""     # optional ISO datetime for phased start  (UTC)
PHASE_END_TIME=""       # optional ISO datetime for phased end    (UTC)
QUERY_ONLY=false        # -q : show status then exit, no submission

# Set after resolve_region() is called
AUTH_URL=""
BASE_URL=""
REGION_LABEL=""

# Populated after authentication
ACCESS_TOKEN=""

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,1\}//'
  exit 0
}

while getopts ":c:s:a:l:g:r:p:d:e:qh" opt; do
  case $opt in
    c) CLIENT_ID="$OPTARG" ;;
    s) CLIENT_SECRET="$OPTARG" ;;
    a) APP_ID="$OPTARG" ;;
    l) LANG="$OPTARG" ;;
    g) REGION="$OPTARG" ;;
    r) RELEASE_TYPE="$OPTARG" ;;
    p) PHASE_PERCENT="$OPTARG" ;;
    d) PHASE_START_TIME="$OPTARG" ;;
    e) PHASE_END_TIME="$OPTARG" ;;
    q) QUERY_ONLY=true ;;
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
    if [[ "$OS_TYPE" == "macos" ]]; then
      echo -e "  Install with Homebrew:  brew install ${missing[*]}" >&2
    else
      echo -e "  Install with apt:       sudo apt install ${missing[*]}" >&2
    fi
    exit 1
  fi
}

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
# Input validation
# ---------------------------------------------------------------------------
validate_inputs() {
  [[ -z "$CLIENT_ID"     ]] && die "Client ID (-c) is required."
  [[ -z "$CLIENT_SECRET" ]] && die "Client Secret (-s) is required."
  [[ -z "$APP_ID"        ]] && die "App ID (-a) is required."

  # Release-type validation (skip for query-only mode)
  if [[ "$QUERY_ONLY" == false ]]; then
    [[ "$RELEASE_TYPE" == "1" || "$RELEASE_TYPE" == "3" ]] \
      || die "Release type (-r) must be 1 (whole network) or 3 (phased)."

    if [[ "$RELEASE_TYPE" == "3" ]]; then
      [[ -z "$PHASE_PERCENT" ]] \
        && die "Phase percent (-p) is required when release type is 3 (phased)."
      [[ "$PHASE_PERCENT" -ge 1 && "$PHASE_PERCENT" -le 100 ]] 2>/dev/null \
        || die "Phase percent (-p) must be an integer between 1 and 100."

      # Validate optional phase window timestamps
      if [[ -n "$PHASE_START_TIME" ]]; then
        date_to_epoch "$PHASE_START_TIME" > /dev/null
      fi
      if [[ -n "$PHASE_END_TIME" ]]; then
        date_to_epoch "$PHASE_END_TIME" > /dev/null
        # Ensure end is after start when both are provided
        if [[ -n "$PHASE_START_TIME" ]]; then
          local start_epoch end_epoch
          start_epoch=$(date_to_epoch "$PHASE_START_TIME")
          end_epoch=$(date_to_epoch "$PHASE_END_TIME")
          (( end_epoch > start_epoch )) \
            || die "End time (-e) must be after start time (-d)."
        fi
      fi
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
    --arg grantType    "client_credentials" \
    --arg clientId     "$CLIENT_ID" \
    --arg clientSecret "$CLIENT_SECRET" \
    '{grant_type: $grantType, client_id: $clientId, client_secret: $clientSecret}')

  local response
  response=$(curl -sf -X POST "$AUTH_URL" \
    -H "Content-Type: application/json" \
    -d "$body") \
    || die "Failed to reach the Huawei OAuth2 endpoint. Check network connectivity."

  local ret_code
  ret_code=$(echo "$response" | jq -r '.ret.code // .error // empty')
  if [[ -n "$ret_code" && "$ret_code" != "0" ]]; then
    local ret_msg
    ret_msg=$(echo "$response" | jq -r '.ret.msg // .error_description // "unknown error"')
    die "Token request failed (code=$ret_code): $ret_msg"
  fi

  ACCESS_TOKEN=$(echo "$response" | jq -r '.access_token // empty')
  [[ -n "$ACCESS_TOKEN" ]] || die "Could not parse access_token from response:\n$response"

  local expires_in
  expires_in=$(echo "$response" | jq -r '.expires_in // "unknown"')
  success "Token obtained (expires in ${expires_in}s)."
}

# ---------------------------------------------------------------------------
# Step 2 – Query app version info and display a status summary
# ---------------------------------------------------------------------------
query_app_info() {
  local step_label="${1:-Step 2}"
  info "${step_label} – Querying app info …"

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

  # ── Parse key fields ──────────────────────────────────────────────────────
  APP_NAME=$(echo    "$response" | jq -r '.appInfo.appName        // "N/A"')
  APP_PKG=$(echo     "$response" | jq -r '.appInfo.bundleId       // "N/A"')
  APP_VERSION=$(echo "$response" | jq -r '.appInfo.versionNumber  // "N/A"')
  APP_STATUS=$(echo  "$response" | jq -r '.appInfo.releaseState   // "N/A"')

  # releaseState numeric → human-readable label
  # https://developer.huawei.com/consumer/en/doc/AppGallery-connect-References/agcapi-app-info-query
  local status_label
  case "$APP_STATUS" in
    1)  status_label="Draft" ;;
    2)  status_label="Under review" ;;
    3)  status_label="Rejected" ;;
    4)  status_label="Released" ;;
    5)  status_label="Paused" ;;
    6)  status_label="Recalled" ;;
    7)  status_label="Scheduled for release" ;;
    8)  status_label="Under phased release" ;;
    9)  status_label="Phased release paused" ;;
    10) status_label="Phased release complete" ;;
    11) status_label="Being delisted" ;;
    12) status_label="Delisted" ;;
    *)  status_label="Unknown (state=$APP_STATUS)" ;;
  esac

  echo -e ""
  echo -e "${BOLD}  ── App Summary ─────────────────────────────────${RESET}"
  echo -e "  Name     : ${BOLD}${APP_NAME}${RESET}"
  echo -e "  Package  : ${APP_PKG}"
  echo -e "  Version  : ${APP_VERSION}"
  echo -e "  Status   : ${YELLOW}${status_label}${RESET}"
  echo -e "${BOLD}  ─────────────────────────────────────────────────${RESET}"
  echo -e ""

  # Guard: warn if current state is not a submittable draft state
  if [[ "$QUERY_ONLY" == false ]]; then
    case "$APP_STATUS" in
      2) warn "App is already under review. Submitting again may replace the current review." ;;
      4) warn "App is currently released. You are submitting an update." ;;
      5) warn "App release is paused." ;;
      6) warn "App has been recalled. Verify before submitting." ;;
    esac
  fi

  success "App info retrieved."
}

# ---------------------------------------------------------------------------
# Step 3 – Attach package to draft version (PUT app-file-info)
#          This is idempotent — safe to call even if already attached.
# ---------------------------------------------------------------------------
attach_package() {
  local step_label="${1:-Step 3}"
  info "${step_label} – Attaching uploaded package to draft version …"

  # Query the current draft to discover the file type (APK vs AAB)
  # so we send the correct fileType integer.
  local pkg_response
  pkg_response=$(curl -sf -X GET \
    "${BASE_URL}/app-info?appId=${APP_ID}&lang=${LANG}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID") \
    || die "Failed to fetch package info."

  # Try to detect file type from what is already staged. If not determinable
  # we default to APK (fileType=1) which the API will correct if wrong.
  local file_type
  local pkg_type
  pkg_type=$(echo "$pkg_response" | jq -r '.appInfo.pkgVersion[0].fileType // empty')
  case "$pkg_type" in
    2) file_type=2 ;; # AAB
    *) file_type=1 ;; # APK (default)
  esac

  # Retrieve the fileDestUlr of the most recently uploaded package
  local file_dest_url
  file_dest_url=$(echo "$pkg_response" \
    | jq -r '.appInfo.pkgVersion[0].fileDestUlr // empty')

  if [[ -z "$file_dest_url" ]]; then
    warn "No staged package found in the draft. Skipping file-info attachment."
    warn "Make sure you ran huawei_appgallery_upload.sh first."
    return 0
  fi

  local file_name
  file_name=$(echo "$file_dest_url" | awk -F'/' '{print $NF}')

  local payload
  payload=$(jq -n \
    --arg appId    "$APP_ID" \
    --arg lang     "$LANG" \
    --argjson fileType "$file_type" \
    --arg fileName "$file_name" \
    --arg fileUrl  "$file_dest_url" \
    '{
      appId:    $appId,
      lang:     $lang,
      fileType: $fileType,
      files: [{ fileName: $fileName, fileDestUlr: $fileUrl }]
    }')

  local response
  response=$(curl -sf -X PUT \
    "${BASE_URL}/app-file-info?appId=${APP_ID}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID" \
    -H "Content-Type: application/json" \
    -d "$payload") \
    || die "Failed to attach package to draft."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo  "$response" | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "File-info update failed (code=$ret_code): $ret_msg"

  success "Package attached to draft (fileType=${file_type}, file=${file_name})."
}

# ---------------------------------------------------------------------------
# Step 4 – Submit for review / release (POST app-submit)
# ---------------------------------------------------------------------------
submit_for_review() {
  local step_label="${1:-Step 4}"

  local release_label
  if [[ "$RELEASE_TYPE" == "3" ]]; then
    release_label="Phased release (${PHASE_PERCENT}%)"
    [[ -n "$PHASE_START_TIME" ]] && release_label+="  start: ${PHASE_START_TIME} UTC"
    [[ -n "$PHASE_END_TIME"   ]] && release_label+="  end: ${PHASE_END_TIME} UTC"
  else
    release_label="Whole-network release"
  fi

  info "${step_label} – Submitting for review … (${release_label})"

  # Build the submission payload
  local payload
  if [[ "$RELEASE_TYPE" == "3" ]]; then
    # Start with required phased fields
    payload=$(jq -n \
      --arg appId          "$APP_ID" \
      --argjson releaseType 3 \
      --argjson phaseReleasePercent "$PHASE_PERCENT" \
      '{
        appId:                $appId,
        releaseType:          $releaseType,
        phaseReleasePercent:  $phaseReleasePercent
      }')

    # Append optional start/end times if provided
    if [[ -n "$PHASE_START_TIME" ]]; then
      payload=$(echo "$payload" \
        | jq --arg t "$PHASE_START_TIME" '. + {phaseReleaseStartTime: $t}')
    fi
    if [[ -n "$PHASE_END_TIME" ]]; then
      payload=$(echo "$payload" \
        | jq --arg t "$PHASE_END_TIME" '. + {phaseReleaseEndTime: $t}')
    fi
  else
    payload=$(jq -n \
      --arg appId          "$APP_ID" \
      --argjson releaseType 1 \
      '{appId: $appId, releaseType: $releaseType}')
  fi

  local response
  response=$(curl -sf -X POST \
    "${BASE_URL}/app-submit?appId=${APP_ID}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID" \
    -H "Content-Type: application/json" \
    -d "$payload") \
    || die "Failed to call the app-submit endpoint."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo  "$response" | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "Submission failed (code=$ret_code): $ret_msg"

  success "App submitted for review successfully! 🎉"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  echo -e "\n${BOLD}═══ Huawei AppGallery Connect – Submit Script ═══${RESET}\n"

  check_deps
  resolve_region
  validate_inputs

  echo -e "  App ID      : ${BOLD}${APP_ID}${RESET}"
  echo -e "  Region      : ${BOLD}${REGION_LABEL}${RESET}"
  if [[ "$QUERY_ONLY" == true ]]; then
    echo -e "  Mode        : ${BOLD}Query only (no submission)${RESET}\n"
  elif [[ "$RELEASE_TYPE" == "3" ]]; then
    echo -e "  Release     : ${BOLD}Phased – ${PHASE_PERCENT}% of users${RESET}"
    [[ -n "$PHASE_START_TIME" ]] \
      && echo -e "  Phase start : ${BOLD}${PHASE_START_TIME} UTC${RESET}"
    [[ -n "$PHASE_END_TIME"   ]] \
      && echo -e "  Phase end   : ${BOLD}${PHASE_END_TIME} UTC${RESET}"
    echo ""
  else
    echo -e "  Release     : ${BOLD}Whole network${RESET}\n"
  fi

  # ── Query-only mode: authenticate, show status, exit ─────────────────────
  if [[ "$QUERY_ONLY" == true ]]; then
    get_access_token "Step 1/1"
    query_app_info   "Step 1/1"
    echo -e "${GREEN}${BOLD}Query complete. Nothing was submitted.${RESET}\n"
    exit 0
  fi

  # ── Full submission flow ──────────────────────────────────────────────────
  get_access_token   "Step 1/4"
  query_app_info     "Step 2/4"
  attach_package     "Step 3/4"
  submit_for_review  "Step 4/4"

  echo -e ""
  echo -e "${GREEN}${BOLD}Submission complete!${RESET}"
  if [[ "$RELEASE_TYPE" == "3" ]]; then
    echo -e "The app is under review for a phased rollout to ${BOLD}${PHASE_PERCENT}%${RESET} of users."
  else
    echo -e "The app is now under review for a whole-network release."
  fi
  echo -e "Monitor review status in the AppGallery Connect console.\n"
}

main "$@"
