package com.ghancaballes.blacksheep

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter used in the "Edit Court" dialog for showing Team A, Team B, and Resting players.
 *
 * Enhancements:
 *  - Optional display of session games count for resting players (showSessionGames = true).
 *    Format: "Player Name (G: <sessionGames>)"
 */
class EditCourtPlayerAdapter(
    private val players: MutableList<Player>,
    private val sessionStats: Map<String, PlayerManagementActivity.SessionStats>? = null,
    private val showSessionGames: Boolean = false
) : RecyclerView.Adapter<EditCourtPlayerAdapter.PlayerViewHolder>() {

    var selectedPlayer: Player? = null
    var onPlayerSelected: ((Player) -> Unit)? = null

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(player: Player, isSelected: Boolean) {
            val displayName = if (showSessionGames) {
                val games = sessionStats?.get(player.id)?.games ?: 0
                "${player.name} (G:$games)"
            } else {
                player.name
            }
            nameText.text = displayName
            nameText.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            val color = if (isSelected)
                ContextCompat.getColor(itemView.context, R.color.badminton_vs_blue)
            else
                ContextCompat.getColor(itemView.context, android.R.color.black)
            nameText.setTextColor(color)

            itemView.setOnClickListener {
                onPlayerSelected?.invoke(player)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return PlayerViewHolder(v)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val p = players[position]
        holder.bind(p, p == selectedPlayer)
    }

    override fun getItemCount(): Int = players.size
}