package de.paperdrop.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface PaperlessApi {

    @Multipart
    @POST("api/documents/post_document/")
    suspend fun uploadDocument(
        @Header("Authorization") token: String,
        @Part parts: List<MultipartBody.Part>
    ): Response<UploadTaskResponse>

    @GET("api/tasks/")
    suspend fun getTaskStatus(
        @Header("Authorization") token: String,
        @Query("task_id") taskId: String
    ): Response<List<TaskStatusResponse>>

    @GET("api/tags/")
    suspend fun getTags(
        @Header("Authorization") token: String,
        @Query("page_size") pageSize: Int = 250
    ): Response<TagsResponse>

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

data class TagsResponse(
    @SerializedName("results") val results: List<PaperlessLabel>
)

data class PaperlessLabel(
    @SerializedName("id")   val id: Int,
    @SerializedName("name") val name: String
)
