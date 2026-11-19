package net.activitywatch.android.ledger

/** Opt-in only relaxes the incognito label gate; all other capture gates stay in ContentService. */
internal object ContentPrivacy {
    private val sensitive = Regex("PRIVATE_TEST|密码|验证码|password|credit.card", RegexOption.IGNORE_CASE)
    private val incognito = Regex("incognito|无痕|隐身", RegexOption.IGNORE_CASE)
    fun blocks(labels: String, allowIncognito: Boolean = false): Boolean =
        sensitive.containsMatchIn(labels) || (!allowIncognito && incognito.containsMatchIn(labels))
}
