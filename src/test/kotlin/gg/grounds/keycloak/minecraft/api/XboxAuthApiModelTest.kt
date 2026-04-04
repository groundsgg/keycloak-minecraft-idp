package gg.grounds.keycloak.minecraft.api

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
    fun `response exposes user hash and gamertag`() {
        val response =
            XboxAuthApi.XboxAuthResponse(
                token = "xsts-token",
                displayClaims =
                    XboxAuthApi.DisplayClaims(
                        xui = listOf(XboxAuthApi.XuiClaim(uhs = "xsts-uhs", gtg = "GroundsTag"))
                    ),
            )

        assertEquals("xsts-uhs", response.userHash)
        assertEquals("GroundsTag", response.gamertag)
    }
}
