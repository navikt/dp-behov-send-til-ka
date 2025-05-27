package no.nav.dagpenger.klageinstans

import com.fasterxml.jackson.databind.JsonNode
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.River.PacketListener
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
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
            validate {
                it.requireKey(
                    "ident",
                    "behandlingId",
                    "fagsakId",
                    "behandlendeEnhet",
                    "hjemler",
                    "opprettet",
                )
            }
            validate {
                it.interestedIn(
                    "kommentar",
                    "tilknyttedeJournalposter",
                    "prosessfullmektigNavn",
                    "prosessfullmektigIdent",
                    "prosessfullmektigAdresselinje1",
                    "prosessfullmektigAdresselinje2",
                    "prosessfullmektigAdresselinje3",
                    "prosessfullmektigPostnummer",
                    "prosessfullmektigPoststed",
                    "prosessfullmektigLand",
                )
            }
        }
    }

    init {
        River(rapidsConnection).apply(rapidFilter).register(this)
    }

    override fun onError(
        problems: MessageProblems,
        context: MessageContext,
        metadata: MessageMetadata,
    ) {
        logger.error("Skjønte ikke meldinga\n$problems")
    }

    override fun onSevere(
        error: MessageProblems.MessageException,
        context: MessageContext,
    ) {
        logger.error("Skjønte ikke meldinga\n$error")
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val behandlingId = packet["behandlingId"].asText()
        if (behandlingId in setOf("0196fcc3-75e1-7846-ab74-de339f20ebc3", "0196fcd0-875b-7868-a783-68a16e98b6d7")) {
            logger.info { "Skipper oversendelse av klagebehandling $behandlingId" }
            return
        }

        val ident = packet["ident"].asText()
        val fagsakId = packet["fagsakId"].asText()
        val opprettet = packet["opprettet"].asLocalDate()
        val behandlendeEnhet = packet["behandlendeEnhet"].asText()
        val hjemler = packet["hjemler"].map { it.asText() }
        val tilknyttedeJournalposter: List<Journalposter> =
            packet["tilknyttedeJournalposter"].takeIf(JsonNode::isArray)?.map {
                it.takeIf(JsonNode::isObject).let { jp ->
                    Journalposter(
                        jp?.get("type")!!.asText(),
                        jp.get("journalpostId")!!.asText(),
                    )
                }
            } ?: emptyList()
        val kommentar = packet["kommentar"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigNavn = packet["prosessfullmektigNavn"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigIdent = packet["prosessfullmektigIdent"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigAdresselinje1 =
            packet["prosessfullmektigAdresselinje1"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigAdresselinje2 =
            packet["prosessfullmektigAdresselinje2"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigAdresselinje3 =
            packet["prosessfullmektigAdresselinje3"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigPostnummer = packet["prosessfullmektigPostnummer"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigPoststed = packet["prosessfullmektigPoststed"].takeIf(JsonNode::isTextual)?.asText()
        val prosessfullmektigLand = packet["prosessfullmektigLand"].takeIf(JsonNode::isTextual)?.asText()

        val prosessFullmektig =
            if (!prosessfullmektigNavn.isNullOrBlank() || !prosessfullmektigIdent.isNullOrBlank()) {
                ProsessFullmektig(
                    id = if (!prosessfullmektigIdent.isNullOrBlank()) PersonIdentId(verdi = prosessfullmektigIdent) else null,
                    navn = prosessfullmektigNavn,
                    adresse =
                        if (!prosessfullmektigLand.isNullOrBlank()) {
                            Adresse(
                                addresselinje1 = prosessfullmektigAdresselinje1,
                                addresselinje2 = prosessfullmektigAdresselinje2,
                                addresselinje3 = prosessfullmektigAdresselinje3,
                                postnummer = prosessfullmektigPostnummer,
                                poststed = prosessfullmektigPoststed,
                                land = prosessfullmektigLand,
                            )
                        } else {
                            null
                        },
                )
            } else {
                null
            }

        withLoggingContext("behandlingId" to "$behandlingId") {
            runBlocking {
                klageKlient.oversendKlageAnke(
                    behandlingId = behandlingId,
                    ident = ident,
                    fagsakId = fagsakId,
                    behandlendeEnhet = behandlendeEnhet,
                    hjemler = hjemler,
                    tilknyttedeJournalposter = tilknyttedeJournalposter,
                    prosessFullmektig = prosessFullmektig,
                    opprettet = opprettet,
                    kommentar = kommentar,
                )
            }.also { resultat ->
                when (resultat.isSuccess) {
                    true -> {
                        logger.info { "Klage er oversendt til klageinstans for behandling $behandlingId" }
                        packet["@løsning"] = mapOf("OversendelseKlageinstans" to "OK")
                        context.publish(key = ident, message = packet.toJson())
                    }

                    false -> {
                        logger.info { "Feil ved oversendelse til klageinstans for behandling $behandlingId" }
                        throw RuntimeException("Feil ved oversendelse av klage til klageinstans for behandling $behandlingId")
                    }
                }
            }
        }
    }
}
