// Serial CLI: `sf`, `pwr`, `name`, `stats`, `help`. One line per command,
// newline-terminated, read non-blocking from Serial in loop().
#pragma once
#include "lora_link.h"
#include "ble_bridge.h"

class Console {
public:
    void begin(LoraLink* link, BleBridge* ble, char* nodeNameBuf, size_t nodeNameBufLen);
    void poll(); // call every loop()

private:
    LoraLink* link_ = nullptr;
    BleBridge* ble_ = nullptr;
    char* nodeName_ = nullptr;
    size_t nodeNameCap_ = 0;
    String lineBuf_;

    void handleLine(const String& line);
    void printHelp();
    void printStats();
};
