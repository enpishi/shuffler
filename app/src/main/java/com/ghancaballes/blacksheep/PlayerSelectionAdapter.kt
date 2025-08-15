package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView

class PlayerSelectionAdapter(
    private val players: List<Player>,
    private val selectedPlayers: MutableSet<Player>,
    private val onPlayerSelectionChanged: (Player, Boolean) -> Unit
) : RecyclerView.Adapter<PlayerSelectionAdapter.PlayerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        // Inflating the correct layout file
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_player_selectable, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = players[position]
        holder.bind(player)
    }

    override fun getItemCount(): Int = players.size

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Referencing the new CheckBox ID
        private val checkBox: CheckBox = itemView.findViewById(R.id.playerCheckBox)

        fun bind(player: Player) {
            // Set initial state without triggering listener
            checkBox.setOnCheckedChangeListener(null)
            checkBox.text = player.name
            // Use player ID for reliable checking
            checkBox.isChecked = selectedPlayers.any { it.id == player.id }

            // Set the listener to handle user interaction
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                onPlayerSelectionChanged(player, isChecked)
            }
        }
    }
}