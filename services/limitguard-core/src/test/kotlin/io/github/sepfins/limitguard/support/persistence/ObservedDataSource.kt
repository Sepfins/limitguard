package io.github.sepfins.limitguard.support.persistence

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

// JDBC observation only arranges contention; results are checked through the public facade.
class ObservedDataSource(
    private val delegate: DataSource,
    private val before: (String) -> Unit = {},
    private val after: (String) -> Unit = {},
) : DataSource by delegate {
    override fun getConnection(): Connection = observe(delegate.connection)

    override fun getConnection(username: String, password: String): Connection =
        observe(delegate.getConnection(username, password))

    private fun observe(connection: Connection): Connection = proxy(Connection::class.java, connection) { method, args ->
        val result = invoke { method.invoke(connection, *args) }
        if (method.name == "prepareStatement" && result is PreparedStatement) {
            val sql = args[0] as String
            proxy(PreparedStatement::class.java, result) { statementMethod, statementArgs ->
                val executes = statementMethod.name.startsWith("execute")
                if (executes) before(sql)
                val value = invoke { statementMethod.invoke(result, *statementArgs) }
                if (executes) after(sql)
                value
            }
        } else result
    }

    private fun <T> proxy(type: Class<T>, target: T, call: (java.lang.reflect.Method, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(target!!::class.java.classLoader, arrayOf(type)) { _, method, args ->
            call(method, args ?: emptyArray())
        })

    private fun invoke(block: () -> Any?): Any? = try {
        block()
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
}
