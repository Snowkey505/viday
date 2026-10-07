package com.vidayapi.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RlsContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        RlsContextHolder.set(RlsContextResolver.resolve(request))
        try {
            filterChain.doFilter(request, response)
        } finally {
            RlsContextHolder.clear()
        }
    }
}
