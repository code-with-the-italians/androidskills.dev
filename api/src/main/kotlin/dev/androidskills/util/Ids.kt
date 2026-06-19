package dev.androidskills.util

import java.util.UUID

/** Generates a UUIDv4 string, the ID format for all TEXT-primary-key tables (spec §4). */
fun newId(): String = UUID.randomUUID().toString()
