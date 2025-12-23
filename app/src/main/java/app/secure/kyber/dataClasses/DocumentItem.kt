package app.secure.kyber.dataClasses

data class DocumentItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val extension: String, // "pdf", "psd", "cdr", "doc", etc.
    val timestampMillis: Long
)
