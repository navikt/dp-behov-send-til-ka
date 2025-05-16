package no.nav.dagpenger.klageinstans

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.kotest.assertions.json.shouldEqualSpecifiedJsonIgnoringOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

class KlageBehovløserTest {
    private val testRapid = TestRapid()

    val type = "ANKE"
    val behandlingId = UUIDv7.ny().toString()
    val ident = "11111111111"
    val fagsakId = UUIDv7.ny().toString()
    val behandlendeEnhet = "4408"
    val hjemler = listOf("FTRL_4_2", "FTRL_4_9", "FTRL_4_18")
    val prosessFullmektig =
        ProsessFullmektig(
            navn = "Djevelens Advokat",
            adresse =
                Adresse(
                    addresselinje1 = "Sydenveien 1",
                    addresselinje2 = "Poste restante",
                    addresselinje3 = "Teisen postkontor",
                    postnummer = "0666",
                    poststed = "Oslo",
                    land = "NO",
                ),
        )

    private val klageKlient =
        mockk<KlageHttpKlient>().also {
            coEvery {
                it.oversendKlageAnke(
                    behandlingId = behandlingId,
                    ident = ident,
                    fagsakId = fagsakId,
                    behandlendeEnhet = behandlendeEnhet,
                    hjemler = hjemler,
                )
            } returns Result.success(HttpStatusCode.OK)
        }

    @Test
    fun `Skal løse behov dersom filter matcher`() {
        KlageBehovløser(
            rapidsConnection = testRapid,
            klageKlient = klageKlient,
        )
        testRapid.sendBehov()
        testRapid.inspektør.size shouldBe 1
        testRapid.inspektør.message(0).toString() shouldEqualSpecifiedJsonIgnoringOrder
            """
            {
                "@event_name" : "behov",
                "@behov" : [ "OversendelseKlageinstans" ],
                "behandlingId" : "$behandlingId",
                "ident" : "$ident",
                "fagsakId" : "$fagsakId",
                "behandlendeEnhet" : "$behandlendeEnhet",
                "hjemler": ["FTRL_4_2", "FTRL_4_9", "FTRL_4_18"],
                "@løsning": {
                    "OversendelseKlageinstans": "OK"
                }
            }
            """.trimIndent()
    }

    private fun TestRapid.sendBehov() {
        this.sendTestMessage(
            """
            {
                "@event_name" : "behov",
                "@behov" : [ "OversendelseKlageinstans" ],
                "behandlingId" : "$behandlingId",
                "ident" : "$ident",
                "fagsakId" : "$fagsakId",
                "behandlendeEnhet" : "$behandlendeEnhet",
                "hjemler": ["FTRL_4_2", "FTRL_4_9", "FTRL_4_18"]
            }
            
            """.trimIndent(),
        )
    }
}
