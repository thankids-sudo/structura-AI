package com.structura.core.fem

/**
 * Verification models after the NSU CEE 330L STAAD.Pro lab manual (Chapters III–V), converted to SI.
 * Also offered as Studio templates (Templates → Benchmark examples). Load values not fixed by the brief are
 * stated here and in the README so the runs are reproducible.
 */
object Benchmarks {
    const val FT = 0.3048
    const val KIP = 4.4482216
    const val KIP_FT = KIP / FT          // kN/m
    const val INCH = 0.0254

    /**
     * B1 — 2D pitched concrete portal (Ch. III): A(0,0) B(0,10) C(12,20) D(27,20) E(39,10) F(39,0) ft, 15"×15",
     * f'c 4 ksi (ACI Ec), fixed at A and F. DL = self-weight. LL = 15 kip/ft partial UDL normal to BC over 0–8 ft
     * (local, towards the frame), 20 kip concentrated on CD at 7.5 ft, trapezoidal 2→4 kip/ft (global, down) on DE.
     */
    fun pitchedPortal(): FrameModel {
        val pts = listOf(0.0 to 0.0, 0.0 to 10.0, 12.0 to 20.0, 27.0 to 20.0, 39.0 to 10.0, 39.0 to 0.0)
        val nodes = pts.mapIndexed { k, (x, y) -> Node(k + 1, x * FT, 0.0, y * FT) }
        val sec = Section.rect(15 * INCH, 15 * INCH, "15\"×15\"")
        val mat = Material.concreteAci(27.579)
        val members = (1..5).map { Member(it, it, it + 1, mat, sec) }
        return FrameModel(
            nodes, members, listOf(Support.fixed(1), Support.fixed(6)),
            memberLoads = listOf(
                MemberLoad(2, LoadDir.LOCAL_Z, 15 * KIP_FT, LoadCase.LL, 0.0, 8 * FT),
                MemberLoad(4, LoadDir.GLOBAL_Z, -2 * KIP_FT, LoadCase.LL, null, null, -4 * KIP_FT),
            ),
            pointLoads = listOf(PointLoad(3, LoadDir.GLOBAL_Z, -20 * KIP, 7.5 * FT, LoadCase.LL)),
            selfWeight = true, title = "B1 Pitched portal (CEE 330L Ch. III)", planeXZ = true,
        )
    }

    /** B2 — Fink roof truss (Ch. IV-A): 12 m × 3 m, JIS L 60×60×4 truss members, 10 kN at each top panel point. */
    fun finkTruss(): FrameModel = Builders.finkTruss(12.0, 3.0, Section.angle(0.06, 0.06, 0.004, "L 60×60×4"), Material.steel(250.0), panelLoad = 10.0)
        .copy(title = "B2 Fink truss (CEE 330L Ch. IV-A)")

    /** B3 — Pratt bridge truss (Ch. IV-B): 24 m, 6 panels, h = 4 m, 50 kN at each interior bottom panel point. */
    fun prattTruss(): FrameModel = Builders.prattTruss(24.0, 4.0, 6, Section.rhs(0.2, 0.2, 0.008), Section.rhs(0.15, 0.15, 0.006),
        Material.steel(250.0), panelLoad = 50.0).copy(title = "B3 Pratt truss (CEE 330L Ch. IV-B)")

    /**
     * B4 — multi-storey RCC building (Ch. V, BIS): 3 × 2 bays (5 m × 4 m), first storey from the frame generator,
     * storeys 2–3 by translational repeat (3.2 m), 1.5 m cantilever veranda on the +Y face by repeat with link.
     * Slab DL 4.75 kN/m² (125 mm + 1.5 finish) and LL 3 kN/m² on every floor via the floor-load tool.
     * Lateral loads are added by the caller (IS 1893 static or IS 875-3 wind) in case WL.
     */
    fun building(): FrameModel {
        val col = Section.rect(0.3, 0.45); val beam = Section.rect(0.23, 0.45)
        val mat = Material.concrete(25.0)
        var m = Generators.spaceFrame(listOf(5.0, 5.0, 5.0), listOf(4.0, 4.0), listOf(3.2), col, beam, mat, beamUdl = 0.0)
        val storey1 = m.members.map { it.id }.toSet()
        m = Builders.translationalRepeat(m, storey1, dir = Vec3.Z, spacings = listOf(3.2, 3.2))
        val yMax = m.nodes.maxOf { it.y }
        val edge = m.members.filter { mm ->
            val a = m.node(mm.i); val b = m.node(mm.j)
            a.z > 0.1 && b.z > 0.1 && kotlin.math.abs(a.y - yMax) < 1e-6 && kotlin.math.abs(b.y - yMax) < 1e-6
        }.map { it.id }.toSet()
        m = Builders.translationalRepeat(m, edge, dir = Vec3.Y, spacings = listOf(1.5), link = true, linkSection = beam, linkMaterial = mat)
        val levels = m.nodes.map { it.z }.filter { it > 0.1 }.distinct().sorted()
        for (z in levels) {
            m = FloorLoad.applyToLevel(m, z, -(25 * 0.125 + 1.5), LoadCase.DL).model
            m = FloorLoad.applyToLevel(m, z, -3.0, LoadCase.LL).model
        }
        return m.copy(title = "B4 3-storey RCC building with veranda (CEE 330L Ch. V, BIS)")
    }

    val all: List<Pair<String, () -> FrameModel>> = listOf(
        "B1 Pitched portal frame (Ch. III)" to ::pitchedPortal,
        "B2 Fink roof truss (Ch. IV-A)" to ::finkTruss,
        "B3 Pratt bridge truss (Ch. IV-B)" to ::prattTruss,
        "B4 3-storey building + veranda (Ch. V)" to ::building,
    )
}
