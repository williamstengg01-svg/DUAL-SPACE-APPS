package com.dualspace.clone.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.GmsLinker
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

    /** Only repaint the green/red dots (cheap; no icon rebinding). */
    fun refreshGmsDots() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_GMS)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_GMS)) holder.bindDot(items[position]) else super.onBindViewHolder(holder, position, payloads)
    }

    inner class VH(private val b: ItemCloneBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Clone) {
            val ctx = b.root.context
            IconCache.load(b.icon, iconKey(c), ctx.packageManager.defaultActivityIcon) {
                CloneManager.icon(ctx, c)
            }
            b.label.text = c.label
            b.icon.alpha = if (c.frozen) 0.4f else 1f
            b.badgeFrozen.visibility = if (c.frozen) View.VISIBLE else View.GONE
            b.badgeLock.visibility = if (c.locked) View.VISIBLE else View.GONE
            b.badgeHidden.visibility = if (c.hidden) View.VISIBLE else View.GONE
            bindDot(c)
            b.root.setOnClickListener { onClick(c) }
            b.root.setOnLongClickListener { onLongClick(c); true }
        }

        fun bindDot(c: Clone) {
            val hostHasGms = GmsLinker.lastStatus?.hostHasGms ?: true
            b.dotGms.setImageResource(
                when {
                    !hostHasGms -> R.drawable.dot_red
                    else -> when (GmsLinker.cachedLinked(c.userId)) {
                        true -> R.drawable.dot_green
                        false -> R.drawable.dot_red
                        null -> R.drawable.dot_grey
                    }
                }
            )
        }
    }

    companion object {
        private const val PAYLOAD_GMS = "gms"
        fun iconKey(c: Clone) = "clone:${c.id}:${c.index}:${c.customIconPath ?: "-"}"
    }
}
