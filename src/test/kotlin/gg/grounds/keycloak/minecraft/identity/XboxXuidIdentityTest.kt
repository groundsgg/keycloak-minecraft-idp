package gg.grounds.keycloak.minecraft.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.keycloak.broker.provider.IdentityBrokerException

class XboxXuidIdentityTest {

    @Test
    fun `missing XID does not create a Bedrock UUID`() {
        assertNull(XboxXuidIdentity.resolveBedrockUuid(emptyList()))
    }

    @Test
    fun `minimum and maximum positive long XIDs use Floodgate UUID layout`() {
        assertEquals(
            "00000000-0000-0000-0000-000000000001",
            XboxXuidIdentity.resolveBedrockUuid(listOf("1")),
        )
        assertEquals(
            "00000000-0000-0000-7fff-ffffffffffff",
            XboxXuidIdentity.resolveBedrockUuid(listOf(Long.MAX_VALUE.toString())),
        )
    }

    @Test
    fun `repeated equal XIDs produce one Bedrock UUID`() {
        assertEquals(
            "00000000-0000-0000-0000-00000000002a",
            XboxXuidIdentity.resolveBedrockUuid(listOf("42", "42", "42")),
        )
    }

    @Test
    fun `malformed present XIDs are rejected`() {
        listOf("", " ", "0", "-1", "+1", "01", "1 ", "9223372036854775808", "xboxptx-1").forEach {
            xid ->
            assertFailsWith<IdentityBrokerException>(xid) {
                XboxXuidIdentity.resolveBedrockUuid(listOf(xid))
            }
        }
    }

    @Test
    fun `different verified XIDs are rejected as ambiguous`() {
        assertFailsWith<IdentityBrokerException> {
            XboxXuidIdentity.resolveBedrockUuid(listOf("42", "43"))
        }
    }
}
