// LoRa mesh link layer: fragmentation/reassembly of opaque BLE frames, message-
// level ACK + retry with jitter, duplicate suppression, and TTL-decremented
// rebroadcast (store-and-forward mesh - upgrade D groundwork).
//
// On-air packet layout (<= 255 bytes total, SX1262 explicit-header max):
//   [0]      ver          protocol version, currently 1
//   [1..4]   msgId        big-endian uint32, random per outbound message
//   [5]      fragIdx      0..fragCount-1
//   [6]      fragCount    total fragments for this msgId (1 for an ACK)
//   [7]      ttl          hop budget; forwarders decrement and drop at 0
//   [8]      flags        bit0: FLAG_ACK: this packet is an ACK, not data
//   [9..]    payload      up to MAX_LORA_FRAGMENT_PAYLOAD (246) bytes
//
// This is a flood mesh: there is no destination node addressing in the frame
// (the app-level Frame format doesn't carry one either, see FrameCodec.kt), so
// every bridge that hears a fragment both consumes it (feeding complete
// messages to its own BLE peripheral) AND rebroadcasts it once (TTL-1) if not
// already seen. This is appropriate for a PTT/broadcast walkie-talkie group;
// point-to-point addressing is future work.
#pragma once
#include <RadioLib.h>
#include <functional>
#include <vector>
#include <cstdint>
#include "config.h"

#pragma pack(push, 1)
struct LoRaPktHeader {
    uint8_t  ver;
    uint32_t msgId;   // stored/sent big-endian on the wire, see encode/decode helpers
    uint8_t  fragIdx;
    uint8_t  fragCount;
    uint8_t  ttl;
    uint8_t  flags;
};
#pragma pack(pop)

constexpr uint8_t LORA_PROTO_VERSION = 1;
constexpr uint8_t LORA_FLAG_ACK = 0x01;
constexpr size_t LORA_HEADER_BYTES = 9; // packed size on the wire (see encode/decode)

struct LinkStats {
    uint32_t txPackets = 0;
    uint32_t rxPackets = 0;
    uint32_t txRetries = 0;
    uint32_t rebroadcasts = 0;
    uint32_t duplicatesDropped = 0;
    float    lastRssi = 0;
    float    lastSnr = 0;
    double   lastAirtimeMs = 0;
};

class LoraLink {
public:
    using MessageCallback = std::function<void(const uint8_t* data, size_t len)>;

    bool begin(int8_t csPin, int8_t irqPin, int8_t rstPin, int8_t busyPin);

    void onMessageReceived(MessageCallback cb) { messageCallback_ = cb; }

    // Fragments + sends payload as a new mesh message; blocks until all
    // fragments are on air (does not block for the ACK - call service()
    // from loop() to drive the ACK/retry state machine asynchronously).
    bool sendMessage(const uint8_t* data, size_t len);

    // Call every loop() iteration: drains the radio RX, feeds the fragment
    // reassembler/dedup cache, handles rebroadcast and ACK/retry timers.
    void service();

    // Runtime radio config (console `sf`, `pwr` commands).
    bool setSpreadingFactor(uint8_t sf);
    bool setTxPower(int8_t dbm);
    uint8_t spreadingFactor() const { return sf_; }
    int8_t txPower() const { return txPowerDbm_; }

    const LinkStats& stats() const { return stats_; }

private:
    SX1262* radio_ = nullptr;
    Module* module_ = nullptr;
    MessageCallback messageCallback_;
    LinkStats stats_;

    uint8_t sf_ = RadioDefaults::SPREADING_FACTOR;
    int8_t  txPowerDbm_ = RadioDefaults::TX_POWER_DBM;

    // ---- dedup cache: ring buffer of recently seen (msgId,fragIdx,flags) keys
    struct SeenEntry { uint32_t msgId; uint8_t fragIdx; uint8_t flags; bool valid; };
    SeenEntry seen_[MeshDefaults::DEDUP_CACHE_SIZE] = {};
    uint8_t seenNext_ = 0;
    bool alreadySeen(uint32_t msgId, uint8_t fragIdx, uint8_t flags);
    void markSeen(uint32_t msgId, uint8_t fragIdx, uint8_t flags);

    // ---- RX reassembly (single in-flight inbound message at a time, keyed by msgId)
    uint32_t rxMsgId_ = 0;
    uint8_t  rxFragCount_ = 0;
    uint8_t  rxFragsReceived_ = 0;
    std::vector<std::vector<uint8_t>> rxParts_;
    bool rxActive_ = false;

    // ---- TX retry state (one in-flight outbound message at a time)
    std::vector<uint8_t> txPending_;
    uint32_t txMsgId_ = 0;
    uint8_t  txRetriesLeft_ = 0;
    uint32_t txDeadlineMs_ = 0;
    bool     txAwaitingAck_ = false;

    // ---- rebroadcast queue: forward a heard fragment/ACK after jitter delay
    struct PendingRebroadcast { std::vector<uint8_t> raw; uint32_t sendAtMs; };
    std::vector<PendingRebroadcast> rebroadcastQueue_;

    void handleIncomingRaw(const uint8_t* raw, size_t len, float rssi, float snr);
    bool transmitRaw(const std::vector<uint8_t>& raw);
    void encodeHeader(uint8_t* out, const LoRaPktHeader& h);
    LoRaPktHeader decodeHeader(const uint8_t* in);
    void sendAck(uint32_t msgId);
    void sendFragments(const std::vector<uint8_t>& data, uint32_t msgId, uint8_t ttl);
};
