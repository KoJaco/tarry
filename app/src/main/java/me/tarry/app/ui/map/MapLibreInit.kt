package me.tarry.app.ui.map

import android.content.Context
import me.tarry.app.BuildConfig
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * One-time MapLibre setup, done lazily on the first map so app start pays nothing. Tiles, glyphs and sprites are
 * the only network traffic (AGENTS.md rule 2). MapLibre Native has no telemetry. OpenFreeMap asks for a unique
 * User-Agent and at most [MaxParallelRequests] requests at a time.
 */
object MapLibreInit {
    const val MaxParallelRequests = 4
    val UserAgent = "Tarry/${BuildConfig.VERSION_NAME} (me.tarry.app; personal, on-device)"

    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            MapLibre.getInstance(context.applicationContext)
            // Native logs can include tile URLs (z/x/y ≈ where the user looked); keep them out of release logcat.
            if (!BuildConfig.DEBUG) Logger.setVerbosity(Logger.NONE)
            val dispatcher = Dispatcher().apply {
                maxRequests = MaxParallelRequests
                maxRequestsPerHost = MaxParallelRequests
            }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", UserAgent).build())
                }
                .build()
            HttpRequestUtil.setOkHttpClient(client)
            done = true
        }
    }
}
