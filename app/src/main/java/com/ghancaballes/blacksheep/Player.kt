package com.ghancaballes.blacksheep

import com.google.firebase.firestore.Exclude

data class Player(
    @get:Exclude var id: String = "",
    val name: String = "",
    val wins: Int = 0,
    val losses: Int = 0,
    val gamesPlayed: Int = 0,
    val winrate: Double = 0.0
)