# Celander

Kalender für Android (Kotlin, Jetpack Compose) im Frosted-Glass-Design.

- Website und Datenschutzerklärung: https://allurecha.github.io/celander-app/
- Quellcode der Android-App: [`android/`](android/) (Version 1.08)

## Bauen

Voraussetzungen: JDK 17 und das Android SDK.

```bash
cd android
./gradlew assembleDebug   # Debug-APK
./gradlew test            # Unit- und UI-Tests
```

Für eine signierte Release-APK braucht es `android/keystore.properties` und den Keystore; beide liegen nicht im Repo.
