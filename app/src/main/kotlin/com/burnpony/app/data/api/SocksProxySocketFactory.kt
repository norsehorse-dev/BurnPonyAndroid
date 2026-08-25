//
// SocksProxySocketFactory.kt
// BurnPony — a minimal, self-contained SOCKS5 client for OkHttp.
//
// Why hand-rolled: Java's built-in SOCKS support (Proxy.Type.SOCKS) does not
// let us control the auth-method negotiation, and Tor applies stream
// isolation (IsolateSOCKSAuth) only when the client actually uses the
// username/password method. Offering our own greeting with exactly the method
// we want is the only deterministic way to get isolation across API 24..36.
//
// Remote DNS: the destination is sent to the proxy as a domain name (ATYP 3),
// so the proxy resolves it. Paired with SocksProxyDns, the destination
// hostname is never resolved on the device, so nothing leaks to the local
// resolver while a proxy is active.
//
// Fail-closed: any failure connecting to the proxy or completing the SOCKS
// handshake throws IOException, which BurnPonyApi maps to a network error.
// There is no direct fallback.
//

package com.burnpony.app.data.api

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Hands OkHttp a placeholder address that carries the destination hostname
 * without resolving it, so SocksProxySocket can forward the name to the proxy
 * (remote DNS). The 0.0.0.0 bytes are never dialed: the socket ignores them
 * and connects to the proxy instead.
 */
object SocksProxyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
}

class SocksProxySocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String?,
    private val password: String?,
) : SocketFactory() {

    override fun createSocket(): Socket =
        SocksProxySocket(proxyHost, proxyPort, username, password)

    // OkHttp only calls the no-arg createSocket(); the connecting overloads
    // are implemented for completeness so this is a drop-in SocketFactory.
    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress.createUnresolved(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }
}

private class SocksProxySocket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String?,
    private val password: String?,
) : Socket() {

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        require(endpoint is InetSocketAddress) { "unsupported endpoint: $endpoint" }
        val destHost = endpoint.hostString ?: throw IOException("no destination host")
        val destPort = endpoint.port
        val useAuth = !username.isNullOrEmpty()

        // Connect the underlying socket to the PROXY, not the destination.
        super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)

        val saved = soTimeout
        if (timeout > 0) soTimeout = timeout
        try {
            val out = getOutputStream()
            val input = getInputStream()

            // Greeting: offer exactly one method so the proxy's choice is
            // forced. user/pass (0x02) when we have credentials (this is what
            // makes Tor isolate the stream); no-auth (0x00) otherwise.
            val greet = ByteArrayOutputStream().apply {
                write(0x05); write(0x01); write(if (useAuth) 0x02 else 0x00)
            }
            out.write(greet.toByteArray()); out.flush()

            val sel = readN(input, 2)
            if ((sel[0].toInt() and 0xFF) != 0x05) throw IOException("bad SOCKS version from proxy")
            val chosen = sel[1].toInt() and 0xFF
            if (chosen == 0xFF) throw IOException("proxy rejected the offered auth method")
            if (chosen != (if (useAuth) 0x02 else 0x00)) {
                throw IOException("proxy selected an unexpected auth method: $chosen")
            }

            // RFC 1929 username/password sub-negotiation.
            if (useAuth) {
                val user = username!!.toByteArray(Charsets.UTF_8)
                val pass = (password ?: "").toByteArray(Charsets.UTF_8)
                if (user.size > 255 || pass.size > 255) throw IOException("SOCKS credentials too long")
                val auth = ByteArrayOutputStream().apply {
                    write(0x01)
                    write(user.size); write(user)
                    write(pass.size); write(pass)
                }
                out.write(auth.toByteArray()); out.flush()
                val authReply = readN(input, 2)
                if ((authReply[1].toInt() and 0xFF) != 0x00) throw IOException("SOCKS authentication failed")
            }

            // CONNECT with the destination as a domain name (proxy resolves it).
            val host = destHost.toByteArray(Charsets.UTF_8)
            if (host.size > 255) throw IOException("destination host too long")
            val req = ByteArrayOutputStream().apply {
                write(0x05); write(0x01); write(0x00); write(0x03)
                write(host.size); write(host)
                write((destPort ushr 8) and 0xFF); write(destPort and 0xFF)
            }
            out.write(req.toByteArray()); out.flush()

            // Reply: VER REP RSV ATYP BND.ADDR BND.PORT
            val head = readN(input, 4)
            if ((head[0].toInt() and 0xFF) != 0x05) throw IOException("bad SOCKS version in reply")
            val rep = head[1].toInt() and 0xFF
            if (rep != 0x00) throw IOException("SOCKS connect failed (code $rep)")
            when (head[3].toInt() and 0xFF) {
                0x01 -> readN(input, 4)   // IPv4
                0x04 -> readN(input, 16)  // IPv6
                0x03 -> readN(input, readN(input, 1)[0].toInt() and 0xFF) // domain
                else -> throw IOException("unknown SOCKS address type in reply")
            }
            readN(input, 2) // bound port, discarded
            // The socket is now a tunnel to the destination.
        } finally {
            soTimeout = saved
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw IOException("proxy closed the connection during handshake")
            off += r
        }
        return b
    }
}
