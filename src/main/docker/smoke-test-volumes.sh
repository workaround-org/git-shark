#!/usr/bin/env sh
# Smoke test: the documented Compose setup mounts named volumes at the /data
# paths below and relies on Docker copying ownership from the image into a
# freshly created volume. That only works when the paths exist in the image
# and are owned by the runtime UID — this script fails the build when they
# don't, which used to leave every /data mount root-owned and unwritable
# (no persisted SSH host key, repository creation failing at first write).
#
# Usage: smoke-test-volumes.sh <image> <runtime-uid>
#   e.g. smoke-test-volumes.sh git-shark:local 185      # JVM image
#        smoke-test-volumes.sh git-shark:native 1001    # native image
set -eu

IMAGE="${1:?usage: smoke-test-volumes.sh <image> <runtime-uid>}"
UID_EXPECTED="${2:?usage: smoke-test-volumes.sh <image> <runtime-uid>}"

DATA_DIRS="data/repositories data/avatars data/repo-images data/ssh"

# --- 1. Static check: paths exist in the image, owned by the runtime UID. ---
# Works for every image, including the shell-less native-micro base, because
# it inspects the exported filesystem instead of executing anything inside.
CONTAINER="$(docker create "$IMAGE")"
trap 'docker rm -f "$CONTAINER" >/dev/null' EXIT
LISTING="$(docker export "$CONTAINER" | tar -tv)"

FAILED=0
for DIR in $DATA_DIRS; do
	LINE="$(printf '%s\n' "$LISTING" | grep -E "[[:space:]]$DIR/?$" || true)"
	if [ -z "$LINE" ]; then
		echo "FAIL: /$DIR missing from image $IMAGE" >&2
		FAILED=1
	elif ! printf '%s\n' "$LINE" | grep -qE "[[:space:]]$UID_EXPECTED/"; then
		echo "FAIL: /$DIR not owned by uid $UID_EXPECTED: $LINE" >&2
		FAILED=1
	fi
done
[ "$FAILED" -eq 0 ] || exit 1
echo "OK: all /data paths present in image and owned by uid $UID_EXPECTED"

# --- 2. Functional check: the runtime user can write through fresh volumes. ---
# Anonymous volumes get the same ownership-copy treatment as the named volumes
# in the documented docker-compose.yml. Skipped when the image has no shell.
if printf '%s\n' "$LISTING" | grep -qE '[[:space:]](bin|usr/bin)/sh( |$)'; then
	docker run --rm \
		-v /data/repositories -v /data/avatars -v /data/repo-images -v /data/ssh \
		--entrypoint "" "$IMAGE" \
		sh -c 'touch /data/repositories/.rw /data/avatars/.rw /data/repo-images/.rw /data/ssh/.rw'
	echo "OK: runtime user can write all /data mount points"
else
	echo "SKIP: image has no shell; static ownership check only"
fi
