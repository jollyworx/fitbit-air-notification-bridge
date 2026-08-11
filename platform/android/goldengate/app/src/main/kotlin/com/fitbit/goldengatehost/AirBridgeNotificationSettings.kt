package com.fitbit.goldengatehost

import android.content.Context

enum class AirHapticPattern(
    val wireName: String,
    val displayName: String,
    /** Pause after each completed toggle/restore group before starting the next one. */
    val betweenGroupDelaysMillis: List<Long>
) {
    SINGLE("single", "单组", emptyList()),
    DOUBLE("double", "双组", listOf(650L)),
    TRIPLE("triple", "三组", listOf(650L, 650L)),
    LONG_GAP("long_gap", "长间隔双组", listOf(1_800L)),
    URGENT("urgent", "紧急节奏", listOf(450L, 1_350L, 450L));

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

        if (lines.isEmpty()) errors += "至少配置一个应用。"
        if (lines.size > MAX_RULES) errors += "最多只能配置 $MAX_RULES 个应用。"

        lines.take(MAX_RULES).forEachIndexed { index, line ->
            val parts = line.split('=', limit = 2).map(String::trim)
            if (parts.size != 2) {
                errors += "第 ${index + 1} 行格式应为：应用包名=pattern。"
                return@forEachIndexed
            }
            val packageName = parts[0]
            val pattern = AirHapticPattern.fromWireName(parts[1])
            when {
                !packageNameRegex.matches(packageName) ->
                    errors += "第 ${index + 1} 行不是有效的应用包名：$packageName"
                !seenPackages.add(packageName) ->
                    errors += "第 ${index + 1} 行重复配置了：$packageName"
                pattern == null ->
                    errors += "第 ${index + 1} 行 pattern 无效：${parts[1]}"
                else -> rules += AirNotificationRule(packageName, pattern)
            }
        }
        return AirNotificationRuleParseResult(rules, errors)
    }

    fun normalizeRules(rules: List<AirNotificationRule>): String = rules.joinToString("\n") {
        "${it.packageName}=${it.pattern.wireName}"
    }
}
