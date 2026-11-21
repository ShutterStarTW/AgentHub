package com.shutterstar.agenthub.usage.discovery

/** Sanitizes the provider-supplied title of a saved reset before it is shown. */
internal object ResetCreditText {
    fun title(raw: String?): String? = raw?.trim()
        ?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }
        ?.take(MAX_LENGTH)

    private const val MAX_LENGTH = 64
}
