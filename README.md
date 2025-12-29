# DaLang - Minimalistische Navigation

Eine minimalistische Navigations-App für Android, entwickelt für Google-freie Geräte wie das Fairphone mit /e/OS.

## Features

- 🗺️ **OpenStreetMap-Karten** - Keine Google-Abhängigkeit
- 📍 **Aktuelle Position** anzeigen
- 🔍 **Adresssuche** mit Nominatim
- 🚗 **Auto-Navigation** mit Turn-by-turn Anweisungen
- 🔊 **Deutsche Sprachansagen** via Android TTS
- 🌙 **Dark/Light Mode** - folgt Systemeinstellung
- 📥 **Offline-Karten** auf Bundesland-Ebene

## Technologie

- Kotlin + Jetpack Compose
- MapLibre für Kartendarstellung
- OSRM für Routing
- Nominatim für Geocoding
- Android TTS für Sprachausgabe

## Installation

### Via GitHub Actions
1. Gehe zu "Actions" → "Build APK"
2. Klicke auf "Run workflow"
3. Lade das APK aus den Artifacts herunter

### Lokal bauen
```bash
./gradlew assembleDebug
```

Das APK findest du unter `app/build/outputs/apk/debug/app-debug.apk`

## Anforderungen

- Android 10+ (API 29)
- Standort-Berechtigung für Navigation
- Internet für Online-Karten und Routing

## Lizenz

MIT License
