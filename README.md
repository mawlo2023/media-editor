# Media Studio

Android-App (ab Android 10) zum Bearbeiten von Fotos und Videos – **komplett offline**.

## Funktionen

### Foto
- **Filter**: 10 Looks (Kino, Teal & Orange, Vintage, Noir …) mit einstellbarer Stärke
- **Anpassen**: Helligkeit, Kontrast, Sättigung, Wärme, Verblassen, Vignette
- **Zuschnitt**: 1:1, 4:5, 9:16, 16:9, 4:3, Drehen, Spiegeln
- **Hintergrund** (Personen werden automatisch erkannt):
  entfernen (transparentes PNG), unscharf (Porträt-Effekt), Farbe oder eigenes Bild
- **Radierer**: über ein störendes Objekt malen, die KI (LaMa) füllt die Stelle passend auf
- **Text**: Farben, Größe, Schatten/Kontur/Box, frei verschiebbar
- Speichern in *Bilder/MediaStudio*

### Video
- Mehrere Clips aneinanderhängen, sortieren, kürzen
- **Übergänge** (12 Stück): Überblenden, Blitz, Schwarzblende, Wischen, Zoom rein/raus,
  Drehen, Glitch, RGB-Split, Wackeln – Dauer einstellbar, „Für alle Clips“
- **Beat-Erkennung**: erkennt Tempo (BPM) und Beats der Musik offline;
  Clips automatisch auf jeden 1./2./4./8. Beat schneiden
- **Effekte**: Zoom-Puls, Blitz, RGB-Split, Glitch, Wackeln auf jeden Beat;
  Looks RGB-Verschiebung, VHS, Filmkorn
- Vorschau zeigt Übergänge und Effekte live (eigene Play-/Spul-Steuerung)
- Gleiche Filter und Regler wie beim Foto
- Text-Einblendungen, Vignette
- Hintergrundmusik, Lautstärke von Originalton und Musik
- Format: Original, **9:16 (TikTok)**, 1:1, 4:5, 16:9
- Tempo 0,5x bis 2x
- Export als MP4 (H.264, 1080p) nach *Filme/MediaStudio*

## Selbst bauen

```bash
./scripts/download_model.sh   # lädt das KI-Modell (~200 MB, Apache-2.0)
./gradlew assembleRelease
```

Die APK liegt dann unter `app/build/outputs/apk/release/app-release.apk`.

Bei jedem Push auf `main` baut GitHub Actions die APK automatisch
(Reiter **Actions** → letzter Lauf → *Artifacts* → `MediaStudio-apk`).

## Verwendete Bibliotheken
- Jetpack Compose, AndroidX Media3 (Transformer/ExoPlayer)
- Google ML Kit Selfie Segmentation (gebündelt, offline)
- ONNX Runtime + [LaMa-ONNX](https://huggingface.co/Carve/LaMa-ONNX) (Apache-2.0)
