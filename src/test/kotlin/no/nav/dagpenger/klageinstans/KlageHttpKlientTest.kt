package no.nav.dagpenger.klageinstans

import io.kotest.assertions.json.shouldEqualSpecifiedJsonIgnoringOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KlageHttpKlientTest {
    private val prometheusRegistry = PrometheusRegistry()
    private val type = "ANKE"
    private val behandlingId = UUIDv7.ny().toString()
    private val ident = "11111111111"
    private val fagsakId = UUIDv7.ny().toString()
    private val behandlendeEnhet = "4408"
    private val hjemler = listOf("FTRL_4_2", "FTRL_4_9", "FTRL_4_18")
    private val prosessFullmektig =
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
    fun `Oversend klage til klageinstans`() {
        val nå = LocalDate.now()
        var requestBody: String? = null
        val mockEngine =
            MockEngine { request ->
                request.headers[HttpHeaders.Authorization] shouldBe "Bearer token"
                request.url.host shouldBe "localhost"
                request.url.segments shouldBe listOf("api", "oversendelse", "v4", "sak")
                requestBody = request.body.toByteArray().toString(Charsets.UTF_8)
                respondOk("")
            }

        val kabalKlient =
            KlageHttpKlient(
                klageApiUrl = "http://localhost:8080",
                tokenProvider = { "token" },
                httpClient =
                    httpClient(
                        prometheusRegistry = prometheusRegistry,
                        engine = mockEngine,
                    ),
            )

        val resultat: Result<HttpStatusCode> =
            runBlocking {
                kabalKlient.oversendKlageAnke(
                    type = type,
                    behandlingId = behandlingId,
                    ident = ident,
                    fagsakId = fagsakId,
                    behandlendeEnhet = behandlendeEnhet,
                    hjemler = hjemler,
                    prosessFullmektig = prosessFullmektig,
                    opprettet = nå,
                )
            }
        resultat shouldBe Result.success(HttpStatusCode.OK)
        requestBody?.shouldEqualSpecifiedJsonIgnoringOrder(
            (
                """
                {
                  "type": "$type",
                  "sakenGjelder": {
                    "id": {
                      "type": "PERSON",
                      "verdi": "$ident"
                    }
                  },
                  "prosessFullmektig": {
                    "navn": "${prosessFullmektig.navn}",
                    "adresse": {
                      "addresselinje1": "${prosessFullmektig.adresse!!.addresselinje1}",
                      "addresselinje2": "${prosessFullmektig.adresse.addresselinje2}",
                      "addresselinje3": "${prosessFullmektig.adresse.addresselinje3}",
                      "postnummer": "${prosessFullmektig.adresse.postnummer}",
                      "poststed": "${prosessFullmektig.adresse.poststed}",
                      "land": "${prosessFullmektig.adresse.land}"
                    }
                  },
                  "fagsak": {
                    "fagsakId": "$fagsakId",
                    "fagsystem": "DAGPENGER"
                  },
                  "kildeReferanse": "$behandlingId",
                  "hjemler": [
                    "FTRL_4_2",
                    "FTRL_4_9",
                    "FTRL_4_18"
                  ],
                  "forrigeBehandlendeEnhet": "$behandlendeEnhet",
                  "brukersKlageMottattVedtaksinstans": "$nå",
                  "tilknyttedeJournalposter": [],
                  "ytelse": "DAG_DAG"
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `Bad request fører til failure-resultat`() {
        val kabalKlient =
            KlageHttpKlient(
                klageApiUrl = "http://localhost:8080",
                tokenProvider = { " " },
                httpClient =
                    httpClient(
                        prometheusRegistry = prometheusRegistry,
                        engine =
                            MockEngine {
                                respondBadRequest()
                            },
                    ),
            )

        val resultat: Result<HttpStatusCode> =
            runBlocking {
                kabalKlient.oversendKlageAnke(
                    type = type,
                    behandlingId = behandlingId,
                    ident = ident,
                    fagsakId = fagsakId,
                    behandlendeEnhet = behandlendeEnhet,
                    hjemler = hjemler,
                    prosessFullmektig = prosessFullmektig,
                    opprettet = LocalDate.now(),
                )
            }
        resultat.isFailure shouldBe true
    }
}
