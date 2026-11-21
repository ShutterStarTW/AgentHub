package com.shutterstar.agenthub.usage.discovery

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal data class UsageHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class UsageHttpBytes(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
)

/**
 * Minimal, HTTPS-only, redirect-free HTTP client for opt-in live usage endpoints.
 * Redirects are deliberately disabled so `Authorization` headers can never be forwarded to
 * another host, responses are capped in memory, and auth headers are never logged.
 */
internal object UsageHttpClient {
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT = Duration.ofSeconds(8)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    private const val MAX_BINARY_RESPONSE_BYTES = 64 * 1024

    /** Empty-bodied POST for Connect RPCs answered in binary protobuf (response capped at 64 KiB). */
    fun postEmpty(url: String, headers: Map<String, String>): UsageHttpBytes {
        val uri = URI.create(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS usage endpoints are permitted" }
        val requestBuilder = HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(REQUEST_TIMEOUT)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        return try {
            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total <= MAX_BINARY_RESPONSE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_BINARY_RESPONSE_BYTES + 1 - total))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toByteArray()
            }
            if (bytes.size > MAX_BINARY_RESPONSE_BYTES) throw IOException("Usage response is too large")
            UsageHttpBytes(response.statusCode(), response.headers().firstValue("content-type").orElse(""), bytes)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Usage request was interrupted", interrupted)
        }
    }

    fun get(url: String, headers: Map<String, String>): UsageHttpResponse {
        val uri = URI.create(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS usage endpoints are permitted" }

        val requestBuilder = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(REQUEST_TIMEOUT)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        return try {
            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < MAX_RESPONSE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toString(StandardCharsets.UTF_8)
            }
            UsageHttpResponse(response.statusCode(), body)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Usage request was interrupted", interrupted)
        }
    }
}
