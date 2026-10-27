package fi.tunturi.rows.workout

/** One interval: hold a target for a duration. */
data class Step(
    val label: String,
    val seconds: Int,
    val targetPowerW: Int? = null,
    val targetResistanceTenths: Int? = null,
    val strokeRateSpm: Double? = null,
)

data class Program(val name: String, val steps: List<Step>)

object Programs {
    private fun repeats(count: Int, work: Step, rest: Step): List<Step> =
        List(count) { listOf(work, rest) }.flatten()

    val presets = listOf(
        Program(
            name = "2k test",
            steps = listOf(Step("2000 m", seconds = 480, targetPowerW = 200)),
        ),
        Program(
            name = "30/30 x10",
            steps = repeats(
                count = 10,
                work = Step("hard", seconds = 30, targetPowerW = 250, strokeRateSpm = 28.0),
                rest = Step("easy", seconds = 30, targetPowerW = 100, strokeRateSpm = 20.0),
            ),
        ),
        Program(
            name = "4 x 4 min",
            steps = repeats(
                count = 4,
                work = Step("4 min", seconds = 240, targetPowerW = 220, strokeRateSpm = 26.0),
                rest = Step("2 min", seconds = 120, targetPowerW = 110, strokeRateSpm = 20.0),
            ),
        ),
    )

    /**
     * Only offers the presets the machine can actually control. A console with no
     * target-setting flags would turn these into plain timers, which is not what
     * the workout asks for.
     */
    fun available(canTargetPower: Boolean): List<Program> =
        if (canTargetPower) presets else emptyList()
}
