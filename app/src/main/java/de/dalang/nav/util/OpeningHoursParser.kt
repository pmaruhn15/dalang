package de.dalang.nav.util

import de.dalang.nav.util.CrashLogger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parser für OSM Opening Hours Format
 * Unterstützt gängige Formate wie:
 * - "24/7"
 * - "Mo-Fr 06:00-22:00"
 * - "Mo-Fr 06:00-22:00; Sa-Su 08:00-20:00"
 */
object OpeningHoursParser {

    data class OpenStatus(
        val isOpenNow: Boolean,
        val closesAt: LocalTime?,        // Wann schließt es heute?
        val opensAt: LocalTime?,         // Wann öffnet es heute (falls noch geschlossen)?
        val isOpen24h: Boolean = false,
        val willBeOpenAtArrival: Boolean = true,  // Wird es bei Ankunft noch offen sein?
        val displayText: String          // Anzeigetext für UI
    )

    /**
     * Prüft ob ein POI bei Ankunft noch offen ist
     * @param openingHours OSM opening_hours String
     * @param arrivalMinutes Geschätzte Ankunftszeit in Minuten
     * @return OpenStatus mit allen relevanten Infos
     */
    fun checkOpenStatus(openingHours: String?, arrivalMinutes: Int): OpenStatus {
        if (openingHours.isNullOrBlank()) {
            return OpenStatus(
                isOpenNow = true,  // Unbekannt = annehmen dass offen
                closesAt = null,
                opensAt = null,
                willBeOpenAtArrival = true,
                displayText = ""  // Nichts anzeigen wenn unbekannt
            )
        }

        val now = LocalTime.now()
        val today = LocalDate.now().dayOfWeek
        val arrivalTime = now.plusMinutes(arrivalMinutes.toLong())

        // 24/7 - immer offen
        if (openingHours.contains("24/7") || openingHours.contains("24 hours")) {
            return OpenStatus(
                isOpenNow = true,
                closesAt = null,
                opensAt = null,
                isOpen24h = true,
                willBeOpenAtArrival = true,
                displayText = "24h geöffnet"
            )
        }

        // Parse opening hours für heute
        val todayHours = parseTodayHours(openingHours, today)

        if (todayHours == null) {
            // Heute geschlossen (z.B. Feiertag oder Ruhetag)
            // Prüfe wann es wieder öffnet (morgen?)
            val tomorrowDay = today.plus(1)
            val tomorrowHours = parseTodayHours(openingHours, tomorrowDay)
            val displayText = if (tomorrowHours != null) {
                val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
                "Geschl. · Morgen ab ${tomorrowHours.first.format(timeFormat)}"
            } else {
                "Heute geschlossen"
            }
            return OpenStatus(
                isOpenNow = false,
                closesAt = null,
                opensAt = null,
                willBeOpenAtArrival = false,
                displayText = displayText
            )
        }

        val (openTime, closeTime) = todayHours

        // Prüfe ob Öffnungszeiten über Mitternacht gehen (z.B. 07:00-01:00)
        val isOvernight = closeTime.isBefore(openTime)

        val isOpenNow = if (isOvernight) {
            // Über Mitternacht: offen wenn nach openTime ODER vor closeTime
            now.isAfter(openTime) || now.isBefore(closeTime)
        } else {
            // Normal: offen wenn zwischen openTime und closeTime
            now.isAfter(openTime) && now.isBefore(closeTime)
        }

        val willBeOpenAtArrival = if (isOvernight) {
            arrivalTime.isAfter(openTime) || arrivalTime.isBefore(closeTime)
        } else {
            arrivalTime.isAfter(openTime) && arrivalTime.isBefore(closeTime)
        }

        // Nächste Öffnungszeit ermitteln (für morgen)
        val tomorrowDay = today.plus(1)
        val tomorrowHours = parseTodayHours(openingHours, tomorrowDay)
        val nextOpenTime = tomorrowHours?.first

        // Anzeigetext erstellen
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        val displayText = when {
            !isOpenNow && (now.isBefore(openTime) || (isOvernight && now.isAfter(closeTime))) -> {
                // Noch nicht geöffnet (vor Öffnung oder nach Nachtschluss)
                if (willBeOpenAtArrival) {
                    "Öffnet ${openTime.format(timeFormat)}"
                } else {
                    "Öffnet ${openTime.format(timeFormat)} ⚠️"
                }
            }
            !isOpenNow -> {
                // Geschlossen (nach regulärer Schließzeit)
                "Geschl. · Öffnet ${openTime.format(timeFormat)}"
            }
            !willBeOpenAtArrival -> {
                // Jetzt offen, aber bei Ankunft geschlossen
                "Schließt ${closeTime.format(timeFormat)} ⚠️"
            }
            else -> {
                // Offen und wird bei Ankunft noch offen sein
                "Bis ${closeTime.format(timeFormat)} geöffnet"
            }
        }

        return OpenStatus(
            isOpenNow = isOpenNow,
            closesAt = closeTime,
            opensAt = openTime,
            willBeOpenAtArrival = willBeOpenAtArrival,
            displayText = displayText
        )
    }

    /**
     * Parsed die Öffnungszeiten für den heutigen Wochentag
     * @return Pair<OpenTime, CloseTime> oder null wenn geschlossen
     */
    private fun parseTodayHours(openingHours: String, today: DayOfWeek): Pair<LocalTime, LocalTime>? {
        try {
            // Normalisieren
            val normalized = openingHours
                .replace("–", "-")
                .replace("—", "-")
                .trim()

            // Auf Semikolon splitten für mehrere Regeln
            val rules = normalized.split(";").map { it.trim() }

            for (rule in rules) {
                // PH (Public Holiday) überspringen - konservativ annehmen dass geschlossen
                if (rule.contains("PH") && rule.contains("off", ignoreCase = true)) {
                    continue
                }

                // Prüfen ob diese Regel für heute gilt
                val matchResult = parseRuleForToday(rule, today)
                if (matchResult != null) {
                    return matchResult
                }
            }

            // Fallback: Erste Regel mit Zeitangabe verwenden
            for (rule in rules) {
                val times = extractTimes(rule)
                if (times != null) {
                    return times
                }
            }

            return null
        } catch (e: Exception) {
            CrashLogger.logError("OpeningHoursParser", "Failed to parse: $openingHours", e)
            return null
        }
    }

    private fun parseRuleForToday(rule: String, today: DayOfWeek): Pair<LocalTime, LocalTime>? {
        val dayAbbreviations = mapOf(
            "Mo" to DayOfWeek.MONDAY,
            "Tu" to DayOfWeek.TUESDAY,
            "We" to DayOfWeek.WEDNESDAY,
            "Th" to DayOfWeek.THURSDAY,
            "Fr" to DayOfWeek.FRIDAY,
            "Sa" to DayOfWeek.SATURDAY,
            "Su" to DayOfWeek.SUNDAY
        )

        // Prüfen ob "off" oder "closed"
        if (rule.contains("off", ignoreCase = true) || rule.contains("closed", ignoreCase = true)) {
            // Prüfen ob heute betroffen
            for ((abbr, day) in dayAbbreviations) {
                if (rule.contains(abbr) && day == today) {
                    return null  // Heute geschlossen
                }
            }
        }

        // Tagesbereich finden (z.B. "Mo-Fr" oder "Sa-Su" oder "Mo,We,Fr")
        val dayRangeRegex = Regex("([A-Za-z]{2})(?:-([A-Za-z]{2}))?(?:,([A-Za-z]{2})(?:-([A-Za-z]{2}))?)*")
        val dayMatch = dayRangeRegex.find(rule)

        if (dayMatch != null) {
            val dayPart = dayMatch.value
            val appliesToday = checkDayApplies(dayPart, today, dayAbbreviations)

            if (appliesToday) {
                return extractTimes(rule)
            }
        }

        return null
    }

    private fun checkDayApplies(dayPart: String, today: DayOfWeek, dayAbbreviations: Map<String, DayOfWeek>): Boolean {
        // Einzelne Tage oder Bereiche parsen
        val parts = dayPart.split(",")

        for (part in parts) {
            if (part.contains("-")) {
                // Bereich wie "Mo-Fr"
                val range = part.split("-")
                if (range.size == 2) {
                    val startDay = dayAbbreviations[range[0].trim()]
                    val endDay = dayAbbreviations[range[1].trim()]

                    if (startDay != null && endDay != null) {
                        if (isDayInRange(today, startDay, endDay)) {
                            return true
                        }
                    }
                }
            } else {
                // Einzelner Tag wie "Sa"
                val day = dayAbbreviations[part.trim()]
                if (day == today) {
                    return true
                }
            }
        }

        return false
    }

    private fun isDayInRange(day: DayOfWeek, start: DayOfWeek, end: DayOfWeek): Boolean {
        val dayValue = day.value
        val startValue = start.value
        val endValue = end.value

        return if (startValue <= endValue) {
            dayValue in startValue..endValue
        } else {
            // Überlauf (z.B. Fr-Mo)
            dayValue >= startValue || dayValue <= endValue
        }
    }

    private fun extractTimes(rule: String): Pair<LocalTime, LocalTime>? {
        // Zeit-Pattern: HH:MM-HH:MM
        val timeRegex = Regex("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})")
        val match = timeRegex.find(rule) ?: return null

        return try {
            val openStr = match.groupValues[1]
            val closeStr = match.groupValues[2]

            val formatter = DateTimeFormatter.ofPattern("H:mm")
            val openTime = LocalTime.parse(openStr, formatter)
            val closeTime = LocalTime.parse(closeStr, formatter)

            Pair(openTime, closeTime)
        } catch (e: Exception) {
            null
        }
    }
}
