package com.tetonova.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tetonova.app.R

@DrawableRes
fun cultivationPathArtworkRes(pathId: String?, realmId: String? = null): Int = when {
    realmId?.startsWith("g") == true -> R.drawable.cultivation_path_great_thousand_v1
    pathId == "martial" -> R.drawable.cultivation_path_martial_v1
    else -> R.drawable.cultivation_path_dou_qi_v1
}

@Composable
fun CultivationPathArtwork(
    pathId: String?,
    realmId: String? = null,
    locked: Boolean = false,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(cultivationPathArtworkRes(pathId, realmId)),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = if (locked) 0.34f else 1f,
        colorFilter = if (locked) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
    )
}

@DrawableRes
fun achievementArtworkRes(achievementId: String): Int? = when (achievementId) {
    "dq_spark" -> R.drawable.achievement_dq_spark_v1
    "dq_king_wings" -> R.drawable.achievement_dq_king_wings_v1
    "dq_space_splitter" -> R.drawable.achievement_dq_space_splitter_v1
    "dq_saint_path" -> R.drawable.achievement_dq_saint_path_v1
    "dq_dou_emperor" -> R.drawable.achievement_dq_dou_emperor_v1
    "mu_yuan_core" -> R.drawable.achievement_mu_yuan_core_v1
    "mu_manifestation" -> R.drawable.achievement_mu_manifestation_v1
    "mu_nine_tribulations" -> R.drawable.achievement_mu_nine_tribulations_v1
    "mu_samsara" -> R.drawable.achievement_mu_samsara_v1
    "mu_martial_ancestor" -> R.drawable.achievement_mu_martial_ancestor_v1
    "gt_beyond_lower_world" -> R.drawable.achievement_gt_beyond_lower_world_v1
    "gt_true_sovereign" -> R.drawable.achievement_gt_true_sovereign_v1
    "gt_heaven_earth" -> R.drawable.achievement_gt_heaven_earth_v1
    "gt_sky_record" -> R.drawable.achievement_gt_sky_record_v1
    "gt_great_ruler" -> R.drawable.achievement_gt_great_ruler_v1
    else -> null
}

@Composable
fun AchievementBadgeArtwork(
    achievementId: String,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val resource = achievementArtworkRes(achievementId) ?: return
    Image(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = if (unlocked) 1f else 0.32f,
        colorFilter = if (unlocked) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
    )
}
