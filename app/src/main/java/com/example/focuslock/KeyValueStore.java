package com.example.focuslock;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal storage abstraction so persistence rules can be tested on the JVM.
 * apply() must be atomic: either every put/remove is visible afterwards, or none is.
 */
interface KeyValueStore {
    String get(String key);

    /** @return true when the whole change was committed durably. */
    boolean apply(Map<String, String> puts, Collection<String> removes);
}
