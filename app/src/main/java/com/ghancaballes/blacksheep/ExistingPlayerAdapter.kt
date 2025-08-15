package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ExistingPlayerAdapter(private val players: List<Player>) :
    RecyclerView.Adapter<ExistingPlayerAdapter.PlayerViewHolder>() {

    val selectedPlayers = mutableSetOf<Player>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_player_checkbox, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = players[position]
        holder.bind(player)
    }

    override fun getItemCount() = players.size

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playerNameTextView: TextView = itemView.findViewById(R.id.playerNameTextView)
        private val playerCheckbox: CheckBox = itemView.findViewById(R.id.playerCheckbox)

        fun bind(player: Player) {
            playerNameTextView.text = player.name
            playerCheckbox.isChecked = selectedPlayers.contains(player)

            itemView.setOnClickListener {
                playerCheckbox.isChecked = !playerCheckbox.isChecked
            }

            playerCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPlayers.add(player)
                } else {
                    selectedPlayers.remove(player)
                }
            }
        }
    }
}