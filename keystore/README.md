# Release signing key

Android installs an update over an existing app **only if both are signed with the same
key**. Dual Space is therefore signed with one permanent key, kept in two places:

1. **GitHub repository secrets** (used by `.github/workflows/build.yml` on every build):
   `KEYSTORE_BASE64` (the PKCS12 file, base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`dualspace`),
   `KEY_PASSWORD` (same as the store password).
2. **An offline backup** of the `.p12` file plus its password, kept by the project owner
   (the file was handed over as `DualSpace-signing-key.p12` together with a README).

If the key is lost, the next build will not install over the existing app: the phone will
ask to uninstall first and every clone will be lost. Keep the backup.

## The key in use

    subject      CN=Dual Space, O=DualSpace, C=ID
    SHA-256      0E:85:3E:1B:47:F3:0F:49:E6:5A:1A:13:2C:CA:6A:30:0F:29:2B:C8:8C:EF:79:94:D8:2F:C6:91:AA:8D:D3:64
    valid until  2054-01-23
    alias        dualspace
    in use since 1.2.3 (versionCode 7)

## Verifying a build

The workflow step *Print signing certificate* shows the certificate's SHA-256 digest for each
APK, and *Settings → Diagnostics* shows the digest of the **installed** app. Both must match
the digest above. A different digest means the build used the throw-away fallback key (no
secrets configured) and will not update the installed app.

## Signing locally

Copy `keystore.properties.example` to `keystore.properties`, put the `.p12` next to it as
`dualspace.jks` (the file name does not matter, `storeType=PKCS12` does) and fill in the
passwords. `./gradlew assembleArm64Release` then produces a signed APK.

## Creating a new key (only for a brand-new app id)

With a JDK: `keytool -genkeypair -v -keystore dualspace.jks -storetype PKCS12 -alias dualspace
-keyalg RSA -keysize 2048 -validity 10000`

With OpenSSL only:

```
openssl req -x509 -newkey rsa:2048 -sha256 -days 10000 -nodes -keyout key.pem -out cert.pem \
  -subj "/CN=Dual Space/O=DualSpace/C=ID"
openssl pkcs12 -export -inkey key.pem -in cert.pem -name dualspace -out dualspace.p12
```

Then `base64 -w0 dualspace.p12` goes into `KEYSTORE_BASE64`.
