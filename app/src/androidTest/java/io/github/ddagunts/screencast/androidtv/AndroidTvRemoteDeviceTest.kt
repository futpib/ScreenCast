package io.github.ddagunts.screencast.androidtv

import android.app.NotificationManager
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ddagunts.screencast.AndroidTvRemoteService
import io.github.ddagunts.screencast.ui.AndroidTvViewModel
import io.github.ddagunts.screencast.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Real Android TLS sockets, Activity lifecycle and notification PendingIntents.
// The endpoint speaks Remote v2 locally; it is not a physical-TV compatibility test.
@RunWith(AndroidJUnit4::class)
class AndroidTvRemoteDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun activationUpdatesAndImeResetsKeepTheSameConnection() = runBlocking {
        TestTv().use { tv ->
            val session = AndroidTvSession(tv.device, AndroidTvCertFactory.generate(), tv.pin)
            try {
                session.connect()
                await { session.state.value is AndroidTvState.Active }
                repeat(4) { index ->
                    tv.send(RemoteMessage.SetActive(615).encode())
                    assertEquals(RemoteMessage.SetActive(615), tv.take<RemoteMessage.SetActive>())
                    tv.send(imeApp("app.$index") + RemoteMessage.ImeBatchEdit(index, index, emptyList()).encode() +
                        RemoteMessage.SetVolumeLevel("TV", index + 1, 20, false).encode())
                    await { session.volume.value.level == index + 1 }
                    session.openImePrompt()
                    session.sendImeText("search $index")
                    val edit = tv.take<RemoteMessage.ImeBatchEdit>()
                    assertEquals(index, edit.imeCounter)
                    assertEquals(index, edit.fieldCounter)
                    session.closeImePrompt()
                }
                // A field/app restart is allowed to reset both counters to zero.
                tv.send(imeApp("app.reset") + RemoteMessage.ImeBatchEdit(0, 0, emptyList()).encode() +
                    RemoteMessage.SetVolumeLevel("TV", 10, 20, false).encode())
                await { session.volume.value.level == 10 }
                session.openImePrompt()
                session.sendImeText("reset")
                assertEquals(0, tv.take<RemoteMessage.ImeBatchEdit>().imeCounter)
                // One invalid framed status cannot tear down the control socket.
                tv.send(byteArrayOf(0xA2.toByte(), 1, 3, 0x0A, 8, 0))
                tv.send(RemoteMessage.PingRequest(987).encode())
                await { tv.received.any { it == RemoteMessage.PingResponse(987) } }
                session.sendKey(AndroidTvKey.Home)
                assertEquals(RemoteKeyCode.HOME, tv.take<RemoteMessage.KeyInject>().keyCode)
                assertEquals(1, tv.connections.get())
                assertTrue(session.state.value is AndroidTvState.Active)
            } finally { session.close() }
        }
    }

    @Test fun immediateDropsReconnectAndManualDisconnectStopsRetries() = runBlocking {
        TestTv(dropBeforeReady = 1, dropAfterReady = 2).use { tv ->
            val session = AndroidTvSession(tv.device, AndroidTvCertFactory.generate(), tv.pin)
            try {
                session.connect()
                await { tv.connections.get() == 3 && session.state.value is AndroidTvState.Active }
                session.sendKey(AndroidTvKey.MediaPlayPause)
                assertEquals(RemoteKeyCode.MEDIA_PLAY_PAUSE, tv.take<RemoteMessage.KeyInject>().keyCode)
                session.disconnect()
                delay(2_000)
                assertEquals(AndroidTvState.Idle, session.state.value)
                assertEquals(3, tv.connections.get())
            } finally { session.close() }
        }
    }

    @Test fun changedCertificateIsRejectedWithoutRetryStorm() = runBlocking {
        TestTv().use { tv ->
            val session = AndroidTvSession(tv.device, AndroidTvCertFactory.generate(), "wrong-pin")
            try {
                session.connect()
                await { session.state.value is AndroidTvState.Error }
                assertTrue((session.state.value as AndroidTvState.Error).message.contains("cert changed"))
                delay(1_500)
                assertEquals(1, tv.connections.get())
                session.disconnect()
                session.resume()
                assertEquals(AndroidTvState.Idle, session.state.value)
            } finally { session.close() }
        }
    }

    @Test fun returningToPhoneAppRecoversWithoutNotification() = runBlocking {
        TestTv().use { tv ->
            val prefs = AndroidTvRemotePreferences(context)
            val oldPreference = prefs.notificationEnabled
            val store = AndroidTvCertStore(context)
            val previousPin = store.getServerPin(tv.device.host)
            var activity: MainActivity? = null
            try {
                store.pinServer(tv.device.host, tv.pin)
                prefs.notificationEnabled = false
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                lateinit var vm: AndroidTvViewModel
                instrumentation.runOnMainSync {
                    vm = ViewModelProvider(activity)[AndroidTvViewModel::class.java]
                    vm.selectDevice(tv.device)
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .setAction(AndroidTvRemoteService.ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                await { vm.currentState.value is AndroidTvState.Active }
                await { screenContains("Remote notification") }
                shell("input keyevent KEYCODE_HOME")
                shell("am start -a android.settings.SETTINGS")
                tv.rejectConnections = true
                tv.dropConnection()
                // Exhaust the retry budget while the phone is in another app.
                await(45_000) { vm.currentState.value is AndroidTvState.Error }
                tv.rejectConnections = false
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                await { vm.currentState.value is AndroidTvState.Active }
                await { screenContains("Remote notification") }
                instrumentation.runOnMainSync { vm.sendKey(AndroidTvKey.MediaPlayPause) }
                assertEquals(RemoteKeyCode.MEDIA_PLAY_PAUSE, tv.take<RemoteMessage.KeyInject>().keyCode)
                assertFalse(context.getSystemService(NotificationManager::class.java)
                    .activeNotifications.any { it.id == 6466 })
            } finally {
                instrumentation.runOnMainSync { activity?.finish() }
                instrumentation.waitForIdleSync()
                AndroidTvRemotes.forget(context, tv.device.host)
                if (previousPin != null) store.pinServer(tv.device.host, previousPin)
                prefs.notificationEnabled = oldPreference
            }
        }
    }

    @Test fun notificationControlsWorkAfterAppSwitchAndActivityDestruction() = runBlocking {
        TestTv().use { tv ->
            val prefs = AndroidTvRemotePreferences(context)
            val oldPreference = prefs.notificationEnabled
            val store = AndroidTvCertStore(context)
            val previousPin = store.getServerPin(tv.device.host)
            val notifications = context.getSystemService(NotificationManager::class.java)
            var activity: MainActivity? = null
            var vm: AndroidTvViewModel? = null
            try {
                shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                store.pinServer(tv.device.host, tv.pin)
                prefs.notificationEnabled = false
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                instrumentation.runOnMainSync {
                    vm = ViewModelProvider(activity)[AndroidTvViewModel::class.java]
                    vm!!.selectDevice(tv.device)
                }
                await { vm!!.currentState.value is AndroidTvState.Active }
                assertFalse(notifications.activeNotifications.any { it.id == 6466 })
                instrumentation.runOnMainSync { vm!!.setNotificationEnabled(true) }
                await { notifications.activeNotifications.any { n -> n.id == 6466 && n.notification.actions.size == 2 } }
                repeat(3) {
                    shell("input keyevent KEYCODE_HOME")
                    shell("am start -a android.settings.SETTINGS")
                    val notification = notifications.activeNotifications.single { it.id == 6466 }.notification
                    notification.actions.first { it.title.toString() == "Play / pause" }.actionIntent.send()
                    assertEquals(RemoteKeyCode.MEDIA_PLAY_PAUSE, tv.take<RemoteMessage.KeyInject>().keyCode)
                    notification.contentIntent.send()
                    await { vm!!.currentState.value is AndroidTvState.Active }
                }
                await { screenContains("Remote notification") }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    context.cacheDir.resolve("remote-controls-test.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                instrumentation.runOnMainSync { vm!!.setNotificationEnabled(false) }
                await { notifications.activeNotifications.none { it.id == 6466 } }
                await { AndroidTvRemotes.notificationRemote.value == null }
                assertTrue(vm!!.currentState.value is AndroidTvState.Active)
                instrumentation.runOnMainSync { vm!!.sendKey(AndroidTvKey.Home) }
                assertEquals(RemoteKeyCode.HOME, tv.take<RemoteMessage.KeyInject>().keyCode)
                instrumentation.runOnMainSync { vm!!.setNotificationEnabled(true) }
                await { notifications.activeNotifications.any { it.id == 6466 && it.notification.actions.size == 2 } }
                shell("input keyevent KEYCODE_HOME")
                shell("cmd statusbar expand-notifications")
                await { screenContains("TV remote") }
                if (!screenContains("Play / pause")) expandRemoteNotification()
                await { screenContains("Play / pause") }
                assertTrue(findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.contains("Play / pause") == true
                }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                assertEquals(RemoteKeyCode.MEDIA_PLAY_PAUSE, tv.take<RemoteMessage.KeyInject>().keyCode)
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    context.cacheDir.resolve("remote-notification-test.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                shell("cmd statusbar collapse")
                // The service owns the connection even when the Activity and VM are gone.
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                await { activity.isDestroyed }
                val notification = notifications.activeNotifications.single { it.id == 6466 }.notification
                notification.actions.first { it.title.toString() == "Play / pause" }.actionIntent.send()
                assertEquals(RemoteKeyCode.MEDIA_PLAY_PAUSE, tv.take<RemoteMessage.KeyInject>().keyCode)
                assertEquals(1, tv.connections.get())
                notification.actions.first { it.title.toString() == "Disconnect" }.actionIntent.send()
                await { notifications.activeNotifications.none { it.id == 6466 } }
                await { AndroidTvRemotes.notificationRemote.value == null }
            } finally {
                shell("cmd statusbar collapse")
                instrumentation.runOnMainSync {
                    AndroidTvRemoteService.hide(context)
                    activity?.finish()
                }
                await { AndroidTvRemotes.notificationRemote.value == null }
                AndroidTvRemotes.forget(context, tv.device.host)
                if (previousPin != null) store.pinServer(tv.device.host, previousPin)
                prefs.notificationEnabled = oldPreference
            }
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun screenContains(text: String): Boolean =
        findNode(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.contains(text) == true || it.contentDescription?.contains(text) == true
        } != null

    private fun expandRemoteNotification() {
        var parent = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.contains("TV remote") == true
        }?.parent
        while (parent != null) {
            val button = findNode(parent) {
                it.contentDescription?.toString()?.contains("Expand", ignoreCase = true) == true
            }
            if (button != null) {
                assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            parent = parent.parent
        }
        fail("Remote notification has no expand button")
    }

    private suspend fun await(timeoutMs: Long = 20_000, predicate: () -> Boolean) = withTimeout(timeoutMs) {
        while (!predicate()) delay(25)
    }

    private fun imeApp(name: String): ByteArray {
        fun field(number: Int, data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            AndroidTvFraming.writeVarint(this, ((number shl 3) or 2).toLong())
            AndroidTvFraming.writeVarint(this, data.size.toLong())
            write(data)
        }.toByteArray()
        return field(20, field(1, field(12, name.toByteArray())))
    }

    private class TestTv(val dropBeforeReady: Int = 0, val dropAfterReady: Int = 0) : Closeable {
        private val material = AndroidTvCertFactory.generate()
        val pin = MessageDigest.getInstance("SHA-256").digest(material.cert.encoded)
            .joinToString("") { "%02x".format(it) }
        private val pool = Executors.newCachedThreadPool()
        private val ticker = Executors.newSingleThreadScheduledExecutor()
        private val incoming = LinkedBlockingQueue<RemoteMessage>()
        val received = CopyOnWriteArrayList<RemoteMessage>()
        val connections = AtomicInteger()
        private val sockets = CopyOnWriteArrayList<SSLSocket>()
        @Volatile private var current: SSLSocket? = null
        @Volatile private var failure: Throwable? = null
        @Volatile private var closed = false
        @Volatile var rejectConnections = false

        fun dropConnection() { current?.close() }
        private val server: SSLServerSocket
        val device: AndroidTvDevice

        init {
            val password = "test-only".toCharArray()
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("tv", material.keyPair.private, password, arrayOf(material.cert))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password)
            }
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { assertTrue(chain.isNotEmpty()) }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf<TrustManager>(trust), null) }
            server = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            device = AndroidTvDevice("Remote regression TV", "127.0.0.1", server.localPort)
            pool.submit {
                while (!closed) {
                    val socket = try { server.accept() as SSLSocket } catch (_: Exception) { break }
                    val attempt = connections.incrementAndGet()
                    sockets += socket
                    pool.submit { serve(socket, attempt) }
                }
            }
            ticker.scheduleAtFixedRate({ runCatching { send(RemoteMessage.PingRequest(42).encode()) } }, 1, 1, TimeUnit.SECONDS)
        }

        private fun serve(socket: SSLSocket, attempt: Int) {
            try {
                socket.soTimeout = 10_000
                socket.startHandshake()
                assertTrue(socket.session.peerCertificates.isNotEmpty())
                if (rejectConnections || attempt == dropBeforeReady) return
                send(socket, RemoteMessage.Configure(1023, RemoteDeviceInfo("Test TV", "Test", packageName = "test", appVersion = "1")))
                assertEquals(615, (read(socket) as RemoteMessage.Configure).code1)
                send(socket, RemoteMessage.SetActive(1023))
                assertEquals(RemoteMessage.SetActive(615), read(socket))
                if (attempt != dropAfterReady) current = socket
                send(socket, RemoteMessage.StartedNotification(true))
                if (attempt == dropAfterReady) return
                while (!closed) {
                    val message = read(socket)
                    received += message
                    if (message !is RemoteMessage.PingResponse) incoming.put(message)
                }
            } catch (e: AssertionError) {
                failure = e
            } catch (_: java.io.IOException) {
                // Client closes on manual disconnect, pin mismatch or teardown.
            } finally {
                if (current === socket) current = null
                socket.close()
            }
        }

        private fun read(socket: SSLSocket) = RemoteMessage.decode(AndroidTvFraming.readFrame(socket.inputStream))
        private fun send(socket: SSLSocket, message: RemoteMessage) = synchronized(socket) {
            AndroidTvFraming.writeFrame(socket.outputStream, message.encode())
        }
        fun send(frame: ByteArray) {
            val socket = current ?: error("No active client")
            synchronized(socket) { AndroidTvFraming.writeFrame(socket.outputStream, frame) }
        }
        inline fun <reified T : RemoteMessage> take(): T {
            failure?.let { throw AssertionError("Test TV failed", it) }
            val message = incoming.poll(10, TimeUnit.SECONDS)
            assertTrue("Expected ${T::class.simpleName}, received $message", message is T)
            return message as T
        }
        override fun close() {
            closed = true
            server.close()
            ticker.shutdownNow()
            sockets.forEach { runCatching { it.close() } }
            pool.shutdownNow()
            failure?.let { throw AssertionError("Test TV failed", it) }
        }
    }
}
