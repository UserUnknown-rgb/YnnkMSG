package com.Ynnk.YnnkMsg.ui.contacts

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.databinding.ActivityEditContactBinding
import com.Ynnk.YnnkMsg.email.EmailService
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.util.ImageUtils
import com.Ynnk.YnnkMsg.util.SecurePrefs
import com.Ynnk.YnnkMsg.util.Base26Encoder
import kotlinx.coroutines.launch
import java.io.File

class EditContactActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONTACT_EMAIL = "extra_contact_email"
    }

    private lateinit var binding: ActivityEditContactBinding
    private lateinit var contactEmail: String
    private var currentContact: Contact? = null
    private var selectedAvatarUri: Uri? = null
    private val db by lazy { AppDatabase.getInstance(this) }
    private val emailService by lazy { EmailService(this) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedAvatarUri = it
            Glide.with(this).load(it).circleCrop().into(binding.ivContactAvatar)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactEmail = intent.getStringExtra(EXTRA_CONTACT_EMAIL) ?: run {
            finish()
            return
        }

        setupToolbar()
        observeContactInfo()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.edit_contact)
    }

    private fun observeContactInfo() {
        val userId = SecurePrefs.getActiveUserId(this)
        db.contactDao().getContactByEmailLiveData(userId, contactEmail).observe(this) { contact ->
            currentContact = contact
            contact?.let {
                if (binding.etContactName.text.isNullOrEmpty()) {
                    binding.etContactName.setText(it.name)
                }
                binding.tvContactEmail.text = it.email
                binding.etSecondaryEmail.setText(it.secondaryEmail)
                binding.swExclusivePrimaryEmail.isChecked = it.exclusivePrimaryEmail
                
                binding.ivEncryptionStatus.visibility = if (!it.publicKey.isNullOrEmpty()) View.VISIBLE else View.GONE
                
                if (it.avatarPath != null && selectedAvatarUri == null) {
                    val file = File(it.avatarPath)
                    if (file.exists()) {
                        Glide.with(this@EditContactActivity)
                            .load(file)
                            .circleCrop()
                            .placeholder(R.drawable.ic_contact_placeholder)
                            .into(binding.ivContactAvatar)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.ivContactAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnPasteKey.setOnClickListener {
            pastePublicKey()
        }

        binding.btnExchangeKeys.setOnClickListener {
            onExchangeKeysClicked()
        }

        binding.btnSave.setOnClickListener {
            saveContact()
        }

        binding.btnRequestInfo.setOnClickListener {
            requestContactInfo()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun onExchangeKeysClicked() {
        val secondary = currentContact?.secondaryEmail
        if (secondary.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.exchange_keys)
                .setMessage(R.string.exchange_keys_warning)
                .setPositiveButton(R.string.yes) { _, _ -> startKeyExchange() }
                .setNegativeButton(R.string.no, null)
                .show()
        } else {
            startKeyExchange()
        }
    }

    private fun startKeyExchange() {
        lifecycleScope.launch {
            binding.btnExchangeKeys.isEnabled = false
            Toast.makeText(this@EditContactActivity, R.string.sending, Toast.LENGTH_SHORT).show()
            emailService.exchangePgpKeys(contactEmail)
            EmailCheckService.startIntensive(this@EditContactActivity)
            binding.root.postDelayed({ binding.btnExchangeKeys.isEnabled = true }, 5000)
        }
    }

    private fun pastePublicKey() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            var key = clip.getItemAt(0).text.toString().trim()

            try {
                if (!isValidPgpKey(key)) {
                    key = Base26Encoder.decodeText(key, this@EditContactActivity);
                }

                if (isValidPgpKey(key)) {
                    lifecycleScope.launch {
                        val userId = SecurePrefs.getActiveUserId(this@EditContactActivity)
                        val contact = currentContact?.copy(publicKey = key)
                            ?: Contact(
                                userId = userId,
                                email = contactEmail,
                                name = binding.etContactName.text.toString(),
                                publicKey = key
                            )

                        db.contactDao().insert(contact)
                        Toast.makeText(
                            this@EditContactActivity,
                            R.string.public_key_pasted,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(this, R.string.invalid_pgp_key, Toast.LENGTH_SHORT).show()
                }
            }
            catch(e: Exception) {
                Toast.makeText(this, R.string.invalid_pgp_key, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isValidPgpKey(key: String): Boolean {
        return key.contains("BEGIN PGP PUBLIC KEY BLOCK") && key.contains("END PGP PUBLIC KEY BLOCK")
    }

    private fun saveContact() {
        val newName = binding.etContactName.text.toString().trim()
        val secondaryEmail = binding.etSecondaryEmail.text.toString().trim()
        val exclusivePrimary = binding.swExclusivePrimaryEmail.isChecked

        if (newName.isEmpty()) {
            Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val userId = SecurePrefs.getActiveUserId(this@EditContactActivity)
            if (userId == -1L) return@launch

            var avatarPath = currentContact?.avatarPath
            selectedAvatarUri?.let { uri ->
                val fileName = "avatar_${contactEmail.hashCode()}.jpg"
                val dir = File(filesDir, "avatars").apply { mkdirs() }
                val targetFile = File(dir, fileName)
                if (ImageUtils.compressImage(this@EditContactActivity, uri, targetFile)) {
                    avatarPath = targetFile.absolutePath
                }
            }

            val updatedContact = currentContact?.copy(
                name = newName,
                avatarPath = avatarPath,
                secondaryEmail = secondaryEmail.ifEmpty { null },
                exclusivePrimaryEmail = exclusivePrimary
            ) ?: Contact(
                userId = userId,
                email = contactEmail,
                name = newName,
                avatarPath = avatarPath,
                secondaryEmail = secondaryEmail.ifEmpty { null },
                exclusivePrimaryEmail = exclusivePrimary
            )

            db.contactDao().insert(updatedContact)
            Toast.makeText(this@EditContactActivity, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun requestContactInfo() {
        lifecycleScope.launch {
            binding.btnRequestInfo.isEnabled = false
            Toast.makeText(this@EditContactActivity, R.string.requesting_avatar, Toast.LENGTH_SHORT).show()
            emailService.requestRemoteAvatar(contactEmail)
            EmailCheckService.startIntensive(this@EditContactActivity)
            binding.root.postDelayed({ binding.btnRequestInfo.isEnabled = true }, 5000)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
