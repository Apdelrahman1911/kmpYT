package dev.kmpyt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class IosExportDeadlineTest {
    @Test fun terminalStatusCompletesEvenWithoutTheCallback() = iosTest {
        awaitIosExport({}, { IosExportState.COMPLETED }, CompletableDeferred(),
            { fail("Completed export was cancelled") }, { fail("Completed export retained its files") })
    }

    @Test fun nativeFailureIsReportedWithoutPublishingOrCancellingAgain() = iosTest {
        val failure = assertFailsWith<DownloadProcessingException> {
            awaitIosExport({}, { IosExportState.FAILED }, CompletableDeferred(),
                { fail("Failed export was cancelled") }, { fail("Failed export retained its files") })
        }
        assertEquals(DownloadProcessingException.Reason.EXPORT_FAILED, failure.reason)
        assertFalse(failure.restartRequired)
    }

    @Test fun timeoutCancelsAndAllowsRetryWhenTheExporterDrains() = iosTest {
        val state = MutableStateFlow(IosExportState.ACTIVE)
        val failure = assertFailsWith<DownloadProcessingException> {
            awaitIosExport({}, { state.value }, CompletableDeferred(), { state.value = IosExportState.CANCELLED },
                { fail("Acknowledged cancellation retained its files") }, timeoutMs = 30, drainMs = 500)
        }
        assertEquals(DownloadProcessingException.Reason.EXPORT_TIMEOUT, failure.reason)
        assertFalse(failure.restartRequired)
        assertEquals(IosExportState.CANCELLED, state.value)
    }

    @Test fun aCallbackWithoutTerminalStatusCannotReleaseStaging() = iosTest {
        var retained = false
        var cancelled = false
        val callback = CompletableDeferred<Unit>()
        val failure = assertFailsWith<DownloadProcessingException> {
            awaitIosExport({ callback.complete(Unit) }, { IosExportState.ACTIVE }, callback,
                { cancelled = true }, { retained = true }, timeoutMs = 30, drainMs = 30)
        }
        assertTrue(cancelled && retained && failure.restartRequired)
    }

    @Test fun callerCancellationCancelsTheSameExporterAndDrainsIt() = iosTest {
        val started = CompletableDeferred<Unit>()
        val state = MutableStateFlow(IosExportState.ACTIVE)
        coroutineScope {
            val job = async {
                awaitIosExport({ started.complete(Unit) }, { state.value }, CompletableDeferred(),
                    { state.value = IosExportState.CANCELLED }, { fail("Export drained") })
            }
            started.await()
            job.cancelAndJoin()
            assertEquals(IosExportState.CANCELLED, state.value)
        }
    }
}
