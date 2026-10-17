# GitHub Actions release builds

[简体中文](github-actions.md) | **English**

Workflow: [Build release APK](../.github/workflows/release.yml). Every push to `main` automatically increments the version, builds, and publishes a dual-signed APK. Only the unprivileged `com.uonest.app` variant is built; the system variant is unchanged.

## Initial upload

Push the source and workflow to your own GitHub repository's default branch. The local project started as a source copy, so Git must be initialized first. This configuration does not create a repository or upload files automatically.

Keep `.gitmodules` and the Git commit references for both lpac and cJSON so recursive checkout works. Do not upload empty dependency directories. The local lpac profile-owner change is preserved in `tools/ci/patches/lpac-profile-owner.patch`; CI applies it before building and skips it if already applied. Commit the patch too and check compatibility when updating lpac.

`.gitignore` excludes local toolchains, generated files, APKs, and signing keystores. Review staged files before uploading. Never commit passwords, private keys, `keystore.properties`, or `.local-build`.

## Configure six Secrets

In **Settings → Secrets and variables → Actions → New repository secret**, create:

| Secret | Value |
| --- | --- |
| `UONEST_KEYSTORE_BASE64` | Base64-encoded existing uonest keystore |
| `UONEST_STORE_PASSWORD` | uonest keystore password |
| `UONEST_KEY_PASSWORD` | uonest private-key password; repeat the store password if identical |
| `NINEESIM_KEYSTORE_BASE64` | Base64-encoded existing 9eSIM community keystore |
| `NINEESIM_STORE_PASSWORD` | 9eSIM keystore password |
| `NINEESIM_KEY_PASSWORD` | 9eSIM private-key password; repeat the store password if identical |

Aliases are fixed to `uonest` and `key0`, matching the current local signing process. Keep the existing keys instead of generating replacements. Hosted runners cannot access your Mac Keychain; store passwords separately in these Secrets.

On macOS, replace the example paths and copy each encoded file into its corresponding Secret:

```sh
base64 -i /path/to/uonest.keystore | tr -d '\n' | pbcopy
base64 -i /path/to/9eSIMCommunityKey.jks | tr -d '\n' | pbcopy
```

Run and paste each command separately; the second overwrites the clipboard. Base64 is encoding, not encryption. Do not commit the encoded values or send credentials in chat. See [GitHub's Secrets documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

No additional PAT is required: publishing uses the automatic `GITHUB_TOKEN`. Repository/organization policy must allow `contents: write` for publishing and permit the workflow to create `v*` tags and Releases. Secrets are passed only to the signing step, not Gradle. The workflow has no PR trigger; run it only on trusted branches and tags, and restrict writes to workflows, release tags, and the repository.

## Automatic release on main pushes (default)

After the initial upload and Secrets setup, commit changes locally and push:

```sh
git push origin main
```

Each push selects the next version, runs tests, builds, signs with both uonest and 9eSIM certificates, verifies the APK, creates a version tag, and publishes a GitHub Release.

- With no existing version tags, releases start at **v1.0.1**, followed by **v1.0.2, v1.0.3…**.
- Otherwise, increment the highest valid `vX.Y.Z` tag. Upstream tags in other formats are ignored.
- Every release targets the exact triggering commit, even if main advances during the build.
- No version file edits or automatic version commits back to main are needed.
- Tags created using `GITHUB_TOKEN` do not trigger another tag build.
- Releases share a queue: active runs are not canceled, and new pushes do not replace pending runs. GitHub's `queue: max` allows up to 100 pending runs, ordered by when they start waiting; excess runs are canceled. See [GitHub's concurrency documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
- A push containing multiple commits produces one release for its tip commit. GitHub's skip-CI commit-message directives can still skip a run.

**Retries:** failed tests or signing do not publish a Release. Failures before tag creation do not reserve a version; failures afterward retain the tag for retries. Use **Re-run jobs** to resume interrupted draft uploads. A completed release for the same commit is left unchanged. Retrying an older version does not replace a newer Latest release. Do not move or delete automatic version tags manually.

## Manual build

1. Open **Actions → Build release APK → Run workflow**.
2. Select a trusted branch and enter a version such as `1.0.1`, without `v`.
3. Download **Artifacts → uonest-1.0.1** from the completed run.

Manual builds upload artifacts without creating a Release. The archive contains:

- `uonest-1.0.1-dual-signed.apk`
- `uonest-1.0.1-dual-signed.apk.sha256`

Artifacts are retained for 30 days. Use a tagged release for long-term distribution.

## Explicit version tag release (optional)

Main pushes need no manual tags. To specify a version explicitly, tag a commit that has not already been automatically released:

```sh
git tag v1.0.1
git push origin v1.0.1
```

CI tests, builds, signs, and verifies the APK, then creates a GitHub Release with the APK, SHA-256 checksum, and generated release notes. Assets are uploaded to a draft before publication.

Only `vX.Y.Z` is accepted, without prerelease suffixes or leading zeros. Any `v*` tag triggers the workflow, but invalid versions fail before compilation. Do not move published tags or reuse versions. Do not give an already automatically released commit another version tag. A same-name tag pointing to another commit causes failure. Drafts can be resumed; published releases are never overwritten.

## Versions and signing

- Package: `com.uonest.app`.
- `versionName`: the automatically calculated, manually entered, or tagged version `X.Y.Z`.
- `versionCode = X × 1,000,000 + Y × 1,000 + Z`; `1.0.1` becomes `1000001`.
- X is at most 2099; Y and Z are at most 999. `0.0.0` is invalid. Automatic patch rollover follows `1.0.999 → 1.1.0`. Increase manually chosen versions too.
- CI codes exceed the previous local `versionCode=1`. Future local releases must use the same version-code rule to continue updating normally.
- APK Signature Scheme **v2 with two signers**, with v1, v3, and v4 disabled, matching the existing local dual-signing configuration.

Verification requires exactly these SHA-1 certificate fingerprints; failure removes the output:

| Signer | SHA-1 |
| --- | --- |
| uonest | `23BEBA4A34379ED306CFE63EAF4FE8EECA9979C9` |
| 9eSIM | `D1C0F48B370E74D4EA4770ED4C3CD70A3198D31F` |

The script also checks the package name and both version fields before signing, verifies alignment and signatures afterward, and generates a checksum.

## Environment and troubleshooting

CI uses Ubuntu 24.04, Temurin JDK 21, Android SDK 37, Build Tools 37.0.0, NDK 26.1.10909125, and the repository's Gradle Wrapper. Android components must be available from the SDK repository. Third-party Actions are pinned to verified commit SHAs.

- Missing Secrets: check all six names and values.
- Signing failure: check stores, passwords, and aliases. Raw errors that could echo signing inputs are not logged.
- Wrong certificates: use the original two keystores.
- Submodule/patch failure: check committed dependency references, access, and patch compatibility.
- Publishing denied: check repository/organization Actions permissions and tag rules.
- SDK/dependency download failure: inspect installation/Gradle logs. CI does not silently change versions.

Run release-tool tests locally:

```sh
python3 -m unittest discover -s tools/ci/tests -v
```

This configuration does not upload source or keys to GitHub. Create the repository, push the source, and configure Secrets separately. The full hosted publishing result can only be confirmed after the first successful GitHub run.
