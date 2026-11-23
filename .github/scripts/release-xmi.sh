#!/usr/bin/env bash
# Publish XMI/*.XMI (and the matching .sha256 files) as the next
# vMAJOR.MINOR.PATCH GitHub release. The patch number increments from the
# highest existing semver tag.
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  DRY_RUN=1
fi

cd "$(git rev-parse --show-toplevel)"

GITHUB_SHA=${GITHUB_SHA:-$(git rev-parse HEAD)}
if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  GITHUB_REPOSITORY=$(
    git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##' | sed 's#\.git$##'
  )
fi
export GH_REPO="$GITHUB_REPOSITORY"

if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  git fetch origin --tags --force
fi

highest_semver() {
  awk -F. '
    /^v[0-9]+\.[0-9]+\.[0-9]+$/ {
      major = substr($1, 2) + 0
      minor = $2 + 0
      patch = $3 + 0
      printf "%010d%010d%010d\t%s\n", major, minor, patch, $0
    }
  ' | sort | tail -n 1 | cut -f2-
}

verify_checksum() {
  local dir=$1
  local base=$2
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dir" && sha256sum -c "${base}.sha256")
  else
    (cd "$dir" && shasum -a 256 -c "${base}.sha256")
  fi
}

if [[ ! -d XMI ]]; then
  echo "XMI directory is not present. Nothing to release."
  exit 0
fi

xmi_files=()
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  xmi_files+=("$f")
done < <(find XMI -type f -iname '*.xmi' | sort)

if [[ ${#xmi_files[@]} -eq 0 ]]; then
  echo "No XMI files under XMI/. Nothing to release."
  exit 0
fi

assets=()
for f in "${xmi_files[@]}"; do
  sum="${f}.sha256"
  if [[ ! -f "$sum" ]]; then
    echo "::error::Missing checksum for ${f}: expected ${sum}"
    exit 1
  fi
  echo "Verifying ${sum}"
  verify_checksum "$(dirname "$f")" "$(basename "$f")"
  assets+=("$f" "$sum")
done

duplicate_names=$(
  for f in "${assets[@]}"; do
    basename "$f"
  done | sort | uniq -d || true
)
if [[ -n "$duplicate_names" ]]; then
  echo "::error::Release assets would have duplicate file names:"
  printf '%s\n' "$duplicate_names"
  exit 1
fi

existing=$(git tag --points-at "$GITHUB_SHA" | highest_semver || true)
if [[ -n "$existing" ]]; then
  tag="$existing"
else
  latest=$(git tag -l 'v*' | highest_semver || true)
  latest=${latest:-v0.0.0}
  IFS=. read -r major minor patch <<<"${latest#v}"
  patch=$((patch + 1))
  tag="v${major}.${minor}.${patch}"
  while git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; do
    patch=$((patch + 1))
    tag="v${major}.${minor}.${patch}"
  done
fi

notes=$(mktemp)
{
  echo "Automated release of the XMI package."
  echo
  echo "Commit: \`${GITHUB_SHA}\`"
  echo
  echo "## Downloads"
  echo
  for f in "${xmi_files[@]}"; do
    base=$(basename "$f")
    echo "- [${base}](https://github.com/${GITHUB_REPOSITORY}/releases/download/${tag}/${base})"
    echo "- [${base}.sha256](https://github.com/${GITHUB_REPOSITORY}/releases/download/${tag}/${base}.sha256)"
  done
  echo
  echo "Download the assets into one directory, then verify:"
  echo
  echo '```sh'
  for f in "${xmi_files[@]}"; do
    echo "shasum -a 256 -c $(basename "$f").sha256"
  done
  echo '```'
  echo
  echo "Install steps are in [README.MD](https://github.com/${GITHUB_REPOSITORY}/blob/${tag}/README.MD)."
  echo "Packaging notes are in [release/RELEASE_NOTES.md](https://github.com/${GITHUB_REPOSITORY}/blob/${tag}/release/RELEASE_NOTES.md)."
  echo
  echo "## Checksums"
  echo
  echo '```text'
  for f in "${xmi_files[@]}"; do
    printf '%s\n' "$(cat "${f}.sha256")"
  done
  echo '```'
  echo
  echo "## Commit"
  echo
  git log -1 --format='%s' "$GITHUB_SHA"
  body=$(git log -1 --format='%b' "$GITHUB_SHA")
  if [[ -n "$body" ]]; then
    echo
    printf '%s\n' "$body"
  fi

  if [[ "${GITHUB_EVENT_NAME:-}" == "push" && -n "${BEFORE_SHA:-}" && "$BEFORE_SHA" != 0000000000000000000000000000000000000000 ]]; then
    files=$(git diff --name-status "$BEFORE_SHA" "$GITHUB_SHA" -- XMI || true)
    if [[ -n "$files" ]]; then
      echo
      echo "## Files updated in this push"
      echo
      echo '```text'
      printf '%s\n' "$files"
      echo '```'
    fi
  fi
} >"$notes"

echo "Release tag: ${tag}"
echo "Assets:"
printf '  %s\n' "${assets[@]}"

if [[ "${DRY_RUN:-}" == "1" ]]; then
  echo "Dry run; not creating the release."
  echo "----- notes -----"
  cat "$notes"
  exit 0
fi

if gh release view "$tag" >/dev/null 2>&1; then
  echo "Release ${tag} already exists; uploading assets."
  gh release upload "$tag" --clobber "${assets[@]}"
else
  gh release create "$tag" \
    --target "$GITHUB_SHA" \
    --title "ENUM ${tag}" \
    --notes-file "$notes" \
    "${assets[@]}"
fi

gh release view "$tag" --json url --jq .url
