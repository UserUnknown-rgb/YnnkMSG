package com.Ynnk.YnnkMsg.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.User
import com.Ynnk.YnnkMsg.databinding.ActivityEditProfileBinding
import com.Ynnk.YnnkMsg.email.EmailProviders
import com.Ynnk.YnnkMsg.util.ImageUtils
import com.Ynnk.YnnkMsg.util.PgpUtils
import com.Ynnk.YnnkMsg.util.SecurePrefs
import com.Ynnk.YnnkMsg.util.Base26Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Session

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var activeUser: User? = null
    private var selectedAvatarUri: Uri? = null
    private var isSecondaryExpanded = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedAvatarUri = it
            Glide.with(this).load(it).circleCrop().into(binding.ivAvatar)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.edit_account)

        loadUserData()
        setupListeners()
    }

    private fun loadUserData() {
        val userId = SecurePrefs.getActiveUserId(this)
        if (userId == -1L) {
            finish()
            return
        }

        lifecycleScope.launch {
            activeUser = db.userDao().getUserById(userId)
            val isVulnerable = activeUser?.vulnerableMode == true

            activeUser?.let { user ->
                binding.etPrimaryEmail.setText(user.primaryEmail)
                binding.etName.setText(user.name)
                binding.swExclusivePrimaryEmail.isChecked = user.exclusivePrimaryEmail

                user.avatarPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        Glide.with(this@EditProfileActivity)
                            .load(file)
                            .circleCrop()
                            .into(binding.ivAvatar)
                    }
                }
            }
            
            if (isVulnerable) {
                binding.etSecondaryEmail.setText("")
                binding.etSecondaryPassword.setText("")
                binding.etSecondaryEmail.isEnabled = false
                binding.etSecondaryPassword.isEnabled = false
                binding.btnCopyMyKey.visibility = View.GONE
            } else {
                // Load secondary credentials from SecurePrefs
                binding.etSecondaryEmail.setText(SecurePrefs.getSecondaryEmail(this@EditProfileActivity))
                binding.etSecondaryPassword.setText(SecurePrefs.getSecondaryPassword(this@EditProfileActivity))
                binding.btnCopyMyKey.visibility = View.VISIBLE
            }

            binding.etSecImapHost.setText(SecurePrefs.getSecondaryImapHost(this@EditProfileActivity))
            binding.etSecImapPort.setText(SecurePrefs.getSecondaryImapPort(this@EditProfileActivity).toString())
            binding.cbSecImapSsl.isChecked = SecurePrefs.getSecondaryImapSsl(this@EditProfileActivity)
            binding.etSecSmtpHost.setText(SecurePrefs.getSecondarySmtpHost(this@EditProfileActivity))
            binding.etSecSmtpPort.setText(SecurePrefs.getSecondarySmtpPort(this@EditProfileActivity).toString())
            binding.cbSecSmtpSsl.isChecked = SecurePrefs.getSecondarySmtpSsl(this@EditProfileActivity)
            binding.cbSecSmtpStarttls.isChecked = SecurePrefs.getSecondarySmtpStartTls(this@EditProfileActivity)
        }
    }

    private fun setupListeners() {
        binding.btnChangeAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
            saveProfile()
        }

        binding.btnCopyMyKey.setOnClickListener {
            copyMyPublicKey()
        }

        binding.btnRecreateKeys.setOnClickListener {
            showRecreateKeysConfirm()
        }

        binding.btnDeleteKeys.setOnClickListener {
            deleteKeys()
        }

        binding.llSecondaryEmailHeader.setOnClickListener {
            isSecondaryExpanded = !isSecondaryExpanded
            binding.llSecondarySettings.visibility = if (isSecondaryExpanded) View.VISIBLE else View.GONE
            binding.tvSecondaryEmailHeader.setText(
                if (isSecondaryExpanded) applicationContext.getString(R.string.hide_secondary_email_header) else applicationContext.getString(R.string.secondary_email_header)
            )
        }

        binding.etSecondaryEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoFillSecondaryProviderSettings()
        }

        binding.btnCheckEmail.setOnClickListener {
            checkSecondaryEmail()
        }
    }

    private fun autoFillSecondaryProviderSettings() {
        val email = binding.etSecondaryEmail.text.toString().trim()
        if (email.isEmpty()) return

        val config = EmailProviders.getConfigForEmail(email)
        if (config != null) {
            binding.etSecImapHost.setText(config.imapHost)
            binding.etSecImapPort.setText(config.imapPort.toString())
            binding.etSecSmtpHost.setText(config.smtpHost)
            binding.etSecSmtpPort.setText(config.smtpPort.toString())
            binding.cbSecImapSsl.isChecked = config.imapSsl
            binding.cbSecSmtpSsl.isChecked = config.smtpSsl
            binding.cbSecSmtpStarttls.isChecked = config.smtpStartTls
        }
    }

    private fun checkSecondaryEmail() {
        val email = binding.etSecondaryEmail.text.toString().trim()
        val password = binding.etSecondaryPassword.text.toString()
        val imapHost = binding.etSecImapHost.text.toString().trim()
        val imapPort = binding.etSecImapPort.text.toString().trim().toIntOrNull() ?: 993
        val imapSsl = binding.cbSecImapSsl.isChecked

        if (email.isEmpty() || password.isEmpty() || imapHost.isEmpty()) {
            Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCheckEmail.isEnabled = false
        Toast.makeText(this, R.string.checking_connection, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = testConnection(email, password, imapHost, imapPort, imapSsl)
            withContext(Dispatchers.Main) {
                binding.btnCheckEmail.isEnabled = true
                if (success) {
                    Toast.makeText(this@EditProfileActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditProfileActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun testConnection(
        email: String, password: String,
        imapHost: String, imapPort: Int, ssl: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val protocol = if (ssl) "imaps" else "imap"
            val props = Properties().apply {
                put("mail.${protocol}.host", imapHost)
                put("mail.${protocol}.port", imapPort.toString())
                put("mail.${protocol}.connectiontimeout", "10000")
                put("mail.${protocol}.timeout", "10000")
                if (ssl) put("mail.${protocol}.ssl.enable", "true")
            }
            val session = Session.getInstance(props)
            val store = session.getStore(protocol)
            store.connect(imapHost, imapPort, email, password)
            store.close()
            true
        } catch (e: AuthenticationFailedException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun copyMyPublicKey() {
        val publicKey = SecurePrefs.getPgpPublicKey(this)
        if (publicKey.isNullOrEmpty()) {
            Toast.makeText(this, R.string.pgp_unavailable_warning, Toast.LENGTH_SHORT).show()
            return
        }

        val encodedPublicKey = "{" + Base26Encoder.encodeText(publicKey, false) + "}";

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("YnnkMsg Public Key", encodedPublicKey)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.public_key_copied, Toast.LENGTH_SHORT).show()
    }

    private fun saveProfile() {
        val user = activeUser ?: return
        val isVulnerable = user.vulnerableMode
        val newName = binding.etName.text.toString().trim()
        val isExclusive = binding.swExclusivePrimaryEmail.isChecked

        lifecycleScope.launch {
            var avatarPath = user.avatarPath
            selectedAvatarUri?.let { uri ->
                val fileName = "my_avatar_${System.currentTimeMillis()}.jpg"
                val dir = File(filesDir, "avatars").apply { mkdirs() }
                val targetFile = File(dir, fileName)
                if (ImageUtils.compressImage(this@EditProfileActivity, uri, targetFile)) {
                    avatarPath = targetFile.absolutePath
                }
            }

            val updatedUser = user.copy(
                name = if (newName.isEmpty()) null else newName,
                avatarPath = avatarPath,
                exclusivePrimaryEmail = isExclusive
            )

            db.userDao().upsert(updatedUser)
            
            if (!isVulnerable) {
                // Save secondary credentials to SecurePrefs
                val secEmail = binding.etSecondaryEmail.text.toString().trim()
                val secPassword = binding.etSecondaryPassword.text.toString().trim()
                val secImapHost = binding.etSecImapHost.text.toString().trim()
                val secImapPort = binding.etSecImapPort.text.toString().trim().toIntOrNull() ?: 993
                val secSmtpHost = binding.etSecSmtpHost.text.toString().trim()
                val secSmtpPort = binding.etSecSmtpPort.text.toString().trim().toIntOrNull() ?: 587
                
                SecurePrefs.saveSecondaryCredentials(
                    this@EditProfileActivity,
                    if (secEmail.isEmpty()) null else secEmail,
                    if (secPassword.isEmpty()) null else secPassword,
                    if (secImapHost.isEmpty()) null else secImapHost,
                    secImapPort,
                    if (secSmtpHost.isEmpty()) null else secSmtpHost,
                    secSmtpPort,
                    binding.cbSecImapSsl.isChecked,
                    binding.cbSecSmtpSsl.isChecked,
                    binding.cbSecSmtpStarttls.isChecked
                )
            }

            Toast.makeText(this@EditProfileActivity, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showRecreateKeysConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recreate_keys)
            .setMessage(R.string.recreate_keys_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                recreateKeys()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun recreateKeys() {
        val email = activeUser?.primaryEmail ?: return
        lifecycleScope.launch {
            Toast.makeText(this@EditProfileActivity, R.string.generating_keys, Toast.LENGTH_SHORT).show()
            val keys = PgpUtils.generateKeyPair(email)
            if (keys != null) {
                SecurePrefs.savePgpKeys(this@EditProfileActivity, keys.publicKey, keys.privateKey)
                Toast.makeText(this@EditProfileActivity, R.string.keys_recreated, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@EditProfileActivity, R.string.error_pgp_generation, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteKeys() {
        SecurePrefs.savePgpKeys(this, "", "")
        Toast.makeText(this, R.string.keys_deleted, Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
