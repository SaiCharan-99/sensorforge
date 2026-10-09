"""Generate SYNTHETIC impact-like IMU replays (SIMULATION) for CrashGuard.

These are not recordings of real crashes. They are physically-shaped synthetic
sequences (handheld -> tumble/free fall -> hard impact with rebounds -> lying still)
used only as the `impact_like` class source and for replay demos, as the spec
allows (tech spec section 20). Every file is labelled SIMULATION in its header.

Format (CSV, 100 Hz): t_ns,channel,x,y,z,w
  channel: A (accel m/s^2), G (gyro rad/s), R (rotation quaternion x,y,z,w)

Usage: python gen_impacts.py   (writes impact_XX.csv next to this script)
"""
import math
import os
import random

HZ = 100
G = 9.80665
OUT = os.path.dirname(os.path.abspath(__file__))


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz)


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def rotate_inv(q, v):
    """World vector v expressed in the device frame for device orientation q."""
    x, y, z, w = q
    qc = (-x, -y, -z, w)
    p = qmul(qmul(qc, (v[0], v[1], v[2], 0.0)), q)
    return p[:3]


def gen(seed):
    r = random.Random(seed)
    dur = 8.0
    t_fall = r.uniform(1.8, 2.4)
    fall_len = r.uniform(0.25, 0.45)
    t_hit = round((t_fall + fall_len) * HZ) / HZ  # impact lands on a sample
    peak_g = r.uniform(6.0, 12.0)
    spin = [r.uniform(-1, 1) for _ in range(3)]
    sn = math.sqrt(sum(c * c for c in spin))
    spin_rate = math.radians(r.uniform(350, 700))
    spin = [c / sn * spin_rate for c in spin]
    q = qnorm((r.gauss(0, 0.1), r.gauss(0, 0.1), r.gauss(0, 0.1), 1.0))
    rows = []
    n = int(dur * HZ)
    t0 = 10_000_000_000 + seed * 1_000_000_000
    for i in range(n + 1):
        t = i / HZ
        ts = t0 + i * 10_000_000
        t = round(t, 6)
        if t < t_fall:  # handheld walking
            w = [r.gauss(0, 0.25) + 0.3 * math.sin(2 * math.pi * 1.0 * t) for _ in range(3)]
            lin = [0.0, 0.0, 0.25 * G * math.sin(2 * math.pi * 1.8 * t)]
            f = [lin[k] + r.gauss(0, 0.3) for k in range(3)]
            g_world = (0.0, 0.0, G)
        elif t < t_hit:  # tumble / free fall
            w = [spin[k] + r.gauss(0, 0.3) for k in range(3)]
            f = [r.gauss(0, 0.5) for _ in range(3)]
            g_world = (0.0, 0.0, 0.0)
        else:
            dt = t - t_hit
            w = [spin[k] * math.exp(-dt / 0.08) + r.gauss(0, 0.01) for k in range(3)]
            g_world = (0.0, 0.0, G)
            spike = peak_g * G * math.exp(-dt / 0.012)
            rebound = 0.35 * peak_g * G * math.exp(-((dt - 0.12) / 0.02) ** 2) \
                + 0.15 * peak_g * G * math.exp(-((dt - 0.22) / 0.02) ** 2)
            f = [r.gauss(0, 0.02 + 2.0 * math.exp(-dt / 0.05)) for _ in range(3)]
            f[2] += spike + rebound
        # integrate orientation
        wn = math.sqrt(sum(c * c for c in w))
        if wn > 1e-9:
            half = wn / HZ / 2
            s = math.sin(half) / wn
            q = qnorm(qmul(q, (w[0] * s, w[1] * s, w[2] * s, math.cos(half))))
        gd = rotate_inv(q, g_world)
        a = [gd[k] + f[k] for k in range(3)]
        rows.append(f"{ts},A,{a[0]:.4f},{a[1]:.4f},{a[2]:.4f},0")
        rows.append(f"{ts},G,{w[0]:.4f},{w[1]:.4f},{w[2]:.4f},0")
        rows.append(f"{ts},R,{q[0]:.5f},{q[1]:.5f},{q[2]:.5f},{q[3]:.5f}")
    peak_ns = t0 + int(round(t_hit * HZ)) * 10_000_000
    header = [
        "# SIMULATION: synthetic impact-like replay, NOT a real recording",
        f"# generator: gen_impacts.py seed={seed}",
        f"# peak_g={peak_g:.2f} impact_t_ns={peak_ns}",
        "t_ns,channel,x,y,z,w",
    ]
    return "\n".join(header + rows) + "\n"


if __name__ == "__main__":
    for s in range(1, 13):
        with open(os.path.join(OUT, f"impact_{s:02d}.csv"), "w", newline="\n") as fh:
            fh.write(gen(s))
    print("wrote 12 replays")
