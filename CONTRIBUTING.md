# Contributing to Eccles Agent

Thanks for helping. A few ground rules keep the project safe and reviewable.

## Setup
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
JDK 17 and the Android SDK (API 34) are required.

## Pull requests
- One change per PR, with a short description of *why*.
- Logic that can be pure Java (parsing, matching, rules, protocol) should be, with a unit test in `app/src/test`.
- **Safety code is special.** Changes under `guard/` or to the confirmation gate must include tests and must keep the guard **failing closed**. A PR that weakens excluded-app or password-screen protection will not be merged.
- Do not add dependencies without discussing it in an issue first.
- Never commit API keys, keystores or screenshots that show personal data.

## Reporting bugs
Use the bug template. Include Android version, device, the provider/model used, and the timeline text (not screenshots of private screens).
