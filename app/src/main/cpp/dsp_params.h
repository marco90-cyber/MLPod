// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - parametri del motore DSP.
//
// L'app li passa come testo "chiave=valore" separati da ';' (vedi DspSettings.toNative() in
// Kotlin): l'ordine non conta, le chiavi sconosciute sono ignorate e quelle mancanti restano ai
// valori predefiniti. Cosi' i due lati non si possono "sfasare" come con un array di numeri.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>

namespace mleq {

constexpr int kMaxBands = 31;   // equalizzatore grafico: da 3 a 31 bande

struct Params {
    bool master = false;          // false = bypass totale (bit-exact)
    double preampDb = 0;          // preamp manuale
    double replayGainDb = 0;      // da tag o misurato
    bool autoHeadroom = true;     // abbassa l'ingresso del rinforzo lineare massimo

    bool eqOn = false;            // equalizzatore grafico
    int geqN = 10;                // numero di bande
    double geqF[kMaxBands] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    double geq[kMaxBands] = {};   // guadagni in dB
    bool geqFaithful = true;

    double bassDb = 0, bassFreq = 90, bassQ = 0.8;        // toni (come Poweramp)
    double trebleDb = 0, trebleFreq = 10000, trebleQ = 0.6;

    bool abassOn = false; double abassDrive = 0.4, abassWarmth = 0.5, abassFreq = 60;  // Analog Bass
    bool airOn = false; double airAmount = 0.5;                                       // Hi-Res Air
    int surroundMode = 0;         // 0 = spento, 1 = tradizionale, 2 = palco frontale, 3 = ultra wide
    bool stereoOn = false;        // bilanciamento, espansione, crossfeed e mono
    double balance = 0, expand = 0, crossfeed = 0;
    bool mono = false;
    int dbfbMode = 0;             // 0 = spento, 1 = normale, 2 = alto
    bool loudnessOn = false; double loudnessAmount = 0.7, volume = 1.0;

    bool limOn = true; double limThr = -0.5, limRel = 80;
};

namespace params {

inline double clampd(double v, double lo, double hi) { return std::isfinite(v) ? std::min(hi, std::max(lo, v)) : lo; }

inline void apply(Params& p, const std::string& k, const std::string& v) {
    const double d = std::atof(v.c_str());
    const bool b = d != 0.0;
    auto is = [&](const char* s) { return k == s; };
    if (is("master")) p.master = b;
    else if (is("pre")) p.preampDb = clampd(d, -24, 24);
    else if (is("rg")) p.replayGainDb = clampd(d, -30, 15);
    else if (is("headroom")) p.autoHeadroom = b;
    else if (is("eq")) p.eqOn = b;
    else if (is("geq.faithful")) p.geqFaithful = b;
    else if (is("geq.n")) p.geqN = static_cast<int>(clampd(d, 1, kMaxBands));
    else if (is("geq.f") || is("geq.g")) {
        // elenco separato da virgole
        double* dst = is("geq.f") ? p.geqF : p.geq;
        const char* s = v.c_str();
        for (int j = 0; j < kMaxBands && *s; j++) {
            char* end = nullptr;
            const double x = std::strtod(s, &end);
            if (end == s) break;
            dst[j] = is("geq.f") ? clampd(x, 20, 20000) : clampd(x, -15, 15);
            s = (*end == ',') ? end + 1 : end;
        }
    }
    else if (is("bass")) p.bassDb = clampd(d, -15, 15);
    else if (is("bass.f")) p.bassFreq = clampd(d, 20, 1000);
    else if (is("bass.q")) p.bassQ = clampd(d, 0.1, 2);
    else if (is("treble")) p.trebleDb = clampd(d, -15, 15);
    else if (is("treble.f")) p.trebleFreq = clampd(d, 1000, 20000);
    else if (is("treble.q")) p.trebleQ = clampd(d, 0.1, 2);
    else if (is("abass")) p.abassOn = b;
    else if (is("abass.drive")) p.abassDrive = clampd(d, 0, 1);
    else if (is("abass.warmth")) p.abassWarmth = clampd(d, 0, 1);
    else if (is("abass.f")) p.abassFreq = clampd(d, 20, 120);
    else if (is("air")) p.airOn = b;
    else if (is("air.amount")) p.airAmount = clampd(d, 0, 1);
    else if (is("surround")) p.surroundMode = static_cast<int>(clampd(d, 0, 3));
    else if (is("stereo")) p.stereoOn = b;
    else if (is("balance")) p.balance = clampd(d, -1, 1);
    else if (is("expand")) p.expand = clampd(d, 0, 1);
    else if (is("crossfeed")) p.crossfeed = clampd(d, 0, 1);
    else if (is("mono")) p.mono = b;
    else if (is("dbfb")) p.dbfbMode = static_cast<int>(clampd(d, 0, 2));
    else if (is("loud")) p.loudnessOn = b;
    else if (is("loud.amount")) p.loudnessAmount = clampd(d, 0, 1);
    else if (is("volume")) p.volume = clampd(d, 0, 1);
    else if (is("lim")) p.limOn = b;
    else if (is("lim.thr")) p.limThr = clampd(d, -12, 0);
    else if (is("lim.rel")) p.limRel = clampd(d, 5, 1000);
}

/** Legge "k=v;k=v;..." partendo dai valori predefiniti. */
inline Params parse(const char* text) {
    Params p;
    if (!text) return p;
    std::string s(text);
    size_t pos = 0;
    while (pos < s.size()) {
        size_t end = s.find_first_of(";\n", pos);
        if (end == std::string::npos) end = s.size();
        const std::string item = s.substr(pos, end - pos);
        const size_t eq = item.find('=');
        if (eq != std::string::npos) apply(p, item.substr(0, eq), item.substr(eq + 1));
        pos = end + 1;
    }
    return p;
}

}  // namespace params
}  // namespace mleq
