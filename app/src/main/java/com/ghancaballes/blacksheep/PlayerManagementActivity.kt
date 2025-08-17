package com.ghancaballes.blacksheep

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    // Shuffling memory
    private val partnershipHistory = mutableMapOf<String, Int>() // legacy, not used in cost
    private val recentOpponents = mutableMapOf<String, Set<String>>() // reserved for future use

    // In-session counters for pairing optimization
    private val partnerCount = mutableMapOf<String, MutableMap<String, Int>>()    // teammates
    private val opponentCount = mutableMapOf<String, MutableMap<String, Int>>()   // opponents

    // Fair-rest scheduling counters (games rested since last play)
    private val restCount = mutableMapOf<String, Int>()

    // Penalty weights (tunable)
    private val ALPHA_PARTNER = 1.0 // avoid repeat teammates
    private val BETA_OPPONENT = 0.5 // avoid repeat opponents

    // Special rule: bias Chad & Budong to be teammates ~3 out of 5 (60%)
    private val SPECIAL_DESIRED_RATIO = 0.60
    private val SPECIAL_WEIGHT = 2.0 // strength of the bias vs balance/penalties
    private var specialTogetherCount = 0   // times both Chad and Budong appeared in the same game
    private var specialTeammateCount = 0   // times they were teammates when together

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
        partnershipHistory.clear()
        recentOpponents.clear()
        partnerCount.clear()
        opponentCount.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        restCount.clear()

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

        // Initialize rest counters for all selected players
        restCount.clear()
        playersForSession.forEach { restCount[it.id] = 0 }

        // Seed courts
        currentCourts.clear()
        for (i in 1..courtCount) {
            if (playerPool.size < 4) break
            // Pop 4 at random from the pool to avoid contiguous chunks
            val courtPlayers = popRandom(playerPool, 4)
            val teams = generateTeamsForCourt(courtPlayers)
            currentCourts.add(Court(teams, i))
        }

        // Remaining players form the resting queue (already random)
        restingPlayers = LinkedList(playerPool)

        switchToGameView()
    }

    // Optional legacy function (not used when recordMatchAndUpdatePlayerStats is present)
    private fun updatePlayerStats(winners: List<Player>, losers: List<Player>) {
        db.runTransaction { transaction ->
            val allGamePlayers = winners + losers
            val snapshots = mutableMapOf<String, DocumentSnapshot>()

            for (player in allGamePlayers) {
                val playerDocRef = playersCollection.document(player.id)
                snapshots[player.id] = transaction.get(playerDocRef)
            }

            for (player in allGamePlayers) {
                val snapshot = snapshots[player.id]
                if (snapshot == null || !snapshot.exists()) {
                    throw Exception("Document for player ${player.name} with ID ${player.id} does not exist!")
                }

                val currentWins = snapshot.getLong("wins") ?: 0L
                val currentLosses = snapshot.getLong("losses") ?: 0L

                var newWins = currentWins
                var newLosses = currentLosses

                if (winners.any { it.id == player.id }) {
                    newWins++
                } else {
                    newLosses++
                }

                val newGamesPlayed = newWins + newLosses
                val newWinRate = if (newGamesPlayed > 0) newWins.toDouble() / newGamesPlayed else 0.0

                val updates = mapOf(
                    "wins" to newWins,
                    "losses" to newLosses,
                    "gamesPlayed" to newGamesPlayed,
                    "winrate" to newWinRate
                )
                transaction.update(snapshot.reference, updates)
            }
            null
        }.addOnSuccessListener {
            Log.d("PlayerStats", "Transaction successful: Player stats updated.")
        }.addOnFailureListener { e ->
            Log.e("PlayerStats", "Transaction failed.", e)
            Toast.makeText(this, "Failed to update player stats: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
            // READS FIRST for idempotency + Firestore constraint
            val matchSnap = tx.get(matchRef)
            if (matchSnap.exists()) {
                // Already recorded → no-op
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

            // WRITES
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
            Toast.makeText(this, "Recorded: Court $courtNumber — Winners: $winnerNames", Toast.LENGTH_SHORT).show()

            // Force rebind of this court row in case the adapter disabled buttons
            courtAdapter.notifyItemChanged(courtIndex)
        }.addOnFailureListener { e ->
            Log.e("PlayerStats", "Failed to record match $matchId.", e)
            Toast.makeText(this, "Failed to record match: ${e.message}", Toast.LENGTH_LONG).show()

            // Rebind the court to re-enable buttons if they were disabled
            courtAdapter.notifyItemChanged(courtIndex)
        }
    }

    private fun handleGameFinished(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        // Persist (idempotent + offline-safe)
        recordMatchAndUpdatePlayerStats(winners, losers, courtIndex)

        // Legacy counters (not used in pairing cost)
        val winnerKey = winners.map { it.name }.sorted().joinToString("|")
        val loserKey = losers.map { it.name }.sorted().joinToString("|")
        partnershipHistory[winnerKey] = partnershipHistory.getOrDefault(winnerKey, 0) + 1
        partnershipHistory[loserKey] = partnershipHistory.getOrDefault(loserKey, 0) + 1

        // Update in-session pairing memory
        updatePairingStats(winners, losers)
        updateSpecialPairStats(winners, losers) // track Chad/Budong ratio

        // Mark court empty
        currentCourts[courtIndex].teams = null

        // Finished players go to rest with restCount = 0
        val finishedPlayers = winners + losers
        finishedPlayers.forEach { restCount[it.id] = 0 }
        restingPlayers.addAll(finishedPlayers)

        // Refill empty courts (prioritize longest-resting first)
        refillEmptyCourts()

        // After refilling, increment rest counters for those STILL resting
        restingPlayers.forEach { p -> restCount[p.id] = (restCount[p.id] ?: 0) + 1 }

        updateRestingPlayersView()
    }

    private fun refillEmptyCourts() {
        for (court in currentCourts) {
            if (court.teams == null && restingPlayers.size >= 4) {
                val newCourtPlayers = drawFairFromRestingQueue(4)
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
        // Teammate increments
        if (winners.size == 2) incPartnerCountPair(winners[0].id, winners[1].id)
        if (losers.size == 2) incPartnerCountPair(losers[0].id, losers[1].id)
        // Opponent increments: cross pairs
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
        // Shuffle within each team to avoid positional bias
        return Pair(best.first.shuffled(), best.second.shuffled())
    }

    private fun generateTeamsForCourt(players: List<Player>): Pair<List<Player>, List<Player>>? {
        if (players.size != 4) return null
        return chooseBestPairingOfFour(players)
    }

    // === Random selection helpers ===

    // Pop 'count' random distinct players from a mutable list, removing them.
    private fun popRandom(list: MutableList<Player>, count: Int): List<Player> {
        val c = min(count, list.size)
        val picked = mutableListOf<Player>()
        repeat(c) {
            val idx = Random.nextInt(list.size)
            picked += list.removeAt(idx)
        }
        return picked
    }

    // Fair draw that prioritizes longest-resting players (FIFO tie-break)
    private fun drawFairFromRestingQueue(n: Int): List<Player> {
        if (restingPlayers.isEmpty() || n <= 0) return emptyList()

        // Build (index, player, restCount), stable-sort by restCount desc then by index asc (FIFO)
        val indexed = restingPlayers.mapIndexed { idx, p -> Triple(idx, p, restCount[p.id] ?: 0) }
        val sorted = indexed.sortedWith(
            compareByDescending<Triple<Int, Player, Int>> { it.third }.thenBy { it.first }
        )
        val take = sorted.take(min(n, restingPlayers.size))
        val indicesDesc = take.map { it.first }.sortedDescending()

        // Remove selected from the queue
        indicesDesc.forEach { restingPlayers.removeAt(it) }
        val picked = take.map { it.second }

        // They’re now seated; reset their counters
        picked.forEach { restCount[it.id] = 0 }

        return picked
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
                    // New resters start with restCount = 0
                    selectedPlayers.forEach { restCount[it.id] = 0 }
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
                restCount[playerWithId.id] = 0 // join resting with zero rest
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
            val newCourtPlayers = drawFairFromRestingQueue(4)
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
                // Ensure any newly-resting players have a restCount initialized
                restingPlayers.forEach { p -> restCount.putIfAbsent(p.id, 0) }

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
                    playersToRest.forEach { restCount[it.id] = 0 } // just left a game
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
}