package com.structura.app.project

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.structura.app.DesignSession
import com.structura.app.ui.studio.ProjectJson
import com.structura.core.fem.FrameModel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Project identity shown on reports. */
data class ProjectInfo(
    val id: String, val name: String, val client: String = "", val location: String = "", val engineer: String = "",
    val created: Long = System.currentTimeMillis(), val modified: Long = System.currentTimeMillis(),
    val members: Int = 0,
) {
    val modifiedText: String get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(modified))
}

/**
 * Projects saved on the phone (files/projects/<id>.json): identity, the 3D model and the last inputs of every design
 * module, so a project re-opens exactly as it was left. The open project autosaves on every Studio change and design run.
 */
object ProjectStore {
    var current by mutableStateOf<ProjectInfo?>(null)
        private set
    /** module id → field → value (strings as typed). */
    val designInputs = HashMap<String, Map<String, String>>()

    private fun dir(c: Context) = File(c.filesDir, "projects").apply { mkdirs() }
    private fun file(c: Context, id: String) = File(dir(c), "$id.json")

    fun list(c: Context): List<ProjectInfo> = dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching { info(JSONObject(f.readText())) }.getOrNull()
    }.sortedByDescending { it.modified }

    private fun info(o: JSONObject) = ProjectInfo(o.getString("id"), o.getString("name"), o.optString("client"), o.optString("location"),
        o.optString("engineer"), o.optLong("created"), o.optLong("modified"), o.optJSONObject("model")?.optJSONArray("members")?.length() ?: 0)

    fun create(c: Context, name: String, client: String, location: String, engineer: String, model: FrameModel) {
        val id = "p" + System.currentTimeMillis().toString(36)
        current = ProjectInfo(id, name.ifBlank { "Untitled project" }, client, location, engineer)
        designInputs.clear()
        DesignSession.currentModel = model.copy(title = current!!.name)
        c.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().putString("engineer", engineer).putString("last", id).apply()
        save(c)
    }

    fun open(c: Context, id: String): Boolean = runCatching {
        val o = JSONObject(file(c, id).readText())
        current = info(o)
        designInputs.clear()
        o.optJSONObject("designs")?.let { d ->
            for (k in d.keys()) { val f = d.getJSONObject(k); designInputs[k] = f.keys().asSequence().associateWith { f.getString(it) } }
        }
        DesignSession.currentModel = o.optJSONObject("model")?.let { ProjectJson.read(it.toString()) }
        c.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().putString("last", id).apply()
        true
    }.getOrDefault(false)

    fun lastId(c: Context) = c.getSharedPreferences("projects", Context.MODE_PRIVATE).getString("last", null)
    fun lastEngineer(c: Context) = c.getSharedPreferences("projects", Context.MODE_PRIVATE).getString("engineer", "") ?: ""

    fun save(c: Context) {
        val p = current ?: return
        val now = System.currentTimeMillis()
        val model = DesignSession.currentModel
        current = p.copy(modified = now, members = model?.members?.size ?: 0)
        val o = JSONObject().put("id", p.id).put("name", p.name).put("client", p.client).put("location", p.location)
            .put("engineer", p.engineer).put("created", p.created).put("modified", now)
        model?.let { o.put("model", JSONObject(ProjectJson.write(it))) }
        val d = JSONObject()
        for ((k, v) in designInputs) d.put(k, JSONObject(v as Map<*, *>))
        o.put("designs", d)
        file(c, p.id).writeText(o.toString())
    }

    fun saveInputs(c: Context, module: String, values: Map<String, String>) { designInputs[module] = values; save(c) }

    fun delete(c: Context, id: String) {
        file(c, id).delete()
        if (current?.id == id) current = null
    }

    fun rename(c: Context, name: String) { current = current?.copy(name = name); DesignSession.currentModel = DesignSession.currentModel?.copy(title = name); save(c) }
}
