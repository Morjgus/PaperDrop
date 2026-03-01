package de.paperdrop.data.api

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ApiClientProviderTest {

    private lateinit var provider: ApiClientProvider

    @Before
    fun setUp() {
        provider = ApiClientProvider()
    }

    @Test
    fun `same URL returns same cached instance`() {
        val api1 = provider.getApi("https://paperless.example.com/")
        val api2 = provider.getApi("https://paperless.example.com/")
        assertSame(api1, api2)
    }

    @Test
    fun `different URL returns a new instance`() {
        val api1 = provider.getApi("https://server1.example.com/")
        val api2 = provider.getApi("https://server2.example.com/")
        assertNotSame(api1, api2)
    }

    @Test
    fun `URL without trailing slash is cached by exact string`() {
        val api1 = provider.getApi("https://paperless.example.com")
        val api2 = provider.getApi("https://paperless.example.com")
        assertSame(api1, api2)
    }

    @Test
    fun `switching URL invalidates cache`() {
        val api1 = provider.getApi("https://server-a.example.com/")
        provider.getApi("https://server-b.example.com/")
        val api3 = provider.getApi("https://server-a.example.com/")
        // Cache only holds the last URL, so api3 is a fresh instance
        assertNotSame(api1, api3)
    }

    @Test
    fun `returned instance implements PaperlessApi`() {
        val api = provider.getApi("https://paperless.example.com/")
        // Verify the returned proxy implements the interface
        assert(api is PaperlessApi)
    }
}
