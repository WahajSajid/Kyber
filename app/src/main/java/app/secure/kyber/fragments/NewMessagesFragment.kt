package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.adapters.ContactListAdapter
import app.secure.kyber.databinding.FragmentNewMessagesBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import kotlin.getValue

@Suppress("UNCHECKED_CAST")
class NewMessagesFragment : Fragment() {


    private lateinit var binding: FragmentNewMessagesBinding
    private lateinit var contactListAdapter: ContactListAdapter
    private lateinit var rv: RecyclerView
    private lateinit var navController: NavController

    private val vm: ContactsViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentNewMessagesBinding.inflate(inflater, container, false)
        rv = binding.contactsRecyclerView
        navController = findNavController()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.viewVisibility()
        (activity as? MainActivity)?.setAppBar("New Messages")
        setListAdapter()


        binding.createGroupLayout.setOnClickListener {

        }
    }


    private fun setListAdapter() {
        val recyclerview = binding.contactsRecyclerView
        recyclerview.setHasFixedSize(false)
        contactListAdapter = ContactListAdapter(requireContext(), onItemClick = { contactEntity ->

            // FIXED: Using "contact_onion" consistently with ChatFragment and ChatListFragment
            val args = bundleOf(
                "contact_onion" to contactEntity.onionAddress,
                "contact_name" to contactEntity.name,
                "coming_from" to "chat_list" // Ensure polling starts
            )
            findNavController().navigate(R.id.chatFragment, args)

        })

        contactListAdapter.notifyDataSetChanged()
        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                vm.contactsFlow.collect { list ->
                    contactListAdapter.submitList(list)
                }
            }
            layoutManager = LinearLayoutManager(activity)
            adapter = contactListAdapter
        }

    }

}
