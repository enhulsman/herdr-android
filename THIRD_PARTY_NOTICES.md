# Third-party notices

Material in this repository that comes from other projects, with its licence
and where it came from. Code dependencies are listed in `Cargo.lock` and
`android/gradle/libs.versions.toml` and checked by `cargo deny` and Gradle
dependency verification.

## Fonts bundled in the app

Both families are licensed under the SIL Open Font License 1.1. The licence
texts ship inside the APK, in `android/app/src/main/assets/licenses/`. The
`.ttf` files in `android/app/src/main/res/font/` are copied unmodified from the
release archives (renamed only, as Android resource names must be lowercase).

### Geist

- Version: 1.8.1
- Source: https://github.com/vercel/geist-font/releases/download/v1.8.1/geist-font-v1.8.1.zip
- Archive SHA-256: `70f42c1452fb6df12ea183e20a65999157354f91d11dac18b4c713e383808ec2`
  (matches the digest GitHub lists for the release asset)
- Licence: `android/app/src/main/assets/licenses/Geist-OFL.txt` (the archive's `OFL.txt`)

| File | From the archive | SHA-256 |
|---|---|---|
| `geist_regular.ttf` | `geist-font/Geist/ttf/Geist-Regular.ttf` | `4467c854246d29cf2fe8a18694f4dcd8996439e535d53b260b3bfb34c110175f` |
| `geist_medium.ttf` | `geist-font/Geist/ttf/Geist-Medium.ttf` | `cf90d22c5f12977b06173440f6f564b7e1245aeb48bc4d5fbd82e6cde16abff5` |
| `geist_semibold.ttf` | `geist-font/Geist/ttf/Geist-SemiBold.ttf` | `9192802fb4e3cb725833b4a74b35ec6a03c62c44cf55b64864abd8de64bf720e` |

### JetBrains Mono

- Version: 2.304
- Source: https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip
- Archive SHA-256: `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`
- Licence: `android/app/src/main/assets/licenses/JetBrainsMono-OFL.txt` (the archive's `OFL.txt`)

| File | From the archive | SHA-256 |
|---|---|---|
| `jetbrains_mono_regular.ttf` | `fonts/ttf/JetBrainsMono-Regular.ttf` | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` |
| `jetbrains_mono_medium.ttf` | `fonts/ttf/JetBrainsMono-Medium.ttf` | `31c92d01a8a08528b718a43addf0ad3df0af2ca4b7b3290a452f70f358e14d3d` |
| `jetbrains_mono_bold.ttf` | `fonts/ttf/JetBrainsMono-Bold.ttf` | `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb` |

## herdr

- Protocol code adapted from herdr (Apache-2.0): `crates/herdr-proto/`, licence in
  `crates/herdr-proto/LICENSE-herdr-Apache-2.0`.
- herdr-app icons (Apache-2.0), used in the design renders: `design/renders/icons/`.
