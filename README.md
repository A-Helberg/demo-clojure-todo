# todos — a full-stack Clojure app, end to end

The lessons in this repo show Clojure ideas in isolation. This is the other
half of the argument: a complete, working web application — registration and
login, a per-user todo list, a third-party JSON API — built the way a
production Clojure system actually gets built. It's deliberately the same
app you'd write as an ASP.NET + React starter, so you can compare piece by
piece.

**Clojure on the server, ClojureScript in the browser** — one language for
the whole stack, and the schemas, not the classes, are what's shared.

## Run it

```bash
mise install     # Java 21, Clojure CLI, Node — pinned in mise.toml
mise run setup   # npm deps: shadow-cljs, react, tailwind
mise run dev     # server :3000 + cljs hot reload + tailwind watch
```

`mise run dev` runs the three processes in the `Procfile` under
[overmind](https://github.com/DarthSim/overmind) (installed by mise).
`overmind connect server` attaches to any process's terminal;
`overmind restart server` bounces one without touching the others.

Then open **http://localhost:8280** (the dev server hot-reloads the UI and
proxies `/api` to the backend). Register an account, add todos, click
**history** on one after toggling it a few times.

Production build and run:

```bash
mise run build   # minified CSS + advanced-compiled JS
mise run prod    # serves everything from :3000
mise run test    # backend tests (kaocha): real handler, in-memory Datomic, no mocks
```

`mise run test-watch` keeps [kaocha](https://github.com/lambdaisland/kaocha)
running and reruns affected tests on every save; from the REPL the same
suite is `(run-tests)`, or `(kaocha.repl/run 'todo.server.api-test)` to
focus one namespace.

If port 3000 is taken, `PORT=3100 mise run dev-server`.

## The map, in ASP.NET terms

| This app | The ASP.NET equivalent | The difference |
|---|---|---|
| [`routes.clj`](src/clj/todo/server/routes.clj) | Controllers + attribute routing | The entire API is **one data structure** — print it, diff it, test it |
| [`schema.clj`](src/clj/todo/server/schema.clj) | DTOs + DataAnnotations + FluentValidation | Schemas are values; the same one validates requests, responses, and tests |
| [`handlers.clj`](src/clj/todo/server/handlers.clj) | Controller actions | Pure-ish functions: request map in, response map out |
| [`db.clj`](src/clj/todo/server/db.clj) | EF Core + DbContext + migrations | Datomic: queries are data, and history is built in (see below) |
| [`system.clj`](src/clj/todo/server/system.clj) | `Program.cs` DI registrations | The dependency graph is a map with explicit refs |
| [`views.cljs`](src/cljs/todo/ui/views.cljs) | React + JSX | Same React underneath; markup is vectors, so "templating" is just code |
| [`state.cljs`](src/cljs/todo/ui/state.cljs) | Redux / Zustand | One atom + plain functions, no library |

## The part worth slowing down for: the history endpoint

Toggle a todo twice, then `GET /api/todos/:id/history`:

```json
[{"at":"2026-07-03T06:49:52Z","attribute":"todo/done","value":false,"op":"set"},
 {"at":"2026-07-03T06:49:52Z","attribute":"todo/title","value":"try Clojure","op":"set"},
 {"at":"2026-07-03T06:50:08Z","attribute":"todo/done","value":false,"op":"retract"},
 {"at":"2026-07-03T06:50:08Z","attribute":"todo/done","value":true,"op":"set"}]
```

There is no audit table, no `TodoHistoryEntry` entity, no interceptor
writing change rows. Datomic **never updates in place** — every transaction
appends facts to an immutable log, and the current database is a value
derived from it. If you've built event sourcing by hand to get exactly this
(an append-only log, full audit, point-in-time queries), that architecture
is the database's native model here. `db.clj`'s `todo-history` is a plain
query against the history index; `d/as-of` gives you the whole database as
it was at any past instant.

The trade-offs are real too: Datomic is a JVM-only, single-writer system —
you'd size it like an event store, not like a sharded OLTP cluster. This
demo uses the embedded flavour (`datomic-local`), which persists to `data/`
with zero infrastructure; the same client API runs against a real
transactor or Datomic Cloud.

## EDN on the wire we own

Open the network tab: the frontend and backend exchange **EDN**, not JSON.

```clojure
{:id #uuid "2a0e5119-...", :title "speak EDN", :done false,
 :created-at #inst "2026-07-03T22:13:43.893-00:00"}
```

Both ends are Clojure, so the wire format is Clojure data: uuids stay
uuids, keywords stay keywords, timestamps stay instants. The
serialize → parse → keywordize → re-hydrate-types dance that a JSON API
forces on a TypeScript frontend simply isn't there —
[`api.cljs`](src/cljs/todo/ui/api.cljs) is `pr-str` out, `read-string` in.

This costs nothing in openness: the server content-negotiates, so
`curl -H "Accept: application/json"` gets ordinary JSON from the same
endpoints (there's a test proving both). And JSON remains the format at
the edge we *don't* own — the Open-Meteo integration below.

## Validation at every edge

Nothing crosses a boundary unvalidated, and it's the same
[malli](https://github.com/metosin/malli) schemas everywhere:

- **Requests in** — reitit coerces and validates `:parameters` before the
  handler runs. Bad input never reaches business logic:
  `{"humanized":{"email":["must be a valid email address"]}}` with a 400.
- **Responses out** — `:responses` schemas mean the server can't quietly
  start returning a different shape than it documents.
- **Third-party JSON** — the weather card is live data from
  [Open-Meteo](https://open-meteo.com/). Their payload is validated against
  `OpenMeteoResponse` on arrival, so if the provider renames a field you get
  an explicit 502 at the boundary, not a null somewhere three layers deep.

Because schemas are plain data, the tests import and reuse them — look at
[`weather_test.clj`](test/todo/server/weather_test.clj), which pins down the
provider contract without any network.

The same schemas do one more job: **interactive API docs**. Open
**http://localhost:3000/api-docs** (or via the :8280 proxy) for swagger-ui
over a generated OpenAPI 3.1 spec — `[:string {:min 8}]` on the password
becomes `minLength: 8` in the doc, `:uuid` becomes `format: uuid`, and so
on. In C# terms: DataAnnotations, FluentValidation rules and Swashbuckle
annotations collapsed into one artifact that can't drift apart, because
there is only one of it.

## Tests without mocks

A ring handler is a function from a request map to a response map, so
[`api_test.clj`](test/todo/server/api_test.clj) exercises the **real**
stack — routing, coercion, session cookies, password hashing, Datomic —
without starting a server or mocking a repository. Each test gets a fresh
in-memory Datomic in milliseconds. The whole suite runs with `mise run test`.

## REPL-driven development

The server that `mise run dev` starts already contains an **nREPL on port
7888** (it writes `.nrepl-port`, so editors auto-detect it). Connect and
you are inside the live app:

```clojure
(reset)      ; after editing any file: reload changed namespaces, restart
(run-tests)  ; the whole backend suite, in-process — no separate runner
```

`(reset)` is also how a schema or route change becomes live: the router
compiles malli schemas in at build time, so it's rebuilt, not patched.

Prefer a REPL without the app auto-started? `mise run repl`, then `(go)`
when you're ready.

`(reset)` takes well under a second. The edit-compile-restart loop you know
from .NET development becomes edit-eval, against a live system with its
state intact.

## Auth, briefly

Registration hashes passwords with bcrypt (buddy-hashers) and logs you in;
the session is an encrypted, http-only, SameSite=Lax cookie, so the server
stays stateless. Ownership checks are part of the Datomic query itself
(`owned-todo` in `db.clj`) — there is no code path that can forget them.

## What "taking this to production" would add

This is an honest starter, not a finished product. For a real deployment
you would add: a `TODO_SESSION_SECRET` (the dev default prints a warning),
HTTPS at the load balancer, CSRF tokens if you ever serve cross-origin,
rate limiting on the auth endpoints, structured logging/metrics, an uberjar
build, and a Datomic system sized for your data. None of that changes the
shape of what's here.
