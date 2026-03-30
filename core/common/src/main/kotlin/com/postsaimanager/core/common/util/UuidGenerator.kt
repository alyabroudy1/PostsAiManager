package com.postsaimanager.core.common.util

import java.util.UUID

/**
 * Generates UUIDs for entity primary keys.
 * Centralized to enable deterministic generation in tests.
 */
object UuidGenerator {
    fun generate(): String = UUID.randomUUID().toString()
}
