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
- **Equalizzatore** DSP a 3-32 bande (frequenza, guadagno e Q per ogni banda), preamp, bassi/alti in boost e attenuazione, preset di fabbrica e preset salvabili
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

## Motore audio (DSP nativo)

MLPod resta su **Media3/ExoPlayer**; tra decoder e uscita c'è un unico processore audio che chiama un motore C++17 (JNI, `app/src/main/cpp`):

- **Filtri**: cascata di biquad (formule dell'*Audio EQ Cookbook*) calcolati in Kotlin e processati in **doppia precisione**; i campioni in ingresso/uscita sono a 32 bit float (24 bit esatti).
- **Formati in ingresso**: PCM 16, 24, 32 bit e float, da 1 a 8 canali. Uscita: 16 bit se la sorgente è a 16 bit (con dither TPDF solo se il DSP è attivo), altrimenti float 32 bit.
- **Bypass reale**: con equalizzatore spento e manopole al centro l'audio non viene elaborato (PCM16 bit-exact, verificato dai test nativi).
- **Limiter look-ahead** (5 ms, soglia -0,5 dBFS, rilascio 80 ms), attivo solo quando c'è elaborazione. Il guadagno applicato è sempre minore o uguale a quello necessario per rispettare la soglia (verificato dai test). Non gestisce i picchi inter-campione (true peak): la soglia a -0,5 dBFS lascia un piccolo margine.
- **Auto-headroom** (opzionale): abbassa il preamp del massimo boost della curva.
- **Latenza**: il limiter introduce circa 5 ms; la coda viene svuotata a fine brano.

### Uscita audio e limiti

La schermata **Uscita audio** mostra solo dati realmente comunicati da Media3, dal motore DSP e da Android. L'audio passa dal mixer di Android, quindi **non è dichiarato bit-perfect**. **La modalità USB DAC diretta non è implementata** (richiederebbe un driver USB audio nativo): un DAC USB funziona solo come normale uscita Android. Il ricampionamento operato dal sistema non è verificabile dall'app.

### Licenza

MLPod è distribuito con licenza **GPL-3.0-or-later** (file `LICENSE`). L'algoritmo del limiter si ispira a `LimiterProcessor` di [Tryptify](https://github.com/tryptz/Tryptify) (GPL-3.0); il codice è stato riscritto (vedi `NOTICE`).

### Test

- Nativi: `g++ -std=c++17 -O2 app/src/test/cpp/dsp_selftest.cpp -o dsp_selftest && ./dsp_selftest`
- Kotlin: `gradle :app:testDebugUnitTest`

Entrambi vengono eseguiti ad ogni build su GitHub Actions. Non sono stati fatti test su hardware (DAC, cuffie, dispositivi diversi).
