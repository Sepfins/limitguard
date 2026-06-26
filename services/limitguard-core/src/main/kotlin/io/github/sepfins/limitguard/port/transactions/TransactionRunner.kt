package io.github.sepfins.limitguard.port.transactions

interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}