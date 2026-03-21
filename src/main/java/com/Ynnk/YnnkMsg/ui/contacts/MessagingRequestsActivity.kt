package com.Ynnk.YnnkMsg.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.databinding.ActivityMessagingRequestsBinding
import com.Ynnk.YnnkMsg.databinding.ItemMessagingRequestBinding
import com.Ynnk.YnnkMsg.util.AppPrefs
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.launch

class MessagingRequestsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessagingRequestsBinding
    private lateinit var adapter: MessagingRequestsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagingRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.messaging_requests_title)

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        adapter = MessagingRequestsAdapter(
            onAllow = { email ->
                lifecycleScope.launch {
                    val userId = SecurePrefs.getActiveUserId(this@MessagingRequestsActivity)
                    if (userId != -1L) {
                        val contactDao = AppDatabase.getInstance(this@MessagingRequestsActivity).contactDao()
                        val normalizedEmail = email.lowercase()
                        val existing = contactDao.getContactByEmail(userId, normalizedEmail)
                        if (existing == null) {
                            contactDao.insert(Contact(userId = userId, email = normalizedEmail, name = email))
                        }
                        AppPrefs.removeMessagingRequest(this@MessagingRequestsActivity, email)
                        refreshList()
                    }
                }
            },
            onDecline = { email ->
                AppPrefs.addIgnoredEmail(this, email.lowercase())
                AppPrefs.removeMessagingRequest(this, email)
                refreshList()
            }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(AppPrefs.getMessagingRequests(this).toList().sorted())
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class MessagingRequestsAdapter(
    private val onAllow: (String) -> Unit,
    private val onDecline: (String) -> Unit
) : ListAdapter<String, MessagingRequestsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemMessagingRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemMessagingRequestBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(email: String) {
            b.tvEmail.text = email
            b.btnAllow.setOnClickListener { onAllow(email) }
            b.btnDecline.setOnClickListener { onDecline(email) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
