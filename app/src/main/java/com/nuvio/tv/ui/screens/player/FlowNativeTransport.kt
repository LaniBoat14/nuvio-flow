package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FlowHttpEngineDataSource
import java.util.concurrent.Executors

/** One shared native engine for progressive media; each source opens one request. */
@UnstableApi
internal object FlowNativeTransport {
    fun factory(context: Context, headers: Map<String, String>): DataSource.Factory? {
        if (Build.VERSION.SDK_INT < 34) return null
        return runCatching { Native.factory(context.applicationContext, headers) }.getOrNull()
    }

    @RequiresApi(34)
    private object Native {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlowHttpEngine").apply { isDaemon = true }
        }
        private var engine: HttpEngine? = null

        @Synchronized
        fun factory(context: Context, headers: Map<String, String>): DataSource.Factory {
            val shared = engine ?: HttpEngine.Builder(context)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .build().also { engine = it }
            val factory = FlowHttpEngineDataSource.Factory(shared, executor)
                .setConnectionTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                .setDefaultRequestProperties(headers.filterKeys { !it.equals("Range", true) })
            return DataSource.Factory {
                val source=factory.createDataSource().apply { addTransferListener(FlowTransferMeter) }
                FlowObservedConnection(source,FlowConnectionInfo()) { details ->
                    details.protocol=source.negotiatedProtocol.takeIf{it.isNotBlank()}
                    details.peer=null // The public native response API does not expose the connected IP.
                }
            }
        }
    }
}
