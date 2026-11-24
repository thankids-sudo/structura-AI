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
        o.put("title", m.title); o.put("selfWeight", m.selfWeight)
        o.put("units", "kN, m; global Z up")
        o.put("materials", JSONArray(mats.map { JSONObject().put("name", it.name).put("E", it.e).put("G", it.g).put("gamma", it.gamma) }))
        o.put("sections", JSONArray(secs.map {
            JSONObject().put("name", it.name).put("A", it.a).put("Iy", it.iy).put("Iz", it.iz).put("J", it.j).put("b", it.b).put("h", it.h)
        }))
        o.put("nodes", JSONArray(m.nodes.map { JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("z", it.z) }))
        o.put("members", JSONArray(m.members.map {
            JSONObject().put("id", it.id).put("i", it.i).put("j", it.j)
                .put("material", mats.indexOf(it.material)).put("section", secs.indexOf(it.section))
                .put("beta", it.betaDeg).put("hingeI", it.hingeI).put("hingeJ", it.hingeJ)
        }))
        o.put("supports", JSONArray(m.supports.map { JSONObject().put("node", it.node).put("restraints", JSONArray(it.restraints)) }))
        o.put("nodalLoads", JSONArray(m.nodalLoads.map { JSONObject().put("node", it.node).put("case", it.case).put("values", JSONArray(it.values)) }))
        o.put("memberLoads", JSONArray(m.memberLoads.map { JSONObject().put("member", it.member).put("case", it.case).put("dir", it.dir.name).put("w", it.w) }))
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
                it.optDouble("b", 0.0), it.optDouble("h", 0.0))
        }
        return FrameModel(
            nodes = arr("nodes") { Node(it.getInt("id"), it.getDouble("x"), it.getDouble("y"), it.getDouble("z")) },
            members = arr("members") {
                Member(it.getInt("id"), it.getInt("i"), it.getInt("j"), mats[it.getInt("material")], secs[it.getInt("section")],
                    it.optDouble("beta", 0.0), it.optBoolean("hingeI"), it.optBoolean("hingeJ"))
            },
            supports = arr("supports") { s -> val r = s.getJSONArray("restraints"); Support(s.getInt("node"), (0 until 6).map { r.getBoolean(it) }) },
            nodalLoads = arr("nodalLoads") { NodalLoad(it.getInt("node"), dbl(it.getJSONArray("values")), it.optString("case", "DL")) },
            memberLoads = arr("memberLoads") { MemberLoad(it.getInt("member"), LoadDir.valueOf(it.getString("dir")), it.getDouble("w"), it.optString("case", "DL")) },
            selfWeight = o.optBoolean("selfWeight", true),
            title = o.optString("title", "Project"),
            pointLoads = arr("pointLoads") {
                PointLoad(it.getInt("member"), LoadDir.valueOf(it.getString("dir")), it.getDouble("p"), it.getDouble("a"), it.optString("case", "DL"))
            },
        )
    }
}
