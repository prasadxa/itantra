#include "lora_link.h"
#include <Arduino.h>
#include <cstring>
#include <algorithm>
#include <esp_system.h>
#include "airtime.h"

namespace {
LoraLink* g_instance = nullptr;
volatile bool g_rxFlag = false;

#if defined(ESP32)
void IRAM_ATTR onDio1Rise() { g_rxFlag = true; }
#else
void onDio1Rise() { g_rxFlag = true; }
#endif
} // namespace

bool LoraLink::begin(int8_t csPin, int8_t irqPin, int8_t rstPin, int8_t busyPin) {
    g_instance = this;
    module_ = new Module(csPin, irqPin, rstPin, busyPin);
    radio_ = new SX1262(module_);

    int state = radio_->begin(
        RadioDefaults::FREQUENCY_MHZ,
        RadioDefaults::BANDWIDTH_KHZ,
        sf_,
        /* cr = */ 5, // 4/5, RadioLib convention (CR+4)
        RadioDefaults::SYNC_WORD,
        txPowerDbm_,
        RadioDefaults::PREAMBLE_LEN);
    if (state != RADIOLIB_ERR_NONE) {
        Serial.printf("SX1262 begin() failed, code %d\n", state);
        return false;
    }

    radio_->setDio1Action(onDio1Rise);
    radio_->setCRC(2); // 16-bit payload CRC on, matches airtime.h's crcOn assumption

    state = radio_->startReceive();
    if (state != RADIOLIB_ERR_NONE) {
        Serial.printf("SX1262 startReceive() failed, code %d\n", state);
        return false;
    }
    return true;
}

void LoraLink::encodeHeader(uint8_t* out, const LoRaPktHeader& h) {
    out[0] = h.ver;
    out[1] = (uint8_t)(h.msgId >> 24);
    out[2] = (uint8_t)(h.msgId >> 16);
    out[3] = (uint8_t)(h.msgId >> 8);
    out[4] = (uint8_t)(h.msgId);
    out[5] = h.fragIdx;
    out[6] = h.fragCount;
    out[7] = h.ttl;
    out[8] = h.flags;
}

LoRaPktHeader LoraLink::decodeHeader(const uint8_t* in) {
    LoRaPktHeader h;
    h.ver = in[0];
    h.msgId = (uint32_t(in[1]) << 24) | (uint32_t(in[2]) << 16) | (uint32_t(in[3]) << 8) | uint32_t(in[4]);
    h.fragIdx = in[5];
    h.fragCount = in[6];
    h.ttl = in[7];
    h.flags = in[8];
    return h;
}

bool LoraLink::transmitRaw(const std::vector<uint8_t>& raw) {
    if (raw.size() > 255) return false;
    std::vector<uint8_t> buf = raw; // RadioLib's transmit() takes a non-const pointer
    int state = radio_->transmit(buf.data(), buf.size());
    stats_.txPackets++;
    LoRaParams p{sf_, RadioDefaults::BANDWIDTH_KHZ, 5, RadioDefaults::PREAMBLE_LEN, true, true};
    stats_.lastAirtimeMs = loraAirtimeMs(p, (uint16_t)buf.size());
    radio_->startReceive(); // transmit() leaves the radio in standby
    return state == RADIOLIB_ERR_NONE;
}

void LoraLink::sendFragments(const std::vector<uint8_t>& data, uint32_t msgId, uint8_t ttl) {
    const size_t chunkSize = MeshDefaults::MAX_LORA_FRAGMENT_PAYLOAD;
    const size_t total = data.size();
    const size_t fragCount = total == 0 ? 1 : (total + chunkSize - 1) / chunkSize;

    for (size_t i = 0; i < fragCount; i++) {
        const size_t start = i * chunkSize;
        const size_t end = std::min(start + chunkSize, total);
        std::vector<uint8_t> pkt(LORA_HEADER_BYTES + (end - start));
        LoRaPktHeader h{LORA_PROTO_VERSION, msgId, (uint8_t)i, (uint8_t)fragCount, ttl, 0};
        encodeHeader(pkt.data(), h);
        if (end > start) memcpy(pkt.data() + LORA_HEADER_BYTES, data.data() + start, end - start);
        transmitRaw(pkt);
    }
}

void LoraLink::sendAck(uint32_t msgId) {
    std::vector<uint8_t> pkt(LORA_HEADER_BYTES);
    LoRaPktHeader h{LORA_PROTO_VERSION, msgId, 0, 1, MeshDefaults::DEFAULT_TTL, LORA_FLAG_ACK};
    encodeHeader(pkt.data(), h);
    transmitRaw(pkt);
}

bool LoraLink::sendMessage(const uint8_t* data, size_t len) {
    const size_t maxBytes = (size_t)MeshDefaults::MAX_LORA_FRAGMENT_PAYLOAD * 255;
    if (len > maxBytes) return false; // see README: no destination addressing/large-message split beyond this

    txPending_.assign(data, data + len);
    txMsgId_ = (uint32_t)esp_random();
    txRetriesLeft_ = MeshDefaults::MAX_RETRIES;
    txAwaitingAck_ = true;
    txDeadlineMs_ = millis() + MeshDefaults::ACK_TIMEOUT_MS;

    sendFragments(txPending_, txMsgId_, MeshDefaults::DEFAULT_TTL);
    return true;
}

bool LoraLink::alreadySeen(uint32_t msgId, uint8_t fragIdx, uint8_t flags) {
    for (auto& e : seen_) {
        if (e.valid && e.msgId == msgId && e.fragIdx == fragIdx && e.flags == flags) return true;
    }
    return false;
}

void LoraLink::markSeen(uint32_t msgId, uint8_t fragIdx, uint8_t flags) {
    seen_[seenNext_] = {msgId, fragIdx, flags, true};
    seenNext_ = (seenNext_ + 1) % MeshDefaults::DEDUP_CACHE_SIZE;
}

void LoraLink::handleIncomingRaw(const uint8_t* raw, size_t len, float rssi, float snr) {
    if (len < LORA_HEADER_BYTES) return;
    LoRaPktHeader h = decodeHeader(raw);
    if (h.ver != LORA_PROTO_VERSION) return;

    if (alreadySeen(h.msgId, h.fragIdx, h.flags)) {
        stats_.duplicatesDropped++;
        return;
    }
    markSeen(h.msgId, h.fragIdx, h.flags);

    const bool isAck = (h.flags & LORA_FLAG_ACK) != 0;
    if (isAck) {
        if (txAwaitingAck_ && h.msgId == txMsgId_) {
            txAwaitingAck_ = false;
        }
    } else {
        const uint8_t* payload = raw + LORA_HEADER_BYTES;
        const size_t payloadLen = len - LORA_HEADER_BYTES;

        if (!rxActive_ || h.msgId != rxMsgId_) {
            rxActive_ = true;
            rxMsgId_ = h.msgId;
            rxFragCount_ = h.fragCount == 0 ? 1 : h.fragCount;
            rxFragsReceived_ = 0;
            rxParts_.assign(rxFragCount_, std::vector<uint8_t>());
        }
        if (h.fragIdx < rxParts_.size() && rxParts_[h.fragIdx].empty()) {
            rxParts_[h.fragIdx].assign(payload, payload + payloadLen);
            // guard against a legitimately-empty final fragment being resent
            // and double counted: only advance the receive count once.
            rxFragsReceived_++;
        }
        if (rxFragsReceived_ >= rxFragCount_) {
            std::vector<uint8_t> full;
            for (auto& part : rxParts_) full.insert(full.end(), part.begin(), part.end());
            rxActive_ = false;
            if (messageCallback_) messageCallback_(full.data(), full.size());
            sendAck(h.msgId);
        }
    }

    // Store-and-forward: rebroadcast unseen packets with TTL-1, after a random
    // jitter delay, to avoid every neighbor transmitting in lockstep.
    if (h.ttl > 0) {
        LoRaPktHeader fwd = h;
        fwd.ttl = h.ttl - 1;
        std::vector<uint8_t> fwdRaw(raw, raw + len);
        encodeHeader(fwdRaw.data(), fwd);
        const uint32_t jitter = random(0, MeshDefaults::RETRY_JITTER_MS);
        rebroadcastQueue_.push_back({fwdRaw, millis() + jitter});
    }
}

void LoraLink::service() {
    if (g_rxFlag) {
        g_rxFlag = false;
        size_t len = radio_->getPacketLength();
        if (len > 0 && len <= 255) {
            std::vector<uint8_t> buf(len);
            int state = radio_->readData(buf.data(), buf.size());
            if (state == RADIOLIB_ERR_NONE) {
                stats_.rxPackets++;
                stats_.lastRssi = radio_->getRSSI();
                stats_.lastSnr = radio_->getSNR();
                handleIncomingRaw(buf.data(), buf.size(), stats_.lastRssi, stats_.lastSnr);
            }
        }
        radio_->startReceive();
    }

    const uint32_t now = millis();

    // Drain due rebroadcasts.
    for (size_t i = 0; i < rebroadcastQueue_.size();) {
        if ((int32_t)(now - rebroadcastQueue_[i].sendAtMs) >= 0) {
            transmitRaw(rebroadcastQueue_[i].raw);
            stats_.rebroadcasts++;
            rebroadcastQueue_.erase(rebroadcastQueue_.begin() + i);
        } else {
            i++;
        }
    }

    // Message-level ACK/retry with jitter.
    if (txAwaitingAck_ && (int32_t)(now - txDeadlineMs_) >= 0) {
        if (txRetriesLeft_ > 0) {
            txRetriesLeft_--;
            stats_.txRetries++;
            sendFragments(txPending_, txMsgId_, MeshDefaults::DEFAULT_TTL);
            const uint32_t jitter = random(0, MeshDefaults::RETRY_JITTER_MS);
            txDeadlineMs_ = now + MeshDefaults::ACK_TIMEOUT_MS + jitter;
        } else {
            txAwaitingAck_ = false; // gave up; caller has no delivery confirmation API yet
        }
    }
}

bool LoraLink::setSpreadingFactor(uint8_t sf) {
    if (sf < 7 || sf > 12) return false;
    int state = radio_->setSpreadingFactor(sf);
    if (state != RADIOLIB_ERR_NONE) return false;
    sf_ = sf;
    radio_->startReceive();
    return true;
}

bool LoraLink::setTxPower(int8_t dbm) {
    int state = radio_->setOutputPower(dbm);
    if (state != RADIOLIB_ERR_NONE) return false;
    txPowerDbm_ = dbm;
    return true;
}
