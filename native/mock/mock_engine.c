/* SPDX-FileCopyrightText: 2026 The clogem-wmark authors */
/* SPDX-License-Identifier: EPL-2.0 */
/*
 * mock_engine.c -- a test double implementing wmark_engine.h.
 *
 * Used by the JVM test suite to prove the C ABI end to end (library lookup,
 * version handshake, JSON exchange, upcall events from a foreign thread,
 * cancellation) without an actual renderer. It "renders" by writing a small
 * file to the plan's output path after emitting progress events.
 *
 *   cc -shared -fPIC -O2 -pthread -o libwmark_engine.so mock_engine.c
 */
#include "../include/wmark_engine.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct wmark_engine { int unused; };

struct wmark_render {
  wmark_event_fn on_event;
  void *user;
  char *output;
  int frames;
  volatile int cancelled;
  pthread_t thread;
};

static char *dup_str(const char *s) {
  size_t n = strlen(s) + 1;
  char *out = malloc(n);
  memcpy(out, s, n);
  return out;
}

static void set_error(char **error_json, const char *kind, const char *message) {
  if (!error_json) return;
  char buf[512];
  snprintf(buf, sizeof buf, "{\"kind\":\"%s\",\"message\":\"%s\"}", kind, message);
  *error_json = dup_str(buf);
}

/* Crude extraction of "key":"value" / "key":number -- good enough for a mock
 * that only reads JSON its own host produced. */
static char *json_string_after(const char *json, const char *anchor, const char *key) {
  const char *from = anchor ? strstr(json, anchor) : json;
  if (!from) return NULL;
  char pattern[64];
  snprintf(pattern, sizeof pattern, "\"%s\":\"", key);
  const char *p = strstr(from, pattern);
  if (!p) return NULL;
  p += strlen(pattern);
  const char *end = strchr(p, '"');
  if (!end) return NULL;
  char *out = malloc((size_t)(end - p) + 1);
  memcpy(out, p, (size_t)(end - p));
  out[end - p] = 0;
  return out;
}

static long json_number_after(const char *json, const char *key, long fallback) {
  char pattern[64];
  snprintf(pattern, sizeof pattern, "\"%s\":", key);
  const char *p = strstr(json, pattern);
  return p ? strtol(p + strlen(pattern), NULL, 10) : fallback;
}

uint32_t wmark_abi_version(void) { return WMARK_ENGINE_ABI_VERSION; }

wmark_engine *wmark_engine_open(const char *config_json, char **error_json) {
  if (config_json && strstr(config_json, "\"fail\":true")) {
    set_error(error_json, "unavailable", "mock asked to fail");
    return NULL;
  }
  return calloc(1, sizeof(wmark_engine));
}

void wmark_engine_close(wmark_engine *engine) { free(engine); }

char *wmark_engine_info(wmark_engine *engine) {
  (void)engine;
  return dup_str(
      "{\"engine/id\":\"mock\",\"engine/version\":\"1.0\",\"available?\":true,\"problems\":[],"
      "\"capabilities\":{\"layers\":[\"image\",\"text\"],\"animations\":[\"flip-y\"],"
      "\"timing\":[\"always\",\"windows\",\"periodic\"],"
      "\"placement\":[\"fixed\",\"burst-scatter\",\"per-window\"],"
      "\"codecs\":[\"h264\"],\"containers\":[\"mp4\",\"mov\"],"
      "\"audio\":[\"copy\",\"none\"],\"sources\":[\"file\"]}}");
}

char *wmark_engine_probe(wmark_engine *engine, const char *source, char **error_json) {
  (void)engine;
  if (!source || strstr(source, "missing")) {
    set_error(error_json, "invalid", "no such file");
    return NULL;
  }
  if (strstr(source, ".png"))
    return dup_str("{\"kind\":\"image\",\"width\":400,\"height\":160,\"rotation\":0,\"has-audio?\":false}");
  return dup_str(
      "{\"kind\":\"video\",\"width\":640,\"height\":360,\"fps-num\":30,\"fps-den\":1,\"frames\":90,"
      "\"duration-s\":3.0,\"start-s\":0.0,\"vfr?\":false,\"has-audio?\":false,\"rotation\":0}");
}

char *wmark_engine_prepare(wmark_engine *engine, const char *request_json, char **error_json) {
  (void)engine;
  char *out = json_string_after(request_json, "\"output\":", "path");
  if (!out) {
    set_error(error_json, "invalid", "request has no output path");
    return NULL;
  }
  long frames = json_number_after(request_json, "frames", 90);
  char *plan = malloc(strlen(out) + 128);
  sprintf(plan, "{\"output\":\"%s\",\"frames\":%ld}", out, frames);
  free(out);
  return plan;
}

static void *render_main(void *arg) {
  struct wmark_render *r = arg;
  char event[256];
  const char *status = "done";
  for (int i = 1; i <= 10; i++) {
    if (r->cancelled) { status = "cancelled"; break; }
    struct timespec ts = {0, 20 * 1000 * 1000};
    nanosleep(&ts, NULL);
    snprintf(event, sizeof event, "{\"event\":\"progress\",\"fraction\":%.2f,\"frame\":%d}",
             i / 10.0, r->frames * i / 10);
    r->on_event(r->user, event);
  }
  if (!r->cancelled) {
    FILE *f = fopen(r->output, "wb");
    if (f) { fputs("mock render\n", f); fclose(f); } else status = "failed";
  } else {
    status = "cancelled";
  }
  if (strcmp(status, "failed") == 0)
    r->on_event(r->user, "{\"event\":\"finished\",\"status\":\"failed\","
                         "\"error\":{\"kind\":\"failed\",\"message\":\"cannot write output\"}}");
  else {
    snprintf(event, sizeof event, "{\"event\":\"finished\",\"status\":\"%s\"}", status);
    r->on_event(r->user, event);
  }
  return NULL;
}

wmark_render *wmark_render_start(wmark_engine *engine, const char *plan_json,
                                 wmark_event_fn on_event, void *user, char **error_json) {
  (void)engine;
  struct wmark_render *r = calloc(1, sizeof *r);
  r->on_event = on_event;
  r->user = user;
  r->output = json_string_after(plan_json, NULL, "output");
  r->frames = (int)json_number_after(plan_json, "frames", 90);
  if (!r->output) {
    set_error(error_json, "invalid", "plan has no output");
    free(r);
    return NULL;
  }
  pthread_create(&r->thread, NULL, render_main, r);
  return r;
}

void wmark_render_cancel(wmark_render *render) { if (render) render->cancelled = 1; }

void wmark_render_release(wmark_render *render) {
  if (!render) return;
  pthread_join(render->thread, NULL);
  free(render->output);
  free(render);
}

void wmark_free(char *s) { free(s); }
