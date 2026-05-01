package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.databinding.FragmentContactDetailsBinding
import app.secure.kyber.databinding.FragmentSettingBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.Utils.DateUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ContactDetailsFragment : Fragment() {
    private lateinit var binding: FragmentContactDetailsBinding

    private val contactName by lazy { requireArguments().getString("contact_name").orEmpty() }
    private val contactOnion by lazy { requireArguments().getString("contact_onion").orEmpty() }
    private val comingFrom by lazy { requireArguments().getString("coming_from").orEmpty() }
    private val shortId by lazy { requireArguments().getString("shortId").orEmpty() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentContactDetailsBinding.inflate(inflater, container, false)

        (activity as? MainActivity)?.hideBottomBar()
        (activity as? MainActivity)?.hideTopBar()


        val firstLetter = if (contactName.isNotEmpty()) contactName[0] else '?'
        binding.avatar.text = firstLetter.toString()
        binding.tvName.text = contactName

        // Show shortId as @handle if available
        if (shortId.isNotBlank()) {
            binding.tvHandle.text = "@$shortId"
            binding.tvHandle.visibility = View.VISIBLE
        } else {
            binding.tvHandle.visibility = View.GONE
        }

        binding.messageUser.setOnClickListener {
            val args = bundleOf(
                "contact_onion" to contactOnion,
                "contact_name" to contactName,
                "coming_from" to "chat_list"
            )
            findNavController().navigate(R.id.chatFragment, args)
        }

        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRealtimeContactObserver()
        startPeriodicTimeRefresh()
    }

    private fun setupRealtimeContactObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            db.contactDao().observeContact(contactOnion).collect { contact ->
                if (contact != null) {
                    updatePillTimeDisplay(contact.lastKeyUpdate)
                }
            }
        }
    }

    private fun startPeriodicTimeRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded) {
                try {
                    val db = AppDb.get(requireContext())
                    val contact = db.contactDao().get(contactOnion)
                    if (contact != null) {
                        updatePillTimeDisplay(contact.lastKeyUpdate)
                    }
                    delay(10000) // Update every 10 seconds
                } catch (e: Exception) {
                    android.util.Log.w("ContactDetails", "Error in periodic time refresh", e)
                }
            }
        }
    }

    private fun updatePillTimeDisplay(lastKeyUpdate: Long) {
        val timeStr = DateUtils.getRelativeTimeSpan(lastKeyUpdate)
        binding.tvPill.text = "Updated $timeStr"
    }


    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.hideBottomBar()
        (activity as? MainActivity)?.hideTopBar()
    }
}