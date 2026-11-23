package com.vidayapi.controller

import com.vidayapi.dto.responses.ContentViewStatsResponse
import com.vidayapi.model.Error
import com.vidayapi.service.AuthService
import com.vidayapi.usecase.GetViewStatsUseCase
import com.vidayapi.controller.support.authenticatedUser
import com.vidayapi.controller.support.forbiddenResponse
import com.vidayapi.controller.support.unauthorizedResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/analytics")
class AnalystController(
    private val authService: AuthService,
    private val getViewStatsUseCase: GetViewStatsUseCase,
) {
    @GetMapping("/views")
    fun listViewStats(@AuthenticationPrincipal userDetails: UserDetails?): ResponseEntity<Any> {
        val userId = resolveUserId(userDetails) ?: return unauthorizedResponse()
        return getViewStatsUseCase.listAll(userId).fold(
            onSuccess = { stats ->
                ResponseEntity.ok(stats.map { ContentViewStatsResponse.from(it) })
            },
            onFailure = { error -> analystError(error as Error) }
        )
    }

    @GetMapping("/views/top")
    fun topViewStats(
        @AuthenticationPrincipal userDetails: UserDetails?,
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<Any> {
        val userId = resolveUserId(userDetails) ?: return unauthorizedResponse()
        return getViewStatsUseCase.getTop(userId, limit).fold(
            onSuccess = { stats ->
                ResponseEntity.ok(stats.map { ContentViewStatsResponse.from(it) })
            },
            onFailure = { error -> analystError(error as Error) }
        )
    }

    @GetMapping("/views/{contentId}")
    fun getViewStats(
        @PathVariable contentId: Int,
        @AuthenticationPrincipal userDetails: UserDetails?,
    ): ResponseEntity<Any> {
        val userId = resolveUserId(userDetails) ?: return unauthorizedResponse()
        return getViewStatsUseCase.getByContentId(userId, contentId).fold(
            onSuccess = { stats -> ResponseEntity.ok(ContentViewStatsResponse.from(stats)) },
            onFailure = { error -> analystError(error as Error) }
        )
    }

    private fun resolveUserId(userDetails: UserDetails?): Int? =
        authService.authenticatedUser(userDetails).getOrNull()?.first

    private fun analystError(error: Error): ResponseEntity<Any> =
        when (error) {
            is Error.Forbidden -> forbiddenResponse()
            is Error.NotFound -> ResponseEntity.notFound().build()
            else -> ResponseEntity.status(500).body(mapOf("error" to "Failed to fetch statistics"))
        }
}
