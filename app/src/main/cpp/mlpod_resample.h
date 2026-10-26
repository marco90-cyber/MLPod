// SPDX-License-Identifier: GPL-3.0-or-later
// Ricampionatore polifase a rapporto razionale (filtro sinc con finestra di Kaiser, ~90 dB di reiezione).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <vector>

namespace mlpod {

class Resampler {
public:
    bool active() const { return L_ > 0; }
    int taps() const { return taps_; }

    // inRate -> outRate, canali interleaved. Ritorna false se non serve.
    bool prepare(int inRate, int outRate, int channels) {
        L_ = 0;
        if (inRate <= 0 || outRate <= 0 || inRate == outRate) return false;
        ch_ = std::max(1, channels);
        const int g = std::gcd(inRate, outRate);
        L_ = outRate / g;
        M_ = inRate / g;
        // taglio a 0,5*min(in,out) con zona di transizione del 10 %
        const double fcut = 0.5 * std::min(inRate, outRate);
        const double tw = 0.10 * fcut;
        const double A = 90.0;
        const double dw = 2.0 * M_PI * tw / inRate;  // rad per campione in ingresso
        taps_ = static_cast<int>(std::ceil((A - 7.95) / (2.285 * dw))) | 1;
        taps_ = std::max(taps_, 33);
        const double beta = 0.1102 * (A - 8.7);
        const int total = taps_ * L_;
        std::vector<double> h(total);
        const double mid = (total - 1) / 2.0;
        const double fc = (fcut - 0.5 * tw) / (static_cast<double>(inRate) * L_);  // cicli/campione @ in*L
        auto i0 = [](double x) {
            double s = 1.0, t = 1.0;
            for (int k = 1; k < 60; k++) {
                t *= (x / (2.0 * k)) * (x / (2.0 * k));
                s += t;
                if (t < 1e-12 * s) break;
            }
            return s;
        };
        const double ib = i0(beta);
        for (int n = 0; n < total; n++) {
            const double x = n - mid;
            const double sinc = (x == 0.0) ? 2.0 * fc : std::sin(2.0 * M_PI * fc * x) / (M_PI * x);
            const double r = x / mid;
            const double w = i0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / ib;
            h[n] = sinc * w * L_;
        }
        coef_.assign(static_cast<size_t>(L_) * taps_, 0.0f);
        for (int ph = 0; ph < L_; ph++)
            for (int k = 0; k < taps_; k++) coef_[static_cast<size_t>(ph) * taps_ + k] = static_cast<float>(h[ph + static_cast<size_t>(k) * L_]);
        reset();
        return true;
    }

    void reset() {
        buf_.assign(static_cast<size_t>(taps_ - 1) * ch_, 0.0f);
        base_ = -(taps_ - 1);
        count_ = taps_ - 1;
        prel_ = static_cast<int64_t>(base_ < 0 ? -base_ : 0) * L_;  // p parte da indice 0 assoluto
    }

    // Massimo numero di frame in uscita per `inFrames` in ingresso.
    int maxOut(int inFrames) const { return static_cast<int>((static_cast<int64_t>(inFrames) * L_) / M_) + 4; }

    // Aggiunge `frames` frame e scrive i frame prodotti in out (interleaved). Ritorna quanti.
    int process(const float* in, int frames, float* out) {
        buf_.insert(buf_.end(), in, in + static_cast<size_t>(frames) * ch_);
        count_ += frames;
        int produced = 0;
        for (;;) {
            const int64_t irel = prel_ / L_;
            if (irel >= count_) break;
            const int ph = static_cast<int>(prel_ % L_);
            const float* c = &coef_[static_cast<size_t>(ph) * taps_];
            for (int ch = 0; ch < ch_; ch++) {
                double acc = 0.0;
                const float* x = &buf_[static_cast<size_t>(irel) * ch_ + ch];
                for (int k = 0; k < taps_; k++) acc += static_cast<double>(c[k]) * x[-static_cast<ptrdiff_t>(k) * ch_];
                out[static_cast<size_t>(produced) * ch_ + ch] = static_cast<float>(acc);
            }
            produced++;
            prel_ += M_;
        }
        // scarta la storia non più necessaria
        const int64_t keepFrom = prel_ / L_ - (taps_ - 1);
        if (keepFrom > 0) {
            buf_.erase(buf_.begin(), buf_.begin() + static_cast<size_t>(keepFrom) * ch_);
            count_ -= keepFrom;
            prel_ -= keepFrom * L_;
        }
        return produced;
    }

    // Fine brano: spinge zeri per estrarre la coda del filtro.
    int flushTail(float* out) {
        std::vector<float> z(static_cast<size_t>(taps_) * ch_, 0.0f);
        return process(z.data(), taps_, out);
    }

private:
    int L_ = 0, M_ = 1, taps_ = 33, ch_ = 2;
    std::vector<float> coef_, buf_;
    int64_t base_ = 0, count_ = 0, prel_ = 0;
};

}  // namespace mlpod
