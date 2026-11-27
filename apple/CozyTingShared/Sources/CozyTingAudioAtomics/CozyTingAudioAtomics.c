#include "CozyTingAudioAtomics.h"
#include <stdatomic.h>

void ct_atomic_u64_init(CTAtomicUInt64 *atomic, uint64_t initial) {
    atomic_init(&atomic->raw, initial);
}

uint64_t ct_atomic_u64_load_relaxed(CTAtomicUInt64 *atomic) {
    return atomic_load_explicit(&atomic->raw, memory_order_relaxed);
}

uint64_t ct_atomic_u64_load_acquire(CTAtomicUInt64 *atomic) {
    return atomic_load_explicit(&atomic->raw, memory_order_acquire);
}

void ct_atomic_u64_store_relaxed(CTAtomicUInt64 *atomic, uint64_t value) {
    atomic_store_explicit(&atomic->raw, value, memory_order_relaxed);
}

void ct_atomic_u64_store_release(CTAtomicUInt64 *atomic, uint64_t value) {
    atomic_store_explicit(&atomic->raw, value, memory_order_release);
}

uint64_t ct_atomic_u64_fetch_add_relaxed(CTAtomicUInt64 *atomic, uint64_t delta) {
    return atomic_fetch_add_explicit(&atomic->raw, delta, memory_order_relaxed);
}

void ct_atomic_bool_init(CTAtomicBool *atomic, bool initial) {
    atomic_init(&atomic->raw, initial ? 1 : 0);
}

bool ct_atomic_bool_load_acquire(CTAtomicBool *atomic) {
    return atomic_load_explicit(&atomic->raw, memory_order_acquire) != 0;
}

void ct_atomic_bool_store_release(CTAtomicBool *atomic, bool value) {
    atomic_store_explicit(&atomic->raw, value ? 1 : 0, memory_order_release);
}
