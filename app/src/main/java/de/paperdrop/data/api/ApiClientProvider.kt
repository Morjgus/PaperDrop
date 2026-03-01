package de.paperdrop.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClientProvider @Inject constructor() {

    private var currentUrl: String    = ""
    private var _api: PaperlessApi?   = null

    fun getApi(baseUrl: String): PaperlessApi {
        if (baseUrl != currentUrl || _api == null) {
            currentUrl = baseUrl
            _api = build(baseUrl)
        }
        return _api!!
    }

    private fun build(baseUrl: String): PaperlessApi {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60,  TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaperlessApi::class.java)
    }
}
