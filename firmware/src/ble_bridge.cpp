#include "ble_bridge.h"
#include "config.h"
#include <Arduino.h>
#include <algorithm>
#include <cstring>

namespace {
constexpr const char* SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr const char* RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr const char* TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
constexpr size_t DEVICE_ID_BYTES = 12;
constexpr uint16_t PREFERRED_MTU = 517;

std::string currentName;

class ServerCallbacksImpl : public NimBLEServerCallbacks {
public:
    explicit ServerCallbacksImpl(BleBridge* owner) : owner_(owner) {}
    void onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        owner_->handleConnect(23); // real MTU arrives via onMTUChange once negotiated
    }
    void onDisconnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        owner_->handleDisconnect();
    }
    void onMTUChange(uint16_t mtu, ble_gap_conn_desc* desc) override {
        owner_->handleMtuChange(mtu);
    }
private:
    BleBridge* owner_;
};

class RxCallbacksImpl : public NimBLECharacteristicCallbacks {
public:
    explicit RxCallbacksImpl(BleBridge* owner) : owner_(owner) {}
    void onWrite(NimBLECharacteristic* pCharacteristic) override {
        NimBLEAttValue v = pCharacteristic->getValue();
        owner_->handleWrite(v.data(), v.length());
    }
private:
    BleBridge* owner_;
};
} // namespace

void BleBridge::begin(const char* nodeName) {
    currentName = std::string(DEFAULT_NODE_NAME_PREFIX) + nodeName;

    NimBLEDevice::init(currentName);
    NimBLEDevice::setMTU(PREFERRED_MTU);

    server_ = NimBLEDevice::createServer();
    server_->setCallbacks(new ServerCallbacksImpl(this));

    NimBLEService* service = server_->createService(NimBLEUUID(SERVICE_UUID));
    NimBLECharacteristic* rx = service->createCharacteristic(
        NimBLEUUID(RX_CHAR_UUID),
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    rx->setCallbacks(new RxCallbacksImpl(this));

    txChar_ = service->createCharacteristic(
        NimBLEUUID(TX_CHAR_UUID),
        NIMBLE_PROPERTY::NOTIFY);

    service->start();
    restartAdvertising();
}

void BleBridge::setNodeName(const char* name) {
    currentName = std::string(DEFAULT_NODE_NAME_PREFIX) + name;
    NimBLEDevice::setDeviceName(currentName);
    restartAdvertising();
}

void BleBridge::restartAdvertising() {
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->stop();

    // Primary packet: just the service UUID, so the phone's scan filter finds us.
    NimBLEAdvertisementData advData;
    advData.setCompleteServices(NimBLEUUID(SERVICE_UUID));
    adv->setAdvertisementData(advData);

    // Scan response: our name, plus a deviceId of all 0x7E bytes. The app's
    // BleTransport tie-breaks who dials by comparing deviceId strings
    // (lexicographically smaller dials); 0x7E ('~') sorts after any
    // phone-generated id, so the phone always ends up dialing us and this
    // firmware never needs to implement the BLE central/scan role. See
    // README.md "Why the bridge never dials out over BLE".
    NimBLEAdvertisementData scanResp;
    scanResp.setName(currentName);
    std::string deviceIdBytes(DEVICE_ID_BYTES, (char)0x7E);
    scanResp.setServiceData(NimBLEUUID(SERVICE_UUID), deviceIdBytes);
    adv->setScanResponseData(scanResp);
    adv->setScanResponse(true);

    adv->start();
}

void BleBridge::handleConnect(uint16_t mtu) {
    connected_ = true;
    mtu_ = mtu;
    rxSeq_ = -1;
    rxParts_.clear();
    rxFilled_.clear();
    txSeq_ = 0;
}

void BleBridge::handleDisconnect() {
    connected_ = false;
    mtu_ = 23;
    // NimBLE resumes advertising automatically after a peripheral disconnect
    // when advertising was left running; restart explicitly to be sure.
    restartAdvertising();
}

void BleBridge::handleMtuChange(uint16_t mtu) {
    mtu_ = mtu;
}

// Mirrors org.itantra.transport.BleReassembler.accept(): 3-byte [seq,index,count]
// header; a new seq drops whatever was incomplete and restarts reassembly.
void BleBridge::handleWrite(const uint8_t* data, size_t len) {
    if (len < 3) return;
    const uint8_t pSeq = data[0];
    const uint8_t pIndex = data[1];
    const uint8_t pCount = data[2] == 0 ? 1 : data[2];

    if ((int)pSeq != rxSeq_) {
        rxSeq_ = pSeq;
        rxReceived_ = 0;
        rxCount_ = pCount;
        rxParts_.assign(pCount, std::vector<uint8_t>());
        rxFilled_.assign(pCount, false);
    }
    if (pIndex >= rxParts_.size()) return;
    if (!rxFilled_[pIndex]) {
        rxFilled_[pIndex] = true;
        rxReceived_++;
    }
    rxParts_[pIndex].assign(data + 3, data + len);

    if (rxReceived_ < rxCount_) return;

    std::vector<uint8_t> full;
    for (auto& part : rxParts_) full.insert(full.end(), part.begin(), part.end());
    rxSeq_ = -1; // reset so a repeated/wrapped seq is treated as a fresh frame

    if (frameCallback_) frameCallback_(full.data(), full.size());
}

// Mirrors FrameCodec.chunkForBle(): same 3-byte [seq,index,count] header. The
// Kotlin-side reassembler is chunk-size agnostic, so we don't need to match
// its exact chunkSize formula - just stay within the negotiated ATT MTU.
bool BleBridge::sendFrame(const uint8_t* data, size_t len) {
    if (!connected_ || !txChar_) return false;
    const size_t attPayload = (mtu_ > 3) ? (mtu_ - 3) : 20;
    const size_t chunkSize = (attPayload > 3) ? (attPayload - 3) : 1;
    const size_t count = (len + chunkSize - 1) / chunkSize;
    const size_t safeCount = count == 0 ? 1 : count;
    if (safeCount > 255) return false; // frame too large to chunk (see FrameCodec.MAX_FRAME_BYTES)

    const uint8_t seq = txSeq_++;
    std::vector<uint8_t> chunk;
    for (size_t index = 0; index < safeCount; index++) {
        const size_t start = index * chunkSize;
        const size_t end = std::min(start + chunkSize, len);
        chunk.assign(3 + (end - start), 0);
        chunk[0] = seq;
        chunk[1] = (uint8_t)index;
        chunk[2] = (uint8_t)safeCount;
        if (end > start) memcpy(chunk.data() + 3, data + start, end - start);
        txChar_->notify(chunk.data(), chunk.size());
    }
    return true;
}
