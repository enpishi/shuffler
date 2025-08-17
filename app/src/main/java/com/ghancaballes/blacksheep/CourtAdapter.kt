package com.ghancaballes.blacksheep

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CourtAdapter(
    private var courts: List<Court>,
    private val onWinnerSelected: (winners: List<Player>, losers: List<Player>, courtIndex: Int) -> Unit,
    private val onEditCourtClicked: (courtIndex: Int) -> Unit
) : RecyclerView.Adapter<CourtAdapter.CourtViewHolder>() {

    companion object {
        private const val MIN_RANKED_GAMES = 10
        private const val TAG = "CourtAdapter"
    }

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
        private val courtName: TextView = itemView.findViewById(R.id.courtName)
        private val editCourtButton: Button = itemView.findViewById(R.id.buttonEditCourt)
        private val courtGrid: View = itemView.findViewById(R.id.courtGrid)
        private val emptyCourtMessage: TextView = itemView.findViewById(R.id.textViewEmptyCourt)

        private val player1Name: TextView = itemView.findViewById(R.id.player1_name)
        private val player2Name: TextView = itemView.findViewById(R.id.player2_name)
        private val player3Name: TextView = itemView.findViewById(R.id.player3_name)
        private val player4Name: TextView = itemView.findViewById(R.id.player4_name)

        private val buttonWinnerTeamA: Button = itemView.findViewById(R.id.buttonWinnerTeamA)
        private val buttonWinnerTeamB: Button = itemView.findViewById(R.id.buttonWinnerTeamB)

        fun bind(court: Court, position: Int) {
            courtName.text = "Court ${court.courtNumber}"

            // Always reset enabled state on bind to avoid recycled-disabled buttons
            buttonWinnerTeamA.isEnabled = true
            buttonWinnerTeamB.isEnabled = true

            val teamA = court.teams?.first
            val teamB = court.teams?.second

            editCourtButton.setOnClickListener {
                onEditCourtClicked(position)
            }

            if (teamA != null && teamA.size == 2 && teamB != null && teamB.size == 2) {
                courtGrid.visibility = View.VISIBLE
                emptyCourtMessage.visibility = View.GONE

                val a1 = teamA[0]
                val a2 = teamA[1]
                val b1 = teamB[0]
                val b2 = teamB[1]

                player1Name.text = a1.name
                player2Name.text = a2.name
                player3Name.text = b1.name
                player4Name.text = b2.name

                setPlayerBackground(player1Name, a1)
                setPlayerBackground(player2Name, a2)
                setPlayerBackground(player3Name, b1)
                setPlayerBackground(player4Name, b2)

                // Winner buttons with confirmation
                buttonWinnerTeamA.setOnClickListener {
                    showConfirmWinnerDialog(
                        courtNumber = court.courtNumber,
                        winners = teamA,
                        losers = teamB
                    ) {
                        // Disable to guard against double taps until Activity rebinds
                        buttonWinnerTeamA.isEnabled = false
                        buttonWinnerTeamB.isEnabled = false
                        onWinnerSelected(teamA, teamB, position)
                    }
                }
                buttonWinnerTeamB.setOnClickListener {
                    showConfirmWinnerDialog(
                        courtNumber = court.courtNumber,
                        winners = teamB,
                        losers = teamA
                    ) {
                        buttonWinnerTeamA.isEnabled = false
                        buttonWinnerTeamB.isEnabled = false
                        onWinnerSelected(teamB, teamA, position)
                    }
                }
            } else {
                courtGrid.visibility = View.GONE
                emptyCourtMessage.visibility = View.VISIBLE
            }
        }

        private fun showConfirmWinnerDialog(
            courtNumber: Int,
            winners: List<Player>,
            losers: List<Player>,
            onConfirm: () -> Unit
        ) {
            val winnersNames = winners.joinToString(" & ") { it.name }
            val losersNames = losers.joinToString(" & ") { it.name }
            AlertDialog.Builder(itemView.context)
                .setTitle("Confirm winner (Court $courtNumber)")
                .setMessage("Winners: $winnersNames\nLosers: $losersNames\n\nRecord this result?")
                .setPositiveButton("Confirm") { _, _ -> onConfirm() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun setPlayerBackground(textView: TextView, player: Player) {
            Log.d(TAG, "Coloring ${player.name} gp=${player.gamesPlayed} wr=${player.winrate}")
            val context = textView.context
            val colorRes = if (player.gamesPlayed < MIN_RANKED_GAMES) {
                android.R.color.transparent
            } else {
                when {
                    player.winrate >= 0.80 -> R.color.skill_high
                    player.winrate >= 0.60 -> R.color.skill_medium
                    player.winrate >= 0.50 -> R.color.skill_low
                    else -> android.R.color.transparent
                }
            }
            textView.setBackgroundColor(ContextCompat.getColor(context, colorRes))
        }
    }
}