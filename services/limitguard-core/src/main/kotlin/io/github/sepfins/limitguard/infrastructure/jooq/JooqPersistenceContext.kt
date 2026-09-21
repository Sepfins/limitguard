package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

/**
 * Shares the transaction-scoped jOOQ configuration between a transaction
 * runner and its repositories. Generated bindings are produced from `public`,
 * but queries must honor each consumer's connection-selected schema.
 */
class JooqPersistenceContext(
    rootDsl: DSLContext,
) {
    private val rootDsl: DSLContext = rootDsl.configuration().let { configuration ->
        val settings = configuration.settings().clone() as Settings
        configuration.derive(settings.withRenderSchema(false)).dsl()
    }

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

    internal fun <T> inSavepoint(block: () -> T): T {
        val dsl = transactionDsl.get()
            ?: throw InfrastructureException("run transaction savepoint requires an active transaction")

        return dsl.connectionResult { connection ->
            val savepoint = connection.setSavepoint()
            try {
                block()
            } catch (exception: Throwable) {
                connection.rollback(savepoint)
                throw exception
            } finally {
                connection.releaseSavepoint(savepoint)
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

    override fun <T> inSavepoint(block: () -> T): T =
        jooqOperation("run transaction savepoint") {
            context.inSavepoint(block)
        }
}
