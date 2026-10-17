package dev.p3view

import java.util.Locale

/** String.format with a fixed locale, so decimals always use a dot whatever the phone's language. */
fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, pattern, *args)
