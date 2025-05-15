package no.nav.dagpenger.klageinstans

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.prometheus.metrics.model.registry.PrometheusRegistry
import mu.KotlinLogging
import no.nav.dagpenger.ktor.client.metrics.PrometheusMetricsPlugin
import java.time.LocalDate
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

class KlageHttpKlient(
    private val klageApiUrl: String,
    private val tokenProvider: () -> String,
    private val httpClient: HttpClient = httpClient(),
) {
    suspend fun registrerKlage(
        behandlingId: String,
        ident: String,
        fagsakId: String,
        behandlendeEnhet: String,
        hjemler: List<String>,
        tilknyttedeJournalposter: List<Journalposter> = emptyList(),
        prosessFullmektig: ProsessFullmektig? = null,
    ): Result<HttpStatusCode> {
        return runCatching {
            httpClient.post(urlString = "$klageApiUrl/api/oversendelse/v4/sak") {
                header(HttpHeaders.Authorization, "Bearer ${tokenProvider.invoke()}")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    KlageinstansOversendelse(
                        sakenGjelder =
                            PersonIdent(
                                id =
                                    PersonIdentId(
                                        verdi = ident,
                                    ),
                            ),
                        fagsak =
                            Fagsak(
                                fagsakId = fagsakId,
                                fagsystem = "DAGPENGER",
                            ),
                        kildeReferanse = behandlingId,
                        forrigeBehandlendeEnhet = behandlendeEnhet,
                        tilknyttedeJournalposter = tilknyttedeJournalposter,
                        hjemler = hjemler,
                        prosessFullmektig = prosessFullmektig,
                    ),
                )
            }.status
        }.onFailure { throwable ->
            logger.error(throwable) { "Kall til kabal api feilet for klagebehandling med id: ${behandlingId}" }
        }
    }
}


private data class KlageinstansOversendelse(
    val type: String = "KLAGE",
    val sakenGjelder: PersonIdent,
    val klager: PersonIdent? = null,
    val prosessFullmektig: ProsessFullmektig? = null,
    val fagsak: Fagsak,
    val kildeReferanse: String,
    val dvhReferanse: String? = null,
    val hjemler: List<String>,
    val forrigeBehandlendeEnhet: String,
    val tilknyttedeJournalposter: List<Journalposter>,
    val brukersKlageMottattVedtaksinstans: String? = null,
    val frist: LocalDate? = null,
    val sakMottattKaTidspunkt: LocalDateTime? = null,
    val ytelse: String = "DAG_DAG",
    val kommentar: String? = null,
    val hindreAutomatiskSvarbrev: Boolean? = null,
    val saksbehandlerIdentForTildeling: String? = null,
)

data class Journalposter(
    val type: String,
    val journalpostId: String,
)

private data class Fagsak(
    val fagsakId: String,
    val fagsystem: String,
)

internal data class PersonIdent(
    val id: PersonIdentId,
)

data class ProsessFullmektig(
    val id: PersonIdentId?,
    val navn: String?,
    val adresse: Adresse?,
)

data class PersonIdentId(
    val type: String = "PERSON",
    val verdi: String,
)

data class Adresse(
    val addresselinje1: String?,
    val addresselinje2: String?,
    val addresselinje3: String?,
    val postnummer: String?,
    val poststed: String?,
    val land: String = "NO",
)

fun httpClient(
    prometheusRegistry: PrometheusRegistry = PrometheusRegistry.defaultRegistry,
    engine: HttpClientEngine = CIO.create { },
) = HttpClient(engine) {
    expectSuccess = true

    install(ContentNegotiation) {
        jackson {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }

    install(PrometheusMetricsPlugin) {
        this.baseName = "dp_send_til_ka_klage_http_klient"
        this.registry = prometheusRegistry
    }
}
