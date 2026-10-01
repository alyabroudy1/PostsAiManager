# Third-party data and attributions

This file records data (not libraries) the app ships under a licence that asks for attribution. Libraries are declared in
`gradle/libs.versions.toml`.

## Google libaddressinput address metadata (CC BY 4.0)

- **What:** `core/domain/src/main/resources/address/formats.json`, the per-country address formats (postcode pattern, the order of
  the address parts, the parts a country requires) used to verify an address read from a letter
  ([07-document-pipeline.md](07-document-pipeline.md), section 12.3). Countries: DE, GB, US, AE, SA, EG.
- **Source:** the Google libaddressinput address metadata, <https://github.com/google/libaddressinput>.
- **Licence:** Creative Commons Attribution 4.0 International (CC BY 4.0), <https://creativecommons.org/licenses/by/4.0/>.
- **Changes:** adapted. Only the postcode patterns, the order of the parts, the required parts and the countries' names (local and
  English, added to verify a country line) of the countries above are kept, in the app's own JSON shape.
- **Where the attribution lives:**
  - in the data file itself (`source` field of `formats.json`);
  - `AddressFormats.ATTRIBUTION` in `:core:domain` (pinned by `AddressFormatsTest`);
  - the string resource `settings_address_data_attribution` in `feature/settings/src/main/res/values/strings.xml`, the same text, for
    a Settings > About screen.
- **Open item:** the string exists but no screen shows it yet (no UI is wired by this change). Before a release that ships the
  formats, show `settings_address_data_attribution` in Settings > About.
