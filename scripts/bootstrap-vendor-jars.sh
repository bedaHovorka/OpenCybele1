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
#   scripts/bootstrap-vendor-jars.sh [--force | --verify-only]
#
#   --force        reinstall even if the artifacts are already current
#   --verify-only  check that both artifacts are installed and exit; writes
#                  nothing and does not touch the working tree
#                  (mutually exclusive with --force)
#
# Environment:
#   MAVEN_REPO_LOCAL   override the local repository path this script writes to.
#                      NOTE: Gradle's mavenLocal() does NOT read this variable —
#                      it reads the `maven.repo.local` system property, then
#                      <localRepository> in ~/.m2/settings.xml, then the
#                      ~/.m2/repository default. So if you set this to a
#                      non-default path you must also pass a matching
#                      -Dmaven.repo.local to Gradle, or it will not find the
#                      artifacts this script just installed.
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

die() { echo "" >&2; echo "ERROR: $*" >&2; echo "" >&2; exit 1; }
log() { echo "[bootstrap-vendor-jars] $*"; }

FORCE=0
VERIFY_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --force)       FORCE=1 ;;
        --verify-only) VERIFY_ONLY=1 ;;
        -h|--help)
            # Print the leading comment block, however long it is.
            awk 'NR==1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
            exit 0 ;;
        *)
            echo "bootstrap-vendor-jars: unknown option '$arg'" >&2
            echo "Usage: $0 [--force | --verify-only]" >&2
            exit 1 ;;
    esac
done

if [ "$FORCE" -eq 1 ] && [ "$VERIFY_ONLY" -eq 1 ]; then
    die \
"--force and --verify-only are mutually exclusive.
--force means 'reinstall unconditionally'; --verify-only means 'write nothing'.
Combined they would report a false failure on a correctly installed repository."
fi

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
#
# Parsing settings.xml needs XML-comment awareness: Maven's own shipped
# settings.xml carries a commented-out example
#     <!-- localRepository ...
#     <localRepository>/path/to/local/repo</localRepository>
#     -->
# and a plain line-oriented grep happily returns "/path/to/local/repo" from it.
# That is the dangerous case, not a cosmetic one: it only bites on machines
# that HAVE Maven, where the install then succeeds into the wrong directory
# and Gradle later fails to resolve the artifacts with an error that blames
# Gradle. The awk pass below drops commented regions before matching.
# ---------------------------------------------------------------------------
resolve_repo_local() {
    if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then
        echo "$MAVEN_REPO_LOCAL"
        return
    fi
    local settings="${HOME}/.m2/settings.xml"
    if [ -f "$settings" ]; then
        local configured
        # Strip <!-- ... --> regions, then match, then trim leading/trailing
        # whitespace only (never internal — paths may legitimately contain
        # spaces).
        configured="$(
            awk '/<!--/ { c = 1 } !c { print } /-->/ { c = 0 }' "$settings" \
            | sed -n 's:.*<localRepository>\(.*\)</localRepository>.*:\1:p' \
            | head -n 1 \
            | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
        )"
        # Skip unexpanded property references such as ${user.home}.
        if [ -n "$configured" ] && [ "${configured#*'${'}" = "$configured" ]; then
            echo "${configured/#\~/$HOME}"
            return
        fi
    fi
    echo "${HOME}/.m2/repository"
}

REPO_LOCAL="$(resolve_repo_local)"
log "local Maven repository: $REPO_LOCAL"

artifact_dir() { echo "$REPO_LOCAL/${GROUP_ID//./\/}/$1/$VERSION"; }

# ---------------------------------------------------------------------------
# --verify-only — read-only check, before anything can touch the working tree.
# ---------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 1 ]; then
    for entry in "${ARTIFACTS[@]}"; do
        artifact_id="${entry%%:*}"
        jar="${entry#*:}"
        dir="$(artifact_dir "$artifact_id")"
        for required in "$dir/$artifact_id-$VERSION.jar" "$dir/$artifact_id-$VERSION.pom"; do
            [ -s "$required" ] || die \
"--verify-only: $GROUP_ID:$artifact_id:$VERSION is not installed in
'$REPO_LOCAL' (missing or empty: $required).
Run scripts/bootstrap-vendor-jars.sh to install it."
        done
        # Only comparable when the source jar happens to be present; absence of
        # cybelle/*.jar is not itself a verification failure.
        if [ -f "$jar" ] && ! cmp -s "$jar" "$dir/$artifact_id-$VERSION.jar"; then
            die \
"--verify-only: installed $GROUP_ID:$artifact_id:$VERSION differs from '$jar'.
Run scripts/bootstrap-vendor-jars.sh --force to reinstall it."
        fi
    done
    log "OK — both artifacts present in $REPO_LOCAL (nothing written)"
    exit 0
fi

# ---------------------------------------------------------------------------
# Step 1 — make sure the jars are present in cybelle/.
#
# A space-separated string rather than an array: `${#arr[@]}` on an EMPTY array
# is an unbound-variable error under `set -u` on bash < 4.4, which is still
# /bin/bash on macOS — a platform this project documents (XQuartz).
# ---------------------------------------------------------------------------
missing_jars=""
for entry in "${ARTIFACTS[@]}"; do
    jar="${entry#*:}"
    [ -f "$jar" ] || missing_jars="${missing_jars:+$missing_jars }$jar"
done

if [ -n "$missing_jars" ]; then
    log "missing from the working tree: $missing_jars"

    if ! command -v git >/dev/null 2>&1 || ! git rev-parse --git-dir >/dev/null 2>&1; then
        die \
"The vendored Cybele jars are missing and cannot be recovered here:
    $missing_jars

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
    $missing_jars

Fetch it from the remote that has it:
    git fetch --tags origin
then re-run: scripts/bootstrap-vendor-jars.sh"

    log "restoring $missing_jars from tag '$VENDOR_TAG'"
    for jar in $missing_jars; do
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
    local artifact_id="$1" jar="$2" dest_dir="$3"
    mkdir -p "$dest_dir"
    cp -f "$jar" "$dest_dir/$artifact_id-$VERSION.jar"
    write_pom "$artifact_id" "$dest_dir/$artifact_id-$VERSION.pom"
}

installed=0
skipped=0
for entry in "${ARTIFACTS[@]}"; do
    artifact_id="${entry%%:*}"
    jar="${entry#*:}"
    dest_dir="$(artifact_dir "$artifact_id")"
    dest_jar="$dest_dir/$artifact_id-$VERSION.jar"
    dest_pom="$dest_dir/$artifact_id-$VERSION.pom"

    if [ "$FORCE" -eq 0 ] && [ -f "$dest_jar" ] && cmp -s "$jar" "$dest_jar" && [ -f "$dest_pom" ]; then
        log "up to date: $GROUP_ID:$artifact_id:$VERSION"
        skipped=$((skipped + 1))
        continue
    fi

    if [ -n "$MVN" ]; then
        log "installing $GROUP_ID:$artifact_id:$VERSION from $jar into $REPO_LOCAL (via $MVN)"
        install_with_maven "$artifact_id" "$jar" || die \
"'mvn install:install-file' failed for $jar.
Re-run without Maven on PATH to use the built-in copy-based installer, or
inspect the Maven output above."
    else
        log "installing $GROUP_ID:$artifact_id:$VERSION from $jar into $REPO_LOCAL (no mvn on PATH, copying)"
        install_manually "$artifact_id" "$jar" "$dest_dir" || die \
"Failed to copy $jar into '$dest_dir'. Check the path is writable."
    fi
    installed=$((installed + 1))
done

# ---------------------------------------------------------------------------
# Step 3 — verify the end state, whichever installer ran.
# ---------------------------------------------------------------------------
for entry in "${ARTIFACTS[@]}"; do
    artifact_id="${entry%%:*}"
    dest_dir="$(artifact_dir "$artifact_id")"
    for required in "$dest_dir/$artifact_id-$VERSION.jar" "$dest_dir/$artifact_id-$VERSION.pom"; do
        [ -s "$required" ] || die \
"Post-install verification failed: '$required' is missing or empty.
The local repository at '$REPO_LOCAL' is not in a state Gradle can resolve
'$GROUP_ID:$artifact_id:$VERSION' from."
    done
done

log "OK — $GROUP_ID:cybele-api:$VERSION and $GROUP_ID:cybele-impl:$VERSION available in $REPO_LOCAL ($installed installed, $skipped already current)"
