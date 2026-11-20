#!/usr/bin/env bash
# Compiles the app's real selector classes against tiny android.* stand-ins and runs them
# on a plain JVM. No Android SDK, no emulator, no third-party library.
#   JAVAC  compiler command (default: javac)
#   JAVA   runtime command  (default: java)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="$here/../../app/src/main/java/com/vrksignatures/sudhaautobot"
out="${SELFTEST_OUT:-$here/out}"
mains=("${@:-CalendarSelfTest ListScrollSelfTest NextRetailerSelfTest FeedbackSelfTest MoldingSelfTest RosterSelfTest StartPointSelfTest MusicSelfTest SpeedSelfTest TempoSelfTest UpgradeSelfTest}")
rm -rf "$out" && mkdir -p "$out"
sources=("$here"/stubs/android/graphics/*.java "$here"/stubs/android/view/accessibility/*.java
         "$app/AccessibilityTree.java" "$app/RetailerPolicy.java" "$app/CalendarDateSelector.java"
         "$app/ListScroller.java" "$app/RetailerSequence.java" "$app/DateRollbackPolicy.java"
         "$app/AppNotices.java" "$app/FeedbackCues.java" "$app/ErrorPlaybook.java"
         "$app/LearnedUi.java" "$app/UiVocabulary.java" "$app/UiMolder.java" "$app/ScreenLabels.java"
         "$app/AccountId.java" "$app/Roster.java" "$app/AakritiChat.java"
         "$app/StartPoint.java" "$app/MusicMood.java" "$app/UpgradeRules.java"
         "$app/ScrollMemory.java" "$app/CopyOutcome.java" "$app/SkipLine.java" "$app/Tempo.java" "$app/Glass.java"
         "$here"/src/*.java)
[ -f "$app/CalendarGridFinder.java" ] && sources+=("$app/CalendarGridFinder.java")
${JAVAC:-javac} -nowarn -d "$out" "${sources[@]}"
cd "$here"
# With no arguments every self-test runs; any failure stops the script.
for main in ${mains[@]}; do
  ${JAVA:-java} -cp "$out" "$main"
done
