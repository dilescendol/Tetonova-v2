package com.tetonova.app.data

/** One pending cultivation result, merged while the player remains open. */
internal data class CultivationLevelUpEvent(
    val fromLevel: Int,
    val level: Int,
    val realmName: String,
    val realmChanged: Boolean,
) {
    val levelsGained: Int get() = (level - fromLevel).coerceAtLeast(1)
}

internal fun mergeCultivationLevelUpEvent(
    pending: CultivationLevelUpEvent?,
    previousLevel: Int,
    newLevel: Int,
    realmName: String,
    realmChanged: Boolean,
): CultivationLevelUpEvent? {
    if (newLevel <= previousLevel) return pending
    return CultivationLevelUpEvent(
        fromLevel = pending?.fromLevel ?: previousLevel,
        level = maxOf(pending?.level ?: newLevel, newLevel),
        realmName = realmName,
        realmChanged = pending?.realmChanged == true || realmChanged,
    )
}
