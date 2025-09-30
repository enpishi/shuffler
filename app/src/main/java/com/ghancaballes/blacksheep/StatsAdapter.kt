package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Single-line stats: Name | G/W/L | G/W/L Win%
 * No "Cur:" or "All:" prefixes.
 */
class StatsAdapter(private val rows: List<StatsRow>) :
    RecyclerView.Adapter<StatsAdapter.StatVH>() {

    inner class StatVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.textStatName)
        val current: TextView = v.findViewById(R.id.textCurrent)
        val overall: TextView = v.findViewById(R.id.textOverall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat_row, parent, false)
        return StatVH(v)
    }

    override fun onBindViewHolder(holder: StatVH, position: Int) {
        val r = rows[position]
        holder.name.text = r.name
        holder.current.text = "${r.currentG}/${r.currentW}/${r.currentL}"
        holder.overall.text = "${r.overallG}/${r.overallW}/${r.overallL} ${r.overallWinPct}%"
    }

    override fun getItemCount(): Int = rows.size
}