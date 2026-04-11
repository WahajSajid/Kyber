package app.secure.kyber.backend

import app.secure.kyber.backend.beans.*
import retrofit2.Response
import retrofit2.http.*

interface KyberApiService {

    @GET("health")
    suspend fun healthCheck(): Response<Unit>

    // --- Authentication ---

    @POST("api/v1/auth/register-discovery")
    suspend fun registerDiscovery(
        @Body request: RegisterRequest
    ): Response<RegisterResponse>

    // --- Circuit Management ---

    @POST("api/v1/circuit/create")
    suspend fun createCircuit(
        @Body body: EmptyRequest = EmptyRequest()
    ): Response<CircuitResponse>

    @DELETE("api/v1/circuit/{circuitId}")
    suspend fun closeCircuit(
        @Path("circuitId") circuitId: String
    ): Response<Unit>

    // --- Hidden Services ---

    @POST("api/v1/hidden-service/create")
    suspend fun createHiddenService(
        @Body body: EmptyRequest = EmptyRequest()
    ): Response<Unit>

    @GET("api/v1/hidden-service/{onionAddress}/messages")
    suspend fun getMessages(
        @Path("onionAddress") onionAddress: String,
        @Query("circuitId") circuitId: String? = null,
        @Query("since") since: Long? = null
    ): Response<HiddenServiceMessageResponse>

    // --- Messaging ---

    @POST("api/v1/message/send")
    suspend fun sendMessage(
        @Body request: SendMessageRequest
    ): Response<SendMessageResponse>

    // --- Discovery ---

    @GET("api/v1/discovery/search")
    suspend fun searchUser(
        @Query("usernameHash") usernameHash: String
    ): Response<DiscoverySearchResponse>

    @GET("api/v1/discovery/public-key")
    suspend fun getPublicKey(
        @Query("onionAddress") onionAddress: String
    ): Response<PublicKeyResponse>

    @PUT("api/v1/discovery/public-key")
    suspend fun updatePublicKey(
        @Query("onionAddress") onionAddress: String,
        @Body request: UpdatePublicKeyRequest
    ): Response<UpdatePublicKeyResponse>
    // --- Admin - License Keys ---

    @POST("api/v1/admin/license-keys")
    suspend fun createLicenseKey(
        @Body request: LicenseKeyRequest
    ): Response<LicenseActionResponse>

    @GET("api/v1/admin/license-keys")
    suspend fun listLicenseKeys(): Response<LicenseListResponse>

    @POST("api/v1/admin/license-keys/revoke")
    suspend fun revokeLicenseKey(
        @Body request: LicenseKeyRequest
    ): Response<LicenseActionResponse>

    @GET("api/v1/admin/license-keys/validate")
    suspend fun validateLicenseKey(
        @Query("key") key: String
    ): Response<LicenseValidateResponse>
}
