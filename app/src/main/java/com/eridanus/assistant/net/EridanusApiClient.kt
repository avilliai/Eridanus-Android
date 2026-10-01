package com.eridanus.assistant.net

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class EridanusApiClient(
    private var serverBaseUrl: String,
    private var authToken: String = ""
) {

    private val gson = Gson()

    private val okHttpClient: OkHttpClient = buildUnsafeOkHttpClient()

    private fun buildUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(70, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(70, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun updateConfig(newUrl: String, newAuthToken: String? = null) {
        this.serverBaseUrl = newUrl.trimEnd('/')
        if (newAuthToken != null) {
            this.authToken = newAuthToken.trim()
        }
    }

    fun updateBaseUrl(newUrl: String) {
        updateConfig(newUrl)
    }

    private fun Request.Builder.withAuthHeader(): Request.Builder {
        if (authToken.isNotBlank()) {
            this.header("X-Auth-Token", authToken)
        }
        return this
    }

    suspend fun downloadImageBytes(rawUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            resolveMediaUrl(rawUrl)
        }
        try {
            val req = Request.Builder()
                .url(fullUrl)
                .withAuthHeader()
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveMediaUrl(path: String): String {
        return normalizeHttpUrl(path)
    }

    private fun normalizeHttpUrl(path: String): String {
        val base = serverBaseUrl.trimEnd('/')
        val rel = path.trimStart('/')
        return "$base/$rel"
    }

    suspend fun checkStatus(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        val targetUrl = normalizeHttpUrl("/api/android/status")
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .withAuthHeader()
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val status = gson.fromJson(bodyStr, StatusResponse::class.java)
                    Result.success(status)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络连接异常: ${e.message}", e))
        }
    }

    suspend fun getChatHistory(limit: Int = 50, sinceId: Long? = null): Result<List<HistoryItem>> = withContext(Dispatchers.IO) {
        val query = if (sinceId != null && sinceId > 0) {
            "/api/android/history?since_id=$sinceId&limit=$limit"
        } else {
            "/api/android/history?limit=$limit"
        }
        val targetUrl = normalizeHttpUrl(query)
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .withAuthHeader()
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = gson.fromJson(bodyStr, HistoryResponse::class.java)
                    Result.success(root.messages ?: emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("获取历史失败: ${e.message}", e))
        }
    }

    suspend fun uploadImage(imageBytes: ByteArray, filename: String = "upload.jpg"): Result<String> = withContext(Dispatchers.IO) {
        val targetUrl = normalizeHttpUrl("/api/android/upload")
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    filename,
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url(targetUrl)
                .withAuthHeader()
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val map = gson.fromJson(bodyStr, Map::class.java)
                    val url = map["url"] as? String ?: ""
                    Result.success(url)
                } else {
                    Result.failure(Exception("图片上传失败 HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络错误: ${e.message}", e))
        }
    }

    suspend fun askAssistant(
        prompt: String,
        imageBase64: String? = null,
        bindQqId: Long = 1840094972L,
        atBot: Boolean = true,
        timeoutSeconds: Int = 60
    ): Result<AskResponse> = withContext(Dispatchers.IO) {
        val targetUrl = normalizeHttpUrl("/api/android/ask")
        try {
            val payload = mutableMapOf<String, Any>(
                "text" to prompt,
                "user_id" to bindQqId,
                "nickname" to "主人",
                "at_bot" to atBot,
                "timeout" to timeoutSeconds
            )
            if (!imageBase64.isNullOrBlank()) {
                payload["image_base64"] = imageBase64
            }
            if (authToken.isNotBlank()) {
                payload["auth_token"] = authToken
            }

            val jsonBody = gson.toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(targetUrl)
                .withAuthHeader()
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val askResp = gson.fromJson(bodyStr, AskResponse::class.java)
                    Result.success(askResp)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("请求异常: ${e.message}", e))
        }
    }

    data class StatusResponse(
        val status: String,
        val bot_connected: Boolean,
        val default_qq_id: Long,
        val active_clients: Int
    ) {
        val botConnected: Boolean get() = bot_connected
        val activeClients: Int get() = active_clients
    }

    data class AskResponse(
        val status: String,
        val reply: String? = null,
        val images: List<String>? = null,
        val msg_id: Long? = null,
        val message: String? = null,
        val user_id: Long? = null
    )

    data class HistoryResponse(
        val status: String,
        val messages: List<HistoryItem>? = null
    )

    data class HistoryItem(
        val id: Long,
        val raw_msg_id: Long? = null,
        val text: String,
        val images: List<String>? = null,
        val is_user: Boolean,
        val time: Long
    )
}