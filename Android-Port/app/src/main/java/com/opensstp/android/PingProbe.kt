package com.opensstp.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real latency measurement.
 *
 * Android apps cannot send ICMP echo packets without root, so the standard honest approach is to
 * time a real TCP handshake (SYN -> SYN/ACK) to the exact host:port the VPN will use. This is a
 * genuine round-trip time to the server, measured on the wire; nothing is simulated.
 *
 * - DNS resolution is excluded from the timing.
 * - Each sample opens a fresh socket; the reported value is the median of the successful samples.
 * - While the VPN is up, probes are bound to the underlying (non-VPN) network so the result is the
 *   real path to the server and not a path through the tunnel itself.
 */
object PingProbe {
    sealed class Result {
        data class Ok(val medianMs: Int, val minMs: Int, val sent: Int, val received: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val DEFAULT_PORT = 443
    private const val SAMPLES = 4
    private const val TIMEOUT_MS = 3000

    fun parse(raw: String): Pair<String, Int>? {
        val value = raw.trim()
        if (value.isEmpty() || value.contains("://")) return null
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            val host = value.substring(1, end)
            val suffix = value.substring(end + 1)
            val port = if (suffix.isEmpty()) DEFAULT_PORT
            else if (suffix.startsWith(":")) suffix.drop(1).toIntOrNull() ?: return null
            else return null
            return host to port
        }
        val lastColon = value.lastIndexOf(':')
        return if (lastColon > 0 && value.indexOf(':') == lastColon) {
            val port = value.substring(lastColon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            value.substring(0, lastColon) to port
        } else {
            value to DEFAULT_PORT
        }
    }

    /** Picks a real internet network that is not the VPN, if one exists. */
    private fun underlyingNetwork(context: Context): Network? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    } catch (_: Exception) {
        null
    }

    /** Blocking; call from a background thread. */
    fun measure(context: Context, rawServer: String): Result {
        val (host, port) = parse(rawServer) ?: return Result.Failed("آدرس نامعتبر")
        val network = underlyingNetwork(context)

        val addresses: Array<InetAddress> = try {
            network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        } catch (_: Exception) {
            return Result.Failed("DNS ناموفق")
        }
        val target = addresses.firstOrNull() ?: return Result.Failed("DNS ناموفق")

        val times = ArrayList<Int>(SAMPLES)
        for (i in 0 until SAMPLES) {
            if (Thread.currentThread().isInterrupted) break
            var socket: Socket? = null
            try {
                socket = network?.socketFactory?.createSocket() ?: Socket()
                socket.tcpNoDelay = true
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(target, port), TIMEOUT_MS)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                times.add(Math.max(1, Math.round(elapsedMs).toInt()))
            } catch (_: Exception) {
                // lost / refused / timed out sample
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            if (i < SAMPLES - 1) {
                try { Thread.sleep(120) } catch (_: InterruptedException) { break }
            }
        }

        if (times.isEmpty()) return Result.Failed("بدون پاسخ")
        times.sort()
        val median = if (times.size % 2 == 1) times[times.size / 2]
        else (times[times.size / 2 - 1] + times[times.size / 2]) / 2
        return Result.Ok(median, times.first(), SAMPLES, times.size)
    }
}
