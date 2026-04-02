package com.Ynnk.YnnkMsg.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.YnnkMsgApplication
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Message
import com.Ynnk.YnnkMsg.databinding.ActivityChatBinding
import com.Ynnk.YnnkMsg.email.EmailService
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.ui.contacts.ContactsAdapter
import com.Ynnk.YnnkMsg.ui.contacts.EditContactActivity
import com.Ynnk.YnnkMsg.util.AppPrefs
import com.Ynnk.YnnkMsg.util.ImageUtils
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONTACT_EMAIL = "contact_email"
        const val EXTRA_CONTACT_NAME = "contact_name"
        private const val PAGE_SIZE = 50
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var contactEmail: String
    private lateinit var contactName: String
    private val db by lazy { AppDatabase.getInstance(this) }
    private val emailService by lazy { EmailService(this) }
    private val pendingAttachments = mutableListOf<File>()
    private var cameraPhotoFile: File? = null
    private var activeUserId: Long = -1L

    private var currentLimit = PAGE_SIZE
    private var isFirstLoad = true
    private var draftRestored = false
    private var localDraft: String = ""

    private var isSearchMode = false
    private var currentMessagesLiveData: LiveData<List<Message>>? = null
    private val messageObserver = Observer<List<Message>> { messages ->
        val oldSize = adapter.itemCount
        
        if (!isSearchMode && !isFirstLoad && messages.size > oldSize) {
            EmailCheckService.notifyUserActivity(this)
        }

        val layoutManager = binding.rvMessages.layoutManager as LinearLayoutManager
        val isAtBottom = layoutManager.findLastVisibleItemPosition() >= oldSize - 2

        adapter.submitList(messages) {
            if (isFirstLoad && messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
                isFirstLoad = false
            } else if (!isFirstLoad && messages.size > oldSize) {
                if (isAtBottom) {
                    binding.rvMessages.post {
                        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            } else if (isSearchMode && messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { addFileAttachment(it) }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { addImageAttachment(it) }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraPhotoFile?.let { addCompressedImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeUserId = SecurePrefs.getActiveUserId(this)
        if (activeUserId == -1L) {
            finish()
            return
        }

        contactEmail = intent.getStringExtra(EXTRA_CONTACT_EMAIL) ?: run { finish(); return }
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: contactEmail
        
        YnnkMsgApplication.activeChatEmail = contactEmail

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvToolbarName.text = contactName
        binding.layoutToolbarTitle.setOnClickListener {
            val intent = Intent(this, EditContactActivity::class.java).apply {
                putExtra(EditContactActivity.EXTRA_CONTACT_EMAIL, contactEmail)
            }
            startActivity(intent)
        }

        applyChatBackground()
        applyFontSize()
        setupRecyclerView()
        setupInputArea()
        loadMessages()
        observeContactInfo()
        markMessagesAsRead()
    }

    override fun onResume() {
        super.onResume()
        YnnkMsgApplication.activeChatEmail = contactEmail
        EmailCheckService.notifyUserActivity(this)

        // Restore draft when opening activity
        if (!draftRestored && localDraft.isNotEmpty() && binding.etMessage.text.isNullOrEmpty()) {
            binding.etMessage.setText(localDraft)
            binding.etMessage.setSelection(localDraft.length)
            draftRestored = true
        }
    }

    override fun onPause() {
        super.onPause()
        YnnkMsgApplication.activeChatEmail = null
        // Save draft when user leaves the chat, unless sending is in progress or in search mode
        if (binding.progressSend.visibility != View.VISIBLE && !isSearchMode) {
            val draft = binding.etMessage.text.toString().trim()
            lifecycleScope.launch(Dispatchers.IO) {
                db.contactDao().updateDraft(activeUserId, contactEmail, if (draft.isEmpty()) null else draft)
                localDraft = binding.etMessage.text.toString()
            }
        }
    }

    private fun observeContactInfo() {
        db.contactDao().getContactByEmailLiveData(activeUserId, contactEmail).observe(this) { contact ->
            contact?.let {
                binding.tvToolbarName.text = it.name
                it.avatarPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        com.bumptech.glide.Glide.with(this@ChatActivity)
                            .load(file)
                            .circleCrop()
                            .into(binding.ivToolbarAvatar)
                    }
                }
                // Restore draft when opening activity
                if (!draftRestored && !it.newMessageDraft.isNullOrEmpty() && binding.etMessage.text.isNullOrEmpty()) {
                    binding.etMessage.setText(it.newMessageDraft)
                    binding.etMessage.setSelection(it.newMessageDraft!!.length)
                    draftRestored = true
                }
            }
        }
    }

    private fun markMessagesAsRead() {
        lifecycleScope.launch {
            db.messageDao().markAsRead(activeUserId, contactEmail)
        }
    }

    private fun applyChatBackground() {
        val bgPath = AppPrefs.getChatBackground(this)
        if (bgPath != null) {
            com.bumptech.glide.Glide.with(this).load(File(bgPath)).into(binding.ivChatBackground)
        } else {
            com.bumptech.glide.Glide.with(this).load(R.drawable.chat_background_default).into(binding.ivChatBackground)
        }
    }

    private fun applyFontSize() {
        val size = AppPrefs.getFontSize(this).toFloat()
        binding.etMessage.textSize = size
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        val searchItem = menu.findItem(R.id.action_search)
        if (isSearchMode) {
            searchItem.setIcon(android.R.drawable.ic_menu_revert)
            searchItem.setTitle(R.string.back)
        } else {
            searchItem.setIcon(android.R.drawable.ic_menu_search)
            searchItem.setTitle(R.string.search)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_search -> {
                toggleSearchMode()
                true
            }
            R.id.action_clear_chat -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.clear_chat_confirm)
                    .setPositiveButton(R.string.clear) { _, _ ->
                        lifecycleScope.launch {
                            db.messageDao().deleteAllForContact(activeUserId, contactEmail)
                            db.appEventDao().logEvent("Cleared chat: $contactEmail")
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        invalidateOptionsMenu()
        if (isSearchMode) {
            binding.btnSend.setImageResource(android.R.drawable.ic_menu_search)
            binding.btnAttach.visibility = View.GONE
            binding.etMessage.hint = getString(R.string.search)
            localDraft = binding.etMessage.text.toString()
            binding.etMessage.setText("")
        } else {
            binding.btnSend.setImageResource(android.R.drawable.ic_menu_send)
            binding.btnAttach.visibility = View.VISIBLE
            binding.etMessage.hint = getString(R.string.message_hint)
            binding.etMessage.setText(localDraft)
            binding.etMessage.setSelection(localDraft.length)
            loadMessages()
        }
    }

    private fun setupRecyclerView() {
        val fontSize = AppPrefs.getFontSize(this).toFloat()
        val userEmail = SecurePrefs.getEmail(this) ?: ""
        adapter = MessageAdapter(fontSize, userEmail, contactEmail) { message ->
            showContextMenu(message)
        }
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter

        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && !isSearchMode) { // Scrolling up
                    val firstVisible = layoutManager.findFirstVisibleItemPosition()
                    if (firstVisible <= 5) { // Near the top
                        loadMoreMessages()
                    }
                }
            }
        })

        // Scroll to bottom when keyboard appears
        binding.rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && adapter.itemCount > 0) {
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
    }

    private fun showContextMenu(message: Message) {
        val options = arrayOf(getString(R.string.forward), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showForwardToDialog(message)
                    1 -> showDeleteMessageDialog(message)
                }
            }
            .show()
    }

    private fun showForwardToDialog(message: Message) {
        lifecycleScope.launch {
            val contacts = db.contactDao().getAllContactsSync(activeUserId)
            withContext(Dispatchers.Main) {
                val dialogView = LayoutInflater.from(this@ChatActivity).inflate(R.layout.dialog_select_contact, null)
                val rvContacts = dialogView.findViewById<RecyclerView>(R.id.rv_contacts)
                
                val dialog = AlertDialog.Builder(this@ChatActivity)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .create()

                val contactAdapter = ContactsAdapter(
                    lifecycleOwner = this@ChatActivity,
                    onContactClick = { contact ->
                        dialog.dismiss()
                        forwardMessage(message, contact.email, contact.name)
                    },
                    onContactLongClick = {}
                )
                
                rvContacts.layoutManager = LinearLayoutManager(this@ChatActivity)
                rvContacts.adapter = contactAdapter
                contactAdapter.submitList(contacts)
                
                dialog.show()
            }
        }
    }

    private fun forwardMessage(message: Message, toEmail: String, toName: String) {
        val attachments = try {
            val arr = JSONArray(message.attachments)
            (0 until arr.length()).map { File(arr.getString(it)) }.filter { it.exists() }
        } catch (e: Exception) { emptyList() }

        lifecycleScope.launch {
            val result = emailService.sendMessage(toEmail, message.text, attachments, message.authorEmail)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(this@ChatActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@ChatActivity, ChatActivity::class.java).apply {
                        putExtra(EXTRA_CONTACT_EMAIL, toEmail)
                        putExtra(EXTRA_CONTACT_NAME, toName)
                    }
                    startActivity(intent)
                    finish()
                }
                result.onFailure { e ->
                    Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadMessages() {
        currentMessagesLiveData?.removeObserver(messageObserver)
        currentMessagesLiveData = db.messageDao().getPagedMessagesForContact(activeUserId, contactEmail, currentLimit, 0)
        currentMessagesLiveData?.observe(this, messageObserver)
    }

    private fun performSearch(query: String) {
        currentMessagesLiveData?.removeObserver(messageObserver)
        currentMessagesLiveData = db.messageDao().searchMessages(activeUserId, contactEmail, query)
        currentMessagesLiveData?.observe(this, messageObserver)
    }

    private fun loadMoreMessages() {
        currentLimit += PAGE_SIZE
        loadMessages() 
    }

    private fun setupInputArea() {
        binding.btnAttach.setOnClickListener { showAttachmentOptions() }
        binding.btnSend.setOnClickListener { 
            if (isSearchMode) {
                performSearch(binding.etMessage.text.toString())
            } else {
                sendMessage(false) 
            }
        }
        binding.btnSend.setOnLongClickListener {
            if (!isSearchMode) {
                showSendMenu()
                true
            } else false
        }
        binding.btnClearAttachments.setOnClickListener {
            pendingAttachments.clear()
            updateAttachmentPreview()
        }
    }

    private fun showSendMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnSend)
        popup.menu.add(0, 1, 0, R.string.send_as_chat)
        popup.menu.add(0, 2, 1, R.string.send_as_email)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> sendMessage(false)
                2 -> sendMessage(true)
            }
            true
        }
        popup.show()
    }

    private fun showAttachmentOptions() {
        val options = arrayOf(
            getString(R.string.take_photo),
            getString(R.string.pick_photo),
            getString(R.string.pick_file)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.attach)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> takePhotoFromCamera()
                    1 -> pickImage.launch("image/*")
                    2 -> pickFile.launch("*/*")
                }
            }
            .show()
    }

    private fun takePhotoFromCamera() {
        val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        cameraPhotoFile = photoFile
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        takePhoto.launch(uri)
    }

    private fun addCompressedImage(sourceFile: File) {
        val compressed = File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
        ImageUtils.compressImage(this, Uri.fromFile(sourceFile), compressed)
        pendingAttachments.add(compressed)
        updateAttachmentPreview()
    }

    private fun addImageAttachment(uri: Uri) {
        val compressed = File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
        if (!ImageUtils.compressImage(this, uri, compressed)) {
            Toast.makeText(this, getString(R.string.image_processing_error), Toast.LENGTH_SHORT).show()
            return
        }
        pendingAttachments.add(compressed)
        updateAttachmentPreview()
    }

    private fun addFileAttachment(uri: Uri) {
        val name = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
        val dest = File(cacheDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            pendingAttachments.add(dest)
            updateAttachmentPreview()
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAttachmentPreview() {
        if (pendingAttachments.isEmpty()) {
            binding.layoutAttachments.visibility = View.GONE
        } else {
            binding.layoutAttachments.visibility = View.VISIBLE
            binding.tvAttachmentsCount.text =
                getString(R.string.attachments_count, pendingAttachments.size)
        }
    }

    private fun sendMessage(isClassic: Boolean = false) {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return

        binding.btnSend.visibility = View.GONE
        binding.progressSend.visibility = View.VISIBLE

        // Save current text to draft before sending as requested
        lifecycleScope.launch(Dispatchers.IO) {
            db.contactDao().updateDraft(activeUserId, contactEmail, if (text.isEmpty()) null else text)
            localDraft = text
        }

        val attachmentsToSend = pendingAttachments.toList()
        pendingAttachments.clear()
        updateAttachmentPreview()
        binding.etMessage.setText("")

        lifecycleScope.launch {
            val result = if (isClassic) {
                emailService.sendClassicEmail(contactEmail, text, attachmentsToSend)
            } else {
                emailService.sendMessage(contactEmail, text, attachmentsToSend)
            }

            withContext(Dispatchers.Main) {
                binding.btnSend.visibility = View.VISIBLE
                binding.progressSend.visibility = View.GONE
                result.onFailure { e ->
                    Toast.makeText(this@ChatActivity, e.message, Toast.LENGTH_LONG).show()
                    // Restore text from draft when sending failed
                    restoreDraft()
                }
                result.onSuccess {
                    // Clear draft on success
                    localDraft = "";
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.contactDao().updateDraft(activeUserId, contactEmail, null)
                    }
                    if (adapter.itemCount > 0) {
                        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                    }
                    EmailCheckService.notifyUserActivity(this@ChatActivity)
                    binding.etMessage.setText("")
                }
            }
        }
    }

    private fun restoreDraft() {
        lifecycleScope.launch {
            val contact = db.contactDao().getContactByEmail(activeUserId, contactEmail)
            withContext(Dispatchers.Main) {
                contact?.newMessageDraft?.let { draft ->
                    if (binding.etMessage.text.isNullOrEmpty()) {
                        binding.etMessage.setText(draft)
                        binding.etMessage.setSelection(draft.length)
                    }
                }
            }
        }
    }

    private fun showDeleteMessageDialog(message: Message) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_message_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { db.messageDao().delete(message) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (YnnkMsgApplication.activeChatEmail == contactEmail) {
            YnnkMsgApplication.activeChatEmail = null
        }
    }
}
