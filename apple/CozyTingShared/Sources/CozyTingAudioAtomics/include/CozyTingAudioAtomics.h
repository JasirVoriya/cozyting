#ifndef COZYTING_AUDIO_ATOMICS_H
#define COZYTING_AUDIO_ATOMICS_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    _Atomic uint64_t raw;
} CTAtomicUInt64;

typedef struct {
    _Atomic uint8_t raw;
} CTAtomicBool;

void ct_atomic_u64_init(CTAtomicUInt64 *atomic, uint64_t initial);
uint64_t ct_atomic_u64_load_relaxed(CTAtomicUInt64 *atomic);
uint64_t ct_atomic_u64_load_acquire(CTAtomicUInt64 *atomic);
void ct_atomic_u64_store_relaxed(CTAtomicUInt64 *atomic, uint64_t value);
void ct_atomic_u64_store_release(CTAtomicUInt64 *atomic, uint64_t value);
uint64_t ct_atomic_u64_fetch_add_relaxed(CTAtomicUInt64 *atomic, uint64_t delta);

void ct_atomic_bool_init(CTAtomicBool *atomic, bool initial);
bool ct_atomic_bool_load_acquire(CTAtomicBool *atomic);
void ct_atomic_bool_store_release(CTAtomicBool *atomic, bool value);

#endif
