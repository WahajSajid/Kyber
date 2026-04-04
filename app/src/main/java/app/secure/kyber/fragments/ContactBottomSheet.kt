package app.secure.kyber.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.ScannerActivity
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ContactBottomSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: KyberRepository
    private var verificationStatus: String = "pending"

    private val vm: ContactsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val onionAddress = result.data?.getStringExtra("onionAddress")
            val etId = view?.findViewById<TextInputEditText>(R.id.etId)
            etId?.setText(onionAddress)
            if (!onionAddress.isNullOrEmpty()) {
                verifyOnion(onionAddress)
            }
        }
    }

    companion object {
        const val TAG = "ContactBottomSheet"
        private const val ARG_ID = "arg_id"

        fun newInstance(prefillId: String? = null) =
            ContactBottomSheet().apply {
                arguments = bundleOf(ARG_ID to prefillId)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.save_contact_bottomsheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tilId   = view.findViewById<TextInputLayout>(R.id.tilId)
        val etId    = view.findViewById<TextInputEditText>(R.id.etId)
        val btnSave = view.findViewById<View>(R.id.btnSave)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        // QR Scanning and Onion Verification
        tilId.setEndIconOnClickListener {
            val query = etId.text?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                scanLauncher.launch(Intent(requireContext(), ScannerActivity::class.java))
            } else {
                if (query.endsWith(".onion") || query.startsWith("union_")) {
                    verifyOnion(query)
                }
            }
        }

        etId.setText(arguments?.getString(ARG_ID).orEmpty())

        fun validate(): Boolean {
            val idOk = !etId.text.isNullOrBlank()
            tilId.error = if (idOk) null else "ID/Onion is required"
            btnSave.isEnabled = idOk
            return idOk
        }

        etId.doAfterTextChanged { validate() }
        validate()

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            if (validate()) {
                val id = etId.text!!.toString().trim()

                lifecycleScope.launch {
                    try {
                        // 1. Fetch user info (including public key) before starting chat
                        // This ensures we ALWAYS have the latest public key.
                        val response = repository.getPublicKey(id)
                        if (response.isSuccessful && response.body() != null) {
                            val publicKey = response.body()!!.publicKey
                            Log.d(TAG, "Fetched public key for $id: $publicKey")
                            
                            // 2. Cache the public key for the upcoming message request flow
                            requireContext().getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                                .edit()
                                .putString("pending_key_$id", publicKey)
                                .apply()

                            // If they are already a contact, update their public key in the DB too
                            val existingContact = vm.getContact(id)
                            if (existingContact != null && existingContact.publicKey != publicKey) {
                                val db = AppDb.get(requireContext())
                                val contactRepo = ContactRepository(db.contactDao())
                                contactRepo.saveContact(id, existingContact.name, publicKey)
                            }

                            val name = existingContact?.name ?: ""
                            val args = bundleOf(
                                "contact_onion" to id,
                                "contact_name" to name,
                                "coming_from" to "chat_list"
                            )

                            requireActivity().findNavController(R.id.main_fragment).navigate(R.id.chatFragment, args)
                            dismiss()
                        } else {
                            verificationStatus = "not found"
                            Toast.makeText(requireContext(), "User not found or has no public key", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        verificationStatus = "failed"
                        Log.e(TAG, "Error fetching public key", e)
                        Toast.makeText(requireContext(), "Error connecting to discovery service", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Snackbar.make(view.rootView, "Please enter onion address", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyOnion(onionAddress: String) {
        lifecycleScope.launch {
            try {
                val pkResponse = repository.getPublicKey(onionAddress)
                if (pkResponse.isSuccessful) {
                    verificationStatus = "verified"
                    Toast.makeText(requireContext(), "Onion address verified", Toast.LENGTH_SHORT).show()
                } else {
                    verificationStatus = "not found"
                    Toast.makeText(requireContext(), "Address not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                verificationStatus = "failed"
                Toast.makeText(requireContext(), "Verification failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
