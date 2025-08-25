package com.ghancaballes.blacksheep

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import java.util.LinkedList
import java.util.LinkedHashSet

class PlayerManagementActivity : AppCompatActivity() {

    // Firebase
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var playersCollection: CollectionReference
    private lateinit var uid: String

    // Optional sync banner
    private var syncBanner: TextView? = null

    // Session + matches
    private var sessionId: String? = null
    private var matchesListener: ListenerRegistration? = null
    private val courtMatchSeq = mutableMapOf<Int, Int>()

    // UI: Setup
    private lateinit var titleTextView: TextView
    private lateinit var setupContainer: LinearLayout
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var courtCountEditText: EditText
    private lateinit var startSessionButton: Button

    // UI: Game
    private lateinit var restingPlayersTextView: TextView
    private lateinit var courtsRecyclerView: RecyclerView
    private lateinit var gameActionButtons: LinearLayout
    private lateinit var addLatePlayerButton: Button
    private lateinit var addCourtButton: Button
    private lateinit var endSessionButton: Button
    private lateinit var sitOutButton: MaterialButton
    private lateinit var restoreButton: MaterialButton

    // Adapters
    private lateinit var playerSelectionAdapter: PlayerSelectionAdapter
    private lateinit var courtAdapter: CourtAdapter

    // State
    private val allPlayers = mutableListOf<Player>()
    private val initialSelectedPlayers = mutableSetOf<Player>()
    private val currentCourts = mutableListOf<Court>()
    private var restingPlayers = LinkedList<Player>()

    // Active session players
    private val sessionPlayerIds = LinkedHashSet<String>()

    data class SessionStats(var games: Int = 0, var wins: Int = 0, var losses: Int = 0)
    private val sessionStats = mutableMapOf<String, SessionStats>()

    // Legacy / pairing memory
    private val partnershipHistory = mutableMapOf<String, Int>()
    private val recentOpponents = mutableMapOf<String, Set<String>>()
    private val partnerCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val opponentCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val restCount = mutableMapOf<String, Int>()
    private val lastCourtGroupIds = mutableMapOf<Int, Set<String>>()
    private val lastQuartetByPlayer = mutableMapOf<String, Set<String>>()

    // Penalties
    private val ALPHA_PARTNER = 1.0
    private val BETA_OPPONENT = 0.5

    // Special rule
    private val SPECIAL_DESIRED_RATIO = 0.60
    private val SPECIAL_WEIGHT = 2.0
    private var specialTogetherCount = 0
    private var specialTeammateCount = 0

    // New: sitting out
    private val sittingOutIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_management)

        db = Firebase.firestore
        auth = Firebase.auth
        val current = auth.currentUser?.uid
        if (current == null) {
            Toast.makeText(this, "No authenticated user. Please login again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uid = current
        playersCollection = db.collection("users").document(uid).collection("players")
        Log.d("PlayersPath", "Using players collection: ${playersCollection.path}")

        initializeUI()
        initializeAdapters()
        setClickListeners()
        listenForPlayerUpdates()
        switchToSetupView()
    }

    private fun initializeUI() {
        titleTextView = findViewById(R.id.textViewTitle)
        syncBanner = findViewById(R.id.syncBanner)

        setupContainer = findViewById(R.id.setupContainer)
        playersRecyclerView = findViewById(R.id.recyclerViewPlayers)
        courtCountEditText = findViewById(R.id.editTextCourtCount)
        startSessionButton = findViewById(R.id.buttonStartSession)

        restingPlayersTextView = findViewById(R.id.textViewRestingPlayers)
        courtsRecyclerView = findViewById(R.id.recyclerViewCourts)
        gameActionButtons = findViewById(R.id.gameActionButtons)
        addLatePlayerButton = findViewById(R.id.buttonAddLatePlayer)
        addCourtButton = findViewById(R.id.buttonAddCourt)
        endSessionButton = findViewById(R.id.buttonEndSession)

        sitOutButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "SIT OUT"
            isAllCaps = true
        }
        restoreButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "RESTORE"
            isAllCaps = true
        }

        playersRecyclerView.layoutManager = LinearLayoutManager(this)
        courtsRecyclerView.layoutManager = LinearLayoutManager(this)

        rebuildActionButtonsGrid()
    }

    private fun rebuildActionButtonsGrid() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        gameActionButtons.removeAllViews()
        gameActionButtons.setPadding(0)

        val statsButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "STATS"
            isAllCaps = true
            setOnClickListener { showStatsDialog() }
        }
        addLatePlayerButton.text = "ADD PLAYER"
        addCourtButton.text = "ADD COURT"
        endSessionButton.text = "END SESSION"

        val minPx = dp(48)
        fun styleUniform(b: View) {
            if (b is TextView) b.isAllCaps = true
            b.minimumHeight = minPx
            b.setPadding(0)
            if (b is MaterialButton) {
                b.shapeAppearanceModel = b.shapeAppearanceModel.toBuilder().setAllCornerSizes(0f).build()
                b.setInsetTop(0)
                b.setInsetBottom(0)
                b.strokeWidth = 0
                b.iconPadding = 0
            }
        }

        val allButtons = listOf<View>(
            statsButton,
            addLatePlayerButton,
            addCourtButton,
            endSessionButton,
            sitOutButton,
            restoreButton
        )
        allButtons.forEach { styleUniform(it) }

        val table = TableLayout(this)
        fun makeRow(left: View, right: View): TableRow {
            val row = TableRow(this)
            val lp = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(left, lp)
            row.addView(right, lp)
            return row
        }
        table.addView(makeRow(statsButton, addLatePlayerButton))
        table.addView(makeRow(addCourtButton, endSessionButton))
        table.addView(makeRow(sitOutButton, restoreButton))

        gameActionButtons.addView(
            table,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun initializeAdapters() {
        playerSelectionAdapter = PlayerSelectionAdapter(allPlayers, initialSelectedPlayers) { player, isSelected ->
            if (isSelected) initialSelectedPlayers.add(player) else initialSelectedPlayers.remove(player)
        }
        courtAdapter = CourtAdapter(currentCourts, this::handleGameFinished, this::showEditCourtDialog)
        playersRecyclerView.adapter = playerSelectionAdapter
        courtsRecyclerView.adapter = courtAdapter
    }

    private fun setClickListeners() {
        val addPlayerButton = findViewById<Button>(R.id.buttonAddPlayer)
        val playerNameEditText = findViewById<EditText>(R.id.editTextPlayerName)
        addPlayerButton.setOnClickListener {
            val playerName = playerNameEditText.text.toString().trim()
            if (playerName.isNotEmpty()) {
                addPlayerToFirestore(playerName)
                playerNameEditText.text.clear()
            }
        }

        startSessionButton.setOnClickListener { startSession() }
        addLatePlayerButton.setOnClickListener { showAddLatePlayerDialog() }
        addCourtButton.setOnClickListener { addCourt() }
        endSessionButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End Session")
                .setMessage("Are you sure you want to end the current session? All court progress will be lost.")
                .setPositiveButton("End Session") { _, _ ->
                    switchToSetupView()
                    Toast.makeText(this, "Session Ended", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null)
                .show()
        }
        sitOutButton.setOnClickListener { showSitOutFlow() }
        restoreButton.setOnClickListener { showRestoreDialog() }
    }

    private fun listenForPlayerUpdates() {
        playersCollection.orderBy("name").addSnapshotListener { snapshots, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed.", e)
                return@addSnapshotListener
            }
            val currentSelectedIds = initialSelectedPlayers.map { it.id }.toSet()
            allPlayers.clear()
            initialSelectedPlayers.clear()
            snapshots?.forEach { doc ->
                val p = doc.toObject(Player::class.java).copy(id = doc.id)
                allPlayers.add(p)
                if (currentSelectedIds.contains(p.id)) initialSelectedPlayers.add(p)
            }
            if (setupContainer.visibility == View.VISIBLE) {
                playerSelectionAdapter.notifyDataSetChanged()
            } else {
                courtAdapter.notifyDataSetChanged()
                updateRestingPlayersView()
            }
        }
    }

    private fun switchToSetupView() {
        titleTextView.text = "Session Setup"
        setupContainer.visibility = View.VISIBLE
        startSessionButton.visibility = View.VISIBLE
        restingPlayersTextView.visibility = View.GONE
        courtsRecyclerView.visibility = View.GONE
        gameActionButtons.visibility = View.GONE

        detachMatchesListener()
        sessionId = null
        courtMatchSeq.clear()

        currentCourts.clear()
        restingPlayers.clear()
        initialSelectedPlayers.clear()
        sessionPlayerIds.clear()
        sessionStats.clear()
        partnershipHistory.clear()
        recentOpponents.clear()
        partnerCount.clear()
        opponentCount.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        restCount.clear()
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        sittingOutIds.clear()

        playerSelectionAdapter.notifyDataSetChanged()
        syncBanner?.visibility = View.GONE
    }

    private fun switchToGameView() {
        titleTextView.text = "Active Courts"
        setupContainer.visibility = View.GONE
        startSessionButton.visibility = View.GONE
        restingPlayersTextView.visibility = View.VISIBLE
        courtsRecyclerView.visibility = View.VISIBLE
        gameActionButtons.visibility = View.VISIBLE
        courtAdapter.notifyDataSetChanged()
        updateRestingPlayersView()
    }

    private fun startSession() {
        val selectedIds = initialSelectedPlayers.map { it.id }.toSet()
        val playersForSession = allPlayers.filter { it.id in selectedIds }

        val courtCount = courtCountEditText.text.toString().toIntOrNull() ?: 0
        if (courtCount <= 0) {
            Toast.makeText(this, "Please enter a valid number of courts.", Toast.LENGTH_SHORT).show()
            return
        }
        val needed = courtCount * 4
        if (playersForSession.size < needed) {
            Toast.makeText(
                this,
                "Not enough players for $courtCount court(s). Need $needed, selected ${playersForSession.size}.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        sessionId = System.currentTimeMillis().toString()
        courtMatchSeq.clear()
        attachMatchesListener()

        val pool = playersForSession.shuffled().toMutableList()

        restCount.clear()
        sessionPlayerIds.clear()
        sessionStats.clear()
        sittingOutIds.clear()
        playersForSession.forEach {
            restCount[it.id] = 0
            sessionPlayerIds.add(it.id)
            sessionStats[it.id] = SessionStats()
        }
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()

        currentCourts.clear()
        for (i in 1..courtCount) {
            if (pool.size < 4) break
            val courtPlayers = popRandom(pool, 4)
            val teams = generateTeamsForCourt(courtPlayers)
            currentCourts.add(Court(teams, i))
        }
        restingPlayers = LinkedList(pool)
        switchToGameView()
    }

    private fun recordMatchAndUpdatePlayerStats(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        val currentSession = sessionId ?: return
        val courtNumber = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
        val nextSeq = (courtMatchSeq[courtIndex] ?: 0) + 1
        courtMatchSeq[courtIndex] = nextSeq
        val matchId = "court${courtNumber}_seq$nextSeq"

        val matchesRef = db.collection("users").document(uid)
            .collection("sessions").document(currentSession)
            .collection("matches")
        val matchRef = matchesRef.document(matchId)

        db.runTransaction { tx ->
            val snap = tx.get(matchRef)
            if (snap.exists()) return@runTransaction null

            val gamePlayers = winners + losers
            val playerSnaps = gamePlayers.associateWith { p -> tx.get(playersCollection.document(p.id)) }
            playerSnaps.forEach { (p, s) ->
                if (!s.exists()) throw Exception("Player doc missing for ${p.name}")
            }

            tx.set(
                matchRef, mapOf(
                    "sessionId" to currentSession,
                    "matchId" to matchId,
                    "courtNumber" to courtNumber,
                    "winners" to winners.map { it.id },
                    "losers" to losers.map { it.id },
                    "createdAt" to FieldValue.serverTimestamp()
                ), SetOptions.merge()
            )

            playerSnaps.forEach { (p, s) ->
                val wins = s.getLong("wins") ?: 0L
                val losses = s.getLong("losses") ?: 0L
                val won = winners.any { it.id == p.id }
                val newWins = if (won) wins + 1 else wins
                val newLosses = if (won) losses else losses + 1
                val games = newWins + newLosses
                val winrate = if (games > 0) newWins.toDouble() / games else 0.0
                tx.update(
                    s.reference,
                    mapOf(
                        "wins" to newWins,
                        "losses" to newLosses,
                        "gamesPlayed" to games,
                        "winrate" to winrate,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            null
        }.addOnSuccessListener {
            Toast.makeText(
                this,
                "Recorded: Court $courtNumber — Winners: ${winners.joinToString { it.name }}",
                Toast.LENGTH_SHORT
            ).show()
            courtAdapter.notifyItemChanged(courtIndex)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to record match: ${it.message}", Toast.LENGTH_LONG).show()
            courtAdapter.notifyItemChanged(courtIndex)
        }
    }

    private fun handleGameFinished(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        recordMatchAndUpdatePlayerStats(winners, losers, courtIndex)

        (winners + losers).forEach {
            sessionPlayerIds.add(it.id)
            val s = sessionStats.getOrPut(it.id) { SessionStats() }
            s.games += 1
        }
        winners.forEach { sessionStats.getOrPut(it.id) { SessionStats() }.wins += 1 }
        losers.forEach { sessionStats.getOrPut(it.id) { SessionStats() }.losses += 1 }

        val winKey = winners.map { it.name }.sorted().joinToString("|")
        val loseKey = losers.map { it.name }.sorted().joinToString("|")
        partnershipHistory[winKey] = partnershipHistory.getOrDefault(winKey, 0) + 1
        partnershipHistory[loseKey] = partnershipHistory.getOrDefault(loseKey, 0) + 1

        updatePairingStats(winners, losers)
        updateSpecialPairStats(winners, losers)

        currentCourts[courtIndex].teams = null
        val courtNumber = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
        val lastFour = winners + losers
        lastCourtGroupIds[courtNumber] = lastFour.map { it.id }.toSet()
        val idList = lastFour.map { it.id }
        for (p in lastFour) lastQuartetByPlayer[p.id] = idList.filter { it != p.id }.toSet()

        lastFour.forEach { restCount[it.id] = 0 }
        restingPlayers.addAll(lastFour)

        refillEmptyCourts()
        restingPlayers.forEach { restCount[it.id] = (restCount[it.id] ?: 0) + 1 }
        updateRestingPlayersView()
    }

    private fun refillEmptyCourts() {
        for (court in currentCourts) {
            if (court.teams == null && restingPlayers.size >= 4) {
                val avoid = lastCourtGroupIds[court.courtNumber]
                val newPlayers = drawFairFromRestingQueueAvoid(4, avoid)
                court.teams = generateTeamsForCourt(newPlayers)
            }
        }
        courtAdapter.notifyDataSetChanged()
    }

    private fun updateRestingPlayersView() {
        val restingNames = if (restingPlayers.isEmpty()) "None" else restingPlayers.joinToString(", ") { it.name }
        val sittingNames = if (sittingOutIds.isEmpty()) ""
        else " | Sitting Out: " + sittingOutIds.mapNotNull { playerById(it)?.name }.sorted().joinToString(", ")
        restingPlayersTextView.text = "Resting: $restingNames$sittingNames"
        addCourtButton.isEnabled = restingPlayers.size >= 4
    }

    private fun addPlayerToFirestore(playerName: String) {
        val normalizedName = playerName.lowercase()
        val ref = playersCollection.document(normalizedName)
        ref.get().addOnSuccessListener {
            if (!it.exists()) {
                val p = Player(name = playerName, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
                ref.set(p)
                    .addOnSuccessListener { Toast.makeText(this, "$playerName added.", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(this, "$playerName already exists.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error checking player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Ratings & pairing counters
    private fun rating(p: Player): Double = if (p.gamesPlayed < 10) 0.5 else p.winrate
    private fun getPartnerCount(a: String, b: String): Int {
        if (a == b) return 0
        val (x, y) = if (a < b) a to b else b to a
        return partnerCount[x]?.get(y) ?: 0
    }
    private fun getOpponentCount(a: String, b: String): Int {
        if (a == b) return 0
        val (x, y) = if (a < b) a to b else b to a
        return opponentCount[x]?.get(y) ?: 0
    }
    private fun incPartnerCountPair(a: String, b: String) {
        if (a == b) return
        val (x, y) = if (a < b) a to b else b to a
        val inner = partnerCount.getOrPut(x) { mutableMapOf() }
        inner[y] = (inner[y] ?: 0) + 1
    }
    private fun incOpponentCountPair(a: String, b: String) {
        if (a == b) return
        val (x, y) = if (a < b) a to b else b to a
        val inner = opponentCount.getOrPut(x) { mutableMapOf() }
        inner[y] = (inner[y] ?: 0) + 1
    }
    private fun updatePairingStats(winners: List<Player>, losers: List<Player>) {
        if (winners.size == 2) incPartnerCountPair(winners[0].id, winners[1].id)
        if (losers.size == 2) incPartnerCountPair(losers[0].id, losers[1].id)
        for (w in winners) for (l in losers) incOpponentCountPair(w.id, l.id)
    }

    // Special rule
    private fun isChad(p: Player) = p.name.equals("chad", ignoreCase = true)
    private fun isBudong(p: Player) = p.name.equals("budong", ignoreCase = true)
    private fun updateSpecialPairStats(winners: List<Player>, losers: List<Player>) {
        val all = winners + losers
        val hasChad = all.any { isChad(it) }
        val hasBudong = all.any { isBudong(it) }
        if (!hasChad || !hasBudong) return
        specialTogetherCount += 1
        val teammates = (winners.any { isChad(it) } && winners.any { isBudong(it) }) ||
                (losers.any { isChad(it) } && losers.any { isBudong(it) })
        if (teammates) specialTeammateCount += 1
    }
    private fun specialBiasCost(teamA: List<Player>, teamB: List<Player>, four: List<Player>): Double {
        val hasChad = four.any { isChad(it) }
        val hasBudong = four.any { isBudong(it) }
        if (!hasChad || !hasBudong) return 0.0
        val teammatesNow = (teamA.any { isChad(it) } && teamA.any { isBudong(it) }) ||
                (teamB.any { isChad(it) } && teamB.any { isBudong(it) })
        val together = specialTogetherCount
        val teamed = specialTeammateCount
        val currentRatio = if (together > 0) teamed.toDouble() / together else 0.0
        val delta = SPECIAL_DESIRED_RATIO - currentRatio
        val sign = if (teammatesNow) -1.0 else 1.0
        return sign * SPECIAL_WEIGHT * delta
    }
    private fun pairingCost(teamA: List<Player>, teamB: List<Player>): Double {
        val avgA = teamA.map { rating(it) }.average()
        val avgB = teamB.map { rating(it) }.average()
        val balanceCost = abs(avgA - avgB)
        val partnerPenalty = getPartnerCount(teamA[0].id, teamA[1].id) + getPartnerCount(teamB[0].id, teamB[1].id)
        var opponentPenalty = 0
        for (a in teamA) for (b in teamB) opponentPenalty += getOpponentCount(a.id, b.id)
        return balanceCost + ALPHA_PARTNER * partnerPenalty + BETA_OPPONENT * opponentPenalty
    }
    private fun chooseBestPairingOfFour(players: List<Player>): Pair<List<Player>, List<Player>> {
        require(players.size == 4)
        val (A, B, C, D) = players
        val options = listOf(
            Pair(listOf(A, B), listOf(C, D)),
            Pair(listOf(A, C), listOf(B, D)),
            Pair(listOf(A, D), listOf(B, C))
        )
        var best = options.first()
        var bestCost = pairingCost(best.first, best.second) + specialBiasCost(best.first, best.second, players)
        for (opt in options.drop(1)) {
            val cost = pairingCost(opt.first, opt.second) + specialBiasCost(opt.first, opt.second, players)
            if (cost < bestCost) {
                best = opt
                bestCost = cost
            }
        }
        return Pair(best.first.shuffled(), best.second.shuffled())
    }
    private fun generateTeamsForCourt(players: List<Player>): Pair<List<Player>, List<Player>>? {
        if (players.size != 4) return null
        return chooseBestPairingOfFour(players)
    }

    // Random selection
    private fun popRandom(list: MutableList<Player>, count: Int): List<Player> {
        val c = min(count, list.size)
        val picked = mutableListOf<Player>()
        repeat(c) {
            val idx = Random.nextInt(list.size)
            picked += list.removeAt(idx)
        }
        return picked
    }

    private fun drawFairFromRestingQueueAvoid(n: Int, avoidGroup: Set<String>?): List<Player> {
        if (restingPlayers.isEmpty() || n <= 0) return emptyList()
        val indexed = restingPlayers.mapIndexed { idx, p -> Triple(idx, p, restCount[p.id] ?: 0) }
        val withRest = indexed.filter { it.third > 0 }
        val basePool = if (withRest.size >= n) withRest else indexed
        val pre = basePool.shuffled()
        val sorted = pre.sortedWith(compareByDescending<Triple<Int, Player, Int>> { it.third })
        val K = min(8, sorted.size)
        val cands = sorted.take(K)
        var best: List<Triple<Int, Player, Int>>? = null
        var bestScore = -1
        val S = cands.size
        if (S >= n) {
            for (i in 0 until S - 3)
                for (j in i + 1 until S - 2)
                    for (k in j + 1 until S - 1)
                        for (l in k + 1 until S) {
                            val picks = listOf(cands[i], cands[j], cands[k], cands[l])
                            val idsSet = picks.map { it.second.id }.toSet()
                            if (avoidGroup != null && idsSet == avoidGroup) continue
                            var ok = true
                            for (t in picks) {
                                val pid = t.second.id
                                val last = lastQuartetByPlayer[pid]
                                if (last != null) {
                                    val others = idsSet - pid
                                    if (others == last) {
                                        ok = false
                                        break
                                    }
                                }
                            }
                            if (!ok) continue
                            val score = picks.sumOf { it.third }
                            if (score > bestScore) {
                                best = picks
                                bestScore = score
                            }
                        }
        }
        val pickedTriples = best ?: run {
            val tmp = sorted.take(min(n, sorted.size)).toMutableList()
            if (avoidGroup != null && tmp.map { it.second.id }.toSet() == avoidGroup) {
                val replacement = sorted.drop(tmp.size).firstOrNull { it.second.id !in avoidGroup }
                if (replacement != null) tmp[0] = replacement
                else {
                    val swapCandidate = sorted.firstOrNull { it.second.id !in avoidGroup }
                    if (swapCandidate != null) tmp[0] = swapCandidate
                }
            }
            tmp
        }
        val indicesDesc = pickedTriples.map { it.first }.sortedDescending()
        indicesDesc.forEach { restingPlayers.removeAt(it) }
        val picked = pickedTriples.map { it.second }
        picked.forEach { restCount[it.id] = 0 }
        return picked
    }

    // Stats
    private data class StatsRow(
        val name: String,
        val currentG: Int, val currentW: Int, val currentL: Int,
        val overallG: Int, val overallW: Int, val overallL: Int,
        val overallWinPct: Int
    )

    private fun buildStatsRows(): List<StatsRow> {
        val byId = allPlayers.associateBy { it.id }
        return sessionPlayerIds.mapNotNull { id ->
            val p = byId[id] ?: return@mapNotNull null
            val cur = sessionStats[id] ?: SessionStats()
            val g = p.gamesPlayed
            val w = p.wins
            val pct = if (g > 0) ((w.toDouble() / g) * 100).toInt() else 0
            StatsRow(
                name = p.name,
                currentG = cur.games, currentW = cur.wins, currentL = cur.losses,
                overallG = g, overallW = w, overallL = p.losses,
                overallWinPct = pct
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun showStatsDialog() {
        val rows = buildStatsRows()
        val hScroll = HorizontalScrollView(this)
        val vScroll = ScrollView(this).apply { isFillViewport = true }
        val table = buildStatsTable(rows)
        vScroll.addView(table, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        hScroll.addView(vScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        AlertDialog.Builder(this)
            .setTitle("Session Stats")
            .setView(hScroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildStatsTable(rows: List<StatsRow>): TableLayout {
        val density = resources.displayMetrics.density
        fun dp(px: Int) = (px * density).toInt()
        val NAME_COL_WIDTH_DP = 112

        fun headerCell(text: String, span: Int = 1, center: Boolean = true, bold: Boolean = true, sizeSp: Float = 13f) =
            TextView(this).apply {
                this.text = text
                textSize = sizeSp
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                gravity = if (center) android.view.Gravity.CENTER else android.view.Gravity.START
            }.also {
                val lp = TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                )
                lp.span = span
                it.layoutParams = lp
            }

        fun headerNameCell(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = android.view.Gravity.START
            minWidth = dp(NAME_COL_WIDTH_DP)
            maxWidth = dp(NAME_COL_WIDTH_DP)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        fun bodyCell(text: String, center: Boolean = true) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = if (center) android.view.Gravity.CENTER else android.view.Gravity.START
        }
        fun nameCell(text: String) = bodyCell(text, center = false).apply {
            minWidth = dp(NAME_COL_WIDTH_DP)
            maxWidth = dp(NAME_COL_WIDTH_DP)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun vSep() = View(this).apply {
            layoutParams = TableRow.LayoutParams(dp(1), TableRow.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
            setBackgroundColor(0xFFDDDDDD.toInt())
        }

        val table = TableLayout(this).apply {
            setPadding(dp(4))
            isShrinkAllColumns = false
            isStretchAllColumns = false
        }
        val hdr1 = TableRow(this)
        hdr1.addView(headerNameCell("Name"))
        hdr1.addView(vSep())
        hdr1.addView(headerCell("Current", span = 3))
        hdr1.addView(vSep())
        hdr1.addView(headerCell("Overall", span = 4))
        table.addView(hdr1)

        val hdr2 = TableRow(this)
        hdr2.addView(headerCell("", span = 1, center = false, bold = false))
        hdr2.addView(vSep())
        listOf("G", "W", "L").forEach { hdr2.addView(headerCell(it)) }
        hdr2.addView(vSep())
        listOf("G", "W", "L", "Win%").forEach { hdr2.addView(headerCell(it)) }
        table.addView(hdr2)

        rows.forEach { r ->
            val tr = TableRow(this)
            tr.addView(nameCell(r.name))
            tr.addView(vSep())
            tr.addView(bodyCell(r.currentG.toString()))
            tr.addView(bodyCell(r.currentW.toString()))
            tr.addView(bodyCell(r.currentL.toString()))
            tr.addView(vSep())
            tr.addView(bodyCell(r.overallG.toString()))
            tr.addView(bodyCell(r.overallW.toString()))
            tr.addView(bodyCell(r.overallL.toString()))
            tr.addView(bodyCell("${r.overallWinPct}%"))
            table.addView(tr)
        }
        return table
    }

    private fun attachMatchesListener() {
        val currentSession = sessionId ?: return
        detachMatchesListener()
        matchesListener = db.collection("users").document(uid)
            .collection("sessions").document(currentSession)
            .collection("matches")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    syncBanner?.apply {
                        visibility = View.VISIBLE
                        text = "Sync error: ${err.code}"
                    }
                    return@addSnapshotListener
                }
                val hasPending = snap?.metadata?.hasPendingWrites() == true
                syncBanner?.apply {
                    visibility = if (hasPending) View.VISIBLE else View.GONE
                    if (hasPending) text = "Syncing… (offline or pending writes)"
                }
            }
    }

    private fun detachMatchesListener() {
        matchesListener?.remove()
        matchesListener = null
    }

    // Helper
    private fun playerById(id: String): Player? = allPlayers.firstOrNull { it.id == id }

    // --- New sit-out flow (single selection with replacement if needed) ---
    private fun showSitOutFlow() {
        // Candidates: anyone not already sitting out and who is either resting or currently on a court
        val onCourts = currentCourts.flatMap { court ->
            val t = court.teams
            if (t == null) emptyList() else t.first + t.second
        }.filter { it.id !in sittingOutIds }

        val resting = restingPlayers.filter { it.id !in sittingOutIds }
        val candidates = (onCourts + resting)
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "No eligible players to sit out.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = candidates.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Player to Sit Out")
            .setItems(names) { dialog, which ->
                val selected = candidates[which]
                dialog.dismiss()
                val courtLoc = locatePlayerOnCourt(selected.id)
                if (courtLoc == null) {
                    // Player not currently playing (resting) -> move directly to sitting out
                    val removed = restingPlayers.removeIf { it.id == selected.id }
                    if (removed) {
                        sittingOutIds.add(selected.id)
                        Toast.makeText(this, "${selected.name} is now sitting out.", Toast.LENGTH_SHORT).show()
                        updateRestingPlayersView()
                    } else {
                        Toast.makeText(this, "Player state changed; try again.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Player is on a court -> need replacement
                    promptReplacementForActivePlayer(selected, courtLoc)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class CourtLocation(val courtIndex: Int, val teamIndex: Int, val posInTeam: Int)

    private fun locatePlayerOnCourt(playerId: String): CourtLocation? {
        currentCourts.forEachIndexed { ci, court ->
            val teams = court.teams ?: return@forEachIndexed
            val aIdx = teams.first.indexOfFirst { it.id == playerId }
            if (aIdx >= 0) return CourtLocation(ci, 0, aIdx)
            val bIdx = teams.second.indexOfFirst { it.id == playerId }
            if (bIdx >= 0) return CourtLocation(ci, 1, bIdx)
        }
        return null
    }

    private fun promptReplacementForActivePlayer(player: Player, loc: CourtLocation) {
        if (restingPlayers.isEmpty()) {
            Toast.makeText(this, "No resting players available to replace ${player.name}.", Toast.LENGTH_LONG).show()
            return
        }
        val replacements = restingPlayers.toList().sortedBy { it.name.lowercase() }
        val names = replacements.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Replace ${player.name} with:")
            .setItems(names) { d, which ->
                val replacement = replacements[which]
                d.dismiss()
                applyReplacement(player, replacement, loc)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyReplacement(leaving: Player, replacement: Player, loc: CourtLocation) {
        val court = currentCourts.getOrNull(loc.courtIndex)
        val teams = court?.teams
        if (court == null || teams == null) {
            Toast.makeText(this, "Court changed; try again.", Toast.LENGTH_SHORT).show()
            return
        }
        val newA = teams.first.toMutableList()
        val newB = teams.second.toMutableList()
        val targetList = if (loc.teamIndex == 0) newA else newB

        // Validate the slot still has leaving
        if (loc.posInTeam >= targetList.size || targetList[loc.posInTeam].id != leaving.id) {
            Toast.makeText(this, "Player already moved; try again.", Toast.LENGTH_SHORT).show()
            return
        }

        // Execute replacement
        targetList[loc.posInTeam] = replacement
        court.teams = Pair(newA, newB)

        // Remove replacement from resting queue
        restingPlayers.removeIf { it.id == replacement.id }
        restCount[replacement.id] = 0

        // Move leaving to sitting out
        sittingOutIds.add(leaving.id)

        Toast.makeText(
            this,
            "${leaving.name} is now sitting out. Replaced by ${replacement.name}.",
            Toast.LENGTH_SHORT
        ).show()

        courtAdapter.notifyItemChanged(loc.courtIndex)
        updateRestingPlayersView()
    }

    // Restore dialog unchanged (multi-select restore)
    private fun showRestoreDialog() {
        if (sittingOutIds.isEmpty()) {
            Toast.makeText(this, "No players are sitting out.", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = sittingOutIds.mapNotNull { playerById(it) }.sortedBy { it.name.lowercase() }
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_late_player, null)
        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.chipGroupExistingPlayers)
        val newPlayerNameEditText = dialogView.findViewById<EditText>(R.id.editTextNewPlayerName)
        val addNewPlayerButton = dialogView.findViewById<Button>(R.id.buttonAddNewPlayer)
        val existingPlayersTitle = dialogView.findViewById<TextView>(R.id.textViewExistingPlayersTitle)
        newPlayerNameEditText.visibility = View.GONE
        addNewPlayerButton.visibility = View.GONE
        existingPlayersTitle.text = "Select players to Restore"
        candidates.forEach { p ->
            val chip = Chip(this).apply {
                text = p.name
                isCheckable = true
                tag = p
            }
            chipGroup.addView(chip)
        }
        AlertDialog.Builder(this)
            .setTitle("Restore Players")
            .setView(dialogView)
            .setPositiveButton("Restore") { _, _ ->
                val selected = mutableListOf<Player>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i) as Chip
                    if (c.isChecked) selected += (c.tag as Player)
                }
                if (selected.isEmpty()) return@setPositiveButton
                selected.forEach { p ->
                    sittingOutIds.remove(p.id)
                    restCount.putIfAbsent(p.id, 0)
                    restingPlayers.add(p)
                }
                Toast.makeText(
                    this,
                    "Restored: ${selected.joinToString { it.name }}",
                    Toast.LENGTH_SHORT
                ).show()
                updateRestingPlayersView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Existing add-late-player dialog (unchanged except skip sitting out players)
    private fun showAddLatePlayerDialog() {
        val activePlayerIds = mutableSetOf<String>()
        currentCourts.forEach { court ->
            court.teams?.let { (a, b) ->
                a.forEach { activePlayerIds.add(it.id) }
                b.forEach { activePlayerIds.add(it.id) }
            }
        }
        val availablePlayers = allPlayers.filter {
            it.id !in activePlayerIds && it.id !in sittingOutIds
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_late_player, null)
        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.chipGroupExistingPlayers)
        val newPlayerNameEditText = dialogView.findViewById<EditText>(R.id.editTextNewPlayerName)
        val addNewPlayerButton = dialogView.findViewById<Button>(R.id.buttonAddNewPlayer)
        val existingPlayersTitle = dialogView.findViewById<TextView>(R.id.textViewExistingPlayersTitle)

        if (availablePlayers.isEmpty()) {
            existingPlayersTitle.visibility = View.GONE
            chipGroup.visibility = View.GONE
        } else {
            availablePlayers.forEach { player ->
                val chip = Chip(this).apply {
                    text = player.name
                    isCheckable = true
                    tag = player
                }
                chipGroup.addView(chip)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Late Players")
            .setView(dialogView)
            .setPositiveButton("Add Selected") { _, _ ->
                val selectedPlayers = mutableListOf<Player>()
                for (i in 0 until chipGroup.childCount) {
                    val chip = chipGroup.getChildAt(i) as Chip
                    if (chip.isChecked) selectedPlayers.add(chip.tag as Player)
                }
                if (selectedPlayers.isNotEmpty()) {
                    selectedPlayers.forEach {
                        restCount[it.id] = 0
                        sessionPlayerIds.add(it.id)
                        sessionStats.putIfAbsent(it.id, SessionStats())
                    }
                    restingPlayers.addAll(selectedPlayers)
                    updateRestingPlayersView()
                    Toast.makeText(
                        this,
                        selectedPlayers.joinToString(", ") { it.name } + " added to the resting queue.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        addNewPlayerButton.setOnClickListener {
            val playerName = newPlayerNameEditText.text.toString().trim()
            if (playerName.isNotEmpty()) {
                addLatePlayer(playerName) {
                    dialog.dismiss()
                    showAddLatePlayerDialog()
                }
            } else {
                Toast.makeText(this, "Player name cannot be empty.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun addLatePlayer(playerName: String, onSuccess: () -> Unit) {
        val normalizedName = playerName.lowercase()
        val exists = allPlayers.any { it.name.equals(normalizedName, ignoreCase = true) }
        if (exists) {
            Toast.makeText(this, "$playerName already exists.", Toast.LENGTH_SHORT).show()
            return
        }
        val newPlayer = Player(name = playerName, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
        playersCollection.document(normalizedName).set(newPlayer)
            .addOnSuccessListener {
                val withId = newPlayer.copy(id = normalizedName)
                allPlayers.add(withId)
                restingPlayers.add(withId)
                restCount[withId.id] = 0
                sessionPlayerIds.add(withId.id)
                sessionStats.putIfAbsent(withId.id, SessionStats())
                updateRestingPlayersView()
                Toast.makeText(this, "$playerName added and is now resting.", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error adding player: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun addCourt() {
        if (restingPlayers.size >= 4) {
            val players = drawFairFromRestingQueueAvoid(4, null)
            val teams = generateTeamsForCourt(players)
            val newNumber = (currentCourts.maxOfOrNull { it.courtNumber } ?: 0) + 1
            currentCourts.add(Court(teams, newNumber))
            courtAdapter.notifyDataSetChanged()
            updateRestingPlayersView()
            Toast.makeText(this, "Court $newNumber added.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Need at least 4 resting players to add a new court.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditCourtDialog(courtIndex: Int) {
        val court = currentCourts.getOrNull(courtIndex) ?: return
        val (teamA, teamB) = court.teams ?: Pair(emptyList(), emptyList())
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_court, null)
        val rvTeamA = dialogView.findViewById<RecyclerView>(R.id.recyclerViewTeamA)
        val rvTeamB = dialogView.findViewById<RecyclerView>(R.id.recyclerViewTeamB)
        val rvResting = dialogView.findViewById<RecyclerView>(R.id.recyclerViewResting)
        val restingTitle = dialogView.findViewById<TextView>(R.id.textViewRestingTitle)
        val deleteButton = dialogView.findViewById<Button>(R.id.buttonDeleteCourt)

        val teamAMutable = teamA.toMutableList()
        val teamBMutable = teamB.toMutableList()
        val restingMutable = restingPlayers.toMutableList()

        val adapterA = EditCourtPlayerAdapter(teamAMutable)
        val adapterB = EditCourtPlayerAdapter(teamBMutable)
        val adapterResting = EditCourtPlayerAdapter(restingMutable)

        var firstSelection: Player? = null
        var firstList: MutableList<Player>? = null
        val allAdapters = listOf(adapterA, adapterB, adapterResting)

        fun handleSelection(player: Player, list: MutableList<Player>, @Suppress("UNUSED_PARAMETER") clicked: EditCourtPlayerAdapter) {
            if (firstSelection == null) {
                firstSelection = player
                firstList = list
                allAdapters.forEach { it.selectedPlayer = player; it.notifyDataSetChanged() }
            } else {
                if (firstSelection != player) {
                    val p1 = firstSelection!!
                    val list1 = firstList!!
                    val i1 = list1.indexOf(p1)
                    val i2 = list.indexOf(player)
                    if (i1 != -1 && i2 != -1) {
                        list1[i1] = player
                        list[i2] = p1
                    }
                }
                firstSelection = null
                firstList = null
                allAdapters.forEach { it.selectedPlayer = null; it.notifyDataSetChanged() }
            }
        }

        adapterA.onPlayerSelected = { handleSelection(it, teamAMutable, adapterA) }
        rvTeamA.layoutManager = LinearLayoutManager(this)
        rvTeamA.adapter = adapterA

        adapterB.onPlayerSelected = { handleSelection(it, teamBMutable, adapterB) }
        rvTeamB.layoutManager = LinearLayoutManager(this)
        rvTeamB.adapter = adapterB

        if (restingMutable.isEmpty()) {
            restingTitle.visibility = View.GONE
            rvResting.visibility = View.GONE
        } else {
            adapterResting.onPlayerSelected = { handleSelection(it, restingMutable, adapterResting) }
            rvResting.layoutManager = LinearLayoutManager(this)
            rvResting.adapter = adapterResting
        }

        val mainDialog = AlertDialog.Builder(this)
            .setTitle("Edit Court ${court.courtNumber}")
            .setView(dialogView)
            .setPositiveButton("Done") { _, _ ->
                currentCourts[courtIndex].teams =
                    if (teamAMutable.isNotEmpty() || teamBMutable.isNotEmpty()) Pair(teamAMutable, teamBMutable) else null
                restingPlayers = LinkedList(restingMutable)
                restingPlayers.forEach { p ->
                    restCount.putIfAbsent(p.id, 0)
                    sessionPlayerIds.add(p.id)
                    sessionStats.putIfAbsent(p.id, SessionStats())
                }
                courtAdapter.notifyItemChanged(courtIndex)
                updateRestingPlayersView()
            }
            .setNeutralButton("Cancel", null)
            .create()

        deleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Court")
                .setMessage("Delete Court ${court.courtNumber}? Players will move to resting queue.")
                .setPositiveButton("Delete") { _, _ ->
                    val toRest = currentCourts[courtIndex].teams?.toList()?.flatMap { it } ?: emptyList()
                    toRest.forEach {
                        restCount[it.id] = 0
                        sessionPlayerIds.add(it.id)
                        sessionStats.putIfAbsent(it.id, SessionStats())
                    }
                    restingPlayers.addAll(toRest)
                    currentCourts.removeAt(courtIndex)
                    courtAdapter.notifyDataSetChanged()
                    updateRestingPlayersView()
                    mainDialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        mainDialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        detachMatchesListener()
    }
}