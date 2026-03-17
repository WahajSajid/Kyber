package app.secure.kyber.backend

import android.content.Context
import app.secure.kyber.backend.beans.*
import app.secure.kyber.backend.common.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KyberRepository @Inject constructor(
    private val apiService: KyberApiService,
    @ApplicationContext private val context: Context
) {

    suspend fun validateLicense(key: String): Response<LicenseValidateResponse> =
        apiService.validateLicenseKey(key)

    suspend fun register(licenseKey: String, publicKey: String): Response<RegisterResponse> =
        apiService.register(RegisterRequest(licenseKey, publicKey))

    suspend fun createCircuit(): Response<CircuitResponse> =
        apiService.createCircuit()

    suspend fun sendMessage(receiverOnion: String, payload: String, circuitId: String): Response<SendMessageResponse> =
        apiService.sendMessage(SendMessageRequest(receiverOnion, payload, circuitId))

    suspend fun searchUser(usernameHash: String): Response<DiscoverySearchResponse> =
        apiService.searchUser(usernameHash)

    suspend fun getPublicKey(onionAddress: String): Response<PublicKeyResponse> =
        apiService.getPublicKey(onionAddress)
}
