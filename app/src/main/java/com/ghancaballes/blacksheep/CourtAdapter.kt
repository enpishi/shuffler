package com.ghancaballes.blacksheep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CourtAdapter(
    private var courts: List<Court>,
    private val onWinnerSelected: (winners: List<Player>, losers: List<Player>, courtIndex: Int) -> Unit,
    private val onEditCourtClicked: (courtIndex: Int) -> Unit
) : RecyclerView.Adapter<CourtAdapter.CourtViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourtViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.court_item, parent, false)
        return CourtViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourtViewHolder, position: Int) {
        val court = courts[position]
        holder.bind(court, position)
    }

    override fun getItemCount(): Int = courts.size

    fun updateCourts(newCourts: List<Court>) {
        this.courts = newCourts
        notifyDataSetChanged()
    }

    inner class CourtViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // References to all the views in the new layout
        private val courtName: TextView = itemView.findViewById(R.id.courtName)
        private val editCourtButton: Button = itemView.findViewById(R.id.buttonEditCourt)
        private val courtGrid: View = itemView.findViewById(R.id.courtGrid)
        private val emptyCourtMessage: TextView = itemView.findViewById(R.id.textViewEmptyCourt)

        // Player name views
        private val player1Name: TextView = itemView.findViewById(R.id.player1_name)
        private val player2Name: TextView = itemView.findViewById(R.id.player2_name)
        private val player3Name: TextView = itemView.findViewById(R.id.player3_name)
        private val player4Name: TextView = itemView.findViewById(R.id.player4_name)

        // Winner buttons
        private val buttonWinnerTeamA: Button = itemView.findViewById(R.id.buttonWinnerTeamA)
        private val buttonWinnerTeamB: Button = itemView.findViewById(R.id.buttonWinnerTeamB)

        fun bind(court: Court, position: Int) {
            courtName.text = "Court ${court.courtNumber}"

            val teamA = court.teams?.first
            val teamB = court.teams?.second

            editCourtButton.setOnClickListener {
                onEditCourtClicked(position)
            }

            if (teamA != null && teamA.size == 2 && teamB != null && teamB.size == 2) {
                // Court has players, show the grid and hide the empty message
                courtGrid.visibility = View.VISIBLE
                emptyCourtMessage.visibility = View.GONE

                // Map players to quadrants
                player1Name.text = teamA[0].name // Team A, Player 1 (Top Left)
                player2Name.text = teamA[1].name // Team A, Player 2 (Bottom Left)
                player3Name.text = teamB[0].name // Team B, Player 1 (Top Right)
                player4Name.text = teamB[1].name // Team B, Player 2 (Bottom Right)

                // Set winner button listeners
                buttonWinnerTeamA.setOnClickListener {
                    onWinnerSelected(teamA, teamB, position)
                }
                buttonWinnerTeamB.setOnClickListener {
                    onWinnerSelected(teamB, teamA, position)
                }

            } else {
                // Court is empty or not properly formed, hide the grid and show the message
                courtGrid.visibility = View.GONE
                emptyCourtMessage.visibility = View.VISIBLE
            }
        }
    }
}