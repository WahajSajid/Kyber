package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.key
import androidx.lifecycle.lifecycleScope
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.backend.common.UsernameHash
import app.secure.kyber.databinding.FragmentDisplayNameBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import app.secure.kyber.Utils.SecureKeyManager
import app.secure.kyber.workers.KeyRotationWorker
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Firebase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class DisplayNameFragment : Fragment(R.layout.fragment_display_name) {

    private lateinit var binding: FragmentDisplayNameBinding
    private lateinit var database: FirebaseDatabase
    private lateinit var databaseReference: DatabaseReference

    @Inject
    lateinit var repository: KyberRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDisplayNameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance("https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/")
        databaseReference = database.getReference("users")
        setClickListeners()
    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener {
            val name = binding.etPwd.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                Snackbar.make(binding.root, "Please enter a display name", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnPwd.isEnabled = false
            lifecycleScope.launch {
                try {
                    val licenseKey = Prefs.getLicense(requireContext()) ?: ""

                    // 1. Generate unique identity using UnionClient
                    val client = UnionClient()
                    val identity = client.exportIdentity()
                    val onionAddress = identity["onionAddress"] ?: ""

                    // --- SECURE KEY GENERATION ---
                    val keyId = UUID.randomUUID().toString()
                    val keypair = SecureKeyManager.generateNewKeyPair()
                    val now = System.currentTimeMillis()
                    
                    val db = AppDb.get(requireContext())
                    db.keyDao().insert(KeyEntity(
                        keyId = keyId,
                        publicKey = keypair.publicKeyBase64,
                        privateKeyEncrypted = keypair.privateKeyEncrypted,
                        createdAt = now,
                        activatedAt = now,
                        expiresAt = now + TimeUnit.DAYS.toMillis(1),
                        status = "ACTIVE"
                    ))
                    // -----------------------------

                    // 2. Hash the display name for the discovery service (API expects Base64)
                    val nameHash = UsernameHash.forDiscovery(name)
                    
                    // 3. Register using the Discovery endpoint since we are providing a username hash
                    val response = repository.registerDiscovery(
                        licenseKey = licenseKey,
                        publicKey = keypair.publicKeyBase64,
                        usernameHash = nameHash,
                        usernameEncrypted = "" // Placeholder
                    )

                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!

                        // 4. Save all identity info to Prefs
                        Prefs.setName(requireContext(), name)
//                        Prefs.setOnionAddress(requireContext(), onionAddress)
                        Prefs.setPublicKey(requireContext(), keypair.publicKeyBase64)
                        Prefs.setSessionToken(requireContext(), body.sessionToken)
                        Prefs.setHashName(requireContext(), nameHash)
                        val onion_address = body.onionAddress ?: ""
                        Prefs.setOnionAddress(requireContext(), onion_address)

                        val shortId = generateShortId(onion_address, 6)
                        Prefs.setShortId(requireContext(), shortId)

                        databaseReference.child(shortId).child("short_id").setValue(shortId)
                        databaseReference.child(shortId).child("onion_address").setValue(onion_address)



                        // Create initial circuit after registration
                        val circuitResp = repository.createCircuit()
                        if (circuitResp.isSuccessful) {
                            Prefs.setCircuitId(
                                requireContext(),
                                circuitResp.body()?.circuitId
                            )
                        }
                        // Schedule the first key rotation
                        KeyRotationWorker.schedule(requireContext())

                        goToMainActivity()
                    } else {
                        val errorMsg = response.body()?.error ?: "Registration failed"
                        Log.e("### Registration Error ###", errorMsg)
                        Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_SHORT).show()
                        // Clean up the key if registration failed
//                        SecureKeyManager.deleteKey(keyId)
                    }
                } catch (e: Exception) {
                    Log.e("DisplayNameFragment", "Error: ${e.message}", e)
                    Snackbar.make(
                        binding.root,
                        "Registration error: ${e.message}",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } finally {
                    binding.btnPwd.isEnabled = true
                }
            }
        }
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }


    fun generateShortId(onionAddress: String, length: Int = 8): String {
        val cleanAddress = onionAddress.removeSuffix(".onion")

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(cleanAddress.toByteArray(Charsets.UTF_8))

        // Convert hash bytes to Base32-like alphanumeric string (uppercase, no ambiguous chars)
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // removed 0,1,I,O to avoid confusion
        val result = StringBuilder()

        for (i in 0 until length) {
            val index = (hashBytes[i].toInt() and 0xFF) % chars.length
            result.append(chars[index])
        }

        return result.toString()
    }

}
