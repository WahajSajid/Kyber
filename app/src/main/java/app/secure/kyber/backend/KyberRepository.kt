package app.secure.kyber.backend

import android.content.Context
import app.secure.kyber.backend.beans.*
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KyberRepository @Inject constructor(
    private val apiService: KyberApiService,
    @ApplicationContext private val context: Context
) {

    suspend fun healthCheck(): Response<Unit> =
        apiService.healthCheck()

    // --- Authentication ---

    suspend fun register(licenseKey: String, publicKey: String, usernameHash: String? = null, usernameEncrypted: String? = null): Response<RegisterResponse> =
        apiService.register(RegisterRequest(licenseKey, publicKey, usernameHash, usernameEncrypted))

    suspend fun registerDiscovery(licenseKey: String, publicKey: String, usernameHash: String, usernameEncrypted: String): Response<RegisterResponse> =
        apiService.registerDiscovery(RegisterRequest(licenseKey, publicKey, usernameHash, usernameEncrypted))

    // --- Circuit Management ---

    suspend fun createCircuit(): Response<CircuitResponse> =
        apiService.createCircuit()

    suspend fun closeCircuit(circuitId: String): Response<Unit> =
        apiService.closeCircuit(circuitId)

    // --- Hidden Services ---

    suspend fun createHiddenService(): Response<Unit> =
        apiService.createHiddenService()

    suspend fun getMessages(onionAddress: String): Response<HiddenServiceMessageResponse> =
        apiService.getMessages(onionAddress)

    // --- Messaging ---

    suspend fun sendMessage(receiverOnion: String, payload: String, circuitId: String): Response<SendMessageResponse> =
        apiService.sendMessage(SendMessageRequest(receiverOnion, payload, circuitId))

    // --- Discovery ---

    suspend fun searchUser(usernameHash: String): Response<DiscoverySearchResponse> =
        apiService.searchUser(usernameHash)

    suspend fun getPublicKey(onionAddress: String): Response<PublicKeyResponse> =
        apiService.getPublicKey(onionAddress)

    // --- Admin - License Keys ---

    suspend fun createLicenseKey(licenseKey: String): Response<LicenseActionResponse> =
        apiService.createLicenseKey(LicenseKeyRequest(licenseKey))

    suspend fun listLicenseKeys(): Response<LicenseListResponse> =
        apiService.listLicenseKeys()

    suspend fun revokeLicenseKey(licenseKey: String): Response<LicenseActionResponse> =
        apiService.revokeLicenseKey(LicenseKeyRequest(licenseKey))

    suspend fun validateLicense(key: String): Response<LicenseValidateResponse> =
        apiService.validateLicenseKey(key)
}
