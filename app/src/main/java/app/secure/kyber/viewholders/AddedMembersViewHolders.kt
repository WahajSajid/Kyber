package app.secure.kyber.viewholders

import app.secure.kyber.R
import app.secure.kyber.adapters.AddedMembersAdapter


import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class AddedMembersViewHolder(view: View, clickListener: AddedMembersAdapter.OnItemClickListener):RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.added_member_name)
    val removeIcon:ImageView = view.findViewById(R.id.remove_icon)
    val profileIcon:TextView = view.findViewById(R.id.profile_icon)


    //Setup the click listener for the removeIcon
    init {
        removeIcon.setOnClickListener {
            removeIcon.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                clickListener.mutex.withLock {
                    clickListener.chatItemClickListener(name.text.toString())
                }
            }
        }
    }

}