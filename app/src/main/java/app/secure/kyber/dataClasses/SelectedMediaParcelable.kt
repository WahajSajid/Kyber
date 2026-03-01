package app.secure.kyber.dataClasses

import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri
import kotlinx.parcelize.Parcelize

@Parcelize
data class SelectedMediaParcelable(
    val uriString: String,
    val type: String,
    var caption: String = ""
) : Parcelable {
    fun getUri(): Uri = uriString.toUri()
}
