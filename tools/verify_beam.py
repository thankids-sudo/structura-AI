"""
Independent hand-calculation of the IS 456:2000 beam cases used in
core/src/test/.../BeamDesignTest.kt. Written separately from the Kotlin engine
(directly from the code formulas) so the two can be cross-checked.

Run:  python3 tools/verify_beam.py
"""
import math

ES = 2e5

def xu_ratio(fy):
    return {250: 0.53, 415: 0.48, 500: 0.46}.get(fy, 0.0035 / (0.0055 + 0.87 * fy / ES))

# SP 16 Table A (design stress-strain, HYSD) — tabulated values, not regenerated
SP16_A = {
    415: [(0.00144, 288.7), (0.00163, 306.7), (0.00192, 324.8), (0.00241, 342.8), (0.00276, 351.8), (0.00380, 360.9)],
    500: [(0.00174, 347.8), (0.00195, 369.6), (0.00226, 391.3), (0.00277, 413.0), (0.00312, 423.9), (0.00417, 434.8)],
}

def fsc(strain, fy):
    pts = SP16_A[fy]
    if strain <= pts[0][0]:
        return strain * ES
    if strain >= pts[-1][0]:
        return pts[-1][1]
    for (e0, s0), (e1, s1) in zip(pts, pts[1:]):
        if strain <= e1:
            return s0 + (strain - e0) / (e1 - e0) * (s1 - s0)

T19_PT = [0.15, 0.25, 0.50, 0.75, 1.00, 1.25, 1.50, 1.75, 2.00, 2.25, 2.50, 2.75, 3.00]
T19 = {20: [0.28, 0.36, 0.48, 0.56, 0.62, 0.67, 0.72, 0.75, 0.79, 0.81, 0.82, 0.82, 0.82],
       25: [0.29, 0.36, 0.49, 0.57, 0.64, 0.70, 0.74, 0.78, 0.82, 0.85, 0.88, 0.90, 0.92]}

def tau_c(pt, fck):
    ys = T19[fck]
    if pt <= T19_PT[0]: return ys[0]
    if pt >= T19_PT[-1]: return ys[-1]
    for i in range(1, len(T19_PT)):
        if pt <= T19_PT[i]:
            t = (pt - T19_PT[i - 1]) / (T19_PT[i] - T19_PT[i - 1])
            return ys[i - 1] + t * (ys[i] - ys[i - 1])

def case(name, b, D, cover, phi, phic, phist, fck, fy, Mu, Vu, span):
    d = D - cover - phist - phi / 2
    dd = cover + phist + phic / 2
    xu = xu_ratio(fy) * d
    mulim = 0.36 * fck * b * xu * (d - 0.42 * xu)
    mu = Mu * 1e6
    print(f"--- {name}")
    print(f"d = {d:.1f}  xu,max = {xu:.2f}  Mu,lim = {mulim/1e6:.3f} kNm")
    if mu <= mulim:
        ast = 0.5 * fck / fy * (1 - math.sqrt(1 - 4.6 * mu / (fck * b * d * d))) * b * d
        asc = 0
        print(f"singly  Ast = {ast:.2f}")
    else:
        mu2 = mu - mulim
        ast1 = mulim / (0.87 * fy * (d - 0.42 * xu))
        esc = 0.0035 * (xu - dd) / xu
        f = fsc(esc, fy)
        asc = mu2 / ((f - 0.446 * fck) * (d - dd))
        ast = ast1 + asc * (f - 0.446 * fck) / (0.87 * fy)
        print(f"doubly  d'={dd}  esc={esc:.5f}  fsc={f:.2f}  Asc={asc:.2f}  Ast={ast:.2f}")
    astmin = 0.85 * b * d / fy
    a = math.pi * phi ** 2 / 4
    n = max(2, math.ceil(max(ast, astmin) / a))
    pt = 100 * n * a / (b * d)
    tv = Vu * 1e3 / (b * d)
    tc = tau_c(pt, fck)
    asv = 2 * math.pi * phist ** 2 / 4
    svmin = 0.87 * 415 * asv / (0.4 * b)
    if tv <= tc:
        sv = svmin
    else:
        vus = Vu * 1e3 - tc * b * d
        sv = min(0.87 * 415 * asv * d / vus, svmin)
        print(f"Vus = {vus/1e3:.3f} kN  sv,design = {0.87*415*asv*d/vus:.2f}")
    sv = math.floor(min(sv, 0.75 * d, 300) / 5) * 5
    print(f"bars n={n}  pt={pt:.4f}  tv={tv:.4f}  tc={tc:.4f}  sv adopt={sv}")

case("Singly: 230x450 M20 Fe500 Mu=90 Vu=80", 230, 450, 25, 16, 12, 8, 20, 500, 90, 80, 5000)
case("Doubly: 230x450 M20 Fe500 Mu=140 Vu=120", 230, 450, 25, 20, 16, 8, 20, 500, 140, 120, 5000)
case("Doubly Fe415: 300x500 M25 Fe415 Mu=260 Vu=150", 300, 500, 25, 20, 16, 8, 25, 415, 260, 150, 6000)
