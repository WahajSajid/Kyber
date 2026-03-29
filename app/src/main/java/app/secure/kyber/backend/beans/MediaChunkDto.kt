package app.secure.kyber.backend.beans

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MediaChunkDto(
    @Json(name = "mediaId")    val mediaId: String,
    @Json(name = "messageId")  val messageId: String,
    @Json(name = "index")      val index: Int,
    @Json(name = "total")      val total: Int,
    @Json(name = "mimeType")   val mimeType: String,
    @Json(name = "data")       val data: String,
    @Json(name = "ampsJson")   val ampsJson: String = "",
    @Json(name = "caption")    val caption: String = "",
    @Json(name = "totalBytes") val totalBytes: Long = 0L,
    @Json(name = "durationMs") val durationMs: Long = 0L
)