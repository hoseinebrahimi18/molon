# Third-party components

- **SoftEther-Android-Module** — https://github.com/hoang-rio/SoftEther-Android-Module — upstream states its original module code is Apache-2.0 and documents the SoftEther protocol reference sources it follows. The bootstrap script downloads the upstream repository and its submodules rather than copying vendor code into this project archive.
- **SoftEtherVPN_Stable** — https://github.com/SoftEtherVPN/SoftEtherVPN_Stable — upstream reference source; retain its license and notices from the checked-out submodule.
- **OpenSSL** — https://github.com/openssl/openssl — dependency submodule; retain its current license/notice files from the checked-out source.
- **AndroidX Security Crypto** — used by the UI for encrypted “Remember me” preferences; its license is governed by the AndroidX artifact distribution.

Before redistributing source or binaries, include the exact upstream `LICENSE`, copyright, and dependency notice files from the pinned vendor checkout. Do not remove vendor license files during updates.
