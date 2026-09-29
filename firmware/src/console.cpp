#include "console.h"
#include <Arduino.h>
#include "airtime.h"

void Console::begin(LoraLink* link, BleBridge* ble, char* nodeNameBuf, size_t nodeNameBufLen) {
    link_ = link;
    ble_ = ble;
    nodeName_ = nodeNameBuf;
    nodeNameCap_ = nodeNameBufLen;
    lineBuf_.reserve(64);
    printHelp();
}

void Console::poll() {
    while (Serial.available() > 0) {
        char c = (char)Serial.read();
        if (c == '\n' || c == '\r') {
            if (lineBuf_.length() > 0) {
                handleLine(lineBuf_);
                lineBuf_ = "";
            }
        } else {
            lineBuf_ += c;
        }
    }
}

void Console::printHelp() {
    Serial.println(F("iTantra LoRa Bridge console (" FW_VERSION "). Commands:"));
    Serial.println(F("  sf <7-12>       set LoRa spreading factor"));
    Serial.println(F("  pwr <dBm>       set LoRa TX power (module max ~22 dBm; keep <=1W ERP, see README)"));
    Serial.println(F("  name <text>     set node name (BLE advertised as iTantra-Bridge-<text>)"));
    Serial.println(F("  stats           show RSSI/SNR/airtime/link counters"));
    Serial.println(F("  help            show this message"));
}

void Console::printStats() {
    const LinkStats& s = link_->stats();
    Serial.println(F("---- LoRa link stats ----"));
    Serial.printf("SF=%u  TxPower=%d dBm  BW=%.0f kHz  CR=4/5\n",
                   link_->spreadingFactor(), link_->txPower(), (double)RadioDefaults::BANDWIDTH_KHZ);
    Serial.printf("Last RX RSSI=%.1f dBm  SNR=%.1f dB\n", s.lastRssi, s.lastSnr);
    Serial.printf("Last TX airtime=%.1f ms\n", s.lastAirtimeMs);
    Serial.printf("TX packets=%lu  retries=%lu  RX packets=%lu\n",
                   (unsigned long)s.txPackets, (unsigned long)s.txRetries, (unsigned long)s.rxPackets);
    Serial.printf("Rebroadcasts=%lu  duplicates dropped=%lu\n",
                   (unsigned long)s.rebroadcasts, (unsigned long)s.duplicatesDropped);
    Serial.printf("BLE connected=%s  MTU=%u\n", ble_->isConnected() ? "yes" : "no", ble_->currentMtu());

    LoRaParams p{link_->spreadingFactor(), RadioDefaults::BANDWIDTH_KHZ, 5,
                 RadioDefaults::PREAMBLE_LEN, true, true};
    Serial.printf("Estimated airtime for an 80B packet at current SF: %.1f ms (%.0f B/s ceiling)\n",
                  loraAirtimeMs(p, 80), loraThroughputBps(p, 80));
}

void Console::handleLine(const String& lineIn) {
    String line = lineIn;
    line.trim();
    int sp = line.indexOf(' ');
    String cmd = (sp < 0) ? line : line.substring(0, sp);
    String arg = (sp < 0) ? String("") : line.substring(sp + 1);
    cmd.toLowerCase();

    if (cmd == "sf") {
        int sf = arg.toInt();
        if (sf < 7 || sf > 12 || !link_->setSpreadingFactor((uint8_t)sf)) {
            Serial.println(F("ERR: sf must be 7-12 and radio must accept it"));
        } else {
            Serial.printf("OK: SF=%d\n", sf);
        }
    } else if (cmd == "pwr") {
        int dbm = arg.toInt();
        if (!link_->setTxPower((int8_t)dbm)) {
            Serial.println(F("ERR: invalid TX power for this module"));
        } else {
            Serial.printf("OK: TX power=%d dBm (verify ERP <= 1W per WPC rules, see README)\n", dbm);
        }
    } else if (cmd == "name") {
        if (arg.length() == 0 || arg.length() >= nodeNameCap_) {
            Serial.println(F("ERR: usage: name <1-31 chars>"));
        } else {
            arg.toCharArray(nodeName_, nodeNameCap_);
            ble_->setNodeName(nodeName_);
            Serial.printf("OK: node name set to '%s'\n", nodeName_);
        }
    } else if (cmd == "stats") {
        printStats();
    } else if (cmd == "help" || cmd == "?") {
        printHelp();
    } else {
        Serial.printf("ERR: unknown command '%s' (try 'help')\n", cmd.c_str());
    }
}
