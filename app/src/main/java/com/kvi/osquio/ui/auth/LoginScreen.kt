package com.kvi.osquio.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvi.osquio.R

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state) {
        if (state is LoginUiState.Success) onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dota),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; vm.clearError() },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; vm.clearError() },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.login(username, password) }),
        )

        if (state is LoginUiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                (state as LoginUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.login(username, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state !is LoginUiState.Loading,
        ) {
            if (state is LoginUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Login")
            }
        }
    }
}
