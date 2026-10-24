# MLPod

Lettore musicale per Android in stile **iPod** (click wheel). Tutta l'interfaccia è in italiano.

## Caratteristiche

- Solo in verticale, scocca in **17 colori** (Impostazioni → Aspetto → Colore scocca)
- Schermo a colori **touch** con feedback tattile e suono al tocco (attivabili/disattivabili)
- **Ghiera** funzionante come quella dell'iPod:
  - ruota il dito sull'anello per scorrere le voci (tic tattile e sonoro a ogni passo)
  - MENU (tieni premuto = menu principale), avanti, indietro (tieni premuto = scorri il brano), play/pausa
  - pulsante centrale per selezionare; in "In riproduzione" la ghiera regola il volume e, premendo il centro, scorre il brano
- Libreria: Artisti, Album, Brani, Generi, Mescola brani, Mescola album (senza radio e podcast)
- Copertine incorporate nei file o file `cover.jpg` / `folder.jpg` nella cartella
- Formati: MP3, FLAC, WAV, OGG, Opus, AAC, M4A, MKA, AMR (M4A/ALAC dipende dal telefono)
- **Hi-Res**: uscita float 32 bit, icona HI-RES quando il brano supera 48 kHz o 16 bit
- **Equalizzatore** DSP a 3-12 bande (frequenza selezionabile per ogni banda), preamp, preset
- **Manopole Bassi e Alti** in stile Poweramp con frequenza selezionabile
- Riproduzione in background con notifica e controlli multimediali

## Impostazioni

Aspetto · Suoni e tatto · Libreria (selezione cartelle, scansione) · Equalizzatore · Riproduzione · Debug (log, esportazione, informazioni audio/libreria) · Informazioni

## Compilazione

L'APK si compila con GitHub Actions a ogni push su `main` (workflow `Build APK`) e viene
pubblicato nelle **Releases** del repository, già firmato e pronto da installare.

Build locale: `gradle :app:assembleRelease` (Gradle 8.9, JDK 17, Android SDK 35).

## Tecnologie

Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + MediaSession).
