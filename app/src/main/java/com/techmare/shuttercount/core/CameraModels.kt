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
    fun ratedLife(brand: Brand, model: String?): Int? {
        val m = model?.uppercase()?.trim() ?: return null
        return when (brand) {
            Brand.NIKON -> nikonRated(m.removePrefix("NIKON").trim())
            Brand.SONY -> sonyRated(m)
            Brand.CANON -> canonRated(m.removePrefix("CANON").trim())
            Brand.OTHER -> null
        }
    }

    private fun nikonRated(m: String): Int? {
        val z = m.replace(" ", "")
        return when {
            z == "Z9" -> null // no mechanical shutter
            z.matches(Regex("Z(30|50|50_2|FC|5_2)")) -> if (z == "Z5_2") 200_000 else 100_000
            z.matches(Regex("Z(5|6|6_2|6_3|7|7_2|8|F)")) -> 200_000
            m.matches(Regex("D(3|3S|3X)")) -> 300_000
            m.matches(Regex("D(4|4S|5|6)")) -> 400_000
            m.matches(Regex("D(500|800|800E|810|810A|850)")) -> 200_000
            m == "DF" || m.matches(Regex("D(300|300S|600|610|700|750|780|7000|7100|7200|7500)")) -> 150_000
            m.matches(Regex("D(200|90|80|5\\d00|3\\d00|40|40X|60)")) -> 100_000
            m.matches(Regex("D(2H|2HS|2X|2XS)")) -> 150_000
            m.matches(Regex("D(70|70S|50|100|1|1H|1X)")) -> 50_000
            else -> null
        }
    }

    private fun sonyRated(m: String): Int? = when {
        m.matches(Regex("ILCE-(1|1M2|9|9M2|7RM2|7RM3A?|7RM4A?|7RM5|7SM3)")) -> 500_000
        m == "ILCE-9M3" -> null // global shutter, no mechanical shutter count
        m.matches(Regex("ILCA-99M2")) -> 300_000
        m.matches(Regex("ILCE-(7|7R|7S|7M2|7SM2|7M3|7M4|7M5|7C|7CM2|7CR)")) -> 200_000
        m.matches(Regex("ILCE-(6100|6300|6400|6500|6600|6700)")) -> 200_000
        m.matches(Regex("ILCE-(5000|5100|6000|3000|3500|QX1)")) -> 100_000
        m.startsWith("ZV-E10") -> 100_000
        m.startsWith("NEX-") || m.startsWith("SLT-") || m.startsWith("ILCA-") -> 100_000
        else -> null
    }

    private fun canonRated(m: String): Int? {
        val s = m.removePrefix("EOS").trim().replace(Regex("\\s+"), " ")
        return when {
            s.matches(Regex("-?1D X MARK III")) -> 500_000
            s.matches(Regex("-?1D X( MARK II)?")) -> 400_000
            s.matches(Regex("-?1D MARK IV")) -> 300_000
            s.matches(Regex("R(1|3|5|5 MARK II|5 C)")) -> 500_000
            s.matches(Regex("R(6|6 MARK II|6 MARK III)")) -> 300_000
            s.matches(Regex("R(7|)")) -> 200_000
            s.matches(Regex("R(P|8|10|50|100)")) -> 100_000
            s.matches(Regex("5D( MARK (II|III|IV))?|5DS R?")) -> 150_000
            s.matches(Regex("7D MARK II")) -> 200_000
            s.matches(Regex("7D")) -> 150_000
            s.matches(Regex("90D")) -> 120_000
            s.matches(Regex("6D( MARK II)?")) -> 100_000
            s.matches(Regex("\\d{2}D|\\d{3}D|\\d{4}D|M\\d.*|REBEL.*|KISS.*")) -> 100_000
            else -> null
        }
    }

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
