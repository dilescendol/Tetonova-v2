package com.tetonova.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class MatureAgeResult {
    VALID,
    UNDERAGE,
    INVALID,
}

private val matureBirthDateFormatter = DateTimeFormatter
    .ofPattern("dd/MM/uuuu")
    .withResolverStyle(ResolverStyle.STRICT)

fun formatMatureBirthDateInput(raw: String): String {
    val digits = raw.filter(Char::isDigit).take(8)
    return buildString {
        append(digits.take(2))
        if (digits.length > 2) {
            append('/')
            append(digits.substring(2, minOf(4, digits.length)))
        }
        if (digits.length > 4) {
            append('/')
            append(digits.substring(4))
        }
    }
}

fun validateMatureBirthDate(raw: String, today: LocalDate = LocalDate.now()): MatureAgeResult {
    val birthDate = runCatching { LocalDate.parse(raw, matureBirthDateFormatter) }.getOrNull()
        ?: return MatureAgeResult.INVALID
    if (birthDate.isAfter(today) || birthDate.isBefore(today.minusYears(120))) {
        return MatureAgeResult.INVALID
    }
    return if (birthDate.isAfter(today.minusYears(18))) {
        MatureAgeResult.UNDERAGE
    } else {
        MatureAgeResult.VALID
    }
}
