#!/usr/bin/env bash
#
# run.sh — compile and run the INVENTORY.md probe programs against the real,
# source-less Cybele jars. See README.md in this directory for what each probe
# establishes and for the caveats that make some results regime-dependent.
#
# Usage:
#   docs/probes/run.sh                 # compile everything, run nothing
#   docs/probes/run.sh ExpA            # compile, then run one probe
#   docs/probes/run.sh ExpA2 --control # run ExpA2 with the event-queue control config
#   docs/probes/run.sh Order           # the util-only, no-Cybele iteration-order probe
#   docs/probes/run.sh OrderLock       # self-verdicting: asserts those orders (#19)
#   docs/probes/run.sh --all           # run every probe in turn
#
# Exit codes: 0 success, 1 failure (always with an actionable message).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${PROBE_WORK_DIR:-$HERE/.work}"

M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
API="$M2/com/iai/cybele-api/1.0/cybele-api-1.0.jar"
IMPL="$M2/com/iai/cybele-impl/1.0/cybele-impl-1.0.jar"

# --- prerequisite: the vendor jars -------------------------------------------
# They are 2008-era IAI binaries, untracked in git (.gitignore: cybelle/*.jar).
# A fresh clone must recover them before anything here will run.
if [ ! -f "$API" ] || [ ! -f "$IMPL" ]; then
    cat >&2 <<'MSG'
ERROR: the Cybele vendor jars are not installed in the local Maven repository.

Expected:
  ~/.m2/repository/com/iai/cybele-api/1.0/cybele-api-1.0.jar
  ~/.m2/repository/com/iai/cybele-impl/1.0/cybele-impl-1.0.jar

They are untracked in git. Recover them with either:

  # preferred, on the opencybele-baseline branch (landed in #14):
  scripts/bootstrap-vendor-jars.sh

  # or by hand, from the withoutGradle tag (see README.md "One-time setup"):
  git checkout withoutGradle -- cybelle/Cybele.jar cybelle/CybeleImpl.jar
  mvn install:install-file -Dfile=cybelle/Cybele.jar \
      -DgroupId=com.iai -DartifactId=cybele-api  -Dversion=1.0 -Dpackaging=jar
  mvn install:install-file -Dfile=cybelle/CybeleImpl.jar \
      -DgroupId=com.iai -DartifactId=cybele-impl -Dversion=1.0 -Dpackaging=jar
MSG
    exit 1
fi

CP="$WORK/classes:$API:$IMPL"

# --- kernel config for --patch-module ----------------------------------------
# Cybele's kernel loads /cybele.prop through Properties.class.getResourceAsStream,
# a java.base class, so since JPMS it only resolves via --patch-module against a
# PLAIN DIRECTORY on disk. Hence the copy: docs/probes/ has no cybelle/ of its own.
mkdir -p "$WORK/cybelle" "$WORK/classes"
cp -f "$REPO/cybelle/cybele.prop" "$REPO/cybelle/ICS.prop" "$WORK/cybelle/"

# The SEM-01 positive control. This line is NEITHER of the two commented-out
# lines in cybele.prop: line 66 puts merge_sort on the SYSTEM queue only, and the
# control needs it on the AGENT queue, which is what dispatches application
# messages. Recorded verbatim so the control stays reproducible.
CONTROL_LINE='cybele.srv.evmgmt.app.param.iai = system_queue merge_sort staticpriority_comp;agent_queue merge_sort staticpriority_comp'
mkdir -p "$WORK/ctrl"
cp -f "$REPO/cybelle/ICS.prop" "$WORK/ctrl/"
{ cat "$REPO/cybelle/cybele.prop"; printf '\n%s\n' "$CONTROL_LINE"; } > "$WORK/ctrl/cybele.prop"

# --- compile ------------------------------------------------------------------
javac -nowarn -cp "$API" -d "$WORK/classes" "$HERE"/Exp*.java
# Order/OrderLock need the app's util classes; OrderLock additionally needs
# ScenarioConfig (the real topology source since #18). Neither pulls in Cybele.
javac -nowarn -d "$WORK/classes" \
      "$REPO"/src/main/java/cz/vutbr/fit/ags/xhovor07/util/*.java \
      "$REPO"/src/main/java/cz/vutbr/fit/ags/xhovor07/ScenarioConfig.java \
      "$HERE"/Order.java "$HERE"/OrderLock.java
echo "compiled -> $WORK/classes"

run_probe() {
    local probe="$1" conf="$2"
    echo "=== $probe (patch-module: $conf) ==="
    if [ "$probe" = "Order" ] || [ "$probe" = "OrderLock" ]; then
        java -ea -cp "$WORK/classes" "$probe"   # no Cybele, no kernel config
    else
        ( cd "$WORK" && java --patch-module "java.base=$conf" -cp "$CP" "$probe" )
    fi
}

case "${1:-}" in
    "")        exit 0 ;;
    --all)     for p in ExpA ExpA2 ExpB ExpB2 ExpB3 ExpB4 ExpC ExpE ExpF Order OrderLock; do
                   run_probe "$p" cybelle || echo "  (probe $p exited non-zero)"
               done ;;
    *)         if [ "${2:-}" = "--control" ]; then run_probe "$1" ctrl; else run_probe "$1" cybelle; fi ;;
esac
