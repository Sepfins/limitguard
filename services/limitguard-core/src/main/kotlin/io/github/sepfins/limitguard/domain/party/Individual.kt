package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.PartyId
import java.time.LocalDate

// One per party of type INDIVIDUAL.
data class Individual(
    val partyId: PartyId,
    val firstName: FirstName,
    val lastName: LastName,
    val birthDate: LocalDate? = null
)
