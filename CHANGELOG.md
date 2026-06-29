# Changelog

## [1.1.2](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.1.1...v1.1.2) (2026-06-29)


### Bug Fixes

* bump com.diffplug.spotless from 8.6.0 to 8.7.0 ([#33](https://github.com/groundsgg/keycloak-minecraft-idp/issues/33)) ([460a6e5](https://github.com/groundsgg/keycloak-minecraft-idp/commit/460a6e5fa34d82596f9d39d8e07d934f5edabb50))
* bump com.gradleup.shadow from 9.4.2 to 9.4.3 ([#37](https://github.com/groundsgg/keycloak-minecraft-idp/issues/37)) ([5ec4bf2](https://github.com/groundsgg/keycloak-minecraft-idp/commit/5ec4bf2f8a99b267d5540026844a7a93acbaacf5))
* bump gradle-wrapper from 9.5.1 to 9.6.0 ([#34](https://github.com/groundsgg/keycloak-minecraft-idp/issues/34)) ([681d53b](https://github.com/groundsgg/keycloak-minecraft-idp/commit/681d53ba7f65da32fd4a16f36f6475bd8ce239fb))
* bump gradle-wrapper from 9.6.0 to 9.6.1 ([#38](https://github.com/groundsgg/keycloak-minecraft-idp/issues/38)) ([92e9d2f](https://github.com/groundsgg/keycloak-minecraft-idp/commit/92e9d2fc4531c47a2ff6cdac9be0c2122f1aa9a7))
* bump org.keycloak:keycloak-parent in the keycloak group ([#36](https://github.com/groundsgg/keycloak-minecraft-idp/issues/36)) ([2488c4d](https://github.com/groundsgg/keycloak-minecraft-idp/commit/2488c4df1ddd9ce91d6d6765e921419a34f2eb31))

## [1.1.1](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.1.0...v1.1.1) (2026-06-06)


### Bug Fixes

* update keycloak and jvm plugin deps ([#30](https://github.com/groundsgg/keycloak-minecraft-idp/issues/30)) ([b15b493](https://github.com/groundsgg/keycloak-minecraft-idp/commit/b15b493d3ab9000cd5430ca82f23ccbb370de189))

## [1.1.0](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.0.3...v1.1.0) (2026-06-03)


### Features

* **idp:** degrade gracefully when Minecraft Services is down ([#28](https://github.com/groundsgg/keycloak-minecraft-idp/issues/28)) ([6744c8f](https://github.com/groundsgg/keycloak-minecraft-idp/commit/6744c8f545fb007ddb9a723b9432e2418114a8a6))


### Bug Fixes

* bump com.diffplug.spotless from 8.5.1 to 8.6.0 ([#25](https://github.com/groundsgg/keycloak-minecraft-idp/issues/25)) ([642a556](https://github.com/groundsgg/keycloak-minecraft-idp/commit/642a55657bbb6ffafcf700d05a8163953df18967))
* bump com.gradleup.shadow from 9.4.1 to 9.4.2 ([#26](https://github.com/groundsgg/keycloak-minecraft-idp/issues/26)) ([94c2855](https://github.com/groundsgg/keycloak-minecraft-idp/commit/94c28552fd25e44c42bd9be4ef1520148489e383))
* bump com.nimbusds:nimbus-jose-jwt from 10.9 to 10.9.1 ([#27](https://github.com/groundsgg/keycloak-minecraft-idp/issues/27)) ([cf8fe9f](https://github.com/groundsgg/keycloak-minecraft-idp/commit/cf8fe9f964647fb12d20fa386ee5c12e5aa21d98))
* bump org.keycloak:keycloak-parent in the keycloak group ([#24](https://github.com/groundsgg/keycloak-minecraft-idp/issues/24)) ([552f13a](https://github.com/groundsgg/keycloak-minecraft-idp/commit/552f13a4d03fbc8f5993883a0cd67d2dd3204182))

## [1.0.3](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.0.2...v1.0.3) (2026-04-28)


### Bug Fixes

* **deps:** bump jvm from 2.3.20 to 2.3.21 ([#19](https://github.com/groundsgg/keycloak-minecraft-idp/issues/19)) ([ad336fa](https://github.com/groundsgg/keycloak-minecraft-idp/commit/ad336fa3a306df96f2413e58896ecfd6eb9dc11a))
* **deps:** bump org.keycloak:keycloak-parent in the keycloak group ([#17](https://github.com/groundsgg/keycloak-minecraft-idp/issues/17)) ([4b3e8b2](https://github.com/groundsgg/keycloak-minecraft-idp/commit/4b3e8b204523a089eb11702106edf02d7e44b7b8))

## [1.0.2](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.0.1...v1.0.2) (2026-04-22)


### Bug Fixes

* register minecraft provider as social identity provider ([#13](https://github.com/groundsgg/keycloak-minecraft-idp/issues/13)) ([fe934a6](https://github.com/groundsgg/keycloak-minecraft-idp/commit/fe934a630f4a971dd1d39e4e895a5f6562227326))

## [1.0.1](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v1.0.0...v1.0.1) (2026-04-06)


### Bug Fixes

* attach jar to release ([#10](https://github.com/groundsgg/keycloak-minecraft-idp/issues/10)) ([0248e1d](https://github.com/groundsgg/keycloak-minecraft-idp/commit/0248e1dc520fab5d484e9fd6c572ddab4eabe882))

## [1.0.0](https://github.com/groundsgg/keycloak-minecraft-idp/compare/v0.1.0...v1.0.0) (2026-04-06)


### ⚠ BREAKING CHANGES

* use partner XSTS ptx for stable account linking ([#9](https://github.com/groundsgg/keycloak-minecraft-idp/issues/9))
* rewrite identity provider in Kotlin ([#1](https://github.com/groundsgg/keycloak-minecraft-idp/issues/1))

### Features

* add MINECRAFT_IDP_REDIRECT_URI env var support ([fc41a51](https://github.com/groundsgg/keycloak-minecraft-idp/commit/fc41a513e9b9b68f0b8932b78542828f62b9340b))


### Bug Fixes

* Keycloak 26.5.4 compatibility + env var support for credentials ([a07c46f](https://github.com/groundsgg/keycloak-minecraft-idp/commit/a07c46f44c8a5c761fd6cb474e8a98f06f54e80a))
* use partner XSTS ptx for stable account linking ([#9](https://github.com/groundsgg/keycloak-minecraft-idp/issues/9)) ([d27f758](https://github.com/groundsgg/keycloak-minecraft-idp/commit/d27f758a16bf40dc908cfcc9fd78dc5a515d82c6))


### Code Refactoring

* rewrite identity provider in Kotlin ([#1](https://github.com/groundsgg/keycloak-minecraft-idp/issues/1)) ([ff96cf0](https://github.com/groundsgg/keycloak-minecraft-idp/commit/ff96cf0a2f3d60b8d3d5de149d2396c88375d29c))
