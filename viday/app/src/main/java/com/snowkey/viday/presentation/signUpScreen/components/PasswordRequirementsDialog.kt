package com.snowkey.viday.presentation.signUpScreen.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.snowkey.viday.R
import com.snowkey.viday.ui.theme.VidayTheme

@Composable
fun PasswordRequirementsDialog(onDismiss: () -> Unit = {}) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.password_requirements_title),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.password_requirement_min_length),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.password_requirement_uppercase),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.password_requirement_digit),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.password_requirement_special),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.understood),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}

@Preview
@Composable
fun PasswordRequirementsDialogPreview() {
    VidayTheme(darkTheme = false) {
        PasswordRequirementsDialog()
    }
}

@Preview
@Composable
fun PasswordRequirementsDialogPreviewDark() {
    VidayTheme(darkTheme = true) {
        PasswordRequirementsDialog()
    }
}
