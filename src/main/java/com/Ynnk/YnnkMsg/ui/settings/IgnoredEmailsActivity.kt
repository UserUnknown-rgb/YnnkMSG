package com.Ynnk.YnnkMsg.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.databinding.ActivityIgnoredEmailsBinding
import com.Ynnk.YnnkMsg.databinding.ItemIgnoredEmailBinding
import com.Ynnk.YnnkMsg.util.AppPrefs
import kotlinx.coroutines.launch

class IgnoredEmailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIgnoredEmailsBinding
    private lateinit var adapter: IgnoredEmailsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIgnoredEmailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ignored_emails_title)

        setupRecyclerView()
        binding.fabAddIgnored.setOnClickListener { showAddIgnoredDialog() }
    }

    private fun setupRecyclerView() {
        adapter = IgnoredEmailsAdapter { email ->
            AppPrefs.removeIgnoredEmail(this, email)
            refreshList()
        }
        binding.rvIgnored.layoutManager = LinearLayoutManager(this)
        binding.rvIgnored.adapter = adapter
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(AppPrefs.getIgnoredEmails(this).toList().sorted())
    }

    private fun showAddIgnoredDialog() {
        val et = EditText(this).apply { hint = "email@example.com" }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_ignored_email)
            .setView(et)
            .setPositiveButton(R.string.add) { _, _ ->
                val email = et.text.toString().trim().lowercase()
                if (email.isNotEmpty()) {
                    AppPrefs.addIgnoredEmail(this, email)
                    lifecycleScope.launch {
                        AppDatabase.getInstance(this@IgnoredEmailsActivity).appEventDao().logEvent("Added to blacklist: $email")
                    }
                    refreshList()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class IgnoredEmailsAdapter(private val onRemove: (String) -> Unit) :
    ListAdapter<String, IgnoredEmailsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemIgnoredEmailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemIgnoredEmailBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(email: String) {
            b.tvEmail.text = email
            b.btnRemove.setOnClickListener { onRemove(email) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
