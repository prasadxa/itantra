// iTantra LoRa Bridge - board pins and default radio configuration.
#pragma once
#include <cstdint>

// ---------------------------------------------------------------------------
// Board pin maps. SX1262 is wired over SPI on both supported boards.
// ---------------------------------------------------------------------------
#if defined(BOARD_HELTEC_V3)
  // Heltec WiFi LoRa 32 V3 (ESP32-S3FN8, SX1262). Well-known default pin map;
  // confirm against your specific board revision's schematic before flashing.
  #define PIN_LORA_NSS   8
  #define PIN_LORA_SCK   9
  #define PIN_LORA_MOSI  10
  #define PIN_LORA_MISO  11
  #define PIN_LORA_RST   12
  #define PIN_LORA_BUSY  13
  #define PIN_LORA_DIO1  14
  #define PIN_VEXT       36   // LOW = peripheral power (OLED/LoRa) enabled
  #define PIN_LED        35
  #define HAS_VEXT       1

#elif defined(BOARD_LILYGO_T3S3)
  // LilyGO T3-S3 (ESP32-S3, SX1262/SX1268 variant). Verify against your
  // revision's schematic silkscreen - LilyGO has shipped several SX126x pin
  // revisions of this board.
  #define PIN_LORA_NSS   7
  #define PIN_LORA_SCK   5
  #define PIN_LORA_MOSI  6
  #define PIN_LORA_MISO  3
  #define PIN_LORA_RST   8
  #define PIN_LORA_BUSY  34
  #define PIN_LORA_DIO1  33
  #define PIN_LED        37
  #define HAS_VEXT       0

#else
  #error "Select a board environment (heltec_wifi_lora_32_V3 or lilygo_t3s3) in platformio.ini"
#endif

// ---------------------------------------------------------------------------
// Regulatory note (India, WPC delicensed band):
//   865-867 MHz ISM band, de-licensed under WPC (Wireless Planning & Coordination
//   wing, DoT) for low-power devices, subject to:
//     - Max radiated power (ERP): 1 W (30 dBm ERP-equivalent limit; commonly
//       implemented as <=500 mW / 27 dBm conducted TX power + antenna gain,
//       stay under 1 W ERP total).
//     - Duty cycle: no hard regulatory duty-cycle cap like EU's 1%/10% bands,
///      but "polite" LoRa practice (short airtime, backoff, listen-before-talk
//       where practical) is followed here to keep the shared channel usable.
//     - Channel: single fixed carrier at 865.0625 MHz below is a common,
//       conservative choice inside the 865.000-865.9375 MHz sub-band used by
//       LoRaWAN-IN plans; adjust if you have a channel plan to coordinate with.
//   This firmware defaults TX power conservatively (14 dBm) well under the
//   module's 22 dBm max and the regulatory ceiling - raise via the `pwr`
//   console command only after checking your antenna gain keeps ERP <= 1 W.
// ---------------------------------------------------------------------------
namespace RadioDefaults {
constexpr float FREQUENCY_MHZ   = 865.0625f;
constexpr float BANDWIDTH_KHZ   = 125.0f;
constexpr uint8_t SPREADING_FACTOR = 9;     // 7-12 configurable at runtime
constexpr uint8_t CODING_RATE   = 5;        // 4/5
constexpr int8_t  TX_POWER_DBM  = 14;       // conservative default, <= 22 dBm module max
constexpr uint16_t PREAMBLE_LEN = 8;
constexpr uint8_t  SYNC_WORD    = 0x12;     // private-network sync word (not the LoRaWAN public 0x34)
}

namespace MeshDefaults {
constexpr uint8_t  DEFAULT_TTL       = 3;   // max hops before a frame is dropped
constexpr uint8_t  MAX_RETRIES       = 3;   // message-level retries if no ACK seen
constexpr uint32_t ACK_TIMEOUT_MS    = 4000;
constexpr uint32_t RETRY_JITTER_MS   = 800; // +/- random jitter added to retry/rebroadcast delay
constexpr uint8_t  DEDUP_CACHE_SIZE  = 32;  // recent (msgId) entries remembered
constexpr uint8_t  MAX_LORA_FRAGMENT_PAYLOAD = 246; // 255 - 9 byte header
}

#define FW_VERSION "0.1.0"
#define DEFAULT_NODE_NAME_PREFIX "iTantra-Bridge-"
