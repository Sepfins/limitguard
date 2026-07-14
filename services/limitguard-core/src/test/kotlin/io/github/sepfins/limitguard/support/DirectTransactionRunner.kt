package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.port.transactions.TransactionRunner

class DirectTransactionRunner : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}
