package com.vidayapi.security

import java.sql.Connection
import javax.sql.DataSource

class RlsAwareDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {

    override fun getConnection(): Connection =
        configure(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        configure(delegate.getConnection(username, password))

    private fun configure(connection: Connection): Connection {
        val context = RlsContextHolder.get() ?: return connection
        connection.createStatement().use { statement ->
            statement.execute("SET ROLE ${context.pgRole.sqlName}")
            if (context.userId != null) {
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, false)").use { ps ->
                    ps.setString(1, context.userId.toString())
                    ps.execute()
                }
            } else {
                statement.execute("RESET app.current_user_id")
            }
        }
        return connection
    }
}
