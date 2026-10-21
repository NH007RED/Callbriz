package com.nh.callbrize.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nh.callbrize.ai.ComputerPlayer
import com.nh.callbrize.ai.Difficulty
import com.nh.callbrize.data.HistoryEntry
import com.nh.callbrize.data.Repository
import com.nh.callbrize.data.Settings
import com.nh.callbrize.data.Stats
import com.nh.callbrize.domain.Card
import com.nh.callbrize.game.GameEngine
import com.nh.callbrize.game.GameRules
import com.nh.callbrize.game.GameState
import com.nh.callbrize.game.Phase
import com.nh.callbrize.game.PlayerInfo
import com.nh.callbrize.game.TrickTakingRules
import com.nh.callbrize.navigation.Navigator
import com.nh.callbrize.navigation.Screen
import com.nh.callbrize.util.Feedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class GameMode { SINGLE, PASS_AND_PLAY }

/**
 * Orchestrates a match. All rule decisions are delegated to [GameEngine];
 * this class only schedules computer turns, handover screens and persistence.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)
    val feedback = Feedback(app)
    val rules: GameRules = TrickTakingRules()
    val nav = Navigator()

    val settings: StateFlow<Settings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val stats: StateFlow<Stats> =
        repo.stats.stateIn(viewModelScope, SharingStarted.Eagerly, Stats())
    val history: StateFlow<List<HistoryEntry>> =
        repo.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var mode by mutableStateOf(GameMode.SINGLE)
        private set
    var passNames by mutableStateOf(listOf("Player 2", "Player 3", "Player 4"))
        private set
    var game by mutableStateOf<GameState?>(null)
        private set
    var selected by mutableStateOf<Card?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var handoverSeat by mutableStateOf<Int?>(null)
        private set
    var viewerSeat by mutableStateOf(0)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    private val engine = GameEngine(rules)
    private var bots: Map<Int, ComputerPlayer> = emptyMap()
    private var loopJob: Job? = null
    private var toastJob: Job? = null
    private var matchSaved = false

    init {
        viewModelScope.launch {
            settings.collect {
                feedback.soundOn = it.sound
                feedback.vibrationOn = it.vibration
            }
        }
    }

    // ---------- settings / profile ----------
    fun selectMode(m: GameMode) { mode = m }
    fun setPassName(index: Int, name: String) {
        passNames = passNames.toMutableList().also { it[index] = name.take(16) }
    }
    fun saveProfile(name: String, avatar: Int) { viewModelScope.launch { repo.saveProfile(name, avatar) } }
    fun setSound(v: Boolean) { viewModelScope.launch { repo.setSound(v) } }
    fun setVibration(v: Boolean) { viewModelScope.launch { repo.setVibration(v) } }
    fun setAnimations(v: Boolean) { viewModelScope.launch { repo.setAnimations(v) } }
    fun setDifficulty(d: Difficulty) { viewModelScope.launch { repo.setDifficulty(d) } }
    fun clearStats() { viewModelScope.launch { repo.clearStatsAndHistory() } }

    // ---------- match lifecycle ----------
    fun startMatch(name: String, avatar: Int, difficulty: Difficulty) {
        loopJob?.cancel()
        val cleanName = name.trim().take(16).ifBlank { "Player" }
        viewModelScope.launch { repo.saveProfile(cleanName, avatar) }
        val players = if (mode == GameMode.SINGLE) {
            listOf(
                PlayerInfo(cleanName, avatar, true),
                PlayerInfo("Rohan", 1, false),
                PlayerInfo("Priya", 2, false),
                PlayerInfo("Arjun", 3, false)
            )
        } else {
            listOf(PlayerInfo(cleanName, avatar, true)) + passNames.mapIndexed { i, n ->
                PlayerInfo(n.trim().ifBlank { "Player ${i + 2}" }.take(16), (avatar + i + 1) % 8, true)
            }
        }
        bots = if (mode == GameMode.SINGLE) {
            (1..3).associateWith { ComputerPlayer(rules, difficulty) }
        } else emptyMap()
        game = engine.newMatch(players, rules.roundsPerMatch)
        selected = null
        paused = false
        matchSaved = false
        viewerSeat = 0
        handoverSeat = null
        toast = null
        feedback.deal()
        launchLoop()
    }

    fun nextRound() {
        if (!engine.startNextRound().ok) return
        game = engine.snapshot()
        selected = null
        feedback.deal()
        nav.replace(Screen.Game)
        launchLoop()
    }

    fun quitToMenu() {
        loopJob?.cancel()
        paused = false
        handoverSeat = null
        selected = null
        nav.replaceAll(Screen.Menu)
        game = null
    }

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun onBackground() {
        if (game != null && nav.current == Screen.Game) paused = true
    }

    fun confirmHandover() {
        val seat = handoverSeat ?: return
        viewerSeat = seat
        handoverSeat = null
        selected = null
    }

    // ---------- human actions ----------
    fun legalNow(): List<Card> {
        val s = game ?: return emptyList()
        if (s.phase != Phase.PLAYING || s.trickPending) return emptyList()
        return engine.legalCards(s.turn)
    }

    fun humanBid(bid: Int) {
        val s = game ?: return
        if (paused || handoverSeat != null) return
        if (s.phase != Phase.BIDDING || s.turn != viewerSeat || !s.players[s.turn].isHuman) return
        val r = engine.placeBid(s.turn, bid)
        if (r.ok) {
            feedback.select()
            game = engine.snapshot()
            launchLoop()
        } else showToast(r.error ?: "Invalid bid")
    }

    fun onCardTapped(card: Card) {
        val s = game ?: return
        if (paused || handoverSeat != null) return
        if (s.phase != Phase.PLAYING || s.trickPending || s.turn != viewerSeat) return
        if (card !in engine.legalCards(s.turn)) {
            showToast("Not allowed now: follow suit, then trump, and beat the highest card if you can")
            return
        }
        selected = card
        feedback.select()
    }

    /** Called when the player swipes a card upwards. The engine still validates the move. */
    fun playCard(card: Card) {
        val s = game ?: return
        if (paused || handoverSeat != null) return
        if (s.phase != Phase.PLAYING || s.trickPending || s.turn != viewerSeat) return
        val r = engine.playCard(s.turn, card)
        if (r.ok) {
            selected = null
            feedback.cardPlayed()
            game = engine.snapshot()
            launchLoop()
        } else {
            showToast("Not allowed now: follow suit, then trump, and beat the highest card if you can")
        }
    }

    fun playSelected() {
        val s = game ?: return
        val card = selected ?: return
        if (paused || handoverSeat != null) return
        if (s.phase != Phase.PLAYING || s.trickPending || s.turn != viewerSeat) return
        val r = engine.playCard(s.turn, card)
        if (r.ok) {
            selected = null
            feedback.cardPlayed()
            game = engine.snapshot()
            launchLoop()
        } else showToast(r.error ?: "Invalid move")
    }

    private fun showToast(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2200); toast = null }
    }

    // ---------- game loop (computer turns, trick resolution, handovers) ----------
    private fun launchLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            while (isActive) {
                while (paused) delay(100)
                val s = game ?: return@launch
                if (s.phase == Phase.ROUND_OVER || s.phase == Phase.MATCH_OVER) {
                    roundEnded(s)
                    return@launch
                }
                if (s.trickPending) {
                    delay(if (settings.value.animations) 1300L else 700L)
                    if (paused) continue
                    engine.resolveTrick()
                    feedback.trickDone()
                    game = engine.snapshot()
                    continue
                }
                val player = s.players[s.turn]
                if (!player.isHuman) {
                    delay(if (settings.value.animations) 800L else 400L)
                    if (paused) continue
                    botAct(s)
                } else {
                    if (mode == GameMode.PASS_AND_PLAY && s.turn != viewerSeat && handoverSeat == null) {
                        handoverSeat = s.turn
                    }
                    return@launch
                }
            }
        }
    }

    private fun botAct(s: GameState) {
        val seat = s.turn
        val bot = bots[seat] ?: return
        if (s.phase == Phase.BIDDING) {
            val bid = bot.chooseBid(s.hands[seat])
            if (!engine.placeBid(seat, bid).ok) engine.placeBid(seat, rules.minBid)
        } else {
            val card = bot.chooseCard(seat, s.hands[seat], s.currentTrick, s.bids[seat] ?: 1, s.tricksWon[seat])
            if (engine.playCard(seat, card).ok) {
                feedback.cardPlayed()
            } else {
                // Safety net: should never happen, but never stall the game.
                engine.legalCards(seat).firstOrNull()?.let { engine.playCard(seat, it) }
            }
        }
        game = engine.snapshot()
    }

    private suspend fun roundEnded(s: GameState) {
        delay(700)
        if (nav.current != Screen.Game) return
        if (s.phase == Phase.MATCH_OVER) {
            finishMatch(s)
            nav.replace(Screen.MatchResults)
        } else {
            nav.replace(Screen.RoundResults)
        }
    }

    private fun finishMatch(s: GameState) {
        if (matchSaved) return
        matchSaved = true
        val max = s.totals.max()
        val winners = s.totals.indices.filter { s.totals[it] == max }
        val single = mode == GameMode.SINGLE
        val humanWon = single && 0 in winners
        feedback.result(if (single) humanWon else true)
        val rank = 1 + s.totals.count { it > s.totals[0] }
        val entry = HistoryEntry(
            time = System.currentTimeMillis(),
            mode = if (single) "Single Player" else "Pass-and-Play",
            difficulty = if (single) settings.value.difficulty.label else "-",
            winner = s.players[winners.first()].name,
            humanScore = s.totals[0],
            rank = rank,
            players = s.players.joinToString(", ") { it.name }
        )
        viewModelScope.launch { repo.recordMatch(entry, single, humanWon) }
    }

    override fun onCleared() {
        feedback.release()
        super.onCleared()
    }
}
