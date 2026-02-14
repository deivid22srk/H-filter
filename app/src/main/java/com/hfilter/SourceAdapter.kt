package com.hfilter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hfilter.databinding.ItemSourceBinding

class SourceAdapter(
    private val onToggle: (HostSource, Boolean) -> Unit,
    private val onDelete: (HostSource) -> Unit
) : ListAdapter<HostSource, SourceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = getItem(position)
        holder.bind(source)
    }

    inner class ViewHolder(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(source: HostSource) {
            binding.tvSourceName.text = source.name
            binding.tvSourceUrl.text = source.url
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = source.enabled

            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(source, isChecked)
            }

            binding.btnDelete.setOnClickListener {
                onDelete(source)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HostSource>() {
        override fun areItemsTheSame(oldItem: HostSource, newItem: HostSource) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HostSource, newItem: HostSource) = oldItem == newItem
    }
}
