# Community testkey

| File | Role |
|------|------|
| `community.pk8` | PKCS#8 private key |
| `community.pem` | X.509 certificate |

Fingerprint historically noted as `d7f1f224`. Used by `:signing` (host CLI) and packaged under `:feature-install` assets for on-device re-sign. The well-known AOSP community testkey is intentionally committed; `copyCommunityKeys` copies it into `features/install/build/generated/communityAssets` at build time, so there is no second checked-in copy.

Host sign:

```bash
./gradlew :signing:signApk -Pin=app/build/outputs/apk/debug/app-debug.apk
# → app-debug_signed.apk next to the input
```
