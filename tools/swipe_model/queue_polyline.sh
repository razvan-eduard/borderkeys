#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
# Step 3 of stream 8: every own-script layout, three seeds, polyline swipes.
cd "$(dirname "$0")"
for pair in russian:ru_RU ukrainian:uk_UA bulgarian:bg_BG serbian:sr_RS macedonian:mk_MK greek:el_GR armenian:hy_AM georgian:ka_GE hebrew:he_IL arabic:ar; do
  echo "=== ${pair%%:*} $(date '+%F %T')"
  ./train_script.sh "${pair%%:*}" "${pair##*:}" polyline "1 2 3" 2>&1 | command grep -E "^epoch [0-9]+/80|wrote|Error|error|Traceback"
done
echo "=== done $(date '+%F %T')"
