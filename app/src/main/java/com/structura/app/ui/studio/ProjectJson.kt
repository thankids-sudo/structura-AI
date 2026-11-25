package com.structura.app.ui.studio

import com.structura.core.fem.FrameModel
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Material
import com.structura.core.fem.Member
import com.structura.core.fem.MemberLoad
import com.structura.core.fem.NodalLoad
import com.structura.core.fem.Node
import com.structura.core.fem.PointLoad
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import org.json.JSONArray
import org.json.JSONObject

/**
 * Structura AI project file (*.structura.json). Plain JSON so colleagues can read or edit it;
 * materials and sections are stored once and referenced by index.
 */
object ProjectJson {
    const val FORMAT = "structura-ai-project"
    const val VERSION = 1

    fun write(m: FrameModel): String {
        val mats = m.members.map { it.material }.distinct()
        val secs = m.members.map { it.section }.distinct()
        val o = JSONObject()
        o.put("format", FORMAT); o.put("version", VERSION)
        o.put("title", m.title); o.put("selfWeight", m.selfWeight); o.put("beamTheory", m.beamTheory.name)
        o.put("units", "kN, m; global Z up")
        o.put("materials", JSONArray(mats.map { JSONObject().put("name", it.name).put("E", it.e).put("G", it.g).put("gamma", it.gamma) }))
        o.put("sections", JSONArray(secs.map {
            JSONObject().put("name", it.name).put("A", it.a).put("Iy", it.iy).put("Iz", it.iz).put("J", it.j).put("b", it.b).put("h", it.h)
                .put("Asy", it.asy).put("Asz", it.asz).put("shape", it.shape).put("t", it.t).put("tw", it.tw).put("Iw", it.iw)
        }))
        o.put("nodes", JSONArray(m.nodes.map { JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("z", it.z) }))
        o.put("members", JSONArray(m.members.map {
            JSONObject().put("id", it.id).put("i", it.i).put("j", it.j)
                .put("material", mats.indexOf(it.material)).put("section", secs.indexOf(it.section))
                .put("beta", it.betaDeg).put("hingeI", it.hingeI).put("hingeJ", it.hingeJ)
                .apply { it.springI?.let { k -> put("springI", k) }; it.springJ?.let { k -> put("springJ", k) } }
                .put("offsetI", it.offsetI).put("offsetJ", it.offsetJ).put("truss", it.truss).put("tensionOnly", it.tensionOnly)
        }))
        o.put("supports", JSONArray(m.supports.map { s -> JSONObject().put("node", s.node).put("restraints", JSONArray(s.restraints)).apply { s.springs?.let { put("springs", JSONArray(it)) } } }))
        o.put("planeXZ", m.planeXZ)
        o.put("nodalLoads", JSONArray(m.nodalLoads.map { JSONObject().put("node", it.node).put("case", it.case).put("values", JSONArray(it.values)) }))
        o.put("memberLoads", JSONArray(m.memberLoads.map { l ->
            JSONObject().put("member", l.member).put("case", l.case).put("dir", l.dir.name).put("w", l.w)
                .apply { l.a?.let { put("a", it) }; l.b?.let { put("b", it) }; l.w2?.let { put("w2", it) } }
        }))
        o.put("panels", JSONArray(m.panels.map {
            JSONObject().put("id", it.id).put("nodes", JSONArray(it.nodes)).put("t", it.t).put("E", it.e).put("G", it.g)
                .put("nu", it.nu).put("gamma", it.gamma).put("pressure", it.pressure).put("case", it.case).put("name", it.name)
        }))
        o.put("pointLoads", JSONArray(m.pointLoads.map {
            JSONObject().put("member", it.member).put("case", it.case).put("dir", it.dir.name).put("p", it.p).put("a", it.a)
        }))
        return o.toString(2)
    }

    fun read(text: String): FrameModel {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "Not a Structura AI project file" }
        fun <T> arr(name: String, f: (JSONObject) -> T): List<T> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { f(a.getJSONObject(it)) }
        }
        fun dbl(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }
        val mats = arr("materials") { Material(it.getString("name"), it.getDouble("E"), it.getDouble("G"), it.getDouble("gamma")) }
        val secs = arr("sections") {
            Section(it.getString("name"), it.getDouble("A"), it.getDouble("Iy"), it.getDouble("Iz"), it.getDouble("J"),
                it.optDouble("b", 0.0), it.optDouble("h", 0.0), it.optDouble("Asy", 0.0), it.optDouble("Asz", 0.0),
                it.optString("shape", "GENERAL"), it.optDouble("t", 0.0), it.optDouble("tw", 0.0), it.optDouble("Iw", 0.0))
        }
        return FrameModel(
            nodes = arr("nodes") { Node(it.getInt("id"), it.getDouble("x"), it.getDouble("y"), it.getDouble("z")) },
            members = arr("members") {
                Member(it.getInt("id"), it.getInt("i"), it.getInt("j"), mats[it.getInt("material")], secs[it.getInt("section")],
                    it.optDouble("beta", 0.0), it.optBoolean("hingeI"), it.optBoolean("hingeJ"),
                    if (it.has("springI")) it.getDouble("springI") else null, if (it.has("springJ")) it.getDouble("springJ") else null,
                    it.optDouble("offsetI", 0.0), it.optDouble("offsetJ", 0.0), it.optBoolean("truss"), it.optBoolean("tensionOnly"))
            },
            supports = arr("supports") { s ->
                val r = s.getJSONArray("restraints")
                Support(s.getInt("node"), (0 until 6).map { r.getBoolean(it) }, s.optJSONArray("springs")?.let { k -> (0 until 6).map { k.getDouble(it) } })
            },
            planeXZ = o.optBoolean("planeXZ", false),
            nodalLoads = arr("nodalLoads") { NodalLoad(it.getInt("node"), dbl(it.getJSONArray("values")), it.optString("case", "DL")) },
            memberLoads = arr("memberLoads") {
                MemberLoad(it.getInt("member"), LoadDir.valueOf(it.getString("dir")), it.getDouble("w"), it.optString("case", "DL"),
                    if (it.has("a")) it.getDouble("a") else null, if (it.has("b")) it.getDouble("b") else null,
                    if (it.has("w2")) it.getDouble("w2") else null)
            },
            panels = arr("panels") { p ->
                val ns = p.getJSONArray("nodes")
                com.structura.core.fem.Panel(p.getInt("id"), (0 until ns.length()).map { ns.getInt(it) }, p.getDouble("t"), p.getDouble("E"),
                    p.getDouble("G"), p.getDouble("nu"), p.getDouble("gamma"), p.optDouble("pressure", 0.0), p.optString("case", "LL"),
                    p.optString("name", "Panel"))
            },
            selfWeight = o.optBoolean("selfWeight", true),
            beamTheory = runCatching { com.structura.core.fem.BeamTheory.valueOf(o.optString("beamTheory", "EULER")) }.getOrDefault(com.structura.core.fem.BeamTheory.EULER),
            title = o.optString("title", "Project"),
            pointLoads = arr("pointLoads") {
                PointLoad(it.getInt("member"), LoadDir.valueOf(it.getString("dir")), it.getDouble("p"), it.getDouble("a"), it.optString("case", "DL"))
            },
        )
    }
}
