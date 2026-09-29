// LoRa airtime estimator - Semtech AN1200.13 "LoRa Modem Designer's Guide" formula.
// Used by the `stats` console command (airtime of the last TX'd packet) and by
// the README's SF7/SF9/SF12 throughput table.
#pragma once
#include <cmath>
#include <cstdint>
#include <algorithm>

struct LoRaParams {
    uint8_t  sf;               // 7-12
    float    bwKHz;             // e.g. 125.0
    uint8_t  crPlus4;           // coding rate as (4/5 -> 5) .. (4/8 -> 8); RadioLib's convention
    uint16_t preambleSymbols;   // e.g. 8
    bool     explicitHeader;    // true = explicit header (variable length, our default)
    bool     crcOn;             // true = 16-bit payload CRC enabled (our default)
};

// Airtime in milliseconds for a payload of payloadBytes with the given params.
inline double loraAirtimeMs(const LoRaParams& p, uint16_t payloadBytes) {
    const double bwHz = p.bwKHz * 1000.0;
    const double tSym = std::pow(2.0, p.sf) / bwHz * 1000.0; // ms/symbol
    const double tPreamble = (p.preambleSymbols + 4.25) * tSym;

    const bool deLowDataRateOpt = p.sf >= 11; // Semtech recommends DE=1 for SF11/12 @ <=125kHz
    const double crcTerm = p.crcOn ? 16.0 : 0.0;
    const double hTerm = p.explicitHeader ? 0.0 : 20.0;
    const double denom = 4.0 * (p.sf - (deLowDataRateOpt ? 2.0 : 0.0));

    const double numerator = 8.0 * payloadBytes - 4.0 * p.sf + 28.0 + crcTerm - hTerm;
    const double payloadSymbNb = 8.0 + std::max(std::ceil(numerator / denom) * p.crPlus4, 0.0);

    return tPreamble + payloadSymbNb * tSym;
}

// Optimistic back-to-back throughput (bytes/sec) at this SF - no inter-packet
// gaps, retries, or duty-cycle limiting. A ceiling, not a sustained rate.
inline double loraThroughputBps(const LoRaParams& p, uint16_t payloadBytes) {
    const double ms = loraAirtimeMs(p, payloadBytes);
    return (payloadBytes * 1000.0) / ms;
}
