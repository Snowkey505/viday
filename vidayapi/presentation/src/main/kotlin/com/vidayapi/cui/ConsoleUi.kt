package com.vidayapi.cui

import com.vidayapi.dto.requests.UploadVideoRequest
import com.vidayapi.model.AccessType
import com.vidayapi.model.Error
import com.vidayapi.model.fold
import com.vidayapi.port.PlaylistRepository
import com.vidayapi.port.UserRepository
import com.vidayapi.usecase.CreatePlaylistUseCase
import com.vidayapi.usecase.RegisterUserUseCase
import com.vidayapi.usecase.UploadVideoUseCase
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import org.springframework.mock.web.MockMultipartFile
import java.io.File
import java.io.FileInputStream
import com.vidayapi.model.Role
import com.vidayapi.service.AuthService
import com.vidayapi.usecase.ActivateChannelUseCase
import com.vidayapi.port.PasswordEncoder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.util.Scanner

@Component
@ConditionalOnProperty(
    prefix = "viday.console",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class ConsoleUi(
    private val registerUserUseCase: RegisterUserUseCase,
    private val createPlaylistUseCase: CreatePlaylistUseCase,
    private val uploadVideoUseCase: UploadVideoUseCase,
    private val activateChannelUseCase: ActivateChannelUseCase,
    private val authService: AuthService,
    private val passwordEncoder: PasswordEncoder,
    private val playlistRepository: PlaylistRepository,
    private val userFollowRepository: UserRepository,
) : CommandLineRunner {

    private var currentToken: String? = null

    override fun run(vararg args: String) {
        println("VidayApi Console")
        val scanner = Scanner(System.`in`)

        while (true) {
            val role = getCurrentRole()
            println("\nCurrent: ${getCurrentUsername() ?: "guest"} (${role?.name ?: "GUEST"})")
            println("1. Login")
            println("2. Register user")
            println("3. Create playlist")
            println("4. Delete playlist")
            println("5. Add content to playlist")
            println("6. Remove content from playlist")
            println("7. Follow creator")
            println("8. Unfollow creator")
            println("9. Upload video")
            println("10. Upload test video")
            println("11. Activate channel")
            println("12. Logout")
            println("0. Exit")
            print("Choose: ")

            when (scanner.nextLine()) {
                "1" -> loginFlow(scanner)
                "2" -> registerUserFlow(scanner)
                "3" -> createPlaylistFlow(scanner)
                "4" -> deletePlaylistFlow(scanner)
                "5" -> addContentToPlaylistFlow(scanner)
                "6" -> removeContentFromPlaylistFlow(scanner)
                "7" -> followCreatorFlow(scanner)
                "8" -> unfollowCreatorFlow(scanner)
                "9" -> uploadVideoFlow(scanner)
                "10" -> uploadTestVideo()
                "11" -> activateChannelFlow()
                "12" -> logoutFlow()
                "0" -> {
                    println("Goodbye!")
                    break
                }
                else -> println("Unknown option")
            }
        }
    }

    private fun getCurrentUsername(): String? {
        val token = currentToken ?: return null
        val userIdResult = authService.validateToken(token)
        return userIdResult.fold(
            ifLeft = { null },
            ifRight = { userId -> "user_$userId" }
        )
    }

    private fun getCurrentRole(): Role? {
        val token = currentToken ?: return null
        val roleResult = authService.getCurrentUserRole(token)
        return roleResult.fold(
            ifLeft = { null },
            ifRight = { it }
        )
    }

    private fun loginFlow(scanner: Scanner) {
        print("Username: ")
        val username = scanner.nextLine()
        print("Password: ")
        val password = scanner.nextLine()

        authService.login(username, password, passwordEncoder).fold(
            onSuccess = { token ->
                currentToken = token
                println("Login successful!")
                val role = getCurrentRole()
                println("Role: ${role?.name}")
            },
            onFailure = { error -> println("Login failed: $error") }
        )
    }

    private fun logoutFlow() {
        currentToken = null
        println("Logged out")
    }

    private fun registerUserFlow(scanner: Scanner) {
        print("Username: ")
        val username = scanner.nextLine()
        print("Password: ")
        val password = scanner.nextLine()

        registerUserUseCase.execute(username, password).fold(
            onSuccess = { user ->
                println("User registered successfully with ID: ${user.id}")
                authService.login(username, password, passwordEncoder).fold(
                    onSuccess = { token ->
                        currentToken = token
                        println("Auto-logged in!")
                    },
                    onFailure = { println("Auto-login failed") }
                )
            },
            onFailure = { error -> println("Registration failed: $error") }
        )
    }

    private fun createPlaylistFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }

        val permission = authService.canCreatePlaylist(token)
        if (permission.isFailure) {
            println("Cannot create playlist: ${permission.exceptionOrNull()}")
            return
        }

        print("Playlist name: ")
        val name = scanner.nextLine()

        print("Access type (PUBLIC/PRIVATE/FOLLOWERS): ")
        val accessTypeStr = scanner.nextLine().uppercase()
        val accessType =
            try {
                AccessType.valueOf(accessTypeStr)
            } catch (_: IllegalArgumentException) {
                println("Invalid access type, using PRIVATE")
                AccessType.PRIVATE
            }

        val userId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        val result = createPlaylistUseCase.execute(
            name = name,
            ownerId = userId,
            accessType = accessType,
        )

        result.fold(
            onSuccess = { playlist ->
                println(
                    "Playlist created: ${playlist.name} (ID: ${playlist.id}), access=${playlist.accessType}",
                )
            },
            onFailure = { error -> println("Create failed: $error") }
        )
    }

    private fun deletePlaylistFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }
        val userId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        print("Playlist id to delete: ")
        val playlistId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid playlist id")
                return
            }

        playlistRepository.deletePlaylist(playlistId, userId).fold(
            onSuccess = { println("Playlist deleted.") },
            onFailure = { error ->
                println(
                    "Delete failed: ${explainPlaylistCliError(error as Error)}",
                )
            }
        )
    }

    private fun addContentToPlaylistFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }
        val userId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        print("Playlist id: ")
        val playlistId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid playlist id")
                return
            }

        if (!ensurePlaylistOwnedByCli(playlistId, userId)) return

        print("Content id: ")
        val contentId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid content id")
                return
            }

        print("Position (1-based): ")
        val position =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid position")
                return
            }

        playlistRepository.addContentToPlaylist(contentId, playlistId, position).fold(
            onSuccess = { println("Content added to playlist.") },
            onFailure = { error ->
                println(
                    "Add failed: ${explainPlaylistCliError(error as Error)}",
                )
            }
        )
    }

    private fun removeContentFromPlaylistFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }
        val userId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        print("Playlist id: ")
        val playlistId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid playlist id")
                return
            }

        if (ensurePlaylistOwnedByCli(playlistId, userId) != true) return

        print("Content id to remove: ")
        val contentId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid content id")
                return
            }

        playlistRepository.removeContentFromPlaylist(contentId, playlistId).fold(
            onSuccess = { println("Content removed.") },
            onFailure = { error ->
                println(
                    "Remove failed: ${explainPlaylistCliError(error as Error)}",
                )
            }
        )
    }

    private fun followCreatorFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }
        val followerId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        print("Creator user id (to follow): ")
        val creatorId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid user id")
                return
            }

        if (followerId == creatorId) {
            println("Cannot follow yourself")
            return
        }

        userFollowRepository.follow(followerId, creatorId).fold(
            onSuccess = { println("Subscribed.") },
            onFailure = { error -> println(followExplain(error as Error)) }
        )
    }

    private fun unfollowCreatorFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }
        val followerId =
            resolveUserIdOrNull(token) ?: run {
                println("Invalid token")
                return
            }

        print("Creator user id (to unfollow): ")
        val creatorId =
            scanner.nextLine().toIntOrNull() ?: run {
                println("Invalid user id")
                return
            }

        if (followerId == creatorId) {
            println("Cannot unfollow yourself")
            return
        }

        userFollowRepository.unfollow(followerId, creatorId).fold(
            onSuccess = { println("Unsubscribed.") },
            onFailure = { error -> println(followExplain(error as Error)) }
        )
    }

    private fun resolveUserIdOrNull(token: String): Int? =
        authService.validateToken(token).getOrNull()

    private fun ensurePlaylistOwnedByCli(playlistId: Int, userId: Int): Boolean =
        playlistRepository.findById(playlistId).fold(
            onSuccess = { playlist ->
                if (playlist.ownerId != userId) {
                    println(
                        "You are not the owner of this playlist.",
                    )
                    false
                } else {
                    true
                }
            },
            onFailure = { error ->
                println(
                    "Playlist lookup failed: ${explainPlaylistCliError(error as Error)}",
                )
                false
            }
        )

    private fun explainPlaylistCliError(err: Error): String =
        when (err) {
            is Error.NotFound -> "Not found."
            is Error.Forbidden -> "Forbidden."
            is Error.PlaylistPositionConflict ->
                "Position conflict (playlist already has something at this index)."

            else -> "$err"
        }

    private fun followExplain(err: Error): String =
        when (err) {
            is Error.UserAlreadyFollowed -> "Already following."
            is Error.NotFound -> "Not following."
            else -> "$err"
        }

    private fun uploadVideoFlow(scanner: Scanner) {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }

        val permission = authService.canUploadVideo(token)
        if (permission.isFailure) {
            val error = permission.exceptionOrNull()
            println("Cannot upload video: $error")
            when (error) {
                Error.CreatorRequired -> println("Use option 11 to activate channel")
                else -> Unit
            }
            return
        }

        print("Video file path: ")
        val filePath = scanner.nextLine()
        val file = File(filePath)

        if (!file.exists()) {
            println("File not found: $filePath")
            return
        }

        print("Video name: ")
        val name = scanner.nextLine()

        print("Description (optional): ")
        val description = scanner.nextLine().takeIf { it.isNotBlank() }

        print("Preview image path: ")
        val previewPath = scanner.nextLine()
        val previewFile = File(previewPath)
        if (!previewFile.exists()) {
            println("Preview file not found: $previewPath")
            return
        }

        print("Access type (PUBLIC/PRIVATE/FOLLOWERS): ")
        val accessTypeStr = scanner.nextLine().uppercase()
        val accessType = try {
            AccessType.valueOf(accessTypeStr)
        } catch (e: IllegalArgumentException) {
            println("Invalid access type, using PUBLIC")
            AccessType.PUBLIC
        }

        val multipartFile = MockMultipartFile(
            "file",
            file.name,
            "video/mp4",
            FileInputStream(file)
        )
        val previewMultipartFile = MockMultipartFile(
            "preview",
            previewFile.name,
            "image/jpeg",
            FileInputStream(previewFile)
        )

        val request = UploadVideoRequest(
            name = name,
            description = description,
            accessType = accessType,
            file = multipartFile,
            preview = previewMultipartFile
        )

        val userIdResult = authService.validateToken(token)
        val userId = userIdResult.fold(
            ifLeft = {
                println("Invalid token")
                return
            },
            ifRight = { it }
        )

        println("Uploading video...")
        uploadVideoUseCase.execute(request, userId).fold(
            onSuccess = { video ->
                println("Video uploaded successfully!")
                println("Content ID: ${video.content.id}")
                println("Name: ${video.content.name}")
                println("Duration: ${video.durationSeconds}s")
                println("Preview: ${video.preview}")
            },
            onFailure = { error -> println("Upload failed: $error") }
        )
    }

    private fun uploadTestVideo() {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }

        val permission = authService.canUploadVideo(token)
        if (permission.isFailure) {
            println("Cannot upload video: ${permission.exceptionOrNull()}")
            println("Use option 11 to activate channel first!")
            return
        }

        val testFile = File.createTempFile("test_video_", ".mp4")
        testFile.writeBytes(ByteArray(1024 * 100))

        println("Test video created: ${testFile.absolutePath}")
        println("Size: ${testFile.length()} bytes")

        val multipartFile = MockMultipartFile(
            "file",
            "test_video.mp4",
            "video/mp4",
            FileInputStream(testFile)
        )
        val previewMultipartFile = MockMultipartFile(
            "preview",
            "test_preview.jpg",
            "image/jpeg",
            ByteArray(1024) { 1 }
        )

        val request = UploadVideoRequest(
            name = "Test Video ${System.currentTimeMillis()}",
            description = "Auto-generated test video",
            accessType = AccessType.PUBLIC,
            file = multipartFile,
            preview = previewMultipartFile
        )

        val userIdResult = authService.validateToken(token)
        val userId = userIdResult.fold(
            ifLeft = {
                println("Invalid token")
                return
            },
            ifRight = { it }
        )

        println("Uploading test video...")
        uploadVideoUseCase.execute(request, userId).fold(
            onSuccess = { video ->
                println("Test video uploaded successfully!")
                println("Content ID: ${video.content.id}")
                println("Name: ${video.content.name}")
                println("Duration: ${video.durationSeconds}s")
                println("Preview: ${video.preview}")
            },
            onFailure = { error -> println("Upload failed: $error") }
        )

        testFile.deleteOnExit()
    }


    private fun activateChannelFlow() {
        val token = currentToken
        if (token == null) {
            println("Please login first")
            return
        }

        val roleResult = authService.getCurrentUserRole(token)
        val currentRole = roleResult.fold(
            ifLeft = {
                println("Error: ${roleResult.exceptionOrNull()}")
                return
            },
            ifRight = { it }
        )

        if (currentRole == Role.CREATOR) {
            println("You are already a CREATOR!")
            return
        }

        if (currentRole != Role.USER) {
            println("Only USER role can activate channel. Please register first.")
            return
        }

        val userIdResult = authService.validateToken(token)
        val userId = userIdResult.fold(
            ifLeft = {
                println("Invalid token")
                return
            },
            ifRight = { it }
        )

        println("Channel Activation - This will make you a CREATOR")
        print("Confirm? (y/n): ")

        val scanner = Scanner(System.`in`)
        if (scanner.nextLine().lowercase() != "y") {
            println("Cancelled")
            return
        }

        activateChannelUseCase.execute(userId).fold(
            onSuccess = {
                println("Channel activated! You are now a CREATOR and can upload videos!")

                authService.refreshToken(token).fold(
                    onSuccess = { newToken ->
                        currentToken = newToken
                        println("Token refreshed! You can now upload videos.")
                    },
                    onFailure = { error ->
                        println("Failed to refresh token: $error")
                        println("Please logout (option 12) and login again (option 1).")
                        currentToken = null
                    }
                )
            },
            onFailure = { error ->
                println("Activation failed: $error")
            }
        )
    }
}
