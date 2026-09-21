#!/bin/bash
set -euo pipefail

package_dir="${1:-/opt/proroot-packages}"

if [ ! -d "$package_dir" ]; then
  printf "Package directory does not exist: %s\n" "$package_dir" >&2
  exit 2
fi

while IFS= read -r -d "" deb; do
  arch="$(dpkg-deb -f "$deb" Architecture)"

  case "$arch" in
    arm64|all)
      ;;
    aarch64)
      work="$(mktemp -d)"
      fixed="${deb}.fixed"

      dpkg-deb -R "$deb" "$work"
      sed -i "s/^Architecture:[[:space:]]*aarch64[[:space:]]*$/Architecture: arm64/" "$work/DEBIAN/control"
      dpkg-deb -b "$work" "$fixed" >/dev/null
      mv -f "$fixed" "$deb"
      rm -rf "$work"
      ;;
    *)
      printf "Unsupported staged Debian package architecture: %s (%s)\n" "$arch" "$deb" >&2
      exit 100
      ;;
  esac

  normalized="$(dpkg-deb -f "$deb" Architecture)"
  case "$normalized" in
    arm64|all)
      ;;
    *)
      printf "Staged package architecture normalization failed: %s (%s)\n" "$normalized" "$deb" >&2
      exit 100
      ;;
  esac
done < <(find "$package_dir" -type f -name "*.deb" -print0)
