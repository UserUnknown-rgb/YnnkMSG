package com.Ynnk.YnnkMsg.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.databinding.ActivityPinBinding
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.ui.contacts.ContactsActivity
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

class PinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinBinding
    private var mode = MODE_VERIFY
    private var firstPin: String? = null

    companion object {
        const val MODE_VERIFY = 0
        const val MODE_CREATE = 1
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_EMAIL = "extra_email"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY)

        setupViews()
    }

    private fun setupViews() {
        if (mode == MODE_CREATE) {
            binding.tvTitle.text = getString(R.string.create_pin)
        } else {
            binding.tvTitle.text = getString(R.string.enter_pin)
        }

        binding.btnConfirm.setOnClickListener {
            val pin = binding.etPin.text.toString()
            if (pin.length < 4) return@setOnClickListener

            if (mode == MODE_CREATE) {
                handleCreateMode(pin)
            } else {
                handleVerifyMode(pin)
            }
        }
    }

    private fun handleCreateMode(pin: String) {
        if (firstPin == null) {
            firstPin = pin
            binding.etPin.text?.clear()
            binding.tvTitle.text = getString(R.string.confirm_pin)
        } else {
            if (pin == firstPin) {
                SecurePrefs.setPinCode(this, pin)
                SecurePrefs.setPinEnabled(this, true)
                Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, R.string.pins_dont_match, Toast.LENGTH_SHORT).show()
                firstPin = null
                binding.etPin.text?.clear()
                binding.tvTitle.text = getString(R.string.create_pin)
            }
        }
    }

    private fun handleVerifyMode(inputPin: String) {
        val savedPin = SecurePrefs.getPinCode(this) ?: ""
        val userId = SecurePrefs.getActiveUserId(this)

        val inputInt = inputPin.toIntOrNull() ?: -1
        val savedInt = savedPin.toIntOrNull() ?: -2

        if (inputPin == savedPin) {
            lifecycleScope.launch {
                AppDatabase.getInstance(this@PinActivity).userDao().setVulnerableMode(userId, false)
                onSuccess()
            }
        } else {
            when (inputInt) {
                (savedInt + 2) % 10000 -> {
                    lifecycleScope.launch {
                        AppDatabase.getInstance(this@PinActivity).userDao().setVulnerableMode(userId, true)
                        onSuccess()
                    }
                }
                (savedInt + 4) % 10000 -> {
                    lifecycleScope.launch {
                        wipeEverything()
                    }
                }
                else -> {
                    Toast.makeText(this, R.string.pin_error, Toast.LENGTH_SHORT).show()
                    binding.etPin.text?.clear()
                }
            }
        }
    }

    private fun onSuccess() {
        startActivity(Intent(this, ContactsActivity::class.java))
        finish()
    }

    private suspend fun wipeEverything(): Nothing = withContext(Dispatchers.IO) {
        // 1. Stop background service
        EmailCheckService.stopService(this@PinActivity)
        EmailCheckService.stopIntensive()

        // 2. Give the service a moment to close connections and exit the loop
        delay(500)

        // 3. Close the database connection to release file locks
        AppDatabase.destroyInstance()

        // 4. Hard delete the database file and all other app data
        val databaseFile = getDatabasePath("ynnkmsg_database")
        if (databaseFile.exists()) databaseFile.delete()

        // Also delete journal/wal files if they exist
        File(databaseFile.path + "-journal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-wal").delete()

        // 5. Clear preferences
        SecurePrefs.logout(this@PinActivity)
        SecurePrefs.setPinCode(this@PinActivity, null)
        SecurePrefs.setPinEnabled(this@PinActivity, false)
        SecurePrefs.clearAll(this@PinActivity)

        // 6. Delete all files (avatars, attachments, etc.)
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()

        // 7. Exit the app
        withContext(Dispatchers.Main) {
            exitProcess(0)
        }
    }
}
