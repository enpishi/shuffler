package com.ghancaballes.blacksheep

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class CourtAdapter(
    private val courts: List<Court>,
    private val onGameFinished: (winners: List<Player>, losers: List<Player>, courtIndex: Int) -> Unit,
    private val onEditCourt: (courtIndex: Int) -> Unit
) : RecyclerView.Adapter<CourtAdapter.CourtVH>() {

    var winStreakProvider: ((Player) -> Int)? = null

    inner class CourtVH(v: View) : RecyclerView.ViewHolder(v) {
        val courtName: TextView = v.findViewById(R.id.courtName)
        val emptyText: TextView = v.findViewById(R.id.textViewEmptyCourt)
        val gridContainer: View = v.findViewById(R.id.courtGrid)
        val player1: TextView = v.findViewById(R.id.player1_name)
        val player2: TextView = v.findViewById(R.id.player2_name)
        val player3: TextView = v.findViewById(R.id.player3_name)
        val player4: TextView = v.findViewById(R.id.player4_name)
        val buttonA: MaterialButton = v.findViewById(R.id.buttonWinnerTeamA)
        val buttonB: MaterialButton = v.findViewById(R.id.buttonWinnerTeamB)
        val editBtn: MaterialButton = v.findViewById(R.id.buttonEditCourt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourtVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.court_item, parent, false)
        return CourtVH(v)
    }

    override fun getItemCount(): Int = courts.size

    override fun onBindViewHolder(holder: CourtVH, position: Int) {
        val court = courts[position]
        holder.courtName.text = "Court ${court.courtNumber}"

        val teams = court.teams
        val hasTeams = teams != null
        holder.emptyText.visibility = if (hasTeams) View.GONE else View.VISIBLE
        holder.gridContainer.visibility = if (hasTeams) View.VISIBLE else View.GONE
        holder.buttonA.isEnabled = hasTeams
        holder.buttonB.isEnabled = hasTeams

        if (teams != null) {
            val (teamA, teamB) = teams
            bindPlayer(holder.player1, teamA.getOrNull(0))
            bindPlayer(holder.player3, teamA.getOrNull(1))
            bindPlayer(holder.player2, teamB.getOrNull(0))
            bindPlayer(holder.player4, teamB.getOrNull(1))
        } else {
            listOf(holder.player1, holder.player2, holder.player3, holder.player4).forEach {
                bindPlayer(it, null)
            }
        }

        holder.buttonA.setOnClickListener {
            courts[position].teams?.let { t -> onGameFinished(t.first, t.second, position) }
        }
        holder.buttonB.setOnClickListener {
            courts[position].teams?.let { t -> onGameFinished(t.second, t.first, position) }
        }
        holder.editBtn.setOnClickListener { onEditCourt(position) }
    }

    private fun bindPlayer(tv: TextView, player: Player?) {
        if (player == null) {
            tv.text = "-"
            tv.setBackgroundColor(Color.parseColor("#BDBDBD"))
            tv.setTextColor(Color.BLACK)
            return
        }
        val (bg, fg) = colorForWinrate(player)
        tv.setBackgroundColor(bg)
        tv.setTextColor(fg)
        val streak = winStreakProvider?.invoke(player) ?: 0
        val flame = if (streak >= 3) " 🔥" else ""
        tv.text = player.name + flame
    }

    // New inverted palette mapping (higher winrate -> warmer colors per your spec)
    private fun colorForWinrate(p: Player): Pair<Int, Int> {
        val games = p.gamesPlayed
        if (games < 10) {
            return Pair(Color.parseColor("#9E9E9E"), Color.WHITE) // Neutral Gray
        }
        val wr = p.winrate
        return when {
            wr < 0.40 -> Pair(Color.parseColor("#C8E6C9"), Color.BLACK)      // Light Green
            wr < 0.50 -> Pair(Color.parseColor("#66BB6A"), Color.BLACK)      // Mid Green
            wr < 0.60 -> Pair(Color.parseColor("#2E7D32"), Color.WHITE)      // Dark Green
            wr < 0.70 -> Pair(Color.parseColor("#EF6C00"), Color.WHITE)      // Dark Orange
            wr < 0.80 -> Pair(Color.parseColor("#AD1457"), Color.WHITE)      // Dark Pink
            else ->       Pair(Color.parseColor("#B71C1C"), Color.WHITE)      // Very Dark Red (≥80%)
        }
    }
}