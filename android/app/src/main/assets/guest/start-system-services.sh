#!/bin/bash
set -euo pipefail

mkdir -p /run/dbus /run/lock
rm -f /run/dbus/system_bus_socket /run/dbus/pid

# No systemd is required. Keep one real D-Bus system bus alive inside the
# fake-root service runtime; D-Bus activation can start PackageKit, polkit and
# other conventional services in the same compatibility context.
exec dbus-daemon --system --nofork --nopidfile
