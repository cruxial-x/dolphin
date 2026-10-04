#!/bin/bash
# Runs inside the builder image (see Dockerfile) with a persistent volume on /work that holds the
# git checkout, the Gradle home and the ccache, so that only the first build is a full one.
#
#   MODE=build  Build SHA (a commit on BRANCH).
#   MODE=sync   Update the master mirror, merge it into BRANCH, push both, then build BRANCH.
#               With SYNC_BUILD_UNCHANGED=0, stop without building if upstream hadn't moved.
#   MODE=dev    Build SHA, the head of pull request PR, as the test app (see thor.init.gradle).
#
# Nothing is built if only documentation or CI files (see app_unchanged_since) have changed since
# the release LAST_RELEASE_TAG or, for a pull request, since BRANCH. FORCE_BUILD=1 builds anyway.
#
# With MIRROR_URL (and MIRROR_TOKEN) set, BRANCH and master are also pushed there after the build.
#
# Results go to /out: the APK, info.env (for the workflow) and notes.md (release notes).
set -euo pipefail

: "${ORIGIN_URL:?}"
# Every build must be signed with the same key, or it can't be installed over the previous one.
if [ -z "${KEYSTORE_B64:-}" ] || [ -z "${KEYSTORE_PASS:-}" ]; then
  echo "KEYSTORE_B64 and KEYSTORE_PASS (the Actions secrets RELEASE_KEYSTORE_B64 and" \
       "RELEASE_KEYSTORE_PASS) must both be set." >&2
  exit 1
fi
: "${KEY_ALIAS:?}"
BRANCH=${BRANCH:-thor}
MODE=${MODE:-build}
UPSTREAM_URL=${UPSTREAM_URL:-https://github.com/dolphin-emu/dolphin.git}
SRC=/work/src
OUT=/out

export GRADLE_USER_HOME=/work/gradle
export CCACHE_DIR=/work/ccache CCACHE_MAXSIZE=15G CCACHE_BASEDIR=$SRC CCACHE_NOHASHDIR=1
export CMAKE_C_COMPILER_LAUNCHER=ccache CMAKE_CXX_COMPILER_LAUNCHER=ccache

mkdir -p "$OUT"
exec 9> /work/lock
flock 9

info() { echo "$1=$2" >> "$OUT/info.env"; }

# Whether everything that goes into the app is the same as in commit $1.
app_unchanged_since() {
  git diff --quiet "$1" HEAD -- . ':(exclude,glob)*.md' ':(exclude).gitea' \
    ':(exclude)Source/Android/thor/docs'
}

# Pushes to the public copy of the repository, if there is one.
mirror() {
  [ -n "${MIRROR_URL:-}" ] || return 0
  # The first -c clears the helper below, so that GIT_TOKEN is never offered to the mirror.
  if git -c credential.helper= \
       -c credential.helper='!f() { echo username=x-access-token; echo "password=$MIRROR_TOKEN"; }; f' \
       push --force "$MIRROR_URL" "HEAD:refs/heads/$BRANCH" upstream/master:refs/heads/master; then
    info MIRROR pushed
  else
    info MIRROR failed
    return 1
  fi
}

git config --global --add safe.directory '*'
git config --global user.name "Thor CI"
git config --global user.email "thor-ci@git.cruxial.org"
# The token only ever comes from the environment, so it's never stored in the checkout.
git config --global credential.helper \
  '!f() { echo username=thor-ci; echo "password=$GIT_TOKEN"; }; f'

if [ ! -d "$SRC/.git" ]; then
  git clone --no-checkout "$ORIGIN_URL" "$SRC"
fi
cd "$SRC"
git remote set-url origin "$ORIGIN_URL"
git remote get-url upstream > /dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"

git fetch --no-tags origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
git fetch --no-tags upstream "+refs/heads/master:refs/remotes/upstream/master" \
  "+refs/tags/*:refs/tags/*"

if [ "$MODE" = sync ]; then
  # master only ever mirrors upstream; this fails rather than overwrite anything else on it.
  git push origin "upstream/master:refs/heads/master"

  git checkout -q -f -B "$BRANCH" "origin/$BRANCH"
  git clean -q -ffd

  if git merge-base --is-ancestor upstream/master HEAD; then
    echo "$BRANCH already contains upstream master."
    info SYNC unchanged
    if [ "${SYNC_BUILD_UNCHANGED:-1}" = 0 ]; then
      exit 0
    fi
  elif ! git merge -q --no-edit -m "Merge upstream master into $BRANCH" upstream/master; then
    conflicts=$(git diff --name-only --diff-filter=U)
    git merge --abort
    {
      echo "Merging upstream \`$(git rev-parse --short=10 upstream/master)\` into \`$BRANCH\`" \
           "conflicts in:"
      echo
      echo "$conflicts" | sed 's/^/- /'
    } > "$OUT/conflict.md"
    info SYNC conflict
    exit 3
  else
    info SYNC merged
    git push origin "HEAD:refs/heads/$BRANCH"
  fi
  SHA=$(git rev-parse HEAD)
else
  : "${SHA:?}"
  if [ "$MODE" = dev ]; then
    : "${PR:?}"
    git fetch --no-tags origin "+refs/pull/$PR/head:refs/remotes/origin/pr"
  fi
  git checkout -q -f --detach "$SHA"
  git clean -q -ffd
fi

if [ "${FORCE_BUILD:-0}" != 1 ]; then
  since=
  if [ "$MODE" = dev ]; then
    since=$(git merge-base "origin/$BRANCH" HEAD)
  elif [ -n "${LAST_RELEASE_TAG:-}" ] && git fetch -q --no-tags origin \
         "+refs/tags/$LAST_RELEASE_TAG:refs/thor/last-release"; then
    since=refs/thor/last-release
  fi
  if [ -n "$since" ] && app_unchanged_since "$since"; then
    echo "Only documentation or CI files have changed since $(git rev-parse --short=10 "$since");" \
         "not building."
    info BUILD skipped
    # There is no release for the workflow to report a failed push after, so fail here.
    mirror
    exit 0
  fi
fi

git submodule sync -q --recursive
git submodule update -q --init --recursive --force --jobs 8

base64 -d <<< "$KEYSTORE_B64" > /tmp/thor.keystore

short_sha=$(git rev-parse --short=10 HEAD)
export THOR_VERSION_SUFFIX="-$short_sha"
apk_prefix=dolphin-thor
if [ "$MODE" = dev ]; then
  export THOR_DEV=1 THOR_VERSION_SUFFIX="-dev-$short_sha"
  apk_prefix=dolphin-thor-dev
fi

ccache -z > /dev/null
(cd Source/Android && ./gradlew --no-daemon --console=plain --init-script thor/thor.init.gradle \
  -Pkeystore=/tmp/thor.keystore -Pstorepass="$KEYSTORE_PASS" \
  -Pkeyalias="$KEY_ALIAS" -Pkeypass="${KEY_PASS:-$KEYSTORE_PASS}" \
  assembleRelease)
ccache -s

# Reading all of the output (rather than piping it to head) avoids a SIGPIPE under pipefail.
badging=$("$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  Source/Android/app/build/outputs/apk/release/app-release.apk)
badging=${badging%%$'\n'*}
version_name=$(sed -E "s/.*versionName='([^']*)'.*/\1/" <<< "$badging")
version_code=$(sed -E "s/.*versionCode='([^']*)'.*/\1/" <<< "$badging")
apk="$apk_prefix-$version_name.apk"
cp Source/Android/app/build/outputs/apk/release/app-release.apk "$OUT/$apk"

info SHA "$(git rev-parse HEAD)"
info APK "$apk"
info VERSION_NAME "$version_name"
info VERSION_CODE "$version_code"
info TAG "$version_name"

base=$(git merge-base HEAD upstream/master)
{
  if [ "$MODE" = dev ]; then
    echo "Test build of #$PR. It installs as Dolphin Thor Dev, next to Dolphin Thor, and is" \
         "signed with the Android debug key."
    echo
  fi
  echo "Based on dolphin-emu/dolphin@\`$(git rev-parse --short=10 "$base")\`" \
       "($(git log -1 --format=%cs "$base"))."
  echo
  echo "Thor commits on top of upstream:"
  echo
  git log --reverse --no-merges --format='- %s' "$base..HEAD"
} > "$OUT/notes.md"

# This comes last and doesn't fail the build, so that the release on this server still goes out;
# the workflow reports a failed push afterwards.
mirror || true
