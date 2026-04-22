package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import app.secure.kyber.GroupCreationBackend.LoadGroups
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.adapters.GroupChatListAdapter
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentGroupChatListBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.GroupRepository
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import com.google.firebase.database.FirebaseDatabase

@Suppress("UNCHECKED_CAST")
class GroupChatListFragment : Fragment() {


    private lateinit var groupChatListAdapter: GroupChatListAdapter

    private lateinit var binding: FragmentGroupChatListBinding
    private lateinit var controller: NavController
    private lateinit var myApp: MyApp

    private lateinit var database: FirebaseDatabase
    private val vm: GroupsViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = GroupRepository(db.groupsDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupsViewModel(repo) as T
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentGroupChatListBinding.inflate(inflater, container, false)
        myApp = requireActivity().application as MyApp
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = findNavController()
        database =
            FirebaseDatabase.getInstance("https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/")
        binding.btnIndividualChat.setOnClickListener {
            myApp.tabBtnState = "individual_chat"
            controller.navigate(R.id.action_groupChatListFragment_to_chatListFragment)
        }

        binding.btnRequest.setOnClickListener {
            controller.navigate(R.id.action_groupChatListFragment_to_messageRequestsFragment)
        }

        val unionId = Prefs.getOnionAddress(requireContext()) ?: ""

        //Retrieve the groups from the database
        LoadGroups.loadGroup(requireContext(), unionId, database, vm)


        setListAdapter()


    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setListAdapter() {
        val recyclerview = binding.groupChatRecyclerView
        recyclerview.layoutManager = LinearLayoutManager(activity)
        recyclerview.setHasFixedSize(false)
        groupChatListAdapter = GroupChatListAdapter(requireContext(), onItemClick = { chatModel ->
            val args = bundleOf(
                "group_id" to chatModel.groupId,
                "group_name" to chatModel.groupName,
                "coming_from" to "group_chat_list"
            )
            controller.navigate(R.id.action_groupChatListFragment_to_chatFragment, args)
        })
        recyclerview.adapter = groupChatListAdapter

        // Observe the reactive StateFlow — any change to newMessagesCount in Room
        // (incremented when a new message arrives, reset when the chat is opened)
        // will automatically flow here and update the badge without extra wiring.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                vm.groupChatFlow.collect { groups ->
                    Log.d("### Groups ###", groups.size.toString())
                    groupChatListAdapter.submitList(groups)
                }
            }
        }
    }


}