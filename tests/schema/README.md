# Lock fixtures

Locks that two validators are asked about: stage 0's `readLock`, which runs on every
invocation, and the published JSON Schema, which runs in whoever's editor. Both are
derived from `LOCK_SCHEMA` in `src/stage0/flixw.java`, so the question worth asking is whether
they still reach the same verdict — and where they deliberately do not.

`tests/UnitCheck.java` walks all four directories through `readLock`. CI additionally
runs [taplo](https://taplo.tamasfe.dev) over `valid/` and `invalid/` against
`docs/schema/lock-v1.schema.json`, so the JSON Schema is exercised by something that is
not this repository's own code.

| directory | the schema | stage 0 |
|---|---|---|
| `valid/` | accepts | accepts |
| `invalid/` | rejects | rejects — `FLIXW002` |
| `semantic/` | accepts | rejects — the checks a regex cannot make |
| `advisory/` | rejects | accepts, with `FLIXW011` on stderr |

The last two rows are the boundary, and both are intentional.

`semantic/` holds locks that are well-formed and still wrong: a URL matching
`^https://[^\s]+$` that names no host, one whose path climbs out of itself, and a java pin
that is a dotted number but below the Java the compiler needs. A pattern cannot express
any of them, so `validateUrl` and `validateJavaPin` run after the schema has accepted the
value. An editor showing no warning on these files is the schema working as specified.

`advisory/` holds locks carrying a key flixw does not read. The schema sets
`additionalProperties: false`, so an editor flags them — which is what someone hand-editing
a lock wants. Stage 0 only prints `FLIXW011` and carries on, because the ordinary way to
meet an unknown key is a lock written by a *newer* flixw, and refusing to run would make
that project unbuildable for every collaborator who had not upgraded yet. `doctor --fix`
declines to rewrite such a lock for the same reason: the rewrite is from the values it
read, so it would delete the key it just said it was ignoring.

Adding a fixture means adding it to the directory whose verdict it belongs in; nothing
enumerates them by name.
