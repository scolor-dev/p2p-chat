package com.p2pchat.app.service

import com.p2pchat.app.data.settings.FakeAppPreferencesRepository
import com.p2pchat.app.data.signaling.FakeSignalingClient
import com.p2pchat.app.data.signaling.SignalingClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** start / stop の呼び出しを記録する。 */
private class RecordingSignaling : SignalingClient by FakeSignalingClient() {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
}

private class FakeLauncher(var startResult: Boolean = true) : ForegroundServiceLauncher {
    val calls = mutableListOf<String>()
    override fun start(): Boolean { calls += "start"; return startResult }
    override fun stop() { calls += "stop" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnectionControllerTest {
    private val prefs = FakeAppPreferencesRepository(backgroundCommsEnabled = true)
    private val signaling = RecordingSignaling()
    private val launcher = FakeLauncher()

    private fun TestScope.controller() = BackgroundConnectionController(
        prefs, signaling, launcher, TestScope(UnconfinedTestDispatcher(testScheduler)),
    ).also { it.start() }

    private fun clear() {
        signaling.calls.clear()
        launcher.calls.clear()
    }

    @Test
    fun 前面に来たらシグナリングを始めサービスは止める() = runTest {
        val controller = controller()

        controller.onForeground()

        assertEquals(listOf("start"), signaling.calls)
        assertEquals(listOf("stop"), launcher.calls)
    }

    @Test
    fun 前面か背面かが分かるまでは何もしない() = runTest {
        controller()

        assertEquals(emptyList<String>(), signaling.calls)
        assertEquals(emptyList<String>(), launcher.calls)
    }

    @Test
    fun 設定オンで背面に回ったらサービスを開始しシグナリングは止めない() = runTest {
        val controller = controller()
        controller.onForeground()
        clear()

        controller.onBackground()

        assertEquals(listOf("start"), launcher.calls)
        assertEquals(emptyList<String>(), signaling.calls)
    }

    @Test
    fun 設定オフで背面に回ったらシグナリングを止めサービスは開始しない() = runTest {
        prefs.backgroundCommsEnabled.value = false
        val controller = controller()
        controller.onForeground()
        clear()

        controller.onBackground()

        assertEquals(listOf("stop"), signaling.calls)
        assertEquals(listOf("stop"), launcher.calls)
    }

    @Test
    fun サービスの開始が許されなければ前面のみの動作に落としてシグナリングを止める() = runTest {
        launcher.startResult = false
        val controller = controller()
        controller.onForeground()
        clear()

        controller.onBackground()

        assertEquals(listOf("start"), launcher.calls)
        assertEquals(listOf("stop"), signaling.calls)
    }

    @Test
    fun 背面でオフにするとサービスとシグナリングを止める() = runTest {
        val controller = controller()
        controller.onForeground()
        controller.onBackground()
        clear()

        prefs.backgroundCommsEnabled.value = false

        assertEquals(listOf("stop"), launcher.calls)
        assertEquals(listOf("stop"), signaling.calls)
    }

    @Test
    fun 背面でオンにするとサービスを開始する() = runTest {
        prefs.backgroundCommsEnabled.value = false
        val controller = controller()
        controller.onForeground()
        controller.onBackground()
        clear()

        prefs.backgroundCommsEnabled.value = true

        assertEquals(listOf("start"), launcher.calls)
    }

    @Test
    fun 前面でオンオフを切り替えてもシグナリングは止まらない() = runTest {
        val controller = controller()
        controller.onForeground()
        clear()

        prefs.backgroundCommsEnabled.value = false

        assertEquals(emptyList<String>(), signaling.calls.filter { it == "stop" })
    }

    @Test
    fun 背面でOSがサービスを止めたらシグナリングを止める() = runTest {
        val controller = controller()
        controller.onForeground()
        controller.onBackground()
        clear()

        controller.onServiceStopped()

        assertEquals(listOf("stop"), signaling.calls)
    }

    @Test
    fun 前面のときにサービスが止まってもシグナリングは止めない() = runTest {
        val controller = controller()
        controller.onForeground()
        clear()

        controller.onServiceStopped()

        assertEquals(emptyList<String>(), signaling.calls)
    }

    @Test
    fun 背面から前面に戻るとシグナリングを始めサービスを止める() = runTest {
        val controller = controller()
        controller.onForeground()
        controller.onBackground()
        clear()

        controller.onForeground()

        assertEquals(listOf("start"), signaling.calls)
        assertEquals(listOf("stop"), launcher.calls)
    }
}
