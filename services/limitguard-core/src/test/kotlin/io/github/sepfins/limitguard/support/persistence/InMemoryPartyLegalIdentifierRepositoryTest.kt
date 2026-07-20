package io.github.sepfins.limitguard.support.persistence

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyLegalIdentifierId
import io.github.sepfins.limitguard.domain.party.JurisdictionCountry
import io.github.sepfins.limitguard.domain.party.LegalIdentifierType
import io.github.sepfins.limitguard.domain.party.LegalIdentifierValue
import io.github.sepfins.limitguard.domain.party.PartyLegalIdentifier
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryPartyLegalIdentifierRepositoryTest {
    private val repository = InMemoryPartyLegalIdentifierRepository()
    private val italy = JurisdictionCountry.of("IT")
    private val codiceFiscale = LegalIdentifierType.CODICE_FISCALE
    private val identifierValue = LegalIdentifierValue.of("RSSMRA80A01H501U")

    @Test
    fun `finds legal identifier by global value`() {
        val identifier = partyLegalIdentifier(
            id = UUID(0L, 1L),
            partyId = UUID(0L, 10L),
            value = identifierValue,
        )

        repository.insert(identifier)

        assertEquals(identifier, repository.findByValue(italy, codiceFiscale, identifierValue))
    }

    @Test
    fun `same legal identifier value cannot belong to another party`() {
        repository.insert(
            partyLegalIdentifier(
                id = UUID(0L, 1L),
                partyId = UUID(0L, 10L),
                value = identifierValue,
            ),
        )

        assertFailsWith<ConflictException> {
            repository.insert(
                partyLegalIdentifier(
                    id = UUID(0L, 2L),
                    partyId = UUID(0L, 11L),
                    value = identifierValue,
                ),
            )
        }
    }

    @Test
    fun `same party cannot have two values for one country and type`() {
        val partyId = UUID(0L, 10L)
        repository.insert(
            partyLegalIdentifier(
                id = UUID(0L, 1L),
                partyId = partyId,
                value = identifierValue,
            ),
        )

        assertFailsWith<ConflictException> {
            repository.insert(
                partyLegalIdentifier(
                    id = UUID(0L, 2L),
                    partyId = partyId,
                    value = LegalIdentifierValue.of("BNCLRA85C45F205Z"),
                ),
            )
        }
    }

    private fun partyLegalIdentifier(
        id: UUID,
        partyId: UUID,
        value: LegalIdentifierValue,
    ): PartyLegalIdentifier =
        PartyLegalIdentifier(
            id = PartyLegalIdentifierId.of(id),
            partyId = PartyId.of(partyId),
            jurisdictionCountry = italy,
            identifierType = codiceFiscale,
            normalizedIdentifier = value,
        )
}
