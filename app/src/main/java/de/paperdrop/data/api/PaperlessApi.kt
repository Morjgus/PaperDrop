package de.paperdrop.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface PaperlessApi {

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Header("Authorization") token: String,
        @Part document: MultipartBody.Part,
        @Part("title") title: RequestBody? = null
    ): Response<UploadTaskResponse>

    @GET("api/tasks/")
    suspend fun getTaskStatus(
        @Header("Authorization") token: String,
        @Query("task_id") taskId: String
    ): Response<List<TaskStatusResponse>>

    @GET("api/")
    suspend fun ping(
        @Header("Authorization") token: String
    ): Response<Unit>
}

data class UploadTaskResponse(
    @SerializedName("task_id") val taskId: String
)

data class TaskStatusResponse(
    @SerializedName("task_id")        val taskId: String,
    @SerializedName("status")         val status: String,
    @SerializedName("result")         val result: Int?,
    @SerializedName("task_file_name") val fileName: String?
)
