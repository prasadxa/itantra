// BLE peripheral matching the phone app's Nordic-UART-style transport
// (org.itantra.transport.BleTransport / FrameCodec / BleReassembler).
//
// Wire compatibility contract (see transport/src/main/kotlin/org/itantra/transport/
// BleTransport.kt, FrameCodec.kt, BleReassembler.kt in the app repo - read-only
// reference, not owned by this firmware):
//   Service UUID   : 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
//   RX char (write): 6E400002-...  phone -> bridge
//   TX char (notify): 6E400003-... bridge -> phone
//   Chunk header   : 3 bytes [seq, index, count] (each wraps at 256) followed by
//                    a slice of the opaque frame payload (JSON today, a compact
//                    binary format is landing separately - this bridge never
//                    parses the payload, it only reassembles/re-chunks bytes).
//
// This firmware only implements the *peripheral* role (GATT server + advertiser).
// The app's BleTransport runs both central and peripheral and tie-breaks who
// dials by comparing `deviceId` strings advertised as BLE service data
// (lexicographically SMALLER deviceId dials as central). We deliberately
// advertise a deviceId of all '~' (0x7E, high in ASCII) so the phone's
// deviceId (its own generated id) is always smaller and the phone always
// becomes the dialer/central - the bridge just needs to be connectable and
// never needs its own central/scanning stack.
#pragma once
#include <NimBLEDevice.h>
#include <functional>
#include <vector>

class BleBridge {
public:
    using FrameCallback = std::function<void(const uint8_t* data, size_t len)>;

    void begin(const char* nodeName);

    // Register the callback invoked once a full opaque frame has been
    // reassembled from incoming BLE write chunks (phone -> bridge -> LoRa).
    void onFrameReceived(FrameCallback cb) { frameCallback_ = cb; }

    // Send an opaque frame to the connected phone (LoRa -> bridge -> phone),
    // re-chunking it into [seq,index,count]-prefixed notify packets.
    bool sendFrame(const uint8_t* data, size_t len);

    bool isConnected() const { return connected_; }
    uint16_t currentMtu() const { return mtu_; }

    // Console `name` command: updates the advertised device name and restarts
    // advertising (does not affect an already-connected phone's session name).
    void setNodeName(const char* name);

    // Internal, called by NimBLE callback glue.
    void handleConnect(uint16_t mtu);
    void handleDisconnect();
    void handleMtuChange(uint16_t mtu);
    void handleWrite(const uint8_t* data, size_t len);

private:
    NimBLEServer* server_ = nullptr;
    NimBLECharacteristic* txChar_ = nullptr;
    FrameCallback frameCallback_;
    volatile bool connected_ = false;
    uint16_t mtu_ = 23; // default ATT MTU until negotiated

    // Incoming reassembly state (mirrors BleReassembler.kt).
    int rxSeq_ = -1;
    int rxCount_ = 0;
    int rxReceived_ = 0;
    std::vector<std::vector<uint8_t>> rxParts_;
    std::vector<bool> rxFilled_;
    uint8_t txSeq_ = 0;

    void restartAdvertising();
};
