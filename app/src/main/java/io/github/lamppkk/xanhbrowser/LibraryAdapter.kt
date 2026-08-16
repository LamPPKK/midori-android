package io.github.lamppkk.xanhbrowser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.lamppkk.xanhbrowser.databinding.RowLibraryBinding

internal data class LibraryRow(val id: Long, val url: String, val title: String, val subtitle: String)

internal class LibraryAdapter(
    private val onOpen: (LibraryRow) -> Unit,
    private val onDelete: (LibraryRow) -> Unit,
) : ListAdapter<LibraryRow, LibraryAdapter.LibraryViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val binding = RowLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LibraryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) = holder.bind(getItem(position))

    inner class LibraryViewHolder(private val binding: RowLibraryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: LibraryRow) {
            binding.itemTitle.text = row.title.ifBlank { row.url }
            binding.itemSubtitle.text = row.subtitle
            binding.root.setOnClickListener { onOpen(row) }
            binding.deleteItem.setOnClickListener { onDelete(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryRow>() {
        override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow) = oldItem == newItem
    }
}
