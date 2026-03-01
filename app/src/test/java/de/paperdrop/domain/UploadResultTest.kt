package de.paperdrop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadResultTest {

    @Test
    fun `Success holds taskId and fileName`() {
        val result = UploadResult.Success(taskId = "abc-123", fileName = "invoice.pdf")
        assertEquals("abc-123", result.taskId)
        assertEquals("invoice.pdf", result.fileName)
    }

    @Test
    fun `Completed holds documentId and fileName`() {
        val result = UploadResult.Completed(documentId = 42, fileName = "invoice.pdf")
        assertEquals(42, result.documentId)
        assertEquals("invoice.pdf", result.fileName)
    }

    @Test
    fun `Error holds message`() {
        val result = UploadResult.Error(message = "Network error")
        assertEquals("Network error", result.message)
    }

    @Test
    fun `Success is only instance of Success`() {
        val result: UploadResult = UploadResult.Success("t", "f")
        assertTrue(result is UploadResult.Success)
        assertFalse(result is UploadResult.Completed)
        assertFalse(result is UploadResult.Error)
    }

    @Test
    fun `when expression covers all subtypes`() {
        val results: List<UploadResult> = listOf(
            UploadResult.Success("t1", "f1"),
            UploadResult.Completed(1, "f2"),
            UploadResult.Error("oops")
        )

        val labels = results.map { r ->
            when (r) {
                is UploadResult.Success   -> "success"
                is UploadResult.Completed -> "completed"
                is UploadResult.Error     -> "error"
            }
        }

        assertEquals(listOf("success", "completed", "error"), labels)
    }

    @Test
    fun `data class equality works for Success`() {
        val a = UploadResult.Success("task-1", "file.pdf")
        val b = UploadResult.Success("task-1", "file.pdf")
        assertEquals(a, b)
    }

    @Test
    fun `data class equality works for Error`() {
        val a = UploadResult.Error("message")
        val b = UploadResult.Error("message")
        assertEquals(a, b)
    }
}
