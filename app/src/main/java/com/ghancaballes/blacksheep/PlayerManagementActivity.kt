package com.ghancaballes.blacksheep

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.LinkedList

class PlayerManagementActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore

    // --- UI Elements ---
    private lateinit var titleTextView: TextView

    // Setup Views
    private lateinit var setupContainer: LinearLayout
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var courtCountEditText: EditText
    private lateinit var startSessionButton: Button

    // Game Views
    private lateinit var restingPlayersTextView: TextView
    private lateinit var courtsRecyclerView: RecyclerView
    private lateinit var gameActionButtons: LinearLayout
    private lateinit var addLatePlayerButton: Button
    private lateinit var addCourtButton: Button
    private lateinit var endSessionButton: Button

    // --- Adapters ---
    private lateinit var playerSelectionAdapter: PlayerSelectionAdapter
    private lateinit var courtAdapter: CourtAdapter

    // --- State Management ---
    private val allPlayers = mutableListOf<Player>()
    private val initialSelectedPlayers = mutableSetOf<Player>()
    private val currentCourts = mutableListOf<Court>()
    private var restingPlayers = LinkedList<Player>()

    // Shuffling Algorithm State
    private val partnershipHistory = mutableMapOf<String, Int>()
    private val recentOpponents = mutableMapOf<String, Set<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player_management)

        db = Firebase.firestore
        initializeUI()
        initializeAdapters()
        setClickListeners()
        listenForPlayerUpdates()
        switchToSetupView()
    }

    private fun initializeUI() {
        titleTextView = findViewById(R.id.textViewTitle)

        // Setup Views
        setupContainer = findViewById(R.id.setupContainer)
        playersRecyclerView = findViewById(R.id.recyclerViewPlayers)
        courtCountEditText = findViewById(R.id.editTextCourtCount)
        startSessionButton = findViewById(R.id.buttonStartSession)

        // Game Views
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
        // FIXED: Added back the showEditCourtDialog reference
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
        db.collection("players").orderBy("name").addSnapshotListener { snapshots, e ->
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

        currentCourts.clear()
        restingPlayers.clear()
        initialSelectedPlayers.clear()
        partnershipHistory.clear()
        recentOpponents.clear()

        playerSelectionAdapter.notifyDataSetChanged()
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

        val playerPool = playersForSession.shuffled().toMutableList()

        currentCourts.clear()
        for (i in 1..courtCount) {
            if (playerPool.size < 4) break
            val courtPlayers = playerPool.take(4)
            playerPool.removeAll(courtPlayers)
            val teams = generateTeamsForCourt(courtPlayers)
            currentCourts.add(Court(teams, i))
        }

        restingPlayers = LinkedList(playerPool)

        switchToGameView()
    }

    private fun updatePlayerStats(winners: List<Player>, losers: List<Player>) {
        db.runTransaction { transaction ->
            val allGamePlayers = winners + losers
            val snapshots = mutableMapOf<String, DocumentSnapshot>()

            for (player in allGamePlayers) {
                val playerDocRef = db.collection("players").document(player.id)
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

    private fun handleGameFinished(winners: List<Player>, losers: List<Player>, courtIndex: Int) {
        updatePlayerStats(winners, losers)

        val winnerKey = winners.map { it.name }.sorted().joinToString("|")
        val loserKey = losers.map { it.name }.sorted().joinToString("|")
        partnershipHistory[winnerKey] = partnershipHistory.getOrDefault(winnerKey, 0) + 1
        partnershipHistory[loserKey] = partnershipHistory.getOrDefault(loserKey, 0) + 1

        val finishedPlayers = winners + losers
        restingPlayers.addAll(finishedPlayers)

        currentCourts[courtIndex].teams = null

        refillEmptyCourts()

        updateRestingPlayersView()
    }


    private fun refillEmptyCourts() {
        for (court in currentCourts) {
            if (court.teams == null && restingPlayers.size >= 4) {
                val newCourtPlayers = (0..3).map { restingPlayers.removeFirst() }
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
        val docRef = db.collection("players").document(normalizedName)

        docRef.get().addOnSuccessListener { document ->
            if (!document.exists()) {
                val player = Player(name = playerName, wins = 0, losses = 0, gamesPlayed = 0, winrate = 0.0)
                docRef.set(player)
                    .addOnSuccessListener { Toast.makeText(this, "$playerName added.", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(this, "$playerName already exists.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateTeamsForCourt(players: List<Player>): Pair<List<Player>, List<Player>>? {
        if (players.size != 4) return null

        val highSkillPlayers = players.filter { it.winrate >= 0.8 }.toMutableList()
        val regularSkillPlayers = players.filter { it.winrate < 0.8 }.toMutableList()

        highSkillPlayers.shuffle()
        regularSkillPlayers.shuffle()

        val teamA = mutableListOf<Player>()
        val teamB = mutableListOf<Player>()

        when (highSkillPlayers.size) {
            // 4 high-skill or 0 high-skill (all regular)
            4, 0 -> {
                teamA.add(players[0])
                teamA.add(players[1])
                teamB.add(players[2])
                teamB.add(players[3])
            }
            // 2 high-skill, 2 regular-skill (ideal balanced scenario)
            2 -> {
                teamA.add(highSkillPlayers.removeFirst())
                teamA.add(regularSkillPlayers.removeFirst())
                teamB.add(highSkillPlayers.removeFirst())
                teamB.add(regularSkillPlayers.removeFirst())
            }
            // 1 high-skill, 3 regular-skill
            1 -> {
                teamA.add(highSkillPlayers.removeFirst())
                teamA.add(regularSkillPlayers.removeFirst())
                teamB.add(regularSkillPlayers.removeFirst())
                teamB.add(regularSkillPlayers.removeFirst())
            }
            // 3 high-skill, 1 regular-skill
            3 -> {
                teamA.add(highSkillPlayers.removeFirst())
                teamA.add(highSkillPlayers.removeFirst())
                teamB.add(highSkillPlayers.removeFirst())
                teamB.add(regularSkillPlayers.removeFirst())
            }
        }

        return Pair(teamA.shuffled(), teamB.shuffled())
    }

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
                    if (chip.isChecked) {
                        selectedPlayers.add(chip.tag as Player)
                    }
                }

                if (selectedPlayers.isNotEmpty()) {
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
                    // This will dismiss the main dialog after adding the new player
                    dialog.dismiss()
                    // Re-open the dialog to show the new player in the list.
                    // In a more advanced implementation, you might just update the chipgroup.
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
        db.collection("players").document(normalizedName).set(newPlayer)
            .addOnSuccessListener {
                val playerWithId = newPlayer.copy(id = normalizedName)
                allPlayers.add(playerWithId)
                restingPlayers.add(playerWithId)
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
            val newCourtPlayers = (0..3).map { restingPlayers.removeFirst() }
            val newTeams = generateTeamsForCourt(newCourtPlayers)
            val newCourtNumber = (currentCourts.maxOfOrNull { it.courtNumber } ?: 0) + 1
            currentCourts.add(Court(newTeams, newCourtNumber))

            courtAdapter.notifyDataSetChanged()
            updateRestingPlayersView() // FIXED: Corrected the function call and completed the function body.
            Toast.makeText(this, "Court $newCourtNumber added.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Need at least 4 resting players to add a new court.", Toast.LENGTH_SHORT).show()
        }
    }

    // FIXED: Added the entire missing function back into the class
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

        fun handleSelection(player: Player, list: MutableList<Player>, clickedAdapter: EditCourtPlayerAdapter) {
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