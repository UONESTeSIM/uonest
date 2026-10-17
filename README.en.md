<p align="center">
  <img src="docs/assets/uonest-logo.svg" alt="uonest" width="280">
</p>

# uonest

<p align="center">
  <a href="README.md">简体中文</a> | <strong>English</strong>
</p>

uonest is an Android eSIM management app for compatible removable eSIM cards and USB readers. It supports downloading, enabling, disabling, deleting, and inspecting profiles.

Website: [uonest.com](https://uonest.com)

## Project origin

uonest is derived from [OpenEUICC / EasyEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC), with changes to the interface, download flow, and everyday management experience. Thanks to PeterCxy and all upstream contributors.

This is an independently maintained derivative, with no official affiliation with the upstream project. Upstream copyright and license notices are retained below. Changes to uonest's interface and workflows do not describe the behavior of upstream releases.

## Features

- **Add eSIMs** by scanning a QR code, recognizing a code from an image, or entering an activation code manually.
- **Choose a download target**: the home screen uses the currently available slot; the launcher download shortcut and external activation links allow target selection on the add page.
- **Follow download progress** with actual stage descriptions, confirmation-code entry, success and failure results, and failure diagnostics.
- **Manage profiles**: inspect, rename, enable, disable, and delete profiles.
- **Display operator logos and flags** using bundled matching data and images. Results depend on readable profile information and matching rules.
- **Use USB readers**: detect compatible readers and manage the inserted eSIM after system permission and successful communication.
- **Manage notifications**: configure automatic delivery of profile-operation notifications, inspect and manually send retained notifications, and automatically remove successfully sent notifications.
- **Access settings and diagnostics**: eSIM details, compatibility checks, custom ISD-R AIDs, verbose logging, and runtime logs.

The interface uses light backgrounds and subtle glass-style cards across the add, download, management, and settings pages.

## Compatibility and requirements

| Item | Details |
| --- | --- |
| Android version | The unprivileged app requires Android 9 or later; actual support depends on the device and card |
| Removable eSIM | A compatible eUICC card is required; access through the phone's SIM slot requires card access rules that allow the app's signing certificate |
| USB reader | A compatible USB CCID reader using T=0 is required, along with USB host support and system USB permission |
| Built-in eSIM | The unprivileged app is not a general-purpose manager for built-in eSIMs; the retained system variant requires system integration and privileges |
| Download connection | Downloads require network access and a valid activation code; some profiles also require a confirmation code |

USB reader access does not rely on the app-signature allowlist used for phone SIM-slot access, but reader, card-protocol, and device compatibility still apply. Operator logos and flags come from local matching and should not be treated as evidence of the currently connected mobile network.

## ARA-M certificate fingerprint

The **SHA-1 fingerprint** of the uonest release signing certificate, in hexadecimal without colons:

```text
23BEBA4A34379ED306CFE63EAF4FE8EECA9979C9
```

This value identifies the uonest signing certificate in ARA-M application access rules for removable eSIMs. It is not an APK file hash. Provide it to the card vendor when arranging application access permissions.

This fingerprint belongs to uonest's own release key. Builds signed with another key have a different fingerprint. To check an installed build, open “About → ARA-M certificate fingerprint” in the app.

## Installation and use

1. Install an APK provided by the uonest project. Upstream EasyEUICC APKs are a separate version.
2. Insert a compatible removable eSIM, or connect a compatible reader and grant system permission.
3. Select an available target on the home screen, tap **+**, and choose an input method.
4. Start the download after entering or recognizing the activation code. Enter a confirmation code when prompted.
5. View and manage the downloaded profile on the home screen.

Deleted profiles cannot be restored within the app. Downloading again may require a new activation code from the operator. Updates require a package name and signing identity compatible with the installed version.

## GitHub Actions release builds

Each push to `main` automatically increments the version, builds a release APK, and publishes it to GitHub Releases with both uonest and 9eSIM signatures. Manual builds and explicit `vX.Y.Z` tag releases are also supported. See the [CI setup guide](docs/github-actions.en.md) for the six required Secrets, version rules, and instructions.

## Build from source

Use Android Studio or a command-line environment with a JDK and Android SDK. The current unprivileged module uses Android SDK 37 and NDK `26.1.10909125`; local builds use JDK 21. Refer to the repository's Gradle configuration for build-tool versions.

After cloning with Git, initialize dependencies:

```sh
git submodule update --init --recursive
```

For source archives, make sure the lpac and cJSON sources under `libs/lpac-jni/src/main/jni/` are complete.

Build the unprivileged debug app:

```sh
./gradlew :app-unpriv:assembleDebug
```

For a release build, configure a local `keystore.properties` file in the project root. These are placeholders only:

```properties
storeFile=/absolute/path/to/your-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
unprivKeyAlias=YOUR_KEY_ALIAS
unprivKeyPassword=YOUR_KEY_PASSWORD
```

This file is ignored by version control. Do not commit real passwords or signing private keys. This configuration signs with one key; additional release-signing workflows require separate configuration.

```sh
./gradlew :app-unpriv:assembleRelease
```

APKs are written under `app-unpriv/build/outputs/apk/`. Without a release signing configuration, the output must be signed before installation.

**Release configuration:** the module's default `applicationId` is still the upstream `im.angry.easyeuicc`. uonest releases override it to `com.uonest.app` through local or CI release configuration. GitHub Actions applies the CI configuration automatically; the commands above alone do not apply release overrides. Explicitly configure the app name, package name, version, and signing identity before publishing your own build to avoid confusion with upstream apps.

The repository also retains the privileged `app` module, built with `./gradlew :app:assembleRelease`. It requires system integration and privileged permissions rather than ordinary APK installation. See [Android.bp](Android.bp) and the [privileged permission allowlist](privapp_whitelist_im.angry.openeuicc.xml).

## Documentation and third-party sources

- [External activation link format](docs/DEEP-LINK.md): retained upstream protocol documentation; the actual interface uses the uonest add flow.
- [OpenEUICC / EasyEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC): the application foundation and LPA implementation.
- [lpac](https://github.com/estkme-group/lpac): eSIM protocol implementation; see individual source directories for component licenses.
- [cJSON](https://github.com/DaveGamble/cJSON): JSON parsing library; see its [MIT license](libs/lpac-jni/src/main/jni/cjson/cjson/LICENSE).
- [NekokoLPA/operator-icons](https://github.com/NekokoLPA/operator-icons): operator matching data and icons; see the [source and revision record](app-common/src/main/assets/operator_catalog/SOURCE.txt). The imported snapshot contained no LICENSE file, so these assets must not be assumed to be MIT-licensed. Refer to the source record for licensing and trademark notes.
- Flag assets: see [source notes](docs/flag-assets.md).
- Country-code data: see the [source notice and Apache-2.0 license](app-common/src/main/assets/iccid_country_codes_LICENSE.txt).
- Lucide icons: see the [license notice](docs/lucide-license.txt).

## Copyright and licensing

The main application retains upstream's **GNU General Public License version 3 (GPL-3.0-only, without the “or later” clause)**. See [LICENSE](LICENSE) for the full text. Modification and redistribution must comply with the applicable license, including applicable corresponding-source requirements. Third-party components and assets retain their respective license and source notices.

The upstream copyright and license notices are preserved below. The attribution for `art/` refers to the upstream artwork retained in this repository:


Everything except `libs/lpac-jni` and `art/`:

```
Copyright 2022-2026 OpenEUICC contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
```

`libs/lpac-jni`:

```
Copyright (C) 2022-2026 OpenEUICC contributiors

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation, version 2.1.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
```

`art/`: Courtesy of [Aikoyori](https://github.com/Aikoyori), CC NC-SA 4.0.
