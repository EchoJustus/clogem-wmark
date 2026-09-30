<!-- SPDX-FileCopyrightText: 2026 The clogem-wmark authors -->
<!-- SPDX-License-Identifier: EPL-2.0 -->
# 0015. One in-process job queue in the core library, for every host

- **Status:** Accepted (2026-09-30). It carries on
  [ADR 0014](0014-the-dart-host.md): the Dart host could run a batch, but
  not queue jobs.
- **Date:** 2026-09-30
- **Builds on:** [ADR 0013](0013-host-logic-into-the-core-library.md),
  section 3 (one async core: `run-job!` returns a task) and
  [ADR 0008](0008-desktop-architecture-and-binary-size.md), section 3
  (Phase 2: GUI apps run the core library in-process on the Dart VM).

## Context

- The Core API's job use cases (`submit-job!`, `cancel-job!`, `list-jobs`,
  `subscribe-jobs!`) go through the `JobQueue` port. They are what the web UI
  and the REST API's clients use: queue a video, follow its progress, cancel
  it, see how it ended.
- The only in-process queue was the JVM's (`watermark.core.jobs.local`): a
  fixed thread pool, `java.util.UUID` and `java.time.Instant`. A program on
  the Dart VM could run a batch (`api/run-batch!`), but had no queue, so an
  app embedding the library had no job model to build its render list on.
- A second queue written for Dart would be a second answer to questions a
  user sees the answer to: which job runs next, what a cancel does to a
  job still waiting, in which order events arrive. Copies drift.
- The JVM's queue also had faults a portable one shouldn't copy:
  - each running job held a pool thread for its whole render, waiting on
    a task (`task/await`), though the pipeline has been asynchronous since
    ADR 0013;
  - a cancelled job still waiting stayed `queued` until its turn came;
  - a cancel that arrived after a job started but before its render began
    didn't stop the render;
  - events went out from pool threads as each change happened, so two
    threads' events could overtake each other;
  - `shutdown!` interrupted the pool but left jobs in their last state.

## Decision

### 1. The queue is core library code: `watermark.core.queue`

- `(queue/queue {:run :concurrency :spawn :on-shutdown})` is a `JobQueue`
  on every runtime.
  - `:run` is `(fn [job opts] task-of-results)`, as
    `watermark.core.jobs/run-job!` takes them; `local-queue` supplies
    `run-job!` against a system map, and the contract a stand-in.
  - `:concurrency` jobs run at once (default 1), oldest first.
  - `:spawn` starts a job off the caller's stack, so `submit!` returns at
    once; by default `task/later`.
  - `:on-shutdown` lets a host release what it made for the queue.
- **No thread waits for a render.** The queue counts the jobs running.
  Starting a job (planning, probing, drawing v2 bitmaps) runs through
  `:spawn`; its render then goes on asynchronously, and when its task
  settles the job ends and the next one starts.
- **What a job does:**
  - `queued`, `running`, then `done`, `failed` or `cancelled`; progress
    events are kept on the job (`:progress`);
  - a cancelled job still waiting is `cancelled` at once and never runs;
  - a running job is stopped through its render's handle. A cancel that
    comes before the render begins stops it as soon as the render hands
    over its handle. A cancelled job keeps what it finished;
  - a failure says why and what kind (`{:message :kind}`), also for a
    runner that throws, and frees the job's slot;
  - an unknown job is `:not-found`;
  - `shutdown!` cancels the jobs waiting, stops the running ones, and
    refuses new ones (`:unavailable`).
- **Events,** `{:type :job :job <the job>}`, one per change, go out in the
  order of the changes and one at a time. Changes queue their event under
  the queue's lock, and one caller delivers them, outside the lock. A
  listener that changes a job in turn, or another thread, never overtakes
  an event already queued. Listeners' errors are ignored, as before.
- Jobs show their public fields only; `list-jobs` lists them in the order
  they were submitted. Ids are random UUIDs (`random-uuid`, portable), and
  `:created-at` is `watermark.util.time/now`: ISO-8601 at millisecond
  precision, as `Instant` prints it.
- **A new host primitive,** `watermark.util.task/later`: a task for `(f)`,
  run soon but not on the caller's stack. The JVM runs it on the common
  pool; the Dart VM schedules it on the event loop (`Future(f)`). A task
  `f` returns is waited for, and what `f` throws fails the task.

### 2. Each host supplies only how jobs start

- **The JVM** (`watermark.core.jobs.local`, in `src/`): the library's queue,
  with jobs started on a pool of `concurrency` daemon threads, made by
  `local-queue` at run time (native-image safe). Planning and probing stay
  off the server's request threads; `shutdown!` also ends the pool.
  `watermark.app` wires it as before.
- **The Dart VM** (`watermark.dartvm.main/system`): the library's queue
  with the default `:spawn`, as the system's `:jobs`. The Dart VM runs one
  isolate's code at a time, so the lock is only a guard for the order of
  events there. An app that runs the library in a background isolate keeps
  the queue in that isolate and sends its events across.

### 3. A contract every in-process queue passes: `watermark.queue-contract`

- Portable, in `testkit/`, like the store contract. It drives a queue with a
  stand-in for `run-job!` whose jobs end when the contract says so, and
  checks the behaviour of section 1: 30 checks in nine cases (running to
  the end; one and two at a time; cancelling a job waiting, running, or
  before its render; failures; subscriptions; shutdown).
- Jobs end on other threads on the JVM, and later on the event loop on the
  Dart VM, so the contract is a task that records its checks as data;
  clojure.test counts an assertion only on the test's own thread. Run it as
  `(assert-all (task/await (run-all make)))` on the JVM and
  `(task/then (run-all make) assert-all)` on the Dart VM.
- It runs against `queue/queue` and the JVM's `jobs.local/queue`
  (`watermark.core.queue-test`), and against `queue/queue` on the Dart VM
  (`bb dart`). Disabling the cancel of a waiting job made it fail on both
  runtimes, as intended.
- Real jobs, with the machine's FFmpeg: on the JVM the existing job tests
  (`watermark.core.jobs-test`, the routes, the web UI and `bb e2e`) pass
  unchanged; on the Dart VM a job submitted through `api/submit-job!`
  renders (`queued`, `running`, progress, `done`, the output published),
  and one cancelled as it starts ends `cancelled` with nothing published.
- Hosted queues (M4) add their own checks to these: leases, retries, a
  dead-letter path, tenant isolation.

## Consequences

- A program on the Dart VM has the Core API's whole job model, with the
  same behaviour as the JVM's server: an app embedding the library can
  queue, follow and cancel renders as the web UI does.
- Changes a user of the JVM's server or web UI can see:
  - a job cancelled while it waits shows `cancelled` at once, not when its
    turn comes;
  - a cancel just after a job starts stops its render;
  - events arrive in order.
- `shutdown!` now ends the jobs too, for a host that calls it (an app
  closing its library). No JVM host calls it today: the server's exit ends
  its FFmpeg processes on its own.
- The JVM's queue holds a thread only while a job starts, not while it
  renders.
- **Bytes:** the queue replaces the JVM's code (`jobs.local` goes from 84
  lines to 39; the library's queue is 212). `wmark-dart` (linux-x64, Dart
  3.13.4) grows from 10,506,720 to 10,546,680 bytes (+39,960), measured
  2026-09-30, and still renders (checked by hand: a run with a text). No
  new dependency, native image or asset.

## Alternatives

- **A queue of its own in the Dart host:** rejected; two answers to what a
  user sees, and the JVM's faults (section "Context") would stay.
- **Keep the JVM's queue and port it:** rejected; it waits on a thread per
  render, which the Dart VM can't do and the JVM needn't.
- **One isolate per job on the Dart VM:** not now. Renders are FFmpeg
  processes, so the Dart side of a job is small; an app that wants the
  library off its UI isolate runs all of it in one background isolate.

## Sources

- Dart `Future(computation)`: runs `computation` asynchronously with
  `Timer.run`, and completes with its result, waiting for a future it
  returns: <https://api.dart.dev/dart-async/Future/Future.html> (read
  2026-09-30).
- Java `CompletableFuture.supplyAsync(Supplier)`: runs on
  `ForkJoinPool.commonPool()`:
  <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html>
  (read 2026-09-30).
