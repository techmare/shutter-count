package com.techmare.shuttercount.core

enum class Brand(val label: String) { NIKON("Nikon"), SONY("Sony"), CANON("Canon"), OTHER("Other") }

/**
 * Display names and approximate rated shutter life. Ratings are the figures
 * makers publish or the ones commonly quoted for each class of body; they are
 * a guide for judging wear, not a guarantee.
 */
object CameraModels {

    fun brandOf(make: String?): Brand {
        val m = make?.uppercase() ?: return Brand.OTHER
        return when {
            m.contains("NIKON") -> Brand.NIKON
            m.contains("SONY") -> Brand.SONY
            m.contains("CANON") -> Brand.CANON
            else -> Brand.OTHER
        }
    }

    /** Human name, e.g. "ILCE-7M3" -> "Sony α7 III", "NIKON Z 6_2" -> "Nikon Z6 II". */
    fun displayName(brand: Brand, model: String?): String {
        val raw = model?.trim().orEmpty()
        if (raw.isEmpty()) return brand.label
        return when (brand) {
            Brand.SONY -> "Sony " + sonyName(raw)
            Brand.NIKON -> "Nikon " + nikonName(raw)
            Brand.CANON -> if (raw.uppercase().startsWith("CANON")) raw else "Canon $raw"
            Brand.OTHER -> raw
        }
    }

    private fun roman(s: String): String = s
        .replace(Regex("M2$"), " II").replace(Regex("M3$"), " III")
        .replace(Regex("M4$"), " IV").replace(Regex("M5$"), " V").replace(Regex("M6$"), " VI")

    private fun sonyName(code: String): String = when {
        code.startsWith("ILCE-") -> "α" + roman(code.removePrefix("ILCE-"))
        code.startsWith("ILCA-") -> "α" + roman(code.removePrefix("ILCA-"))
        code.startsWith("SLT-") -> "α" + code.removePrefix("SLT-").removePrefix("A")
        code.startsWith("ILME-") -> code.removePrefix("ILME-")
        else -> code
    }

    private fun nikonName(model: String): String {
        var m = model.removePrefix("NIKON").trim()
        // "Z 6_2" -> "Z6 II", "Z 50" -> "Z50", "Z f" -> "Zf"
        m = m.replace(Regex("^Z\\s+"), "Z")
        m = m.replace("_2", " II").replace("_3", " III")
        return m
    }

    /** Approximate rated shutter actuations, or null if unknown / no mechanical shutter. */
    /** A shutter rating. [official] = published by the maker; otherwise an estimate for the camera's class. */
    data class Rating(val value: Int, val official: Boolean)

    private fun off(v: Int) = Rating(v, true)
    private fun est(v: Int) = Rating(v, false)

    fun ratedLife(brand: Brand, model: String?): Int? = rating(brand, model)?.value

    fun rating(brand: Brand, model: String?): Rating? {
        val m = model?.uppercase()?.trim() ?: return null
        return when (brand) {
            Brand.NIKON -> nikonRated(m.removePrefix("NIKON").trim())
            Brand.SONY -> sonyRated(m)
            Brand.CANON -> canonRated(m.removePrefix("CANON").trim())
            Brand.OTHER -> null
        }
    }

    // Official figures are the ones makers have published (e.g. listed by Photo Review,
    // "How relevant are shutter actuation cycles?"). Entry-level bodies rarely get one.
    private fun nikonRated(m: String): Rating? {
        val z = m.replace(" ", "")
        return when {
            z == "Z9" || z == "Z8" -> null // no mechanical shutter
            z.matches(Regex("Z(6|7|6_2|7_2)")) -> off(200_000)
            z.matches(Regex("Z(5|5_2|6_3|F)")) -> est(200_000)
            z.matches(Regex("Z(30|50|50_2|FC)")) -> est(100_000)
            m.matches(Regex("D(3|3S|3X)")) -> off(300_000)
            m.matches(Regex("D(4|4S|5|6)")) -> off(400_000)
            m.matches(Regex("D(500|800|800E|810|810A|850)")) -> off(200_000)
            m == "DF" || m.matches(Regex("D(300|300S|600|610|700|750|780|7000|7100|7200|7500)")) -> off(150_000)
            m.matches(Regex("D(200|90|80)")) -> off(100_000)
            m.matches(Regex("D(5\\d00|3\\d00|40|40X|60)")) -> est(100_000)
            m.matches(Regex("D(2H|2HS|2X|2XS)")) -> off(150_000)
            m.matches(Regex("D(70|70S|50|100|1|1H|1X)")) -> est(50_000)
            else -> null
        }
    }

    private fun sonyRated(m: String): Rating? = when {
        m == "ILCE-9M3" -> null // global shutter, no mechanical shutter count
        m.matches(Regex("ILCE-(7RM2|7RM3A?|9)")) -> off(500_000)
        m.matches(Regex("ILCE-(1|1M2|9M2|7RM4A?|7RM5|7SM3)")) -> est(500_000)
        m.matches(Regex("ILCA-99M2")) -> est(300_000)
        m.matches(Regex("ILCE-(7|7R|7S|7M2|7SM2|7M3|7M4|7M5|7C|7CM2|7CR)")) -> est(200_000)
        m.matches(Regex("ILCE-(6300|6400|6500|6600|6700)")) -> est(200_000)
        m.matches(Regex("ILCE-(5000|5100|6000|6100|3000|3500|QX1)")) -> est(100_000)
        m.startsWith("ZV-E10") -> est(100_000)
        m.startsWith("NEX-") || m.startsWith("SLT-") || m.startsWith("ILCA-") -> est(100_000)
        else -> null
    }

    private fun canonRated(m: String): Rating? {
        val s = m.removePrefix("EOS").trim().replace(Regex("\\s+"), " ").replace("R6M2", "R6 MARK II")
        return when {
            s.matches(Regex("-?1D X MARK (II|III)")) -> off(500_000)
            s.matches(Regex("-?1D X")) -> off(400_000)
            s.matches(Regex("-?1D MARK IV")) -> off(300_000)
            s.matches(Regex("R(1|3|5|5 MARK II)")) -> off(500_000)
            s.matches(Regex("R5 C")) -> est(500_000)
            s == "R6" -> off(300_000)
            s.matches(Regex("R6 MARK (II|III)")) -> est(300_000)
            s == "R" -> off(200_000)
            s == "RP" -> off(100_000)
            s == "R7" -> est(200_000)
            s.matches(Regex("R(8|10|50|50 V|100)")) -> est(100_000)
            s.matches(Regex("5D( MARK (II|III|IV))?|5DS R?")) -> off(150_000)
            s == "7D MARK II" -> off(200_000)
            s == "7D" -> off(150_000)
            s.matches(Regex("6D( MARK II)?|60D|70D|80D|750D|760D")) -> off(100_000)
            s == "90D" -> est(100_000)
            s.matches(Regex("\\d{2}D|\\d{3}D|\\d{4}D|M\\d.*|REBEL.*|KISS.*")) -> est(100_000)
            else -> null
        }
    }

    /** Where Canon R bodies keep their counter in a photo's CameraInfo block (ExifTool), or null. */
    fun canonPhotoOffset(model: String?): Int? {
        val m = model ?: return null
        return when {
            Regex("\\bEOS (R6m2|R6 Mark II|R8|R50)$").containsMatchIn(m) -> 0x0d29
            Regex("\\bEOS R[56]$").containsMatchIn(m) -> 0x0af1
            // found by comparing two R100 photos 5 shots apart (623 -> 628); confirmed it keeps
            // counting after a card format (632, 633), so it is the lifetime counter
            Regex("\\bEOS R100$").containsMatchIn(m) -> 0x0acf
            else -> null
        }
    }

    /** A Canon EOS R mirrorless body ("Canon EOS RP", "Canon EOS R10"), whatever its photo support. */
    fun isCanonR(model: String?): Boolean = Regex("\\bEOS R\\w*$", RegexOption.IGNORE_CASE).containsMatchIn(model.orEmpty().trim())

    /** Canon's DeviceInfo "serial" is a long hex ID of the USB connection, not the body serial. */
    fun isDeviceId(s: String): Boolean = s.length >= 20 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    enum class Wear(val label: String) { LIKE_NEW("Like new"), GOOD("Good"), USED("Used"), HIGH("High wear"), BEYOND("Beyond rating") }

    fun wear(count: Long, rated: Int): Wear {
        val pct = count.toDouble() / rated
        return when {
            pct < 0.10 -> Wear.LIKE_NEW
            pct < 0.40 -> Wear.GOOD
            pct < 0.75 -> Wear.USED
            pct <= 1.0 -> Wear.HIGH
            else -> Wear.BEYOND
        }
    }
}
