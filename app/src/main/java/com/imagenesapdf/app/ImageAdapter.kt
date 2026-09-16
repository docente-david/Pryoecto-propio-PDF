package com.imagenesapdf.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.imagenesapdf.app.databinding.ItemImageBinding
import java.util.Collections

class ImageAdapter(
    private val items: MutableList<Uri>,
    private val onRemove: (position: Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.Holder>() {

    class Holder(val binding: ItemImageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val uri = items[position]
        with(holder.binding) {
            thumbnail.load(uri) {
                crossfade(true)
                size(480)
            }
            pageBadge.text = (position + 1).toString()
            root.contentDescription = root.context.getString(R.string.page_number, position + 1)
            removeButton.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemove(pos)
            }
        }
    }

    /** Mueve un elemento durante el arrastre. */
    fun move(from: Int, to: Int) {
        if (from == to) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    /** Tras soltar, refresca los números de página de todos los elementos. */
    fun refreshBadges() = notifyItemRangeChanged(0, items.size, PAYLOAD_BADGE)

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_BADGE)) {
            holder.binding.pageBadge.text = (position + 1).toString()
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private companion object {
        const val PAYLOAD_BADGE = "badge"
    }
}
