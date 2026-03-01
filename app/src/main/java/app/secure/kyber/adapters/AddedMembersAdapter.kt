package app.secure.kyber.adapters


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.viewholders.AddedMembersViewHolder

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AddedMembersAdapter(private val addedMembers:List<AddedMembers>):RecyclerView.Adapter<AddedMembersViewHolder>() {


    private lateinit var clickListener: OnItemClickListener

    interface OnItemClickListener {
        fun chatItemClickListener(
            name: String
        )

        val mutex: Mutex
    }

    fun itemClickListener(listener: OnItemClickListener) {
        clickListener = listener
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddedMembersViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.added_members_items, parent, false)
        return AddedMembersViewHolder(view,clickListener)
    }

    override fun getItemCount(): Int  = addedMembers.size

    override fun onBindViewHolder(holder: AddedMembersViewHolder, position: Int) {
        val addedMember = addedMembers[position]
        holder.name.text = addedMember.name
        val firstLetter = addedMember.name.trim().first()
        holder.profileIcon.text = firstLetter.toString()
    }
}

//Class to store the data of the added members
data class AddedMembers(val name:String, val id:String)