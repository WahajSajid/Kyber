package app.secure.kyber.adapters

import app.secure.kyber.R
import app.secure.kyber.viewholders.AddMembersViewHolder


import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.roomdb.ContactEntity
import app.secure.kyber.viewmodels.AddMembersViewModel
import kotlinx.coroutines.sync.Mutex

@Suppress("DEPRECATION")
class AddMembersAdapter(
    private val contacts: List<ContactEntity>,
    val myApp: MyApp,
    private val viewModel: AddMembersViewModel
) : RecyclerView.Adapter<AddMembersViewHolder>() {


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


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddMembersViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.add_members_items, parent, false)
        return AddMembersViewHolder(view, clickListener, myApp, viewModel)
    }

    override fun getItemCount(): Int = contacts.size

    override fun onBindViewHolder(holder: AddMembersViewHolder, position: Int) {
        val addedMember = contacts[position]
        holder.bind(addedMember)

    }
}