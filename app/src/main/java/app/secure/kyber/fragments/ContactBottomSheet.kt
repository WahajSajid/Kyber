package app.secure.kyber.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.secure.kyber.R
import app.secure.kyber.activities.ScannerActivity
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ContactBottomSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: KyberRepository

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
            val name = result.data?.getStringExtra("name")
            
            val etId = view?.findViewById<TextInputEditText>(R.id.etId)
            val etName = view?.findViewById<TextInputEditText>(R.id.etName)
            
            etId?.setText(onionAddress)
            name?.let { etName?.setText(it) }
            
            if (!onionAddress.isNullOrEmpty()) {
                searchUser(onionAddress)
            }
        }
    }

    companion object {
        const val TAG = "ContactBottomSheet"
        const val REQUEST_KEY = "contact_result"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"

        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"

        fun newInstance(prefillId: String? = null, prefillName: String? = null) =
            ContactBottomSheet().apply {
                arguments = bundleOf(ARG_ID to prefillId, ARG_NAME to prefillName)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.save_contact_bottomsheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tilId   = view.findViewById<TextInputLayout>(R.id.tilId)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val etId    = view.findViewById<TextInputEditText>(R.id.etId)
        val etName  = view.findViewById<TextInputEditText>(R.id.etName)
        val btnSave = view.findViewById<View>(R.id.btnSave)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        // Discovery Feature: Search by Username Hash or Scan QR
        tilId.setEndIconOnClickListener {
            val query = etId.text?.toString()?.trim()
            if (query.isNullOrEmpty()) {
                // If empty, open scanner
                scanLauncher.launch(Intent(requireContext(), ScannerActivity::class.java))
            } else {
                searchUser(query)
            }
        }

        // Prefill if provided
        etId.setText(arguments?.getString(ARG_ID).orEmpty())
        etName.setText(arguments?.getString(ARG_NAME).orEmpty())

        fun validate(): Boolean {
            val idOk = !etId.text.isNullOrBlank()
            val nameOk = !etName.text.isNullOrBlank()

            tilId.error = if (idOk) null else "ID/Onion is required"
            tilName.error = if (nameOk) null else "Name is required"
            btnSave.isEnabled = idOk && nameOk
            return btnSave.isEnabled
        }

        etId.doAfterTextChanged { validate() }
        etName.doAfterTextChanged { validate() }
        validate()

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            if (validate()) {
                val id = etId.text!!.toString().trim()
                val name = etName.text!!.toString().trim()
                
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY, bundleOf(KEY_ID to id, KEY_NAME to name)
                )
                vm.saveContact(id, name)
                dismiss()
            }
        }
    }

    private fun searchUser(query: String) {
        val etId = view?.findViewById<TextInputEditText>(R.id.etId)
        lifecycleScope.launch {
            try {
                // Search by hash first (existing logic)
                val response = repository.searchUser(query)
                if (response.isSuccessful && response.body()?.found == true) {
                    val data = response.body()!!
                    etId?.setText(data.onionAddress)
                    Toast.makeText(requireContext(), "User found!", Toast.LENGTH_SHORT).show()
                } else {
                    // Try as onion address directly
                    val pkResponse = repository.getPublicKey(query)
                    if (pkResponse.isSuccessful) {
                        Toast.makeText(requireContext(), "Onion address verified", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "User not found on network", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Discovery service unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
