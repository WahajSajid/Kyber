package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.viewholders.FinalAddedMemberViewHolder

class FinalAddedMembersAdapter(private val addedMembers: List<AddedMembers>) :
    RecyclerView.Adapter<FinalAddedMemberViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): FinalAddedMemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.final_added_members_item, parent, false)
        return FinalAddedMemberViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: FinalAddedMemberViewHolder,
        position: Int
    ) {
        val addedMember = addedMembers[position]
        holder.name.text = addedMember.name
        holder.id.text = addedMember.id
        val firstLetter = addedMember.name.trim().first()
        holder.profileIcon.text = firstLetter.toString()
    }

    override fun getItemCount(): Int = addedMembers.size
}