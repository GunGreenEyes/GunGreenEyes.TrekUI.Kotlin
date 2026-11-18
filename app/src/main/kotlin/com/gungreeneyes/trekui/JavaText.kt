package com.gungreeneyes.trekui

/**
 * `java.lang.String.trim()`: strips every character up to U+0020 from both ends. Kotlin's
 * [trim] strips Unicode whitespace instead, so labels and names converted from the Java edition use
 * this to keep exactly the same results.
 */
internal fun String.javaTrim(): String = trim { it <= ' ' }
