package app.secure.kyber.dataClasses

data class LinkItem(
    val id: String,
    val title: String,
    val displayPathOrUrl: Int, // drawable resource id as string for demo, or image URI/path
    val timestampMillis: Long,
    val url:String
)
