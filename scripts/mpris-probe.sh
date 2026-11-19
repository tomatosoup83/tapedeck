#!/usr/bin/env bash
#
# mpris-probe.sh — show what a Linux Tapedeck bridge would see on the D-Bus session bus.
#
# The diagnostic counterpart to the macOS osascript bridge: it answers, in one shot,
# "is there a player, which one would the mod pick, and what would it draw?".
#
# It uses the same primitives the intended port uses:
#   * discovery via well-known names `org.mpris.MediaPlayer2.*`
#   * one `Properties.GetAll` per player (a single round trip, no polling loops)
#   * the same election rule: Playing > Paused > Stopped, minus an ignore list
#   * the same unit conversion: `Position` and `mpris:length` are MICROSECONDS
#
# Exit codes:
#   0  at least one player found
#   2  no usable session bus, or no D-Bus tooling at all
#   3  bus is fine, but no MPRIS players are on it
#
set -uo pipefail

PLAYER_PATH="/org/mpris/MediaPlayer2"
PLAYER_IFACE="org.mpris.MediaPlayer2.Player"
PROPS_IFACE="org.freedesktop.DBus.Properties"
NAME_PREFIX="org.mpris.MediaPlayer2."
# ASCII Unit Separator, the same delimiter the mod's wire format uses: it cannot occur
# in a song title, so metadata can never shift a field.
US=$'\x1f'

MODE="all"
IGNORE="${MPRIS_IGNORE:-}"   # empty by default: show everything, flag duplicates instead
NO_COLOR="${NO_COLOR:-}"
PARSER="${MPRIS_PARSER:-auto}"   # auto | jq | python — force python to exercise that path

# ---------------------------------------------------------------------------- helpers

die() { printf 'error: %s\n' "$1" >&2; exit "${2:-2}"; }

have() { command -v "$1" >/dev/null 2>&1; }

bold() {
	if [[ -n $NO_COLOR || ! -t 1 ]]; then printf '%s' "$1"; else printf '\033[1m%s\033[0m' "$1"; fi
}

# Microseconds -> m:ss, or --:-- when unknown or nonsensical.
fmt_us() {
	local us=$1 secs
	if ! [[ $us =~ ^-?[0-9]+$ ]] || (( us <= 0 )); then
		printf -- '--:--'
		return
	fi
	secs=$(( us / 1000000 ))
	printf '%d:%02d' $(( secs / 60 )) $(( secs % 60 ))
}

usage() {
	cat <<-'EOF'
	Usage: mpris-probe.sh [option]

	  (no option)       every player, plus the one Tapedeck would mirror
	  --playing         only the elected player, one tab-separated line (scriptable)
	  --json            raw GetAll JSON per player (paste straight into a test fixture)
	  --watch           stream the signals a bridge would subscribe to
	  --ignore REGEX    skip players whose bus name matches REGEX
	  -h, --help        this text

	Environment:
	  MPRIS_IGNORE=REGEX   same as --ignore
	  NO_COLOR=1           disable bold output

	Exit: 0 player(s) found, 2 no bus, 3 no players.
	EOF
	exit 0
}

# ----------------------------------------------------------------------- bus handling

# busctl falls back to $XDG_RUNTIME_DIR/bus, so an unset env var does not prove the bus
# is unreachable — but a Java client would need the address, so report both states.
bus_report() {
	local addr="${DBUS_SESSION_BUS_ADDRESS:-}"
	if [[ -n $addr ]]; then
		printf '%s\n' "$addr"
	else
		printf 'unset (busctl falls back to %s)\n' "${XDG_RUNTIME_DIR:-/run/user/$UID}/bus"
	fi
}

bus_reachable() { busctl --user --no-pager list --no-legend >/dev/null 2>&1; }

require_bus() {
	if ! have busctl && ! have gdbus; then
		die "neither busctl nor gdbus is installed; install systemd or glib2" 2
	fi
	if ! bus_reachable; then
		die "cannot reach the session bus (DBUS_SESSION_BUS_ADDRESS=${DBUS_SESSION_BUS_ADDRESS:-unset})
  * launched from SSH or a systemd unit? run this from your desktop session
  * inside a Flatpak/Snap sandbox? grant bus access, or run outside the sandbox
  * retry with: DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/$UID/bus $0" 2
	fi
}

# MPRIS well-known names, one per line, with $IGNORE applied.
list_names() {
	local names
	if have gdbus; then
		# ListNames is unambiguous; the busctl table needs column parsing.
		names=$(gdbus call --session --dest org.freedesktop.DBus \
			--object-path /org/freedesktop/DBus \
			--method org.freedesktop.DBus.ListNames 2>/dev/null \
			| tr ',' '\n' | tr -d " []'\"" \
			| grep -E "^${NAME_PREFIX//./\\.}")
	else
		names=$(busctl --user --no-pager list --no-legend 2>/dev/null \
			| awk '{print $1}' | grep -E "^${NAME_PREFIX//./\\.}")
	fi
	[[ -z $names ]] && return 0
	if [[ -n $IGNORE ]]; then
		printf '%s\n' "$names" | grep -Ev -- "$IGNORE" || true
	else
		printf '%s\n' "$names"
	fi
}

# One round trip for every Player property, including Position.
fetch_props() {
	busctl --user call --json=short "$1" "$PLAYER_PATH" "$PROPS_IFACE" \
		GetAll s "$PLAYER_IFACE" 2>/dev/null
}

# ------------------------------------------------------------------- JSON extraction

# Flattens one GetAll reply into US-separated fields:
#   state title artist album length_us position_us rate canControl trackid url artUrl
render_fields() {
	if [[ $PARSER != python ]] && have jq; then
		jq -r '
			def v: if . == null then "" elif (type == "array") then join(", ") else tostring end;
			.data[0] as $p
			| [
				($p.PlaybackStatus.data // "Stopped" | v),
				($p.Metadata.data["xesam:title"].data // "" | v),
				($p.Metadata.data["xesam:artist"].data // "" | v),
				($p.Metadata.data["xesam:album"].data // "" | v),
				($p.Metadata.data["mpris:length"].data // 0 | v),
				($p.Position.data // 0 | v),
				(($p.Rate.data // 1) | tonumber * 100 | round / 100),
				($p.CanControl.data // false | v),
				($p.Metadata.data["mpris:trackid"].data // "" | v),
				($p.Metadata.data["xesam:url"].data // "" | v),
				($p.Metadata.data["mpris:artUrl"].data // "" | v)
			] | map(tostring) | join("\u001f")' 2>/dev/null
	elif have python3; then
		python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)["data"][0]
except Exception:
    sys.exit(1)
def pick(container, key, default=""):
    v = container.get(key) if isinstance(container, dict) else None
    if not isinstance(v, dict):
        return default
    v = v.get("data", default)
    if isinstance(v, list):
        return ", ".join(str(x) for x in v)
    return v
md = (d.get("Metadata") or {}).get("data") or {}
try:
    rate = f"{float(pick(d, "Rate", 1)):g}"
except Exception:
    rate = "1"
fields = [
    pick(d, "PlaybackStatus", "Stopped"), pick(md, "xesam:title"),
    pick(md, "xesam:artist"), pick(md, "xesam:album"),
    pick(md, "mpris:length", 0), pick(d, "Position", 0), rate,
    str(pick(d, "CanControl", False)).lower(), pick(md, "mpris:trackid"),
    pick(md, "xesam:url"), pick(md, "mpris:artUrl"),
]
print("\x1f".join(str(f) for f in fields))
' 2>/dev/null
	else
		die "need jq or python3 to parse busctl JSON" 2
	fi
}

# The bridge's election rule: Playing=3, Paused=2, anything else=1.
state_rank() {
	case "${1,,}" in
		playing) echo 3 ;;
		paused)  echo 2 ;;
		*)       echo 1 ;;
	esac
}

# ------------------------------------------------------------------------ collection

NAMES=() STATES=() TITLES=() ARTISTS=() ALBUMS=() LENGTHS=()
POSITIONS=() RATES=() CONTROLS=() TRACKIDS=() URLS=() ARTS=()

collect() {
	local name json fields state title artist album len pos rate ctl tid url art
	while IFS= read -r name; do
		[[ -z $name ]] && continue
		json=$(fetch_props "$name")
		# A player can vanish between ListNames and GetAll; that is normal churn.
		if [[ -z $json || $json != "{"* ]]; then
			printf 'warning: %s disappeared while reading its properties\n' "$name" >&2
			continue
		fi
		# The reply is a variant wrapper; render_fields tolerates a missing Metadata map.
		fields=$(printf '%s' "$json" | render_fields)
		[[ -z $fields ]] && { printf 'warning: could not parse %s\n' "$name" >&2; continue; }
		IFS=$US read -r state title artist album len pos rate ctl tid url art <<<"$fields"
		NAMES+=("$name"); STATES+=("$state"); TITLES+=("$title"); ARTISTS+=("$artist")
		ALBUMS+=("$album"); LENGTHS+=("$len"); POSITIONS+=("$pos"); RATES+=("$rate")
		CONTROLS+=("$ctl"); TRACKIDS+=("$tid"); URLS+=("$url"); ARTS+=("$art")
	done < <(list_names)
}

elect() {
	local i best=-1 best_rank=0 rank
	for i in "${!NAMES[@]}"; do
		rank=$(state_rank "${STATES[$i]}")
		# Strictly greater, so ties keep the first (alphabetical) name: deterministic.
		if (( rank > best_rank )); then best=$i; best_rank=$rank; fi
	done
	printf '%s' "$best"
}

# KDE in particular can re-export the same media under a second bus name.
dupes() {
	local i j
	for i in "${!NAMES[@]}"; do
		for j in "${!NAMES[@]}"; do
			(( j <= i )) && continue
			[[ -z ${TITLES[$i]} || ${TITLES[$i]} != "${TITLES[$j]}" ]] && continue
			printf 'note: same title from two players (%s, %s) — dedupe in the election policy\n' \
				"${NAMES[$i]}" "${NAMES[$j]}" >&2
		done
	done
}

# ---------------------------------------------------------------------------- output

show_player() {
	local i=$1 idx=$2
	printf '%s\n' "$(bold "[$idx] ${NAMES[$i]}")"
	printf '    state  : %s%s\n' "${STATES[$i]}" \
		"$( [[ ${CONTROLS[$i]} == true ]] && printf ' (controllable)' )"
	if [[ -z ${TITLES[$i]} ]]; then
		printf '    track  : -  (no metadata: idle player or radio stream)\n\n'
		return
	fi
	printf '    title  : %s\n' "${TITLES[$i]}"
	printf '    artist : %s\n' "${ARTISTS[$i]:--}"
	printf '    album  : %s\n' "${ALBUMS[$i]:--}"
	printf '    time   : %s / %s   (position %s µs, rate %s)\n' \
		"$(fmt_us "${POSITIONS[$i]}")" "$(fmt_us "${LENGTHS[$i]}")" \
		"${POSITIONS[$i]}" "${RATES[$i]}"
	printf '    trackid: %s\n' "${TRACKIDS[$i]:--}"
	[[ -n ${URLS[$i]} ]] && printf '    url    : %s\n' "${URLS[$i]}"
	[[ -n ${ARTS[$i]} ]] && printf '    art    : %s\n' "${ARTS[$i]}"
	printf '\n'
}

verdict() {
	local i=$1 heading
	if (( i < 0 )); then
		printf '%s (no player is Playing or Paused)\n' "$(bold 'Tapedeck would show:') nothing —"
		return
	fi
	if [[ -z ${TITLES[$i]} ]]; then
		printf '%s nothing — %s has no track metadata\n' "$(bold 'Tapedeck would show:')" "${NAMES[$i]}"
		return
	fi
	heading=${TITLES[$i]}
	[[ -n ${ARTISTS[$i]} ]] && heading="$heading - ${ARTISTS[$i]}"
	printf '%s %s\n' "$(bold 'Tapedeck would mirror:')" "${NAMES[$i]}"
	printf '  heading: %s\n' "$heading"
	printf '  border : %s\n' "$( [[ ${STATES[$i]} == Playing ]] && echo green || echo amber )"
	printf '  time   : %s / %s\n' "$(fmt_us "${POSITIONS[$i]}")" "$(fmt_us "${LENGTHS[$i]}")"
	# Observed live on music.apple.com in Firefox: title/artist/album present, no length.
	if ! [[ ${LENGTHS[$i]} =~ ^[0-9]+$ ]] || (( LENGTHS[$i] <= 0 )); then
		printf '  note   : no mpris:length in metadata, so the HUD hides the time line\n'
	fi
}

no_players_hint() {
	printf '%s\n' "$(bold 'No MPRIS players on the session bus.')"
	cat <<-'EOF'

	  Checks, cheapest first:
	    1. Is audio audibly playing right now? Firefox registers its MPRIS name only
	       while a media session is live and abandons it when playback ends.
	    2. Some sites never create a media session: if the page itself fails to play,
	       nothing is exported. music.apple.com needs Widevine (`widevine` from the AUR),
	       though it can play without it yet report no `mpris:length` (duration --:--).
	    3. Does the player implement MPRIS at all? mpv needs `mpv-mpris`, mpd needs
	       `mpd-mpris`; plain mpv/cvlc expose nothing.
	    4. Is the tab muted, or blocked by autoplay policy? Nothing audible = no session.
	EOF
}

# ------------------------------------------------------------------------- watch mode

watch_events() {
	have busctl || die "--watch needs busctl" 2
	have python3 || die "--watch needs python3" 2
	printf '%s\n' "$(bold 'watching MPRIS signals (ctrl-c to stop)')"
	printf '  bridge subscriptions: PropertiesChanged, Seeked, NameOwnerChanged\n\n'

	# The reader lives in a quoted heredoc on purpose: Python f-strings containing
	# backslash-escaped quotes cannot survive a bash single-quoted -c argument.
	local reader
	reader=$(cat <<'PY'
import json, sys

for line in sys.stdin:
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        msg = json.loads(line)
    except Exception:
        continue
    member = msg.get("member", "")
    iface = msg.get("interface", "")
    path = msg.get("path", "")
    payload = (msg.get("payload") or {}).get("data") or []

    if member == "NameOwnerChanged" and payload:
        name = str(payload[0])
        new_owner = payload[2] if len(payload) > 2 else ""
        if name.startswith("org.mpris.MediaPlayer2."):
            print("name  {}  {}".format(name, "appeared" if new_owner else "vanished"))
    elif member == "PropertiesChanged" and iface == "org.freedesktop.DBus.Properties" and payload:
        target = payload[0]
        changed = payload[1] if len(payload) > 1 else {}
        if target == "org.mpris.MediaPlayer2.Player":
            # A well-formed MPRIS signal sends a{sv}; guard anyway, because a malformed
            # or synthetic message must not kill the watcher.
            keys = ",".join(changed.keys()) if isinstance(changed, dict) else str(changed)
            print("props {}  changed: {}".format(path, keys))
    elif member == "Seeked" and payload:
        print("seek  {}  -> {:.3f}s".format(path, float(payload[0]) / 1e6))
PY
)
	# One broad match, filtered in the reader, so no match rule can hide an event.
	busctl --user monitor --json=short --match "type='signal'" 2>/dev/null \
		| python3 -u -c "$reader"
}

# ------------------------------------------------------------------------------ main

while (( $# )); do
	case "$1" in
		--playing)  MODE="playing" ;;
		--json)     MODE="json" ;;
		--watch)    MODE="watch" ;;
		--ignore)   shift; IGNORE="${1:-}" ;;
		--ignore=*) IGNORE="${1#*=}" ;;
		-h|--help)  usage ;;
		*)          die "unknown argument: $1 (try --help)" 2 ;;
	esac
	shift
done

require_bus

if [[ $MODE == watch ]]; then
	watch_events
	exit 0
fi

collect

if (( ${#NAMES[@]} == 0 )); then
	[[ $MODE != playing ]] && no_players_hint
	exit 3
fi

case "$MODE" in
	json)
		for i in "${!NAMES[@]}"; do
			# One builtin call per player, so `probe --json | head` produces at most one
			# broken-pipe complaint when the reader hangs up early.
			printf '# %s\n%s\n\n' "${NAMES[$i]}" "$(fetch_props "${NAMES[$i]}")"
		done
		;;
	all)
		printf 'session bus : %s\n' "$(bus_report)"
		printf 'players     : %d\n\n' "${#NAMES[@]}"
		for i in "${!NAMES[@]}"; do show_player "$i" $(( i + 1 )); done
		dupes
		verdict "$(elect)"
		;;
	playing)
		best=$(elect)
		if (( best < 0 )); then
			printf 'none\n'
		elif [[ -z ${TITLES[$best]} ]]; then
			printf '%s\t%s\t%s\n' "${NAMES[$best]}" "${STATES[$best]}" ""
		else
			printf '%s\t%s\t%s\t%s\t%s - %s\n' \
				"${NAMES[$best]}" "${STATES[$best]}" \
				"$(fmt_us "${POSITIONS[$best]}")" "$(fmt_us "${LENGTHS[$best]}")" \
				"${TITLES[$best]}" "${ARTISTS[$best]}"
		fi
		;;
esac

exit 0
