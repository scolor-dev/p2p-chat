package com.p2pchat.app.data.network

import com.p2pchat.app.data.settings.ServerSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    override suspend fun registerDevice(
        server: ServerSettings,
        name: String,
        publicKey: String,
    ): Result<RegisteredDevice> = withContext(ioDispatcher) {
        try {
            val body = json.encodeToString(RegisterRequest(name, publicKey))
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url("${server.httpBaseUrl}/devices").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 201 -> Result.success(parseRegistered(text))
                    else -> Result.failure(parseError(response.code, text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 応答が読めないときの例外には、応答の中身（token を含みうる）を入れない。 */
    private fun parseRegistered(text: String): RegisteredDevice {
        val parsed = try {
            json.decodeFromString<RegisterResponse>(text)
        } catch (e: Exception) {
            throw IOException("サーバーの応答を読めませんでした")
        }
        if (parsed.deviceId.isEmpty() || parsed.token.isEmpty()) throw IOException("サーバーの応答が不完全です")
        return RegisteredDevice(parsed.deviceId, parsed.token)
    }

    private fun parseError(status: Int, text: String): ApiException {
        val parsed = try {
            json.decodeFromString<ErrorResponse>(text)
        } catch (e: Exception) {
            null
        }
        return ApiException(status, parsed?.code, parsed?.message)
    }

    @Serializable
    private class RegisterRequest(val name: String, val publicKey: String)

    @Serializable
    private class RegisterResponse(val deviceId: String = "", val token: String = "")

    @Serializable
    private class ErrorResponse(val code: String? = null, val message: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
