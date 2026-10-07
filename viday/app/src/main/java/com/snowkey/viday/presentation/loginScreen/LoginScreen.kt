package com.snowkey.viday.presentation.loginScreen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.snowkey.viday.R
import com.snowkey.viday.model.Navigation
import com.snowkey.viday.presentation.signUpScreen.components.PasswordInputField
import com.snowkey.viday.presentation.signUpScreen.components.PasswordRequirementsDialog
import com.snowkey.viday.presentation.signUpScreen.components.UsernameInputField
import com.snowkey.viday.ui.theme.VidayTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun LoginScreen(
    vm: LoginViewModel = koinViewModel(),
    navController: NavController
) {
    val state by vm.uiState.collectAsState()
    val username by vm.usernameField.collectAsStateWithLifecycle()
    val password by vm.passwordField.collectAsStateWithLifecycle()
    val showPasswordDialog by vm.showPasswordDialog.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val actionLabelText = stringResource(R.string.repeat)

    val errorState = state as? LoginUiState.Error
    val errorMessage = errorState?.let { stringResource(it.messageResId) }

    LaunchedEffect(errorState, errorMessage) {
        if (errorState != null && errorMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = actionLabelText,
                duration = SnackbarDuration.Short
            )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    errorState.onRepeat.invoke()
                }

                SnackbarResult.Dismissed -> {}
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    actionColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            LoginScreenContent(
                state = state,
                username = username,
                password = password,
                showPasswordDialog = showPasswordDialog,
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onLoginClick = vm::login,
                onTogglePasswordDialog = vm::togglePasswordDialog,
                toSignUp = { navController.navigate(Navigation.SIGNUP.route) },
                onDone = { userId ->
                    val route = Navigation.createVideosRoute(
                        userId = userId,
                        playlistId = null
                    )
                    navController.navigate(route) {
                        popUpTo(Navigation.LOGIN.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

@Composable
fun LoginScreenContent(
    state: LoginUiState,
    username: String,
    password: String,
    showPasswordDialog: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onTogglePasswordDialog: () -> Unit,
    toSignUp: () -> Unit,
    onDone: (Long) -> Unit
) {
    when (state) {
        is LoginUiState.Loading -> {
            LoginLoading()
        }

        is LoginUiState.Success -> {
            LaunchedEffect(Unit) {
                onDone(state.userId)
            }
        }

        else -> {
            LoginForm(
                username = username,
                password = password,
                showPasswordDialog = showPasswordDialog,
                isLoading = state is LoginUiState.Loading,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
                onLoginClick = onLoginClick,
                toSignUp = toSignUp,
                onTogglePasswordDialog = onTogglePasswordDialog
            )
        }
    }
}

@Composable
fun LoginLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoginForm(
    username: String,
    password: String,
    showPasswordDialog: Boolean = false,
    isLoading: Boolean = false,
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onLoginClick: () -> Unit = {},
    toSignUp: () -> Unit = {},
    onTogglePasswordDialog: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = 60.dp, vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(60.dp)
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                Image(
                    modifier = Modifier
                        .size(120.dp),
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = stringResource(R.string.app_name)
                )
                Text(
                    text = "VIDAY",
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Column() {
                UsernameInputField(
                    username = username,
                    onUsernameChange = onUsernameChange,
                    isLoading = isLoading
                )

                PasswordInputField(
                    password = password,
                    onPasswordChange = onPasswordChange,
                    isLoading = isLoading
                )

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onTogglePasswordDialog,
                    enabled = !isLoading
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Spacer(modifier = Modifier.size(16.dp))
                    }
                }

                Button(
                    onClick = onLoginClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = username.isNotBlank() && password.length >= 6 && !isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.login),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                TextButton(
                    onClick = { toSignUp() },
                    enabled = !isLoading
                ) {
                    Text(
                        text = stringResource(R.string.create_account),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showPasswordDialog) {
        PasswordRequirementsDialog(onDismiss = onTogglePasswordDialog)
    }
}

@Preview
@Composable
fun LoginEmptyFormPreview() {
    VidayTheme(dynamicColor = false, darkTheme = false) {
        LoginForm(username = "", password = "")
    }
}


@Preview
@Composable
fun LoginEmptyFormPreviewDark() {
    VidayTheme(dynamicColor = false, darkTheme = true) {
        LoginForm(username = "", password = "")
    }
}

@Preview
@Composable
fun LoginFormPreview() {
    VidayTheme(dynamicColor = false, darkTheme = false) {
        LoginForm(username = "ps", password = "12345678")
    }
}


@Preview
@Composable
fun LoginFormPreviewDark() {
    VidayTheme(dynamicColor = false, darkTheme = true) {
        LoginForm(username = "ps", password = "12345678")
    }
}
