#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root/libs/lpac-jni/src/main/jni/lpac"
patch="$root/tools/ci/patches/lpac-profile-owner.patch"
if git apply --reverse --check "$patch" 2>/dev/null; then
    echo 'lpac profile-owner patch already applied.'
else
    git apply --check "$patch"
    git apply "$patch"
    echo 'Applied lpac profile-owner patch.'
fi
