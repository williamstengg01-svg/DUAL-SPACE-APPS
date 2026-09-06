Create a signing key once and keep it safe — every future update must be signed with the same key:

    keytool -genkeypair -v -keystore dualspace.jks -alias dualspace -keyalg RSA -keysize 2048 -validity 10000

Then copy `keystore.properties.example` to `keystore.properties` and fill in the passwords.
For GitHub Actions, add the base64 of the .jks as secret `KEYSTORE_BASE64` plus
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see .github/workflows/build.yml).
