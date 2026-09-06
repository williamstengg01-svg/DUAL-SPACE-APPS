package com.dualspace.clone.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.databinding.ItemCloneBinding
import com.dualspace.clone.model.Clone

class CloneAdapter(
    private val onClick: (Clone) -> Unit,
    private val onLongClick: (Clone) -> Unit
) : RecyclerView.Adapter<CloneAdapter.VH>() {

    private val items = mutableListOf<Clone>()

    fun submit(list: List<Clone>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == list[n]
        })
        items.clear(); items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemCloneBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Clone) {
            b.icon.setImageDrawable(CloneManager.icon(b.root.context, c))
            b.label.text = c.label
            b.icon.alpha = if (c.frozen) 0.4f else 1f
            b.badgeFrozen.visibility = if (c.frozen) View.VISIBLE else View.GONE
            b.badgeLock.visibility = if (c.locked) View.VISIBLE else View.GONE
            b.badgeHidden.visibility = if (c.hidden) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onClick(c) }
            b.root.setOnLongClickListener { onLongClick(c); true }
        }
    }
}
