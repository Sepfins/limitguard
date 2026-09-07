package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

/**
 * Shares the transaction-scoped jOOQ configuration between a transaction
 * runner and its repositories.
 */
class JooqPersistenceContext(
    private val rootDsl: DSLContext,
) {
    constructor(dataSource: DataSource) : this(DSL.using(dataSource, SQLDialect.POSTGRES))

    init {
        require(rootDsl.dialect().family() == SQLDialect.POSTGRES) {
            "LimitGuard jOOQ persistence requires the PostgreSQL dialect"
        }
    }

    private val transactionDsl = ThreadLocal<DSLContext?>()

    internal fun dsl(): DSLContext = transactionDsl.get() ?: rootDsl

    internal fun requireTransaction(operation: String) {
        if (transactionDsl.get() == null) {
            throw InfrastructureException("$operation requires an active transaction")
        }
    }

    internal fun <T> inTransaction(block: () -> T): T {
        if (transactionDsl.get() != null) return block()

        return rootDsl.transactionResult { configuration ->
            transactionDsl.set(DSL.using(configuration))
            try {
                block()
            } finally {
                transactionDsl.remove()
            }
        }
    }
}

class JooqTransactionRunner(
    private val context: JooqPersistenceContext,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        jooqOperation("run transaction") {
            context.inTransaction(block)
        }
}
