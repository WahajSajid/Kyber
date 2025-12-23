package app.secure.kyber.fragments

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.secure.kyber.R
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.getValue


class ContactBottomSheet : BottomSheetDialogFragment() {

    private val vm: ContactsViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
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


        // Make trailing icon clickable
        tilId.setEndIconOnClickListener {
           val unionId = Prefs.getUnionId(requireContext())


        }


        // Prefill if provided
        etId.setText(arguments?.getString(ARG_ID).orEmpty())
        etName.setText(arguments?.getString(ARG_NAME).orEmpty())

        fun validate(): Boolean {
            val idOk = !etId.text.isNullOrBlank()
            val nameOk = !etName.text.isNullOrBlank()

            tilId.error = if (idOk) null else "ID is required"
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
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        KEY_ID to etId.text!!.toString().trim(),
                        KEY_NAME to etName.text!!.toString().trim()
                    )
                )
                vm.saveContact(
                    etId.text!!.toString().trim(),
                    name = etName.text!!.toString().trim()
                )
                dismiss()
            }
        }
    }
}
