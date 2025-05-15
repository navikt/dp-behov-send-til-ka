package no.nav.dagpenger.klageinstans

import com.fasterxml.jackson.databind.JsonNode
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.River.PacketListener
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import mu.withLoggingContext

private val logger = KotlinLogging.logger {}
private val sikkerlogg = KotlinLogging.logger("tjenestekall")

internal class KlageBehovløser(
    rapidsConnection: RapidsConnection,
    private val klageKlient: KlageHttpKlient,
) : PacketListener {
    companion object {
        val rapidFilter: River.() -> Unit = {
            precondition {
                it.requireValue("@event_name", "behov")
                it.requireAll("@behov", listOf("OversendelseKlageinstans"))
                it.forbid("@løsning")
            }
            validate { it.requireKey("ident", "behandlingId", "fagsakId", "behandlendeEnhet", "hjemler") }
            validate { it.interestedIn("tilknyttedeJournalposter", "prosessFullmektig") }
        }
    }

    init {
        River(rapidsConnection).apply(rapidFilter).register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val behandlingId = packet["behandlingId"].asText()
        val ident = packet["ident"].asText()
        val fagsakId = packet["fagsakId"].asText()
        val behandlendeEnhet = packet["behandlendeEnhet"].asText()
        val hjemler = packet["hjemler"].map { it.asText() }
        val tilknyttedeJournalposter: List<Journalposter> = packet["tilknyttedeJournalposter"].takeIf(JsonNode::isArray)?.map {
            it.takeIf(JsonNode::isObject).let { jp ->
                Journalposter(
                    jp?.get("type")!!.asText(),
                    jp.get("journalpostId")!!.asText()
                )
            }
        } ?: emptyList()
        val prosessFullmektig: ProsessFullmektig? = packet["prosessFullmektig"].takeIf(JsonNode::isObject).let {
            ProsessFullmektig(
                id = it?.get("id")?.let { id ->
                    PersonIdentId(
                        verdi = id["verdi"].asText(),
                    )
                },
                navn = it?.get("navn")?.asText(),
                adresse = it?.get("adresse")?.let { adresse ->
                    Adresse(
                        addresselinje1 = adresse.get("adresselinje1")?.asText(),
                        addresselinje2 = adresse.get("adresselinje2")?.asText(),
                        addresselinje3 = adresse.get("adresselinje3")?.asText(),
                        postnummer = adresse.get("postnummer")?.asText(),
                        poststed = adresse.get("poststed")?.asText(),
                        land = adresse.get("land")!!.asText()
                    )
                }
            )
        }
        withLoggingContext("behandlingId" to "$behandlingId") {
            runBlocking {
                klageKlient.registrerKlage(
                    behandlingId = behandlingId,
                    ident = ident,
                    fagsakId = fagsakId,
                    behandlendeEnhet = behandlendeEnhet,
                    hjemler = hjemler,
                    tilknyttedeJournalposter = tilknyttedeJournalposter,
                    prosessFullmektig = prosessFullmektig
                )
            }.also { resultat ->
                when (resultat.isSuccess) {
                    true -> {
                        logger.info { "Klage er oversendt til klageinstans $behandlingId" }
                        packet["@løsning"] = mapOf("OversendelseKlageinstans" to "OK")
                    }

                    false -> {
                        logger.info { "Feil ved oversendelse til klageinstans for behandling $behandlingId" }
                        throw RuntimeException("Feil ved oversendelse av klage til klageinstans $behandlingId")
                    }
                }
            }
        }
    }
}
