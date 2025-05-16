package no.nav.dagpenger.klageinstans

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import io.kotest.assertions.json.shouldEqualSpecifiedJsonIgnoringOrder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
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

    @Test
    fun `Skal løse behov dersom filter matcher`() {
        val klageKlient =
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

    @Test
    fun `Skal løse behov med fullmektig dersom filter matcher`() {
        val klageKlient =
            mockk<KlageHttpKlient>().also {
                coEvery {
                    it.oversendKlageAnke(
                        behandlingId = behandlingId,
                        ident = ident,
                        fagsakId = fagsakId,
                        behandlendeEnhet = behandlendeEnhet,
                        hjemler = hjemler,
                        prosessFullmektig = prosessFullmektig,
                    )
                } returns Result.success(HttpStatusCode.OK)
            }

        KlageBehovløser(
            rapidsConnection = testRapid,
            klageKlient = klageKlient,
        )
        testRapid.sendBehovMedFullmektig()
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
                "prosessfullmektigNavn": "Djevelens Advokat",
                "prosessfullmektigAdresselinje1": "Sydenveien 1",
                "prosessfullmektigAdresselinje2": "Poste restante",
                "prosessfullmektigAdresselinje3": "Teisen postkontor",
                "prosessfullmektigPostnummer": "0666",
                "prosessfullmektigPoststed": "Oslo",
                "prosessfullmektigLand": "NO",
                "@løsning": {
                    "OversendelseKlageinstans": "OK"
                }
            }
            """.trimIndent()
    }

    @Test
    fun `Bad request fører til runtime exception`() {
        val klageKlient =
            KlageHttpKlient(
                klageApiUrl = "http://localhost:8080",
                tokenProvider = { " " },
                httpClient =
                    httpClient(
                        engine =
                            MockEngine {
                                respondBadRequest()
                            },
                    ),
            )
        KlageBehovløser(
            rapidsConnection = testRapid,
            klageKlient = klageKlient,
        )
        shouldThrow<RuntimeException> {
            testRapid.sendBehov()
        }
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

    private fun TestRapid.sendBehovMedFullmektig() {
        this.sendTestMessage(
            """
            {
                "@event_name" : "behov",
                "@behov" : [ "OversendelseKlageinstans" ],
                "behandlingId" : "$behandlingId",
                "ident" : "$ident",
                "fagsakId" : "$fagsakId",
                "behandlendeEnhet" : "$behandlendeEnhet",
                "hjemler": ["FTRL_4_2", "FTRL_4_9", "FTRL_4_18"],
                "prosessfullmektigNavn": "Djevelens Advokat",
                "prosessfullmektigAdresselinje1": "Sydenveien 1",
                "prosessfullmektigAdresselinje2": "Poste restante",
                "prosessfullmektigAdresselinje3": "Teisen postkontor",
                "prosessfullmektigPostnummer": "0666",
                "prosessfullmektigPoststed": "Oslo",
                "prosessfullmektigLand": "NO"
            }
            
            """.trimIndent(),
        )
    }
}
