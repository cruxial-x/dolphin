#!/bin/bash
# Runs inside the builder image (see Dockerfile) with a persistent volume on /work that holds the
# git checkout, the Gradle home and the ccache, so that only the first build is a full one.
#
#   MODE=build  Build SHA (a commit on BRANCH).
#   MODE=sync   Merge upstream master into BRANCH, push it, then build it.
#               With SYNC_BUILD_UNCHANGED=0, stop without building if upstream hadn't moved.
#
# Results go to /out: the APK, info.env (for the workflow) and notes.md (release notes).
set -euo pipefail

: "${ORIGIN_URL:?}"
# Every build must be signed with the same key, or it can't be installed over the previous one.
if [ -z "${KEYSTORE_B64:-}" ]; then
  echo "KEYSTORE_B64 (the Actions secret of the same name) is not set." >&2
  exit 1
fi
BRANCH=${BRANCH:-master}
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
  git checkout -q -f --detach "$SHA"
  git clean -q -ffd
fi

git submodule sync -q --recursive
git submodule update -q --init --recursive --force --jobs 8

mkdir -p ~/.android
base64 -d <<< "$KEYSTORE_B64" > ~/.android/debug.keystore

short_sha=$(git rev-parse --short=10 HEAD)
export THOR_VERSION_SUFFIX="-$short_sha"

ccache -z > /dev/null
(cd Source/Android && ./gradlew --no-daemon --console=plain --init-script /ci/abi.init.gradle \
  assembleDebug)
ccache -s

# Reading all of the output (rather than piping it to head) avoids a SIGPIPE under pipefail.
badging=$("$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  Source/Android/app/build/outputs/apk/debug/app-debug.apk)
badging=${badging%%$'\n'*}
version_name=$(sed -E "s/.*versionName='([^']*)'.*/\1/" <<< "$badging")
version_code=$(sed -E "s/.*versionCode='([^']*)'.*/\1/" <<< "$badging")
apk="dolphin-thor-$version_name.apk"
cp Source/Android/app/build/outputs/apk/debug/app-debug.apk "$OUT/$apk"

info SHA "$(git rev-parse HEAD)"
info APK "$apk"
info VERSION_NAME "$version_name"
info VERSION_CODE "$version_code"
info TAG "$version_name"

base=$(git merge-base HEAD upstream/master)
{
  echo "Based on dolphin-emu/dolphin@\`$(git rev-parse --short=10 "$base")\`" \
       "($(git log -1 --format=%cs "$base"))."
  echo
  echo "Thor commits on top of upstream:"
  echo
  git log --reverse --no-merges --format='- %s' "$base..HEAD"
} > "$OUT/notes.md"
