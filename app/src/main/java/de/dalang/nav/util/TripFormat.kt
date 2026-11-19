package de.dalang.nav.util

import de.dalang.nav.navigation.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dateiformat des Fahrtenschreibers (Vertrag mit der Auswertung, format = 1). Reines Kotlin/org.json,
 * keine Android-Abhängigkeit außer org.json -> Unit-Tests. Alle `t` = elapsedRealtimeNanos.
 * NDJSON: genau eine Zeile pro Eintrag, ohne Zeilenumbruch im Inhalt (org.json escaped \n).
 */
object TripFormat {
    const val FORMAT = 1
    const val IMU_HEADER = "t_ns,sensor,x,y,z\n"

    /** null und nicht-endliche Zahlen -> JSON null (JSONObject.put(k, null) würde den Key löschen, NaN wirft). */
    private fun JSONObject.n(k: String, v: Any?): JSONObject {
        val ok = v != null && !(v is Double && !v.isFinite()) && !(v is Float && !v.isFinite())
        // Float -> Double über toString: sonst 3.9000000953674316 statt 3.9
        put(k, if (!ok) JSONObject.NULL else if (v is Float) v.toString().toDouble() else v)
        return this
    }

    fun fixLine(
        t: Long, wall: Long, lat: Double, lng: Double, acc: Float?, speed: Float?,
        bearing: Float?, alt: Double?, vacc: Float?, sacc: Float?
    ): String = JSONObject().n("t", t).n("wall", wall).n("lat", lat).n("lng", lng).n("acc", acc)
        .n("speed", speed).n("bearing", bearing).n("alt", alt).n("vacc", vacc).n("sacc", sacc).toString()

    fun statusLine(t: Long, used: Int, visible: Int, cn0mean: Double?): String =
        JSONObject().n("t", t).n("type", "status").n("sats", used).n("visible", visible)
            .n("cn0mean", cn0mean?.let { Math.round(it * 10) / 10.0 }).toString()

    fun progressLine(
        t: Long, s: Double, lateral: Double?, estimating: Boolean, step: Int,
        distNext: Double, remaining: Double, matched: Boolean
    ): String = JSONObject().n("t", t).n("s", round1(s)).n("lateral", lateral?.let(::round1))
        .n("estimating", estimating).n("step", step).n("distNext", round1(distNext))
        .n("remaining", round1(remaining)).n("matched", matched).toString()

    fun eventLine(t: Long, type: String, text: String?, data: Map<String, Any?>?): String {
        val d = JSONObject()
        data?.forEach { (k, v) -> d.n(k, v) }
        return JSONObject().n("t", t).n("type", type).n("text", text).put("data", d).toString()
    }

    /** geometry von Hand (bis zehntausende Punkte, kein JSONArray-Objektwald), Rest über org.json. */
    fun routeLine(t: Long, reason: String, r: Route): String {
        val sb = StringBuilder(r.geometry.size * 24 + 4096)
        sb.append("{\"t\":").append(t).append(",\"reason\":").append(JSONObject.quote(reason))
        sb.append(",\"distance\":").append(num(r.distance))
        sb.append(",\"duration\":").append(num(r.duration))
        sb.append(",\"geometry\":[")
        r.geometry.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p.lat).append(',').append(p.lng).append(']')
        }
        sb.append("],\"steps\":")
        val steps = JSONArray()
        for (s in r.steps) steps.put(
            JSONObject().n("type", s.maneuver.type).n("modifier", s.maneuver.modifier)
                .n("lat", s.maneuver.location.lat).n("lng", s.maneuver.location.lng)
                .n("distance", s.distance).n("duration", s.duration).n("instruction", s.instruction)
                .n("lanes", s.laneInfo?.lanes?.isNotEmpty() == true)
        )
        return sb.append(steps.toString()).append('}').toString()
    }

    fun metaJson(
        app: String, device: String, android: Int, tester: String, startWall: Long, endWall: Long,
        startElapsedNs: Long, destLat: Double?, destLng: Double?, destName: String?,
        incomplete: Boolean, imuClockOffsetNs: Long?
    ): String = JSONObject().n("format", FORMAT).n("app", app).n("device", device).n("android", android)
        .n("tester", tester).n("startWall", startWall).n("endWall", endWall).n("startElapsedNs", startElapsedNs)
        .put("dest", JSONObject().n("lat", destLat).n("lng", destLng).n("name", destName))
        .n("incomplete", incomplete).n("imuClockOffsetNs", imuClockOffsetNs).toString()

    private fun num(v: Double) = if (v.isFinite()) v.toString() else "null"

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private val POW10 = longArrayOf(1, 10, 100, 1000, 10_000, 100_000)

    /**
     * Eine CSV-Zeile `t_ns,sensor,x,y,z\n` ohne Allokation (Festkomma statt Float.toString: kürzer, schneller,
     * besser komprimierbar). [ncols] = 1 -> y,z leer (Pressure). Rundung: acc 3, gyr/grv 4, pre 3 Stellen.
     */
    fun imuRow(sb: StringBuilder, t: Long, sensor: String, dec: Int, x: Float, y: Float, z: Float, ncols: Int) {
        sb.append(t).append(',').append(sensor).append(',')
        fixed(sb, x.toDouble(), dec)
        sb.append(',')
        if (ncols > 1) fixed(sb, y.toDouble(), dec)
        sb.append(',')
        if (ncols > 2) fixed(sb, z.toDouble(), dec)
        sb.append('\n')
    }

    fun fixed(sb: StringBuilder, v: Double, dec: Int) {
        val scale = POW10[dec]
        var n = Math.round(v * scale)
        if (n < 0) { sb.append('-'); n = -n }
        sb.append(n / scale)
        if (dec > 0) {
            sb.append('.')
            val f = n % scale
            var d = scale / 10
            while (d > 0) { sb.append(((f / d) % 10).toInt()); d /= 10 }
        }
    }
}
