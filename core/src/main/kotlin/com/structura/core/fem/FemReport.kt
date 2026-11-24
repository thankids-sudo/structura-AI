package com.structura.core.fem

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class MemberEnvelope(
    val member: Int,
    val length: Double,
    val horizontal: Boolean,
    val nAbsMax: Double,     // signed N at the station of largest |N|
    val vzAbsMax: Double,    // largest |Vz|
    val myMax: Double,       // largest sagging (+) My, ≥ 0
    val myMin: Double,       // largest hogging (−) My, ≤ 0
    val b: Double,
    val h: Double,
)

object FemReport {

    fun envelope(r: FrameResults, stations: Int = 40): List<MemberEnvelope> = r.model.members.map { m ->
        val st = r.internalForces(m.id, stations)
        val ax = r.memberAxes[m.id]!!
        MemberEnvelope(
            member = m.id,
            length = ax.length,
            horizontal = abs(ax.x.z) < 0.2,
            nAbsMax = st.maxByOrNull { abs(it.n) }!!.n,
            vzAbsMax = st.maxOf { abs(it.vz) },
            myMax = max(0.0, st.maxOf { it.my }),
            myMin = minOf(0.0, st.minOf { it.my }),
            b = m.section.b,
            h = m.section.h,
        )
    }

    /** Envelope (max/min) of each member over several load combinations. */
    fun envelope(all: List<FrameResults>, stations: Int = 40): List<MemberEnvelope> {
        require(all.isNotEmpty())
        val per = all.map { envelope(it, stations) }
        return per.first().indices.map { k ->
            val rows = per.map { it[k] }
            rows.first().copy(
                nAbsMax = rows.maxByOrNull { abs(it.nAbsMax) }!!.nAbsMax,
                vzAbsMax = rows.maxOf { it.vzAbsMax },
                myMax = rows.maxOf { it.myMax },
                myMin = rows.minOf { it.myMin },
            )
        }
    }

    /** Horizontal member with the largest |My| — handed to the IS 456 beam designer. */
    fun criticalBeam(r: FrameResults): MemberEnvelope? = criticalBeam(envelope(r))

    fun criticalBeam(env: List<MemberEnvelope>): MemberEnvelope? =
        env.filter { it.horizontal && it.b > 0 }.maxByOrNull { max(it.myMax, -it.myMin) }

    /** Full results as CSV (opens in Excel): nodes, reactions, member stations. */
    fun csv(r: FrameResults, stations: Int = 10): String = buildString {
        fun f(v: Double) = String.format(Locale.US, "%.4f", v)
        appendLine("Structura AI - FEM results,${r.model.title}")
        appendLine("Units,kN; m; rad")
        appendLine()
        appendLine("NODE DISPLACEMENTS")
        appendLine("Node,X,Y,Z,ux (mm),uy (mm),uz (mm),phi_x (mrad),phi_y (mrad),phi_z (mrad)")
        for (n in r.model.nodes) {
            val d = r.nodeDisplacements[n.id]!!
            appendLine(listOf(n.id.toString(), f(n.x), f(n.y), f(n.z)).plus(d.map { f(it * 1000) }).joinToString(","))
        }
        appendLine()
        appendLine("SUPPORT REACTIONS")
        appendLine("Node,Rx,Ry,Rz,Mx,My,Mz")
        for ((n, v) in r.reactions) appendLine((listOf(n.toString()) + v.map { f(it) }).joinToString(","))
        val eq = r.equilibriumError
        appendLine("Sum of loads,${f(r.totalLoad[0])},${f(r.totalLoad[1])},${f(r.totalLoad[2])}")
        appendLine("Equilibrium error,${f(eq[0])},${f(eq[1])},${f(eq[2])}")
        appendLine()
        appendLine("MEMBER INTERNAL FORCES (RFEM sign convention: local z down on beams, sagging My +)")
        appendLine("Member,Node i,Node j,x (m),N,Vy,Vz,Mt,My,Mz")
        for (m in r.model.members) for (s in r.internalForces(m.id, stations)) {
            appendLine(listOf(m.id.toString(), m.i.toString(), m.j.toString(), f(s.s), f(s.n), f(s.vy), f(s.vz), f(s.mt), f(s.my), f(s.mz)).joinToString(","))
        }
    }
}
