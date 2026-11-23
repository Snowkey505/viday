package com.vidayapi.jdbc

import org.springframework.jdbc.datasource.AbstractDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * JDBC [DataSource] that never opens a TCP session to PostgreSQL.
 *
 * Spring [org.springframework.jdbc.core.JdbcTemplate] still receives a real
 * [Connection] / [PreparedStatement] / [ResultSet]. SQL is executed against an
 * in-process catalog, so classic tests run the production JDBC repositories
 * (including their SQL strings) without writing to a physical database.
 */
class InMemoryPostgresDataSource(
    val catalog: InMemoryCatalog = InMemoryCatalog(),
) : AbstractDataSource() {

    override fun getConnection(): Connection = InMemoryConnection(catalog).asJdbc()

    override fun getConnection(username: String?, password: String?): Connection = getConnection()
}

class InMemoryCatalog {
    private val sequences = mutableMapOf<String, AtomicInteger>()
    private val tables = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()

    init {
        seedLookups()
    }

    fun clearBusinessData() {
        listOf("user", "user_follows", "playlist", "content", "video", "media_variant", "content_to_playlist")
            .forEach { name ->
                table(name).clear()
                sequences[name]?.set(0)
            }
    }

    fun rows(table: String): List<Map<String, Any?>> = table(table).map { it.toMap() }

    fun execute(sql: String, params: List<Any?>): SqlOutcome {
        val n = normalize(sql)
        return when {
            n.startsWith("select") -> select(n, params)
            n.startsWith("insert") -> insert(n, params)
            n.startsWith("update") -> update(n, params)
            n.startsWith("delete") -> delete(n, params)
            else -> error("Unsupported SQL: $sql")
        }
    }

    private fun seedLookups() {
        insertRow("role", mapOf("id" to 1, "name" to "GUEST"))
        insertRow("role", mapOf("id" to 2, "name" to "USER"))
        insertRow("role", mapOf("id" to 3, "name" to "CREATOR"))
        insertRow("role", mapOf("id" to 4, "name" to "ADMIN"))
        insertRow("role", mapOf("id" to 5, "name" to "ANALYST"))
        sequences["role"] = AtomicInteger(5)

        insertRow("access_type", mapOf("id" to 1, "name" to "PUBLIC"))
        insertRow("access_type", mapOf("id" to 2, "name" to "PRIVATE"))
        insertRow("access_type", mapOf("id" to 3, "name" to "FOLLOWERS"))
        sequences["access_type"] = AtomicInteger(3)

        insertRow("content_type", mapOf("id" to 1, "name" to "VIDEO"))
        insertRow("content_type", mapOf("id" to 2, "name" to "STREAM"))
        sequences["content_type"] = AtomicInteger(2)

        insertRow("codec", mapOf("id" to 1, "name" to "H264"))
        insertRow("codec", mapOf("id" to 2, "name" to "VP9"))
        insertRow("codec", mapOf("id" to 3, "name" to "AV1"))
        insertRow("codec", mapOf("id" to 4, "name" to "H265"))
        sequences["codec"] = AtomicInteger(4)
    }

    private fun select(n: String, params: List<Any?>): SqlOutcome {
        return when {
            n.contains("from viday.user") && n.contains("where u.id = ?") -> {
                val id = intParam(params, 0)
                val user = table("user").firstOrNull { it["id"] == id }
                userRow(user)
            }
            n.contains("from viday.user") && n.contains("where u.username = ?") -> {
                val username = params[0] as String
                val user = table("user").firstOrNull { it["username"] == username }
                userRow(user)
            }
            n.contains("from viday.playlist") && n.contains("where p.id = ?") -> {
                val id = intParam(params, 0)
                val playlist = table("playlist").firstOrNull { it["id"] == id }
                playlistRow(playlist)
            }
            n.contains("count(*)") && n.contains("from viday.playlist") && n.contains("where name = ?") -> {
                val name = params[0] as String
                val ownerId = intParam(params, 1)
                val count = table("playlist").count { it["name"] == name && it["owner_id"] == ownerId }
                countOutcome(count)
            }
            n.contains("count(*)") && n.contains("from viday.content_to_playlist") -> {
                val playlistId = intParam(params, 0)
                val count = table("content_to_playlist").count { it["playlist_id"] == playlistId }
                countOutcome(count)
            }
            n.contains("from viday.content") && n.contains("where c.id = ?") -> {
                val id = intParam(params, 0)
                val content = table("content").firstOrNull { it["id"] == id }
                contentRow(content)
            }
            else -> error("Unsupported SELECT: $n")
        }
    }

    private fun insert(n: String, params: List<Any?>): SqlOutcome {
        return when {
            n.contains("insert into viday.user ") -> {
                val username = params[0] as String
                if (table("user").any { it["username"] == username }) duplicate()
                val roleName = params[2] as String
                val roleId = lookupId("role", roleName) ?: error("Unknown role $roleName")
                val id = nextId("user")
                insertRow(
                    "user",
                    mapOf(
                        "id" to id,
                        "username" to username,
                        "password" to params[1],
                        "role_id" to roleId,
                        "created_at" to params[3],
                    ),
                )
                returningId(id)
            }
            n.contains("insert into viday.user_follows") -> {
                val following = intParam(params, 0)
                val followed = intParam(params, 1)
                if (table("user_follows").any {
                        it["following_user_id"] == following && it["followed_user_id"] == followed
                    }
                ) {
                    duplicate()
                }
                insertRow(
                    "user_follows",
                    mapOf(
                        "following_user_id" to following,
                        "followed_user_id" to followed,
                        "created_at" to params[2],
                    ),
                )
                SqlOutcome.update(1)
            }
            n.contains("insert into viday.playlist") -> {
                val name = params[0] as String
                val ownerId = intParam(params, 1)
                val createdAt = params[2]
                val accessName = params[3] as String
                val accessId = lookupId("access_type", accessName) ?: return SqlOutcome.empty()
                val id = nextId("playlist")
                insertRow(
                    "playlist",
                    mapOf(
                        "id" to id,
                        "name" to name,
                        "owner_id" to ownerId,
                        "access_type_id" to accessId,
                        "created_at" to createdAt,
                    ),
                )
                returningId(id)
            }
            n.contains("insert into viday.content_to_playlist") -> {
                val contentId = intParam(params, 0)
                val playlistId = intParam(params, 1)
                val position = intParam(params, 2)
                if (table("content_to_playlist").any {
                        it["playlist_id"] == playlistId && it["position"] == position
                    }
                ) {
                    duplicate()
                }
                insertRow(
                    "content_to_playlist",
                    mapOf(
                        "content_id" to contentId,
                        "playlist_id" to playlistId,
                        "position" to position,
                        "added_at" to params[3],
                    ),
                )
                SqlOutcome.update(1)
            }
            n.contains("insert into viday.content ") -> {
                val typeName = params[5] as String
                val accessName = params[6] as String
                val typeId = lookupId("content_type", typeName)
                val accessId = lookupId("access_type", accessName)
                if (typeId == null || accessId == null) return SqlOutcome.empty()
                val id = nextId("content")
                insertRow(
                    "content",
                    mapOf(
                        "id" to id,
                        "content_type_id" to typeId,
                        "name" to params[0],
                        "description" to params[1],
                        "source" to params[2],
                        "owner_id" to intParam(params, 3),
                        "access_type_id" to accessId,
                        "created_at" to params[4],
                    ),
                )
                returningId(id)
            }
            n.contains("insert into viday.video") -> {
                val contentId = intParam(params, 0)
                val existing = table("video").firstOrNull { it["content_id"] == contentId }
                if (existing != null) {
                    existing["duration_seconds"] = intParam(params, 1)
                    existing["preview"] = params[2]
                } else {
                    insertRow(
                        "video",
                        mapOf(
                            "content_id" to contentId,
                            "duration_seconds" to intParam(params, 1),
                            "preview" to params[2],
                        ),
                    )
                }
                SqlOutcome.update(1)
            }
            n.contains("insert into viday.media_variant") -> {
                val codecName = params[8] as String
                val codecId = lookupId("codec", codecName) ?: return SqlOutcome.empty()
                val id = nextId("media_variant")
                insertRow(
                    "media_variant",
                    mapOf(
                        "id" to id,
                        "content_id" to intParam(params, 0),
                        "width" to intParam(params, 1),
                        "height" to intParam(params, 2),
                        "bitrate" to intParam(params, 3),
                        "is_source" to boolParam(params, 4),
                        "file_path" to params[5],
                        "file_size_bytes" to params[6],
                        "created_at" to params[7],
                        "codec_id" to codecId,
                    ),
                )
                returningId(id)
            }
            else -> error("Unsupported INSERT: $n")
        }
    }

    private fun update(n: String, params: List<Any?>): SqlOutcome {
        error("Unsupported UPDATE: $n")
    }

    private fun delete(n: String, params: List<Any?>): SqlOutcome {
        error("Unsupported DELETE: $n")
    }

    private fun userRow(user: Map<String, Any?>?): SqlOutcome {
        if (user == null) return SqlOutcome.empty()
        val roleName = table("role").first { it["id"] == user["role_id"] }["name"]
        return SqlOutcome.rows(
            listOf("id", "username", "password", "role_id", "created_at", "role_name"),
            listOf(
                listOf(
                    user["id"],
                    user["username"],
                    user["password"],
                    user["role_id"],
                    user["created_at"],
                    roleName,
                ),
            ),
        )
    }

    private fun playlistRow(playlist: Map<String, Any?>?): SqlOutcome {
        if (playlist == null) return SqlOutcome.empty()
        val accessName = table("access_type").first { it["id"] == playlist["access_type_id"] }["name"]
        return SqlOutcome.rows(
            listOf("id", "name", "owner_id", "created_at", "access_type_name"),
            listOf(
                listOf(
                    playlist["id"],
                    playlist["name"],
                    playlist["owner_id"],
                    playlist["created_at"],
                    accessName,
                ),
            ),
        )
    }

    private fun contentRow(content: Map<String, Any?>?): SqlOutcome {
        if (content == null) return SqlOutcome.empty()
        val typeName = table("content_type").first { it["id"] == content["content_type_id"] }["name"]
        val accessName = table("access_type").first { it["id"] == content["access_type_id"] }["name"]
        return SqlOutcome.rows(
            listOf(
                "id", "content_type_id", "name", "description", "source",
                "owner_id", "access_type_id", "created_at", "type_name", "access_type_name",
            ),
            listOf(
                listOf(
                    content["id"],
                    content["content_type_id"],
                    content["name"],
                    content["description"],
                    content["source"],
                    content["owner_id"],
                    content["access_type_id"],
                    content["created_at"],
                    typeName,
                    accessName,
                ),
            ),
        )
    }

    private fun countOutcome(count: Int) =
        SqlOutcome.rows(listOf("count"), listOf(listOf(count)))

    private fun returningId(id: Int) =
        SqlOutcome.rows(listOf("id"), listOf(listOf(id)))

    private fun lookupId(table: String, name: String): Int? =
        table(table).firstOrNull { it["name"] == name }?.get("id") as Int?

    private fun table(name: String) = tables.getOrPut(name) { mutableListOf() }

    private fun insertRow(table: String, row: Map<String, Any?>) {
        table(table) += row.toMutableMap()
    }

    private fun nextId(table: String): Int =
        sequences.getOrPut(table) { AtomicInteger(0) }.incrementAndGet()

    private fun intParam(params: List<Any?>, index: Int): Int = when (val v = params[index]) {
        is Int -> v
        is Number -> v.toInt()
        else -> error("Expected int at $index, got $v")
    }

    private fun boolParam(params: List<Any?>, index: Int): Boolean = when (val v = params[index]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> error("Expected boolean at $index, got $v")
    }

    private fun duplicate(): Nothing =
        throw SQLException("duplicate key value violates unique constraint", "23505", 0)

    private fun normalize(sql: String): String =
        sql.lowercase(Locale.ROOT)
            .replace("\"", "")
            .replace(Regex("\\s+"), " ")
            .trim()
}

data class SqlOutcome(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val updateCount: Int,
) {
    companion object {
        fun rows(columns: List<String>, rows: List<List<Any?>>) = SqlOutcome(columns, rows, -1)
        fun empty() = SqlOutcome(emptyList(), emptyList(), -1)
        fun update(count: Int) = SqlOutcome(emptyList(), emptyList(), count)
    }
}

private class InMemoryConnection(
    private val catalog: InMemoryCatalog,
) {
    private val closed = AtomicBoolean(false)
    private val proxy: Connection by lazy { buildProxy() }

    fun asJdbc(): Connection = proxy

    private fun buildProxy(): Connection {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "prepareStatement" -> InMemoryPreparedStatement(catalog, args!![0] as String, proxy)
                "createStatement" -> InMemoryPreparedStatement(catalog, "", proxy)
                "close" -> closed.set(true)
                "isClosed" -> closed.get()
                "getAutoCommit" -> true
                "setAutoCommit", "commit", "rollback", "clearWarnings" -> null
                "getWarnings" -> null as SQLWarning?
                "nativeSQL" -> args!![0]
                "isValid" -> !closed.get()
                "getMetaData" -> metaData()
                "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
                "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT
                "isReadOnly" -> false
                "getCatalog" -> "viday"
                "getSchema" -> "viday"
                "unwrap" -> proxy
                "isWrapperFor" -> false
                "toString" -> "InMemoryPostgresConnection"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.getOrNull(0) === proxy
                else -> defaultJdbcValue(method.returnType)
            }
        }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }

    private fun metaData(): DatabaseMetaData {
        return Proxy.newProxyInstance(
            DatabaseMetaData::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDatabaseProductName" -> "PostgreSQL"
                "getDatabaseProductVersion" -> "15.6"
                "getDriverName" -> "InMemoryPostgres"
                "getDriverVersion" -> "1.0"
                "getURL" -> "jdbc:inmemory:postgres"
                "getUserName" -> "viday"
                "getIdentifierQuoteString" -> "\""
                "getCatalogSeparator" -> "."
                "getCatalogTerm" -> "catalog"
                "getSchemaTerm" -> "schema"
                "getProcedureTerm" -> "procedure"
                "getSearchStringEscape" -> "\\"
                "getExtraNameCharacters" -> ""
                "getNumericFunctions", "getStringFunctions", "getSystemFunctions",
                "getTimeDateFunctions", "getSQLKeywords",
                -> ""
                "getDatabaseMajorVersion" -> 15
                "getDatabaseMinorVersion" -> 6
                "getDriverMajorVersion" -> 1
                "getDriverMinorVersion" -> 0
                "getJDBCMajorVersion" -> 4
                "getJDBCMinorVersion" -> 2
                "getSQLStateType" -> DatabaseMetaData.sqlStateSQL
                "supportsGetGeneratedKeys", "supportsBatchUpdates", "supportsTransactions",
                "supportsMixedCaseQuotedIdentifiers", "storesMixedCaseQuotedIdentifiers",
                -> true
                "getDefaultTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
                "getConnection" -> proxy
                else -> defaultJdbcValue(method.returnType)
            }
        } as DatabaseMetaData
    }
}

private class InMemoryPreparedStatement(
    private val catalog: InMemoryCatalog,
    private val sql: String,
    private val connection: Connection,
) : PreparedStatement by unsupportedPreparedStatement() {
    private val params = mutableMapOf<Int, Any?>()
    private var resultSet: ResultSet = InMemoryResultSet(SqlOutcome.empty())
    private var updateCount: Int = -1
    private val closed = AtomicBoolean(false)

    override fun setObject(parameterIndex: Int, x: Any?) {
        params[parameterIndex] = x
    }

    override fun setString(parameterIndex: Int, x: String?) = setObject(parameterIndex, x)
    override fun setInt(parameterIndex: Int, x: Int) = setObject(parameterIndex, x)
    override fun setLong(parameterIndex: Int, x: Long) = setObject(parameterIndex, x)
    override fun setBoolean(parameterIndex: Int, x: Boolean) = setObject(parameterIndex, x)
    override fun setNull(parameterIndex: Int, sqlType: Int) = setObject(parameterIndex, null)
    override fun setTimestamp(parameterIndex: Int, x: java.sql.Timestamp?) = setObject(parameterIndex, x)
    override fun setDate(parameterIndex: Int, x: java.sql.Date?) = setObject(parameterIndex, x)
    override fun setBytes(parameterIndex: Int, x: ByteArray?) = setObject(parameterIndex, x)

    override fun executeQuery(): ResultSet {
        val outcome = run()
        resultSet = InMemoryResultSet(outcome)
        updateCount = -1
        return resultSet
    }

    override fun executeQuery(sql: String): ResultSet {
        val outcome = catalog.execute(sql, orderedParams())
        resultSet = InMemoryResultSet(outcome)
        return resultSet
    }

    override fun executeUpdate(): Int {
        val outcome = run()
        updateCount = if (outcome.updateCount >= 0) outcome.updateCount else outcome.rows.size
        resultSet = InMemoryResultSet(outcome)
        return updateCount
    }

    override fun execute(): Boolean {
        val outcome = run()
        resultSet = InMemoryResultSet(outcome)
        val isResultSet = outcome.rows.isNotEmpty() || outcome.updateCount < 0
        updateCount = if (isResultSet) -1 else outcome.updateCount
        return isResultSet && outcome.columns.isNotEmpty()
    }

    override fun getResultSet(): ResultSet = resultSet
    override fun getUpdateCount(): Int = updateCount
    override fun getMoreResults(): Boolean = false
    override fun close() {
        closed.set(true)
    }

    override fun isClosed(): Boolean = closed.get()
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() = Unit
    override fun clearParameters() = params.clear()
    override fun getConnection(): Connection = connection
    override fun getParameterMetaData(): ParameterMetaData =
        InMemoryParameterMetaData(sql.count { it == '?' })
    override fun getGeneratedKeys(): ResultSet = resultSet
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>): T = this as T
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    override fun getFetchSize(): Int = 0
    override fun setFetchSize(rows: Int) = Unit
    override fun getMaxRows(): Int = 0
    override fun setMaxRows(max: Int) = Unit
    override fun getQueryTimeout(): Int = 0
    override fun setQueryTimeout(seconds: Int) = Unit

    private fun run(): SqlOutcome {
        val ordered = orderedParams()
        return catalog.execute(sql, ordered)
    }

    private fun orderedParams(): List<Any?> {
        if (params.isEmpty()) return emptyList()
        val max = params.keys.max()
        return (1..max).map { params[it] }
    }
}

private class InMemoryResultSet(
    outcome: SqlOutcome,
) : ResultSet by unsupportedResultSet() {
    private val columns = outcome.columns
    private val rows = outcome.rows
    private var cursor = -1
    private var lastWasNull = false
    private val closed = AtomicBoolean(false)

    override fun next(): Boolean {
        cursor++
        return cursor < rows.size
    }

    override fun close() {
        closed.set(true)
    }

    override fun isClosed(): Boolean = closed.get()
    override fun wasNull(): Boolean = lastWasNull
    override fun findColumn(columnLabel: String): Int {
        val idx = columns.indexOfFirst { it.equals(columnLabel, ignoreCase = true) }
        if (idx < 0) throw SQLException("No column $columnLabel")
        return idx + 1
    }

    override fun getMetaData(): ResultSetMetaData = object : ResultSetMetaData by unsupportedResultSetMeta() {
        override fun getColumnCount(): Int = columns.size.coerceAtLeast(1)
        override fun getColumnLabel(column: Int): String = columns.getOrElse(column - 1) { "col$column" }
        override fun getColumnName(column: Int): String = getColumnLabel(column)
        override fun getColumnType(column: Int): Int = java.sql.Types.OTHER
        override fun getColumnClassName(column: Int): String = Any::class.java.name
    }

    override fun getObject(columnIndex: Int): Any? = value(columnIndex)
    override fun getObject(columnLabel: String): Any? = value(findColumn(columnLabel))
    override fun <T : Any?> getObject(columnIndex: Int, type: Class<T>): T? = convert(value(columnIndex), type)
    override fun <T : Any?> getObject(columnLabel: String, type: Class<T>): T? =
        getObject(findColumn(columnLabel), type)

    override fun getInt(columnIndex: Int): Int = number(value(columnIndex))?.toInt() ?: 0
    override fun getInt(columnLabel: String): Int = getInt(findColumn(columnLabel))
    override fun getLong(columnIndex: Int): Long = number(value(columnIndex))?.toLong() ?: 0L
    override fun getLong(columnLabel: String): Long = getLong(findColumn(columnLabel))
    override fun getBoolean(columnIndex: Int): Boolean = when (val v = value(columnIndex)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> false
    }
    override fun getBoolean(columnLabel: String): Boolean = getBoolean(findColumn(columnLabel))
    override fun getString(columnIndex: Int): String? = value(columnIndex)?.toString()
    override fun getString(columnLabel: String): String? = getString(findColumn(columnLabel))

    private fun value(columnIndex: Int): Any? {
        val row = rows.getOrNull(cursor) ?: throw SQLException("ResultSet cursor is not on a row")
        val v = row.getOrNull(columnIndex - 1)
        lastWasNull = v == null
        return v
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> convert(value: Any?, type: Class<T>): T? {
        lastWasNull = value == null
        if (value == null) return null
        if (type.isInstance(value)) return value as T
        if (type == OffsetDateTime::class.java && value is OffsetDateTime) return value as T
        if (type == Int::class.java || type == Integer::class.java) return number(value)?.toInt() as T
        if (type == Long::class.java || type == java.lang.Long::class.java) return number(value)?.toLong() as T
        if (type == String::class.java) return value.toString() as T
        error("Cannot convert $value to $type")
    }

    private fun number(value: Any?): Number? = value as? Number
}

private fun defaultJdbcValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE, Boolean::class.java -> false
    java.lang.Integer.TYPE, Int::class.java -> 0
    java.lang.Long.TYPE, Long::class.java -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    String::class.java -> ""
    else -> null
}

private fun unsupportedPreparedStatement(): PreparedStatement =
    Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader,
        arrayOf(PreparedStatement::class.java, Statement::class.java),
    ) { _, method, _ ->
        if (method.name == "toString") "unsupported-ps" else defaultJdbcValue(method.returnType)
    } as PreparedStatement

private fun unsupportedResultSet(): ResultSet =
    Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, _ -> defaultJdbcValue(method.returnType) } as ResultSet

private fun unsupportedResultSetMeta(): ResultSetMetaData =
    Proxy.newProxyInstance(
        ResultSetMetaData::class.java.classLoader,
        arrayOf(ResultSetMetaData::class.java),
    ) { _, method, _ -> defaultJdbcValue(method.returnType) } as ResultSetMetaData

private fun unsupportedParameterMetaData(): ParameterMetaData =
    Proxy.newProxyInstance(
        ParameterMetaData::class.java.classLoader,
        arrayOf(ParameterMetaData::class.java),
    ) { _, method, _ -> defaultJdbcValue(method.returnType) } as ParameterMetaData

/**
 * Minimal [ParameterMetaData]: Spring's [org.springframework.jdbc.core.StatementCreatorUtils]
 * asks for the declared type of a `NULL` argument before calling [PreparedStatement.setNull].
 * Every placeholder is reported as VARCHAR so that nulls bind to `ps.setNull(i, Types.VARCHAR)`.
 */
private class InMemoryParameterMetaData(
    private val count: Int,
) : ParameterMetaData by unsupportedParameterMetaData() {
    override fun getParameterCount(): Int = count
    override fun getParameterType(param: Int): Int = java.sql.Types.VARCHAR
    override fun getParameterTypeName(param: Int): String = "varchar"
    override fun getParameterClassName(param: Int): String = String::class.java.name
    override fun getPrecision(param: Int): Int = 0
    override fun getScale(param: Int): Int = 0
    override fun isNullable(param: Int): Int = ParameterMetaData.parameterNullableUnknown
    override fun isSigned(param: Int): Boolean = false
    override fun getParameterMode(param: Int): Int = ParameterMetaData.parameterModeIn

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>): T = this as T
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
