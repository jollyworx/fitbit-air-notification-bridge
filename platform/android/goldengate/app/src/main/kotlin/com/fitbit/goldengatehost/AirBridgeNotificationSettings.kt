package com.fitbit.goldengatehost

import android.content.Context

enum class AirHapticPattern(
    val wireName: String,
    val displayName: String,
    /** Pause after each completed toggle/restore group before starting the next one. */
    val betweenGroupDelaysMillis: List<Long>
) {
    SINGLE("single", "Eén trilgroep", emptyList()),
    DOUBLE("double", "Twee trilgroepen", listOf(650L)),
    TRIPLE("triple", "Drie trilgroepen", listOf(650L, 650L)),
    LONG_GAP("long_gap", "Twee trilgroepen met langere pauze", listOf(1_800L)),
    URGENT("urgent", "Dringend trilpatroon", listOf(450L, 1_350L, 450L));

    val groupCount: Int
        get() = betweenGroupDelaysMillis.size + 1

    companion object {
        fun fromWireName(value: String): AirHapticPattern? = values().firstOrNull {
            it.wireName.equals(value.trim(), ignoreCase = true)
        }
    }
}

data class AirNotificationRule(
    val packageName: String,
    val pattern: AirHapticPattern
)

data class AirNotificationTrigger(
    val sourcePackage: String,
    val pattern: AirHapticPattern
)

data class AirNotificationRuleParseResult(
    val rules: List<AirNotificationRule>,
    val errors: List<String>
) {
    val isValid: Boolean
        get() = errors.isEmpty() && rules.isNotEmpty()
}

object AirBridgeNotificationSettings {
    private const val PREFS_NAME = "air_notification_bridge"
    private const val KEY_RULES = "notification_rules"
    const val MAX_RULES = 5
    const val DEFAULT_RULES_TEXT = "com.tencent.mm=single"

    private val packageNameRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun loadText(context: Context): String = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_RULES, DEFAULT_RULES_TEXT)
        ?: DEFAULT_RULES_TEXT

    fun saveRules(context: Context, rules: List<AirNotificationRule>) {
        require(rules.isNotEmpty()) { "At least one notification rule is required" }
        require(rules.size <= MAX_RULES) { "At most $MAX_RULES notification rules are supported" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, normalizeRules(rules))
            .apply()
    }

    fun loadRules(context: Context): List<AirNotificationRule> {
        val parsed = parseRules(loadText(context))
        return if (parsed.isValid) parsed.rules else parseRules(DEFAULT_RULES_TEXT).rules
    }

    fun findRule(context: Context, packageName: String): AirNotificationRule? =
        loadRules(context).firstOrNull { it.packageName == packageName }

    fun parseRules(value: String): AirNotificationRuleParseResult {
        val lines = value.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val errors = mutableListOf<String>()
        val rules = mutableListOf<AirNotificationRule>()
        val seenPackages = mutableSetOf<String>()

        if (lines.isEmpty()) errors += "Stel minstens één app in."
        if (lines.size > MAX_RULES) errors += "Je kunt maximaal $MAX_RULES apps instellen."

        lines.take(MAX_RULES).forEachIndexed { index, line ->
            val parts = line.split('=', limit = 2).map(String::trim)
            if (parts.size != 2) {
                errors += "Regel ${index + 1}: gebruik pakketnaam=patroon."
                return@forEachIndexed
            }
            val packageName = parts[0]
            val pattern = AirHapticPattern.fromWireName(parts[1])
            when {
                !packageNameRegex.matches(packageName) ->
                    errors += "Regel ${index + 1}: ongeldige pakketnaam: $packageName"
                !seenPackages.add(packageName) ->
                    errors += "Regel ${index + 1}: dubbele regel voor $packageName"
                pattern == null ->
                    errors += "Regel ${index + 1}: ongeldig patroon: ${parts[1]}"
                else -> rules += AirNotificationRule(packageName, pattern)
            }
        }
        return AirNotificationRuleParseResult(rules, errors)
    }

    fun normalizeRules(rules: List<AirNotificationRule>): String = rules.joinToString("\n") {
        "${it.packageName}=${it.pattern.wireName}"
    }
}
