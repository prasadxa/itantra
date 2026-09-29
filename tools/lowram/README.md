# 4 GB-class memory test (no 4 GB phone needed)

`lowram_test.py` runs the same workload on the 12 GB test phone several times in one session:
normal, then with `memhold` locking RAM so only a target amount stays free (3000 MB ≈ 6 GB-class,
1800 MB ≈ 4 GB-class). The app is forced into its LITE profile (what a <6 GB phone auto-selects).
`memhold` fills its memory with random data and re-reads it every second so it stays in real RAM
(results record its RSS vs swap as proof).

Workload per run: speech-to-text on 10 clips (one per language, `DEBUG_STT_WAV`), 5 spoken sentences
(`DEBUG_SPEAK`), 75 s idle, 30 s idle-CPU sample, app PSS, low-memory kills, thermal status.

Limit: the chip is not slowed; a real budget phone will be slower.

## Result 2026-09-30 (vivo I2202, Snapdragon 870, Android 14) — `results-20260930-013303.json`
| | normal | ~3 GB free | ~1.7 GB free (4 GB-class) |
|---|---|---|---|
| RAM locked (in RAM / swap) | – | 4.9 GB / 1 MB | 6.9 GB / 1 MB |
| STT OK | 10/10 | 10/10 | 10/10 |
| STT RTF median | 0.088 | 0.072 | 0.072 |
| Speech end → text, median | 0.77 s | 0.62 s | 0.62 s (0.42–0.70) |
| Voice first audio (LITE, cold) | 2.9–5.8 s | 2.8–5.8 s | 2.8–6.3 s |
| Playback underruns | 0 | 0 | 0 |
| App PSS idle / peak | 850 MB / 2.2 GB | 850 MB / 2.2 GB | 765 MB / 1.8 GB |
| Idle CPU (one core) | 0.27% | 0.4% | 0.33% |
| App killed | no | no | no (115 other kills) |

`results-20260930-011551-firstrun-sttbug.json`: first attempt (clips failed to upload; 8 GB locked
left only ~0.3 GB free and Android killed the app's service) — kept for the record.
