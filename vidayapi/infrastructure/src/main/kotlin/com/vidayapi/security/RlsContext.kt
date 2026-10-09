package com.vidayapi.security

import com.vidayapi.model.Role
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

enum class PgRole(val sqlName: String) {
    GUEST("viday_guest"),
    USER("viday_user"),
    CREATOR("viday_creator"),
    ADMIN("viday_admin"),
    ANALYST("viday_analyst"),
    ;

    companion object {
        fun fromAppRole(role: Role): PgRole = when (role) {
            Role.GUEST -> GUEST
            Role.USER -> USER
            Role.CREATOR -> CREATOR
            Role.ADMIN -> ADMIN
            Role.ANALYST -> ANALYST
        }
    }
}

data class RlsContext(
    val pgRole: PgRole,
    val userId: Int? = null,
) {
    fun cacheViewerKey(): String = when {
        pgRole == PgRole.ADMIN -> "admin"
        userId == null -> pgRole.name.lowercase()
        else -> "${pgRole.name.lowercase()}:$userId"
    }

    companion object {
        val GUEST = RlsContext(PgRole.GUEST)
        val REGISTRATION = RlsContext(PgRole.USER)
        val ADMIN = RlsContext(PgRole.ADMIN)
    }
}

object RlsContextHolder {
    private val holder = ThreadLocal<RlsContext?>()

    fun get(): RlsContext? = holder.get()

    fun set(context: RlsContext?) {
        if (context == null) {
            holder.remove()
        } else {
            holder.set(context)
        }
    }

    fun clear() {
        holder.remove()
    }

    fun <T> withContext(context: RlsContext, block: () -> T): T {
        set(context)
        try {
            return block()
        } finally {
            clear()
        }
    }
}

object RlsContextResolver {
    fun guestBootstrap(): RlsContext = RlsContext.GUEST

    fun resolve(request: HttpServletRequest): RlsContext {
        if (isRegisterRequest(request)) {
            return RlsContext.REGISTRATION
        }

        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || authentication is AnonymousAuthenticationToken) {
            return RlsContext.GUEST
        }

        val userId = authentication.name.toIntOrNull()
        val roleName = authentication.authorities
            .firstOrNull()
            ?.authority
            ?.removePrefix("ROLE_")
            ?: return RlsContext.GUEST

        val appRole = runCatching { Role.valueOf(roleName) }.getOrNull() ?: return RlsContext.GUEST
        return RlsContext(PgRole.fromAppRole(appRole), userId)
    }

    fun resolveCacheViewerKey(): String =
        get()?.cacheViewerKey() ?: RlsContext.GUEST.cacheViewerKey()

    fun resolveCacheViewerKey(authentication: Authentication?): String {
        val context = get() ?: authentication?.let { auth ->
            if (auth is AnonymousAuthenticationToken) {
                RlsContext.GUEST
            } else {
                val userId = auth.name.toIntOrNull()
                val roleName = auth.authorities.firstOrNull()?.authority?.removePrefix("ROLE_")
                val appRole = roleName?.let { runCatching { Role.valueOf(it) }.getOrNull() }
                if (appRole == null) RlsContext.GUEST else RlsContext(PgRole.fromAppRole(appRole), userId)
            }
        } ?: RlsContext.GUEST
        return context.cacheViewerKey()
    }

    private fun get(): RlsContext? = RlsContextHolder.get()

    private fun isRegisterRequest(request: HttpServletRequest): Boolean {
        val path = request.servletPath.takeIf { it.isNotBlank() } ?: request.requestURI
        return path.removeSuffix("/").endsWith("/api/auth/register")
    }
}
