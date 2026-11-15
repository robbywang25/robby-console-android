# Authentication domain transition

New Android sign-ins use `https://auth.robby.wang` as issuer and `auth.robby.wang` as WebAuthn RP. The `robby-console-android` client ID, God API audience, redirect URI, requested scopes, encrypted store filename/AAD/key alias and independent God `/session` federated flow remain unchanged.

The release baseline was verified on 2026-11-15: latest GitHub release `v0.49.0`, tag and remote `robbywang/god-android` all resolve to `793411f8bec02e389b4b9591d966bdb5db89d5d0`. This candidate does not bump the release version, publish an APK, or install on a device.

## Existing sessions

- New pending requests and native sessions persist their exact issuer. Pending requests saved by older versions have no issuer field and remain bound to `https://auth.god.robby.wang`.
- Existing native sessions recover the issuer from their access JWT inside the existing encrypted record. Only the exact canonical or legacy issuer is accepted. An explicit stored issuer must match the JWT. JWT format, client and audience checks remain enforced; local parsing is a routing hint, not signature verification.
- Discovery, code exchange, refresh and revocation stay on the selected origin. Discovery cannot point a token endpoint at the other allowed issuer. A response from one issuer cannot silently replace a session with a token issued by the other. DNS/network/configuration failure does not trigger a cross-origin fallback or clear the session.
- Legacy sessions need the legacy issuer's real discovery, token and revocation endpoints while they remain in use. Redirecting those endpoints to the new issuer is deliberately rejected. Before retiring the old domain, complete new-domain sign-in on the relevant devices and verify that new issuer tokens are stored and accepted by the API.
- The God `/session` Google/Apple federated token and revocation endpoints remain independent. No account data or saved token is rewritten merely by updating the app.

## Native Passkey and settings

The native flow derives one allowed issuer from its authorization URL, then binds every request, returned challenge RP, assertion RP hash, cookie domain, consent/resume URL and callback issuer to that same value. New sign-in uses the canonical issuer. A restored legacy authorization can still complete against its own domain.

Account security links accept exactly `https://auth.robby.wang/settings/two-factor-authentication` and its legacy-domain equivalent. The default is the new domain. Other paths, query strings, fragments, ports, credentials and lookalike domains are not accepted.

## Server configuration to coordinate

Serve the adjacent `assetlinks.json` at `https://auth.robby.wang/.well-known/assetlinks.json`, over valid HTTPS, as JSON with an HTTP 200 response. It names only the release package and the existing release certificate. The public certificate fingerprint and Android opaque origin are corroborated by the existing native Passkey test and site-admin `deploy/android-client/enable_native_passkey.py`; no private signing material is included.

The matching Authelia Android origin remains:

```text
android:apk-key-hash:g9Yk4ObUlZissgYreTrsad3vxPRQmmpCmQQEZhs9pWk
```

The new domain must issue a challenge whose `rpId` is exactly `auth.robby.wang`, recognize that release signing origin, and expose matching OIDC discovery/endpoints. Existing credentials registered only for the old RP need a genuine new-RP registration; changing a database RP string cannot move an authenticator's credential. Keep old-domain DAL and endpoints available during the approved transition. The app's God callback App Link continues at `god.robby.wang/mobile/android/callback`; this change adds no auth-domain intent filter and authorizes no debug/preview signing certificate.

Publishing DAL or changing Authelia is coordinated server work and has not been performed by this Android candidate. Device registration, Credential Manager/DAL reachability, biometric sign-in, browser fallback, refresh and revocation require release-signed real-device acceptance.

## Local verification

Run the repository's CI-equivalent tasks with JDK 17 and Android SDK 36:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

`AuthIssuerMigrationTest` uses an in-memory store and synthetic transport to exercise old/new session reading, refresh, revocation, pending callback recovery, mismatch rejection, endpoint substitution and the independent federated route. No test reads a real credential or connects to the identity service.
