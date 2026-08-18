#!/usr/bin/env bash
#
# bootstrap-vendor-jars.sh — recover the vendored Cybele jars and install them
# into the local Maven repository so Gradle's `mavenLocal()` can resolve them.
#
# The two jars (cybelle/Cybele.jar, cybelle/CybeleImpl.jar) are 2008-era IAI
# binaries with no public Maven repo. They are NOT tracked on this branch
# (.gitignore: cybelle/*.jar) but remain reachable through the `withoutGradle`
# git tag, from which this script restores them when they are missing.
#
# The script is idempotent: re-running it is a no-op once both artifacts are
# present in the local repository with matching content. Use --force to
# reinstall regardless.
#
# It does NOT require `mvn` on PATH. If Maven is available it is used; if not,
# the jars are installed by copying them into the local repository layout and
# generating the minimal POMs Gradle needs. Both paths produce an identical
# on-disk result.
#
# Usage:
#   scripts/bootstrap-vendor-jars.sh [--force] [--verify-only]
#
# Environment:
#   MAVEN_REPO_LOCAL   override the local repository path
#                      (default: <localRepository> from ~/.m2/settings.xml,
#                       else ~/.m2/repository)
#
# Exit codes: 0 success, 1 failure (always with an actionable message).

set -euo pipefail

GROUP_ID="com.iai"
VERSION="1.0"
VENDOR_TAG="withoutGradle"

# artifactId:relative jar path
ARTIFACTS=(
    "cybele-api:cybelle/Cybele.jar"
    "cybele-impl:cybelle/CybeleImpl.jar"
)

FORCE=0
VERIFY_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --force)       FORCE=1 ;;
        --verify-only) VERIFY_ONLY=1 ;;
        -h|--help)
            sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "bootstrap-vendor-jars: unknown option '$arg'" >&2
            echo "Usage: $0 [--force] [--verify-only]" >&2
            exit 1 ;;
    esac
done

die() { echo "" >&2; echo "ERROR: $*" >&2; echo "" >&2; exit 1; }
log() { echo "[bootstrap-vendor-jars] $*"; }

# ---------------------------------------------------------------------------
# Locate the project root (works from any cwd, inside or outside a git repo).
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

[ -f settings.gradle.kts ] || die \
"Could not find settings.gradle.kts in '$PROJECT_ROOT'.
This script must stay in <project-root>/scripts/ so it can locate the project."

# ---------------------------------------------------------------------------
# Resolve the local Maven repository path.
# ---------------------------------------------------------------------------
resolve_repo_local() {
    if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then
        echo "$MAVEN_REPO_LOCAL"
        return
    fi
    local settings="${HOME}/.m2/settings.xml"
    if [ -f "$settings" ]; then
        local configured
        configured="$(sed -n 's:.*<localRepository>\(.*\)</localRepository>.*:\1:p' "$settings" | head -n 1 | tr -d '[:space:]')"
        # Ignore the commented-out default placeholder and property refs we
        # cannot expand here.
        if [ -n "$configured" ] && [[ "$configured" != *'${'* ]]; then
            echo "${configured/#\~/$HOME}"
            return
        fi
    fi
    echo "${HOME}/.m2/repository"
}

REPO_LOCAL="$(resolve_repo_local)"

# ---------------------------------------------------------------------------
# Step 1 — make sure the jars are present in cybelle/.
# ---------------------------------------------------------------------------
missing_jars=()
for entry in "${ARTIFACTS[@]}"; do
    jar="${entry#*:}"
    [ -f "$jar" ] || missing_jars+=("$jar")
done

if [ ${#missing_jars[@]} -gt 0 ]; then
    log "missing from the working tree: ${missing_jars[*]}"

    if ! command -v git >/dev/null 2>&1 || ! git rev-parse --git-dir >/dev/null 2>&1; then
        die \
"The vendored Cybele jars are missing and cannot be recovered here:
    ${missing_jars[*]}

They are not tracked on this branch (.gitignore: cybelle/*.jar) and this
directory is not a git working tree (or git is unavailable), so the
'$VENDOR_TAG' tag cannot be read.

If you are building a Docker image: the jars must already exist in the build
context. Run this script on the host first, then rebuild:
    scripts/bootstrap-vendor-jars.sh && docker compose build app"
    fi

    if ! git rev-parse --verify --quiet "refs/tags/$VENDOR_TAG" >/dev/null; then
        log "tag '$VENDOR_TAG' not found locally, trying 'git fetch --tags'..."
        git fetch --tags --quiet || true
    fi
    git rev-parse --verify --quiet "refs/tags/$VENDOR_TAG" >/dev/null || die \
"The git tag '$VENDOR_TAG' does not exist in this clone, so the vendored
Cybele jars cannot be recovered:
    ${missing_jars[*]}

Fetch it from the remote that has it:
    git fetch --tags origin
then re-run: scripts/bootstrap-vendor-jars.sh"

    log "restoring ${missing_jars[*]} from tag '$VENDOR_TAG'"
    for jar in "${missing_jars[@]}"; do
        git checkout "$VENDOR_TAG" -- "$jar" || die \
"'git checkout $VENDOR_TAG -- $jar' failed. The tag exists but does not
contain that path, or the working tree is in a conflicted state."
        # Leave the index clean: the jars are deliberately untracked here.
        git reset --quiet -- "$jar" 2>/dev/null || true
    done
fi

for entry in "${ARTIFACTS[@]}"; do
    jar="${entry#*:}"
    [ -s "$jar" ] || die "'$jar' is missing or empty after recovery — cannot continue."
done

# ---------------------------------------------------------------------------
# Step 2 — install into the local Maven repository.
# ---------------------------------------------------------------------------
same_file() {
    # $1 source, $2 destination — true when both exist with identical content.
    [ -f "$2" ] || return 1
    cmp -s "$1" "$2"
}

write_pom() {
    local artifact_id="$1" pom_path="$2"
    cat > "$pom_path" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_ID}</groupId>
  <artifactId>${artifact_id}</artifactId>
  <version>${VERSION}</version>
  <packaging>jar</packaging>
  <description>Vendored IAI Cybele kernel jar, installed by scripts/bootstrap-vendor-jars.sh</description>
</project>
POM
}

find_maven() {
    if command -v mvn >/dev/null 2>&1; then
        command -v mvn
        return 0
    fi
    for candidate in \
        "${MAVEN_HOME:-}/bin/mvn" \
        "${M2_HOME:-}/bin/mvn" \
        "/usr/share/maven/bin/mvn" \
        "/opt/maven/bin/mvn"
    do
        [ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return 0; }
    done
    return 1
}

MVN="$(find_maven || true)"

install_with_maven() {
    local artifact_id="$1" jar="$2"
    "$MVN" -q -B install:install-file \
        -Dfile="$jar" \
        -DgroupId="$GROUP_ID" \
        -DartifactId="$artifact_id" \
        -Dversion="$VERSION" \
        -Dpackaging=jar \
        -Dmaven.repo.local="$REPO_LOCAL"
}

install_manually() {
    local artifact_id="$1" jar="$2"
    local dest_dir="$REPO_LOCAL/${GROUP_ID//./\/}/$artifact_id/$VERSION"
    mkdir -p "$dest_dir"
    cp -f "$jar" "$dest_dir/$artifact_id-$VERSION.jar"
    write_pom "$artifact_id" "$dest_dir/$artifact_id-$VERSION.pom"
}

installed=0
skipped=0
for entry in "${ARTIFACTS[@]}"; do
    artifact_id="${entry%%:*}"
    jar="${entry#*:}"
    dest_dir="$REPO_LOCAL/${GROUP_ID//./\/}/$artifact_id/$VERSION"
    dest_jar="$dest_dir/$artifact_id-$VERSION.jar"
    dest_pom="$dest_dir/$artifact_id-$VERSION.pom"

    if [ "$FORCE" -eq 0 ] && same_file "$jar" "$dest_jar" && [ -f "$dest_pom" ]; then
        log "up to date: $GROUP_ID:$artifact_id:$VERSION"
        skipped=$((skipped + 1))
        continue
    fi

    if [ "$VERIFY_ONLY" -eq 1 ]; then
        die \
"--verify-only: $GROUP_ID:$artifact_id:$VERSION is not installed (or differs)
in '$REPO_LOCAL'. Run scripts/bootstrap-vendor-jars.sh to install it."
    fi

    if [ -n "$MVN" ]; then
        log "installing $GROUP_ID:$artifact_id:$VERSION from $jar (via $MVN)"
        install_with_maven "$artifact_id" "$jar" || die \
"'mvn install:install-file' failed for $jar.
Re-run without Maven on PATH to use the built-in copy-based installer, or
inspect the Maven output above."
    else
        log "installing $GROUP_ID:$artifact_id:$VERSION from $jar (no mvn on PATH, copying into $REPO_LOCAL)"
        install_manually "$artifact_id" "$jar" || die \
"Failed to copy $jar into '$dest_dir'. Check the path is writable."
    fi
    installed=$((installed + 1))
done

# ---------------------------------------------------------------------------
# Step 3 — verify the end state, whichever installer ran.
# ---------------------------------------------------------------------------
for entry in "${ARTIFACTS[@]}"; do
    artifact_id="${entry%%:*}"
    dest_dir="$REPO_LOCAL/${GROUP_ID//./\/}/$artifact_id/$VERSION"
    for required in "$dest_dir/$artifact_id-$VERSION.jar" "$dest_dir/$artifact_id-$VERSION.pom"; do
        [ -s "$required" ] || die \
"Post-install verification failed: '$required' is missing or empty.
The local repository at '$REPO_LOCAL' is not in a state Gradle can resolve
'$GROUP_ID:$artifact_id:$VERSION' from."
    done
done

log "OK — $GROUP_ID:cybele-api:$VERSION and $GROUP_ID:cybele-impl:$VERSION available in $REPO_LOCAL ($installed installed, $skipped already current)"
