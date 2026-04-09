package app.secure.kyber.fragments

import android.annotation.SuppressLint
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
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.R
import app.secure.kyber.adapters.ContactListAdapter
import app.secure.kyber.backend.models.ContactModel
import app.secure.kyber.databinding.FragmentContactsBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import kotlin.getValue


class ContactsFragment : Fragment(R.layout.fragment_contacts) {
    private lateinit var binding: FragmentContactsBinding
    private lateinit var navController: NavController

    private lateinit var contactListModel: List<ContactModel>

    private lateinit var contactListAdapter: ContactListAdapter

    private val vm: ContactsViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navController = view.findNavController()

//        binding.btnCreateContact.setOnClickListener {
//            ContactBottomSheet.newInstance().show(parentFragmentManager, ContactBottomSheet.TAG)
//
//        }

        setListAdapter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setListAdapter() {
        val recyclerview = binding.rvContacts
        recyclerview.setHasFixedSize(false)
        contactListAdapter = ContactListAdapter(requireContext(), onItemClick = { contactEntity ->

            val args = bundleOf(
                "contact_onion" to contactEntity.onionAddress,
                "contact_name" to contactEntity.name,
                "coming_from" to "chat_list"
            )
            findNavController().navigate(R.id.contactDetailsFragment, args)

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