package com.Ynnk.YnnkMsg.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.databinding.ActivitySettingsBinding
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.ui.login.LoginActivity
import com.Ynnk.YnnkMsg.util.AppPrefs
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private val pickBackground = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { saveBackground(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        loadSettings()
        setupListeners()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadSettings() {
        binding.switchAutoDelete.isChecked = AppPrefs.isAutoDeleteEnabled(this)
        binding.switchBase26.isChecked = AppPrefs.isBase26Enabled(this)
        binding.switchAutoAddContacts.isChecked = AppPrefs.isAutoAddContactsEnabled(this)
        binding.switchRequestConfirmation.isChecked = AppPrefs.isConfirmationRequestEnabled(this)
        
        updatePgpSwitchState()

        val userId = SecurePrefs.getActiveUserId(this)
        lifecycleScope.launch {
            val user = db.userDao().getUserById(userId)
            val isVulnerable = user?.vulnerableMode == true
            
            withContext(Dispatchers.Main) {
                if (isVulnerable) {
                    binding.switchPin.visibility = View.GONE
                    binding.btnChangePin.visibility = View.GONE
                } else {
                    binding.switchPin.isChecked = SecurePrefs.isPinEnabled(this@SettingsActivity)
                    binding.btnChangePin.visibility = if (SecurePrefs.isPinEnabled(this@SettingsActivity)) View.VISIBLE else View.GONE
                }
            }
        }

        binding.seekBarFontSize.progress = AppPrefs.getFontSize(this) - 12
        binding.tvFontSize.text = getString(R.string.font_size_label, AppPrefs.getFontSize(this))

        updateBackgroundPreview()
    }

    private fun updateBackgroundPreview() {
        val bgPath = AppPrefs.getChatBackground(this)
        if (bgPath != null) {
            Glide.with(this).load(File(bgPath)).into(binding.ivBackgroundPreview)
        } else {
            Glide.with(this).load(R.drawable.chat_background_default).into(binding.ivBackgroundPreview)
        }
    }

    private fun updatePgpSwitchState() {
        val publicKey = SecurePrefs.getPgpPublicKey(this)
        val privateKey = SecurePrefs.getPgpPrivateKey(this)
        val hasKeys = !publicKey.isNullOrEmpty() && !privateKey.isNullOrEmpty()
        val base26Enabled = AppPrefs.isBase26Enabled(this)

        if (hasKeys && base26Enabled) {
            binding.switchPgp.isEnabled = true
            binding.switchPgp.isChecked = AppPrefs.isPgpEnabled(this)
            binding.tvPgpWarning.visibility = View.GONE
        } else {
            binding.switchPgp.isEnabled = false
            binding.switchPgp.isChecked = false
            binding.tvPgpWarning.visibility = View.VISIBLE
            AppPrefs.setPgp(this, false)
            
            if (!hasKeys) {
                binding.tvPgpWarning.text = getString(R.string.pgp_unavailable_warning)
            } else if (!base26Enabled) {
                binding.tvPgpWarning.text = getString(R.string.pgp_requires_base26)
            }
        }
    }

    private fun setupListeners() {
        binding.switchAutoDelete.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setAutoDelete(this, checked)
        }
        binding.switchBase26.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setBase26(this, checked)
            if (!checked) {
                AppPrefs.setPgp(this, false)
                binding.switchPgp.isChecked = false
            }
            updatePgpSwitchState()
        }
        binding.switchAutoAddContacts.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setAutoAddContacts(this, checked)
        }
        binding.switchRequestConfirmation.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setConfirmationRequest(this, checked)
        }
        binding.switchPgp.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setPgp(this, checked)
        }

        binding.switchPin.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (SecurePrefs.getPinCode(this) == null) {
                    val intent = Intent(this, PinActivity::class.java).apply {
                        putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CREATE)
                    }
                    startActivity(intent)
                } else {
                    SecurePrefs.setPinEnabled(this, true)
                    binding.btnChangePin.visibility = View.VISIBLE
                }
            } else {
                SecurePrefs.setPinEnabled(this, false)
                binding.btnChangePin.visibility = View.GONE
                lifecycleScope.launch {
                    val userId = SecurePrefs.getActiveUserId(this@SettingsActivity)
                    db.userDao().setVulnerableMode(userId, false)
                }
            }
        }

        binding.btnChangePin.setOnClickListener {
            val intent = Intent(this, PinActivity::class.java).apply {
                putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CREATE)
            }
            startActivity(intent)
        }

        binding.btnManageIgnored.setOnClickListener {
            startActivity(Intent(this, IgnoredEmailsActivity::class.java))
        }
        binding.btnChooseBackground.setOnClickListener {
            pickBackground.launch("image/*")
        }
        binding.btnClearBackground.setOnClickListener {
            AppPrefs.setChatBackground(this, null)
            updateBackgroundPreview()
        }
        binding.seekBarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress + 12
                binding.tvFontSize.text = getString(R.string.font_size_label, size)
                AppPrefs.setFontSize(this@SettingsActivity, size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        binding.btnClearCache.setOnClickListener {
            showCleanupOptions()
        }
    }

    override fun onResume() {
        super.onResume()
        // Update UI in case PIN was created/cancelled
        val userId = SecurePrefs.getActiveUserId(this)
        lifecycleScope.launch {
            val user = db.userDao().getUserById(userId)
            val isVulnerable = user?.vulnerableMode == true
            withContext(Dispatchers.Main) {
                if (!isVulnerable) {
                    binding.switchPin.isChecked = SecurePrefs.isPinEnabled(this@SettingsActivity)
                    binding.btnChangePin.visibility = if (SecurePrefs.isPinEnabled(this@SettingsActivity)) View.VISIBLE else View.GONE
                } else {
                    binding.switchPin.visibility = View.GONE
                    binding.btnChangePin.visibility = View.GONE
                }
            }
        }
    }

    private fun showCleanupOptions() {
        val options = arrayOf(
            getString(R.string.wipe_all_cache),
            getString(R.string.delete_user_data)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.cleanup_options_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showWipeAllConfirm()
                    1 -> showDeleteUserDataConfirm()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWipeAllConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wipe_all_cache)
            .setMessage(R.string.clear_cache_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                wipeAllData()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showDeleteUserDataConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_user_data)
            .setMessage(R.string.delete_user_data_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                deleteActiveUserData()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun wipeAllData() {
        lifecycleScope.launch {
            EmailCheckService.stopService(this@SettingsActivity)

            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@SettingsActivity).clearAllTables()
                SecurePrefs.logout(this@SettingsActivity)
                SecurePrefs.setPinCode(this@SettingsActivity, null)
                SecurePrefs.setPinEnabled(this@SettingsActivity, false)
                
                val sharedPrefs = getSharedPreferences("ynnkmsg_prefs", MODE_PRIVATE)
                sharedPrefs.edit().clear().apply()

                filesDir.deleteRecursively()
                cacheDir.deleteRecursively()
            }

            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun deleteActiveUserData() {
        lifecycleScope.launch {
            val userId = SecurePrefs.getActiveUserId(this@SettingsActivity)
            if (userId == -1L) return@launch

            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@SettingsActivity)
                db.messageDao().deleteAllForUser(userId)
                db.contactDao().deleteAllForUser(userId)
            }
            
            Toast.makeText(this@SettingsActivity, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBackground(uri: Uri) {
        val bgDir = File(filesDir, "backgrounds")
        bgDir.mkdirs()
        val bgFile = File(bgDir, "chat_bg.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            bgFile.outputStream().use { out -> input.copyTo(out) }
        }
        AppPrefs.setChatBackground(this, bgFile.absolutePath)
        updateBackgroundPreview()
    }
}
