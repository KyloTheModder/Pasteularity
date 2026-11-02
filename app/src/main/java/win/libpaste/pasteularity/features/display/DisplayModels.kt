package win.libpaste.pasteularity.features.display

const val PROP_REFRESH = "debug.oculus.refreshRate"
const val PROP_TEXTURE_WIDTH = "debug.oculus.textureWidth"
const val PROP_TEXTURE_HEIGHT = "debug.oculus.textureHeight"
const val PROP_FOVEATION = "debug.oculus.foveation.level"
const val PROP_CPU = "debug.oculus.cpuLevel"
const val PROP_GPU = "debug.oculus.gpuLevel"

val allDisplayProps = listOf(PROP_REFRESH, PROP_TEXTURE_WIDTH, PROP_TEXTURE_HEIGHT, PROP_FOVEATION, PROP_CPU, PROP_GPU)

const val MIN_TEXTURE_SIZE = 256
const val MAX_TEXTURE_SIZE = 8192

val texturePresets = listOf(1024, 1440, 1832, 2048, 2560)
val foveationLevels = (0..4).toList()

/** Community-known ranges per headset, keyed by ro.product.device. Unknown models get the full list. */
data class HeadsetLimits(val refreshRates: List<Int>, val maxPerformanceLevel: Int)

fun limitsFor(device: String): HeadsetLimits = when (device.lowercase()) {
    "monterey" -> HeadsetLimits(listOf(60, 72), 4)
    "hollywood" -> HeadsetLimits(listOf(60, 72, 90, 120), 4)
    "seacliff" -> HeadsetLimits(listOf(72, 90), 4)
    "eureka", "panther" -> HeadsetLimits(listOf(72, 90, 120), 5)
    else -> HeadsetLimits(listOf(60, 72, 90, 120), 5)
}

/** Returns an error to show, or null if [text] is a usable texture dimension. */
fun validateTextureSize(text: String): String? {
    val size = text.toIntOrNull()
    return if (size == null || size !in MIN_TEXTURE_SIZE..MAX_TEXTURE_SIZE) {
        "Enter a number from $MIN_TEXTURE_SIZE to $MAX_TEXTURE_SIZE."
    } else {
        null
    }
}

fun isValidPropValue(prop: String, value: String): Boolean =
    prop in allDisplayProps && value.isNotEmpty() && value.all(Char::isDigit)
