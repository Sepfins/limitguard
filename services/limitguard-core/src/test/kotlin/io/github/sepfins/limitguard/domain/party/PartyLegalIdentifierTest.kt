package io.github.sepfins.limitguard.domain.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PartyLegalIdentifierTest {
    @Test
    fun `country type and value are normalized for lookup`() {
        assertEquals("IT", JurisdictionCountry.of("it").value)
        assertEquals("CODICE_FISCALE", LegalIdentifierType.of("codice_fiscale").value)
        assertEquals("RSSMRA80A01H501U", LegalIdentifierValue.of(" rssmra80a01h501u ").value)
    }

    @Test
    fun `country must be ISO alpha 2`() {
        assertFailsWith<IllegalArgumentException> {
            JurisdictionCountry.of("ITA")
        }
    }

    @Test
    fun `identifier type must be stable symbolic code`() {
        assertFailsWith<IllegalArgumentException> {
            LegalIdentifierType.of("codice-fiscale")
        }
    }

    @Test
    fun `identifier value cannot be blank`() {
        assertFailsWith<IllegalArgumentException> {
            LegalIdentifierValue.of(" ")
        }
    }
}
