package com.tetonova.app.data

internal fun isMandatoryUpdateRequired(
    currentVersionCode: Int,
    minimumVersionCode: Int,
): Boolean = minimumVersionCode > 0 && currentVersionCode < minimumVersionCode
