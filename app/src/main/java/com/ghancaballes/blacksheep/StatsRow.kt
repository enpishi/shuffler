package com.ghancaballes.blacksheep

data class StatsRow(
    val playerId: String,
    val name: String,
    val currentG: Int,
    val currentW: Int,
    val currentL: Int,
    val overallG: Int,
    val overallW: Int,
    val overallL: Int,
    val overallWinPct: Int
)