package com.ghancaballes.blacksheep

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * Restored grid design + winrate color coding.
 * Team mapping:
 *  Team A -> left column (player1_name top, player3_name bottom)
 *  Team B -> right column (player2_name top, player4_name bottom)
 */
class CourtAdapter(
    private val courts: List<Court>,
    private val onGameFinished: (winners: List<Player>, losers: List<Player>, courtIndex: Int) -> Unit,
    private val onEditCourt: (courtIndex: Int) -> Unit
) : RecyclerView.Adapter<CourtAdapter.CourtVH>() {

    inner class CourtVH(v: View) : RecyclerView.ViewHolder(v) {
        val courtName: TextView = v.findViewById(R.id.courtName)
        val emptyText: TextView = v.findViewById(R.id.textViewEmptyCourt)
        val gridContainer: View = v.findViewById(R.id.courtGrid)
        val player1: TextView = v.findViewById(R.id.player1_name)
        val player2: TextView = v.findViewById(R.id.player2_name)
        val player3: TextView = v.findViewById(R.id.player3_name)
        val player4: TextView = v.findViewById(R.id.player4_name)
        val buttonTeamA: MaterialButton = v.findViewById(R.id.buttonWinnerTeamA)
        val buttonTeamB: MaterialButton = v.findViewById(R.id.buttonWinnerTeamB)
        val editBtn: MaterialButton = v.findViewById(R.id.buttonEditCourt)
        val grid: GridLayout = v.findViewById(R.id.gridPlayers)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourtVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.court_item, parent, false)
        return CourtVH(v)
    }

    override fun getItemCount(): Int = courts.size

    override fun onBindViewHolder(holder: CourtVH, position: Int) {
        val court = courts[position]
        holder.courtName.text = "Court ${court.courtNumber}"

        val teams = court.teams
        val hasTeams = teams != null

        holder.emptyText.isVisible = !hasTeams
        holder.gridContainer.isVisible = hasTeams
        holder.buttonTeamA.isEnabled = hasTeams
        holder.buttonTeamB.isEnabled = hasTeams

        if (teams != null) {
            // Ensure we have exactly 2 players per team (or show blanks)
            val (teamA, teamB) = teams
            bindPlayerCell(holder.player1, teamA.getOrNull(0))
            bindPlayerCell(holder.player3, teamA.getOrNull(1))
            bindPlayerCell(holder.player2, teamB.getOrNull(0))
            bindPlayerCell(holder.player4, teamB.getOrNull(1))
        } else {
            listOf(holder.player1, holder.player2, holder.player3, holder.player4).forEach {
                bindPlayerCell(it, null)
            }
        }

        holder.buttonTeamA.setOnClickListener {
            val t = courts[position].teams ?: return@setOnClickListener
            onGameFinished(t.first, t.second, position)
        }
        holder.buttonTeamB.setOnClickListener {
            val t = courts[position].teams ?: return@setOnClickListener
            onGameFinished(t.second, t.first, position)
        }
        holder.editBtn.setOnClickListener { onEditCourt(position) }
    }

    private fun bindPlayerCell(textView: TextView, player: Player?) {
        if (player == null) {
            textView.text = "-"
            textView.setBackgroundColor(Color.parseColor("#EEEEEE"))
            textView.setTextColor(Color.BLACK)
            return
        }
        textView.text = player.name
        val (bg, fg) = getPlayerColor(player)
        textView.setBackgroundColor(bg)
        textView.setTextColor(fg)
    }

    /**
     * Returns Pair(backgroundColor, foregroundColor)
     */
    private fun getPlayerColor(p: Player): Pair<Int, Int> {
        val neutral = Pair(Color.parseColor("#E0E0E0"), Color.BLACK)
        if (p.gamesPlayed < 10) return neutral

        val wr = p.winrate
        return when {
            wr < 0.40 -> Pair(Color.parseColor("#FFCDD2"), Color.BLACK)       // light red
            wr < 0.50 -> Pair(Color.parseColor("#FFE0B2"), Color.BLACK)       // amber
            wr < 0.60 -> Pair(Color.parseColor("#C8E6C9"), Color.BLACK)       // soft green
            wr < 0.70 -> Pair(Color.parseColor("#A5D6A7"), Color.BLACK)       // medium green
            else -> Pair(Color.parseColor("#81C784"), Color.BLACK)            // deeper green
        }
    }
}