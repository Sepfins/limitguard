package io.github.sepfins.limitguard.port.transactions

interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T

    /**
     * Runs one recoverable unit inside the surrounding transaction.
     * Implementations must roll back a failed block to its item boundary
     * before rethrowing so the outer transaction can continue.
     */
    fun <T> inSavepoint(block: () -> T): T
}
