package app.secure.kyber.backend

import app.secure.kyber.backend.beans.*
import retrofit2.Response
import retrofit2.http.*

interface KyberApiService {

    @GET("health")
    suspend fun healthCheck(): Response<Unit>

    @POST("api/v1/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<RegisterResponse>

    @POST("api/v1/auth/register-discovery")
    suspend fun registerDiscovery(
        @Body request: RegisterRequest
    ): Response<RegisterResponse>

    @POST("api/v1/circuit/create")
    suspend fun createCircuit(): Response<CircuitResponse>

    @DELETE("api/v1/circuit/{circuitId}")
    suspend fun closeCircuit(
        @Path("circuitId") circuitId: String
    ): Response<Unit>

    @POST("api/v1/message/send")
    suspend fun sendMessage(
        @Body request: SendMessageRequest
    ): Response<SendMessageResponse>

    @GET("api/v1/discovery/search")
    suspend fun searchUser(
        @Query("usernameHash") usernameHash: String
    ): Response<DiscoverySearchResponse>

    @GET("api/v1/discovery/public-key")
    suspend fun getPublicKey(
        @Query("onionAddress") onionAddress: String
    ): Response<PublicKeyResponse>

    @GET("api/v1/admin/license-keys/validate")
    suspend fun validateLicenseKey(
        @Query("key") key: String
    ): Response<LicenseValidateResponse>
}
