package me.tarry.app.ui.about

import androidx.annotation.StringRes
import me.tarry.app.R

/** One row of the open-source licences list. [assetFile] lives in `assets/licenses/`. */
data class OssNotice(
    @StringRes val nameRes: Int,
    @StringRes val subtitleRes: Int,
    val assetFile: String,
)

/** Every third-party notice shown in the app. Phase 8b adds Play Billing here. */
val OssNotices: List<OssNotice> = listOf(
    OssNotice(R.string.licences_nunito, R.string.licences_ofl, "OFL-Nunito.txt"),
    OssNotice(R.string.licences_nunito_sans, R.string.licences_ofl, "OFL-NunitoSans.txt"),
    OssNotice(R.string.licences_maplibre, R.string.licences_bsd2, "BSD-2-MapLibre.txt"),
    OssNotice(R.string.licences_osm, R.string.licences_odbl, "ODbL-notice.txt"),
    OssNotice(R.string.licences_android_libs, R.string.licences_apache_libs, "Apache-2.0.txt"),
)
