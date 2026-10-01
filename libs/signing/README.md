# Community testkey

| File | Role |
|------|------|
| `community.pk8` | PKCS#8 private key |
| `community.pem` | X.509 certificate |

Fingerprint historically noted as `d7f1f224`. Used by the `:signing` host CLI. The well-known AOSP community testkey is intentionally committed.

Host sign:

```bash
./gradlew :signing:signApk -Pin=app/build/outputs/apk/debug/app-debug.apk
# → app-debug_signed.apk next to the input
```
