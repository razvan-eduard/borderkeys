#!/bin/bash
set -e

# Generates the F-Droid metadata tree `fdroid update` expects for both BorderKeys flavors:
# metadata/<applicationId>/en-US/{icon.png, summary.txt, description.txt, phoneScreenshots/*,
# changelogs/<versionCode>.txt} -- sourced from this repo's own fastlane/ store listing plus a
# git-log-generated changelog.
#
# Modeled on VoxApps' scripts/sync_fdroid_metadata.sh, which pushes into the same custom repo
# (vox-fdroid-repo) -- kept as close to that script's shape as this repo's own single-app,
# two-flavor layout allows, rather than inventing a second convention for the same target repo.
# The one real difference: VoxApps is a monorepo of unrelated apps and scopes its git-log range
# per app directory; BorderKeys ships both flavors from the same tag with one shared
# versionCode, so one changelog is written under both application IDs, not two independently
# generated ones.

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

# Same filter release.yml's own CHANGELOG.md generation uses, kept identical on purpose so the
# F-Droid "what's new" text and the GitHub release notes never tell two different stories.
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

# F-Droid's whatsNew field has a hard 500-character limit; fdroid update silently truncates
# anything longer mid-character, which can cut a bullet off mid-word. Trim to whole bullet
# lines that fit instead, so a long changelog degrades to "here are the first few things"
# rather than a garbled half-word fragment.
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
    FASTLANE_DIR="fastlane/$APP_ID/metadata/android/en-US"
    TARGET_DIR="$METADATA_DIR/$APP_ID/en-US"
    mkdir -p "$TARGET_DIR"

    if [ -f "$FASTLANE_DIR/images/icon.png" ]; then
        cp "$FASTLANE_DIR/images/icon.png" "$TARGET_DIR/icon.png"
        echo "  Icon synced."
    fi
    if [ -f "$FASTLANE_DIR/short_description.txt" ]; then
        cp "$FASTLANE_DIR/short_description.txt" "$TARGET_DIR/summary.txt"
        echo "  Summary synced."
    fi
    if [ -f "$FASTLANE_DIR/full_description.txt" ]; then
        cp "$FASTLANE_DIR/full_description.txt" "$TARGET_DIR/description.txt"
        echo "  Description synced."
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
