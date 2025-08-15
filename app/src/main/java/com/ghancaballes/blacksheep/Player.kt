package com.ghancaballes.blacksheep

import com.google.firebase.firestore.Exclude

// Change from 'class' to 'data class' to auto-generate equals() and hashCode()
data class Player(
    @get:Exclude var id: String = "", // Exclude ID from Firestore serialization
    val name: String = "",
    val wins: Int = 0,
    val losses: Int = 0
) {
    // Add a read-only winrate property for potential future use
    val winrate: Double
        get() = if (wins + losses > 0) (wins.toDouble() / (wins + losses)) * 100 else 0.0
}