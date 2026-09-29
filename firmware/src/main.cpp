// iTantra LoRa Bridge - ESP32-S3 + SX1262
//
// Relays opaque app frames between a phone's BLE (Nordic-UART-style) transport
// and a LoRa mesh (India 865-867 MHz ISM band), so two walkie-talkie phones out
// of BLE/Wi-Fi range of each other can still reach one another via one or more
// of these bridges. See README.md for wiring, build/flash, BOM and test plan.
//
// NOT FLASHED: this firmware was built and size-checked on a workstation with
// no LoRa/ESP32-S3 hardware attached. See README's "What is untested" section.
#include <Arduino.h>
#include "config.h"
#include "ble_bridge.h"
#include "lora_link.h"
#include "console.h"

static BleBridge bleBridge;
static LoraLink loraLink;
static Console console_;
static char nodeName[32];

// BLE -> LoRa: a full frame arrived from the phone, forward it into the mesh.
static void onBleFrame(const uint8_t* data, size_t len) {
    if (!loraLink.sendMessage(data, len)) {
        Serial.println(F("WARN: LoRa send failed (radio busy or payload too large)"));
    }
}

// LoRa -> BLE: a full frame was reassembled from the mesh, forward it to the phone.
static void onLoraMessage(const uint8_t* data, size_t len) {
    if (bleBridge.isConnected()) {
        bleBridge.sendFrame(data, len);
    }
    // If no phone is connected, the frame is simply dropped on this hop - store-
    // and-forward for offline phones is future work (see README limitations).
}

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println(F("\niTantra LoRa Bridge " FW_VERSION " booting..."));

#if HAS_VEXT
    pinMode(PIN_VEXT, OUTPUT);
    digitalWrite(PIN_VEXT, LOW); // enable peripheral power rail (Heltec V3)
    delay(50);
#endif
    pinMode(PIN_LED, OUTPUT);

    // Derive a stable default node name from the MAC address, e.g. "A1B2".
    uint64_t mac = ESP.getEfuseMac();
    snprintf(nodeName, sizeof(nodeName), "%04X", (unsigned)(mac & 0xFFFF));

    bleBridge.onFrameReceived(onBleFrame);
    bleBridge.begin(nodeName);

    if (!loraLink.begin(PIN_LORA_NSS, PIN_LORA_DIO1, PIN_LORA_RST, PIN_LORA_BUSY)) {
        Serial.println(F("FATAL: SX1262 init failed - check wiring/pins in config.h"));
    }
    loraLink.onMessageReceived(onLoraMessage);

    console_.begin(&loraLink, &bleBridge, nodeName, sizeof(nodeName));
    Serial.println(F("Ready."));
}

void loop() {
    loraLink.service();
    console_.poll();
}
