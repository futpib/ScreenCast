#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# != 3 || ! "$1" =~ ^[0-9][0-9A-Za-z.+-]*$ || ! "$2" =~ ^[1-9][0-9]{0,8}$ ]]; then
    echo 'Usage: build-release.sh VERSION_NAME VERSION_CODE OUTPUT_DIRECTORY' >&2
    exit 2
fi

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK}"
apksigner="$sdk/build-tools/36.0.0/apksigner"
if [[ ! -x "$apksigner" ]]; then
    echo 'Android SDK Build Tools 36.0.0 (apksigner) are required' >&2
    exit 2
fi

apk=app/build/outputs/apk/release/app-release.apk
rm -f "$apk"
./gradlew --no-daemon :app:assembleRelease \
    -PreleaseVersionName="$1" -PreleaseVersionCode="$2"

if [[ ! -f "$apk" ]]; then
    echo 'Signed release APK was not produced; configure release signing' >&2
    exit 1
fi
"$apksigner" verify "$apk"
package_line=$("$sdk/build-tools/36.0.0/aapt" dump badging "$apk" | sed -n '1p')
if [[ "$package_line" != *"name='io.github.ddagunts.screencast'"* ||
      "$package_line" != *"versionCode='$2'"* ||
      "$package_line" != *"versionName='$1'"* ]]; then
    echo 'Release APK package or version does not match the requested build' >&2
    exit 1
fi

mkdir -p "$3"
output=$(realpath "$3")
install -m644 "$apk" "$output/app-release.apk"
(
    cd "$output"
    sha256sum app-release.apk > SHA256SUMS
)
