package de.paperdrop.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `fromStatus converts RUNNING to string`() {
        assertEquals("RUNNING", converters.fromStatus(UploadStatus.RUNNING))
    }

    @Test
    fun `fromStatus converts SUCCESS to string`() {
        assertEquals("SUCCESS", converters.fromStatus(UploadStatus.SUCCESS))
    }

    @Test
    fun `fromStatus converts FAILED to string`() {
        assertEquals("FAILED", converters.fromStatus(UploadStatus.FAILED))
    }

    @Test
    fun `toStatus parses RUNNING`() {
        assertEquals(UploadStatus.RUNNING, converters.toStatus("RUNNING"))
    }

    @Test
    fun `toStatus parses SUCCESS`() {
        assertEquals(UploadStatus.SUCCESS, converters.toStatus("SUCCESS"))
    }

    @Test
    fun `toStatus parses FAILED`() {
        assertEquals(UploadStatus.FAILED, converters.toStatus("FAILED"))
    }

    @Test
    fun `round-trip conversion is lossless for all statuses`() {
        UploadStatus.entries.forEach { status ->
            assertEquals(status, converters.toStatus(converters.fromStatus(status)))
        }
    }
}
