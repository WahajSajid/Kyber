package app.secure.kyber.viewholders


import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R

class FinalAddedMemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.name)
    val profileIcon: TextView = view.findViewById(R.id.profile_icon)

    val id: TextView = view.findViewById(R.id.id)
}