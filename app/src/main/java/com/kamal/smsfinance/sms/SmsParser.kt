package com.kamal.smsfinance.sms

import com.kamal.smsfinance.data.TransactionType

/** Intermediate result of parsing one SMS, before it becomes a Room Transaction. */
data class ParsedSms(
    val sender: String,
    val amountToman: Long,
    val type: TransactionType,
    val bankName: String,
    val description: String,
    val timestamp: Long,
    val rawSms: String,
    val accountTail: String?
)

/**
 * Parses Iranian bank SMS messages into structured transaction data.
 *
 * Coverage notes:
 * - Bank identification is done first from the sender number / known sender
 *   name patterns, then confirmed from body keywords, since many banks share
 *   short-code ranges or get spoofed under generic names.
 * - Amount extraction supports both "تومان" and "ریال" (auto-converted /10),
 *   with or without thousands separators (٬ , or normal comma), and both
 *   Persian and Latin digits.
 * - Type (expense/income) is inferred from a keyword table; ambiguous or
 *   promotional messages ("تخفیف", "تبلیغ") are rejected outright so they
 *   never get filed as false transactions.
 */
object SmsParser {

    // Sender short-codes / names, per bank. These are the most common ones;
    // extend freely as new bank sender IDs are observed on-device.
    private val BANK_SENDERS = mapOf(
        "ملت" to listOf("Mellat", "MELLAT", "ملت", "10000210", "10000211"),
        "سپه" to listOf("Sepah", "SEPAH", "سپه", "10009999", "10000155"),
        "پاسارگاد" to listOf("Pasargad", "PASARGAD", "پاسارگاد", "10000068", "500068"),
        "سامان" to listOf("Saman", "SAMAN", "سامان", "10000770", "10005010"),
        "ملی" to listOf("BMI", "10000019", "ملی ایران", "بانک ملی"),
        "تجارت" to listOf("Tejarat", "10000017", "تجارت"),
        "صادرات" to listOf("Saderat", "10000019", "صادرات"),
        "کشاورزی" to listOf("Keshavarzi", "10000160", "کشاورزی"),
        "رفاه" to listOf("Refah", "10000144", "رفاه کارگران"),
        "اقتصاد نوین" to listOf("EN Bank", "10000079", "اقتصادنوین"),
        "پارسیان" to listOf("Parsian", "10000622", "پارسیان"),
        "آینده" to listOf("Ayandeh", "10008485", "آینده"),
        "شهر" to listOf("City Bank", "10004555", "بانک شهر"),
        "دی" to listOf("Day Bank", "10009898", "بانک دی"),
        "کارآفرین" to listOf("Karafarin", "10008717", "کارآفرین"),
        "مسکن" to listOf("Maskan", "10000129", "مسکن")
    )

    // Keyword -> transaction type. Order matters: more specific phrases first.
    private val EXPENSE_KEYWORDS = listOf(
        "پرداخت قسط", "خرید", "برداشت", "کارمزد", "هزینه", "پرداخت اینترنتی",
        "پرداخت شد", "انتقال به", "کسر از", "چک", "قبض"
    )
    private val INCOME_KEYWORDS = listOf(
        "واریز", "دریافت", "واریزی", "به حساب شما", "بازگشت وجه", "سود سپرده"
    )

    // Messages that only mention balance / OTP / promos, never a real txn.
    private val IGNORE_KEYWORDS = listOf(
        "موجودی شما", "رمز یکبار مصرف", "کد تایید", "تخفیف", "جشنواره",
        "تبلیغ", "کد فعال", "OTP"
    )

    private val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    // Matches amounts like: "مبلغ: 500,000 تومان", "500000 ريال", "1,250,000ریال"
    // Supports Persian thousands separator ٬ and plain , as well as Persian digits.
    private val AMOUNT_REGEX = Regex(
        """([\d۰-۹][\d۰-۹,٬./]*)\s*(تومان|ریال|ريال|Rials?|Toman)""",
        RegexOption.IGNORE_CASE
    )

    // Fallback: a bare number of 5+ digits immediately followed by common
    // currency-less bank phrasing ("مبلغ 500000 از").
    private val BARE_AMOUNT_REGEX = Regex("""مبلغ[:\s]*([\d۰-۹][\d۰-۹,٬]*)""")

    // Card/account tail, e.g. "...1234" or "حساب ****1234"
    private val TAIL_REGEX = Regex("""[*x]{2,}(\d{4})""")

    fun parse(sender: String, body: String, timestamp: Long): ParsedSms? {
        if (body.isBlank()) return null
        if (IGNORE_KEYWORDS.any { body.contains(it) }) return null

        val bank = identifyBank(sender, body) ?: return null
        val type = identifyType(body) ?: return null
        val amount = extractAmountToman(body) ?: return null
        if (amount <= 0) return null

        val tail = TAIL_REGEX.find(body)?.groupValues?.get(1)
        val description = buildDescription(body, type)

        return ParsedSms(
            sender = sender,
            amountToman = amount,
            type = type,
            bankName = bank,
            description = description,
            timestamp = timestamp,
            rawSms = body,
            accountTail = tail
        )
    }

    private fun identifyBank(sender: String, body: String): String? {
        for ((bankName, identifiers) in BANK_SENDERS) {
            if (identifiers.any { sender.contains(it, ignoreCase = true) }) return bankName
        }
        // Fall back to scanning the body text itself for a bank name mention.
        for ((bankName, identifiers) in BANK_SENDERS) {
            if (identifiers.any { it.length > 3 && body.contains(it, ignoreCase = true) }) return bankName
        }
        // Unknown sender: only accept if the body clearly looks like a bank
        // transaction (has an amount + a strong keyword), otherwise skip to
        // avoid false positives from random SMS.
        val looksLikeBankMsg = (EXPENSE_KEYWORDS + INCOME_KEYWORDS).any { body.contains(it) } &&
            AMOUNT_REGEX.containsMatchIn(body)
        return if (looksLikeBankMsg) "نامشخص" else null
    }

    private fun identifyType(body: String): TransactionType? {
        val hasExpense = EXPENSE_KEYWORDS.any { body.contains(it) }
        val hasIncome = INCOME_KEYWORDS.any { body.contains(it) }
        return when {
            hasIncome && !hasExpense -> TransactionType.INCOME
            hasExpense && !hasIncome -> TransactionType.EXPENSE
            // Both matched (e.g. "کارمزد" inside a deposit message) -- prefer
            // whichever keyword appears first in the text, it's usually the
            // primary action.
            hasExpense && hasIncome -> {
                val expenseIdx = EXPENSE_KEYWORDS.minOf { kw -> body.indexOf(kw).let { if (it < 0) Int.MAX_VALUE else it } }
                val incomeIdx = INCOME_KEYWORDS.minOf { kw -> body.indexOf(kw).let { if (it < 0) Int.MAX_VALUE else it } }
                if (expenseIdx <= incomeIdx) TransactionType.EXPENSE else TransactionType.INCOME
            }
            else -> null
        }
    }

    private fun extractAmountToman(body: String): Long? {
        val match = AMOUNT_REGEX.find(body)
        if (match != null) {
            val (numberRaw, unit) = match.destructured
            val number = normalizeNumber(numberRaw) ?: return null
            return if (unit.startsWith("ری", ignoreCase = true) || unit.startsWith("Rial", ignoreCase = true)) {
                number / 10 // Rial -> Toman
            } else {
                number
            }
        }
        val bareMatch = BARE_AMOUNT_REGEX.find(body)
        if (bareMatch != null) {
            return normalizeNumber(bareMatch.groupValues[1])
        }
        return null
    }

    private fun normalizeNumber(raw: String): Long? {
        val converted = raw.map { ch ->
            val idx = PERSIAN_DIGITS.indexOf(ch)
            if (idx >= 0) ('0' + idx) else ch
        }.joinToString("")
        val digitsOnly = converted.filter { it.isDigit() }
        return digitsOnly.toLongOrNull()
    }

    private fun buildDescription(body: String, type: TransactionType): String {
        // Take a short, human-readable slice around the matched keyword so the
        // list screen shows something meaningful instead of the full SMS.
        val keyword = (if (type == TransactionType.EXPENSE) EXPENSE_KEYWORDS else INCOME_KEYWORDS)
            .firstOrNull { body.contains(it) }
        val trimmed = body.replace(Regex("\\s+"), " ").trim()
        return if (keyword != null && trimmed.length > 60) {
            val idx = trimmed.indexOf(keyword).coerceAtLeast(0)
            val start = (idx - 15).coerceAtLeast(0)
            val end = (idx + 45).coerceAtMost(trimmed.length)
            "…" + trimmed.substring(start, end) + "…"
        } else {
            trimmed.take(80)
        }
    }
}
