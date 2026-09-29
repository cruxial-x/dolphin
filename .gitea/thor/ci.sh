#!/bin/bash
# Runs inside the builder image (see Dockerfile) with a persistent volume on /work that holds the
# git checkout, the Gradle home and the ccache, so that only the first build is a full one.
#
#   MODE=build  Build SHA (a commit on BRANCH).
#   MODE=sync   Rebase BRANCH onto upstream master, push it (and the master mirror), then build it.
#               With SYNC_BUILD_UNCHANGED=0, stop without building if upstream hadn't moved.
#
# Results go to /out: the APK, info.env (for the workflow) and notes.md (release notes).
set -euo pipefail

: "${ORIGIN_URL:?}"
# Every build must be signed with the same key, or it can't be installed over the previous one.
if [ -z "${KEYSTORE_B64:-}" ]; then
  echo "KEYSTORE_B64 (the THOR_KEYSTORE secret) is not set." >&2
  exit 1
fi
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
  old_head=$(git rev-parse "origin/$BRANCH")
  git checkout -q -f -B "$BRANCH" "$old_head"
  git clean -q -ffd

  if git merge-base --is-ancestor upstream/master HEAD; then
    echo "$BRANCH already contains upstream master."
    info SYNC unchanged
    if [ "${SYNC_BUILD_UNCHANGED:-1}" = 0 ]; then
      exit 0
    fi
  elif ! git rebase upstream/master; then
    stopped_at=$(git log -1 --format='%h %s' REBASE_HEAD 2>/dev/null || true)
    conflicts=$(git diff --name-only --diff-filter=U)
    git rebase --abort
    {
      echo "Rebasing \`$BRANCH\` onto upstream \`$(git rev-parse --short upstream/master)\` stopped" \
           "at \`$stopped_at\` with conflicts in:"
      echo
      echo "$conflicts" | sed 's/^/- /'
    } > "$OUT/conflict.md"
    info SYNC conflict
    exit 3
  else
    info SYNC rebased
    git push origin "upstream/master:refs/heads/master"
    git push --force-with-lease="$BRANCH:$old_head" origin "HEAD:refs/heads/$BRANCH"
  fi
  SHA=$(git rev-parse HEAD)
else
  : "${SHA:?}"
  git checkout -q -f --detach "$SHA"
  git clean -q -ffd
fi

git submodule sync -q --recursive
git submodule update -q --init --recursive --force --jobs 8

mkdir -p ~/.android
base64 -d <<< "$KEYSTORE_B64" > ~/.android/debug.keystore

ccache -z > /dev/null
(cd Source/Android && ./gradlew --no-daemon --console=plain --init-script /ci/abi.init.gradle \
  assembleDebug)
ccache -s

badging=$("$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  Source/Android/app/build/outputs/apk/debug/app-debug.apk | head -1)
version_name=$(sed -E "s/.*versionName='([^']*)'.*/\1/" <<< "$badging")
version_code=$(sed -E "s/.*versionCode='([^']*)'.*/\1/" <<< "$badging")
short_sha=$(git rev-parse --short=10 HEAD)
apk="dolphin-thor-${version_name%-debug}-$short_sha.apk"
cp Source/Android/app/build/outputs/apk/debug/app-debug.apk "$OUT/$apk"

info SHA "$(git rev-parse HEAD)"
info APK "$apk"
info VERSION_NAME "$version_name"
info VERSION_CODE "$version_code"
info TAG "thor-${version_name%-debug}-$short_sha"

base=$(git merge-base HEAD upstream/master)
{
  echo "Based on dolphin-emu/dolphin@\`$(git rev-parse --short=10 "$base")\`" \
       "($(git log -1 --format=%cs "$base"))."
  echo
  echo "Thor commits on top of upstream:"
  echo
  git log --reverse --format='- %s' "$base..HEAD"
} > "$OUT/notes.md"
