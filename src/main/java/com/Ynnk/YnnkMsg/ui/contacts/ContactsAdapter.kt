package com.Ynnk.YnnkMsg.ui.contacts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.databinding.ItemContactBinding
import com.Ynnk.YnnkMsg.util.SecurePrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactsAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val onContactClick: (Contact) -> Unit,
    private val onContactLongClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            binding.tvName.text = contact.name
            binding.tvEmail.text = contact.email

            val avatarFile = contact.avatarPath?.let { File(it) }
            if (avatarFile != null && avatarFile.exists()) {
                binding.tvAvatarLetter.visibility = View.GONE
                binding.ivAvatar.visibility = View.VISIBLE
                Glide.with(binding.root.context)
                    .load(avatarFile)
                    .circleCrop()
                    .into(binding.ivAvatar)
            } else {
                binding.ivAvatar.visibility = View.GONE
                binding.tvAvatarLetter.visibility = View.VISIBLE
                binding.tvAvatarLetter.text =
                    contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }

            // Show last message time
            if (contact.lastMsgTime != null && contact.lastMsgTime > 0) {
                binding.tvTime.visibility = View.VISIBLE
                binding.tvTime.text = timeFormat.format(Date(contact.lastMsgTime))
            } else {
                binding.tvTime.visibility = View.GONE
            }

            // Task: Show unread mark
            val context = binding.root.context
            val db = AppDatabase.getInstance(context)
            val userId = SecurePrefs.getActiveUserId(context)
            db.messageDao().getUnreadCount(userId, contact.email).observe(lifecycleOwner) { count ->
                binding.vNewMessageIndicator.visibility = if (count > 0) View.VISIBLE else View.GONE
            }

            binding.root.setOnClickListener { onContactClick(contact) }
            binding.root.setOnLongClickListener { onContactLongClick(contact); true }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact) = 
            oldItem.userId == newItem.userId && oldItem.email == newItem.email
        override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
    }
}
