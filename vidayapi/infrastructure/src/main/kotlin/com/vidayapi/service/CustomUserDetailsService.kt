package com.vidayapi.service

import com.vidayapi.model.fold
import com.vidayapi.port.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val userId = username.toIntOrNull() ?: throw UsernameNotFoundException("Invalid user id")
        val user = userRepository.findById(userId).fold(
            ifLeft = { throw UsernameNotFoundException("User not found") },
            ifRight = { it ?: throw UsernameNotFoundException("User not found") }
        )

        return User(
            user.id.toString(),
            user.passwordHash,
            listOf(SimpleGrantedAuthority("ROLE_${user.role.name}"))
        )
    }
}
