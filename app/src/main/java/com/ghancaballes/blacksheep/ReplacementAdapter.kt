package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ReplacementAdapter(
    private val resting: List<Player>,
    private val onClick: (Player) -> Unit
) : RecyclerView.Adapter<ReplacementAdapter.ReplVH>() {

    inner class ReplVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.textPlayerName)
        val meta: TextView = v.findViewById(R.id.textPlayerMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReplVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_line, parent, false)
        return ReplVH(v)
    }

    override fun onBindViewHolder(holder: ReplVH, position: Int) {
        val p = resting[position]
        holder.name.text = p.name
        holder.meta.text = "Resting"
        holder.itemView.setOnClickListener { onClick(p) }
    }

    override fun getItemCount(): Int = resting.size
}