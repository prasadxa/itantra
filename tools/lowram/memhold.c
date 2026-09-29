// memhold — reserve RAM on an Android phone to emulate a smaller device (no root needed).
// Usage (adb shell): ./memhold <MB> [chunkMB]   e.g. ./memhold 8192
// Allocates MB megabytes in chunks and fills every page with pseudo-random bytes, so zram
// (compressed swap) cannot shrink it back; then holds it until killed (SIGTERM/SIGINT) and
// prints progress. Stops early and keeps what it has if an allocation fails.
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
static volatile sig_atomic_t stop = 0;
static void on_sig(int s) { (void)s; stop = 1; }
int main(int argc, char **argv) {
  long total = argc > 1 ? atol(argv[1]) : 8192, chunk = argc > 2 ? atol(argv[2]) : 256, held = 0;
  signal(SIGTERM, on_sig); signal(SIGINT, on_sig);
  uint64_t x = 0x9E3779B97F4A7C15ULL;
  static uint64_t *chunks[1024]; long nchunks = 0;
  while (held < total && !stop) {
    size_t n = (size_t)chunk << 20; uint64_t *p = malloc(n);
    if (!p) { fprintf(stderr, "alloc failed at %ld MB\n", held); break; }
    for (size_t i = 0; i < n / 8; i++) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; p[i] = x; }   // incompressible
    chunks[nchunks++] = p; held += chunk; printf("held %ld MB\n", held); fflush(stdout);
  }
  printf("holding %ld MB (pid %d)\n", held, getpid()); fflush(stdout);
  // keep-warm: read one word per 4 KB page every second so the pages stay on the active LRU list and the
  // kernel does not push them to (vivo "extended RAM") swap, which would silently hand the RAM back.
  volatile uint64_t sink = 0;
  while (!stop) {
    for (long c = 0; c < nchunks && !stop; c++)
      for (size_t i = 0; i < ((size_t)chunk << 20) / 8; i += 512) sink += chunks[c][i];
    sleep(1);
  }
  printf("released\n"); return 0;
}
