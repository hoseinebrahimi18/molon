package com.opensstp.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import vn.unlimit.softether.client.SoftEtherClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Real SoftEther protocol session + Android TUN bridge.
 * The external native module is SoftEther-Android-Module (not the legacy SSTP code under reference/).
 * Connection state is reported only after SoftEther authentication, socket protection, DHCP and
 * VpnService.Builder.establish() have all succeeded.
 */
class SoftEtherVpnService : VpnService() {
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var nativeClient: SoftEtherClient? = null
    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var cpuWakeLock: PowerManager.WakeLock? = null
    private val sessionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "opensstp-softether-session").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (running) {
                    publishStatus("اتصال در حال انجام است", STATE_CONNECTING)
                    return START_NOT_STICKY
                }
                val rawServer = intent.getStringExtra(EXTRA_SERVER).orEmpty()
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { "vpn" }
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val target = try {
                    parseServer(rawServer)
                } catch (e: IllegalArgumentException) {
                    publishStatus(e.message ?: "آدرس سرور معتبر نیست", STATE_DISCONNECTED)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                running = true
                startAsForeground("در حال اتصال به سرور SoftEther…")
                publishStatus("در حال اتصال به ${target.host}:${target.port}", STATE_CONNECTING)
                sessionExecutor.execute { runSession(target.host, target.port, username, password) }
            }
            ACTION_DISCONNECT -> {
                requestDisconnect()
                if (nativeHandle == 0L) stopSelf(startId)
            }
            ACTION_QUERY -> {
                val state = when { connected -> STATE_CONNECTED; running || nativeHandle != 0L -> STATE_CONNECTING; else -> STATE_DISCONNECTED }
                publishStatus(when { connected -> "متصل"; running -> "در حال اتصال"; nativeHandle != 0L -> "در حال قطع اتصال"; else -> "قطع است" }, state)
                if (!running && nativeHandle == 0L) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runSession(host: String, port: Int, username: String, password: String) {
        var client: SoftEtherClient? = null
        var handle = 0L
        var tun: ParcelFileDescriptor? = null
        var tunInput: FileInputStream? = null
        var tunOutput: FileOutputStream? = null
        var failureMessage: String? = null
        val loopFailure = AtomicReference<String?>(null)
        var inboundThread: Thread? = null
        var outboundThread: Thread? = null
        var statsThread: Thread? = null
        // Byte counters measured at the packet bridge itself (independent of native stats).
        val rxBytes = AtomicLong(0L)
        val txBytes = AtomicLong(0L)

        try {
            client = SoftEtherClient()
            handle = client.nativeCreate()
            if (handle == 0L) throw IOException("موتور بومی SoftEther نتوانست نشست ایجاد کند.")
            nativeClient = client
            nativeHandle = handle

            // A single TCP session avoids unprotected extra native sockets being added after TUN starts.
            client.nativeSetMaxConnection(handle, 1)
            client.nativeSetOption(handle, SoftEtherClient.OPTION_TIMEOUT, 20_000L)
            client.nativeSetAuthType(handle, if (password.isBlank()) AUTH_AUTO else AUTH_PASSWORD)

            val connectResult = client.nativeConnectWithHub(
                handle = handle,
                host = host,
                port = port,
                username = username,
                password = password,
                hubName = HUB_NAME,
                useTcp = true,
                clientProductName = "OpenSSTP Android",
                clientVersion = APP_VERSION,
                clientBuild = 1,
                clientOsName = "Android",
                clientOsVersion = Build.VERSION.RELEASE.orEmpty(),
                clientOsProductId = Build.DEVICE.orEmpty(),
                clientHostName = Build.MODEL.orEmpty().take(120),
                clientIpAddress = "",
                clientPort = 0,
                serverHostName = host,
                serverIpAddress = "",
                serverPort = port
            )
            if (!running) throw InterruptedException("اتصال توسط کاربر لغو شد.")
            if (connectResult != 0 || client.nativeGetState(handle) != NATIVE_STATE_CONNECTED) {
                throw IOException("اتصال SoftEther انجام نشد (کد خطا: $connectResult). آدرس، پورت، Hub و اطلاعات ورود را بررسی کنید.")
            }

            // Protect every socket before adding the default routes to the Android VPN interface.
            protectNativeSockets(client, handle)
            publishStatus("اتصال برقرار شد؛ دریافت تنظیمات IP از سرور…", STATE_CONNECTING)

            val lease = client.doDhcp(handle)
                ?: throw IOException("سرور SoftEther اتصال را پذیرفت، اما DHCP نشانی IP معتبر نداد؛ تونل اینترنت ساخته نشد.")
            if (!isUsableIpv4(lease.assignedIp) || lease.prefixLength !in 1..32) {
                throw IOException("پیکربندی IP دریافت‌شده از سرور معتبر نیست.")
            }
            if (!running) throw InterruptedException("اتصال توسط کاربر لغو شد.")

            val builder = Builder()
                .setSession("OpenSSTP — SoftEther VPN")
                .setMtu(TUN_MTU)
                .addAddress(lease.assignedIp, lease.prefixLength)
                .addRoute("0.0.0.0", 0)
                // Capture IPv6 too, so it cannot silently bypass the VPN on the physical network.
                // Real IPv6 Internet access depends on the remote SoftEther hub/server routing IPv6.
                .addAddress(IPV6_TUN_ADDRESS, 128)
                .addRoute("::", 0)
                .setConfigureIntent(PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))

            listOf(lease.dnsServer, lease.dnsServer2)
                .filter { isUsableIpv4(it) }
                .distinct()
                .forEach { builder.addDnsServer(it) }
            if (!isUsableIpv4(lease.dnsServer) && !isUsableIpv4(lease.dnsServer2)) {
                // Both DNS requests still traverse the configured VPN default route.
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
            }

            val establishedTun = builder.establish() ?: throw IOException("اندروید اجازهٔ ایجاد تونل VPN را نداد.")
            tun = establishedTun
            tunDescriptor = establishedTun
            val inputStream = FileInputStream(establishedTun.fileDescriptor)
            val outputStream = FileOutputStream(establishedTun.fileDescriptor)
            tunInput = inputStream
            tunOutput = outputStream
            connected = true
            publishStatus("متصل به ${host}:${port}", STATE_CONNECTED)
            val sessionClient = client ?: throw IOException("موتور SoftEther پس از اتصال در دسترس نیست.")
            val sessionHandle = handle

            // The native protocol uses blocking socket reads; keep the CPU awake while this
            // user-requested VPN session is active, especially with the screen off.
            statsThread = Thread({
                var previousTx = 0L
                var previousRx = 0L
                var previousTime = SystemClock.elapsedRealtime()
                while (running && connected) {
                    try { Thread.sleep(STATS_INTERVAL_MS) } catch (_: InterruptedException) { break }
                    if (!running || !connected) break
                    try {
                        val now = SystemClock.elapsedRealtime()
                        val elapsedMs = (now - previousTime).coerceAtLeast(1L)
                        val currentTx = txBytes.get()
                        val currentRx = rxBytes.get()
                        val txRate = (currentTx - previousTx).coerceAtLeast(0L) * 1000L / elapsedMs
                        val rxRate = (currentRx - previousRx).coerceAtLeast(0L) * 1000L / elapsedMs
                        previousTx = currentTx
                        previousRx = currentRx
                        previousTime = now
                        publishTrafficRates(rxRate, txRate)
                    } catch (e: Exception) {
                        Log.w(TAG, "Traffic statistics unavailable", e)
                    }
                }
            }, "opensstp-softether-stats").apply { isDaemon = true }

            val input = inputStream
            val output = outputStream
            inboundThread = Thread({
                try {
                    val buffer = ByteArray(PACKET_BUFFER_SIZE)
                    while (running) {
                        val count = sessionClient.nativeReceive(sessionHandle, buffer, buffer.size)
                        when {
                            count > 0 -> { output.write(buffer, 0, count); rxBytes.addAndGet(count.toLong()) }
                            count == 0 -> Thread.sleep(RECEIVE_IDLE_SLEEP_MS)
                            else -> throw IOException("ارتباط داده‌ای SoftEther قطع شد.")
                        }
                    }
                } catch (e: Exception) {
                    if (running) failPacketLoop("دریافت از تونل ناموفق بود: ${e.message}", loopFailure)
                }
            }, "opensstp-softether-rx").apply { isDaemon = true }

            outboundThread = Thread({
                try {
                    val buffer = ByteArray(PACKET_BUFFER_SIZE)
                    while (running) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val sent = sessionClient.nativeSendSlice(sessionHandle, buffer, 0, count)
                        if (sent != count) throw IOException("ارسال بسته به موتور SoftEther کامل نشد (sent=$sent, expected=$count).")
                        txBytes.addAndGet(count.toLong())
                    }
                    if (running) throw IOException("ورودی تونل اندروید بسته شد.")
                } catch (e: Exception) {
                    if (running) failPacketLoop("ارسال ترافیک به تونل ناموفق بود: ${e.message}", loopFailure)
                }
            }, "opensstp-softether-tx").apply { isDaemon = true }

            inboundThread.start()
            outboundThread.start()
            statsThread?.start()
            inboundThread.join()
            outboundThread.join()
            loopFailure.get()?.let { throw IOException(it) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (running) failureMessage = "فرایند اتصال متوقف شد."
        } catch (e: Exception) {
            if (running || loopFailure.get() != null) {
                failureMessage = loopFailure.get() ?: (e.message ?: "اتصال VPN ناموفق بود.")
            }
            Log.e(TAG, "SoftEther session ended", e)
        } finally {
            running = false
            connected = false
            statsThread?.interrupt()
            try { statsThread?.join(250L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            try { tunInput?.close() } catch (_: Exception) { }
            try { tunOutput?.close() } catch (_: Exception) { }
            try { tun?.close() } catch (_: Exception) { }
            if (tunDescriptor === tun) tunDescriptor = null

            if (handle != 0L && client != null) {
                try { client.nativeDisconnect(handle) } catch (e: Exception) { Log.w(TAG, "nativeDisconnect", e) }
                try { client.nativeDestroy(handle) } catch (e: Exception) { Log.w(TAG, "nativeDestroy", e) }
            }
            nativeHandle = 0L
            nativeClient = null
            releaseCpuWakeLock()

            val finalMessage = failureMessage ?: "قطع شد"
            publishStatus(finalMessage, if (failureMessage == null) STATE_DISCONNECTED else STATE_ERROR)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun protectNativeSockets(client: SoftEtherClient, handle: Long) {
        val descriptors = linkedSetOf<Int>()
        client.nativeGetSocketFd(handle).takeIf { it >= 0 }?.let(descriptors::add)
        client.nativeGetAllSocketFds(handle)?.filter { it >= 0 }?.forEach(descriptors::add)
        client.nativeGetRudpSocketFd(handle).takeIf { it >= 0 }?.let(descriptors::add)
        client.nativeGetNatTUdpSocketFd(handle).takeIf { it >= 0 }?.let(descriptors::add)
        if (descriptors.isEmpty()) throw IOException("موتور SoftEther هیچ سوکت شبکهٔ فعالی گزارش نکرد.")
        descriptors.forEach { fd ->
            if (!protect(fd)) throw IOException("اندروید نتوانست سوکت موتور SoftEther (fd=$fd) را از تونل VPN مستثنا کند.")
        }
    }

    private fun failPacketLoop(message: String, failure: AtomicReference<String?>) {
        failure.compareAndSet(null, message)
        running = false
        try { tunDescriptor?.close() } catch (_: Exception) { }
        val client = nativeClient
        val handle = nativeHandle
        if (client != null && handle != 0L) {
            try { client.nativeForceCloseSocket(handle) } catch (_: Exception) { }
        }
    }

    private fun requestDisconnect() {
        if (!running && nativeHandle == 0L) {
            publishStatus("قطع است", STATE_DISCONNECTED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        running = false
        publishStatus("در حال قطع اتصال…", STATE_DISCONNECTED)
        try { tunDescriptor?.close() } catch (_: Exception) { }
        val client = nativeClient
        val handle = nativeHandle
        if (client != null && handle != 0L) {
            // Interrupt a blocked native connect/read. The session worker owns final destruction.
            try { client.nativeForceCloseSocket(handle) } catch (e: Exception) { Log.w(TAG, "force close", e) }
        }
    }

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        cpuWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:VpnSession").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuWakeLock() {
        val lock = cpuWakeLock
        cpuWakeLock = null
        try { if (lock?.isHeld == true) lock.release() } catch (e: Exception) {
            Log.w(TAG, "Unable to release CPU wake lock", e)
        }
    }

    private fun publishTrafficRates(rxBytesPerSecond: Long, txBytesPerSecond: Long) {
        val broadcast = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, STATE_CONNECTED)
            .putExtra(EXTRA_RX_RATE, formatRate(rxBytesPerSecond))
            .putExtra(EXTRA_TX_RATE, formatRate(txBytesPerSecond))
        sendBroadcast(broadcast)
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val safeRate = bytesPerSecond.coerceAtLeast(0L)
        return when {
            safeRate < 1024L -> "$safeRate B/s"
            safeRate < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", safeRate / 1024.0)
            else -> String.format(java.util.Locale.US, "%.2f MB/s", safeRate / 1048576.0)
        }
    }

    private fun startAsForeground(text: String) {
        acquireCpuWakeLock()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OpenSSTP VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("OpenSSTP VPN")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun publishStatus(message: String, state: String) {
        Log.i(TAG, "$state: $message")
        val broadcast = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_STATE, state)
        sendBroadcast(broadcast)
        if (running) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

    override fun onRevoke() {
        requestDisconnect()
        if (nativeHandle == 0L) stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        requestDisconnect()
        releaseCpuWakeLock()
        sessionExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private data class ServerAddress(val host: String, val port: Int)

    private fun parseServer(raw: String): ServerAddress {
        val value = raw.trim()
        require(value.isNotEmpty()) { "آدرس سرور را وارد کنید." }
        require(!value.contains("://")) { "فقط نام میزبان یا IP را وارد کنید؛ بدون https://." }

        val host: String
        val port: Int
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 1) { "آدرس IPv6 داخل کروشه معتبر نیست." }
            host = value.substring(1, end)
            val suffix = value.substring(end + 1)
            port = if (suffix.isEmpty()) DEFAULT_PORT else {
                require(suffix.startsWith(":")) { "پورت سرور معتبر نیست." }
                suffix.drop(1).toIntOrNull() ?: throw IllegalArgumentException("پورت سرور باید عددی باشد.")
            }
        } else {
            val lastColon = value.lastIndexOf(':')
            if (lastColon > 0 && value.indexOf(':') == lastColon) {
                host = value.substring(0, lastColon)
                port = value.substring(lastColon + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("پورت سرور باید عددی باشد.")
            } else {
                host = value
                port = DEFAULT_PORT
            }
        }
        require(host.isNotBlank()) { "نام میزبان خالی است." }
        require(port in 1..65535) { "پورت سرور باید بین 1 تا 65535 باشد." }
        return ServerAddress(host, port)
    }

    private fun isUsableIpv4(address: String): Boolean {
        val octets = address.split('.')
        return octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull()?.let { it in 0..255 } == true
        } && address != "0.0.0.0"
    }

    companion object {
        private const val TAG = "OpenSSTP-VpnService"
        private const val CHANNEL_ID = "opensstp_vpn_channel"
        private const val NOTIFICATION_ID = 4017
        private const val DEFAULT_PORT = 443
        private const val HUB_NAME = "VPNGATE"
        private const val APP_VERSION = "0.2.0"
        private const val AUTH_AUTO = 0
        private const val AUTH_PASSWORD = 1
        private const val NATIVE_STATE_CONNECTED = 6
        private const val TUN_MTU = 1400
        private const val IPV6_TUN_ADDRESS = "fd42:6f70:656e:5353::2"
        private const val PACKET_BUFFER_SIZE = 32768
        private const val RECEIVE_IDLE_SLEEP_MS = 5L
        private const val STATS_INTERVAL_MS = 1000L

        const val ACTION_CONNECT = "com.opensstp.android.CONNECT"
        const val ACTION_DISCONNECT = "com.opensstp.android.DISCONNECT"
        const val ACTION_QUERY = "com.opensstp.android.QUERY"
        const val ACTION_STATUS = "com.opensstp.android.STATUS"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATE = "state"
        const val EXTRA_RX_RATE = "rx_rate"
        const val EXTRA_TX_RATE = "tx_rate"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
    }
}
