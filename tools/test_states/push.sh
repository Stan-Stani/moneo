#!/bin/zsh
# Push saved emulator states into the installed app's save-state slots.
#
#   tools/test_states/push.sh [set] [serial]      # set defaults to kr2024
#
# Copies <set>/slot_N.{bin,meta} into com.poketrek's files/savestates, then
# load them from Settings -> Save states. The app must be installed and have
# been launched once. Overwrites those slots on the device.
set -e
here=${0:A:h}
set_dir=$here/${1:-kr2024}
ADB=(~/Library/Android/sdk/platform-tools/adb ${2:+-s $2})
$ADB shell run-as com.poketrek mkdir -p files/savestates
for f in $set_dir/slot_*.(bin|meta)(N); do
  n=${f:t}
  $ADB push $f /data/local/tmp/$n >/dev/null
  $ADB shell "run-as com.poketrek cp /data/local/tmp/$n files/savestates/$n && rm /data/local/tmp/$n"
  echo "pushed $n"
done
echo "Force-stop and relaunch the app so the slot list refreshes."
