package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlayerAdapter(private val players: List<Player>) : RecyclerView.Adapter<PlayerAdapter.PlayerViewHolder>() {

    // This class holds the UI views for each item in the list.
    class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.textViewPlayerName)
    }

    // Called when RecyclerView needs a new "row" to display. It inflates our XML layout for the item.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.player_item, parent, false)
        return PlayerViewHolder(view)
    }

    // Called for each row to bind the data (a player's name) to the UI (the TextView).
    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.nameTextView.text = players[position].name
    }

    // Returns the total number of items in the list.
    override fun getItemCount(): Int {
        return players.size
    }
}