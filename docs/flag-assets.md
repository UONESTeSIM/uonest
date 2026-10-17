# Flag assets

`app-common/src/main/assets/flags` contains offline country flag icons from
[hampusborgos/country-flags](https://github.com/hampusborgos/country-flags),
commit `c09927e63705529bbf59ca6684cd9b23225dddad`.

The upstream README identifies the source flags as public-domain designs from Wikimedia Commons.
Two-letter SVG assets were rasterized with Sharp to 192×192 PNG, center-cropped to fill the profile icon.
Names use ISO country/territory codes; the picker uses Android's localized country names.
No flag image is downloaded at application runtime.
