#!/usr/bin/env bash
# =============================================================================
# Huawei AppGallery Connect – Phased Rollout Management Script
# =============================================================================
# Prerequisites:
#   Linux : sudo apt install curl jq
#   macOS : brew install curl jq        (no extra tools needed beyond these)
#   - A Huawei AppGallery Connect API client (Client ID + Client Secret)
#     https://developer.huawei.com/consumer/en/doc/development/AppGallery-connect-Guides/agcapi-getstarted
#
# Usage:
#   chmod +x huawei_appgallery_phase.sh
#   ./huawei_appgallery_phase.sh [options]
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
#   -p  <percent>         New rollout percentage to set (1–100)
#                         Must be HIGHER than the current percentage.
#                         Use 100 with --full to promote to whole-network release.
#   -k  <action>          Action to perform instead of percentage update:
#                           pause    → Pause the phased rollout (state → 9)
#                           resume   → Resume a paused rollout  (state → 8)
#                           full     → Promote to whole-network release immediately
#                           halt     → Halt (stop) the phased rollout entirely
#   -y                    Skip the confirmation prompt (non-interactive / CI mode)
#   -q                    Query current phased release status only, no changes
#   -h                    Show this help message
#
# Behaviour:
#   1. Obtains an OAuth2 access token.
#   2. Queries the current app state and phased release details.
#   3. Validates that the app is in a phased-release state (8 or 9).
#   4. For -p: validates the new percentage is strictly greater than the current.
#   5. Calls PUT /app-release/phase to apply the requested change.
#
# Phased release state reference:
#   8  Under phased release  (active — percentage can be increased)
#   9  Phased release paused (must resume before increasing percentage)
#
# Percentage rules (enforced by Huawei API):
#   • Must be an integer or decimal with up to 2 decimal places (e.g. 5, 10.5)
#   • Must be strictly greater than the current percentage
#   • Cannot be decreased — use pause/halt to stop a bad rollout
#
# Example – increase rollout from 10% to 25%:
#   ./huawei_appgallery_phase.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -p 25
#
# Example – pause the rollout:
#   ./huawei_appgallery_phase.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -k pause
#
# Example – resume a paused rollout:
#   ./huawei_appgallery_phase.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -k resume
#
# Example – promote to 100% whole-network release:
#   ./huawei_appgallery_phase.sh \
#       -c "your_client_id" -s "your_client_secret" -a "your_app_id" \
#       -k full
#
# Example – query current phased release status only:
#   ./huawei_appgallery_phase.sh -q \
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
NEW_PERCENT=""       # -p : new rollout percentage
ACTION=""            # -k : pause | resume | full | halt
AUTO_CONFIRM=false   # -y
QUERY_ONLY=false     # -q

AUTH_URL=""
BASE_URL=""
REGION_LABEL=""
ACCESS_TOKEN=""

# Populated by query_phase_status
CURRENT_STATE=""
CURRENT_PERCENT=""
APP_NAME=""
VERSION_CODE=""
VERSION_NAME=""

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,1\}//'
  exit 0
}

while getopts ":c:s:a:g:l:p:k:yqh" opt; do
  case $opt in
    c) CLIENT_ID="$OPTARG" ;;
    s) CLIENT_SECRET="$OPTARG" ;;
    a) APP_ID="$OPTARG" ;;
    g) REGION="$OPTARG" ;;
    l) LANG="$OPTARG" ;;
    p) NEW_PERCENT="$OPTARG" ;;
    k) ACTION="${OPTARG,,}" ;;   # lowercase
    y) AUTO_CONFIRM=true ;;
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

  if [[ "$QUERY_ONLY" == true ]]; then
    return 0
  fi

  # Must supply exactly one of -p or -k
  if [[ -n "$NEW_PERCENT" && -n "$ACTION" ]]; then
    die "Use either -p <percent> OR -k <action>, not both."
  fi
  if [[ -z "$NEW_PERCENT" && -z "$ACTION" ]]; then
    die "Specify an action: -p <percent> to update rollout %, or -k <pause|resume|full|halt>."
  fi

  # Validate percentage value
  if [[ -n "$NEW_PERCENT" ]]; then
    # Allow integers and decimals with up to 2 decimal places (e.g. 5, 10.5, 33.33)
    if ! [[ "$NEW_PERCENT" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then
      die "Percentage (-p) must be a number with up to 2 decimal places (e.g. 25 or 33.33)."
    fi
    local int_part="${NEW_PERCENT%%.*}"
    if (( int_part < 1 || int_part > 100 )); then
      die "Percentage (-p) must be between 1 and 100."
    fi
  fi

  # Validate action keyword
  if [[ -n "$ACTION" ]]; then
    case "$ACTION" in
      pause|resume|full|halt) ;;
      *) die "Unknown action (-k): '${ACTION}'. Valid values: pause, resume, full, halt" ;;
    esac
  fi
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
# Step 2 – Query current phased release state and display summary
# ---------------------------------------------------------------------------
query_phase_status() {
  local step_label="${1:-Step 2/3}"
  info "${step_label} – Querying current phased release status …"

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

  CURRENT_STATE=$(echo   "$response" | jq -r '.appInfo.releaseState              // "0"')
  CURRENT_PERCENT=$(echo "$response" | jq -r '.appInfo.phaseReleasePercent       // "N/A"')
  APP_NAME=$(echo        "$response" | jq -r '.appInfo.appName                   // "N/A"')
  VERSION_CODE=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].versionCode // "N/A"')
  VERSION_NAME=$(echo    "$response" | jq -r '.appInfo.pkgVersion[0].versionName // "N/A"')

  local phase_start phase_end
  phase_start=$(echo "$response" | jq -r '.appInfo.phaseReleaseStartTime // ""')
  phase_end=$(echo   "$response" | jq -r '.appInfo.phaseReleaseEndTime   // ""')

  # releaseState label
  local state_label state_colour
  case "$CURRENT_STATE" in
    8)  state_label="Under phased release"; state_colour="$GREEN" ;;
    9)  state_label="Phased release paused"; state_colour="$YELLOW" ;;
    4)  state_label="Released (whole network)"; state_colour="$GREEN" ;;
    10) state_label="Phased release complete"; state_colour="$GREEN" ;;
    *)  state_label="Not in phased release (state=${CURRENT_STATE})"; state_colour="$RED" ;;
  esac

  echo -e ""
  echo -e "${BOLD}  ╔══════════════════════════════════════════════════╗${RESET}"
  echo -e "${BOLD}  ║         Phased Rollout Status                    ║${RESET}"
  echo -e "${BOLD}  ╚══════════════════════════════════════════════════╝${RESET}"
  echo -e ""
  echo -e "  App name     : ${BOLD}${APP_NAME}${RESET}"
  echo -e "  App ID       : ${APP_ID}"
  echo -e "  Region       : ${REGION_LABEL}"
  echo -e "  versionCode  : ${BOLD}${VERSION_CODE}${RESET}"
  echo -e "  versionName  : ${VERSION_NAME}"
  echo -e ""
  echo -e "  ── Rollout ─────────────────────────────────────────"
  echo -e "  State        : ${state_colour}${BOLD}${state_label}${RESET}"
  echo -e "  Current %    : ${BOLD}${CURRENT_PERCENT}%${RESET}"
  if [[ -n "$phase_start" && "$phase_start" != "null" ]]; then
    echo -e "  Start time   : ${phase_start}"
  fi
  if [[ -n "$phase_end" && "$phase_end" != "null" ]]; then
    echo -e "  End time     : ${phase_end}"
  fi
  echo -e ""

  success "Phase status retrieved."
}

# ---------------------------------------------------------------------------
# State guard – ensure app is in a phased-release state before proceeding
# ---------------------------------------------------------------------------
guard_phase_state() {
  case "$CURRENT_STATE" in
    8|9)
      # Valid states for percentage update, pause, resume, full, halt
      ;;
    4|10)
      die "App is fully released (state=${CURRENT_STATE}). No phased rollout is active."
      ;;
    1|2|3)
      die "App is not yet in phased release (state=${CURRENT_STATE}). Submit with -r 3 first."
      ;;
    *)
      die "Unexpected app state: ${CURRENT_STATE}. Cannot manage phased rollout."
      ;;
  esac

  # Additional guards per action
  if [[ -n "$NEW_PERCENT" || "$ACTION" == "resume" || "$ACTION" == "full" ]]; then
    if [[ "$CURRENT_STATE" == "9" && -z "$ACTION" ]]; then
      die "Rollout is paused (state=9). Resume it first with -k resume before increasing the percentage."
    fi
  fi

  if [[ "$ACTION" == "pause" && "$CURRENT_STATE" == "9" ]]; then
    die "Rollout is already paused (state=9). Use -k resume to resume it."
  fi

  if [[ "$ACTION" == "resume" && "$CURRENT_STATE" == "8" ]]; then
    die "Rollout is already active (state=8). Nothing to resume."
  fi
}

# ---------------------------------------------------------------------------
# Percentage guard – new % must be strictly greater than current
# ---------------------------------------------------------------------------
guard_percent_increase() {
  [[ -z "$NEW_PERCENT" ]] && return 0

  if [[ "$CURRENT_PERCENT" == "N/A" || "$CURRENT_PERCENT" == "null" ]]; then
    warn "Could not read current percentage from API. Proceeding without increase check."
    return 0
  fi

  # Use awk for floating-point comparison (portable on macOS and Linux)
  local is_greater
  is_greater=$(awk -v new="$NEW_PERCENT" -v cur="$CURRENT_PERCENT" \
    'BEGIN { print (new > cur) ? "yes" : "no" }')

  if [[ "$is_greater" != "yes" ]]; then
    error "New percentage (${NEW_PERCENT}%) must be GREATER than the current percentage (${CURRENT_PERCENT}%)."
    echo -e "  Huawei does not allow decreasing the rollout percentage." >&2
    echo -e "  To stop a bad rollout, use: -k halt" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# Confirmation prompt
# ---------------------------------------------------------------------------
describe_action() {
  if [[ -n "$NEW_PERCENT" ]]; then
    echo "Increase rollout from ${CURRENT_PERCENT}% → ${NEW_PERCENT}%"
  else
    case "$ACTION" in
      pause)  echo "Pause the phased rollout (no new users will receive the update)" ;;
      resume) echo "Resume the paused phased rollout" ;;
      full)   echo "Promote to WHOLE-NETWORK release — all users receive the update immediately" ;;
      halt)   echo "Halt (stop) the phased rollout entirely" ;;
    esac
  fi
}

confirm_action() {
  local desc
  desc=$(describe_action)

  echo -e "${BOLD}  Planned action:${RESET} ${desc}"
  echo -e ""

  if [[ "$AUTO_CONFIRM" == true ]]; then
    warn "Auto-confirm enabled (-y) — proceeding without prompt."
    return 0
  fi

  local prompt_colour="$CYAN"
  [[ "$ACTION" == "full" || "$ACTION" == "halt" ]] && prompt_colour="$RED"

  echo -en "${prompt_colour}  → Confirm? [y/N]: ${RESET}"
  local answer
  read -r answer
  case "$answer" in
    [yY]|[yY][eE][sS]) echo "" ;;
    *)
      echo -e "\n${YELLOW}Action cancelled. No changes made.${RESET}\n"
      exit 2
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Step 3 – Apply the phased release change
#
# Huawei API: PUT /publish/v2/app-release/phase?appId=<appId>
# Payload fields:
#   phaseReleasePercent  – new percentage (when updating %)
#   releaseType          – 1 = convert to whole-network release immediately
#   status               – 1 = resume, 2 = pause, 3 = halt/stop
# ---------------------------------------------------------------------------
apply_phase_change() {
  info "Step 3/3 – Applying phased rollout change …"

  local payload

  if [[ -n "$NEW_PERCENT" ]]; then
    # Increase rollout percentage
    payload=$(jq -n \
      --argjson phaseReleasePercent "$NEW_PERCENT" \
      '{phaseReleasePercent: $phaseReleasePercent}')

  else
    case "$ACTION" in
      pause)
        payload=$(jq -n '{status: 2}')
        ;;
      resume)
        payload=$(jq -n '{status: 1}')
        ;;
      full)
        # Convert phased rollout to whole-network release immediately
        payload=$(jq -n '{releaseType: 1}')
        ;;
      halt)
        # Stop/halt the phased rollout entirely
        payload=$(jq -n '{status: 3}')
        ;;
    esac
  fi

  local response
  response=$(curl -sf -X PUT \
    "${BASE_URL}/app-release/phase?appId=${APP_ID}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "client_id: $CLIENT_ID" \
    -H "Content-Type: application/json" \
    -d "$payload") \
    || die "Failed to call the phase-release update endpoint."

  local ret_code ret_msg
  ret_code=$(echo "$response" | jq -r '.ret.code')
  ret_msg=$(echo  "$response" | jq -r '.ret.msg')

  [[ "$ret_code" == "0" ]] \
    || die "Phase release update failed (code=$ret_code): $ret_msg"

  success "$(describe_action) — applied successfully."
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  echo -e "\n${BOLD}═══ Huawei AppGallery Connect – Phased Rollout ═══${RESET}\n"

  check_deps
  resolve_region
  validate_inputs

  echo -e "  App ID  : ${BOLD}${APP_ID}${RESET}"
  echo -e "  Region  : ${BOLD}${REGION_LABEL}${RESET}"
  if [[ "$QUERY_ONLY" == true ]]; then
    echo -e "  Mode    : ${BOLD}Query only${RESET}\n"
  elif [[ -n "$NEW_PERCENT" ]]; then
    echo -e "  Action  : ${BOLD}Set rollout to ${NEW_PERCENT}%${RESET}\n"
  else
    echo -e "  Action  : ${BOLD}${ACTION}${RESET}\n"
  fi

  # Query-only mode
  if [[ "$QUERY_ONLY" == true ]]; then
    get_access_token
    info "Step 1/1 – Obtaining access token …" 2>/dev/null || true
    # Re-run quietly for query mode
    ACCESS_TOKEN=""
    local body
    body=$(jq -n \
      --arg grantType    "client_credentials" \
      --arg clientId     "$CLIENT_ID" \
      --arg clientSecret "$CLIENT_SECRET" \
      '{grant_type: $grantType, client_id: $clientId, client_secret: $clientSecret}')
    local tok_resp
    tok_resp=$(curl -sf -X POST "$AUTH_URL" \
      -H "Content-Type: application/json" \
      -d "$body") || die "Token request failed."
    ACCESS_TOKEN=$(echo "$tok_resp" | jq -r '.access_token // empty')
    [[ -n "$ACCESS_TOKEN" ]] || die "Could not parse access_token."

    query_phase_status "Step 1/1"
    echo -e "${GREEN}${BOLD}Query complete. No changes made.${RESET}\n"
    exit 0
  fi

  # Full action flow
  get_access_token
  query_phase_status "Step 2/3"
  guard_phase_state
  guard_percent_increase
  confirm_action
  apply_phase_change

  echo -e ""
  echo -e "${GREEN}${BOLD}Done!${RESET}"
  case "$ACTION" in
    pause)  echo -e "The rollout is paused. Use ${BOLD}-k resume${RESET} to continue it." ;;
    resume) echo -e "The rollout is active again at ${BOLD}${CURRENT_PERCENT}%${RESET}. Use ${BOLD}-p <percent>${RESET} to increase." ;;
    full)   echo -e "The app is now releasing to all users on the whole network." ;;
    halt)   echo -e "The phased rollout has been stopped. The app remains at ${BOLD}${CURRENT_PERCENT}%${RESET} of users." ;;
    "")     echo -e "Rollout is now at ${BOLD}${NEW_PERCENT}%${RESET} of users." ;;
  esac
  echo ""
}

main "$@"
