package com.snowkey.viday.presentation.signUpScreen.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snowkey.viday.R

@Composable
fun UsernameInputField(
    username: String,
    onUsernameChange: (String) -> Unit = {},
    isLoading: Boolean = false,
    enabled: Boolean = !isLoading
) {
    var isUsernameFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = {
            if (username.isEmpty() && !isUsernameFocused) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.username),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                isUsernameFocused = focusState.isFocused
            },
        singleLine = true,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        ),
        textStyle = MaterialTheme.typography.titleSmall.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        ),
        shape = RoundedCornerShape(12.dp),
    )
}
