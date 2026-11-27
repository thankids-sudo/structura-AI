package com.structura.core.fem

import com.structura.core.rcc.Status
import com.structura.core.steel.SteelModelCheck

/**
 * One list of everything a checking engineer would look at: model and analysis findings ([ResultCheck]: supports,
 * free nodes, combinations per IS 456 Table 18 / IS 875-5, equilibrium, uplift, load path, service deflection
 * span/250, storey drift 0.004h, stability) plus every failed or advisory member design check (IS 456 / IS 800 /
 * IS 13920) with its clause.
 */
object Validation {
    enum class Level { ERROR, WARN, INFO, OK }
    class Item(val level: Level, val topic: String, val message: String, val clause: String, val member: Int? = null, val node: Int? = null)
    class Report(val items: List<Item>) {
        val errors get() = items.count { it.level == Level.ERROR }
        val warnings get() = items.count { it.level == Level.WARN }
    }

    fun run(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, rcc: List<RccModelDesign.MemberDesign>,
            steel: List<SteelModelCheck.MemberCheck>, footings: List<com.structura.core.rcc.FoundationDesign.Result> = emptyList()): Report {
        val items = ArrayList<Item>()
        val review = ResultCheck.review(model, results)
        for (f in review.findings) items += Item(
            when (f.level) { ResultCheck.Level.ERROR -> Level.ERROR; ResultCheck.Level.WARN -> Level.WARN; ResultCheck.Level.INFO -> Level.INFO; ResultCheck.Level.OK -> Level.OK },
            f.topic, f.message, f.clause, f.member,
        )
        for (d in rcc) for (c in d.checks) if (c.status == Status.FAIL) items += Item(Level.ERROR, "${d.kind} M${d.member}", "${c.item}: ${c.value} — ${c.requirement}", c.clause, d.member)
        for (s in steel) for (c in s.result.checks) if (c.status == Status.FAIL) items += Item(Level.ERROR, "Steel M${s.member}", "${c.item}: ${c.value} — ${c.requirement}", c.clause, s.member)
        for (f in footings) for (c in f.checks) if (c.status == Status.FAIL) items += Item(Level.ERROR, "Footing N${f.node}", "${c.item}: ${c.value} — ${c.requirement}", c.clause, node = f.node)
        val designed = rcc.map { it.member }.toSet() + steel.map { it.member }
        val undesigned = model.members.filter { it.id !in designed }
        if (undesigned.isNotEmpty()) items += Item(Level.INFO, "Not designed", "${undesigned.size} member(s) have no design: " +
            undesigned.take(12).joinToString { "M${it.id} ${it.section.name}" } + " — use a rectangular RCC section (M-grade) or an IS 808 / hollow steel section.", "")
        if (rcc.isNotEmpty() && rcc.all { it.ok } && steel.all { it.ok } && footings.all { it.ok }) items += Item(Level.OK, "Member design", "All ${rcc.size + steel.size} designed members satisfy their checks.", "IS 456 · IS 800")
        return Report(items.sortedBy { it.level.ordinal })
    }
}
