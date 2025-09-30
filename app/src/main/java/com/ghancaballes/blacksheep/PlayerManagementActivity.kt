package com.ghancaballes.blacksheep

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
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

    // Setup UI
    private lateinit var titleTextView: TextView
    private var syncBanner: TextView? = null
    private lateinit var setupContainer: LinearLayout
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var playerChipGroup: ChipGroup
    private lateinit var startSessionButton: Button

    // Player add
    private lateinit var playerNameInputLayout: TextInputLayout
    private lateinit var playerNameAutoComplete: AutoCompleteTextView
    private lateinit var playerNameAdapter: ArrayAdapter<String>
    private lateinit var addPlayerManualButton: MaterialButton

    // Court stepper (triangles)
    private lateinit var courtMinusButton: ImageButton
    private lateinit var courtPlusButton: ImageButton
    private lateinit var courtCountText: TextView
    private var desiredCourtCount = 1

    // Game phase
    private lateinit var courtsRecyclerView: RecyclerView
    private lateinit var restingPlayersTextView: TextView

    // Action menu
    private lateinit var statsButton: MaterialButton
    private lateinit var addLatePlayerButton: MaterialButton
    private lateinit var addCourtButton: MaterialButton
    private lateinit var endSessionButton: MaterialButton
    private lateinit var sitOutButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var signOutButton: MaterialButton
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

    private var sessionId: String? = null
    private var matchesListener: ListenerRegistration? = null
    private val courtMatchSeq = mutableMapOf<Int, Int>()

    // Pairing memory / fairness
    private val partnershipHistory = mutableMapOf<String, Int>()
    private val recentOpponents = mutableMapOf<String, Set<String>>()
    private val partnerCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val opponentCount = mutableMapOf<String, MutableMap<String, Int>>()
    private val restCount = mutableMapOf<String, Int>()
    private val lastCourtGroupIds = mutableMapOf<Int, Set<String>>()
    private val lastQuartetByPlayer = mutableMapOf<String, Set<String>>()

    private val ALPHA_PARTNER = 1.0
    private val BETA_OPPONENT = 0.5

    // Special pair bias
    private val SPECIAL_DESIRED_RATIO = 0.60
    private val SPECIAL_WEIGHT = 2.0
    private var specialTogetherCount = 0
    private var specialTeammateCount = 0

    // Sitting out
    private val sittingOutIds = mutableSetOf<String>()

    // High win constraint
    private val HIGH_WINRATE_THRESHOLD = 0.65
    private val MIN_GAMES_FOR_HIGH = 10
    private fun isHighWinPlayer(p: Player) =
        p.gamesPlayed >= MIN_GAMES_FOR_HIGH && p.winrate >= HIGH_WINRATE_THRESHOLD

    // Lifecycle
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

    private fun initializeUI() {
        titleTextView = findViewById(R.id.textViewTitle)
        syncBanner = findViewById(R.id.syncBanner)
        setupContainer = findViewById(R.id.setupContainer)
        playersRecyclerView = findViewById(R.id.recyclerViewPlayers)
        playerChipGroup = findViewById(R.id.playerChipGroup)
        playerNameInputLayout = findViewById(R.id.tilPlayerName)
        playerNameAutoComplete = findViewById(R.id.autoCompletePlayerName)
        addPlayerManualButton = findViewById(R.id.buttonAddPlayerManual)
        courtMinusButton = findViewById(R.id.buttonCourtMinus)
        courtPlusButton = findViewById(R.id.buttonCourtPlus)
        courtCountText = findViewById(R.id.textCourtCount)
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
        signOutButton = findViewById(R.id.buttonSignOut)

        playersRecyclerView.layoutManager = LinearLayoutManager(this)
        courtsRecyclerView.layoutManager = LinearLayoutManager(this)

        desiredCourtCount = 1
        updateCourtCountDisplay()

        courtMinusButton.setOnClickListener {
            if (desiredCourtCount > 1) {
                desiredCourtCount--
                updateCourtCountDisplay()
            }
        }
        courtPlusButton.setOnClickListener {
            desiredCourtCount++
            updateCourtCountDisplay()
        }

        playerNameAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        playerNameAutoComplete.setAdapter(playerNameAdapter)
        playerNameAutoComplete.threshold = 1

        playerNameAutoComplete.setOnItemClickListener { _, _, position, _ ->
            val name = playerNameAdapter.getItem(position)?.trim() ?: return@setOnItemClickListener
            val existing = allPlayers.firstOrNull { it.name.equals(name, true) }
            if (existing != null) {
                initialSelectedPlayers.add(existing)
                rebuildPlayerChips()
                playerNameAutoComplete.text.clear()
            }
        }

        playerNameInputLayout.setEndIconOnClickListener { playerNameAutoComplete.text.clear() }
        addPlayerManualButton.setOnClickListener { handleAddPlayerFromInput() }

        playerNameAutoComplete.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleAddPlayerFromInput()
                true
            } else false
        }

        fabMenu.setOnClickListener { toggleActionMenu() }
    }

    private fun initializeAdapters() {
        playerSelectionAdapter = PlayerSelectionAdapter(allPlayers, initialSelectedPlayers) { p, sel ->
            if (sel) initialSelectedPlayers.add(p) else initialSelectedPlayers.remove(p)
        }
        courtAdapter = CourtAdapter(currentCourts, this::handleGameFinished, this::showEditCourtDialog)
        playersRecyclerView.adapter = playerSelectionAdapter
        courtsRecyclerView.adapter = courtAdapter
    }

    // Action menu
    private fun toggleActionMenu(forceShow: Boolean? = null) {
        val show = forceShow ?: !isMenuOpen
        if (show == isMenuOpen) return
        isMenuOpen = show
        animateMenuVisibility(show)
    }

    private fun animateMenuVisibility(show: Boolean) {
        if (show) {
            actionMenuContainer.visibility = View.VISIBLE
            actionMenuContainer.alpha = 0f
            actionMenuContainer.translationY = 32f
            actionMenuContainer.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            actionMenuContainer.animate()
                .alpha(0f)
                .translationY(16f)
                .setDuration(120)
                .withEndAction { actionMenuContainer.visibility = View.GONE }
                .start()
        }
    }

    override fun onBackPressed() {
        if (isMenuOpen) toggleActionMenu(false) else super.onBackPressed()
    }

    private fun setClickListeners() {
        startSessionButton.setOnClickListener { startSession() }
        statsButton.setOnClickListener { showStatsDialog(); toggleActionMenu(false) }
        addLatePlayerButton.setOnClickListener { showAddLatePlayerDialog(); toggleActionMenu(false) }
        addCourtButton.setOnClickListener { addCourt(); toggleActionMenu(false) }
        endSessionButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End Session")
                .setMessage("End the current session?")
                .setPositiveButton("End Session") { _, _ ->
                    switchToSetupView()
                    Toast.makeText(this, "Session Ended", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            toggleActionMenu(false)
        }
        sitOutButton.setOnClickListener { showSitOutFlow(); toggleActionMenu(false) }
        restoreButton.setOnClickListener { showRestoreDialog(); toggleActionMenu(false) }
        signOutButton.setOnClickListener { toggleActionMenu(false); confirmSignOut() }
    }

    // Firestore listener with uniqueness & suggestions update
    private fun listenForPlayerUpdates() {
        playersCollection.orderBy("name").addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w("Firestore", "Listen failed", e)
                return@addSnapshotListener
            }

            val prevSelected = initialSelectedPlayers.map { it.id }.toSet()

            val unique = LinkedHashMap<String, Player>()
            snap?.forEach { doc ->
                val raw = doc.toObject(Player::class.java)
                val p = raw.copy(id = doc.id, name = raw.name.trim())
                val k = p.name.lowercase()
                if (!unique.containsKey(k)) unique[k] = p
            }

            allPlayers.clear()
            allPlayers.addAll(unique.values)

            initialSelectedPlayers.clear()
            allPlayers.forEach { if (prevSelected.contains(it.id)) initialSelectedPlayers.add(it) }

            val names = allPlayers.map { it.name }.sorted()
            playerNameAdapter.clear()
            playerNameAdapter.addAll(names)
            playerNameAdapter.notifyDataSetChanged()

            if (setupContainer.visibility == View.VISIBLE) {
                rebuildPlayerChips()
            } else {
                courtAdapter.notifyDataSetChanged()
                updateRestingPlayersView()
            }
        }
    }

    // Setup / Game view switching
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
        partnershipHistory.clear()
        recentOpponents.clear()
        partnerCount.clear()
        opponentCount.clear()
        restCount.clear()
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        sittingOutIds.clear()

        desiredCourtCount = 1
        updateCourtCountDisplay()
        playerNameAutoComplete.text.clear()

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

    // Chips
    private fun rebuildPlayerChips() {
        if (setupContainer.visibility != View.VISIBLE) return
        playerChipGroup.removeAllViews()
        val selected = initialSelectedPlayers.map { it.id }.toSet()
        allPlayers.sortedBy { it.name.lowercase() }.forEach { player ->
            val chip = Chip(this).apply {
                text = player.name
                isCheckable = true
                isChecked = selected.contains(player.id)
                tag = player
                setChipBackgroundColorResource(R.color.bs_secondary_container)
                setTextColor(ContextCompat.getColor(context, R.color.bs_on_secondary_container))
                setOnClickListener {
                    val checked = (it as Chip).isChecked
                    if (checked) initialSelectedPlayers.add(player) else initialSelectedPlayers.remove(player)
                }
            }
            playerChipGroup.addView(chip)
        }
    }

    // Session start
    private fun startSession() {
        val selectedIds = initialSelectedPlayers.map { it.id }.toSet()
        val playersForSession = allPlayers.filter { it.id in selectedIds }
        val courtCount = desiredCourtCount
        if (courtCount <= 0) {
            Toast.makeText(this, "Invalid number of courts.", Toast.LENGTH_SHORT).show()
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
        }
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0

        currentCourts.clear()
        for (i in 1..courtCount) {
            if (pool.size < 4) break
            val quartet = popRandom(pool, 4)
            val teams = generateTeamsForCourt(quartet)
            currentCourts.add(Court(teams, i))
        }
        restingPlayers = LinkedList(pool)
        switchToGameView()
    }

    // Match recording
    private fun recordMatchAndUpdatePlayerStats(
        winners: List<Player>,
        losers: List<Player>,
        courtIndex: Int
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
                winnerIds = winners.map { it.id }.toSet(),
                loserIds = losers.map { it.id }.toSet()
            )
            courtAdapter.notifyDataSetChanged()
            updateRestingPlayersView()
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Record failed: ${e.message}", Toast.LENGTH_LONG).show()
            courtAdapter.notifyItemChanged(courtIndex)
        }
    }

    private fun applyLocalMatchResult(winnerIds: Set<String>, loserIds: Set<String>) {
        fun updated(p: Player): Player {
            val addW = if (winnerIds.contains(p.id)) 1 else 0
            val addL = if (loserIds.contains(p.id)) 1 else 0
            if (addW == 0 && addL == 0) return p
            val wins = p.wins + addW
            val losses = p.losses + addL
            val games = wins + losses
            val wr = if (games > 0) wins.toDouble() / games else 0.0
            return p.copy(wins = wins, losses = losses, gamesPlayed = games, winrate = wr)
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
            sessionStats.getOrPut(it.id) { SessionStats() }.games++
        }
        winners.forEach { sessionStats[it.id]!!.wins++ }
        losers.forEach { sessionStats[it.id]!!.losses++ }

        partnershipHistory[winners.map { it.name }.sorted().joinToString("|")] =
            (partnershipHistory[winners.map { it.name }.sorted().joinToString("|")] ?: 0) + 1
        partnershipHistory[losers.map { it.name }.sorted().joinToString("|")] =
            (partnershipHistory[losers.map { it.name }.sorted().joinToString("|")] ?: 0) + 1

        updatePairingStats(winners, losers)
        updateSpecialPairStats(winners, losers)

        currentCourts[courtIndex].teams = null
        val courtNumber = currentCourts.getOrNull(courtIndex)?.courtNumber ?: (courtIndex + 1)
        val lastFour = winners + losers
        lastCourtGroupIds[courtNumber] = lastFour.map { it.id }.toSet()
        val ids = lastFour.map { it.id }
        lastFour.forEach { p -> lastQuartetByPlayer[p.id] = ids.filter { it != p.id }.toSet() }

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
                val picks = drawFairFromRestingQueueAvoid(4, avoid)
                court.teams = generateTeamsForCourt(picks)
            }
        }
        courtAdapter.notifyDataSetChanged()
    }

    private fun updateRestingPlayersView() {
        val resting = if (restingPlayers.isEmpty()) "None" else restingPlayers.joinToString(", ") { it.name }
        val sitting = if (sittingOutIds.isEmpty()) "" else " | Sitting Out: " +
                sittingOutIds.mapNotNull { playerById(it)?.name }.sorted().joinToString(", ")
        restingPlayersTextView.text = "Resting: $resting$sitting"
        addCourtButton.isEnabled = restingPlayers.size >= 4
    }

    // Player add / uniqueness
    private fun addPlayerToFirestore(name: String) {
        val norm = name.trim()
        val normKey = norm.lowercase()
        if (allPlayers.any { it.name.trim().lowercase() == normKey }) {
            Toast.makeText(this, "$norm already exists.", Toast.LENGTH_SHORT).show()
            return
        }
        val id = normKey
        val ref = playersCollection.document(id)
        val p = Player(name = norm, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
        ref.set(p).addOnSuccessListener {
            val withId = p.copy(id = id)
            allPlayers.add(withId)
            allPlayers.sortBy { it.name.lowercase() }
            initialSelectedPlayers.add(withId)
            rebuildPlayerChips()
            playerNameAdapter.add(withId.name)
            playerNameAdapter.sort { a, b -> a.lowercase().compareTo(b.lowercase()) }
            playerNameAdapter.notifyDataSetChanged()
            Toast.makeText(this, "$norm added.", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(this, "Add failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleAddPlayerFromInput() {
        val raw = playerNameAutoComplete.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a player name.", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = allPlayers.firstOrNull { it.name.equals(raw, true) }
        if (existing != null) {
            if (!initialSelectedPlayers.contains(existing)) {
                initialSelectedPlayers.add(existing)
                rebuildPlayerChips()
            }
            playerNameAutoComplete.text.clear()
            return
        }
        addPlayerToFirestore(raw)
        playerNameAutoComplete.text.clear()
    }

    // Sit out / restore
    private data class CourtLocation(val courtIndex: Int, val teamIndex: Int, val posInTeam: Int)

    private fun showSitOutFlow() {
        val playingCourtMap = mutableMapOf<String, Int>()
        currentCourts.forEach { court ->
            court.teams?.let { (a, b) ->
                a.forEach { playingCourtMap[it.id] = court.courtNumber }
                b.forEach { playingCourtMap[it.id] = court.courtNumber }
            }
        }

        val all = (playingCourtMap.keys + restingPlayers.map { it.id }).distinct()
            .mapNotNull { id -> allPlayers.firstOrNull { it.id == id } }
            .filter { it.id !in sittingOutIds }

        if (all.isEmpty()) {
            Toast.makeText(this, "No eligible players.", Toast.LENGTH_SHORT).show()
            return
        }

        val ordered = all.sortedWith { p1, p2 ->
            val c1 = playingCourtMap[p1.id]
            val c2 = playingCourtMap[p2.id]
            when {
                c1 != null && c2 != null -> c1.compareTo(c2).takeIf { it != 0 }
                    ?: p1.name.lowercase().compareTo(p2.name.lowercase())
                c1 != null -> -1
                c2 != null -> 1
                else -> p1.name.lowercase().compareTo(p2.name.lowercase())
            }
        }

        val entries = ordered.map { p ->
            SitOutEntry(p, playingCourtMap.containsKey(p.id), playingCourtMap[p.id] ?: -1)
        }

        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_sit_out_select, null)
        val rv = sheetView.findViewById<RecyclerView>(R.id.recyclerViewSitOutPlayers)
        rv.layoutManager = LinearLayoutManager(this)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        rv.adapter = SitOutAdapter(entries) { selected ->
            val loc = locatePlayerOnCourt(selected.id)
            if (loc == null) {
                if (restingPlayers.removeIf { it.id == selected.id }) {
                    sittingOutIds.add(selected.id)
                    Toast.makeText(this, "${selected.name} is sitting out.", Toast.LENGTH_SHORT).show()
                    updateRestingPlayersView()
                }
            } else {
                promptReplacementForActivePlayer(selected, loc)
            }
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
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
        // Filter only resting players (not sittingOut or already playing)
        val replacements = restingPlayers.toList().sortedBy { it.name.lowercase() }
        if (replacements.isEmpty()) {
            Toast.makeText(this, "No resting players to replace ${leaving.name}.", Toast.LENGTH_LONG).show()
            return
        }

        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_replace_player, null)
        val title = sheetView.findViewById<TextView>(R.id.textReplaceTitle)
        val subtitle = sheetView.findViewById<TextView>(R.id.textReplaceSubtitle)
        val rv = sheetView.findViewById<RecyclerView>(R.id.recyclerViewReplacementPlayers)
        val cancelBtn = sheetView.findViewById<TextView>(R.id.buttonCancelReplace)

        title.text = "Replace ${leaving.name}"
        subtitle.text = "Choose a resting player to sub in"

        rv.layoutManager = LinearLayoutManager(this)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        rv.adapter = ReplacementAdapter(replacements) { replacement ->
            dialog.dismiss()
            applyReplacement(leaving, replacement, loc)
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(sheetView)
        dialog.show()
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
        sittingOutIds.add(leaving.id)
        Toast.makeText(this, "${leaving.name} out, ${replacement.name} in.", Toast.LENGTH_SHORT).show()
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
                    setChipBackgroundColorResource(R.color.bs_secondary_container)
                    setTextColor(ContextCompat.getColor(context, R.color.bs_on_secondary_container))
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
                if (selected.isEmpty()) return@setPositiveButton
                selected.forEach {
                    sittingOutIds.remove(it.id)
                    restCount.putIfAbsent(it.id, 0)
                    restingPlayers.add(it)
                }
                Toast.makeText(this, "Restored: ${selected.joinToString { it.name }}", Toast.LENGTH_SHORT).show()
                updateRestingPlayersView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Add Late Player dialog
    private fun showAddLatePlayerDialog() {
        val available = allPlayers.filter { it.id !in sessionPlayerIds && it.id !in sittingOutIds }
        val view = layoutInflater.inflate(R.layout.dialog_add_late_player, null)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupExistingPlayers)
        val newName = view.findViewById<EditText>(R.id.editTextNewPlayerName)
        val addNewBtn = view.findViewById<Button>(R.id.buttonAddNewPlayer)
        val title = view.findViewById<TextView>(R.id.textViewExistingPlayersTitle)

        if (available.isEmpty()) {
            title.text = "No more players available"
            chipGroup.visibility = View.GONE
        } else {
            title.text = "Add from Existing"
            available.sortedBy { it.name.lowercase() }.forEach { player ->
                chipGroup.addView(Chip(this).apply {
                    text = player.name
                    isCheckable = true
                    tag = player
                    setChipBackgroundColorResource(R.color.bs_secondary_container)
                    setTextColor(ContextCompat.getColor(context, R.color.bs_on_secondary_container))
                })
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Late Players")
            .setView(view)
            .setPositiveButton("Add Selected") { _, _ ->
                val selected = mutableListOf<Player>()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i) as Chip
                    if (c.isChecked) selected += c.tag as Player
                }
                if (selected.isNotEmpty()) {
                    selected.forEach {
                        restCount[it.id] = 0
                        sessionPlayerIds.add(it.id)
                        sessionStats.putIfAbsent(it.id, SessionStats())
                    }
                    restingPlayers.addAll(selected)
                    updateRestingPlayersView()
                    Toast.makeText(this, "Added: ${selected.joinToString { it.name }}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        addNewBtn.setOnClickListener {
            val nm = newName.text.toString().trim()
            if (nm.isNotEmpty()) {
                addLatePlayer(nm) {
                    dialog.dismiss()
                    showAddLatePlayerDialog()
                }
            } else {
                Toast.makeText(this, "Player name cannot be empty.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun addLatePlayer(name: String, onSuccess: () -> Unit) {
        val normalized = name.trim().lowercase()
        if (allPlayers.any { it.name.trim().lowercase() == normalized }) {
            Toast.makeText(this, "$name already exists.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Player(name = name.trim(), wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
        playersCollection.document(normalized).set(p)
            .addOnSuccessListener {
                val withId = p.copy(id = normalized)
                allPlayers.add(withId)
                allPlayers.sortBy { it.name.lowercase() }
                restingPlayers.add(withId)
                restCount[withId.id] = 0
                sessionPlayerIds.add(withId.id)
                sessionStats.putIfAbsent(withId.id, SessionStats())
                playerNameAdapter.add(withId.name)
                playerNameAdapter.sort { a, b -> a.lowercase().compareTo(b.lowercase()) }
                playerNameAdapter.notifyDataSetChanged()
                updateRestingPlayersView()
                Toast.makeText(this, "$name added & resting.", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error adding player: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Court + Edit
    private fun addCourt() {
        if (restingPlayers.size < 4) {
            Toast.makeText(this, "Need 4 resting players.", Toast.LENGTH_SHORT).show()
            return
        }
        val picks = drawFairFromRestingQueueAvoid(4, null)
        val teams = generateTeamsForCourt(picks)
        val newNumber = (currentCourts.maxOfOrNull { it.courtNumber } ?: 0) + 1
        currentCourts.add(Court(teams, newNumber))
        courtAdapter.notifyDataSetChanged()
        updateRestingPlayersView()
        Toast.makeText(this, "Court $newNumber added.", Toast.LENGTH_SHORT).show()
    }

    private fun showEditCourtDialog(courtIndex: Int) {
        val court = currentCourts.getOrNull(courtIndex) ?: return
        val (teamAOrig, teamBOrig) = court.teams ?: Pair(emptyList<Player>(), emptyList<Player>())
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
        val adapterResting = EditCourtPlayerAdapter(restingMutable)

        var firstSelection: Player? = null
        var firstList: MutableList<Player>? = null
        val adapters = listOf(adapterA, adapterB, adapterResting)

        fun handleSelection(p: Player, listRef: MutableList<Player>) {
            if (firstSelection == null) {
                firstSelection = p
                firstList = listRef
                adapters.forEach { it.selectedPlayer = p; it.notifyDataSetChanged() }
            } else {
                if (firstSelection != p) {
                    val p1 = firstSelection!!
                    val l1 = firstList!!
                    val i1 = l1.indexOf(p1)
                    val i2 = listRef.indexOf(p)
                    if (i1 >= 0 && i2 >= 0) {
                        l1[i1] = p
                        listRef[i2] = p1
                    }
                }
                firstSelection = null
                firstList = null
                adapters.forEach { it.selectedPlayer = null; it.notifyDataSetChanged() }
            }
        }

        adapterA.onPlayerSelected = { handleSelection(it, teamAMutable) }
        adapterB.onPlayerSelected = { handleSelection(it, teamBMutable) }
        adapterResting.onPlayerSelected = { handleSelection(it, restingMutable) }

        rvTeamA.layoutManager = LinearLayoutManager(this); rvTeamA.adapter = adapterA
        rvTeamB.layoutManager = LinearLayoutManager(this); rvTeamB.adapter = adapterB

        if (restingMutable.isEmpty()) {
            restingTitle.visibility = View.GONE
            rvResting.visibility = View.GONE
        } else {
            rvResting.layoutManager = LinearLayoutManager(this)
            rvResting.adapter = adapterResting
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Court ${court.courtNumber}")
            .setView(view)
            .setPositiveButton("Done") { _, _ ->
                currentCourts[courtIndex].teams =
                    if (teamAMutable.isEmpty() && teamBMutable.isEmpty()) null
                    else Pair(teamAMutable.toList(), teamBMutable.toList())
                restingPlayers = LinkedList(restingMutable)
                restingPlayers.forEach {
                    restCount.putIfAbsent(it.id, 0)
                    sessionPlayerIds.add(it.id)
                    sessionStats.putIfAbsent(it.id, SessionStats())
                }
                courtAdapter.notifyItemChanged(courtIndex)
                updateRestingPlayersView()
            }
            .setNegativeButton("Cancel", null)
            .create()

        deleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Court")
                .setMessage("Delete this court and move players to resting?")
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
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    // Pairing logic
    private fun rating(p: Player) = if (p.gamesPlayed < 10) 0.5 else p.winrate
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
        val map = partnerCount.getOrPut(x) { mutableMapOf() }
        map[y] = (map[y] ?: 0) + 1
    }
    private fun incOpponentCountPair(a: String, b: String) {
        if (a == b) return
        val (x, y) = if (a < b) a to b else b to a
        val map = opponentCount.getOrPut(x) { mutableMapOf() }
        map[y] = (map[y] ?: 0) + 1
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
        val ratio = if (specialTogetherCount > 0) specialTeammateCount.toDouble() / specialTogetherCount else 0.0
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
        val options = listOf(
            Pair(listOf(A, B), listOf(C, D)),
            Pair(listOf(A, C), listOf(B, D)),
            Pair(listOf(A, D), listOf(B, C))
        )
        val highs = players.filter { isHighWinPlayer(it) }
        val highCount = highs.size
        val constrained = when (highCount) {
            2 -> {
                val highIds = highs.map { it.id }.toSet()
                options.filter { (t1, t2) ->
                    t1.count { it.id in highIds } == 1 && t2.count { it.id in highIds } == 1
                }
            }
            3 -> {
                val highest = highs.maxByOrNull { it.winrate }!!
                val low = players.first { it.id !in highs.map { h -> h.id }.toSet() }
                options.filter { (t1, t2) ->
                    (t1.contains(highest) && t1.contains(low)) ||
                            (t2.contains(highest) && t2.contains(low))
                }
            }
            else -> options
        }.ifEmpty { options }

        var best = constrained.first()
        var bestCost = pairingCost(best.first, best.second) + specialBiasCost(best.first, best.second, players)
        for (opt in constrained.drop(1)) {
            val cost = pairingCost(opt.first, opt.second) + specialBiasCost(opt.first, opt.second, players)
            if (cost < bestCost) {
                best = opt
                bestCost = cost
            }
        }
        return Pair(best.first.shuffled(), best.second.shuffled())
    }

    private fun generateTeamsForCourt(players: List<Player>) =
        if (players.size != 4) null else chooseBestPairingOfFour(players)

    private fun popRandom(list: MutableList<Player>, count: Int): List<Player> {
        val c = min(count, list.size)
        val picked = mutableListOf<Player>()
        repeat(c) { picked += list.removeAt(Random.nextInt(list.size)) }
        return picked
    }

    private fun drawFairFromRestingQueueAvoid(n: Int, avoidGroup: Set<String>?): List<Player> {
        if (restingPlayers.isEmpty() || n <= 0) return emptyList()
        val indexed = restingPlayers.mapIndexed { idx, p -> Triple(idx, p, restCount[p.id] ?: 0) }
        val withRest = indexed.filter { it.third > 0 }
        val base = if (withRest.size >= n) withRest else indexed
        val pre = base.shuffled()
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
                                if (last != null && (idsSet - pid) == last) {
                                    ok = false; break
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
        val chosen = best ?: run {
            val tmp = sorted.take(min(n, sorted.size)).toMutableList()
            if (avoidGroup != null && tmp.map { it.second.id }.toSet() == avoidGroup) {
                val repl = sorted.drop(tmp.size).firstOrNull { it.second.id !in avoidGroup }
                if (repl != null) tmp[0] = repl
            }
            tmp
        }
        chosen.map { it.first }.sortedDescending().forEach { restingPlayers.removeAt(it) }
        val picked = chosen.map { it.second }
        picked.forEach { restCount[it.id] = 0 }
        return picked
    }

    private fun buildStatsRows(): List<StatsRow> {
        // We iterate over sessionStats keys so we only consider players who are (or were) in this session.
        return sessionStats.mapNotNull { (id, cur) ->
            val p = allPlayers.firstOrNull { it.id == id } ?: return@mapNotNull null
            val g = p.gamesPlayed
            val w = p.wins
            val pct = if (g > 0) ((w.toDouble() / g) * 100).toInt() else 0
            StatsRow(
                name = p.name,
                currentG = cur.games,
                currentW = cur.wins,
                currentL = cur.losses,
                overallG = g,
                overallW = w,
                overallL = p.losses,
                overallWinPct = pct
            )
        }
            // Sort: most current games first, then most current wins, then name
            .sortedWith(
                compareByDescending<StatsRow> { it.currentG }
                    .thenByDescending { it.currentW }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun showStatsDialog() {
        val rows = buildStatsRows()

        val view = layoutInflater.inflate(R.layout.dialog_session_stats, null)
        val title = view.findViewById<TextView>(R.id.textStatsTitle)
        title.text = "Session Stats (${getTodayDisplayDate()})"

        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewStats)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = StatsAdapter(rows)

        // Measure anticipated height: if too tall, allow scrolling naturally (dialog’s default).
        // Since RecyclerView is wrap_content, small lists show immediately without scroll.
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    // Firestore matches listener
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

    // Sign out
    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Sign out and return to the login screen?")
            .setPositiveButton("Sign Out") { _, _ -> performSignOut() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSignOut() {
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
        restCount.clear()
        lastCourtGroupIds.clear()
        lastQuartetByPlayer.clear()
        specialTogetherCount = 0
        specialTeammateCount = 0
        sittingOutIds.clear()
        auth.signOut()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        startActivity(intent)
        finish()
    }

    private fun updateCourtCountDisplay() {
        courtCountText.text = desiredCourtCount.toString()
    }

    private fun playerById(id: String): Player? = allPlayers.firstOrNull { it.id == id }

    override fun onDestroy() {
        super.onDestroy()
        detachMatchesListener()
    }

    // ----- Inner Adapters for Material Stats & Sit Out -----

    data class SitOutEntry(val player: Player, val playing: Boolean, val court: Int)

    inner class SitOutAdapter(
        private val entries: List<SitOutEntry>,
        private val onClick: (Player) -> Unit
    ) : RecyclerView.Adapter<SitOutAdapter.SitOutVH>() {

        inner class SitOutVH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.textPlayerName)
            val meta: TextView = v.findViewById(R.id.textPlayerMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SitOutVH {
            val v = layoutInflater.inflate(R.layout.item_player_line, parent, false)
            return SitOutVH(v)
        }

        override fun onBindViewHolder(holder: SitOutVH, position: Int) {
            val (p, playing, court) = entries[position]
            holder.name.text = p.name
            holder.meta.text = if (playing) "Court $court" else "Resting"
            holder.itemView.setOnClickListener { onClick(p) }
        }

        override fun getItemCount(): Int = entries.size
    }
}

// Date helper
private fun getTodayDisplayDate(): String = try {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
    } else {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        sdf.format(java.util.Date())
    }
} catch (e: Exception) { "" }