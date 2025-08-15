package com.ghancaballes.blacksheep

// This data class defines the structure for a single court.
// It holds the teams playing and the court's number.
// The 'teams' property is nullable to represent a court that is empty or waiting for players.
data class Court(
    var teams: Pair<List<Player>, List<Player>>?,
    val courtNumber: Int
)