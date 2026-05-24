#!/usr/bin/env bash
# scripts/run-on-device.sh — one-shot install + smoke-test for LocalLLM on a
# connected Android device. Pass `--device <serial>` to target a specific
# attached device, or leave blank to pick the first / only one.
#
# What it does, in order:
#   1. Find a connected device (single device → silent; multiple → require flag)
#   2. Build :app:assembleDebug (or skip with --no-build)
#   3. Install the arm64 APK (or universal if arm64 missing)
#   4. Launch MainActivity so the foreground constraint is satisfied
#   5. adb forward tcp:18080 → tcp:8080 so curl from the host works
#   6. Probe /health, /v1/aicore/status?probe=all, /v1/models, /v1/chat/completions
#      against the AICore model — expects either a real response (working
#      device) or the structured AICORE_UNAVAILABLE envelope (broken AICore)
#   7. Print a one-line summary
#
# Exit non-zero only on hard failures (no device, build/install failed,
# server didn't come up). A broken AICore on the device prints WARN but
# returns 0 — that's a device state, not a script failure.

set -euo pipefail

# ---- arg parsing ----
DEVICE=""
BUILD=1
PORT_HOST=18080
PORT_DEVICE=8080
PKG="com.localllm.app"
ACTIVITY="$PKG/.MainActivity"
APIKEY="${LOCALLLM_API_KEY:-dev-token}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device|-s) DEVICE="$2"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --port) PORT_HOST="$2"; shift 2 ;;
        --help|-h)
            grep '^#' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# ---- repo root resolution ----
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---- pick a device ----
if [[ -z "$DEVICE" ]]; then
    mapfile -t DEVS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [[ ${#DEVS[@]} -eq 0 ]]; then
        echo "ERR: no Android device attached. Connect one and re-run." >&2
        exit 1
    fi
    if [[ ${#DEVS[@]} -gt 1 ]]; then
        echo "ERR: multiple devices attached; pass --device <serial>:" >&2
        printf '  %s\n' "${DEVS[@]}" >&2
        exit 1
    fi
    DEVICE="${DEVS[0]}"
fi

# Verify the device is actually reachable.
if ! adb -s "$DEVICE" shell true >/dev/null 2>&1; then
    echo "ERR: device $DEVICE not reachable via adb." >&2
    exit 1
fi

DEV_MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')
DEV_CODENAME=$(adb -s "$DEVICE" shell getprop ro.product.device | tr -d '\r')
SOC=$(adb -s "$DEVICE" shell getprop ro.soc.model | tr -d '\r')
echo "[device] $DEVICE  $DEV_MODEL ($DEV_CODENAME, SoC=$SOC)"

# ---- build ----
if [[ $BUILD -eq 1 ]]; then
    echo "[build] :app:assembleDebug"
    ./gradlew :app:assembleDebug -q
else
    echo "[build] skipped (--no-build)"
fi

# ---- pick APK that matches device ABI ----
APK_ARM64="$ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
APK_UNIV="$ROOT/app/build/outputs/apk/debug/app-universal-debug.apk"
if [[ -f "$APK_ARM64" ]]; then APK="$APK_ARM64"
elif [[ -f "$APK_UNIV" ]]; then APK="$APK_UNIV"
else
    echo "ERR: no debug APK found under app/build/outputs/apk/debug/" >&2
    exit 1
fi
echo "[install] $(basename "$APK")"
adb -s "$DEVICE" install -r "$APK" >/dev/null

# ---- pre-launch device prep ----
# Wake + unlock so MainActivity actually goes visible (needed for the FGS
# notification to register on Android 14+).
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$DEVICE" shell input keyevent KEYCODE_MENU   >/dev/null 2>&1 || true
# Pre-grant POST_NOTIFICATIONS — autostart in MainActivity gates on this.
adb -s "$DEVICE" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS \
    >/dev/null 2>&1 || true

# ---- launch MainActivity (AICore requires foreground; FGS autostarts here) ----
echo "[launch] $ACTIVITY"
adb -s "$DEVICE" shell am start -n "$ACTIVITY" >/dev/null
# Wait for the in-app server to bind. The Service starts in onCreate of the
# Application class but the Ktor bind is async. Poll for the port.
echo -n "[wait]  server port $PORT_DEVICE on device "
for i in {1..30}; do
    if adb -s "$DEVICE" shell "ss -ltnp 2>/dev/null" | grep -q ":${PORT_DEVICE}\b"; then
        echo "ready (${i}s)"
        break
    fi
    echo -n "."
    sleep 1
    if [[ $i -eq 30 ]]; then
        echo
        echo "ERR: server didn't bind to :$PORT_DEVICE within 30s on $DEVICE." >&2
        echo "     Check 'adb -s $DEVICE logcat | grep LLMServerService'" >&2
        exit 1
    fi
done

# ---- port forward ----
adb -s "$DEVICE" forward "tcp:$PORT_HOST" "tcp:$PORT_DEVICE" >/dev/null
BASE="http://127.0.0.1:$PORT_HOST"
echo "[forward] localhost:$PORT_HOST → $DEVICE:$PORT_DEVICE"

# ---- helper: curl + pretty-print or fail cleanly ----
hit() {
    local method="$1"; local path="$2"; local data="${3:-}"
    if [[ -n "$data" ]]; then
        curl -sS --max-time 30 -X "$method" "$BASE$path" \
            -H "Authorization: Bearer $APIKEY" \
            -H "Content-Type: application/json" \
            -d "$data"
    else
        curl -sS --max-time 30 -X "$method" "$BASE$path" \
            -H "Authorization: Bearer $APIKEY"
    fi
}

# ---- smoke tests ----
echo
echo "──── /health ────"
hit GET /health | python3 -m json.tool

echo
echo "──── /v1/aicore/status?probe=all ────"
hit GET '/v1/aicore/status?probe=all' | python3 -m json.tool

echo
echo "──── /v1/models ────"
hit GET /v1/models | python3 -m json.tool

echo
echo "──── POST /v1/chat/completions (gemini-nano-aicore) ────"
CHAT_BODY='{"model":"gemini-nano-aicore","messages":[{"role":"user","content":"Reply with the single word OK."}]}'
hit POST /v1/chat/completions "$CHAT_BODY" | python3 -m json.tool

# ---- summary ----
echo
echo "──── SUMMARY ────"
AICORE_STATUS=$(hit GET /v1/aicore/status 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))")
HEALTH=$(hit GET /health 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))")
echo "  device:        $DEV_MODEL ($DEVICE)"
echo "  server:        $BASE — $HEALTH"
echo "  AICore status: $AICORE_STATUS"
if [[ "$AICORE_STATUS" == "available" ]]; then
    echo "  ✓ Ready. Try:"
    echo "    curl $BASE/v1/aicore/benchmark -X POST -H 'Authorization: Bearer $APIKEY'"
else
    echo "  ⚠ AICore not usable on this device. LiteRT-LM models still work."
    echo "    See /v1/models for downloadable .litertlm options."
fi
