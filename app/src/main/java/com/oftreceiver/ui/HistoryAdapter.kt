package com.oftreceiver.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.oftreceiver.data.TransferRecord
import com.oftreceiver.databinding.ItemTransferBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onDelete: (TransferRecord) -> Unit
) : ListAdapter<TransferRecord, HistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemTransferBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: TransferRecord) {
            binding.itemFilename.text = record.filename
            binding.itemSize.text = formatBytes(record.fileSize)
            binding.itemDate.text = formatDate(record.timestamp)
            binding.itemSha.text = record.sha256

            binding.btnDelete.setOnClickListener {
                onDelete(record)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun formatDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            diff < 604800_000 -> {
                val sdf = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
            else -> {
                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TransferRecord>() {
            override fun areItemsTheSame(old: TransferRecord, new: TransferRecord) = old.id == new.id
            override fun areContentsTheSame(old: TransferRecord, new: TransferRecord) = old == new
        }
    }
}
