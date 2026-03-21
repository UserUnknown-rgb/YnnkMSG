package com.Ynnk.YnnkMsg.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.databinding.ActivityContactsBinding
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.ui.chat.ChatActivity
import com.Ynnk.YnnkMsg.ui.settings.SettingsActivity
import com.Ynnk.YnnkMsg.util.AppPrefs
import com.Ynnk.YnnkMsg.util.EventBus
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var adapter: ContactsAdapter
    private val db by lazy { AppDatabase.getInstance(this) }
    private var activeUserId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        activeUserId = SecurePrefs.getActiveUserId(this)
        if (activeUserId == -1L) {
            finish()
            return
        }

        setupRecyclerView()
        setupFab()
        loadAvatar()
        setupAvatarMenu()
        setupMessagingRequests()
        startEmailService()
        setupEventBus()
        
        // Custom settings icon click listener
        binding.ivSettingsCustom.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.ivEvents.setOnClickListener {
            startActivity(Intent(this, AppEventsActivity::class.java))
        }
    }

    private fun setupEventBus() {
        EventBus.events
            .onEach { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        loadAvatar() // Refresh my avatar if changed
        updateMessagingRequests()
    }

    private fun setupMessagingRequests() {
        binding.layoutMessagingRequests.setOnClickListener {
            startActivity(Intent(this, MessagingRequestsActivity::class.java))
        }
        updateMessagingRequests()
    }

    private fun updateMessagingRequests() {
        val requests = AppPrefs.getMessagingRequests(this)
        if (requests.isNotEmpty()) {
            binding.layoutMessagingRequests.visibility = View.VISIBLE
            binding.dividerRequests.visibility = View.VISIBLE
            binding.tvRequestsCount.text = requests.size.toString()
        } else {
            binding.layoutMessagingRequests.visibility = View.GONE
            binding.dividerRequests.visibility = View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_contacts, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupAvatarMenu() {
        binding.ivMyAvatar.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, R.string.edit_account)
            popup.menu.add(0, 2, 1, R.string.logout)
            
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        startActivity(Intent(this, EditProfileActivity::class.java))
                        true
                    }
                    2 -> {
                        showLogoutDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                EmailCheckService.stopService(this)
                SecurePrefs.logout(this)
                finish()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter(
            this,
            onContactClick = { contact ->
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_CONTACT_EMAIL, contact.email)
                    putExtra(ChatActivity.EXTRA_CONTACT_NAME, contact.name)
                }
                startActivity(intent)
            },
            onContactLongClick = { contact ->
                showContactOptions(contact)
            }
        )
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter

        db.contactDao().getAllContacts(activeUserId).observe(this) { contacts ->
            adapter.submitList(contacts)
            binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            showAddContactDialog()
        }
    }

    private fun showAddContactDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etEmail = view.findViewById<android.widget.EditText>(R.id.et_contact_email)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_contact_name)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_contact)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val email = etEmail.text.toString().trim().lowercase()
                val name = etName.text.toString().trim()
                if (email.isEmpty() || name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, getString(R.string.invalid_email), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    db.contactDao().insert(Contact(userId = activeUserId, email = email, name = name))
                    db.appEventDao().logEvent("Added new contact: $email")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showContactOptions(contact: Contact) {
        val options = arrayOf(
            getString(R.string.open_chat),
            getString(R.string.edit_contact),
            getString(R.string.delete_contact)
        )
        AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, ChatActivity::class.java).apply {
                            putExtra(ChatActivity.EXTRA_CONTACT_EMAIL, contact.email)
                            putExtra(ChatActivity.EXTRA_CONTACT_NAME, contact.name)
                        }
                        startActivity(intent)
                    }
                    1 -> {
                        val intent = Intent(this, EditContactActivity::class.java).apply {
                            putExtra(EditContactActivity.EXTRA_CONTACT_EMAIL, contact.email)
                        }
                        startActivity(intent)
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.delete_contact_confirm, contact.name))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                lifecycleScope.launch {
                                    db.contactDao().delete(contact)
                                    db.appEventDao().logEvent("Deleted contact: ${contact.email}")
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun loadAvatar() {
        lifecycleScope.launch {
            val user = db.userDao().getUserById(activeUserId)
            val avatarPath = user?.avatarPath
            withContext(Dispatchers.Main) {
                if (avatarPath != null) {
                    val file = File(avatarPath)
                    if (file.exists()) {
                        com.bumptech.glide.Glide.with(this@ContactsActivity)
                            .load(file)
                            .circleCrop()
                            .into(binding.ivMyAvatar)
                    }
                }
            }
        }
    }

    private fun startEmailService() {
        EmailCheckService.startIntensive(this)
    }
}
