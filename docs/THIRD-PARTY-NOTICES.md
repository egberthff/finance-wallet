# Third-Party Notices

Components bundled with the app that require attribution. Runtime libraries are also listed in `gradle/libs.versions.toml`.

| Component | Use | License |
|---|---|---|
| SecLists `Passwords/Common-Credentials/10k-most-common.txt` (Daniel Miessler et al.) | Rejecting common backup passphrases (`data/src/main/resources/.../common-passwords.txt`, SHA-256 `68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce`) | MIT License, Copyright (c) 2018 Daniel Miessler |
| Bouncy Castle `bcprov-jdk18on` 1.86 | Argon2id key derivation for backups | MIT-style Bouncy Castle License |
| Material Icons (Google), path data of ~25 baseline icons in `app/.../ui/theme/AppIcons.kt` | In-app icons | Apache License 2.0 |
