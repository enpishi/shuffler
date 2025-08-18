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
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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

    // Optional sync banner (can be null if not in layout)
    private var syncBanner: TextView? = null

    // Session + matches
    private var sessionId: String? = null
    private var matchesListener: ListenerRegistration? = null
    private val courtMatchSeq = mutableMapOf<Int, Int>() // per-court sequence for idempotent match IDs

    // UI: Setup Views
    private lateinit var titleTextView: TextView
    private lateinit var setupContainer: LinearLayout
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var courtCountEditText: EditText
    private lateinit var startSessionButton: Button

    // UI: Game Views
    private lateinit var restingPlayersTextView: TextView
    private lateinit var courtsRecyclerView: RecyclerView
    private lateinit var gameActionButtons: LinearLayout
    private lateinit var addLatePlayerButton: Button
    private lateinit var addCourtButton: Button
    private lateinit var endSessionButton: Button

    // Adapters
    private lateinit var playerSelectionAdapter: PlayerSelectionAdapter
    private lateinit var courtAdapter: CourtAdapter

    // State: players + courts + resting queue
    private val allPlayers = mutableListOf<Player>()
    private val initialSelectedPlayers = mutableSetOf<Player>()
    private val currentCourts = mutableListOf<Court>()
    private var restingPlayers = LinkedList<Player>()

    // Active players in this session (for stats/ordering)
    private val sessionPlayerIds = LinkedHashSet<String>()

    // In-session per-player stats for CURRENT session only
    data class SessionStats(var games: Int = 0, var wins: Int = 0, var losses: Int = 0)
    private val sessionStats = mutableMapOf<String, SessionStats>()

    // Shuffling memory (legacy helpers + counters)
    private val partnershipHistory = mutableMapOf<String, Int>() // legacy, not used in cost
    private val recentOpponents = mutableMapOf<String, Set<String>>() // reserved for future use

    // In-session counters for pairing optimization
    private val partnerCount = mutableMapOf<String, MutableMap<String, Int>>()    // teammates
    private val opponentCount = mutableMapOf<String, MutableMap<String, Int>>()   // opponents

    // Fair-rest scheduling counters (games rested since last play)
    private val restCount = mutableMapOf<String, Int>()

    // NEW: remember the last quartet that played on each court (by courtNumber)
    private val lastCourtGroupIds = mutableMapOf<Int, Set<String>>()

    // NEW: remember for each player the last 3 others they were grouped with
    private val lastQuartetByPlayer = mutableMapOf<String, Set<String>>()

    // Penalty weights (tunable)
    private val ALPHA_PARTNER = 1.0 // avoid repeat teammates
    private val BETA_OPPONENT = 0.5 // avoid repeat opponents

    // Special rule: bias Chad & Budong to be teammates ~3 out of 5 (60%)
    private val SPECIAL_DESIRED_RATIO = 0.60
    private val SPECIAL_WEIGHT = 2.0 // strength of the bias vs balance/penalties
    private var specialTogetherCount = 0
    private var specialTeammateCount = 0

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
        syncBanner = findViewById(R.id.syncBanner) // optional; safe if not in layout

        // Setup views
        setupContainer = findViewById(R.id.setupContainer)
        playersRecyclerView = findViewById(R.id.recyclerViewPlayers)
        courtCountEditText = findViewById(R.id.editTextCourtCount)
        startSessionButton = findViewById(R.id.buttonStartSession)

        // Game views
        restingPlayersTextView = findViewById(R.id.textViewRestingPlayers)
        courtsRecyclerView = findViewById(R.id.recyclerViewCourts)
        gameActionButtons = findViewById(R.id.gameActionButtons)
        addLatePlayerButton = findViewById(R.id.buttonAddLatePlayer)
        addCourtButton = findViewById(R.id.buttonAddCourt)
        endSessionButton = findViewById(R.id.buttonEndSession)

        playersRecyclerView.layoutManager = LinearLayoutManager(this)
        courtsRecyclerView.layoutManager = LinearLayoutManager(this)

        // Build the 2x2 full-bleed action grid
        rebuildActionButtonsGrid()
    }

    // FULL-BLEED 2x2 grid
    private fun rebuildActionButtonsGrid() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        gameActionButtons.removeAllViews()
        gameActionButtons.setPadding(0)

        val statsButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
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
                b.shapeAppearanceModel = b.shapeAppearanceModel
                    .toBuilder()
                    .setAllCornerSizes(0f)
                    .build()
                b.setInsetTop(0)
                b.setInsetBottom(0)
                b.strokeWidth = 0
                b.iconPadding = 0
            }
        }

        val allButtons = listOf<View>(statsButton, addLatePlayerButton, addCourtButton, endSessionButton)
        allButtons.forEach { styleUniform(it) }

        val table = TableLayout(this).apply {
            setPadding(0)
            isShrinkAllColumns = false
            isStretchAllColumns = false
        }

        fun makeRow(left: View, right: View): TableRow {
            val row = TableRow(this).apply { setPadding(0) }
            val lp = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 0, 0)
            }
            row.addView(left, lp)
            row.addView(right, lp)
            return row
        }

        val row1 = makeRow(statsButton, addLatePlayerButton)
        val row2 = makeRow(addCourtButton, endSessionButton)

        table.addView(
            row1,
            TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        )
        table.addView(
            row2,
            TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        )

        gameActionButtons.addView(
            table,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) }
        )
    }

    private fun initializeAdapters() {
        playerSelectionAdapter = PlayerSelectionAdapter(allPlayers, initialSelectedPlayers) { player, isSelected ->
            if (isSelected) {
                initialSelectedPlayers.add(player)
            } else {
                initialSelectedPlayers.remove(player)
            }
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
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun listenForPlayerUpdates() {
        playersCollection.orderBy("name").addSnapshotListener { snapshots, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed.", e)
                return@addSnapshotListener
            }
            val currentlySelectedIds = initialSelectedPlayers.map { it.id }.toSet()
            allPlayers.clear()
            initialSelectedPlayers.clear()

            snapshots?.forEach { doc ->
                val player = doc.toObject(Player::class.java).copy(id = doc.id)
                allPlayers.add(player)
                if (currentlySelectedIds.contains(player.id)) {
                    initialSelectedPlayers.add(player)
                }
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
        val selectedPlayerIds = initialSelectedPlayers.map { it.id }.toSet()
        val playersForSession = allPlayers.filter { it.id in selectedPlayerIds }

        val courtCount = courtCountEditText.text.toString().toIntOrNull() ?: 0
        if (courtCount <= 0) {
            Toast.makeText(this, "Please enter a valid number of courts.", Toast.LENGTH_SHORT).show()
            return
        }
        val requiredPlayers = courtCount * 4
        if (playersForSession.size < requiredPlayers) {
            Toast.makeText(this, "Not enough players for $courtCount court(s). Need $requiredPlayers, selected ${playersForSession.size}.", Toast.LENGTH_LONG).show()
            return
        }

        // Start a new session
        sessionId = System.currentTimeMillis().toString()
        courtMatchSeq.clear()
        attachMatchesListener()

        // Start with a random order
        val playerPool = playersForSession.shuffled().toMutableList()

        // Initialize rest counters and session stats for all selected players
        restCount.clear()
        sessionPlayerIds.clear()
        sessionStats.clear()
        playersForSession.forEach {
            restCount[it.id] = 0
            sessionPlayerIds.add(it.id)
            sessionStats[it.id] = SessionStats(0, 0, 0)
        }
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()

        // Seed courts
        currentCourts.clear()
        for (i in 1..courtCount) {
            if (playerPool.size < 4) break
            val courtPlayers = popRandom(playerPool, 4)
            val teams = generateTeamsForCourt(courtPlayers)
            currentCourts.add(Court(teams, i))
        }

        // Remaining players form the resting queue
        restingPlayers = LinkedList(playerPool)

        switchToGameView()
    }

    // Idempotent, read-before-write match recording with success toast
    private fun recordMatchAndUpdatePlayerStats(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        val currentSession = sessionId
        if (currentSession == null) {
            Log.w("Match", "No active session; cannot record match.")
            return
        }
        val courtNumber = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
        val nextSeq = (courtMatchSeq[courtIndex] ?: 0) + 1
        courtMatchSeq[courtIndex] = nextSeq

        val matchId = "court${courtNumber}_seq$nextSeq"
        val matchesRef = db.collection("users").document(uid)
            .collection("sessions").document(currentSession)
            .collection("matches")
        val matchRef = matchesRef.document(matchId)

        db.runTransaction { tx ->
            val matchSnap = tx.get(matchRef)
            if (matchSnap.exists()) {
                return@runTransaction null
            }

            val allGamePlayers = winners + losers
            val playerSnaps: Map<Player, DocumentSnapshot> = allGamePlayers.associateWith { p ->
                val pref = playersCollection.document(p.id)
                tx.get(pref)
            }

            playerSnaps.forEach { (p, snap) ->
                if (!snap.exists()) {
                    throw Exception("Document for player ${p.name} with ID ${p.id} does not exist!")
                }
            }

            val matchData = mapOf(
                "sessionId" to currentSession,
                "matchId" to matchId,
                "courtNumber" to courtNumber,
                "winners" to winners.map { it.id },
                "losers" to losers.map { it.id },
                "createdAt" to FieldValue.serverTimestamp()
            )
            tx.set(matchRef, matchData, SetOptions.merge())

            playerSnaps.forEach { (p, snap) ->
                val currentWins = snap.getLong("wins") ?: 0L
                val currentLosses = snap.getLong("losses") ?: 0L

                val won = winners.any { it.id == p.id }
                val newWins = if (won) currentWins + 1 else currentWins
                val newLosses = if (won) currentLosses else currentLosses + 1
                val newGamesPlayed = newWins + newLosses
                val newWinRate = if (newGamesPlayed > 0) newWins.toDouble() / newGamesPlayed else 0.0

                tx.update(
                    snap.reference,
                    mapOf(
                        "wins" to newWins,
                        "losses" to newLosses,
                        "gamesPlayed" to newGamesPlayed,
                        "winrate" to newWinRate,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            null
        }.addOnSuccessListener {
            Log.d("PlayerStats", "Match $matchId recorded; player stats updated.")
            val winnerNames = winners.joinToString(", ") { it.name }
            val courtNumber2 = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
            Toast.makeText(this, "Recorded: Court $courtNumber2 — Winners: $winnerNames", Toast.LENGTH_SHORT).show()
            courtAdapter.notifyItemChanged(courtIndex)
        }.addOnFailureListener { e ->
            Log.e("PlayerStats", "Failed to record match $matchId.", e)
            Toast.makeText(this, "Failed to record match: ${e.message}", Toast.LENGTH_LONG).show()
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
        winners.forEach {
            val s = sessionStats.getOrPut(it.id) { SessionStats() }
            s.wins += 1
        }
        losers.forEach {
            val s = sessionStats.getOrPut(it.id) { SessionStats() }
            s.losses += 1
        }

        val winnerKey = winners.map { it.name }.sorted().joinToString("|")
        val loserKey = losers.map { it.name }.sorted().joinToString("|")
        partnershipHistory[winnerKey] = partnershipHistory.getOrDefault(winnerKey, 0) + 1
        partnershipHistory[loserKey] = partnershipHistory.getOrDefault(loserKey, 0) + 1

        updatePairingStats(winners, losers)
        updateSpecialPairStats(winners, losers)

        currentCourts[courtIndex].teams = null

        val courtNumber = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
        val lastFour = (winners + losers)
        lastCourtGroupIds[courtNumber] = lastFour.map { it.id }.toSet()

        val idList = lastFour.map { it.id }
        for (p in lastFour) {
            lastQuartetByPlayer[p.id] = idList.filter { it != p.id }.toSet()
        }

        lastFour.forEach { restCount[it.id] = 0 }
        restingPlayers.addAll(lastFour)

        refillEmptyCourts()

        restingPlayers.forEach { p -> restCount[p.id] = (restCount[p.id] ?: 0) + 1 }

        updateRestingPlayersView()
    }

    private fun refillEmptyCourts() {
        for (court in currentCourts) {
            if (court.teams == null && restingPlayers.size >= 4) {
                val avoid = lastCourtGroupIds[court.courtNumber]
                val newCourtPlayers = drawFairFromRestingQueueAvoid(4, avoid)
                court.teams = generateTeamsForCourt(newCourtPlayers)
            }
        }
        courtAdapter.notifyDataSetChanged()
    }

    private fun updateRestingPlayersView() {
        if (restingPlayers.isEmpty()) {
            restingPlayersTextView.text = "Resting: None"
        } else {
            val restingNames = restingPlayers.joinToString(", ") { it.name }
            restingPlayersTextView.text = "Resting: $restingNames"
        }
        addCourtButton.isEnabled = restingPlayers.size >= 4
    }

    private fun addPlayerToFirestore(playerName: String) {
        val normalizedName = playerName.lowercase()
        val docRef = playersCollection.document(normalizedName)

        docRef.get().addOnSuccessListener { document ->
            if (!document.exists()) {
                val player = Player(name = playerName, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
                docRef.set(player)
                    .addOnSuccessListener { Toast.makeText(this, "$playerName added.", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(this, "$playerName already exists.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error checking player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Rating used for balance cost
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

    // === Special rule helpers (Chad & Budong) ===

    private fun isChad(p: Player) = p.name.equals("chad", ignoreCase = true)
    private fun isBudong(p: Player) = p.name.equals("budong", ignoreCase = true)

    private fun updateSpecialPairStats(winners: List<Player>, losers: List<Player>) {
        val all = winners + losers
        val hasChad = all.any { isChad(it) }
        val hasBudong = all.any { isBudong(it) }
        if (!hasChad || !hasBudong) return

        specialTogetherCount += 1

        val chadOnWinners = winners.any { isChad(it) }
        val budongOnWinners = winners.any { isBudong(it) }
        val chadOnLosers = losers.any { isChad(it) }
        val budongOnLosers = losers.any { isBudong(it) }

        val teammates = (chadOnWinners && budongOnWinners) || (chadOnLosers && budongOnLosers)
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

    // === Pairing cost & selection ===

    private fun pairingCost(teamA: List<Player>, teamB: List<Player>): Double {
        val avgA = teamA.map { rating(it) }.average()
        val avgB = teamB.map { rating(it) }.average()
        val balanceCost = abs(avgA - avgB)

        val partnerPenalty = getPartnerCount(teamA[0].id, teamA[1].id) +
                getPartnerCount(teamB[0].id, teamB[1].id)

        var opponentPenalty = 0
        for (a in teamA) for (b in teamB) {
            opponentPenalty += getOpponentCount(a.id, b.id)
        }

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

    // === Random selection helpers ===

    private fun popRandom(list: MutableList<Player>, count: Int): List<Player> {
        val c = min(count, list.size)
        val picked = mutableListOf<Player>()
        repeat(c) {
            val idx = Random.nextInt(list.size)
            picked += list.removeAt(idx)
        }
        return picked
    }

    // Legacy simple fair draw (kept for reference)
    private fun drawFairFromRestingQueue(n: Int): List<Player> {
        if (restingPlayers.isEmpty() || n <= 0) return emptyList()
        val indexed = restingPlayers.mapIndexed { idx, p -> Triple(idx, p, restCount[p.id] ?: 0) }
        val sorted = indexed.sortedWith(
            compareByDescending<Triple<Int, Player, Int>> { it.third }.thenBy { it.first }
        )
        val take = sorted.take(min(n, restingPlayers.size))
        val indicesDesc = take.map { it.first }.sortedDescending()
        indicesDesc.forEach { restingPlayers.removeAt(it) }
        val picked = take.map { it.second }
        picked.forEach { restCount[it.id] = 0 }
        return picked
    }

    // Fair draw with anti-repeat and randomized tie-breaks
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
            for (i in 0 until S - 3) {
                for (j in i + 1 until S - 2) {
                    for (k in j + 1 until S - 1) {
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
                }
            }
        }

        val pickedTriples = when {
            best != null -> best!!
            else -> {
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
        }

        val indicesDesc = pickedTriples.map { it.first }.sortedDescending()
        indicesDesc.forEach { restingPlayers.removeAt(it) }

        val picked = pickedTriples.map { it.second }
        picked.forEach { restCount[it.id] = 0 }
        return picked
    }

    // =========================
    // Stats modal (TABLE: Current vs Overall)
    // =========================

    private data class StatsRow(
        val name: String,
        val currentG: Int, val currentW: Int, val currentL: Int,
        val overallG: Int, val overallW: Int, val overallL: Int,
        val overallWinPct: Int // 0..100 rounded
    )

    private fun buildStatsRows(): List<StatsRow> {
        val byId = allPlayers.associateBy { it.id }

        return sessionPlayerIds.mapNotNull { id ->
            val p = byId[id] ?: return@mapNotNull null
            val cur = sessionStats[id] ?: SessionStats(0, 0, 0)
            val overallG = p.gamesPlayed
            val overallW = p.wins
            val overallL = p.losses
            val pct = if (overallG > 0) ((overallW.toDouble() / overallG) * 100).toInt() else 0
            StatsRow(
                name = p.name,
                currentG = cur.games, currentW = cur.wins, currentL = cur.losses,
                overallG = overallG, overallW = overallW, overallL = overallL,
                overallWinPct = pct
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun showStatsDialog() {
        val rows = buildStatsRows()

        // Horizontal scroll for columns, vertical scroll for rows
        val hScroll = HorizontalScrollView(this)
        val vScroll = ScrollView(this).apply { isFillViewport = true }

        val table = buildStatsTable(rows)

        val vLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        vScroll.addView(table, vLp)

        val hLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hScroll.addView(vScroll, hLp)

        AlertDialog.Builder(this)
            .setTitle("Session Stats")
            .setView(hScroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildStatsTable(rows: List<StatsRow>): TableLayout {
        val density = resources.displayMetrics.density
        fun dp(px: Int) = (px * density).toInt()

        // Approximate width for up to 12 characters at 13sp (includes padding headroom)
        val NAME_COL_WIDTH_DP = 112

        fun headerCell(
            text: String,
            span: Int = 1,
            center: Boolean = true,
            bold: Boolean = true,
            sizeSp: Float = 13f
        ): TextView {
            return TextView(this).apply {
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
        }

        // Header cell for Name with fixed width to align the column
        fun headerNameCell(text: String): TextView {
            return TextView(this).apply {
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
        }

        fun bodyCell(text: String, center: Boolean = true): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                gravity = if (center) android.view.Gravity.CENTER else android.view.Gravity.START
            }
        }

        // Body cell for Name with same fixed width as header
        fun nameCell(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                gravity = android.view.Gravity.START
                minWidth = dp(NAME_COL_WIDTH_DP)
                maxWidth = dp(NAME_COL_WIDTH_DP)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }

        // Thin vertical separator
        fun vSep(): View {
            return View(this).apply {
                layoutParams = TableRow.LayoutParams(dp(1), TableRow.LayoutParams.MATCH_PARENT).apply {
                    setMargins(0, dp(2), 0, dp(2))
                }
                setBackgroundColor(0xFFDDDDDD.toInt()) // light gray
            }
        }

        val table = TableLayout(this).apply {
            setPadding(dp(4))
            isShrinkAllColumns = false
            isStretchAllColumns = false
        }

        // Header Row 1: Name | | Current(3) | | Overall(4)
        val hdr1 = TableRow(this).apply { setPadding(0) }
        hdr1.addView(headerNameCell("Name"))
        hdr1.addView(vSep())
        hdr1.addView(headerCell("Current", span = 3))
        hdr1.addView(vSep())
        hdr1.addView(headerCell("Overall", span = 4))
        table.addView(hdr1)

        // Header Row 2: "" | | G W L | | G W L Win%
        val hdr2 = TableRow(this).apply { setPadding(0) }
        // Keep first empty header cell; the Name column width is enforced by headerNameCell above
        hdr2.addView(headerCell("", span = 1, center = false, bold = false))
        hdr2.addView(vSep())
        listOf("G", "W", "L").forEach { hdr2.addView(headerCell(it)) }
        hdr2.addView(vSep())
        listOf("G", "W", "L", "Win%").forEach { hdr2.addView(headerCell(it)) }
        table.addView(hdr2)

        // Body rows
        rows.forEach { r ->
            val tr = TableRow(this).apply { setPadding(0) }
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
                if (snap == null) return@addSnapshotListener
                val hasPending = snap.metadata.hasPendingWrites()
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

    override fun onDestroy() {
        super.onDestroy()
        detachMatchesListener()
    }

    // === UI: Add Late Players / Edit Court ===

    private fun showAddLatePlayerDialog() {
        val activePlayerIds = mutableSetOf<String>()
        restingPlayers.forEach { activePlayerIds.add(it.id) }
        currentCourts.forEach { court ->
            court.teams?.let { (teamA, teamB) ->
                teamA.forEach { activePlayerIds.add(it.id) }
                teamB.forEach { activePlayerIds.add(it.id) }
            }
        }

        val availablePlayers = allPlayers.filter { it.id !in activePlayerIds }
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
                val chip = Chip(this)
                chip.text = player.name
                chip.isCheckable = true
                chip.tag = player
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
                    val playerNames = selectedPlayers.joinToString(", ") { it.name }
                    Toast.makeText(this, "$playerNames added to the resting queue.", Toast.LENGTH_SHORT).show()
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
        val playerExists = allPlayers.any { it.name.equals(normalizedName, ignoreCase = true) }
        if (playerExists) {
            Toast.makeText(this, "$playerName already exists.", Toast.LENGTH_SHORT).show()
            return
        }

        val newPlayer = Player(name = playerName, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
        playersCollection.document(normalizedName).set(newPlayer)
            .addOnSuccessListener {
                val playerWithId = newPlayer.copy(id = normalizedName)
                allPlayers.add(playerWithId)

                restingPlayers.add(playerWithId)
                restCount[playerWithId.id] = 0
                sessionPlayerIds.add(playerWithId.id)
                sessionStats.putIfAbsent(playerWithId.id, SessionStats())

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
            val newCourtPlayers = drawFairFromRestingQueueAvoid(4, avoidGroup = null)
            val newTeams = generateTeamsForCourt(newCourtPlayers)
            val newCourtNumber = (currentCourts.maxOfOrNull { it.courtNumber } ?: 0) + 1
            currentCourts.add(Court(newTeams, newCourtNumber))

            courtAdapter.notifyDataSetChanged()
            updateRestingPlayersView()
            Toast.makeText(this, "Court $newCourtNumber added.", Toast.LENGTH_SHORT).show()
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

        fun handleSelection(player: Player, list: MutableList<Player>, @Suppress("UNUSED_PARAMETER") clickedAdapter: EditCourtPlayerAdapter) {
            if (firstSelection == null) {
                firstSelection = player
                firstList = list
                allAdapters.forEach { it.selectedPlayer = player; it.notifyDataSetChanged() }
            } else {
                if (firstSelection != player) {
                    val player1 = firstSelection!!
                    val list1 = firstList!!

                    val index1 = list1.indexOf(player1)
                    val index2 = list.indexOf(player)

                    if (index1 != -1 && index2 != -1) {
                        list1[index1] = player
                        list[index2] = player1
                    }
                }
                firstSelection = null
                firstList = null
                allAdapters.forEach { it.selectedPlayer = null; it.notifyDataSetChanged() }
            }
        }

        adapterA.onPlayerSelected = { player -> handleSelection(player, teamAMutable, adapterA) }
        rvTeamA.layoutManager = LinearLayoutManager(this)
        rvTeamA.adapter = adapterA

        adapterB.onPlayerSelected = { player -> handleSelection(player, teamBMutable, adapterB) }
        rvTeamB.layoutManager = LinearLayoutManager(this)
        rvTeamB.adapter = adapterB

        if (restingMutable.isEmpty()) {
            restingTitle.visibility = View.GONE
            rvResting.visibility = View.GONE
        } else {
            adapterResting.onPlayerSelected = { player -> handleSelection(player, restingMutable, adapterResting) }
            rvResting.layoutManager = LinearLayoutManager(this)
            rvResting.adapter = adapterResting
        }

        val mainDialog = AlertDialog.Builder(this)
            .setTitle("Edit Court ${court.courtNumber}")
            .setView(dialogView)
            .setPositiveButton("Done") { _, _ ->
                currentCourts[courtIndex].teams = if (teamAMutable.isNotEmpty() || teamBMutable.isNotEmpty()) {
                    Pair(teamAMutable, teamBMutable)
                } else {
                    null
                }

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
                .setMessage("Are you sure you want to delete Court ${court.courtNumber}? All players on this court will be moved to the resting queue.")
                .setPositiveButton("Delete") { _, _ ->
                    val playersToRest = currentCourts[courtIndex].teams?.toList()?.flatMap { it } ?: emptyList()
                    playersToRest.forEach {
                        restCount[it.id] = 0
                        sessionPlayerIds.add(it.id)
                        sessionStats.putIfAbsent(it.id, SessionStats())
                    }
                    restingPlayers.addAll(playersToRest)
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
}