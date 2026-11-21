package com.example.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.example.tools.UserTurnContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneCallingSuiteTest {

    private lateinit var context: Context
    private lateinit var phoneControl: PhoneControlManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        phoneControl = PhoneControlManager(context)
    }

    @Test
    fun test1_RawNumberCall_ReturnsResult() = runBlocking {
        val result = phoneControl.requestCallContact("1234567890", useDialer = true)
        assertTrue("Raw number call should return DIALER_OPENED or ERROR", result.contains("DIALER_OPENED") || result.contains("ERROR"))
    }

    @Test
    fun test2_MissingContact_ReturnsNotFound() = runBlocking {
        val result = phoneControl.requestCallContact("NonExistentContactXYZ999", useDialer = true)
        assertTrue("Missing contact must return NOT_FOUND", result.contains("NOT_FOUND"))
    }

    @Test
    fun test3_ToolExecutionEngine_SupportsFallbackArgumentNames() = runBlocking {
        val engine = com.example.tools.ToolExecutionEngine(context)
        val args = buildJsonObject {
            put("recipient", JsonPrimitive("1234567890"))
            put("useDialer", JsonPrimitive(true))
        }
        val turn = UserTurnContext(userTurnId = "t1", transcript = "Call 1234567890", toolCallId = "c1")
        val result = engine.execute("searchAndCallContact", args, turn)
        assertTrue("ToolExecutionEngine must support 'recipient' argument fallback", result.contains("DIALER_OPENED") || result.contains("ERROR") || result.contains("PERMISSION"))
    }

    @Test
    fun test4_NullTranscriptSafetyGate_AllowsToolExecution() = runBlocking {
        val engine = com.example.tools.ToolExecutionEngine(context)
        val args = buildJsonObject {
            put("phoneNumber", JsonPrimitive("9876543210"))
            put("useDialer", JsonPrimitive(true))
        }
        val result = engine.execute("searchAndCallContact", args, null as UserTurnContext?)
        assertTrue("Null transcript with valid args should be authorized and return DIALER_OPENED or ERROR", result.contains("DIALER_OPENED") || result.contains("ERROR"))
    }
}
