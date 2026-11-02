#include "hevc_strips.h"

#include <cstring>
#include <map>

namespace thumb {
namespace {

using Bytes = std::vector<uint8_t>;

struct Span {
    const uint8_t *p;
    size_t n;
};

std::vector<Span> nal_units(const uint8_t *b, size_t n) {
    std::vector<Span> out;
    size_t i = 0, start = 0;
    bool open = false;
    while (i + 3 <= n) {
        if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
            if (open) {
                size_t end = i;
                while (end > start && b[end - 1] == 0) end--;
                out.push_back({b + start, end - start});
            }
            i += 3;
            start = i;
            open = true;
        } else {
            i++;
        }
    }
    if (open) out.push_back({b + start, n - start});
    return out;
}

Bytes unescape(Span s) {
    Bytes out;
    out.reserve(s.n);
    int zeros = 0;
    for (size_t i = 0; i < s.n; i++) {
        uint8_t x = s.p[i];
        if (zeros >= 2 && x == 3) {
            zeros = 0;
            continue;
        }
        out.push_back(x);
        zeros = x == 0 ? zeros + 1 : 0;
    }
    return out;
}

void escape(const Bytes &r, Bytes &out) {
    int zeros = 0;
    for (uint8_t x : r) {
        if (zeros >= 2 && x <= 3) {
            out.push_back(3);
            zeros = 0;
        }
        out.push_back(x);
        zeros = x == 0 ? zeros + 1 : 0;
    }
}

struct Reader {
    const Bytes &b;
    size_t pos;
    bool bad = false;

    Reader(const Bytes &bytes, size_t at) : b(bytes), pos(at) {}

    uint32_t u(int n) {
        uint32_t v = 0;
        for (int i = 0; i < n; i++) {
            if ((pos >> 3) >= b.size()) {
                bad = true;
                return 0;
            }
            v = (v << 1) | ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
            pos++;
        }
        return v;
    }

    uint32_t ue() {
        int z = 0;
        while (!bad && u(1) == 0) {
            if (++z > 31) bad = true;
        }
        if (bad) return 0;
        return (1u << z) - 1 + u(z);
    }
};

struct Writer {
    Bytes bytes;
    size_t bits = 0;

    void bit(uint32_t v) {
        if ((bits & 7) == 0) bytes.push_back(0);
        if (v & 1) bytes.back() |= static_cast<uint8_t>(0x80 >> (bits & 7));
        bits++;
    }

    void u(int n, uint32_t v) {
        for (int i = n - 1; i >= 0; i--) bit(v >> i);
    }

    void ue(uint32_t v) {
        v += 1;
        int n = 0;
        while ((v >> n) > 1) n++;
        u(n, 0);
        u(n + 1, v);
    }

    /** Whole bytes once source and output are both at a byte boundary (the slice data after a rewritten header). */
    void copy(const Bytes &b, size_t start, size_t end) {
        size_t p = start;
        for (; p < end && ((p & 7) || (bits & 7)); p++) bit((b[p >> 3] >> (7 - (p & 7))) & 1);
        if (!(p & 7) && !(bits & 7) && p + 8 <= end) {
            const size_t n = (end - p) >> 3;
            bytes.insert(bytes.end(), b.begin() + (p >> 3), b.begin() + (p >> 3) + n);
            bits += n * 8;
            p += n * 8;
        }
        for (; p < end; p++) bit((b[p >> 3] >> (7 - (p & 7))) & 1);
    }

    void trailing() {
        bit(1);
        while (bits & 7) bit(0);
    }
};

int ue_len(uint32_t v) {
    int n = 0;
    while (((v + 1) >> n) > 1) n++;
    return 2 * n + 1;
}

int ceil_log2(uint32_t x) {
    int n = 0;
    while ((1u << n) < x) n++;
    return n;
}

/** Bit position of rbsp_stop_one_bit, or -1. */
long stop_bit(const Bytes &r) {
    for (long i = static_cast<long>(r.size()) - 1; i >= 0; i--) {
        if (r[i] == 0) continue;
        int k = 0;
        while (!((r[i] >> k) & 1)) k++;
        return i * 8 + 7 - k;
    }
    return -1;
}

struct Sps {
    uint32_t id = 0, width = 0, height = 0, ctb = 0;
    size_t h_start = 0, rest = 0;
    Bytes rbsp;
};

struct Pps {
    uint32_t id = 0, sps = 0, extra = 0;
    bool dependent = false, tiles_or_wpp = false;
    size_t id_start = 0, id_end = 0, extra_pos = 0;
    Bytes rbsp;
    Span raw{};
};

bool parse_sps(Bytes r, Sps *s) {
    Reader rd(r, 16);
    rd.u(4);
    uint32_t max_sub = rd.u(3);
    rd.u(1);
    rd.u(32);
    rd.u(32);
    rd.u(32);
    bool pp[8] = {}, lp[8] = {};
    for (uint32_t i = 0; i < max_sub; i++) {
        pp[i] = rd.u(1);
        lp[i] = rd.u(1);
    }
    if (max_sub > 0)
        for (uint32_t i = max_sub; i < 8; i++) rd.u(2);
    for (uint32_t i = 0; i < max_sub; i++) {
        if (pp[i]) {
            rd.u(32);
            rd.u(32);
            rd.u(24);
        }
        if (lp[i]) rd.u(8);
    }
    s->id = rd.ue();
    uint32_t chroma = rd.ue();
    if (chroma == 3 && rd.u(1)) return false;
    s->width = rd.ue();
    s->h_start = rd.pos;
    s->height = rd.ue();
    if (rd.u(1)) {
        for (int i = 0; i < 4; i++)
            if (rd.ue() != 0) return false;     // cropped pictures keep the whole-picture path
    }
    s->rest = rd.pos;
    rd.ue();
    rd.ue();
    rd.ue();
    bool ordering = rd.u(1);
    for (uint32_t i = ordering ? 0 : max_sub; i <= max_sub; i++) {
        rd.ue();
        rd.ue();
        rd.ue();
    }
    uint32_t min_cb = rd.ue() + 3;
    uint32_t log2_ctb = min_cb + rd.ue();
    if (rd.bad || log2_ctb > 6 || s->width == 0 || s->height == 0) return false;
    s->ctb = 1u << log2_ctb;
    s->rbsp = std::move(r);
    return true;
}

bool parse_pps(Bytes r, Pps *p) {
    Reader rd(r, 16);
    p->id_start = rd.pos;
    p->id = rd.ue();
    p->id_end = rd.pos;
    p->sps = rd.ue();
    p->dependent = rd.u(1);
    rd.u(1);
    p->extra_pos = rd.pos;
    p->extra = rd.u(3);
    rd.u(1);
    rd.u(1);
    rd.ue();
    rd.ue();
    rd.ue();
    rd.u(1);
    rd.u(1);
    if (rd.u(1)) rd.ue();
    rd.ue();
    rd.ue();
    rd.u(1);
    rd.u(1);
    rd.u(1);
    rd.u(1);
    bool tiles = rd.u(1);
    bool wpp = rd.u(1);
    p->tiles_or_wpp = tiles || wpp;
    if (rd.bad || p->id > 63) return false;
    p->rbsp = std::move(r);
    return true;
}

void put_nal(Bytes &out, const uint8_t *p, size_t n) {
    static const uint8_t kStart[4] = {0, 0, 0, 1};
    out.insert(out.end(), kStart, kStart + 4);
    out.insert(out.end(), p, p + n);
}

bool strip_sps(const Sps &s, uint32_t height, Bytes &out) {
    long stop = stop_bit(s.rbsp);
    if (stop < static_cast<long>(s.rest)) return false;
    Writer w;
    w.copy(s.rbsp, 0, s.h_start);
    w.ue(height);
    w.u(1, 0);
    w.copy(s.rbsp, s.rest, static_cast<size_t>(stop));
    w.trailing();
    Bytes esc;
    escape(w.bytes, esc);
    put_nal(out, esc.data(), esc.size());
    return true;
}

bool strip_pps(const Pps &p, uint32_t id, uint32_t extra, Bytes &out) {
    long stop = stop_bit(p.rbsp);
    if (stop < static_cast<long>(p.extra_pos + 3)) return false;
    Writer w;
    w.copy(p.rbsp, 0, p.id_start);
    w.ue(id);
    w.copy(p.rbsp, p.id_end, p.extra_pos);
    w.u(3, p.extra + extra);
    w.copy(p.rbsp, p.extra_pos + 3, static_cast<size_t>(stop));
    w.trailing();
    Bytes esc;
    escape(w.bytes, esc);
    put_nal(out, esc.data(), esc.size());
    return true;
}

struct Piece {
    uint32_t y;
    const Pps *pps;
    bool rewritten;
    uint32_t new_id, extra;
    Bytes slice;      // escaped NAL, or empty to use raw
    Span raw;
};

}  // namespace

bool split_hevc(const uint8_t *au, size_t len, Strips *out, const char **why) {
#define FAIL(reason) do { if (why) *why = reason; return false; } while (0)
    std::vector<Span> vps, extras, slices;
    std::map<uint32_t, Sps> spss;
    std::map<uint32_t, Pps> ppss;
    for (const Span &u : nal_units(au, len)) {
        if (u.n < 3 || (u.p[0] & 0x80) || (((u.p[0] & 1) << 5) | (u.p[1] >> 3)) != 0) continue;
        int t = (u.p[0] >> 1) & 0x3F;
        if (t == 32) {
            vps.push_back(u);
        } else if (t == 33) {
            Sps s;
            if (!parse_sps(unescape(u), &s)) FAIL("SPS not supported (cropped, separate colour planes or unreadable)");
            spss[s.id] = std::move(s);
        } else if (t == 34) {
            Pps p;
            if (!parse_pps(unescape(u), &p)) FAIL("PPS unreadable");
            p.raw = u;
            ppss[p.id] = std::move(p);
        } else if (t >= 16 && t <= 21) {
            slices.push_back(u);
        } else if (t <= 15) {
            FAIL("not an intra picture");
        } else if (t == 39 || t == 62) {
            extras.push_back(u);
        }
    }
    if (slices.size() < 2) FAIL("one slice per picture");
    if (vps.empty()) FAIL("no VPS in the keyframe");

    std::vector<Piece> pieces;
    const Sps *sps = nullptr;
    for (const Span &u : slices) {
        Bytes r = unescape(u);
        Reader rd(r, 16);
        bool first = rd.u(1);
        rd.u(1);
        size_t id_start = rd.pos;
        uint32_t pid = rd.ue();
        auto pi = ppss.find(pid);
        if (rd.bad || pi == ppss.end()) FAIL("slice refers to a missing PPS");
        const Pps &p = pi->second;
        auto si = spss.find(p.sps);
        if (si == spss.end()) FAIL("PPS refers to a missing SPS");
        if (p.tiles_or_wpp) FAIL("tiles or wavefront rows");
        if (sps && sps != &si->second) FAIL("slices use different SPSs");
        sps = &si->second;
        uint32_t wctb = (sps->width + sps->ctb - 1) / sps->ctb;
        uint32_t size = wctb * ((sps->height + sps->ctb - 1) / sps->ctb);
        Piece piece{0, &p, false, 0, 0, {}, u};
        if (!first) {
            int dep_bits = 0;
            if (p.dependent) {
                dep_bits = 1;
                if (rd.u(1)) FAIL("dependent slice segments");
            }
            int addr_bits = ceil_log2(size);
            uint32_t addr = rd.u(addr_bits);
            if (rd.bad || addr % wctb) FAIL("a slice starts mid-row");
            int removed = dep_bits + addr_bits;
            // The header keeps its length: a longer PPS id code plus 0 or 1 extra reserved bits.
            bool found = false;
            uint32_t target = 0, e = 0;
            for (e = 0; e <= 1; e++) {
                int l = ue_len(pid) + removed - static_cast<int>(e);
                if (l % 2 == 1 && p.extra + e <= 7) {
                    uint32_t v = (1u << ((l - 1) / 2)) - 1;
                    if (v <= 63 && v != pid) {
                        target = v;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) FAIL("slice header cannot keep its length");
            size_t after_addr = rd.pos;
            rd.u(static_cast<int>(p.extra));
            size_t reserved_end = rd.pos;
            if (rd.bad) FAIL("slice header unreadable");
            Writer w;
            w.copy(r, 0, 16);
            w.u(1, 1);
            w.copy(r, 17, id_start);
            w.ue(target);
            w.copy(r, after_addr, reserved_end);
            w.u(static_cast<int>(e), 0);
            if (w.bits != reserved_end) FAIL("slice header rewrite");
            w.copy(r, reserved_end, r.size() * 8);
            escape(w.bytes, piece.slice);
            piece.y = addr / wctb * sps->ctb;
            piece.rewritten = true;
            piece.new_id = target;
            piece.extra = e;
        }
        pieces.push_back(std::move(piece));
    }
    if (!sps || pieces.front().y != 0) FAIL("first slice not at the top");

    Strips result;
    result.width = static_cast<int>(sps->width);
    result.height = static_cast<int>(sps->height);
    for (size_t i = 0; i < pieces.size(); i++) {
        const Piece &pc = pieces[i];
        uint32_t end = i + 1 < pieces.size() ? pieces[i + 1].y : sps->height;
        if (end <= pc.y) FAIL("slices out of order");
        Strip s;
        s.y = static_cast<int>(pc.y);
        s.rows = static_cast<int>(end - pc.y);
        Bytes &pkt = s.packet;
        for (const Span &v : vps) put_nal(pkt, v.p, v.n);
        if (!strip_sps(*sps, end - pc.y, pkt)) FAIL("SPS rewrite");
        if (pc.rewritten) {
            if (!strip_pps(*pc.pps, pc.new_id, pc.extra, pkt)) FAIL("PPS rewrite");
        } else {
            put_nal(pkt, pc.pps->raw.p, pc.pps->raw.n);
        }
        if (i == 0)
            for (const Span &x : extras) put_nal(pkt, x.p, x.n);
        if (pc.rewritten) put_nal(pkt, pc.slice.data(), pc.slice.size());
        else put_nal(pkt, pc.raw.p, pc.raw.n);
        result.pieces.push_back(std::move(s));
    }
    *out = std::move(result);
    return true;
#undef FAIL
}

}  // namespace thumb
