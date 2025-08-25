package com.ghancaballes.blacksheep

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import java.util.ArrayDeque
import java.util.HashSet

class PlayerManagementActivity : AppCompatActivity() {

    // Firebase
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var playersCollection: CollectionReference
    private lateinit var uid: String

    // UI
    private lateinit var titleTextView: TextView
    private var syncBanner: TextView? = null
    private lateinit var setupContainer: LinearLayout
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var playerChipGroup: ChipGroup
    private lateinit var courtCountEditText: EditText
    private lateinit var startSessionButton: Button
    private lateinit var courtsRecyclerView: RecyclerView
    private lateinit var restingPlayersTextView: TextView
    private lateinit var statsButton: MaterialButton
    private lateinit var addLatePlayerButton: MaterialButton
    private lateinit var addCourtButton: MaterialButton
    private lateinit var endSessionButton: MaterialButton
    private lateinit var sitOutButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var fabMenu: FloatingActionButton
    private lateinit var actionMenuContainer: ViewGroup
    private var isMenuOpen = false

    // Adapters
    private lateinit var playerSelectionAdapter: PlayerSelectionAdapter
    private lateinit var courtAdapter: CourtAdapter

    // Data
    private val allPlayers = mutableListOf<Player>()
    private val initialSelectedPlayers = mutableSetOf<Player>()
    private val currentCourts = mutableListOf<Court>()
    private var restingPlayers = LinkedList<Player>()
    private val sessionPlayerIds = LinkedHashSet<String>()

    data class SessionStats(var games: Int = 0, var wins: Int = 0, var losses: Int = 0)
    private val sessionStats = mutableMapOf<String, SessionStats>()

    // Session
    private var sessionId: String? = null
    private var matchesListener: ListenerRegistration? = null
    private val courtMatchSeq = mutableMapOf<Int, Int>()

    // Pair memory
    private val partnerCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val opponentCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val restCount = mutableMapOf<String, Int>()
    private val lastCourtGroupIds = mutableMapOf<Int, Set<String>>()
    private val lastQuartetByPlayer = mutableMapOf<String, Set<String>>()

    // Team forming weights
    private val ALPHA_PARTNER = 1.0
    private val BETA_OPPONENT = 0.5

    // Quartet selection weights
    private val DIVERSITY_WEIGHT = 1.5
    private val REST_WEIGHT = 1.0

    // Quartet repetition control
    private val REPEAT_WEIGHT = 3.0
    private val RECENT_BLOCK_SIZE = 6
    private val RECENT_BLOCK_PENALTY = 1000.0
    private val quartetUsage = mutableMapOf<String, Int>()
    private val recentQuartets = ArrayDeque<String>()

    // Special pair
    private val SPECIAL_DESIRED_RATIO = 0.60
    private val SPECIAL_WEIGHT = 2.0
    private var specialTogetherCount = 0
    private var specialTeammateCount = 0

    // Sitting out
    private val sittingOutIds = mutableSetOf<String>()

    // High winrate constraints (60%)
    private val HIGH_WINRATE_THRESHOLD = 0.60
    private val MIN_GAMES_FOR_HIGH = 10
    private fun isHighWinPlayer(p: Player): Boolean =
        p.gamesPlayed >= MIN_GAMES_FOR_HIGH && p.winrate >= HIGH_WINRATE_THRESHOLD

    // Carry-over config
    private val CARRY_OVER_MAX = 2
    private val CARRY_OVER_MIN = 1
    private val TOP_NEW_CANDIDATES = 8

    // Consecutive tracking
    private val MAX_CONSEC_GAMES = 2
    private val MAX_CONSEC_STAYS = 1
    private val consecutiveGames = mutableMapOf<String, Int>()
    private val consecutiveStays = mutableMapOf<String, Int>()

    // Pending auto-select for setup
    private val pendingAutoSelectIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_management)

        db = Firebase.firestore
        auth = Firebase.auth
        val currentUser = auth.currentUser?.uid
        if (currentUser == null) {
            Toast.makeText(this, "No authenticated user. Please login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uid = currentUser
        playersCollection = db.collection("users").document(uid).collection("players")

        initializeUI()
        initializeAdapters()
        setClickListeners()
        listenForPlayerUpdates()
        switchToSetupView()
    }

    // -------------------- UI INIT --------------------
    private fun initializeUI() {
        titleTextView = findViewById(R.id.textViewTitle)
        syncBanner = findViewById(R.id.syncBanner)
        setupContainer = findViewById(R.id.setupContainer)
        playersRecyclerView = findViewById(R.id.recyclerViewPlayers)
        playerChipGroup = findViewById(R.id.playerChipGroup)
        courtCountEditText = findViewById(R.id.editTextCourtCount)
        startSessionButton = findViewById(R.id.buttonStartSession)
        courtsRecyclerView = findViewById(R.id.recyclerViewCourts)
        restingPlayersTextView = findViewById(R.id.textViewRestingPlayers)
        fabMenu = findViewById(R.id.fabMenu)
        actionMenuContainer = findViewById(R.id.actionMenuContainer)
        statsButton = findViewById(R.id.buttonStats)
        addLatePlayerButton = findViewById(R.id.buttonAddLatePlayer)
        addCourtButton = findViewById(R.id.buttonAddCourt)
        endSessionButton = findViewById(R.id.buttonEndSession)
        sitOutButton = findViewById(R.id.buttonSitOut)
        restoreButton = findViewById(R.id.buttonRestore)

        playersRecyclerView.layoutManager = LinearLayoutManager(this)
        courtsRecyclerView.layoutManager = LinearLayoutManager(this)
        fabMenu.setOnClickListener { toggleActionMenu() }
    }

    private fun toggleActionMenu(forceShow: Boolean? = null) {
        val target = forceShow ?: !isMenuOpen
        if (target == isMenuOpen) return
        isMenuOpen = target
        animateMenuVisibility(isMenuOpen)
    }

    private fun animateMenuVisibility(show: Boolean) {
        if (show) {
            actionMenuContainer.visibility = View.VISIBLE
            actionMenuContainer.alpha = 0f
            actionMenuContainer.translationY = 32f
            actionMenuContainer.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            actionMenuContainer.animate()
                .alpha(0f).translationY(16f).setDuration(120)
                .withEndAction { actionMenuContainer.visibility = View.GONE }
                .start()
        }
    }

    override fun onBackPressed() {
        if (isMenuOpen) toggleActionMenu(false) else super.onBackPressed()
    }

    private fun initializeAdapters() {
        playerSelectionAdapter = PlayerSelectionAdapter(allPlayers, initialSelectedPlayers) { p, sel ->
            if (sel) initialSelectedPlayers.add(p) else initialSelectedPlayers.remove(p)
        }
        courtAdapter = CourtAdapter(currentCourts, this::handleGameFinished, this::showEditCourtDialog)
        playersRecyclerView.adapter = playerSelectionAdapter
        courtsRecyclerView.adapter = courtAdapter
    }

    private fun setClickListeners() {
        val addPlayerButton = findViewById<Button>(R.id.buttonAddPlayer)
        val playerNameEditText = findViewById<EditText>(R.id.editTextPlayerName)
        addPlayerButton.setOnClickListener {
            val name = playerNameEditText.text.toString().trim()
            if (name.isNotEmpty()) {
                addPlayerToFirestore(name)
                playerNameEditText.text.clear()
            }
        }
        startSessionButton.setOnClickListener { startSession() }
        statsButton.setOnClickListener { showStatsDialog(); toggleActionMenu(false) }
        addLatePlayerButton.setOnClickListener { showAddLatePlayerDialog(); toggleActionMenu(false) }
        addCourtButton.setOnClickListener { addCourt(); toggleActionMenu(false) }
        endSessionButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End Session")
                .setMessage("End the current session?")
                .setPositiveButton("End Session") { _, _ -> switchToSetupView() }
                .setNegativeButton("Cancel", null)
                .show()
            toggleActionMenu(false)
        }
        sitOutButton.setOnClickListener { showSitOutFlow(); toggleActionMenu(false) }
        restoreButton.setOnClickListener { showRestoreDialog(); toggleActionMenu(false) }
    }

    // -------------------- Firestore Listener --------------------
    private fun listenForPlayerUpdates() {
        playersCollection.orderBy("name").addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed", e)
                return@addSnapshotListener
            }
            val prevSelected = initialSelectedPlayers.map { it.id }.toSet()
            allPlayers.clear()
            initialSelectedPlayers.clear()

            snap?.forEach { doc ->
                val p = doc.toObject(Player::class.java).copy(id = doc.id)
                allPlayers.add(p)
                if (prevSelected.contains(p.id) || pendingAutoSelectIds.contains(p.id)) {
                    initialSelectedPlayers.add(p)
                }
            }

            if (pendingAutoSelectIds.isNotEmpty()) pendingAutoSelectIds.clear()

            dedupeAllPlayers()

            if (setupContainer.visibility == View.VISIBLE) {
                rebuildPlayerChips()
            } else {
                courtAdapter.notifyDataSetChanged()
                updateRestingPlayersView()
            }
        }
    }

    private fun dedupeAllPlayers() {
        val seen = HashSet<String>()
        val deduped = mutableListOf<Player>()
        for (p in allPlayers) if (seen.add(p.id)) deduped.add(p)
        if (deduped.size != allPlayers.size) {
            allPlayers.clear()
            allPlayers.addAll(deduped)
        }
    }

    // -------------------- Mode Switching --------------------
    private fun switchToSetupView() {
        titleTextView.text = "Session Setup"
        setupContainer.visibility = View.VISIBLE
        startSessionButton.visibility = View.VISIBLE
        courtsRecyclerView.visibility = View.GONE
        restingPlayersTextView.visibility = View.GONE
        fabMenu.visibility = View.GONE
        toggleActionMenu(false)

        detachMatchesListener()
        sessionId = null
        courtMatchSeq.clear()

        currentCourts.clear()
        restingPlayers.clear()
        initialSelectedPlayers.clear()
        sessionPlayerIds.clear()
        sessionStats.clear()
        partnerCount.clear()
        opponentCount.clear()
        restCount.clear()
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        sittingOutIds.clear()
        quartetUsage.clear()
        recentQuartets.clear()
        consecutiveGames.clear()
        consecutiveStays.clear()
        pendingAutoSelectIds.clear()

        playerSelectionAdapter.notifyDataSetChanged()
        rebuildPlayerChips()
        syncBanner?.visibility = View.GONE
    }

    private fun switchToGameView() {
        titleTextView.text = "Active Courts"
        setupContainer.visibility = View.GONE
        startSessionButton.visibility = View.GONE
        courtsRecyclerView.visibility = View.VISIBLE
        restingPlayersTextView.visibility = View.VISIBLE
        fabMenu.visibility = View.VISIBLE
        toggleActionMenu(false)
        courtAdapter.notifyDataSetChanged()
        updateRestingPlayersView()
    }

    // -------------------- Chips --------------------
    private fun rebuildPlayerChips() {
        if (setupContainer.visibility != View.VISIBLE) return
        playerChipGroup.removeAllViews()
        val selectedIds = initialSelectedPlayers.map { it.id }.toSet()
        allPlayers.distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
            .forEach { player ->
                val chip = Chip(this).apply {
                    text = player.name
                    isCheckable = true
                    isChecked = selectedIds.contains(player.id)
                    tag = player
                    setOnClickListener {
                        val c = it as Chip
                        if (c.isChecked) initialSelectedPlayers.add(player) else initialSelectedPlayers.remove(player)
                    }
                }
                playerChipGroup.addView(chip)
            }
    }

    // -------------------- Session Start --------------------
    private fun startSession() {
        val selectedIds = initialSelectedPlayers.map { it.id }.toSet()
        val playersForSession = allPlayers.filter { it.id in selectedIds }
        val courtCount = courtCountEditText.text.toString().toIntOrNull() ?: 0
        if (courtCount <= 0) {
            Toast.makeText(this, "Enter number of courts.", Toast.LENGTH_SHORT).show()
            return
        }
        val needed = courtCount * 4
        if (playersForSession.size < needed) {
            Toast.makeText(this, "Need $needed players; selected ${playersForSession.size}.", Toast.LENGTH_LONG).show()
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
            consecutiveGames[it.id] = 0
            consecutiveStays[it.id] = 0
        }
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        quartetUsage.clear()
        recentQuartets.clear()

        currentCourts.clear()
        for (i in 1..courtCount) {
            val quartet = popRandom(pool, 4)
            quartet.forEach {
                consecutiveGames[it.id] = (consecutiveGames[it.id] ?: 0) + 1
                consecutiveStays[it.id] = 0
            }
            registerQuartetUsage(quartet.map { it.id })
            val teams = generateTeamsForCourt(quartet)
            currentCourts.add(Court(teams, i))
            val idsSet = quartet.map { it.id }.toSet()
            lastCourtGroupIds[i] = idsSet
            quartet.forEach { p -> lastQuartetByPlayer[p.id] = (idsSet - p.id) }
        }
        restingPlayers = LinkedList(pool)
        dedupeResting()
        switchToGameView()
    }

    // -------------------- Match Recording --------------------
    private fun recordMatchAndUpdatePlayerStats(
        winners: List<Player>, losers: List<Player>, courtIndex: Int
    ) {
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
            if (tx.get(matchRef).exists()) return@runTransaction null
            val gamePlayers = winners + losers
            val snaps = gamePlayers.associateWith { tx.get(playersCollection.document(it.id)) }
            snaps.forEach { (p, s) -> if (!s.exists()) throw Exception("Missing player ${p.name}") }
            tx.set(
                matchRef,
                mapOf(
                    "sessionId" to currentSession,
                    "matchId" to matchId,
                    "courtNumber" to courtNumber,
                    "winners" to winners.map { it.id },
                    "losers" to losers.map { it.id },
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            snaps.forEach { (p, s) ->
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
            applyLocalMatchResult(
                winners.map { it.id }.toSet(),
                losers.map { it.id }.toSet()
            )
            courtAdapter.notifyDataSetChanged()
            updateRestingPlayersView()
        }.addOnFailureListener {
            Toast.makeText(this, "Record failed: ${it.message}", Toast.LENGTH_LONG).show()
            courtAdapter.notifyItemChanged(courtIndex)
        }
    }

    private fun applyLocalMatchResult(winnerIds: Set<String>, loserIds: Set<String>) {
        fun updated(p: Player): Player {
            val addWin = if (winnerIds.contains(p.id)) 1 else 0
            val addLoss = if (loserIds.contains(p.id)) 1 else 0
            if (addWin == 0 && addLoss == 0) return p
            val newWins = p.wins + addWin
            val newLosses = p.losses + addLoss
            val games = newWins + newLosses
            val wr = if (games > 0) newWins.toDouble() / games else 0.0
            return p.copy(wins = newWins, losses = newLosses, gamesPlayed = games, winrate = wr)
        }
        for (i in allPlayers.indices) allPlayers[i] = updated(allPlayers[i])
        currentCourts.forEach { court ->
            court.teams?.let { (a, b) ->
                court.teams = Pair(a.map { updated(it) }, b.map { updated(it) })
            }
        }
        restingPlayers = LinkedList(restingPlayers.map { updated(it) })
    }

    private fun handleGameFinished(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        recordMatchAndUpdatePlayerStats(winners, losers, courtIndex)

        (winners + losers).forEach {
            sessionPlayerIds.add(it.id)
            sessionStats.getOrPut(it.id) { SessionStats() }.games += 1
        }
        winners.forEach { sessionStats.getOrPut(it.id) { SessionStats() }.wins += 1 }
        losers.forEach { sessionStats.getOrPut(it.id) { SessionStats() }.losses += 1 }

        updatePairingStats(winners, losers)
        updateSpecialPairStats(winners, losers)

        val finished = winners + losers
        val success = attemptCarryOverRefillRandom(courtIndex, finished)

        if (!success) {
            // Standard path: everyone rests, then full refill
            finished.forEach {
                restCount[it.id] = 0
                restingPlayers.add(it)
                resetConsecutiveStays(it.id)
                resetConsecutiveGamesIfRested(it.id)
            }
            dedupeResting()
            refillEmptyCourtsSingle(courtIndex)
        }

        updateRestingPlayersView()
        courtAdapter.notifyItemChanged(courtIndex)
    }

    // -------------------- Carry-over gating helper --------------------
    private fun isCarryOverAllowed(): Boolean {
        // Enabled only for exactly 2 courts and total session players <= 12
        return currentCourts.size == 2 && sessionPlayerIds.size <= 12
    }

    // -------------------- Carry-over Logic --------------------
    private fun attemptCarryOverRefillRandom(courtIndex: Int, finished: List<Player>): Boolean {
        if (finished.size != 4) return false
        if (!isCarryOverAllowed()) return false  // Gate: disable carry-over outside condition

        val finishedIds = finished.map { it.id }.toSet()
        removePlayersFromRest(finishedIds)

        val desired = Random.nextInt(CARRY_OVER_MIN, CARRY_OVER_MAX + 1)
        for (carryAttempt in desired downTo 0) {
            if (carryAttempt == 0) break
            val neededNew = 4 - carryAttempt
            if (restingPlayers.size < neededNew) continue
            val carryOvers = pickRandomEligibleCarryOvers(finished, carryAttempt)
            if (carryOvers.size != carryAttempt) continue
            if (carryOvers.any { (consecutiveGames[it.id] ?: 0) >= MAX_CONSEC_GAMES }) continue

            val finishedExceptCarry = finished.filter { it !in carryOvers }.map { it.id }.toSet()

            val newPlayers = chooseNewPlayerCombination(carryOvers, neededNew, excludeJustRested = true, justRestedIds = finishedExceptCarry)
                .ifEmpty {
                    chooseNewPlayerCombination(carryOvers, neededNew, excludeJustRested = false, justRestedIds = finishedExceptCarry)
                }

            if (newPlayers.size != neededNew) continue

            val toRest = finished.filter { it !in carryOvers }
            toRest.forEach {
                restCount[it.id] = 0
                restingPlayers.add(it)
                resetConsecutiveGamesIfRested(it.id)
                resetConsecutiveStays(it.id)
            }
            dedupeResting()

            carryOvers.forEach {
                incrementConsecutiveGames(it.id)
                incrementConsecutiveStays(it.id)
            }
            newPlayers.forEach {
                incrementConsecutiveGames(it.id)
                resetConsecutiveStays(it.id)
            }

            val quartet = (carryOvers + newPlayers)
            if (!enforceUniqueQuartet(quartet, courtIndex, finishedIds)) {
                return false
            }
            return true
        }
        return false
    }

    private fun pickRandomEligibleCarryOvers(finished: List<Player>, count: Int): List<Player> {
        val eligible = finished.filter { canBeCarried(it.id) }
        if (eligible.size < count) return emptyList()
        val grouped = eligible.groupBy { consecutiveStays[it.id] ?: 0 }
        val zero = grouped[0] ?: emptyList()
        val pool = if (zero.size >= count) zero else eligible
        return pool.shuffled().take(count)
    }

    private fun canBeCarried(playerId: String): Boolean {
        val cg = consecutiveGames[playerId] ?: 0
        val cs = consecutiveStays[playerId] ?: 0
        if (cg >= MAX_CONSEC_GAMES) return false
        if (cs >= MAX_CONSEC_STAYS) return false
        return true
    }

    private fun incrementConsecutiveGames(id: String) {
        consecutiveGames[id] = (consecutiveGames[id] ?: 0) + 1
    }
    private fun resetConsecutiveGamesIfRested(id: String) { consecutiveGames[id] = 0 }
    private fun incrementConsecutiveStays(id: String) {
        consecutiveStays[id] = (consecutiveStays[id] ?: 0) + 1
    }
    private fun resetConsecutiveStays(id: String) { consecutiveStays[id] = 0 }

    private fun chooseNewPlayerCombination(
        carryOvers: List<Player>,
        needed: Int,
        excludeJustRested: Boolean,
        justRestedIds: Set<String>
    ): List<Player> {
        if (needed <= 0) return emptyList()
        val carryIds = carryOvers.map { it.id }.toSet()
        val eligibleRaw = restingPlayers.filter {
            (consecutiveGames[it.id] ?: 0) < MAX_CONSEC_GAMES &&
                    it.id !in sittingOutIds &&
                    it.id !in carryIds &&
                    (!excludeJustRested || it.id !in justRestedIds)
        }
        if (eligibleRaw.size < needed) return emptyList()
        val candidatePool = eligibleRaw.take(TOP_NEW_CANDIDATES)

        val combos = mutableListOf<List<Player>>()
        fun dfs(start: Int, pick: MutableList<Player>) {
            if (pick.size == needed) { combos += pick.toList(); return }
            for (i in start until candidatePool.size) {
                pick += candidatePool[i]; dfs(i + 1, pick); pick.removeAt(pick.size - 1)
            }
        }
        dfs(0, mutableListOf())
        if (combos.isEmpty()) return emptyList()

        var best: List<Player>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (combo in combos) {
            var diversityEdges = 0
            for (c in combo) {
                for (co in carryOvers) if (getOpponentCount(c.id, co.id) == 0) diversityEdges++
            }
            for (i in 0 until combo.size - 1)
                for (j in i + 1 until combo.size)
                    if (getOpponentCount(combo[i].id, combo[j].id) == 0) diversityEdges++

            val restSum = combo.sumOf { restCount[it.id] ?: 0 }
            val predictedKey = (carryOvers.map { it.id } + combo.map { it.id }).sorted().joinToString("|")
            val usage = quartetUsage[predictedKey] ?: 0
            val score = REST_WEIGHT * restSum + DIVERSITY_WEIGHT * diversityEdges - REPEAT_WEIGHT * usage
            if (score > bestScore) { bestScore = score; best = combo }
        }

        val chosen = best ?: return emptyList()
        chosen.forEach { rp ->
            val idx = restingPlayers.indexOfFirst { it.id == rp.id }
            if (idx >= 0) restingPlayers.removeAt(idx)
            restCount[rp.id] = 0
        }
        return chosen
    }

    private fun enforceUniqueQuartet(
        quartet: List<Player>,
        courtIndex: Int,
        finishedIds: Set<String>
    ): Boolean {
        if (quartet.map { it.id }.toSet().size == 4) {
            finalizeQuartet(courtIndex, quartet)
            return true
        }
        val used = mutableSetOf<String>()
        val fixed = quartet.toMutableList()
        for (i in fixed.indices) {
            val id = fixed[i].id
            if (used.add(id)) continue
            val replacement = restingPlayers.firstOrNull { it.id !in used && it.id !in finishedIds }
                ?: restingPlayers.firstOrNull { it.id !in used }
            if (replacement != null) {
                restingPlayers.removeIf { it.id == replacement.id }
                restCount[replacement.id] = 0
                fixed[i] = replacement
                used.add(replacement.id)
            } else {
                return false
            }
        }
        if (fixed.map { it.id }.toSet().size == 4) {
            finalizeQuartet(courtIndex, fixed)
            return true
        }
        return false
    }

    private fun finalizeQuartet(courtIndex: Int, quartet: List<Player>) {
        registerQuartetUsage(quartet.map { it.id })
        val idsSet = quartet.map { it.id }.toSet()
        val court = currentCourts[courtIndex]
        lastCourtGroupIds[court.courtNumber] = idsSet
        quartet.forEach { p -> lastQuartetByPlayer[p.id] = (idsSet - p.id) }
        court.teams = generateTeamsForCourt(quartet)
    }

    private fun refillEmptyCourtsSingle(courtIndex: Int) {
        val court = currentCourts.getOrNull(courtIndex) ?: return
        if (court.teams == null && restingPlayers.size >= 4) {
            val avoid = lastCourtGroupIds[court.courtNumber]
            val picks = drawFairFromRestingQueueAvoid(4, avoid)
            picks.forEach {
                incrementConsecutiveGames(it.id)
                resetConsecutiveStays(it.id)
            }
            registerQuartetUsage(picks.map { it.id })
            val idsSet = picks.map { it.id }.toSet()
            lastCourtGroupIds[court.courtNumber] = idsSet
            picks.forEach { p -> lastQuartetByPlayer[p.id] = (idsSet - p.id) }
            court.teams = generateTeamsForCourt(picks)
        }
    }

    // -------------------- Rest hygiene --------------------
    private fun dedupeResting() {
        val seen = HashSet<String>()
        val iter = restingPlayers.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (!seen.add(p.id)) iter.remove()
        }
    }
    private fun removePlayersFromRest(ids: Set<String>) {
        if (ids.isEmpty()) return
        restingPlayers.removeIf { it.id in ids }
    }

    // -------------------- Add Court --------------------
    private fun addCourt() {
        if (restingPlayers.size < 4) {
            Toast.makeText(this, "Need 4 resting players.", Toast.LENGTH_SHORT).show()
            return
        }
        dedupeResting()
        val picks = drawFairFromRestingQueueAvoid(4, null)
        picks.forEach {
            incrementConsecutiveGames(it.id)
            resetConsecutiveStays(it.id)
        }
        registerQuartetUsage(picks.map { it.id })
        val idsSet = picks.map { it.id }.toSet()
        val newNumber = (currentCourts.maxOfOrNull { it.courtNumber } ?: 0) + 1
        lastCourtGroupIds[newNumber] = idsSet
        picks.forEach { p -> lastQuartetByPlayer[p.id] = (idsSet - p.id) }
        currentCourts.add(Court(generateTeamsForCourt(picks), newNumber))
        courtAdapter.notifyDataSetChanged()
        updateRestingPlayersView()
        Toast.makeText(this, "Court $newNumber added.", Toast.LENGTH_SHORT).show()
    }

    // -------------------- Edit Court Dialog --------------------
    private fun showEditCourtDialog(courtIndex: Int) {
        val court = currentCourts.getOrNull(courtIndex) ?: return
        val (teamAOrig, teamBOrig) = court.teams ?: Pair(emptyList(), emptyList())

        val view = layoutInflater.inflate(R.layout.dialog_edit_court, null)
        val rvTeamA = view.findViewById<RecyclerView>(R.id.recyclerViewTeamA)
        val rvTeamB = view.findViewById<RecyclerView>(R.id.recyclerViewTeamB)
        val rvResting = view.findViewById<RecyclerView>(R.id.recyclerViewResting)
        val restingTitle = view.findViewById<TextView>(R.id.textViewRestingTitle)
        val deleteButton = view.findViewById<Button>(R.id.buttonDeleteCourt)

        val teamAMutable = teamAOrig.toMutableList()
        val teamBMutable = teamBOrig.toMutableList()
        val restingMutable = restingPlayers.toMutableList()

        val adapterA = EditCourtPlayerAdapter(teamAMutable)
        val adapterB = EditCourtPlayerAdapter(teamBMutable)
        val adapterRest = EditCourtPlayerAdapter(
            restingMutable,
            sessionStats = sessionStats,
            showSessionGames = true
        )

        var firstSelection: Player? = null
        var firstList: MutableList<Player>? = null
        val adapters = listOf(adapterA, adapterB, adapterRest)

        fun handleSelection(player: Player, list: MutableList<Player>) {
            if (firstSelection == null) {
                firstSelection = player
                firstList = list
                adapters.forEach { it.selectedPlayer = player; it.notifyDataSetChanged() }
            } else {
                if (firstSelection != player) {
                    val p1 = firstSelection!!
                    val l1 = firstList!!
                    val i1 = l1.indexOf(p1)
                    val i2 = list.indexOf(player)
                    if (i1 != -1 && i2 != -1) {
                        l1[i1] = player
                        list[i2] = p1
                    }
                }
                firstSelection = null
                firstList = null
                adapters.forEach { it.selectedPlayer = null; it.notifyDataSetChanged() }
            }
        }

        adapterA.onPlayerSelected = { handleSelection(it, teamAMutable) }
        adapterB.onPlayerSelected = { handleSelection(it, teamBMutable) }
        adapterRest.onPlayerSelected = { handleSelection(it, restingMutable) }

        rvTeamA.layoutManager = LinearLayoutManager(this)
        rvTeamA.adapter = adapterA
        rvTeamB.layoutManager = LinearLayoutManager(this)
        rvTeamB.adapter = adapterB

        if (restingMutable.isEmpty()) {
            restingTitle.visibility = View.GONE
            rvResting.visibility = View.GONE
        } else {
            rvResting.layoutManager = LinearLayoutManager(this)
            rvResting.adapter = adapterRest
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Court ${court.courtNumber}")
            .setView(view)
            .setPositiveButton("Done") { _, _ ->
                currentCourts[courtIndex].teams =
                    if (teamAMutable.isNotEmpty() || teamBMutable.isNotEmpty()) Pair(teamAMutable, teamBMutable) else null
                restingPlayers = LinkedList(restingMutable)
                restingPlayers.forEach {
                    restCount.putIfAbsent(it.id, 0)
                    sessionPlayerIds.add(it.id)
                    sessionStats.putIfAbsent(it.id, SessionStats())
                    resetConsecutiveGamesIfRested(it.id)
                    resetConsecutiveStays(it.id)
                }
                dedupeResting()
                courtAdapter.notifyItemChanged(courtIndex)
                updateRestingPlayersView()
            }
            .setNeutralButton("Cancel", null)
            .create()

        deleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Court")
                .setMessage("Delete this court and move its players to resting?")
                .setPositiveButton("Delete") { _, _ ->
                    val toRest = currentCourts[courtIndex].teams?.toList()?.flatMap { it } ?: emptyList()
                    toRest.forEach {
                        restCount[it.id] = 0
                        restingPlayers.add(it)
                        resetConsecutiveGamesIfRested(it.id)
                        resetConsecutiveStays(it.id)
                    }
                    dedupeResting()
                    currentCourts.removeAt(courtIndex)
                    courtAdapter.notifyDataSetChanged()
                    updateRestingPlayersView()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    // -------------------- Pairing Logic & Helpers --------------------
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
        val m = partnerCount.getOrPut(x) { mutableMapOf() }
        m[y] = (m[y] ?: 0) + 1
    }
    private fun incOpponentCountPair(a: String, b: String) {
        if (a == b) return
        val (x, y) = if (a < b) a to b else b to a
        val m = opponentCount.getOrPut(x) { mutableMapOf() }
        m[y] = (m[y] ?: 0) + 1
    }
    private fun updatePairingStats(winners: List<Player>, losers: List<Player>) {
        if (winners.size == 2) incPartnerCountPair(winners[0].id, winners[1].id)
        if (losers.size == 2) incPartnerCountPair(losers[0].id, losers[1].id)
        for (w in winners) for (l in losers) incOpponentCountPair(w.id, l.id)
    }
    private fun isChad(p: Player) = p.name.equals("chad", true)
    private fun isBudong(p: Player) = p.name.equals("budong", true)
    private fun updateSpecialPairStats(winners: List<Player>, losers: List<Player>) {
        val all = winners + losers
        val hasChad = all.any { isChad(it) }
        val hasBudong = all.any { isBudong(it) }
        if (!hasChad || !hasBudong) return
        specialTogetherCount++
        val teammates = (winners.any { isChad(it) } && winners.any { isBudong(it) }) ||
                (losers.any { isChad(it) } && losers.any { isBudong(it) })
        if (teammates) specialTeammateCount++
    }
    private fun specialBiasCost(teamA: List<Player>, teamB: List<Player>, four: List<Player>): Double {
        val hasChad = four.any { isChad(it) }
        val hasBudong = four.any { isBudong(it) }
        if (!hasChad || !hasBudong) return 0.0
        val teammatesNow = (teamA.any { isChad(it) } && teamA.any { isBudong(it) }) ||
                (teamB.any { isChad(it) } && teamB.any { isBudong(it) })
        val together = specialTogetherCount
        val teamed = specialTeammateCount
        val ratio = if (together > 0) teamed.toDouble() / together else 0.0
        val delta = SPECIAL_DESIRED_RATIO - ratio
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
        val partitions = listOf(
            Pair(listOf(A, B), listOf(C, D)),
            Pair(listOf(A, C), listOf(B, D)),
            Pair(listOf(A, D), listOf(B, C))
        )
        val highs = players.filter { isHighWinPlayer(it) }
        val constrained = when (highs.size) {
            2 -> {
                val highIds = highs.map { it.id }.toSet()
                partitions.filter { (t1, t2) ->
                    t1.count { it.id in highIds } == 1 && t2.count { it.id in highIds } == 1
                }
            }
            3 -> {
                val highest = highs.maxByOrNull { it.winrate }!!
                val low = players.first { it.id !in highs.map { h -> h.id }.toSet() }
                partitions.filter { (t1, t2) ->
                    (t1.contains(highest) && t1.contains(low)) || (t2.contains(highest) && t2.contains(low))
                }
            }
            else -> partitions
        }.ifEmpty { partitions }

        var best = constrained.first()
        var bestCost = pairingCost(best.first, best.second) + specialBiasCost(best.first, best.second, players)
        for (p in constrained.drop(1)) {
            val cost = pairingCost(p.first, p.second) + specialBiasCost(p.first, p.second, players)
            if (cost < bestCost) {
                best = p
                bestCost = cost
            }
        }
        return Pair(best.first.shuffled(), best.second.shuffled())
    }
    private fun generateTeamsForCourt(players: List<Player>): Pair<List<Player>, List<Player>>? {
        if (players.size != 4) return null
        return chooseBestPairingOfFour(players)
    }

    private fun popRandom(list: MutableList<Player>, count: Int): List<Player> {
        val c = min(count, list.size)
        val picked = mutableListOf<Player>()
        repeat(c) { picked += list.removeAt(Random.nextInt(list.size)) }
        return picked
    }

    private fun quartetDiversityScore(players: List<Player>): Int {
        var score = 0
        for (i in 0 until players.size - 1)
            for (j in i + 1 until players.size)
                if (getOpponentCount(players[i].id, players[j].id) == 0) score++
        return score
    }

    private fun registerQuartetUsage(ids: List<String>) {
        val key = ids.sorted().joinToString("|")
        quartetUsage[key] = (quartetUsage[key] ?: 0) + 1
        if (!recentQuartets.contains(key)) {
            recentQuartets.addLast(key)
            while (recentQuartets.size > RECENT_BLOCK_SIZE) recentQuartets.removeFirst()
        }
    }

    private fun drawFairFromRestingQueueAvoid(n: Int, avoidGroup: Set<String>?): List<Player> {
        if (restingPlayers.isEmpty() || n <= 0) return emptyList()
        val indexed = restingPlayers.mapIndexed { idx, p -> Triple(idx, p, restCount[p.id] ?: 0) }
        val withRest = indexed.filter { it.third > 0 }
        val base = if (withRest.size >= n) withRest else indexed
        val sorted = base.shuffled().sortedWith(compareByDescending<Triple<Int, Player, Int>> { it.third })
        val K = min(10, sorted.size)
        val cands = sorted.take(K)
        data class EvalQuartet(val picks: List<Triple<Int, Player, Int>>, val score: Double)
        fun eval(blockRecent: Boolean): EvalQuartet? {
            var best: EvalQuartet? = null
            val S = cands.size
            if (S < n) return null
            for (i in 0 until S - 3)
                for (j in i + 1 until S - 2)
                    for (k in j + 1 until S - 1)
                        for (l in k + 1 until S) {
                            val picks = listOf(cands[i], cands[j], cands[k], cands[l])
                            val players = picks.map { it.second }
                            val ids = players.map { it.id }.toSet()
                            if (avoidGroup != null && ids == avoidGroup) continue
                            var blocked = false
                            for (p in players) {
                                val last = lastQuartetByPlayer[p.id]
                                if (last != null && (ids - p.id) == last) { blocked = true; break }
                            }
                            if (blocked) continue
                            if (players.any { (consecutiveGames[it.id] ?: 0) >= MAX_CONSEC_GAMES }) continue
                            val restSum = picks.sumOf { it.third }
                            val diversity = quartetDiversityScore(players)
                            val key = players.map { it.id }.sorted().joinToString("|")
                            val usage = quartetUsage[key] ?: 0
                            val recent = recentQuartets.contains(key)
                            if (blockRecent && recent) continue
                            val score = REST_WEIGHT * restSum +
                                    DIVERSITY_WEIGHT * diversity -
                                    REPEAT_WEIGHT * usage -
                                    (if (!blockRecent && recent) RECENT_BLOCK_PENALTY else 0.0)
                            if (best == null || score > best!!.score) best = EvalQuartet(picks, score)
                        }
            return best
        }
        var best = eval(blockRecent = true)
        if (best == null) best = eval(blockRecent = false)
        val chosen = best?.picks ?: cands.take(min(n, cands.size))
        chosen.map { it.first }.sortedDescending().forEach { restingPlayers.removeAt(it) }
        val picked = chosen.map { it.second }
        picked.forEach {
            restCount[it.id] = 0
            incrementConsecutiveGames(it.id)
            resetConsecutiveStays(it.id)
        }
        return picked
    }

    // -------------------- Rest UI --------------------
    private fun updateRestingPlayersView() {
        dedupeResting()
        val resting = if (restingPlayers.isEmpty()) "None" else restingPlayers.joinToString(", ") { it.name }
        val sitting = if (sittingOutIds.isEmpty()) "" else
            " | Sitting Out: " + sittingOutIds.mapNotNull { playerById(it)?.name }.sorted().joinToString(", ")
        restingPlayersTextView.text = "Resting: $resting$sitting"
        addCourtButton.isEnabled = restingPlayers.size >= 4
    }

    // -------------------- Add Player (setup) --------------------
    private fun addPlayerToFirestore(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        val id = normalized.lowercase()
        val ref = playersCollection.document(id)

        if (allPlayers.any { it.id == id }) {
            Toast.makeText(this, "$normalized already exists.", Toast.LENGTH_SHORT).show()
            return
        }

        ref.get().addOnSuccessListener { snap ->
            if (snap.exists()) {
                Toast.makeText(this, "$normalized already exists.", Toast.LENGTH_SHORT).show()
            } else {
                val p = Player(
                    name = normalized,
                    wins = 0,
                    losses = 0,
                    gamesPlayed = 0,
                    winrate = 0.0
                )
                ref.set(p).addOnSuccessListener {
                    pendingAutoSelectIds += id
                    Toast.makeText(this, "$normalized added.", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener {
                    Toast.makeText(this, "Add failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Lookup failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------- Sit Out / Restore --------------------
    private data class CourtLocation(val courtIndex: Int, val teamIndex: Int, val posInTeam: Int)

    private fun showSitOutFlow() {
        val playingCourtMap = mutableMapOf<String, Int>()
        currentCourts.forEach { court ->
            court.teams?.let { (a, b) ->
                a.forEach { playingCourtMap[it.id] = court.courtNumber }
                b.forEach { playingCourtMap[it.id] = court.courtNumber }
            }
        }
        val playingPlayers = playingCourtMap.keys.filter { it !in sittingOutIds }
            .mapNotNull { id -> allPlayers.firstOrNull { it.id == id } }
        val restingCandidates = restingPlayers.filter { it.id !in sittingOutIds }
        val combined = (playingPlayers + restingCandidates).distinctBy { it.id }
        if (combined.isEmpty()) {
            Toast.makeText(this, "No eligible players.", Toast.LENGTH_SHORT).show()
            return
        }
        val ordered = combined.sortedWith { p1, p2 ->
            val c1 = playingCourtMap[p1.id]; val c2 = playingCourtMap[p2.id]
            when {
                c1 != null && c2 != null -> c1.compareTo(c2).takeIf { it != 0 }
                    ?: p1.name.lowercase().compareTo(p2.name.lowercase())
                c1 != null -> -1
                c2 != null -> 1
                else -> p1.name.lowercase().compareTo(p2.name.lowercase())
            }
        }
        val display = ordered.map { p ->
            val label = playingCourtMap[p.id]?.let { " (Court $it)" } ?: " (Resting)"
            "${p.name}$label"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Player to Sit Out")
            .setItems(display) { dialog, which ->
                val selected = ordered[which]
                dialog.dismiss()
                val loc = locatePlayerOnCourt(selected.id)
                if (loc == null) {
                    if (restingPlayers.removeIf { it.id == selected.id }) {
                        sittingOutIds.add(selected.id)
                        updateRestingPlayersView()
                    }
                } else {
                    promptReplacementForActivePlayer(selected, loc)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun locatePlayerOnCourt(playerId: String): CourtLocation? {
        currentCourts.forEachIndexed { ci, court ->
            court.teams?.let { (a, b) ->
                val ai = a.indexOfFirst { it.id == playerId }
                if (ai >= 0) return CourtLocation(ci, 0, ai)
                val bi = b.indexOfFirst { it.id == playerId }
                if (bi >= 0) return CourtLocation(ci, 1, bi)
            }
        }
        return null
    }

    private fun promptReplacementForActivePlayer(leaving: Player, loc: CourtLocation) {
        if (restingPlayers.isEmpty()) {
            Toast.makeText(this, "No resting players to replace ${leaving.name}.", Toast.LENGTH_LONG).show()
            return
        }
        dedupeResting()
        val replacements = restingPlayers.toList().sortedBy { it.name.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Replace ${leaving.name} with:")
            .setItems(replacements.map { it.name }.toTypedArray()) { d, idx ->
                d.dismiss()
                applyReplacement(leaving, replacements[idx], loc)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applyReplacement(leaving: Player, replacement: Player, loc: CourtLocation) {
        val court = currentCourts.getOrNull(loc.courtIndex) ?: return
        val teams = court.teams ?: return
        val teamA = teams.first.toMutableList()
        val teamB = teams.second.toMutableList()
        val target = if (loc.teamIndex == 0) teamA else teamB
        if (loc.posInTeam !in target.indices || target[loc.posInTeam].id != leaving.id) {
            Toast.makeText(this, "Player already moved; try again.", Toast.LENGTH_SHORT).show()
            return
        }
        target[loc.posInTeam] = replacement
        court.teams = Pair(teamA, teamB)
        restingPlayers.removeIf { it.id == replacement.id }
        restCount[replacement.id] = 0
        incrementConsecutiveGames(replacement.id)
        resetConsecutiveStays(replacement.id)
        sittingOutIds.add(leaving.id)
        resetConsecutiveGamesIfRested(leaving.id)
        resetConsecutiveStays(leaving.id)
        dedupeResting()
        courtAdapter.notifyItemChanged(loc.courtIndex)
        updateRestingPlayersView()
    }

    private fun showRestoreDialog() {
        if (sittingOutIds.isEmpty()) {
            Toast.makeText(this, "No players sitting out.", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = sittingOutIds.mapNotNull { playerById(it) }.sortedBy { it.name.lowercase() }
        val view = layoutInflater.inflate(R.layout.dialog_restore_players, null)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupRestorePlayers)
        val hint = view.findViewById<TextView>(R.id.textViewRestoreHint)
        if (candidates.isEmpty()) {
            hint.text = "No players to restore."
        } else {
            candidates.forEach { p ->
                chipGroup.addView(Chip(this).apply {
                    text = p.name
                    isCheckable = true
                    tag = p
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Restore Players")
            .setView(view)
            .setPositiveButton("Restore") { _, _ ->
                val selected = mutableListOf<Player>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i) as Chip
                    if (c.isChecked) selected += c.tag as Player
                }
                selected.forEach {
                    sittingOutIds.remove(it.id)
                    restCount.putIfAbsent(it.id, 0)
                    restingPlayers.add(it)
                    resetConsecutiveGamesIfRested(it.id)
                    resetConsecutiveStays(it.id)
                }
                dedupeResting()
                updateRestingPlayersView()
            }.setNegativeButton("Cancel", null).show()
    }

    // -------------------- Late Player --------------------
    private fun showAddLatePlayerDialog() {
        val available = allPlayers.filter { it.id !in sessionPlayerIds && it.id !in sittingOutIds }
        val view = layoutInflater.inflate(R.layout.dialog_add_late_player, null)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupExistingPlayers)
        val edit = view.findViewById<EditText>(R.id.editTextNewPlayerName)
        val addBtn = view.findViewById<Button>(R.id.buttonAddNewPlayer)
        val title = view.findViewById<TextView>(R.id.textViewExistingPlayersTitle)

        if (available.isEmpty()) {
            title.text = "No more players available"
            chipGroup.visibility = View.GONE
        } else {
            title.text = "Add from Existing"
            available.sortedBy { it.name.lowercase() }.forEach { p ->
                chipGroup.addView(Chip(this).apply {
                    text = p.name
                    isCheckable = true
                    tag = p
                })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Late Players")
            .setView(view)
            .setPositiveButton("Add Selected") { _, _ ->
                val chosen = mutableListOf<Player>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i) as Chip
                    if (c.isChecked) chosen += c.tag as Player
                }
                chosen.forEach {
                    restCount[it.id] = 0
                    consecutiveGames[it.id] = 0
                    consecutiveStays[it.id] = 0
                    sessionPlayerIds.add(it.id)
                    sessionStats.putIfAbsent(it.id, SessionStats())
                    restingPlayers.add(it)
                }
                dedupeResting()
                updateRestingPlayersView()
            }
            .setNegativeButton("Cancel", null)
            .create()

        addBtn.setOnClickListener {
            val nm = edit.text.toString().trim()
            if (nm.isNotEmpty()) {
                addLatePlayer(nm) { dialog.dismiss(); showAddLatePlayerDialog() }
            } else {
                Toast.makeText(this, "Player name cannot be empty.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun addLatePlayer(name: String, onSuccess: () -> Unit) {
        val norm = name.lowercase()
        if (allPlayers.any { it.name.equals(norm, true) }) {
            Toast.makeText(this, "$name already exists.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Player(name = name, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
        playersCollection.document(norm).set(p)
            .addOnSuccessListener {
                val withId = p.copy(id = norm)
                allPlayers.add(withId)
                restingPlayers.add(withId)
                restCount[withId.id] = 0
                consecutiveGames[withId.id] = 0
                consecutiveStays[withId.id] = 0
                sessionPlayerIds.add(withId.id)
                sessionStats.putIfAbsent(withId.id, SessionStats())
                dedupeResting()
                updateRestingPlayersView()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error adding player: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // -------------------- Stats --------------------
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
            StatsRow(p.name, cur.games, cur.wins, cur.losses, g, w, p.losses, pct)
        }.sortedBy { it.name.lowercase() }
    }

    private fun showStatsDialog() {
        val rows = buildStatsRows()
        val hScroll = HorizontalScrollView(this)
        val vScroll = ScrollView(this).apply { isFillViewport = true }
        val table = buildStatsTable(rows)
        vScroll.addView(
            table,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        hScroll.addView(
            vScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Session Stats")
            .setView(hScroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildStatsTable(rows: List<StatsRow>): TableLayout {
        val density = resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()
        val NAME_W = 112
        fun headerCell(text: String, span: Int = 1) = TextView(this).apply {
            this.text = text; textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = android.view.Gravity.CENTER
            val lp = TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT,
                TableRow.LayoutParams.WRAP_CONTENT
            )
            lp.span = span; layoutParams = lp
        }
        fun headerNameCell(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minWidth = dp(NAME_W); maxWidth = dp(NAME_W)
            gravity = android.view.Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun bodyCell(text: String, name: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = if (name) android.view.Gravity.START else android.view.Gravity.CENTER
            if (name) {
                minWidth = dp(NAME_W); maxWidth = dp(NAME_W)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        fun vSep() = View(this).apply {
            layoutParams = TableRow.LayoutParams(
                dp(1), TableRow.LayoutParams.MATCH_PARENT
            ).apply { setMargins(0, dp(2), 0, dp(2)) }
            setBackgroundColor(0xFFDDDDDD.toInt())
        }
        val table = TableLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isShrinkAllColumns = false
            isStretchAllColumns = false
        }
        val r1 = TableRow(this)
        r1.addView(headerNameCell("Name"))
        r1.addView(vSep())
        r1.addView(headerCell("Current", 3))
        r1.addView(vSep())
        r1.addView(headerCell("Overall", 4))
        table.addView(r1)
        val r2 = TableRow(this)
        r2.addView(bodyCell("", true))
        r2.addView(vSep())
        listOf("G", "W", "L").forEach { r2.addView(headerCell(it)) }
        r2.addView(vSep())
        listOf("G", "W", "L", "Win%").forEach { r2.addView(headerCell(it)) }
        table.addView(r2)
        rows.forEach { row ->
            val tr = TableRow(this)
            tr.addView(bodyCell(row.name, true))
            tr.addView(vSep())
            tr.addView(bodyCell(row.currentG.toString()))
            tr.addView(bodyCell(row.currentW.toString()))
            tr.addView(bodyCell(row.currentL.toString()))
            tr.addView(vSep())
            tr.addView(bodyCell(row.overallG.toString()))
            tr.addView(bodyCell(row.overallW.toString()))
            tr.addView(bodyCell(row.overallL.toString()))
            tr.addView(bodyCell("${row.overallWinPct}%"))
            table.addView(tr)
        }
        return table
    }

    // -------------------- Firestore Matches Listener --------------------
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
                val pending = snap?.metadata?.hasPendingWrites() == true
                syncBanner?.apply {
                    visibility = if (pending) View.VISIBLE else View.GONE
                    if (pending) text = "Syncing… (offline/pending)"
                }
            }
    }

    private fun detachMatchesListener() {
        matchesListener?.remove()
        matchesListener = null
    }

    // -------------------- Helpers --------------------
    private fun playerById(id: String): Player? = allPlayers.firstOrNull { it.id == id }

    override fun onDestroy() {
        super.onDestroy()
        detachMatchesListener()
    }
}