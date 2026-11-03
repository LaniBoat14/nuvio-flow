/*
 * HEVC keyframes coded as several row-aligned slices, split into one small picture per slice. Intra prediction never
 * crosses a slice, so with the loop filter skipped each strip decodes to the same samples as the whole picture, and a
 * decoder only holds a strip at a time.
 */
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace thumb {

struct Strip {
    int y = 0;
    int rows = 0;
    std::vector<uint8_t> packet;   // Annex B: VPS, strip SPS, PPS, the slice
};

struct Strips {
    int width = 0;
    int height = 0;
    std::vector<Strip> pieces;
};

/** Splits an Annex B intra access unit; false when it has one slice or cannot be split safely, with why. */
bool split_hevc(const uint8_t *au, size_t len, Strips *out, const char **why = nullptr);

}  // namespace thumb
