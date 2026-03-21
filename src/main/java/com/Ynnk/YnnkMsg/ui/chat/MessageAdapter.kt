package com.Ynnk.YnnkMsg.ui.chat

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.data.entity.Message
import com.Ynnk.YnnkMsg.databinding.ItemMessageIncomingBinding
import com.Ynnk.YnnkMsg.databinding.ItemMessageOutgoingBinding
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val VIEW_OUTGOING = 0
private const val VIEW_INCOMING = 1

class MessageAdapter(
    private val fontSize: Float,
    private val currentUserEmail: String,
    private val contactEmail: String,
    private val onLongClick: (Message) -> Unit
) : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int) =
        if (getItem(position).isOutgoing) VIEW_OUTGOING else VIEW_INCOMING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_OUTGOING) {
            val b = ItemMessageOutgoingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            OutgoingViewHolder(b)
        } else {
            val b = ItemMessageIncomingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            IncomingViewHolder(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is OutgoingViewHolder -> holder.bind(message)
            is IncomingViewHolder -> holder.bind(message)
        }
    }

    inner class OutgoingViewHolder(private val b: ItemMessageOutgoingBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(message: Message) {
            val showAuthor = message.authorEmail.isNotEmpty() &&
                    message.authorEmail.lowercase() != currentUserEmail.lowercase() &&
                    message.authorEmail.lowercase() != contactEmail.lowercase()

            if (showAuthor) {
                b.tvAuthor.visibility = View.VISIBLE
                b.tvAuthor.text = b.tvAuthor.context.getString(R.string.forward_from) + " " + message.authorEmail
            } else {
                b.tvAuthor.visibility = View.GONE
            }

            b.tvMessage.text = message.text
            b.tvMessage.textSize = fontSize
            b.tvTime.text = formatTime(message.timestamp)
            b.root.setOnLongClickListener { onLongClick(message); true }
            
            val hasImages = bindAttachments(b.llAttachments, message)
            
            val lp = b.bubbleLayout.layoutParams
            if (hasImages) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            b.bubbleLayout.layoutParams = lp

            // Show green check mark if message is read
            b.ivRead.visibility = if (message.isRead) View.VISIBLE else View.GONE
        }
    }

    inner class IncomingViewHolder(private val b: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(message: Message) {
            val showAuthor = message.authorEmail.isNotEmpty() &&
                    message.authorEmail.lowercase() != currentUserEmail.lowercase() &&
                    message.authorEmail.lowercase() != contactEmail.lowercase()

            if (showAuthor) {
                b.tvAuthor.visibility = View.VISIBLE
                b.tvAuthor.text = message.authorEmail
            } else {
                b.tvAuthor.visibility = View.GONE
            }

            b.tvMessage.text = message.text
            b.tvMessage.textSize = fontSize
            b.tvTime.text = formatTime(message.timestamp)
            b.root.setOnLongClickListener { onLongClick(message); true }
            
            val hasImages = bindAttachments(b.llAttachments, message)

            val lp = b.bubbleLayout.layoutParams
            if (hasImages) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            b.bubbleLayout.layoutParams = lp
        }
    }

    private fun bindAttachments(container: ViewGroup, message: Message): Boolean {
        container.removeAllViews()
        if (message.attachments.isEmpty()) return false

        val paths = try {
            val arr = JSONArray(message.attachments)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }

        var hasImage = false
        for (path in paths) {
            val file = File(path)
            if (!file.exists()) continue

            val isImage = path.lowercase().let {
                it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".webp")
            }

            if (isImage) {
                container.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

                hasImage = true
                val iv = ImageView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        800
                    ).apply { topMargin = 8 }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener { openFile(path, container) }
                }
                Glide.with(container.context).load(file).into(iv)
                container.addView(iv)
            }
            else
            {
                val tv = TextView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                    text = context.getString(R.string.attachment_indicator, file.name)
                    setPadding(8, 8, 8, 8)
                    setTextColor(android.graphics.Color.parseColor("#0077CC"))
                    setOnClickListener { showAttachmentMenu(path, container) }
                }

                container.addView(tv)
            }
        }
        return hasImage
    }

    private fun showAttachmentMenu(path: String, container: ViewGroup) {
        val context = container.context
        val options = arrayOf(context.getString(R.string.open), context.getString(R.string.save_to_downloads))
        AlertDialog.Builder(context)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(path, container)
                    1 -> saveToDownloads(path, container)
                }
            }
            .show()
    }

    private fun saveToDownloads(path: String, container: ViewGroup) {
        val context = container.context
        val srcFile = File(path)
        if (!srcFile.exists()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, srcFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, context.contentResolver.getType(
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", srcFile)
                    ) ?: "*/*")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(srcFile).use { input ->
                            input.copyTo(output)
                        }
                    }
                    Toast.makeText(context, R.string.saved_to_downloads, Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadsDir, srcFile.name)
                FileInputStream(srcFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(context, R.string.saved_to_downloads, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(path: String, container: ViewGroup) {
        val context = container.context
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_file)))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(o: Message, n: Message) = o.id == n.id
        override fun areContentsTheSame(o: Message, n: Message) = o == n
    }
}
