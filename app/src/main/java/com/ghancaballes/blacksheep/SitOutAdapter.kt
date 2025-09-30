package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SitOutAdapter(
    private val entries: List<Triple<Player, Boolean, Int>>, // Player, playing?, court#
    private val onClick: (Player) -> Unit
) : RecyclerView.Adapter<SitOutAdapter.SitOutVH>() {

    inner class SitOutVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.textPlayerName)
        val meta: TextView = v.findViewById(R.id.textPlayerMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SitOutVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_line, parent, false)
        return SitOutVH(v)
    }

    override fun onBindViewHolder(holder: SitOutVH, position: Int) {
        val (p, playing, court) = entries[position]
        holder.name.text = p.name
        holder.meta.text = if (playing) "Court $court" else "Resting"
        holder.itemView.setOnClickListener { onClick(p) }
    }

    override fun getItemCount(): Int = entries.size
}