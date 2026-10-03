#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

set -e

# Generates the F-Droid metadata tree `fdroid update` expects for both BorderKeys flavors:
# metadata/<applicationId>/<locale>/{summary.txt, description.txt} for every listed locale, plus
# en-US/{icon.png, phoneScreenshots/*, changelogs/<versionCode>.txt} -- sourced from this repo's
# own fastlane/ store listing plus a git-log-generated changelog. Both flavors share one versionCode, so one changelog is written
# under both application IDs.

APPS=(com.borderkeys com.borderkeys.plus)

METADATA_DIR="metadata_fdroid_sync"
rm -rf "$METADATA_DIR"
mkdir -p "$METADATA_DIR"

VERSION_CODE=$(grep -m1 'versionCode = ' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
if [ -z "$VERSION_CODE" ]; then
    echo "Could not find versionCode in app/build.gradle.kts" >&2
    exit 1
fi

LATEST_TAG=$(git tag -l 'v*' --sort=-v:refname | sed -n '1p')
PREV_TAG=$(git tag -l 'v*' --sort=-v:refname | sed -n '2p')

# The same filter release.yml's release notes use.
KEEP_TYPES='^- (feat|fix|perf)(\(|!?:)'
if [ -n "$LATEST_TAG" ] && [ -n "$PREV_TAG" ]; then
    echo "Generating changelog from $PREV_TAG to $LATEST_TAG..."
    CHANGELOG=$(git log "${PREV_TAG}..${LATEST_TAG}" --pretty=format:"- %s" --no-merges \
        | grep -iE "$KEEP_TYPES" || true)
elif [ -n "$LATEST_TAG" ]; then
    echo "No previous tag found. Using the last commit on $LATEST_TAG..."
    CHANGELOG=$(git log "$LATEST_TAG" -1 --pretty=format:"- %s" --no-merges \
        | grep -iE "$KEEP_TYPES" || true)
else
    echo "No tags found, skipping changelog content."
    CHANGELOG=""
fi
if [ -z "$CHANGELOG" ]; then
    CHANGELOG="Maintenance update and performance improvements."
fi

# F-Droid's whatsNew field holds 500 characters: trimmed to the whole bullet lines that fit.
if [ ${#CHANGELOG} -gt 500 ]; then
    TRIMMED=""
    while IFS= read -r line; do
        CANDIDATE="${TRIMMED:+$TRIMMED$'\n'}$line"
        if [ ${#CANDIDATE} -gt 500 ]; then
            break
        fi
        TRIMMED="$CANDIDATE"
    done <<< "$CHANGELOG"
    CHANGELOG="$TRIMMED"
fi

for APP_ID in "${APPS[@]}"; do
    echo "Processing $APP_ID..."
    # Each locale's summary and description; the icon, screenshots and changelog are en-US only.
    for LOCALE_DIR in "fastlane/$APP_ID/metadata/android"/*/; do
        LOCALE=$(basename "$LOCALE_DIR")
        LOCALE_TARGET="$METADATA_DIR/$APP_ID/$LOCALE"
        mkdir -p "$LOCALE_TARGET"
        if [ -f "$LOCALE_DIR/short_description.txt" ]; then
            cp "$LOCALE_DIR/short_description.txt" "$LOCALE_TARGET/summary.txt"
        fi
        if [ -f "$LOCALE_DIR/full_description.txt" ]; then
            cp "$LOCALE_DIR/full_description.txt" "$LOCALE_TARGET/description.txt"
        fi
        echo "  $LOCALE summary and description synced."
    done

    FASTLANE_DIR="fastlane/$APP_ID/metadata/android/en-US"
    TARGET_DIR="$METADATA_DIR/$APP_ID/en-US"

    if [ -f "$FASTLANE_DIR/images/icon.png" ]; then
        cp "$FASTLANE_DIR/images/icon.png" "$TARGET_DIR/icon.png"
        echo "  Icon synced."
    fi
    if [ -d "$FASTLANE_DIR/images/phoneScreenshots" ]; then
        mkdir -p "$TARGET_DIR/phoneScreenshots" "$TARGET_DIR/images/phoneScreenshots"
        for ext in png jpg jpeg; do
            cp "$FASTLANE_DIR/images/phoneScreenshots"/*."$ext" "$TARGET_DIR/phoneScreenshots/" 2>/dev/null || true
            cp "$FASTLANE_DIR/images/phoneScreenshots"/*."$ext" "$TARGET_DIR/images/phoneScreenshots/" 2>/dev/null || true
        done
        echo "  Screenshots synced."
    fi

    mkdir -p "$TARGET_DIR/changelogs"
    echo "$CHANGELOG" > "$TARGET_DIR/changelogs/${VERSION_CODE}.txt"
    echo "  Changelog written to $TARGET_DIR/changelogs/${VERSION_CODE}.txt"
done

echo "F-Droid metadata sync complete."
