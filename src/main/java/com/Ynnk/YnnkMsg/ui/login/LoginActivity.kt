package com.Ynnk.YnnkMsg.ui.login

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.User
import com.Ynnk.YnnkMsg.databinding.ActivityLoginBinding
import com.Ynnk.YnnkMsg.email.EmailProviders
import com.Ynnk.YnnkMsg.ui.contacts.ContactsActivity
import com.Ynnk.YnnkMsg.ui.settings.PinActivity
import com.Ynnk.YnnkMsg.util.ImageUtils
import com.Ynnk.YnnkMsg.util.PgpUtils
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.mail.*
import java.util.Properties

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var selectedAvatarUri: Uri? = null
    private var isManualSettings = false
    private var isInstructionsVisible = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedAvatarUri = it
            binding.ivAvatar.setImageURI(it)
            binding.tvAvatarHint.visibility = View.GONE
        }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // If already logged in, skip login
        if (SecurePrefs.isLoggedIn(this)) {
            if (SecurePrefs.isPinEnabled(this)) {
                val intent = Intent(this, PinActivity::class.java).apply {
                    putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
                }
                startActivity(intent)
            } else {
                startActivity(Intent(this, ContactsActivity::class.java))
            }
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestStoragePermissions()
        setupViews()
    }

    private fun requestStoragePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    private fun setupViews() {
        binding.ivAvatar.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSelectAvatar.setOnClickListener { pickImage.launch("image/*") }

        binding.etEmail.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoFillProviderSettings()
        }

        binding.tvManualSettings.setOnClickListener {
            isManualSettings = !isManualSettings
            binding.layoutManualSettings.visibility = if (isManualSettings) View.VISIBLE else View.GONE
            binding.tvManualSettings.text = if (isManualSettings)
                getString(R.string.hide_manual_settings)
            else
                getString(R.string.manual_settings)
        }

        binding.tvLoginInstructionsToggle.setOnClickListener {
            isInstructionsVisible = !isInstructionsVisible
            binding.tvLoginInstructionsText.visibility = if (isInstructionsVisible) View.VISIBLE else View.GONE
            binding.tvLoginInstructionsToggle.text = if (isInstructionsVisible)
                getString(R.string.login_instructions_toggle_hide)
            else
                getString(R.string.login_instructions_toggle)
        }

        binding.btnLogin.setOnClickListener { doLogin() }
    }

    private fun autoFillProviderSettings() {
        val email = binding.etEmail.text.toString().trim()
        if (email.isEmpty()) return

        val config = EmailProviders.getConfigForEmail(email)
        if (config != null) {
            binding.etImapHost.setText(config.imapHost)
            binding.etImapPort.setText(config.imapPort.toString())
            binding.etSmtpHost.setText(config.smtpHost)
            binding.etSmtpPort.setText(config.smtpPort.toString())
            binding.switchImapSsl.isChecked = config.imapSsl
            binding.switchSmtpSsl.isChecked = config.smtpSsl
            binding.switchSmtpStartTls.isChecked = config.smtpStartTls
            binding.tvProviderDetected.text = getString(R.string.provider_detected)
            binding.tvProviderDetected.visibility = View.VISIBLE
        } else {
            binding.tvProviderDetected.visibility = View.GONE
            isManualSettings = true
            binding.layoutManualSettings.visibility = View.VISIBLE
            binding.tvManualSettings.text = getString(R.string.hide_manual_settings)
        }
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (email.isEmpty()) { binding.etEmail.error = getString(R.string.required); return }
        if (password.isEmpty()) { binding.etPassword.error = getString(R.string.required); return }
        if (selectedAvatarUri == null) { Toast.makeText(this, getString(R.string.select_avatar), Toast.LENGTH_SHORT).show(); return }

        val config = EmailProviders.getConfigForEmail(email)
        val imapHost = binding.etImapHost.text.toString().ifEmpty { config?.imapHost ?: "" }
        val imapPort = binding.etImapPort.text.toString().toIntOrNull() ?: (config?.imapPort ?: 993)
        val smtpHost = binding.etSmtpHost.text.toString().ifEmpty { config?.smtpHost ?: "" }
        val smtpPort = binding.etSmtpPort.text.toString().toIntOrNull() ?: (config?.smtpPort ?: 587)
        val imapSsl = binding.switchImapSsl.isChecked
        val smtpSsl = binding.switchSmtpSsl.isChecked
        val smtpStartTls = binding.switchSmtpStartTls.isChecked

        if (imapHost.isEmpty() || smtpHost.isEmpty()) {
            Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        lifecycleScope.launch {
            val success = testConnection(email, password, imapHost, imapPort, imapSsl)
            if (!success) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val avatarPath = ImageUtils.saveAvatarToInternalStorage(this@LoginActivity, selectedAvatarUri!!)
            if (avatarPath == null) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, getString(R.string.avatar_save_error), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Database User management
            val db = AppDatabase.getInstance(this@LoginActivity)
            val userDao = db.userDao()
            var user = userDao.getUserByEmail(email)
            if (user == null) {
                user = User(primaryEmail = email, avatarPath = avatarPath)
                val newId = userDao.upsert(user)
                user = user.copy(id = newId)
            } else {
                user = user.copy(avatarPath = avatarPath)
                userDao.upsert(user)
            }

            // Step 2: Generate PGP keys if they don't exist
            if (SecurePrefs.getPgpPublicKey(this@LoginActivity) == null) {
                val keys = PgpUtils.generateKeyPair(email)
                if (keys != null) {
                    SecurePrefs.savePgpKeys(this@LoginActivity, keys.publicKey, keys.privateKey)
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, getString(R.string.error_pgp_generation), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
            }

            // Check if PIN is enabled and it's a pre-saved email
            val savedEmail = SecurePrefs.getEmail(this@LoginActivity)
            if (SecurePrefs.isPinEnabled(this@LoginActivity) && email.lowercase() == savedEmail?.lowercase()) {
                SecurePrefs.saveCredentials(
                    this@LoginActivity, user.id, email, password,
                    imapHost, imapPort, smtpHost, smtpPort, imapSsl, smtpSsl, smtpStartTls
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@LoginActivity, PinActivity::class.java).apply {
                        putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
                        putExtra(PinActivity.EXTRA_EMAIL, email)
                    }
                    startActivity(intent)
                    finish()
                }
            } else {
                SecurePrefs.saveCredentials(
                    this@LoginActivity, user.id, email, password,
                    imapHost, imapPort, smtpHost, smtpPort, imapSsl, smtpSsl, smtpStartTls
                )
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@LoginActivity, ContactsActivity::class.java))
                    finish()
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
}
