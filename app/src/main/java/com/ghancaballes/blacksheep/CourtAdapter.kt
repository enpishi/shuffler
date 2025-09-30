package com.ghancaballes.blacksheep

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    private val RecyclerView.ViewHolder.safePos: Int
        get() = if (adapterPosition != RecyclerView.NO_POSITION) adapterPosition else RecyclerView.NO_POSITION

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

            holder.buttonA.setOnClickListener {
                val idx = holder.safePos
                if (idx == RecyclerView.NO_POSITION) return@setOnClickListener
                showConfirmWinnerSheet(
                    holder.itemView,
                    courtIndex = idx,
                    courtNumber = court.courtNumber,
                    winners = teamA,
                    losers = teamB,
                    winningLabel = "Team A"
                ) {
                    onGameFinished(teamA, teamB, idx)
                }
            }

            holder.buttonB.setOnClickListener {
                val idx = holder.safePos
                if (idx == RecyclerView.NO_POSITION) return@setOnClickListener
                showConfirmWinnerSheet(
                    holder.itemView,
                    courtIndex = idx,
                    courtNumber = court.courtNumber,
                    winners = teamB,
                    losers = teamA,
                    winningLabel = "Team B"
                ) {
                    onGameFinished(teamB, teamA, idx)
                }
            }
        } else {
            listOf(holder.player1, holder.player2, holder.player3, holder.player4).forEach {
                bindPlayer(it, null)
            }
            holder.buttonA.setOnClickListener(null)
            holder.buttonB.setOnClickListener(null)
        }

        holder.editBtn.setOnClickListener {
            val idx = holder.safePos
            if (idx != RecyclerView.NO_POSITION) onEditCourt(idx)
        }
    }

    private fun showConfirmWinnerSheet(
        anchorView: View,
        courtIndex: Int,
        courtNumber: Int,
        winners: List<Player>,
        losers: List<Player>,
        winningLabel: String,
        onConfirm: () -> Unit
    ) {
        val ctx = anchorView.context
        val dialog = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_confirm_winner, null)

        val title = view.findViewById<TextView>(R.id.textConfirmTitle)
        val courtLabel = view.findViewById<TextView>(R.id.textCourtLabel)
        val winnersCol = view.findViewById<LinearLayout>(R.id.listWinners)
        val losersCol = view.findViewById<LinearLayout>(R.id.listLosers)
        val btnCancel = view.findViewById<TextView>(R.id.buttonCancel)
        val btnRecord = view.findViewById<TextView>(R.id.buttonRecord)


        title.text = "Confirm $winningLabel Win"
        courtLabel.text = "Court $courtNumber"

        winners.forEach { addPlayerRow(ctx, winnersCol, it, isWinner = true) }
        losers.forEach { addPlayerRow(ctx, losersCol, it, isWinner = false) }

        // (Optional) disable button for 500ms to prevent accidental double tap
        btnRecord.isEnabled = false
        btnRecord.postDelayed({ btnRecord.isEnabled = true }, 500)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnRecord.setOnClickListener {
            // (Optional) capture scores here if you extend schema
            // val wScore = editWinnerScore.text.toString().toIntOrNull()
            // val lScore = editLoserScore.text.toString().toIntOrNull()
            dialog.dismiss()
            onConfirm()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun addPlayerRow(
        context: android.content.Context,
        parent: LinearLayout,
        player: Player,
        isWinner: Boolean
    ) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_confirm_player_line, parent, false)

        val name = row.findViewById<TextView>(R.id.textPlayerName)
        val meta = row.findViewById<TextView>(R.id.textMeta)

        val streak = winStreakProvider?.invoke(player) ?: 0
        val flame = if (streak >= 3) " 🔥" else ""
        name.text = player.name + flame

        // Meta: overall record + winrate
        val games = player.gamesPlayed
        val wrPct = if (games > 0) (player.winrate * 100).toInt() else 0
        meta.text = "${player.wins}/${player.losses}  ${wrPct}%"

        // Background tint using your palette
        val (bg, fg) = colorForWinrate(player)
        row.setBackgroundColor(bg.adjustAlpha(if (isWinner) 1.0f else 0.82f))
        name.setTextColor(fg)
        meta.setTextColor(fg.adjustForMeta())

        parent.addView(row)
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

    // New color scheme mapping
    private fun colorForWinrate(p: Player): Pair<Int, Int> {
        if (p.gamesPlayed < 10) return Pair(Color.parseColor("#9E9E9E"), Color.WHITE)
        val wr = p.winrate
        return when {
            wr < 0.40 -> Pair(Color.parseColor("#C8E6C9"), Color.BLACK)      // Light Green
            wr < 0.50 -> Pair(Color.parseColor("#66BB6A"), Color.BLACK)      // Mid Green
            wr < 0.60 -> Pair(Color.parseColor("#2E7D32"), Color.WHITE)      // Dark Green
            wr < 0.70 -> Pair(Color.parseColor("#EF6C00"), Color.WHITE)      // Dark Orange
            wr < 0.80 -> Pair(Color.parseColor("#AD1457"), Color.WHITE)      // Dark Pink
            else -> Pair(Color.parseColor("#B71C1C"), Color.WHITE)           // Very Dark Red
        }
    }

    // Utility extensions
    private fun Int.adjustAlpha(factor: Float): Int {
        val a = (Color.alpha(this) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun Int.adjustForMeta(): Int {
        // Slightly dim meta text if foreground is very bright
        val r = Color.red(this)
        val g = Color.green(this)
        val b = Color.blue(this)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return if (luminance > 180) Color.parseColor("#444444") else this
    }
}