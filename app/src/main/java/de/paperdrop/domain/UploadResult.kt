package de.paperdrop.domain

sealed class UploadResult {
    data class Success(val taskId: String, val fileName: String) : UploadResult()
    data class Completed(val documentId: Int, val fileName: String, val isDuplicate: Boolean = false) : UploadResult()
    data class Error(val message: String) : UploadResult()
}
