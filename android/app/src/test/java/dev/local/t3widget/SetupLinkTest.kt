package dev.local.t3widget

import dev.local.t3widget.data.ConnectionCache
import dev.local.t3widget.data.Endpoint
import dev.local.t3widget.data.EndpointKind
import dev.local.t3widget.data.EndpointOrder
import dev.local.t3widget.data.NetClass
import dev.local.t3widget.data.SetupLink
import dev.local.t3widget.data.TunnelHost
import dev.local.t3widget.live.LiveSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLinkTest {

    private val tunnel = "https://prod-0123456789abcdef.t3coderelay.com"
    private val lan = "http://192.168.1.23:3773"
    private val token = "abc.DEF-ghi_jkl+mno/pqr=="

    private fun ok(link: String) = (SetupLink.parse(link) as SetupLink.Result.Ok).config
    private fun invalid(link: String?) = SetupLink.parse(link) is SetupLink.Result.Invalid

    @Test fun parsesEncodedFragment() {
        val link = "t3widget://setup#v=1&url=https%3A%2F%2Fprod-0123456789abcdef.t3coderelay.com&" +
            "lan=http%3A%2F%2F192.168.1.23%3A3773&token=abc.DEF-ghi_jkl%2Bmno%2Fpqr%3D%3D"
        val c = ok(link)
        assertEquals(tunnel, c.url)
        assertEquals(lan, c.lanUrl)
        assertEquals(token, c.token)
    }

    @Test fun roundTripsBuild() {
        val c = ok(SetupLink.build("$tunnel/", lan, token))
        assertEquals(SetupLink.Config(tunnel, lan, token), c)
    }

    @Test fun lanIsOptionalAndOrderFree() {
        val c = ok("t3widget://setup#token=t0k&url=https%3A%2F%2Fexample.t3coderelay.com%2F&v=1")
        assertEquals("https://example.t3coderelay.com", c.url)
        assertEquals("", c.lanUrl)
        assertEquals("t0k", c.token)
        assertEquals("", ok("t3widget://setup#v=1&url=https%3A%2F%2Fa.b&lan=&token=x").lanUrl)
    }

    @Test fun plusIsLiteralAndUnencodedSlashesWork() {
        val c = ok("t3widget://setup#v=1&url=https://a.example.com&token=a+b/c")
        assertEquals("https://a.example.com", c.url)
        assertEquals("a+b/c", c.token)
    }

    @Test fun acceptsQueryFormAndUppercaseScheme() {
        assertEquals("x", ok("T3WIDGET://setup?v=1&url=https%3A%2F%2Fa.b&token=x").token)
    }

    @Test fun rejectsBadLinks() {
        assertTrue(invalid(null))
        assertTrue(invalid("https://evil.example/setup#v=1&url=https%3A%2F%2Fa.b&token=x"))
        assertTrue(invalid("t3widget://setup"))
        assertTrue(invalid("t3widget://setup#url=https%3A%2F%2Fa.b&token=x")) // no version
        assertTrue(invalid("t3widget://setup#v=2&url=https%3A%2F%2Fa.b&token=x")) // newer version
        assertTrue(invalid("t3widget://setup#v=1&token=x")) // no url
        assertTrue(invalid("t3widget://setup#v=1&url=ftp%3A%2F%2Fa.b&token=x"))
        assertTrue(invalid("t3widget://setup#v=1&url=https%3A%2F%2Fa.b")) // no token
        assertTrue(invalid("t3widget://setup#v=1&url=https%3A%2F%2Fa.b&token=a%20b")) // whitespace
        assertTrue(invalid("t3widget://setup#v=1&url=https%3A%2F%2Fa.b&lan=notaurl&token=x"))
        assertTrue(invalid("t3widget://setup#v=1&url=https%3A%2F%2Fa.b&token=x%2"))
    }

    @Test fun normalizesBaseUrls() {
        assertEquals("https://a.b", SetupLink.normalizeBaseUrl(" HTTPS://a.b/ "))
        assertEquals("http://10.0.0.2:3773", SetupLink.normalizeBaseUrl("http://10.0.0.2:3773"))
        assertNull(SetupLink.normalizeBaseUrl("a.b"))
        assertNull(SetupLink.normalizeBaseUrl("https://"))
    }

    // ---- endpoint choice

    @Test fun endpointOrderPrefersLanOnWifiAndTunnelElsewhere() {
        val t = Endpoint(EndpointKind.TUNNEL, tunnel)
        val l = Endpoint(EndpointKind.LAN, lan)
        assertEquals(listOf(l, t), EndpointOrder.candidates(tunnel, lan, NetClass.WIFI, null))
        assertEquals(listOf(t, l), EndpointOrder.candidates(tunnel, lan, NetClass.OTHER, null))
        // Remembered winner for this network class goes first.
        assertEquals(listOf(t, l), EndpointOrder.candidates(tunnel, lan, NetClass.WIFI, EndpointKind.TUNNEL))
        assertEquals(listOf(t), EndpointOrder.candidates(tunnel, "", NetClass.WIFI, EndpointKind.LAN))
        assertEquals(listOf(l), EndpointOrder.candidates("", lan, NetClass.OTHER, null))
    }

    @Test fun connectionCacheRemembersPerNetworkClass() {
        val c = ConnectionCache().remember(NetClass.WIFI, EndpointKind.LAN).remember(NetClass.OTHER, EndpointKind.TUNNEL)
            .remember(NetClass.WIFI, EndpointKind.TUNNEL)
        assertEquals(EndpointKind.TUNNEL, c.rememberedFor(NetClass.WIFI))
        assertEquals(EndpointKind.TUNNEL, c.rememberedFor(NetClass.OTHER))
        assertNull(ConnectionCache().rememberedFor(NetClass.WIFI))
    }

    @Test fun tunnelHostnameUpdatesOnlyTunnelUrls() {
        assertEquals("https://prod-new.t3coderelay.com", TunnelHost.updatedServerUrl(tunnel, "prod-new.t3coderelay.com"))
        assertNull(TunnelHost.updatedServerUrl(tunnel, "prod-0123456789abcdef.t3coderelay.com")) // unchanged
        assertNull(TunnelHost.updatedServerUrl(tunnel, null))
        assertNull(TunnelHost.updatedServerUrl(tunnel, "bad host/evil"))
        // A hand-entered Tailscale / LAN URL is never replaced.
        assertNull(TunnelHost.updatedServerUrl("https://pc.tail1234.ts.net", "prod-new.t3coderelay.com"))
        assertNull(TunnelHost.updatedServerUrl(lan, "prod-new.t3coderelay.com"))
        // Empty URL gets filled.
        assertEquals("https://prod-new.t3coderelay.com", TunnelHost.updatedServerUrl("", "prod-new.t3coderelay.com"))
    }

    // ---- live socket wire format

    @Test fun subscribeShellRequestShape() {
        val o = JSONObject(LiveSession.subscribeShellRequest("7", 730760))
        assertEquals("Request", o.getString("_tag"))
        assertEquals("7", o.getString("id"))
        assertEquals("orchestration.subscribeShell", o.getString("tag"))
        val p = o.getJSONObject("payload")
        assertEquals(730760, p.getLong("afterSequence"))
        assertTrue(p.getBoolean("httpSnapshotFallback"))
        assertTrue(p.getBoolean("omitIdleSubagents"))
        assertTrue(p.getBoolean("omitOlderSettled"))
        // Without a cursor the server sends a full socket snapshot instead.
        assertFalse(JSONObject(LiveSession.subscribeShellRequest("1", -1)).getJSONObject("payload").has("afterSequence"))
        assertEquals("""{"_tag":"Ack","requestId":"7"}""", LiveSession.ack("7"))
    }

    @Test fun backoffGrowsAndCaps() {
        assertTrue(LiveSession.backoffMs(0) in 1_600..2_400)
        assertTrue(LiveSession.backoffMs(5) in 48_000..72_000)
        assertTrue(LiveSession.backoffMs(99) in 240_000..360_000)
    }
}
