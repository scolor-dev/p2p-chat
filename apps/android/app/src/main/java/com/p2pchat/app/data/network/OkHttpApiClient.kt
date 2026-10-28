package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** OkHttp を使う実装。通信はメインスレッドでできないので、[ioDispatcher] で行う。 */
class OkHttpApiClient(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApiClient {

    override suspend fun healthz(server: ServerSettings): Result<Unit> = withContext(ioDispatcher) {
        try {
            val request = Request.Builder().url("${server.httpBaseUrl}/healthz").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 到達できない、タイムアウト、URL が不正（IllegalArgumentException）など
            Result.failure(e)
        }
    }
}
