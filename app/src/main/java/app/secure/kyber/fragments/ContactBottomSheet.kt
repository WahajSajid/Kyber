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
import app.secure.kyber.backend.common.UsernameHash
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ContactBottomSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: KyberRepository

    private lateinit var database: FirebaseDatabase
    private lateinit var databaseReference: DatabaseReference

    private val vm: ContactsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }

    // After scan: auto-fill the field and trigger verification
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val scannedId = result.data?.getStringExtra("short_id")
                val etId = view?.findViewById<TextInputEditText>(R.id.etId)
                if (!scannedId.isNullOrEmpty()) {
                    etId?.setText(scannedId)
                    // Verify immediately after scan (toast only, no navigation)
                    lookupAndVerify(scannedId, onSuccess = { onionAddress ->
                        showToast("Onion address verified ✓")
                    })
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
        val tilId     = view.findViewById<TextInputLayout>(R.id.tilId)
        val etId      = view.findViewById<TextInputEditText>(R.id.etId)
        val btnSave   = view.findViewById<View>(R.id.btnSave)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        database = FirebaseDatabase.getInstance(
            "https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/"
        )
        databaseReference = database.getReference("users")

        // End icon: if field is empty → open scanner; if field has text → verify it
        tilId.setEndIconOnClickListener {
            val query = etId.text?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                scanLauncher.launch(Intent(requireContext(), ScannerActivity::class.java))
            } else {
                lookupAndVerify(query, onSuccess = { onionAddress ->
                    showToast("Onion address verified ✓")
                })
            }
        }

        etId.setText(arguments?.getString(ARG_ID).orEmpty())

        fun validate(): Boolean {
            val idOk = !etId.text.isNullOrBlank()
            tilId.error = if (idOk) null else "ID is required"
            btnSave.isEnabled = idOk
            return idOk
        }

        etId.doAfterTextChanged { validate() }
        validate()

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            if (!validate()) {
                Snackbar.make(view.rootView, "Please enter an ID", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val inputId = etId.text!!.toString().trim()

            // Step 1 & 2: Firebase lookup → API search
            lookupAndVerify(inputId, onSuccess = { onionAddress ->
                // Step 3: User found — fetch public key and navigate
                lifecycleScope.launch {
                    try {
                        val response = repository.getPublicKey(onionAddress)
                        if (response.isSuccessful && response.body() != null) {
                            val publicKey = response.body()!!.publicKey
                            Log.d(TAG, "Fetched public key for $onionAddress")

                            // Cache the public key for the upcoming message request flow
                            requireContext()
                                .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                                .edit()
                                .putString("pending_key_$onionAddress", publicKey)
                                .apply()

                            // If already a contact, update their public key if it changed
                            val existingContact = vm.getContact(onionAddress)
                            if (existingContact != null && existingContact.publicKey != publicKey) {
                                val contactRepo =
                                    ContactRepository(AppDb.get(requireContext()).contactDao())
                                contactRepo.saveContact(inputId, existingContact.name, publicKey)
                            }

                            val args = bundleOf(
                                "contact_onion" to onionAddress,
                                "contact_name" to (existingContact?.name ?: ""),
                                "coming_from" to "chat_list"
                            )
                            requireActivity()
                                .findNavController(R.id.main_fragment)
                                .navigate(R.id.chatFragment, args)
                            dismiss()

                        } else {
                            showToast("User not found or has no public key")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching public key", e)
                        showToast("Error connecting to discovery service")
                    }
                }
            })
        }
    }

    /**
     * Full lookup flow:
     * 1. Use [userId] to fetch the onion address from Firebase.
     * 2. Search that onion address via the discovery API.
     * 3. Call [onSuccess] with the resolved onion address if found, otherwise show a toast.
     */
    private fun lookupAndVerify(userId: String, onSuccess: (onionAddress: String) -> Unit) {
        // Step 1: Firebase — get onion address from user ID
        databaseReference.child(userId).child("onion_address").get()
            .addOnSuccessListener { snapshot ->
                val onionFromFirebase = snapshot.value as? String
                if (onionFromFirebase.isNullOrBlank()) {
                    showToast("User not found")
                    return@addOnSuccessListener
                }

                // Step 2: Discovery API — search using the onion address
                lifecycleScope.launch {
                    try {
                        val resp = repository.getPublicKey(onionFromFirebase)
                        if (resp.isSuccessful) {
                            val resolvedOnion = resp.body()?.onionAddress
                            if (!resolvedOnion.isNullOrBlank()) {
                                // Step 3: Found — hand off to caller
                                onSuccess( resolvedOnion)
                            } else {
                                showToast("User not found on network")
                                Log.d("### Onion Address ###", onionFromFirebase)
                            }
                        } else {
                            showToast("User not found on network")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Discovery search failed", e)
                        showToast("Verification failed. Please try again.")
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Firebase lookup failed", error)
                showToast("Lookup failed: ${error.message}")
            }
    }

    private fun showToast(message: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }
}