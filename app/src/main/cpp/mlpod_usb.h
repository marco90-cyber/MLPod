// SPDX-License-Identifier: GPL-3.0-or-later
// Uscita audio USB isocrona diretta (USB Audio Class 1) tramite usbfs.
// Il file descriptor arriva da UsbDeviceConnection; interfaccia e alt-setting sono già
// stati selezionati dal lato Java. Qui si gestiscono solo i trasferimenti isocroni.
#pragma once

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

namespace mlpod {

class UsbOut {
public:
    static constexpr int kUrbs = 6;
    static constexpr int kPackets = 8;

    // Solo stereo a 16 bit: frameBytes = 4.
    bool open(int fd, int endpoint, int rate, int intervalUs, int maxPacket) {
        fd_ = fd;
        ep_ = endpoint;
        rate_ = rate;
        intervalUs_ = intervalUs;
        maxPacket_ = maxPacket;
        if (fd < 0 || rate <= 0 || intervalUs <= 0 || maxPacket < static_cast<int>(kFrameBytes)) return false;
        const size_t ringBytes = static_cast<size_t>(rate) * kFrameBytes / 2;  // 0,5 s
        ring_.assign(ringBytes - ringBytes % kFrameBytes, 0);
        rd_ = wr_ = fill_ = 0;
        played_ = 0;
        const size_t urbSize = sizeof(usbdevfs_urb) + kPackets * sizeof(usbdevfs_iso_packet_desc);
        for (int i = 0; i < kUrbs; i++) {
            auto* u = static_cast<usbdevfs_urb*>(std::calloc(1, urbSize));
            buf_[i].assign(static_cast<size_t>(maxPacket) * kPackets, 0);
            u->type = USBDEVFS_URB_TYPE_ISO;
            u->endpoint = static_cast<unsigned char>(endpoint);
            u->flags = USBDEVFS_URB_ISO_ASAP;
            u->buffer = buf_[i].data();
            u->number_of_packets = kPackets;
            u->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(i));
            urb_[i] = u;
            real_[i] = 0;
        }
        running_ = true;
        failed_ = false;
        th_ = std::thread([this] { run(); });
        return true;
    }

    void close() {
        running_ = false;
        if (th_.joinable()) th_.join();
        for (auto& u : urb_) {
            std::free(u);
            u = nullptr;
        }
    }

    // Scrive frame interi nell'anello; restituisce i byte accettati.
    size_t write(const uint8_t* data, size_t bytes) {
        std::lock_guard<std::mutex> lk(m_);
        size_t space = ring_.size() - fill_;
        size_t n = std::min(bytes, space);
        n -= n % kFrameBytes;
        size_t first = std::min(n, ring_.size() - wr_);
        std::memcpy(ring_.data() + wr_, data, first);
        std::memcpy(ring_.data(), data + first, n - first);
        wr_ = (wr_ + n) % ring_.size();
        fill_ += n;
        return n;
    }

    long long playedFrames() const { return played_.load(); }
    long long bufferedFrames() {
        std::lock_guard<std::mutex> lk(m_);
        return static_cast<long long>(fill_ / kFrameBytes);
    }
    void setPaused(bool p) { paused_ = p; }
    void setGain(float g) { gain_ = g; }
    bool failed() const { return failed_.load(); }
    int lastErrno() const { return err_.load(); }

    // Riavvia il thread dopo un errore (per esempio alt-setting azzerato dal sistema), tenendo i dati.
    void restart() {
        running_ = false;
        if (th_.joinable()) th_.join();
        failed_ = false;
        err_ = 0;
        {
            std::lock_guard<std::mutex> lk(m_);
            for (auto& r : real_) r = 0;
        }
        running_ = true;
        th_ = std::thread([this] { run(); });
    }

    void flush() {
        std::lock_guard<std::mutex> lk(m_);
        rd_ = wr_ = fill_ = 0;
        played_ = 0;
        for (auto& r : real_) r = 0;
    }

private:
    static constexpr size_t kFrameBytes = 4;

    // Riempie l'URB idx con i prossimi pacchetti (dati reali o silenzio). Chiamare con m_ non preso.
    void fill(int idx) {
        std::lock_guard<std::mutex> lk(m_);
        usbdevfs_urb* u = urb_[idx];
        uint8_t* dst = buf_[idx].data();
        size_t off = 0;
        long long real = 0;
        const bool pause = paused_.load();
        const float g = gain_.load();
        for (int p = 0; p < kPackets; p++) {
            acc_ += static_cast<long long>(rate_) * intervalUs_;
            long long frames = acc_ / 1000000;
            acc_ -= frames * 1000000;
            size_t bytes = static_cast<size_t>(frames) * kFrameBytes;
            if (bytes > static_cast<size_t>(maxPacket_)) bytes = static_cast<size_t>(maxPacket_) / kFrameBytes * kFrameBytes;
            size_t take = pause ? 0 : std::min(bytes, fill_);
            take -= take % kFrameBytes;
            if (take) {
                size_t first = std::min(take, ring_.size() - rd_);
                std::memcpy(dst + off, ring_.data() + rd_, first);
                std::memcpy(dst + off + first, ring_.data(), take - first);
                rd_ = (rd_ + take) % ring_.size();
                fill_ -= take;
                real += static_cast<long long>(take / kFrameBytes);
                if (g < 0.9999f) {
                    auto* s = reinterpret_cast<int16_t*>(dst + off);
                    for (size_t i = 0; i < take / 2; i++) {
                        int v = static_cast<int>(s[i] * g);
                        s[i] = static_cast<int16_t>(std::max(-32768, std::min(32767, v)));
                    }
                }
            }
            if (take < bytes) std::memset(dst + off + take, 0, bytes - take);
            u->iso_frame_desc[p].length = static_cast<unsigned int>(bytes);
            u->iso_frame_desc[p].actual_length = 0;
            u->iso_frame_desc[p].status = 0;
            off += bytes;
        }
        u->buffer_length = static_cast<int>(off);
        u->actual_length = 0;
        u->status = 0;
        real_[idx] = real;
    }

    bool submit(int idx) {
        if (ioctl(fd_, USBDEVFS_SUBMITURB, urb_[idx]) != 0) return false;
        inflight_++;
        return true;
    }

    void run() {
        int submitted = 0;
        for (int i = 0; i < kUrbs; i++) {
            fill(i);
            if (!submit(i)) {
                err_ = errno;
                failed_ = true;
                running_ = false;
                break;
            }
            submitted++;
        }
        while (running_.load()) {
            pollfd pf{fd_, POLLOUT, 0};
            int pr = poll(&pf, 1, 100);
            if (pr < 0 && errno != EINTR) {
                failed_ = true;
                break;
            }
            for (;;) {
                usbdevfs_urb* done = nullptr;
                if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &done) < 0) {
                    if (errno == ENODEV || errno == ESHUTDOWN) failed_ = true;
                    break;
                }
                if (!done) break;
                inflight_--;
                int idx = static_cast<int>(reinterpret_cast<intptr_t>(done->usercontext));
                {
                    std::lock_guard<std::mutex> lk(m_);
                    played_ += real_[idx];
                    real_[idx] = 0;
                }
                fill(idx);
                if (!submit(idx)) {
                    err_ = errno;
                    failed_ = true;
                    running_ = false;
                    break;
                }
            }
            if (failed_.load()) break;
        }
        for (int i = 0; i < kUrbs; i++) ioctl(fd_, USBDEVFS_DISCARDURB, urb_[i]);
        for (int tries = 0; tries < 200 && inflight_.load() > 0; tries++) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &done) == 0 && done) {
                inflight_--;
            } else {
                usleep(2000);
            }
        }
        (void)submitted;
    }

    int fd_ = -1, ep_ = 0, rate_ = 44100, intervalUs_ = 1000, maxPacket_ = 192;
    long long acc_ = 0;
    std::vector<uint8_t> ring_;
    size_t rd_ = 0, wr_ = 0, fill_ = 0;
    std::mutex m_;
    std::vector<uint8_t> buf_[kUrbs];
    usbdevfs_urb* urb_[kUrbs] = {};
    long long real_[kUrbs] = {};
    std::atomic<long long> played_{0};
    std::atomic<bool> running_{false}, paused_{true}, failed_{false};
    std::atomic<int> err_{0};
    std::atomic<int> inflight_{0};
    std::atomic<float> gain_{1.0f};
    std::thread th_;
};

}  // namespace mlpod
