package com.ghancaballes.blacksheep

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EditCourtPlayerAdapter(
    private val players: List<Player>
) : RecyclerView.Adapter<EditCourtPlayerAdapter.PlayerViewHolder>() {

    // --- FIX: onPlayerSelected is now a public variable that can be assigned later ---
    lateinit var onPlayerSelected: (Player) -> Unit
    var selectedPlayer: Player? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = players[position]
        holder.bind(player)
    }

    override fun getItemCount(): Int = players.size

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(player: Player) {
            nameTextView.text = player.name

            if (player == selectedPlayer) {
                itemView.setBackgroundColor(Color.LTGRAY)
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            itemView.setOnClickListener {
                // Check if the handler has been initialized before calling it
                if (::onPlayerSelected.isInitialized) {
                    onPlayerSelected(player)
                }
            }
        }
    }
}