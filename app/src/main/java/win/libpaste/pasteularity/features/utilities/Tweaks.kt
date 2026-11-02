package win.libpaste.pasteularity.features.utilities

data class TweakOption(val label: String, val apply: String, val matches: (String) -> Boolean)

/** A reversible, community-known setting. Every tweak offers its default as an option. */
data class Tweak(
    val title: String,
    val description: String,
    val read: String,
    val options: List<TweakOption>,
)

private fun animationScale(name: String) = Tweak(
    title = name.replace('_', ' ').replaceFirstChar(Char::uppercase).removeSuffix(" scale") + " speed",
    description = "How long system animations for this category take. Off makes panels feel quicker.",
    read = "settings get global $name",
    options = listOf(
        TweakOption("Default", "settings put global $name 1") { it == "1" || it == "1.0" || it == "null" },
        TweakOption("Fast", "settings put global $name 0.5") { it == "0.5" },
        TweakOption("Off", "settings put global $name 0") { it == "0" || it == "0.0" },
    ),
)

private fun captureSize(label: String, size: Int?): TweakOption {
    val value = size?.toString().orEmpty()
    return TweakOption(
        label = label,
        apply = "setprop debug.oculus.capture.width '$value' && setprop debug.oculus.capture.height '$value'",
        matches = { it == value },
    )
}

val tweaks = listOf(
    Tweak(
        title = "Stay awake while charging",
        description = "Keeps the display on while the headset is plugged in. Android's own developer setting.",
        read = "settings get global stay_on_while_plugged_in",
        options = listOf(
            TweakOption("Off", "settings put global stay_on_while_plugged_in 0") { it == "0" || it == "null" },
            TweakOption("On", "settings put global stay_on_while_plugged_in 7") { it != "0" && it != "null" },
        ),
    ),
    Tweak(
        title = "Capture size",
        description = "Resolution of the headset's own video capture. Resets on reboot.",
        read = "getprop debug.oculus.capture.width",
        options = listOf(
            captureSize("Default", null),
            captureSize("1024", 1024),
            captureSize("1440", 1440),
            captureSize("1920", 1920),
        ),
    ),
    animationScale("window_animation_scale"),
    animationScale("transition_animation_scale"),
    animationScale("animator_duration_scale"),
)
