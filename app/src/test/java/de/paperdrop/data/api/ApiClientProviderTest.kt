package de.paperdrop.data.api

import org.junit.Assert.*
import org.junit.Test

class ApiClientProviderTest {

    private val provider = ApiClientProvider()

    @Test
    fun `getApi returns same instance for same URL`() {
        val api1 = provider.getApi("http://example.com/")
        val api2 = provider.getApi("http://example.com/")
        assertSame(api1, api2)
    }

    @Test
    fun `getApi rebuilds instance when URL changes`() {
        val api1 = provider.getApi("http://server1.com/")
        val api2 = provider.getApi("http://server2.com/")
        assertNotSame(api1, api2)
    }

    @Test
    fun `getApi returns same instance after URL change then back`() {
        provider.getApi("http://server1.com/")
        val api1 = provider.getApi("http://server2.com/")
        val api2 = provider.getApi("http://server2.com/")
        assertSame(api1, api2)
    }

    @Test
    fun `getApi returns non-null instance`() {
        assertNotNull(provider.getApi("http://example.com/"))
    }

    @Test
    fun `getApi treats URL without trailing slash as different from same URL with slash`() {
        val api1 = provider.getApi("http://example.com")
        val api2 = provider.getApi("http://example.com/")
        assertNotSame(api1, api2)
    }
}
