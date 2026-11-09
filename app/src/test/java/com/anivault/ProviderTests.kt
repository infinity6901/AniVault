package com.anivault

import com.anivault.core.Sanitizer
import com.anivault.data.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

private fun src(id: String, name: String = id) = ApiSource(id, name, "https://$id.example.org", AnimeApiType.JIKAN_COMPATIBLE)

class FallbackEngineTest {
    private fun engine(now: () -> Long = { 0L }) = FallbackEngine(clock = now, sleeper = {})
    private val list = { r: List<Int> -> r.isEmpty() }

    @Test fun `falls back to next source on server error`() = runBlocking {
        val out = engine().run(listOf(src("a"), src("b")), true, true, 0, "t", list) { s ->
            if (s.id == "a") throw ProviderException(FailureKind.SERVER, "boom") else listOf(1)
        }
        out as Outcome.Ok
        assertEquals("b", out.source.id)
        assertEquals(1, out.skipped.size)
    }

    @Test fun `no fallback when auto fallback is off`() = runBlocking {
        val out = engine().run(listOf(src("a"), src("b")), false, true, 0, "t", list) { s ->
            if (s.id == "a") throw ProviderException(FailureKind.NETWORK, "x") else listOf(1)
        }
        assertTrue(out is Outcome.AllFailed)
    }

    @Test fun `empty success is final by default`() = runBlocking {
        var calls = 0
        val out = engine().run(listOf(src("a"), src("b")), true, true, 0, "t", list) { calls++; emptyList<Int>() }
        assertTrue(out is Outcome.Ok)
        assertEquals(1, calls)
    }

    @Test fun `empty can fall through when configured but still returns empty if nobody has data`() = runBlocking {
        var calls = 0
        val out = engine().run(listOf(src("a"), src("b")), true, false, 0, "t", list) { calls++; emptyList<Int>() }
        assertTrue(out is Outcome.Ok)
        assertEquals(2, calls)
    }

    @Test fun `rate limit cools the source down`() = runBlocking {
        var t = 0L
        val e = engine { t }
        val sources = listOf(src("a"), src("b"))
        e.run(sources, true, true, 0, "t", list) { s -> if (s.id == "a") throw ProviderException(FailureKind.RATE_LIMITED, "slow", 429, 10_000) else listOf(1) }
        var aCalled = false
        e.run(sources, true, true, 0, "t", list) { s -> if (s.id == "a") aCalled = true; listOf(1) }
        assertFalse(aCalled)
        t = 20_000
        e.run(sources, true, true, 0, "t", list) { s -> if (s.id == "a") aCalled = true; listOf(1) }
        assertTrue(aCalled)
    }

    @Test fun `retries only transient failures`() = runBlocking {
        var n = 0
        engine().run(listOf(src("a")), true, true, 2, "t", list) { n++; if (n < 3) throw ProviderException(FailureKind.TIMEOUT, "t") else listOf(1) }
        assertEquals(3, n)
        var m = 0
        engine().run(listOf(src("a")), true, true, 2, "t", list) { m++; throw ProviderException(FailureKind.AUTH, "no") }
        assertEquals(1, m)
    }

    @Test fun `no sources`() = runBlocking {
        assertTrue(engine().run(emptyList(), true, true, 0, "t", list) { listOf(1) } is Outcome.NoSources)
    }
}

class SourceValidatorTest {
    private fun v(url: String, type: AnimeApiType = AnimeApiType.JIKAN_COMPATIBLE) = SourceValidator.validate("X", url, type)
    @Test fun accepts_https() { assertTrue(v("https://api.example.org/v4").ok) }
    @Test fun rejects_http() { assertFalse(v("http://api.example.org").ok) }
    @Test fun rejects_private_hosts() {
        listOf(
            "https://localhost/x",
            "https://192.168.1.5/",
            "https://10.0.0.1/",
            "https://printer.local/",
            "https://127.0.0.1/",
            "https://[::ffff:127.0.0.1]/",
            "https://[::ffff:192.168.1.1]/",
            "https://[fd00::1]/"
        ).forEach { assertFalse(it, v(it).ok) }
    }
    @Test fun safe_dns_blocks_private_and_mapped_addresses() {
        assertTrue(SafeDns.isPrivateAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
        assertTrue(SafeDns.isPrivateAddress(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1))))
        assertTrue(SafeDns.isPrivateAddress(InetAddress.getByAddress(byteArrayOf(0xfd.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))))
        assertFalse(SafeDns.isPrivateAddress(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
    }
    @Test fun rejects_secrets_in_url() {
        assertFalse(v("https://api.example.org/v1?api_key=abc").ok)
        assertFalse(v("https://user:pw@api.example.org/").ok)
    }
    @Test fun mal_host_locked() {
        assertFalse(v("https://evil.example.org/v2", AnimeApiType.MAL_V2).ok)
        assertTrue(v("https://api.myanimelist.net/v2", AnimeApiType.MAL_V2).ok)
    }
    @Test fun duplicate_rejected() {
        val existing = listOf(ApiSource("1", "A", "https://api.example.org/v4", AnimeApiType.JIKAN_COMPATIBLE))
        assertFalse(SourceValidator.validate("B", "https://api.example.org/v4/", AnimeApiType.JIKAN_COMPATIBLE, existing).ok)
        assertTrue(SourceValidator.validate("A", "https://api.example.org/v4", AnimeApiType.JIKAN_COMPATIBLE, existing, "1").ok)
    }
}

class TitleMatcherTest {
    @Test fun normalizes() { assertEquals("attack on titan", TitleMatcher.normalize("Attack on Titan (TV)")) }
    @Test fun diacritics() { assertEquals(TitleMatcher.normalize("Pokémon"), TitleMatcher.normalize("Pokemon")) }
    @Test fun dedupe_keeps_first() {
        fun a(p: String, t: String) = NormalizedAnime(p, p, "1", t, year = 2013)
        val out = TitleMatcher.dedupe(listOf(a("anilist", "Attack on Titan"), a("kitsu", "Attack on Titan!")))
        assertEquals(1, out.size); assertEquals("anilist", out[0].provider)
    }
}

class SanitizerTest {
    @Test fun hides_tokens_and_paths() {
        val s = Sanitizer.clean("GET https://api.x.org/v1/q?access_token=SECRET&q=private failed, Bearer abc.def")
        assertFalse(s.contains("SECRET")); assertFalse(s.contains("private")); assertFalse(s.contains("abc.def"))
    }
}

private class Fake(val code: Int, val body: String) : HttpTransport {
    override suspend fun execute(request: HttpRequestSpec) = HttpResult(code, body)
}

class AdapterTest {
    private val s = ApiSource("anilist", "AniList", "https://graphql.anilist.co", AnimeApiType.ANILIST_GRAPHQL)

    @Test fun anilist_parses() = runBlocking {
        val body = """{"data":{"Page":{"pageInfo":{"hasNextPage":true},"media":[{"id":1,"idMal":2,"title":{"romaji":"Cowboy Bebop","english":null,"native":null},"episodes":26,"averageScore":86,"startDate":{"year":1998,"month":4,"day":3},"genres":["Action"]}]}}}"""
        val r = AniListAdapter(s, Fake(200, body)).search("bebop", 1)
        assertTrue(r.hasNext)
        assertEquals("Cowboy Bebop", r.items[0].title)
        assertEquals(8.6f, r.items[0].score!!, 0.01f)
        assertEquals("2", r.items[0].remoteIds["mal"])
    }

    @Test fun anilist_malformed_is_an_error_not_empty() = runBlocking {
        try { AniListAdapter(s, Fake(200, """{"hello":1}""")).search("x", 1); fail() }
        catch (e: ProviderException) { assertEquals(FailureKind.MALFORMED, e.kind) }
    }

    @Test fun http_429_carries_retry_after() = runBlocking {
        val t = object : HttpTransport { override suspend fun execute(request: HttpRequestSpec) = HttpResult(429, "x", mapOf("Retry-After" to "7")) }
        try { AniListAdapter(s, t).search("x", 1); fail() }
        catch (e: ProviderException) { assertEquals(FailureKind.RATE_LIMITED, e.kind); assertEquals(7000L, e.retryAfterMs) }
    }

    @Test fun jikan_empty_list_is_success() = runBlocking {
        val j = ApiSource("j", "J", "https://j.example.org/v4", AnimeApiType.JIKAN_COMPATIBLE)
        val r = JikanAdapter(j, Fake(200, """{"data":[],"pagination":{"has_next_page":false}}""")).search("zzz", 1)
        assertTrue(r.items.isEmpty())
    }

    @Test fun jikan_wrong_schema_fails_check() = runBlocking {
        val j = ApiSource("j", "J", "https://j.example.org/v4", AnimeApiType.JIKAN_COMPATIBLE)
        try { JikanAdapter(j, Fake(200, """{"results":[]}""")).check(); fail() }
        catch (e: ProviderException) { assertEquals(FailureKind.MALFORMED, e.kind) }
    }

    @Test fun kitsu_parses() = runBlocking {
        val k = ApiSource("kitsu", "Kitsu", "https://kitsu.io/api/edge", AnimeApiType.KITSU_JSON_API)
        val body = """{"data":[{"id":"7","attributes":{"canonicalTitle":"Naruto","titles":{"en":"Naruto"},"episodeCount":220,"averageRating":"79.5","startDate":"2002-10-03"}}],"links":{}}"""
        val r = KitsuAdapter(k, Fake(200, body)).search("naruto", 1)
        assertEquals(7.95f, r.items[0].score!!, 0.01f); assertEquals(2002, r.items[0].year)
    }
}

class TtlCacheTest {
    @Test fun expires() {
        var t = 0L
        val c = TtlCache<String, Int>(2, 100) { t }
        c.put("a", 1); assertEquals(1, c.get("a"))
        t = 200; assertNull(c.get("a"))
    }
    @Test fun evicts_oldest() {
        val c = TtlCache<String, Int>(2, 1000)
        c.put("a", 1); c.put("b", 2); c.put("c", 3)
        assertNull(c.get("a")); assertEquals(3, c.get("c"))
    }
}
