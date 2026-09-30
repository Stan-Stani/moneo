package com.poketrek.step

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.poketrek.emu.MovementGateBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.budgetStore by preferencesDataStore("movement_budget")

private val KEY_BUDGET = intPreferencesKey("budget_tiles")

// Legacy single-int "tiles per step" key, kept only for migration on first
// launch after upgrade. Newer builds use KEY_RATIO_NUM / KEY_RATIO_DEN.
private val KEY_RATIO_LEGACY = intPreferencesKey("tiles_per_step")
private val KEY_RATIO_NUM = intPreferencesKey("tiles_per_step_num")
private val KEY_RATIO_DEN = intPreferencesKey("tiles_per_step_den")
private val KEY_STEP_CARRY = intPreferencesKey("step_carry_remainder")

private val KEY_LAST_SENSOR_VALUE = longPreferencesKey("last_sensor_value")
private val KEY_GATE_ENABLED = booleanPreferencesKey("gate_enabled")
private val KEY_DEBUG_HUD = booleanPreferencesKey("debug_hud_visible")
private val KEY_HAPTIC_ON_STEP = booleanPreferencesKey("haptic_on_step")
private val KEY_RARE_CANDY_COST = intPreferencesKey("rare_candy_cost_tiles")
private val KEY_TALLY_DAY = longPreferencesKey("tally_epoch_day")
private val KEY_TALLY_STEPS = longPreferencesKey("tally_steps")
private val KEY_TALLY_TILES = intPreferencesKey("tally_tiles_spent")

private const val DEFAULT_RATIO_NUM = 4
private const val DEFAULT_RATIO_DEN = 1
const val MIN_RATIO_PART = 1
// Generous upper bound for custom user-entered ratios. The discrete slider
// table is independent of this and tops out much lower; this only widens
// the floor/ceiling for the typed text-field input.
const val MAX_RATIO_PART = 1000

/** Default tile cost to mint one Rare Candy. Tunable from the Shop UI. */
const val DEFAULT_RARE_CANDY_COST = 1000
const val MIN_RARE_CANDY_COST = 1
const val MAX_RARE_CANDY_COST = 100_000

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/**
 * Parses a user-typed step ratio into reduced (num, den) ints. Accepts:
 *   - integer:  "4"        → 4/1
 *   - decimal:  "2.5"      → 5/2
 *   - fraction: "5/2", "1/3" → as-typed, reduced by GCD
 *
 * Returns null on empty/malformed input or if either reduced part falls
 * outside [MIN_RATIO_PART, MAX_RATIO_PART]. Pure — kept out of MovementBudget
 * so the parser can be tested without an Android Context.
 */
fun parseRatioInput(input: String): Pair<Int, Int>? {
    val s = input.trim()
    if (s.isEmpty()) return null
    val (rawNum, rawDen) = when {
        '/' in s -> {
            val parts = s.split('/')
            if (parts.size != 2) return null
            val n = parts[0].trim().toLongOrNull() ?: return null
            val d = parts[1].trim().toLongOrNull() ?: return null
            n to d
        }
        '.' in s -> {
            val parts = s.split('.')
            if (parts.size != 2) return null
            val intPart = parts[0].trim().ifEmpty { "0" }
            val fracPart = parts[1].trim()
            if (fracPart.isEmpty()
                || !intPart.all(Char::isDigit)
                || !fracPart.all(Char::isDigit)) return null
            // Cap fractional digits so 0.000000001 doesn't blow past Long range.
            if (fracPart.length > 9) return null
            val combined = (intPart + fracPart).toLongOrNull() ?: return null
            var denom = 1L
            repeat(fracPart.length) { denom *= 10L }
            combined to denom
        }
        else -> {
            val n = s.toLongOrNull() ?: return null
            n to 1L
        }
    }
    if (rawNum <= 0 || rawDen <= 0) return null
    val g = gcd(rawNum, rawDen)
    val n = rawNum / g
    val d = rawDen / g
    if (n < MIN_RATIO_PART || d < MIN_RATIO_PART) return null
    if (n > MAX_RATIO_PART || d > MAX_RATIO_PART) return null
    return n.toInt() to d.toInt()
}

/**
 * Computes how many whole tiles to credit for [deltaSteps] real-world steps
 * given a fractional ratio of [num]/[den] tiles per step and a leftover
 * [carryIn] remainder from previous calls. Returns (tilesAwarded, carryOut).
 *
 * Pure function — pulled out of MovementBudget so it can be tested without
 * an Android Context.
 */
internal fun creditTiles(
    deltaSteps: Long,
    num: Int,
    den: Int,
    carryIn: Int,
): Pair<Long, Int> {
    require(num >= 1 && den >= 1) { "ratio parts must be >= 1" }
    if (deltaSteps <= 0) return 0L to carryIn
    val totalCredit = carryIn.toLong() + deltaSteps * num.toLong()
    val tilesAwarded = totalCredit / den
    val carryOut = (totalCredit % den).toInt()
    return tilesAwarded to carryOut
}

/**
 * Steps walked and tiles spent on one local calendar day ([epochDay]).
 * Adding to a tally from an earlier day starts the new day from zero.
 * Pure, for JVM tests.
 */
data class DailyTally(val epochDay: Long, val steps: Long = 0, val tilesSpent: Int = 0) {
    fun addSteps(today: Long, n: Long): DailyTally =
        if (today == epochDay) copy(steps = steps + n) else DailyTally(today, steps = n)

    fun addTilesSpent(today: Long, n: Int): DailyTally =
        if (today == epochDay) copy(tilesSpent = tilesSpent + n) else DailyTally(today, tilesSpent = n)

    /** This tally as seen on [today]: itself, or empty if it's from another day. */
    fun on(today: Long): DailyTally = if (today == epochDay) this else DailyTally(today)
}

private fun localEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

/**
 * Tracks the player's movement budget — how many in-game tiles they're allowed
 * to walk before they need more real-world steps.
 *
 * The budget is credited at a fractional ratio of [tilesPerStepNum] /
 * [tilesPerStepDen] tiles per real-world step. Examples:
 *   - 4/1: easy, 1 step = 4 tiles (default)
 *   - 1/1: realistic, 1 step = 1 tile
 *   - 1/2: hard, 2 steps required for 1 tile
 *
 * Sub-1 ratios mean a single step often credits zero tiles; the leftover
 * lives in [stepCarry] and combines with the next step's contribution. The
 * carry is persisted across launches and reset on ratio change.
 *
 * Process-wide singleton (use [get]) so the foreground StepCounterService and
 * the Activity share state.
 */
class MovementBudget private constructor(private val context: Context) : MovementGateBudget {

    companion object {
        @Volatile private var instance: MovementBudget? = null

        fun get(context: Context): MovementBudget {
            return instance ?: synchronized(this) {
                instance ?: MovementBudget(context.applicationContext).also { instance = it }
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _budget = MutableStateFlow(0)
    override val budget: StateFlow<Int> = _budget.asStateFlow()

    private val _tilesPerStepNum = MutableStateFlow(DEFAULT_RATIO_NUM)
    val tilesPerStepNum: StateFlow<Int> = _tilesPerStepNum.asStateFlow()

    private val _tilesPerStepDen = MutableStateFlow(DEFAULT_RATIO_DEN)
    val tilesPerStepDen: StateFlow<Int> = _tilesPerStepDen.asStateFlow()

    private val _gateEnabled = MutableStateFlow(false)
    override val gateEnabled: StateFlow<Boolean> = _gateEnabled.asStateFlow()

    private val _debugHudVisible = MutableStateFlow(false)
    val debugHudVisible: StateFlow<Boolean> = _debugHudVisible.asStateFlow()

    private val _hapticOnStep = MutableStateFlow(true)
    val hapticOnStep: StateFlow<Boolean> = _hapticOnStep.asStateFlow()

    private val _rareCandyCost = MutableStateFlow(DEFAULT_RARE_CANDY_COST)
    val rareCandyCost: StateFlow<Int> = _rareCandyCost.asStateFlow()

    /**
     * Fires (tilesAwarded) every time the budget is credited. Subscribers
     * (e.g. the haptic vibrator in StepCounterService, future HUD flash
     * animations, step-history graph) consume this without having to poll
     * the budget StateFlow.
     */
    private val _creditedTiles = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val creditedTiles: SharedFlow<Int> = _creditedTiles.asSharedFlow()

    private val _today = MutableStateFlow(DailyTally(localEpochDay()))
    /** Steps and tiles spent today; read through [DailyTally.on] to roll over at midnight. */
    val today: StateFlow<DailyTally> = _today.asStateFlow()

    private var lastSensorValue: Long = -1L
    private var stepCarry: Int = 0

    init {
        runBlocking {
            val prefs = context.budgetStore.data.first()
            _budget.value = prefs[KEY_BUDGET] ?: 0
            val legacy = prefs[KEY_RATIO_LEGACY]
            val num = prefs[KEY_RATIO_NUM] ?: legacy ?: DEFAULT_RATIO_NUM
            val den = prefs[KEY_RATIO_DEN] ?: DEFAULT_RATIO_DEN
            _tilesPerStepNum.value = num.coerceIn(MIN_RATIO_PART, MAX_RATIO_PART)
            _tilesPerStepDen.value = den.coerceIn(MIN_RATIO_PART, MAX_RATIO_PART)
            stepCarry = (prefs[KEY_STEP_CARRY] ?: 0).coerceAtLeast(0)
            lastSensorValue = prefs[KEY_LAST_SENSOR_VALUE] ?: -1L
            _today.value = DailyTally(
                epochDay = prefs[KEY_TALLY_DAY] ?: localEpochDay(),
                steps = prefs[KEY_TALLY_STEPS] ?: 0L,
                tilesSpent = prefs[KEY_TALLY_TILES] ?: 0,
            ).on(localEpochDay())
            _gateEnabled.value = prefs[KEY_GATE_ENABLED] ?: false
            _debugHudVisible.value = prefs[KEY_DEBUG_HUD] ?: false
            _hapticOnStep.value = prefs[KEY_HAPTIC_ON_STEP] ?: true
            _rareCandyCost.value = (prefs[KEY_RARE_CANDY_COST] ?: DEFAULT_RARE_CANDY_COST)
                .coerceIn(MIN_RARE_CANDY_COST, MAX_RARE_CANDY_COST)
        }
    }

    /**
     * Called by the step source whenever Sensor.TYPE_STEP_COUNTER reports a new
     * cumulative count. Computes a delta from [lastSensorValue], handles reboot
     * (counter resets to 0 → next value is smaller than last), and credits the
     * delta * ratio tiles to the budget — carrying the fractional remainder.
     */
    fun onSensorValue(cumulativeSteps: Long) {
        val previous = lastSensorValue
        lastSensorValue = cumulativeSteps
        val delta: Long = when {
            previous < 0 -> 0L                       // first ever read, just rebase
            cumulativeSteps < previous -> 0L          // reboot, rebase silently
            else -> cumulativeSteps - previous
        }
        creditAndPersist(delta)
        scope.launch {
            context.budgetStore.edit { it[KEY_LAST_SENSOR_VALUE] = cumulativeSteps }
        }
    }

    /**
     * Debug entry point: adds [realSteps] steps as if the sensor had reported
     * them. Useful for testing on the Android Emulator (which has no
     * TYPE_STEP_COUNTER hardware).
     */
    fun debugAddSteps(realSteps: Int) {
        if (realSteps <= 0) return
        creditAndPersist(realSteps.toLong())
    }

    private fun creditAndPersist(deltaSteps: Long) {
        if (deltaSteps <= 0) return
        _today.value = _today.value.addSteps(localEpochDay(), deltaSteps)
        persistTally()
        val (tiles, newCarry) = creditTiles(
            deltaSteps,
            _tilesPerStepNum.value,
            _tilesPerStepDen.value,
            stepCarry,
        )
        val carryChanged = newCarry != stepCarry
        stepCarry = newCarry
        if (tiles > 0) {
            addTiles(tiles.toInt().coerceAtLeast(0))
        } else if (carryChanged) {
            persistCarry(newCarry)
        }
    }

    /** Consumes one tile of the budget. Returns false if the budget was 0. */
    override fun consumeOneTile(): Boolean {
        val current = _budget.value
        if (current <= 0) return false
        _budget.value = current - 1
        persistBudget(current - 1)
        _today.value = _today.value.addTilesSpent(localEpochDay(), 1)
        persistTally()
        return true
    }

    /**
     * Atomic-ish spend of [tiles] from the budget. Returns true if the budget
     * had enough and was decremented; false otherwise (budget unchanged).
     *
     * Atomicity caveat: this isn't strictly thread-safe against concurrent
     * spenders, but the only writers are (a) the emu thread's gate consumer,
     * which spends 1 tile/frame, and (b) the UI thread's Buy button. The
     * compare-and-set on a single _budget.value read is good enough here.
     */
    fun spend(tiles: Int): Boolean {
        if (tiles <= 0) return true
        val current = _budget.value
        if (current < tiles) return false
        val next = current - tiles
        _budget.value = next
        persistBudget(next)
        return true
    }

    /**
     * Returns [tiles] to the budget. Used to undo a [spend] when the
     * downstream action (e.g. shop write) fails after we've already debited.
     * Skips the credit-tiles SharedFlow so the haptic pulse doesn't fire on
     * what is effectively a transactional rollback.
     */
    fun refund(tiles: Int) {
        if (tiles <= 0) return
        val next = (_budget.value + tiles).coerceAtMost(Int.MAX_VALUE / 2)
        _budget.value = next
        persistBudget(next)
    }

    fun setRareCandyCost(value: Int) {
        val v = value.coerceIn(MIN_RARE_CANDY_COST, MAX_RARE_CANDY_COST)
        if (v == _rareCandyCost.value) return
        _rareCandyCost.value = v
        scope.launch {
            context.budgetStore.edit { it[KEY_RARE_CANDY_COST] = v }
        }
    }

    /**
     * Sets the credit ratio to [num] tiles per [den] real-world steps. Resets
     * the carry remainder so a switch from e.g. 1:8 → 8:1 doesn't drop a
     * stale partial credit.
     */
    fun setRatio(num: Int, den: Int) {
        val n = num.coerceIn(MIN_RATIO_PART, MAX_RATIO_PART)
        val d = den.coerceIn(MIN_RATIO_PART, MAX_RATIO_PART)
        if (n == _tilesPerStepNum.value && d == _tilesPerStepDen.value) return
        _tilesPerStepNum.value = n
        _tilesPerStepDen.value = d
        stepCarry = 0
        scope.launch {
            context.budgetStore.edit {
                it[KEY_RATIO_NUM] = n
                it[KEY_RATIO_DEN] = d
                it[KEY_STEP_CARRY] = 0
                it.remove(KEY_RATIO_LEGACY)
            }
        }
    }

    override fun setGateEnabled(value: Boolean) {
        _gateEnabled.value = value
        scope.launch {
            context.budgetStore.edit { it[KEY_GATE_ENABLED] = value }
        }
    }

    fun setDebugHudVisible(value: Boolean) {
        _debugHudVisible.value = value
        scope.launch {
            context.budgetStore.edit { it[KEY_DEBUG_HUD] = value }
        }
    }

    fun setHapticOnStep(value: Boolean) {
        _hapticOnStep.value = value
        scope.launch {
            context.budgetStore.edit { it[KEY_HAPTIC_ON_STEP] = value }
        }
    }

    /**
     * Zeros the tile budget, clears the fractional carry remainder, and
     * rebases the hardware step counter so the next sensor sample is treated
     * as the new origin (i.e. previously-walked steps are forgotten). The
     * ratio and toggles are left alone.
     *
     * "Rebase" rather than "set lastSensorValue = 0": the counter is
     * monotonic-since-boot so we can't actually reset it; instead we mark
     * lastSensorValue = -1 and let [onSensorValue]'s first-read branch
     * adopt whatever the next sample happens to be as the new baseline.
     */
    fun resetBudgetAndRebaseSteps() {
        _budget.value = 0
        stepCarry = 0
        lastSensorValue = -1L
        scope.launch {
            context.budgetStore.edit {
                it[KEY_BUDGET] = 0
                it[KEY_STEP_CARRY] = 0
                it.remove(KEY_LAST_SENSOR_VALUE)
            }
        }
    }

    private fun addTiles(tiles: Int) {
        val next = (_budget.value + tiles).coerceAtMost(Int.MAX_VALUE / 2)
        _budget.value = next
        _creditedTiles.tryEmit(tiles)
        val carryToWrite = stepCarry
        scope.launch {
            context.budgetStore.edit {
                it[KEY_BUDGET] = next
                it[KEY_STEP_CARRY] = carryToWrite
            }
        }
    }

    private fun persistBudget(value: Int) {
        scope.launch {
            context.budgetStore.edit { it[KEY_BUDGET] = value }
        }
    }

    private fun persistTally() {
        val t = _today.value
        scope.launch {
            context.budgetStore.edit {
                it[KEY_TALLY_DAY] = t.epochDay
                it[KEY_TALLY_STEPS] = t.steps
                it[KEY_TALLY_TILES] = t.tilesSpent
            }
        }
    }

    private fun persistCarry(value: Int) {
        scope.launch {
            context.budgetStore.edit { it[KEY_STEP_CARRY] = value }
        }
    }
}
