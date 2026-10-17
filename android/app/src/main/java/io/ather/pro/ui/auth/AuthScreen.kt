package io.ather.pro.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import io.ather.pro.data.auth.PhoneNumbers
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.data.auth.AuthUiState
import io.ather.pro.data.auth.DiscoveredScooter

@Composable
fun AuthScreen(
    state: AuthUiState,
    onPhoneChanged: (String) -> Unit,
    onCountryChanged: (String) -> Unit,
    onOtpChanged: (String) -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onSelectScooter: (DiscoveredScooter) -> Unit,
    onRetryScooters: () -> Unit,
    onBackToPhone: () -> Unit,
    onImportSeed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Athr+",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = when (state.step) {
                AuthStep.PHONE -> "Sign in with your registered mobile number"
                AuthStep.OTP -> "Enter the OTP sent by SMS"
                AuthStep.SCOOTER_SELECT -> "Select your scooter"
                AuthStep.READY -> "Signed in"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = when (state.step) {
                AuthStep.PHONE -> "Welcome back"
                AuthStep.OTP -> "Check your messages"
                else -> "Your scooters"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state.step) {
                    AuthStep.PHONE -> PhoneStep(
                        phone = state.phone,
                        countryCode = state.countryCode,
                        hasError = state.errorMessage != null,
                        onCountryChanged = onCountryChanged,
                        isLoading = state.isLoading,
                        onPhoneChanged = onPhoneChanged,
                        onRequestOtp = onRequestOtp
                    )

                    AuthStep.OTP -> OtpStep(
                        phone = state.phone,
                        otp = state.otp,
                        countryCode = state.countryCode,
                        hasError = state.errorMessage != null,
                        resendAvailableAtMillis = state.resendAvailableAtMillis,
                        onResend = onRequestOtp,
                        isLoading = state.isLoading,
                        onOtpChanged = onOtpChanged,
                        onVerifyOtp = onVerifyOtp,
                        onBack = onBackToPhone
                    )

                    AuthStep.SCOOTER_SELECT -> ScooterStep(
                        scooters = state.scooters,
                        isLoading = state.isLoading,
                        onSelectScooter = onSelectScooter,
                        onRetry = onRetryScooters,
                        onBack = onBackToPhone
                    )

                    AuthStep.READY -> Unit
                }

            }
        }

        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.primary
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state.step == AuthStep.PHONE) {
            TextButton(
                onClick = onImportSeed,
                enabled = !state.isLoading,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("I have a session file — import it")
            }
        }
    }
}

@Composable
private fun PhoneStep(
    phone: String,
    countryCode: String,
    hasError: Boolean,
    isLoading: Boolean,
    onCountryChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onRequestOtp: () -> Unit
) {
    var showCountries by remember { mutableStateOf(false) }
    val country = PhoneNumbers.country(countryCode)
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !isLoading && PhoneNumbers.canSubmit(phone, countryCode)
    val submit = { if (canSubmit) { keyboard?.hide(); onRequestOtp() } }
    OutlinedButton(onClick = { showCountries = true }, enabled = !isLoading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("${country.label}  ▾")
    }
    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChanged,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Mobile number") },
        prefix = { Text("+${country.dialCode} ") },
        supportingText = { Text("Use the number registered with your Ather account") },
        isError = hasError,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        colors = authFieldColors()
    )
    Button(onClick = submit, enabled = canSubmit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (isLoading) "Sending…" else "Send OTP")
    }
    if (showCountries) CountryPicker(countryCode, onDismiss = { showCountries = false }) {
        onCountryChanged(it)
        showCountries = false
    }
}

@Composable
private fun CountryPicker(selected: String, onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        PhoneNumbers.countries.filter {
            it.name.contains(query.trim(), ignoreCase = true) ||
                it.region.contains(query.trim(), ignoreCase = true) ||
                ("+${it.dialCode}").contains(query.trim())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your country") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Country or calling code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.region }) { country ->
                        TextButton(onClick = { onSelected(country.region) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (country.region == selected) "✓ ${country.label}" else country.label)
                        }
                    }
                    if (filtered.isEmpty()) item { Text("No countries found") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun OtpStep(
    phone: String,
    countryCode: String,
    otp: String,
    hasError: Boolean,
    resendAvailableAtMillis: Long,
    isLoading: Boolean,
    onOtpChanged: (String) -> Unit,
    onVerifyOtp: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !isLoading && otp.length in 4..8
    val submit = { if (canSubmit) { keyboard?.hide(); onVerifyOtp() } }
    var secondsLeft by remember(resendAvailableAtMillis) {
        mutableStateOf(((resendAvailableAtMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(resendAvailableAtMillis) {
        do {
            secondsLeft = ((resendAvailableAtMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            if (secondsLeft > 0) delay(1000)
        } while (secondsLeft > 0)
    }
    Text("Sent to +${PhoneNumbers.country(countryCode).dialCode} $phone",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = otp,
        onValueChange = onOtpChanged,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Verification code") },
        placeholder = { Text("Enter SMS code") },
        supportingText = { Text("Type or paste the code from your SMS") },
        textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
        isError = hasError,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        colors = authFieldColors()
    )
    Button(onClick = submit, enabled = canSubmit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (isLoading) "Please wait…" else "Verify & continue")
    }
    TextButton(onClick = onResend, enabled = !isLoading && secondsLeft == 0L,
        modifier = Modifier.fillMaxWidth()) {
        Text(if (secondsLeft > 0) "Resend code in ${secondsLeft}s" else "Resend code")
    }
    TextButton(onClick = onBack, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
        Text("Change mobile number")
    }
}

@Composable
private fun ScooterStep(
    scooters: List<DiscoveredScooter>,
    isLoading: Boolean,
    onSelectScooter: (DiscoveredScooter) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    if (scooters.isEmpty() && !isLoading) {
        Text(
            text = "No scooters were found on this account. Pull them again, or start over with another number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onRetry,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry")
        }
    } else {
        Text(
            text = "Tap your scooter to continue",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        scooters.forEach { scooter ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLoading) { onSelectScooter(scooter) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = scooter.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    listOfNotNull(scooter.modelType, scooter.registration, scooter.colour)
                        .forEach { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }
            }
        }
        if (scooters.isNotEmpty()) {
            TextButton(
                onClick = onRetry,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh list")
            }
        }
    }
    TextButton(onClick = onBack, enabled = !isLoading) {
        Text("Start over")
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary
)
