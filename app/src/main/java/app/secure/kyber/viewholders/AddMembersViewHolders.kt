package app.secure.kyber.viewholders

import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.adapters.AddMembersAdapter
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.roomdb.ContactEntity
import app.secure.kyber.viewmodels.AddMembersViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@SuppressLint("NotifyDataSetChanged")
class AddMembersViewHolder(
    view: View,
    clickListener: AddMembersAdapter.OnItemClickListener,
    private val myApp: MyApp,
    private val viewModel: AddMembersViewModel
) : RecyclerView.ViewHolder(view) {
    val nameTextView: TextView = view.findViewById(R.id.tvName)
    val select: RelativeLayout = view.findViewById(R.id.contact_item) // Based on add_members_items.xml
    val profileIcon: ImageView = view.findViewById(R.id.ivAvatar)
    val selectedImage: ImageView = view.findViewById(R.id.btnAdd)
    val id: TextView = view.findViewById(R.id.tvStatus)


    init {
        select.setOnClickListener {
            select.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                clickListener.mutex.withLock {
                    clickListener.chatItemClickListener(nameTextView.text.toString())

                    // Toggle the selection state
                    viewModel.toggleMemberSelection(nameTextView.text.toString())
                    bindSelectionState(nameTextView.text.toString(), id.text.toString())
                }
            }
        }
    }

    fun bind(contact: ContactEntity) {
        nameTextView.text = contact.name
        id.text = ""
        // Handle image or generic icon
        profileIcon.setImageResource(R.drawable.anonymous_icon)
        bindSelectionState(contact.name, contact.onionAddress)
    }

    @SuppressLint("ResourceAsColor")
    private fun bindSelectionState(name: String, id: String) {
        val currentList = myApp.addedMembersList.value ?: mutableListOf()
        val member = AddedMembers(name, id)

        if (viewModel.isMemberSelected(name)) {
            selectedImage.setImageResource(R.drawable.selected_contact_ic) // Using available drawable
            select.setBackgroundResource(R.drawable.selected_contact_bg) // Ensure this exists or use pill_bg
            nameTextView.setTextColor(itemView.context.getColor(android.R.color.white))
            
            if (!currentList.contains(member)) {
                currentList.add(member)
                myApp.addedMembersList.value = currentList
            }
        } else {
            selectedImage.setImageResource(R.drawable.deselected_contact_ic)
            select.setBackgroundResource(R.drawable.deselected_contact_bg)
            nameTextView.setTextColor(ContextCompat.getColor(itemView.context, R.color.deselected_contact_name_color))

            if (currentList.contains(member)) {
                currentList.remove(member)
                myApp.addedMembersList.value = currentList
            }
        }
    }
}
