package io.github.lamppkk.xanhbrowser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.lamppkk.xanhbrowser.databinding.RowTabBinding

internal class TabAdapter(
    private val onOpen: (BrowserTab) -> Unit,
    private val onClose: (BrowserTab) -> Unit,
) : ListAdapter<BrowserTab, TabAdapter.TabViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = RowTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) = holder.bind(getItem(position))

    inner class TabViewHolder(private val binding: RowTabBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tab: BrowserTab) {
            binding.tabTitle.text = tab.title.ifBlank { binding.root.context.getString(R.string.new_tab) }
            binding.tabUrl.text = tab.url
            binding.selectedIndicator.visibility = if (tab.selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.root.setOnClickListener { onOpen(tab) }
            binding.closeTab.setOnClickListener { onClose(tab) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<BrowserTab>() {
        override fun areItemsTheSame(oldItem: BrowserTab, newItem: BrowserTab) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BrowserTab, newItem: BrowserTab) = oldItem == newItem
    }
}
