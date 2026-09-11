#!/bin/bash
set -euo pipefail
# The vendor initializer enables xtrace around a command containing the password.
# Keep initialization behavior while preventing credentials from entering logs.
sed '/^[[:space:]]*set -x[[:space:]]*$/d' /usr/bin/entrypoint.sh > /tmp/mgs-tdengine-entrypoint.sh
exec bash /tmp/mgs-tdengine-entrypoint.sh "$@"
