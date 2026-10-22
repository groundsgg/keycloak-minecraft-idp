package gg.grounds.keycloak.minecraft.api

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XboxAuthApiModelTest {

    @Test
    fun `buildMessage maps known Xbox error code`() {
        val message = XboxAuthException.buildMessage(2148916238L, null)

        assertEquals("This is a child account and needs to be added to a family.", message)
    }

    @Test
    fun `buildMessage includes redirect when present`() {
        val message = XboxAuthException.buildMessage(2148916233L, "https://www.xbox.com/en-US/live")

        assertContains(message, "This Microsoft account has no Xbox account.")
        assertContains(message, "https://www.xbox.com/en-US/live")
    }

    @Test
    fun `buildMessage falls back to generic message for unknown codes`() {
        val message = XboxAuthException.buildMessage(999L, null)

        assertEquals("Xbox Live authentication failed (Error: 999)", message)
    }

    @Test
    fun `response exposes user hash gamertag and partner xbox user id`() {
        val response =
            XboxAuthApi.XboxAuthResponse(
                token = "xsts-token",
                displayClaims =
                    XboxAuthApi.DisplayClaims(
                        xui =
                            listOf(
                                XboxAuthApi.XuiClaim(
                                    uhs = "xsts-uhs",
                                    gtg = "GroundsTag",
                                    ptx = "partner-123",
                                    xid = "281467",
                                ),
                            ),
                    ),
            )

        assertEquals("xsts-uhs", response.userHash)
        assertEquals("GroundsTag", response.gamertag)
        assertEquals("partner-123", response.partnerXboxUserId)
        assertEquals(listOf("281467"), response.xboxUserIds)
        assertEquals(setOf("uhs", "gtg", "ptx", "xid"), response.displayClaimKeys)
    }

    @Test
    fun `JSON response preserves all XID claims for ambiguity checks`() {
        val response =
            ObjectMapper()
                .readValue(
                    """{"Token":"token","DisplayClaims":{"xui":[{"xid":"42"},{"xid":"43"}]}}""",
                    XboxAuthApi.XboxAuthResponse::class.java,
                )

        assertEquals(listOf("42", "43"), response.xboxUserIds)
    }
}
