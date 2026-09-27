/* SPDX-FileCopyrightText: 2026 The clogem-wmark authors */
/* SPDX-License-Identifier: EPL-2.0 */
/*
 * mock_engine.c -- a test double implementing wmark_engine.h (ABI 2).
 *
 * The JVM test suite compiles it to prove the C ABI end to end: library
 * lookup, the version handshake, JSON exchange, upcall events from a
 * foreign thread, cancellation.
 *
 *   - Render spec v1: it doesn't render. It writes a placeholder file to the
 *     output path after emitting progress events.
 *   - Render spec v2: it renders for real, because v2 only asks an engine
 *     to composite host-drawn bitmaps. Every layer of every frame goes over
 *     a white canvas (the conformance clips are white, and the mock decodes
 *     no video), written as grey YUV4MPEG2 ("y4m" container, "rawvideo"
 *     codec) that the conformance harness decodes and measures.
 *   - Stills: it decodes PAM (P7) files only, which is all the harness needs.
 *
 * It reads JSON with a small parser (below), so it can serve as a reference
 * for reading render requests, as well as for ownership, threads and events.
 *
 *   cc -shared -fPIC -O2 -pthread -o libwmark_engine.so mock_engine.c
 */
#include "../include/wmark_engine.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#endif

/* The reference semantics are IEEE double arithmetic, one rounding per
 * operation: no fused multiply-add. Clang honours the pragma; for GCC, `mul`
 * below rounds each product before it is added. */
#ifdef __clang__
#pragma STDC FP_CONTRACT OFF
#endif

struct wmark_engine { int unused; };

struct wmark_render {
  wmark_event_fn on_event;
  void *user;
  char *plan;
  volatile int cancelled;
  pthread_t thread;
};

static char *dup_str(const char *s) {
  size_t n = strlen(s) + 1;
  char *out = malloc(n);
  if (out) memcpy(out, s, n);
  return out;
}

/* Paths arrive as UTF-8; Windows' fopen would read them in the ANSI code page. */
static FILE *open_file(const char *path, const char *mode) {
#ifdef _WIN32
  wchar_t wpath[4096], wmode[8];
  if (!MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, 4096)) return NULL;
  if (!MultiByteToWideChar(CP_UTF8, 0, mode, -1, wmode, 8)) return NULL;
  return _wfopen(wpath, wmode);
#else
  return fopen(path, mode);
#endif
}

/* ------------------------------------------------------------------------ */
/* A small JSON reader: UTF-8 text in, a tree out. Integers without a
 * fraction or exponent keep 64-bit precision. */

typedef enum { J_NULL, J_BOOL, J_NUM, J_STR, J_ARR, J_OBJ } jtype;

typedef struct jval {
  jtype type;
  int boolean;
  int is_int;
  long long i;
  double num;
  char *str;             /* J_STR */
  size_t n;              /* J_ARR, J_OBJ: number of items */
  char **keys;           /* J_OBJ */
  struct jval **items;   /* J_ARR, J_OBJ */
} jval;

typedef struct { const char *p; int depth; } jreader;

static void jfree(jval *v) {
  if (!v) return;
  for (size_t k = 0; k < v->n; k++) {
    if (v->keys) free(v->keys[k]);
    jfree(v->items[k]);
  }
  free(v->keys);
  free(v->items);
  free(v->str);
  free(v);
}

static void jskip(jreader *r) {
  while (*r->p == ' ' || *r->p == '\t' || *r->p == '\n' || *r->p == '\r') r->p++;
}

static int hex4(const char *p, unsigned *out) {
  unsigned v = 0;
  for (int k = 0; k < 4; k++) {
    char c = p[k];
    v <<= 4;
    if (c >= '0' && c <= '9') v |= (unsigned)(c - '0');
    else if (c >= 'a' && c <= 'f') v |= (unsigned)(c - 'a' + 10);
    else if (c >= 'A' && c <= 'F') v |= (unsigned)(c - 'A' + 10);
    else return 0;
  }
  *out = v;
  return 1;
}

static size_t put_utf8(char *o, unsigned cp) {
  if (cp < 0x80) { o[0] = (char)cp; return 1; }
  if (cp < 0x800) { o[0] = (char)(0xC0 | (cp >> 6)); o[1] = (char)(0x80 | (cp & 0x3F)); return 2; }
  if (cp < 0x10000) {
    o[0] = (char)(0xE0 | (cp >> 12)); o[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
    o[2] = (char)(0x80 | (cp & 0x3F)); return 3;
  }
  o[0] = (char)(0xF0 | (cp >> 18)); o[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
  o[2] = (char)(0x80 | ((cp >> 6) & 0x3F)); o[3] = (char)(0x80 | (cp & 0x3F)); return 4;
}

/* A string body after its opening quote; an escape never grows the text. */
static char *jstring(jreader *r) {
  const char *s = r->p;
  size_t cap = 0;
  while (s[cap] && s[cap] != '"') cap += (s[cap] == '\\' && s[cap + 1]) ? 2 : 1;
  if (s[cap] != '"') return NULL;
  char *out = malloc(cap + 1), *o = out;
  if (!out) return NULL;
  while (*r->p != '"') {
    char c = *r->p++;
    if ((unsigned char)c < 0x20) { free(out); return NULL; }
    if (c != '\\') { *o++ = c; continue; }
    c = *r->p++;
    switch (c) {
      case '"': *o++ = '"'; break;
      case '\\': *o++ = '\\'; break;
      case '/': *o++ = '/'; break;
      case 'b': *o++ = '\b'; break;
      case 'f': *o++ = '\f'; break;
      case 'n': *o++ = '\n'; break;
      case 'r': *o++ = '\r'; break;
      case 't': *o++ = '\t'; break;
      case 'u': {
        unsigned cp, lo;
        if (!hex4(r->p, &cp)) { free(out); return NULL; }
        r->p += 4;
        if (cp >= 0xD800 && cp < 0xDC00 && r->p[0] == '\\' && r->p[1] == 'u' && hex4(r->p + 2, &lo)
            && lo >= 0xDC00 && lo < 0xE000) {
          cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
          r->p += 6;
        }
        o += put_utf8(o, cp);
        break;
      }
      default: free(out); return NULL;
    }
  }
  r->p++;
  *o = 0;
  return out;
}

static jval *jparse_value(jreader *r);

static jval *jnew(jtype t) {
  jval *v = calloc(1, sizeof *v);
  if (v) v->type = t;
  return v;
}

static int jpush(jval *v, char *key, jval *item) {
  jval **items = realloc(v->items, (v->n + 1) * sizeof *items);
  if (!items) return 0;
  v->items = items;
  if (v->type == J_OBJ) {
    char **keys = realloc(v->keys, (v->n + 1) * sizeof *keys);
    if (!keys) return 0;
    v->keys = keys;
    v->keys[v->n] = key;
  }
  v->items[v->n++] = item;
  return 1;
}

static jval *jparse_container(jreader *r, jtype t, char close) {
  jval *v = jnew(t);
  if (!v) return NULL;
  jskip(r);
  if (*r->p == close) { r->p++; return v; }
  for (;;) {
    char *key = NULL;
    jskip(r);
    if (t == J_OBJ) {
      if (*r->p != '"') break;
      r->p++;
      if (!(key = jstring(r))) break;
      jskip(r);
      if (*r->p != ':') { free(key); break; }
      r->p++;
    }
    jval *item = jparse_value(r);
    if (!item || !jpush(v, key, item)) { free(key); jfree(item); break; }
    jskip(r);
    if (*r->p == ',') { r->p++; continue; }
    if (*r->p == close) { r->p++; return v; }
    break;
  }
  jfree(v);
  return NULL;
}

static jval *jparse_value(jreader *r) {
  if (++r->depth > 64) return NULL;
  jskip(r);
  jval *v = NULL;
  char c = *r->p;
  if (c == '{') { r->p++; v = jparse_container(r, J_OBJ, '}'); }
  else if (c == '[') { r->p++; v = jparse_container(r, J_ARR, ']'); }
  else if (c == '"') {
    r->p++;
    char *s = jstring(r);
    if (s && (v = jnew(J_STR))) v->str = s; else free(s);
  } else if (!strncmp(r->p, "true", 4)) { r->p += 4; if ((v = jnew(J_BOOL))) v->boolean = 1; }
  else if (!strncmp(r->p, "false", 5)) { r->p += 5; v = jnew(J_BOOL); }
  else if (!strncmp(r->p, "null", 4)) { r->p += 4; v = jnew(J_NULL); }
  else if (c == '-' || (c >= '0' && c <= '9')) {
    /* Integers go through strtoll. Fractions go through strtod, which reads
     * '.' as the decimal point in the "C" locale: the locale of any process
     * that never calls setlocale, the JVM included. */
    const char *s = r->p;
    if (*s == '-') s++;
    if (*s < '0' || *s > '9') { r->depth--; return NULL; }
    while (*s >= '0' && *s <= '9') s++;
    int frac = (*s == '.' || *s == 'e' || *s == 'E');
    if ((v = jnew(J_NUM))) {
      if (!frac) {
        v->is_int = 1;
        v->i = strtoll(r->p, NULL, 10);
        v->num = (double)v->i;
        r->p = s;
      } else {
        char *end;
        v->num = strtod(r->p, &end);
        r->p = end;
        v->i = (long long)v->num;
      }
    }
  }
  r->depth--;
  return v;
}

static jval *jparse(const char *text) {
  if (!text) return NULL;
  jreader r = {text, 0};
  jval *v = jparse_value(&r);
  if (v) { jskip(&r); if (*r.p) { jfree(v); v = NULL; } }
  return v;
}

static jval *jget(const jval *obj, const char *key) {
  if (!obj || obj->type != J_OBJ) return NULL;
  for (size_t k = 0; k < obj->n; k++)
    if (!strcmp(obj->keys[k], key)) return obj->items[k];
  return NULL;
}

static jval *jat(const jval *arr, size_t k) {
  return (arr && arr->type == J_ARR && k < arr->n) ? arr->items[k] : NULL;
}

static const char *jstr(const jval *v) { return (v && v->type == J_STR) ? v->str : NULL; }
static long long jint(const jval *v, long long fallback) { return (v && v->type == J_NUM) ? v->i : fallback; }
static double jnum(const jval *v, double fallback) { return (v && v->type == J_NUM) ? v->num : fallback; }

/* ------------------------------------------------------------------------ */
/* Errors */

static void set_error(char **error_json, const char *kind, const char *message) {
  if (!error_json) return;
  char buf[512];
  /* messages here are fixed ASCII text without quotes or backslashes */
  snprintf(buf, sizeof buf, "{\"kind\":\"%s\",\"message\":\"%s\"}", kind, message);
  *error_json = dup_str(buf);
}

/* ------------------------------------------------------------------------ */
/* Stills: PAM (P7), 8 bits, RGB or RGB_ALPHA */

typedef struct { int w, h; unsigned char *rgba; } image;

static int pam_header(FILE *f, int *w, int *h, int *depth) {
  char line[256];
  int maxval = 0;
  *w = *h = *depth = 0;
  if (!fgets(line, sizeof line, f) || strncmp(line, "P7", 2)) return 0;
  while (fgets(line, sizeof line, f)) {
    if (!strncmp(line, "ENDHDR", 6)) return *w > 0 && *h > 0 && (*depth == 3 || *depth == 4) && maxval == 255;
    if (!strncmp(line, "WIDTH ", 6)) *w = atoi(line + 6);
    else if (!strncmp(line, "HEIGHT ", 7)) *h = atoi(line + 7);
    else if (!strncmp(line, "DEPTH ", 6)) *depth = atoi(line + 6);
    else if (!strncmp(line, "MAXVAL ", 7)) maxval = atoi(line + 7);
  }
  return 0;
}

static int read_pam(const char *path, image *img, int pixels) {
  FILE *f = open_file(path, "rb");
  if (!f) return 0;
  int depth, ok = pam_header(f, &img->w, &img->h, &depth);
  img->rgba = NULL;
  if (ok && pixels) {
    size_t n = (size_t)img->w * (size_t)img->h;
    unsigned char *raw = malloc(n * (size_t)depth);
    img->rgba = malloc(n * 4);
    ok = raw && img->rgba && fread(raw, (size_t)depth, n, f) == n;
    for (size_t k = 0; ok && k < n; k++) {
      memcpy(img->rgba + 4 * k, raw + depth * k, 3);
      img->rgba[4 * k + 3] = depth == 4 ? raw[depth * k + 3] : 255;
    }
    free(raw);
    if (!ok) { free(img->rgba); img->rgba = NULL; }
  }
  fclose(f);
  return ok;
}

static int has_suffix(const char *s, const char *suffix) {
  size_t n = strlen(s), m = strlen(suffix);
  return n >= m && !strcmp(s + n - m, suffix);
}

/* ------------------------------------------------------------------------ */
/* The engine */

/* The test suite also builds the mock as another ABI version
 * (-DWMARK_MOCK_ABI=1) to check the host's compatibility rule. */
#ifndef WMARK_MOCK_ABI
#define WMARK_MOCK_ABI WMARK_ENGINE_ABI_VERSION
#endif

uint32_t wmark_abi_version(void) { return WMARK_MOCK_ABI; }

wmark_engine *wmark_engine_open(const char *config_json, char **error_json) {
  jval *config = jparse(config_json);
  jval *fail = jget(config, "fail");
  int failing = fail && fail->type == J_BOOL && fail->boolean;
  jfree(config);
  if (failing) {
    set_error(error_json, "unavailable", "mock asked to fail");
    return NULL;
  }
  return calloc(1, sizeof(wmark_engine));
}

void wmark_engine_close(wmark_engine *engine) { free(engine); }

char *wmark_engine_info(wmark_engine *engine) {
  (void)engine;
  return dup_str(
      "{\"engine/id\":\"mock\",\"engine/version\":\"2.0\",\"available?\":true,\"problems\":[],"
      "\"capabilities\":{\"spec-versions\":[1,2],"
      "\"layers\":[\"image\",\"text\",\"flipbook\",\"bitmap\"],\"animations\":[\"flip-y\"],"
      "\"timing\":[\"always\",\"windows\",\"periodic\"],"
      "\"placement\":[\"fixed\",\"burst-scatter\",\"per-window\"],"
      "\"codecs\":[\"h264\",\"rawvideo\"],\"containers\":[\"mp4\",\"mov\",\"y4m\"],"
      "\"audio\":[\"copy\",\"none\"],\"sources\":[\"file\"]}}");
}

char *wmark_engine_probe(wmark_engine *engine, const char *source, char **error_json) {
  (void)engine;
  if (!source || strstr(source, "missing")) {
    set_error(error_json, "invalid", "no such file");
    return NULL;
  }
  if (has_suffix(source, ".pam")) {
    image img;
    if (!read_pam(source, &img, 0)) {
      set_error(error_json, "invalid", "not a readable 8-bit RGB or RGBA PAM file");
      return NULL;
    }
    char buf[160];
    snprintf(buf, sizeof buf, "{\"kind\":\"image\",\"width\":%d,\"height\":%d,\"rotation\":0,\"has-audio?\":false}",
             img.w, img.h);
    return dup_str(buf);
  }
  if (strstr(source, ".png"))
    return dup_str("{\"kind\":\"image\",\"width\":400,\"height\":160,\"rotation\":0,\"has-audio?\":false}");
  return dup_str(
      "{\"kind\":\"video\",\"width\":640,\"height\":360,\"fps-num\":30,\"fps-den\":1,\"frames\":90,"
      "\"duration-s\":3.0,\"start-s\":0.0,\"vfr?\":false,\"has-audio?\":false,\"rotation\":0}");
}

/* {"source": path, "output": path} -> straight RGBA8, row-major, at output;
 * returns {"width": w, "height": h}. */
char *wmark_engine_decode_still(wmark_engine *engine, const char *request_json, char **error_json) {
  (void)engine;
  jval *req = jparse(request_json);
  const char *source = jstr(jget(req, "source")), *output = jstr(jget(req, "output"));
  char *result = NULL;
  image img = {0, 0, NULL};
  if (!source || !output) set_error(error_json, "invalid", "decode request needs source and output");
  else if (!has_suffix(source, ".pam")) set_error(error_json, "unsupported", "the mock decodes PAM (P7) stills only");
  else if (!read_pam(source, &img, 1)) set_error(error_json, "invalid", "not a readable 8-bit RGB or RGBA PAM file");
  else {
    FILE *f = open_file(output, "wb");
    size_t n = (size_t)img.w * (size_t)img.h * 4;
    if (!f || fwrite(img.rgba, 1, n, f) != n) set_error(error_json, "failed", "cannot write the decoded still");
    else {
      char buf[96];
      snprintf(buf, sizeof buf, "{\"width\":%d,\"height\":%d}", img.w, img.h);
      result = dup_str(buf);
    }
    if (f && fclose(f) && result) { free(result); result = NULL; set_error(error_json, "failed", "cannot write the decoded still"); }
  }
  free(img.rgba);
  jfree(req);
  return result;
}

static int is_v2(const jval *request) {
  return jint(jget(jget(request, "spec"), "spec/version"), 1) == 2;
}

/* The plan is the request itself: render_start reads it again. */
char *wmark_engine_prepare(wmark_engine *engine, const char *request_json, char **error_json) {
  (void)engine;
  jval *req = jparse(request_json);
  const char *out = jstr(jget(jget(req, "output"), "path"));
  const char *container = jstr(jget(jget(req, "output"), "container"));
  char *plan = NULL;
  long long version = jint(jget(jget(req, "spec"), "spec/version"), 1);
  if (!req) set_error(error_json, "invalid", "request is not JSON");
  else if (version != 1 && version != 2) set_error(error_json, "unsupported", "the mock takes render spec 1 and 2");
  else if (!out) set_error(error_json, "invalid", "request has no output path");
  else if (is_v2(req) && !(container && !strcmp(container, "y4m")))
    set_error(error_json, "unsupported", "the mock writes render spec v2 as y4m only");
  else if (!is_v2(req) && container && !strcmp(container, "y4m"))
    set_error(error_json, "unsupported", "the mock renders y4m from render spec v2 only");
  else plan = dup_str(request_json);
  jfree(req);
  return plan;
}

/* ------------------------------------------------------------------------ */
/* Render spec v2: the reference semantics (kernel watermark.render and
 * watermark.render.v2), then compositing */

static long long floor_mod(long long a, long long m) { long long r = a % m; return r < 0 ? r + m : r; }

static double floor_d(double x) {
  double t = (double)(long long)x;
  return t > x ? t - 1.0 : t;
}

/* One IEEE product, rounded before any addition (no FMA). */
static double mul(double a, double b) { volatile double r = a * b; return r; }

static long long window_index(const jval *windows, long long n) {
  for (size_t k = 0; k < (windows ? windows->n : 0); k++) {
    const jval *w = windows->items[k];
    if (jint(jget(w, "start"), 0) <= n && n <= jint(jget(w, "end"), -1)) return (long long)k;
  }
  return -1;
}

static int active(const jval *timing, long long n) {
  const char *type = jstr(jget(timing, "type"));
  if (!type) return 0;
  if (!strcmp(type, "always")) return 1;
  if (!strcmp(type, "windows")) return window_index(jget(timing, "windows"), n) >= 0;
  if (!strcmp(type, "periodic")) {
    long long offset = jint(jget(timing, "offset"), 0), period = jint(jget(timing, "period"), 1);
    return n >= offset && floor_mod(n - offset, period) < jint(jget(timing, "length"), 0);
  }
  return 0;
}

static double scatter(const jval *ab, long long burst, double margin, long long modulus) {
  long long m = floor_mod(burst * jint(jget(ab, "a"), 0) + jint(jget(ab, "b"), 0), modulus);
  return margin + mul(1.0 - mul(2.0, margin), (double)m / (double)modulus);
}

static int placement_fractions(const jval *layer, long long n, double *fx, double *fy) {
  const jval *p = jget(layer, "placement"), *timing = jget(layer, "timing");
  const char *type = jstr(jget(p, "type"));
  if (!type) return 0;
  if (!strcmp(type, "fixed")) {
    *fx = jnum(jget(p, "fx"), 0.0); *fy = jnum(jget(p, "fy"), 0.0);
    return 1;
  }
  if (!strcmp(type, "burst-scatter")) {
    long long period = jint(jget(timing, "period"), 1), modulus = jint(jget(p, "modulus"), 2);
    long long burst = (n - jint(jget(timing, "offset"), 0)) / period;   /* n >= offset: quot = floor */
    double margin = jnum(jget(p, "margin"), 0.0);
    *fx = scatter(jget(p, "x"), burst, margin, modulus);
    *fy = scatter(jget(p, "y"), burst, margin, modulus);
    return 1;
  }
  if (!strcmp(type, "per-window")) {
    const jval *pt = jat(jget(p, "points"), (size_t)window_index(jget(timing, "windows"), n));
    if (!pt) return 0;
    *fx = jnum(jat(pt, 0), 0.0); *fy = jnum(jat(pt, 1), 0.0);
    return 1;
  }
  return 0;
}

typedef struct { const char *id; image img; } bitmap;

typedef struct {
  const jval *spec;
  int width, height;
  long long frames, first_frame;
  bitmap *bitmaps;
  size_t nbitmaps;
} scene;

static const image *find_bitmap(const scene *s, const char *id) {
  for (size_t k = 0; id && k < s->nbitmaps; k++)
    if (!strcmp(s->bitmaps[k].id, id)) return &s->bitmaps[k].img;
  return NULL;
}

static void free_scene(scene *s) {
  for (size_t k = 0; k < s->nbitmaps; k++) free(s->bitmaps[k].img.rgba);
  free(s->bitmaps);
}

/* Read every bitmap the spec names; 0 (with a message) if one is unusable. */
static int load_scene(const jval *spec, scene *s, const char **message) {
  const jval *canvas = jget(spec, "canvas"), *timebase = jget(spec, "timebase"), *bitmaps = jget(spec, "bitmaps");
  memset(s, 0, sizeof *s);
  s->spec = spec;
  s->width = (int)jint(jget(canvas, "width"), 0);
  s->height = (int)jint(jget(canvas, "height"), 0);
  s->frames = jint(jget(timebase, "frames"), 0);
  s->first_frame = jint(jget(timebase, "first-frame"), 0);
  if (s->width <= 0 || s->height <= 0 || s->frames <= 0 || !bitmaps || bitmaps->type != J_OBJ) {
    *message = "render spec v2 needs a canvas, a timebase and bitmaps";
    return 0;
  }
  s->bitmaps = calloc(bitmaps->n ? bitmaps->n : 1, sizeof *s->bitmaps);
  for (size_t k = 0; k < bitmaps->n; k++) {
    const jval *b = bitmaps->items[k];
    bitmap *bm = &s->bitmaps[s->nbitmaps++];
    bm->id = bitmaps->keys[k];
    bm->img.w = (int)jint(jget(b, "width"), 0);
    bm->img.h = (int)jint(jget(b, "height"), 0);
    const char *path = jstr(jget(b, "path"));
    size_t n = (size_t)bm->img.w * (size_t)bm->img.h * 4;
    FILE *f = path ? open_file(path, "rb") : NULL;
    bm->img.rgba = (f && n) ? malloc(n + 1) : NULL;
    /* exactly width x height x 4 bytes: one more would be a wrong size */
    int ok = bm->img.rgba && fread(bm->img.rgba, 1, n + 1, f) == n;
    if (f) fclose(f);
    if (!ok) { *message = "a bitmap file is missing or has the wrong size"; return 0; }
  }
  return 1;
}

/* What `layer` draws at frame n (watermark.render.v2/draw-at). */
static const image *draw_at(const scene *s, const jval *layer, long long n, long long *x, long long *y) {
  if (!active(jget(layer, "timing"), n)) return NULL;
  const char *kind = jstr(jget(layer, "kind"));
  if (kind && !strcmp(kind, "flipbook")) {
    const jval *placed = jget(layer, "rest"), *cycle = jget(layer, "cycle");
    if (cycle) {
      long long start = jint(jget(cycle, "start"), 0);
      if (n >= start) {
        const jval *frame = jat(jget(cycle, "frames"), (size_t)floor_mod(n - start, jint(jget(cycle, "period"), 1)));
        if (frame) placed = frame;
      }
    }
    *x = jint(jget(placed, "x"), 0);
    *y = jint(jget(placed, "y"), 0);
    return find_bitmap(s, jstr(jget(placed, "bitmap")));
  }
  if (kind && !strcmp(kind, "bitmap")) {
    const image *img = find_bitmap(s, jstr(jget(layer, "bitmap")));
    double fx, fy;
    if (!img || !placement_fractions(layer, n, &fx, &fy)) return NULL;
    const jval *p = jget(layer, "placement");
    *x = (long long)floor_d(mul(fx, (double)(s->width - img->w)) + (double)jint(jget(p, "px"), 0));
    *y = (long long)floor_d(mul(fy, (double)(s->height - img->h)) + (double)jint(jget(p, "py"), 0));
    return img;
  }
  return NULL;
}

/* Straight-alpha source over an opaque canvas, clipped to it. */
static void composite(unsigned char *rgb, int W, int H, const image *img, long long x0, long long y0) {
  for (long long y = y0 < 0 ? 0 : y0; y < y0 + img->h && y < H; y++)
    for (long long x = x0 < 0 ? 0 : x0; x < x0 + img->w && x < W; x++) {
      const unsigned char *s = img->rgba + 4 * ((y - y0) * img->w + (x - x0));
      unsigned char *d = rgb + 3 * (y * W + x);
      unsigned a = s[3];
      for (int c = 0; c < 3; c++) d[c] = (unsigned char)((s[c] * a + d[c] * (255u - a) + 127u) / 255u);
    }
}

static void send_progress(struct wmark_render *r, long long done, long long total) {
  char event[128];
  snprintf(event, sizeof event, "{\"event\":\"progress\",\"fraction\":%.3f,\"frame\":%lld}",
           (double)done / (double)total, done);
  r->on_event(r->user, event);
}

/* "done", "cancelled", or NULL after setting *message */
static const char *render_v2(struct wmark_render *r, const jval *req, const char **message) {
  const jval *spec = jget(req, "spec"), *layers = jget(spec, "layers");
  const jval *timebase = jget(spec, "timebase");
  const char *out = jstr(jget(jget(req, "output"), "path"));
  scene s;
  if (!load_scene(spec, &s, message)) { free_scene(&s); return NULL; }
  size_t npx = (size_t)s.width * (size_t)s.height;
  unsigned char *rgb = malloc(npx * 3), *luma = malloc(npx);
  FILE *f = out ? open_file(out, "wb") : NULL;
  const char *status = "done";
  if (!rgb || !luma || !f) { *message = "cannot write the output"; status = NULL; }
  else {
    fprintf(f, "YUV4MPEG2 W%d H%d F%lld:%lld Ip A1:1 Cmono\n", s.width, s.height,
            jint(jget(timebase, "fps-num"), 30), jint(jget(timebase, "fps-den"), 1));
    long long step = s.frames / 10 > 0 ? s.frames / 10 : 1;
    for (long long i = 0; i < s.frames; i++) {
      if (r->cancelled) { status = "cancelled"; break; }
      long long n = s.first_frame + i;
      memset(rgb, 255, npx * 3);
      for (size_t k = 0; k < (layers ? layers->n : 0); k++) {
        long long x, y;
        const image *img = draw_at(&s, layers->items[k], n, &x, &y);
        if (img) composite(rgb, s.width, s.height, img, x, y);
      }
      for (size_t p = 0; p < npx; p++)
        luma[p] = (unsigned char)((299u * rgb[3 * p] + 587u * rgb[3 * p + 1] + 114u * rgb[3 * p + 2] + 500u) / 1000u);
      if (fputs("FRAME\n", f) < 0 || fwrite(luma, 1, npx, f) != npx) {
        *message = "cannot write the output"; status = NULL; break;
      }
      if ((i + 1) % step == 0 || i + 1 == s.frames) send_progress(r, i + 1, s.frames);
    }
  }
  if (f && fclose(f) && status) { *message = "cannot write the output"; status = NULL; }
  free(rgb);
  free(luma);
  free_scene(&s);
  return status;
}

/* Render spec v1: progress, then a placeholder file. */
static const char *render_placeholder(struct wmark_render *r, const jval *req, const char **message) {
  long long frames = jint(jget(jget(jget(req, "spec"), "timebase"), "frames"), 90);
  for (int i = 1; i <= 10; i++) {
    if (r->cancelled) return "cancelled";
    struct timespec ts = {0, 20 * 1000 * 1000};
    nanosleep(&ts, NULL);
    send_progress(r, frames * i / 10, frames);
  }
  if (r->cancelled) return "cancelled";
  FILE *f = open_file(jstr(jget(jget(req, "output"), "path")), "wb");
  if (!f) { *message = "cannot write output"; return NULL; }
  fputs("mock render\n", f);
  fclose(f);
  return "done";
}

static void *render_main(void *arg) {
  struct wmark_render *r = arg;
  jval *req = jparse(r->plan);
  const char *message = "plan is not JSON";
  const char *status = req ? (is_v2(req) ? render_v2(r, req, &message) : render_placeholder(r, req, &message)) : NULL;
  char event[640];
  if (status)
    snprintf(event, sizeof event, "{\"event\":\"finished\",\"status\":\"%s\"}", status);
  else
    snprintf(event, sizeof event, "{\"event\":\"finished\",\"status\":\"failed\","
                                  "\"error\":{\"kind\":\"failed\",\"message\":\"%s\"}}", message);
  jfree(req);
  r->on_event(r->user, event);
  return NULL;
}

wmark_render *wmark_render_start(wmark_engine *engine, const char *plan_json,
                                 wmark_event_fn on_event, void *user, char **error_json) {
  (void)engine;
  if (!plan_json || !on_event) {
    set_error(error_json, "invalid", "render needs a plan and an event function");
    return NULL;
  }
  struct wmark_render *r = calloc(1, sizeof *r);
  r->on_event = on_event;
  r->user = user;
  r->plan = dup_str(plan_json);
  if (pthread_create(&r->thread, NULL, render_main, r)) {
    set_error(error_json, "failed", "cannot start a render thread");
    free(r->plan);
    free(r);
    return NULL;
  }
  return r;
}

void wmark_render_cancel(wmark_render *render) { if (render) render->cancelled = 1; }

void wmark_render_release(wmark_render *render) {
  if (!render) return;
  pthread_join(render->thread, NULL);
  free(render->plan);
  free(render);
}

void wmark_free(char *s) { free(s); }
