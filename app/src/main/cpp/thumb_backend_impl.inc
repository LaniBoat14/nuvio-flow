/*
 * One FFmpeg backend of the keyframe decoder (see thumb_backend.h). Included by one .cpp per backend, inside
 * an anonymous namespace, after that file's own FFmpeg headers. The including file defines load_symbols().
 */

#define FF_FUNCS(X) \
    X(avcodec_find_decoder_by_name) X(av_mallocz) X(avcodec_alloc_context3) X(avcodec_open2) X(avcodec_send_packet) \
    X(avcodec_receive_frame) X(avcodec_free_context) X(avcodec_flush_buffers) \
    X(av_packet_alloc) X(av_packet_free) X(av_new_packet) X(av_packet_unref) \
    X(av_frame_alloc) X(av_frame_free) X(av_frame_unref) X(av_frame_get_side_data) \
    X(av_pix_fmt_desc_get) X(av_log_set_level) \
    X(sws_getCachedContext) X(sws_scale) X(sws_freeContext) X(sws_setColorspaceDetails) X(sws_getCoefficients)

struct Api {
#define DECL(name) decltype(&::name) name = nullptr;
    FF_FUNCS(DECL)
#undef DECL
};

Api g_api;
bool g_ready = false;
char g_version[64] = "";
pthread_mutex_t g_init_lock = PTHREAD_MUTEX_INITIALIZER;

bool load_symbols(Api &api, char *version, size_t n);

void *find_symbol(void *const *handles, int count, const char *name) {
    for (int i = 0; i < count; i++) {
        if (!handles[i]) continue;
        if (void *s = dlsym(handles[i], name)) return s;
    }
    return nullptr;
}

bool resolve_all(Api &api, void *const *handles, int count) {
    bool ok = true;
#define RESOLVE(name) \
    api.name = reinterpret_cast<decltype(&::name)>(find_symbol(handles, count, #name)); \
    if (!api.name) { thumb::note_failure("%s: missing symbol %s", BACKEND_NAME, #name); ok = false; }
    FF_FUNCS(RESOLVE)
#undef RESOLVE
    return ok;
}

bool ensure_loaded() {
    pthread_mutex_lock(&g_init_lock);
    if (!g_ready) {
        g_ready = load_symbols(g_api, g_version, sizeof g_version);
        if (g_ready) g_api.av_log_set_level(AV_LOG_ERROR);
    }
    bool r = g_ready;
    pthread_mutex_unlock(&g_init_lock);
    return r;
}

struct Handle {
    AVCodecContext *ctx = nullptr;
    AVPacket *pkt = nullptr;
    AVFrame *frame = nullptr;
    bool have_frame = false;
    struct SwsContext *sws = nullptr;
    uint8_t *g16 = nullptr;       // GBRP16 scratch for the HDR path
    size_t g16_size = 0;
    tc_ctx *tone = nullptr;
    tc_params tone_params{};
    bool have_dovi = false;       // current frame has Dolby Vision IPT metadata (profile 5)
    td_params dovi{};
    float dovi_peak_nits = 0;
    // Multi-slice HEVC keyframes are decoded a strip at a time (hevc_strips.h), by up to two strip decoders.
    bool hevc = false;
    bool split = false;
    bool told_whole = false;      // why a keyframe decodes whole, logged once per handle
    thumb::Strips strips;
    AVCodecContext *strip_ctx[2] = {};
    AVPacket *strip_pkt[2] = {};
    AVFrame *strip_frame[2] = {};
};

/** Output of one render: straight to the bitmap (SDR), or through a 16-bit scratch for the HDR / Dolby Vision steps. */
struct Target {
    enum { SDR, HDR, DOVI } mode = SDR;
    uint8_t *dst[4] = {};
    int dst_ls[4] = {};
};

/** Copies FFmpeg's parsed Dolby Vision metadata of an IPT frame into td_params. */
bool read_dovi(const AVFrame *f, td_params *p, float *peak_nits) {
    AVFrameSideData *sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_DOVI_METADATA);
    if (!sd || !sd->data) return false;
    const auto *md = reinterpret_cast<const AVDOVIMetadata *>(sd->data);
    const AVDOVIRpuDataHeader *hdr = av_dovi_get_header(md);
    const AVDOVIDataMapping *map = av_dovi_get_mapping(md);
    const AVDOVIColorMetadata *col = av_dovi_get_color(md);
    if (col->signal_color_space != 2) return false;          // 2 = IPT
    if (hdr->bl_bit_depth < 8 || hdr->bl_bit_depth > 16 || hdr->coef_log2_denom > 62) return false;
    const double den = static_cast<double>(1ULL << hdr->coef_log2_denom);
    const double bl_max = static_cast<double>((1 << hdr->bl_bit_depth) - 1);
    for (int c = 0; c < 3; c++) {
        const AVDOVIReshapingCurve &cv = map->curves[c];
        if (cv.num_pivots < 2 || cv.num_pivots > TD_MAX_PIECES + 1) return false;
        p->num_pivots[c] = cv.num_pivots;
        for (int k = 0; k < cv.num_pivots; k++) p->pivots[c][k] = cv.pivots[k] / bl_max;
        for (int k = 0; k < cv.num_pivots - 1; k++) {
            td_piece &pc = p->pieces[c][k];
            pc.method = cv.mapping_idc[k] == AV_DOVI_MAPPING_MMR ? TD_MMR : TD_POLYNOMIAL;
            pc.poly_order = cv.poly_order[k];
            for (int j = 0; j < 3; j++) pc.poly[j] = cv.poly_coef[k][j] / den;
            pc.mmr_order = cv.mmr_order[k];
            pc.mmr_constant = cv.mmr_constant[k] / den;
            for (int o = 0; o < 3; o++)
                for (int j = 0; j < 7; j++) pc.mmr[o][j] = cv.mmr_coef[k][o][j] / den;
        }
    }
    for (int i = 0; i < 9; i++) {
        p->ycc_to_rgb[i] = av_q2d(col->ycc_to_rgb_matrix[i]);
        p->rgb_to_lms[i] = av_q2d(col->rgb_to_lms_matrix[i]);
    }
    for (int i = 0; i < 3; i++) p->ycc_offset[i] = av_q2d(col->ycc_to_rgb_offset[i]);
    *peak_nits = col->source_max_pq > 0 ? static_cast<float>(td_pq_to_nits(col->source_max_pq / 4095.0)) : 1000.0f;
    return true;
}

void free_strips(Handle *h) {
    for (int k = 0; k < 2; k++) {
        if (h->strip_frame[k]) g_api.av_frame_free(&h->strip_frame[k]);
        if (h->strip_pkt[k]) g_api.av_packet_free(&h->strip_pkt[k]);
        if (h->strip_ctx[k]) g_api.avcodec_free_context(&h->strip_ctx[k]);
    }
}

/** Strip decoder k: one thread, loop filter skipped (strips only match the whole picture without it). */
bool open_strip_decoder(Handle *h, int k) {
    if (h->strip_ctx[k]) return true;
    const AVCodec *c = g_api.avcodec_find_decoder_by_name("hevc");
    if (!c) return false;
    if (!h->strip_pkt[k]) h->strip_pkt[k] = g_api.av_packet_alloc();
    if (!h->strip_frame[k]) h->strip_frame[k] = g_api.av_frame_alloc();
    h->strip_ctx[k] = g_api.avcodec_alloc_context3(c);
    if (!h->strip_ctx[k] || !h->strip_pkt[k] || !h->strip_frame[k]) {
        if (h->strip_ctx[k]) g_api.avcodec_free_context(&h->strip_ctx[k]);
        return false;
    }
    h->strip_ctx[k]->thread_count = 1;
    h->strip_ctx[k]->skip_loop_filter = AVDISCARD_ALL;
    if (g_api.avcodec_open2(h->strip_ctx[k], c, nullptr) < 0) {
        g_api.avcodec_free_context(&h->strip_ctx[k]);
        return false;
    }
    return true;
}

/** Decodes strip i of the current picture with strip decoder k. 0 or a negative AVERROR. */
int decode_strip(Handle *h, int k, size_t i) {
    const thumb::Strip &s = h->strips.pieces[i];
    AVCodecContext *ctx = h->strip_ctx[k];
    AVPacket *pkt = h->strip_pkt[k];
    AVFrame *f = h->strip_frame[k];
    g_api.av_frame_unref(f);
    g_api.avcodec_flush_buffers(ctx);
    g_api.av_packet_unref(pkt);
    if (g_api.av_new_packet(pkt, static_cast<int>(s.packet.size())) < 0) return AVERROR(ENOMEM);
    memcpy(pkt->data, s.packet.data(), s.packet.size());
    pkt->flags |= AV_PKT_FLAG_KEY;
    int r = g_api.avcodec_send_packet(ctx, pkt);
    g_api.av_packet_unref(pkt);
    if (r < 0) return r;
    g_api.avcodec_send_packet(ctx, nullptr);
    r = g_api.avcodec_receive_frame(ctx, f);
    if (r < 0) return r;
    if (f->width != h->strips.width || f->height != s.rows) return AVERROR_INVALIDDATA;
    return 0;
}

void b_release(void *handle) {
    auto *h = static_cast<Handle *>(handle);
    if (!h) return;
    free_strips(h);
    if (h->frame) g_api.av_frame_free(&h->frame);
    if (h->pkt) g_api.av_packet_free(&h->pkt);
    if (h->ctx) g_api.avcodec_free_context(&h->ctx);
    if (h->sws) g_api.sws_freeContext(h->sws);
    free(h->g16);
    tc_free(h->tone);
    delete h;
}

void *b_open(const char *decoder, const uint8_t *extradata, int extradata_len, int width, int height, int threads) {
    if (!ensure_loaded()) return nullptr;
    const AVCodec *c = g_api.avcodec_find_decoder_by_name(decoder);
    if (!c) return nullptr;
    auto *h = new Handle();
    h->hevc = strcmp(decoder, "hevc") == 0;
    h->ctx = g_api.avcodec_alloc_context3(c);
    h->pkt = g_api.av_packet_alloc();
    h->frame = g_api.av_frame_alloc();
    if (!h->ctx || !h->pkt || !h->frame) { b_release(h); return nullptr; }
    if (extradata && extradata_len > 0) {
        // FFmpeg owns and frees extradata, so it must come from av_mallocz, with input padding.
        h->ctx->extradata = static_cast<uint8_t *>(g_api.av_mallocz(extradata_len + AV_INPUT_BUFFER_PADDING_SIZE));
        if (!h->ctx->extradata) { b_release(h); return nullptr; }
        memcpy(h->ctx->extradata, extradata, extradata_len);
        h->ctx->extradata_size = extradata_len;
    }
    if (width > 0 && height > 0) {            // WMV3 cannot decode without the coded size
        h->ctx->width = h->ctx->coded_width = width;
        h->ctx->height = h->ctx->coded_height = height;
    }
    h->ctx->thread_count = threads < 1 ? 1 : threads;
    h->ctx->thread_type = FF_THREAD_SLICE;
    // Deblocking and SAO change nothing visible once a 4K picture is shrunk to thumbnail size; smaller sources keep it.
    if (h->hevc && (width >= 3200 || height >= 2000)) h->ctx->skip_loop_filter = AVDISCARD_ALL;
    if (g_api.avcodec_open2(h->ctx, c, nullptr) < 0) { b_release(h); return nullptr; }
    return h;
}

uint8_t *b_packet(void *handle, int len) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || len <= 0) return nullptr;
    if (h->have_frame) { g_api.av_frame_unref(h->frame); h->have_frame = false; }
    h->split = false;
    g_api.avcodec_flush_buffers(h->ctx);            // reset after the previous drain
    g_api.av_packet_unref(h->pkt);
    if (g_api.av_new_packet(h->pkt, len) < 0) return nullptr;
    return h->pkt->data;
}

int b_decode(void *handle, int32_t *out) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || !h->pkt->data) return AVERROR(EINVAL);
    // Strip 0 now, for the frame's details; the others while rendering.
    const char *why = nullptr;
    if (h->hevc && thumb::split_hevc(h->pkt->data, static_cast<size_t>(h->pkt->size), &h->strips, &why)) {
        if (!open_strip_decoder(h, 0)) why = "strip decoder did not open";
        else if (decode_strip(h, 0, 0) != 0) why = "strip 0 did not decode";
        else h->split = true;
    }
    if (h->split) {
        g_api.av_packet_unref(h->pkt);
    } else if (h->hevc && why && !h->told_whole) {
        h->told_whole = true;
        __android_log_print(ANDROID_LOG_INFO, "ThumbDecode", "keyframe decodes whole: %s", why);
    }
    if (!h->split) {
        h->pkt->flags |= AV_PKT_FLAG_KEY;
        int r = g_api.avcodec_send_packet(h->ctx, h->pkt);
        g_api.av_packet_unref(h->pkt);
        if (r < 0) return r;
        g_api.avcodec_send_packet(h->ctx, nullptr);      // drain to get this picture now
        r = g_api.avcodec_receive_frame(h->ctx, h->frame);
        if (r < 0) return r;
    }
    h->have_frame = true;

    memset(out, 0, sizeof(int32_t) * thumb::INFO_COUNT);
    AVFrame *f = h->split ? h->strip_frame[0] : h->frame;
    const AVPixFmtDescriptor *d = g_api.av_pix_fmt_desc_get(static_cast<AVPixelFormat>(f->format));
    out[thumb::INFO_WIDTH] = f->width;
    out[thumb::INFO_HEIGHT] = h->split ? h->strips.height : f->height;
    out[thumb::INFO_STRIPS] = h->split ? static_cast<int32_t>(h->strips.pieces.size()) : 0;
    out[thumb::INFO_BIT_DEPTH] = d ? d->comp[0].depth : 8;
    out[thumb::INFO_SAR_NUM] = f->sample_aspect_ratio.num;
    out[thumb::INFO_SAR_DEN] = f->sample_aspect_ratio.den;
    out[thumb::INFO_TRC] = f->color_trc;
    out[thumb::INFO_PRIMARIES] = f->color_primaries;
    out[thumb::INFO_MATRIX] = f->colorspace;
    out[thumb::INFO_RANGE] = f->color_range;
    AVFrameSideData *sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (sd) out[thumb::INFO_MAX_CLL] = static_cast<int32_t>(reinterpret_cast<AVContentLightMetadata *>(sd->data)->MaxCLL);
    sd = g_api.av_frame_get_side_data(f, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    if (sd) {
        auto *m = reinterpret_cast<AVMasteringDisplayMetadata *>(sd->data);
        if (m->has_luminance && m->max_luminance.den)
            out[thumb::INFO_MASTERING_MAX_NITS] = m->max_luminance.num / m->max_luminance.den;
    }
    h->have_dovi = read_dovi(f, &h->dovi, &h->dovi_peak_nits);
    out[thumb::INFO_DOVI_IPT] = h->have_dovi ? 1 : 0;
    return 0;
}

/** Grows the 16-bit scratch to at least need bytes. */
bool scratch(Handle *h, size_t need) {
    if (h->g16_size < need) {
        free(h->g16);
        h->g16 = static_cast<uint8_t *>(malloc(need));
        h->g16_size = h->g16 ? need : 0;
    }
    return h->g16 != nullptr;
}

/**
 * Sets up the scaler for a src_w x src_h source and the output of one render. Dolby Vision profile 5 scales the IPT
 * base layer to the output size at 4:4:4; finish_render reshapes it to PQ BT.2020 (thumbdovi.c) and tone-maps.
 */
int begin_render(Handle *h, Target *t, AVPixelFormat fmt, int src_w, int src_h, int full_h, uint8_t *pixels,
                 int stride, int dw, int dh, bool rgb565, int transfer, bool bt2020, bool fullRange) {
    if (transfer == 3) {
        if (!h->have_dovi) return AVERROR(EINVAL);
        h->sws = g_api.sws_getCachedContext(h->sws, src_w, src_h, fmt, dw, dh, AV_PIX_FMT_YUV444P16LE, SWS_BICUBIC,
                                            nullptr, nullptr, nullptr);
        if (!h->sws) return AVERROR(ENOMEM);
        const size_t plane = static_cast<size_t>(dw) * dh;
        if (!scratch(h, plane * 2 * 6)) return AVERROR(ENOMEM);     // YUV444P16 in + GBRP16 out
        auto *base = reinterpret_cast<uint16_t *>(h->g16);
        t->mode = Target::DOVI;
        for (int p = 0; p < 3; p++) {
            t->dst[p] = reinterpret_cast<uint8_t *>(base + p * plane);
            t->dst_ls[p] = dw * 2;
        }
        return 0;
    }
    const bool hdr = transfer == 1 || transfer == 2;
    const AVPixelFormat dst_fmt = hdr ? AV_PIX_FMT_GBRP16LE : (rgb565 ? AV_PIX_FMT_RGB565LE : AV_PIX_FMT_RGBA);
    h->sws = g_api.sws_getCachedContext(h->sws, src_w, src_h, fmt, dw, dh, dst_fmt, SWS_BICUBIC, nullptr, nullptr,
                                        nullptr);
    if (!h->sws) return AVERROR(ENOMEM);
    const int cs = bt2020 ? SWS_CS_BT2020 : (full_h > 576 ? SWS_CS_ITU709 : SWS_CS_ITU601);
    g_api.sws_setColorspaceDetails(h->sws, g_api.sws_getCoefficients(cs), fullRange ? 1 : 0,
                                   g_api.sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);
    if (!hdr) {
        t->mode = Target::SDR;
        t->dst[0] = pixels;
        t->dst_ls[0] = stride;
        return 0;
    }
    const size_t plane = static_cast<size_t>(dw) * dh * 2;
    if (!scratch(h, plane * 3)) return AVERROR(ENOMEM);
    t->mode = Target::HDR;
    for (int p = 0; p < 3; p++) {
        t->dst[p] = h->g16 + p * plane;
        t->dst_ls[p] = dw * 2;
    }
    return 0;
}

/** Feeds source rows [y, y + rows), top to bottom across calls. rs 2 skips every other row. */
void scale_rows(Handle *h, const Target &t, const AVFrame *f, int y, int rows, int rs) {
    const uint8_t *src[4] = {f->data[0], f->data[1], f->data[2], f->data[3]};
    int ls[4] = {f->linesize[0], f->linesize[1], f->linesize[2], f->linesize[3]};
    for (int p = 0; p < 4; p++) ls[p] *= rs;
    g_api.sws_scale(h->sws, src, ls, y / rs, rows / rs, t.dst, t.dst_ls);
}

int finish_render(Handle *h, const Target &t, uint8_t *pixels, int stride, int dw, int dh, bool rgb565, int transfer,
                  bool bt2020, float peakNits) {
    if (t.mode == Target::SDR) return 0;
    tc_params want{};
    want.target_white_nits = 0;   // default 203 (BT.2408)
    uint16_t *g = reinterpret_cast<uint16_t *>(t.dst[0]);
    uint16_t *b = reinterpret_cast<uint16_t *>(t.dst[1]);
    uint16_t *r = reinterpret_cast<uint16_t *>(t.dst[2]);
    if (t.mode == Target::DOVI) {
        const size_t plane = static_cast<size_t>(dw) * dh;
        auto *base = reinterpret_cast<uint16_t *>(h->g16);
        g = base + 3 * plane;
        b = base + 4 * plane;
        r = base + 5 * plane;
        td_convert(&h->dovi, base, base + plane, base + 2 * plane, dw, dw, dh, g, b, r, dw);
        want.transfer = TC_TRANSFER_PQ;
        want.source_peak_nits = h->dovi_peak_nits;
        want.bt2020_primaries = 1;
    } else {
        want.transfer = transfer == 2 ? TC_TRANSFER_HLG : TC_TRANSFER_PQ;
        want.source_peak_nits = peakNits;
        want.bt2020_primaries = bt2020 ? 1 : 0;
    }
    if (!h->tone || memcmp(&want, &h->tone_params, sizeof want) != 0) {
        tc_free(h->tone);
        h->tone = tc_create(&want);
        h->tone_params = want;
    }
    if (!h->tone) return AVERROR(ENOMEM);
    // GBRP plane order: [0]=G [1]=B [2]=R
    tc_convert(h->tone, g, dw, b, dw, r, dw, dw, dh, pixels, stride, rgb565 ? 1 : 0);
    return 0;
}

/** Decodes the other strips, two at a time when a second decoder opens, and feeds them to the scaler in order. */
int scale_strips(Handle *h, const Target &t, int rs) {
    const size_t n = h->strips.pieces.size();
    const bool pairs = n > 2 && open_strip_decoder(h, 1);
    for (size_t i = 0; i < n; i += pairs ? 2 : 1) {
        const bool both = pairs && i + 1 < n;
        int other = 0;
        std::thread worker;
        if (both) {
            try {
                worker = std::thread([h, i, &other] { other = decode_strip(h, 1, i + 1); });
            } catch (...) {
                other = decode_strip(h, 1, i + 1);
            }
        }
        const int r = i == 0 ? 0 : decode_strip(h, 0, i);
        if (worker.joinable()) worker.join();
        if (r < 0) return r;
        const thumb::Strip &s = h->strips.pieces[i];
        scale_rows(h, t, h->strip_frame[0], s.y, s.rows, rs);
        if (both) {
            if (other < 0) return other;
            const thumb::Strip &next = h->strips.pieces[i + 1];
            scale_rows(h, t, h->strip_frame[1], next.y, next.rows, rs);
        }
    }
    return 0;
}

int b_render(void *handle, uint8_t *pixels, int stride, int dw, int dh, bool rgb565, int transfer, bool bt2020,
           bool fullRange, float peakNits, bool rowSkip) {
    auto *h = static_cast<Handle *>(handle);
    if (!h || !h->have_frame) return AVERROR(EINVAL);
    const AVFrame *f = h->split ? h->strip_frame[0] : h->frame;
    const int full_h = h->split ? h->strips.height : f->height;
    // Row pre-decimation: every other row of every plane.
    const int rs = rowSkip && full_h >= 4 * dh ? 2 : 1;
    Target t;
    int r = begin_render(h, &t, static_cast<AVPixelFormat>(f->format), f->width, full_h / rs, full_h, pixels, stride,
                         dw, dh, rgb565, transfer, bt2020, fullRange);
    if (r < 0) return r;
    if (h->split) {
        r = scale_strips(h, t, rs);
        if (r < 0) return r;
    } else {
        scale_rows(h, t, f, 0, full_h, rs);
    }
    return finish_render(h, t, pixels, stride, dw, dh, rgb565, transfer, bt2020, peakNits);
}

void b_trim(void *handle) {
    auto *h = static_cast<Handle *>(handle);
    if (!h) return;
    if (h->have_frame) { g_api.av_frame_unref(h->frame); h->have_frame = false; }
    g_api.avcodec_flush_buffers(h->ctx);
    h->split = false;
    h->strips = thumb::Strips();
    for (int k = 0; k < 2; k++) {
        if (h->strip_frame[k]) g_api.av_frame_unref(h->strip_frame[k]);
        if (h->strip_ctx[k]) g_api.avcodec_flush_buffers(h->strip_ctx[k]);
    }
    free(h->g16); h->g16 = nullptr; h->g16_size = 0;
    if (h->sws) { g_api.sws_freeContext(h->sws); h->sws = nullptr; }
}

const thumb::Backend *get() {
    static thumb::Backend backend{BACKEND_NAME, g_version, b_open, b_packet, b_decode, b_render, b_trim, b_release};
    return ensure_loaded() ? &backend : nullptr;
}

#undef FF_FUNCS
