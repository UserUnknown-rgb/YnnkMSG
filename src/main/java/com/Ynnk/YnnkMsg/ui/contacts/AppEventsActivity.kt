package com.Ynnk.YnnkMsg.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.AppEvent
import com.Ynnk.YnnkMsg.databinding.ActivityAppEventsBinding
import com.Ynnk.YnnkMsg.databinding.ItemAppEventBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.Ynnk.YnnkMsg.R
import java.util.*

class AppEventsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppEventsBinding
    private val adapter = AppEventsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = binding.rvEvents.context.getString(R.string.app_events)

        binding.rvEvents.layoutManager = LinearLayoutManager(this)
        binding.rvEvents.adapter = adapter

        observeEvents()
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.getInstance(this@AppEventsActivity).appEventDao().getAllEvents()
                    .collect { events ->
                        adapter.submitList(events)
                    }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class AppEventsAdapter : ListAdapter<AppEvent, AppEventsAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemAppEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemAppEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: AppEvent) {
            b.tvTime.text = dateFormat.format(Date(event.timestamp))
            b.tvMessage.text = event.message
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppEvent>() {
        override fun areItemsTheSame(oldItem: AppEvent, newItem: AppEvent) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AppEvent, newItem: AppEvent) = oldItem == newItem
    }
}
