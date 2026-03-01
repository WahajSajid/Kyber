package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.GroupCreationBackend.GroupManager
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.adapters.AddMembersAdapter
import app.secure.kyber.adapters.FinalAddedMembersAdapter
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentCreateGroupBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.GroupRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import app.secure.kyber.viewmodels.AddMembersViewModel
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class CreateGroupFragment : Fragment() {

    private lateinit var binding: FragmentCreateGroupBinding
    private lateinit var adapter: FinalAddedMembersAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var myApp: MyApp
    private lateinit var database: FirebaseDatabase
    private lateinit var groupManager: GroupManager
    private lateinit var currentUserId: String
    private lateinit var navController: NavController

    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var contactsAdapter: AddMembersAdapter

    private val viewModel: AddMembersViewModel by viewModels()

    private val vm: GroupsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = GroupRepository(db.groupsDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupsViewModel(repo) as T
        }
    }

    private val contactsViewModel: ContactsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCreateGroupBinding.inflate(inflater, container, false)
        navController = findNavController()

        myApp = requireActivity().application as MyApp
        database = FirebaseDatabase.getInstance()
        groupManager = GroupManager()

        (activity as? MainActivity)?.hideTopBar()
        (activity as? MainActivity)?.hideBottomBar()

        contactsRecyclerView = binding.contactsRecyclerView

        contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()) // Fixed: Added LayoutManager
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                contactsViewModel.contactsFlow.collect { list ->
                    Log.d("### Contacts Size ###", list.size.toString())
                    contactsAdapter = AddMembersAdapter(list, myApp, viewModel)
                    contactsRecyclerView.adapter = contactsAdapter

                    contactsAdapter.itemClickListener(object :
                        AddMembersAdapter.OnItemClickListener {
                        override fun chatItemClickListener(name: String) {
                        }

                        override val mutex: Mutex = Mutex()
                    })
                }
            }
        }
        
        myApp.addedMembersList.observe(viewLifecycleOwner) { list ->
            binding.tvSelectedCount.text = "${list?.size ?: 0} Selected"
        }

        setupAnonymousModeSwitch()
        setupCreateButton()

        return binding.root
    }

    private fun setupAnonymousModeSwitch() {
        binding.switchAnonymous.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.llCreatorViewOnly.visibility = View.VISIBLE
                binding.cvAnonymousMode.setBackgroundResource(R.drawable.creator_view_expanded_view_bg)
                binding.ivAnonymousIcon.setImageResource(R.drawable.selected_anonymous_overlay_ic)
                binding.textAnonymousMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.selected_anonymous_text_color))
            } else {
                binding.llCreatorViewOnly.visibility = View.GONE
                binding.cvAnonymousMode.setBackgroundResource(R.drawable.anonymous_mode_bg)
                binding.ivAnonymousIcon.setImageResource(R.drawable.deselected_overlay_anonymous_ic)
                binding.textAnonymousMode.setTextColor(ContextCompat.getColor(requireContext(), R.color.deselected_anonymous_text_color))
            }
        }
    }

    private fun setupCreateButton() {
        binding.btnCreateGroup.setOnClickListener {
            val groupName = binding.etGroupName.text.toString().trim()
            binding.loading.visibility = View.VISIBLE

            if (groupName.isEmpty()) {
                binding.loading.visibility = View.GONE
                Toast.makeText(context, "Please enter group name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val membersList = myApp.addedMembersList.value
            if (membersList.isNullOrEmpty()) {
                binding.loading.visibility = View.GONE
                Toast.makeText(context, "Please select at least one member", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentUserId = getCurrentUserId()

            if (currentUserId.isEmpty()) {
                binding.loading.visibility = View.GONE
                Toast.makeText(context, "User ID is not available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            createGroup(groupName)
        }
    }

    private fun createGroup(groupName: String) {
        lifecycleScope.launch {
            try {
                binding.btnCreateGroup.isEnabled = false
                // binding.loading.visibility = View.VISIBLE // Removed if loading doesn't exist in layout

                val name = Prefs.getName(requireContext()) ?: "Unknown User"
                val membersList = myApp.addedMembersList.value ?: mutableListOf()

                Log.d("CreateGroupFragment", "Current User ID: $currentUserId, Name: $name")
                Log.d("CreateGroupFragment", "Group Name: $groupName")

                val groupId = groupManager.createGroup(
                    groupName = groupName,
                    groupImage = "", 
                    members = membersList,
                    currentUserId = currentUserId,
                    currentUserName = name,
                    groupViewModel = vm
                )

                if (groupId != null) {
                    Log.d("CreateGroupFragment", "Group created successfully with ID: $groupId")
                    Toast.makeText(context, "Group created successfully!", Toast.LENGTH_SHORT).show()
                    
                    myApp.addedMembersList.value?.clear()
                    myApp.addedMembersList.value = mutableListOf()
                    
                     binding.loading.visibility = View.GONE

                    // Updated navigation to correct destination ID based on action
                     navController.navigate(R.id.action_createGroupFragment_to_groupChatListFragment)
                }
                else {
                    Log.e("CreateGroupFragment", "Group creation returned null ID.")
                    binding.btnCreateGroup.isEnabled = true
                    binding.loading.visibility = View.GONE
                    Toast.makeText(context, "Failed to create group", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CreateGroupFragment", "Exception during group creation: ${e.message}", e)
                binding.btnCreateGroup.isEnabled = true
                binding.loading.visibility = View.GONE
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCurrentUserId(): String {
        return Prefs.getUnionId(requireContext()) ?: ""
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::myApp.isInitialized) {
            myApp.addedMembersList.value?.clear()
            myApp.addedMembersList.value = mutableListOf()
        }
    }
}
