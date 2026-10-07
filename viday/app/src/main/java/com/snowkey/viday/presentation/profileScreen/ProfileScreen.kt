package com.snowkey.viday.presentation.profileScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun ProfileScreen(
    vm: ProfileViewModel = koinViewModel(),
    navController: NavController
) {
    val state by vm.uiState.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val showPasswordDialog by vm.showPasswordDialog.collectAsStateWithLifecycle()

    ProfileScreenContent(
        state = state,
        username = username,
        password = password,
        showPasswordDialog = showPasswordDialog,
        onTogglePasswordDialog = vm::togglePasswordDialog,
        toLogOut = {
            vm.logout()
            navController.navigate(Navigation.LOGIN.route)
        }
    )
}

@Composable
fun ProfileScreenContent(
    state: ProfileUiState,
    username: String,
    password: String,
    showPasswordDialog: Boolean,
    onTogglePasswordDialog: () -> Unit,
    toLogOut: () -> Unit
) {
    when (state) {

        is ProfileUiState.Idle -> {
            ProfileForm(
                username = username,
                password = password,
                showPasswordDialog = showPasswordDialog,
                toLogOut = toLogOut,
                onTogglePasswordDialog = onTogglePasswordDialog
            )
        }

        else -> {}
    }
}

@Composable
fun ProfileLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ProfileForm(
    username: String,
    password: String,
    showPasswordDialog: Boolean = false,
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    toLogOut: () -> Unit = {},
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
            verticalArrangement = Arrangement.spacedBy(80.dp)
        ) {

            Icon(
                imageVector = Icons.Default.AccountBox,
                modifier = Modifier
                    .size(120.dp),
                contentDescription = stringResource(R.string.account),
                tint = MaterialTheme.colorScheme.primary
            )

            Column() {
                UsernameInputField(
                    username = username,
                    onUsernameChange = onUsernameChange,
                    enabled = false
                )

                PasswordInputField(
                    password = password,
                    onPasswordChange = onPasswordChange,
                    enabled = false
                )

                TextButton(
                    onClick = { toLogOut() }
                ) {
                    Text(
                        text = stringResource(R.string.logout),
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
fun ProfileEmptyFormPreview() {
    VidayTheme(dynamicColor = false, darkTheme = false) {
        ProfileForm(username = "", password = "")
    }
}


@Preview
@Composable
fun ProfileEmptyFormPreviewDark() {
    VidayTheme(dynamicColor = false, darkTheme = true) {
        ProfileForm(username = "", password = "")
    }
}

@Preview
@Composable
fun ProfileFormPreview() {
    VidayTheme(dynamicColor = false, darkTheme = false) {
        ProfileForm(username = "ps", password = "12345678")
    }
}


@Preview
@Composable
fun ProfileFormPreviewDark() {
    VidayTheme(dynamicColor = false, darkTheme = true) {
        ProfileForm(username = "ps", password = "12345678")
    }
}
