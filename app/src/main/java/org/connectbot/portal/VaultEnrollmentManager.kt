package org.connectbot.portal

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Android vault-access enrollment lifecycle: creating unlock
 * requests, watching for approval via SSE with a polling fallback, and
 * storing or clearing the decrypted vault secret as the enrollment changes.
 */
class VaultEnrollmentManager(
    private val scope: CoroutineScope,
    private val repository: PortalHubRepository,
    private val state: MutableStateFlow<PortalUiState>,
) {
    private var pollJob: Job? = null
    private var eventJob: Job? = null
    private var eventEnrollmentId: String? = null

    suspend fun createUnlockRequest(message: String, pairingId: String?): VaultEnrollment {
        val publicKey = repository.vaultEnrollmentPublicKeyBase64()
        val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }
        val enrollment = repository.createVaultEnrollment(deviceName, publicKey, pairingId)
        repository.saveVaultEnrollmentId(enrollment.id)
        state.update {
            it.copy(
                vaultEnrollmentId = enrollment.id,
                vaultEnrollmentStatus = enrollment.status,
                vaultActionMessage = message,
                error = null,
            )
        }
        startPolling(enrollment.id)
        startEvents(enrollment.id)
        return enrollment
    }

    fun handleEnrollment(enrollment: VaultEnrollment) {
        if (enrollment.status == "revoked") {
            repository.clearVaultEnrollmentId()
            repository.clearVaultEnrollmentKey()
            if (repository.loadVaultDeviceEnrollmentId() == enrollment.id) {
                repository.clearVaultSecret()
                repository.clearVaultDeviceEnrollmentId()
            }
            stop()
            state.update {
                it.copy(
                    vaultSecretStored = repository.loadVaultSecret() != null,
                    vaultEnrollmentId = null,
                    vaultEnrollmentStatus = enrollment.status,
                    vaultActionMessage = "Vault access was revoked in Portal desktop",
                    error = null,
                )
            }
            return
        }
        if (enrollment.status != "approved") {
            state.update {
                it.copy(
                    vaultEnrollmentId = enrollment.id,
                    vaultEnrollmentStatus = enrollment.status,
                    vaultActionMessage = "Vault access request is ${enrollment.status}",
                    error = null,
                )
            }
            return
        }
        val encrypted = enrollment.encryptedSecretBase64
            ?: throw IllegalStateException("Approved vault request did not include an encrypted unlock key")
        val secret = try {
            repository.decryptVaultEnrollmentSecret(encrypted)
        } catch (error: Throwable) {
            repository.clearVaultEnrollmentId()
            repository.clearVaultEnrollmentKey()
            state.update {
                it.copy(
                    vaultEnrollmentId = null,
                    vaultEnrollmentStatus = null,
                    vaultActionMessage = null,
                )
            }
            throw IllegalStateException(
                "Vault unlock failed. Request vault access again and approve the new request in Portal desktop.",
                error,
            )
        }
        validateVaultSecret(secret, state.value.sync?.vault ?: HubVaultConfig())
        repository.saveVaultSecret(secret)
        repository.saveVaultDeviceEnrollmentId(enrollment.id)
        repository.clearVaultEnrollmentId()
        stop()
        state.update {
            it.copy(
                vaultSecretStored = true,
                vaultSecretInput = "",
                vaultEnrollmentId = null,
                vaultEnrollmentStatus = enrollment.status,
                vaultActionMessage = "Vault unlock key stored on this device",
                error = null,
            )
        }
    }

    fun handleEnrollmentEvent(enrollment: VaultEnrollment) {
        if (enrollment.status == "revoked" && repository.loadVaultDeviceEnrollmentId() == enrollment.id) {
            repository.clearVaultSecret()
            repository.clearVaultDeviceEnrollmentId()
            state.update {
                it.copy(
                    vaultSecretStored = false,
                    vaultEnrollmentStatus = enrollment.status,
                    vaultActionMessage = "Vault access was revoked in Portal desktop",
                    error = null,
                )
            }
            return
        }
        if (repository.loadVaultEnrollmentId() == enrollment.id) {
            handleEnrollment(enrollment)
        }
    }

    suspend fun checkStoredEnrollment() {
        val id = repository.loadVaultDeviceEnrollmentId() ?: return
        val enrollment = repository.loadVaultEnrollment(id)
        if (enrollment.status == "revoked") {
            handleEnrollmentEvent(enrollment)
        }
    }

    fun startPolling(id: String) {
        if (id.isBlank()) return
        if (pollJob?.isActive == true && state.value.vaultEnrollmentId == id) return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && repository.loadVaultEnrollmentId() == id && repository.loadVaultSecret() == null) {
                delay(POLL_INTERVAL_MS)
                if (!isActive || repository.loadVaultEnrollmentId() != id || repository.loadVaultSecret() != null) {
                    break
                }
                try {
                    val enrollment = repository.loadVaultEnrollment(id)
                    handleEnrollment(enrollment)
                    if (enrollment.status != "pending") {
                        break
                    }
                } catch (error: Throwable) {
                    state.update { it.copy(error = error.message ?: error.toString()) }
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun startEvents(id: String) {
        if (id.isBlank()) return
        if (eventJob?.isActive == true && eventEnrollmentId == id) return
        eventJob?.cancel()
        eventEnrollmentId = id
        eventJob = scope.launch {
            try {
                repository.streamVaultEnrollmentEvents(id) { enrollment ->
                    scope.launch {
                        handleEnrollmentEvent(enrollment)
                    }
                }
            } catch (_: Throwable) {
                // Polling remains the compatibility fallback for older Hub builds and transient network drops.
            }
        }
    }

    fun stopEvents() {
        eventJob?.cancel()
        eventJob = null
        eventEnrollmentId = null
    }

    fun stop() {
        stopPolling()
        stopEvents()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
