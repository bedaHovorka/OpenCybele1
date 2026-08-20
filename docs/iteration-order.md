# Iteration order: what is pinned, and why it did not change

Issue [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19) · Phase 1, stage 1-PRE ·
lock probe: `docs/probes/OrderLock.java`

The railway topology lives in `HashMapGraph`, a `Map<Doubleton<String>, String>`. Before this
change, four structural facts were decided by `java.util.HashMap`/`HashSet` **bucket order**, which
is not specified by the JLS and may change with the JDK. `String.hashCode` *is* specified, so the
orders were stable in practice for a given JDK build — a latent flake exactly of the kind that
destroys a golden-master suite after an upgrade. Goldens are recorded against this tree
(`docs/Phase1.md`, issue #24), so the orders had to become explicit **without changing which order
is chosen**.

## The rule

`Util.stableOrder(Collection)` — a stable sort by `Util.orderRank(o) = h ^ (h >>> 16)` ascending,
ties broken by the collection's own iteration order.

* `orderRank` is the *hash spread* function `HashMap` uses when it computes a bucket index.
  **It is not equivalent to `HashMap` ordering, and the match is not causal.** `HashMap`'s order is
  `spread & (n-1)` plus chain order plus resize history, not a total order by spread; over random
  key sets the two agree only about 9% of the time, and they disagree for e.g. `s1..s8`, or for
  `stA..stH` plus one extra name. They coincide for `stA..stH` / `tr1..tr7` because those hashes
  form a *contiguous run* that maps to consecutive buckets without wraparound — a property of that
  naming family, not of the function. That is enough: today's order only has to be preserved for
  the **default** topology, the one goldens are recorded against. For any other topology there is
  no "today's order" to preserve, only a determinism requirement, which the rule always meets.
* The tie-break is now well defined, because `HashMapGraph`'s backing map is a `LinkedHashMap`
  (declared as `HashMap` so the serialized field signature is untouched). Ties therefore fall back
  on **`sim.topology` declaration order** (`ScenarioConfig`, #18) — our own configuration, not a JDK
  internal. In the default topology exactly one tie exists: `tr1`, `tr4` and `tr7` all have
  `Doubleton.hashCode() == 228359`, and they come out in declaration order.

Nothing in `HashMapGraph` now exposes `HashMap` bucket order: `values()`, `nodeSet()` and
`get(node)` all iterate `orderedKeys()`. `entrySet()` is left as the live, insertion-ordered view;
production code does not use it.

## Before / after

Measured with `docs/probes/Order.java` on OpenJDK 21.0.11, pre-#19 tree vs post-#19 tree. The full
dump is byte-identical (`md5sum 5d8a2f2fbedfc5134fe3d48b0192d962`).

| Claim | Site | Decides | Before (`HashMap` order) | After (`Util.stableOrder`) |
|---|---|---|---|---|
| 1 · NDT-01 | `HashMapGraph.nodeSet()` → `RailwayMainAgent:92` | Station agent creation order | `[stB, stA, stD, stC, stF, stE, stH, stG]` | **identical** |
| 2 · NDT-02 | `HashMapGraph.values()` → `RailwayMainAgent:99` | RoadAgent creation order | `[tr1, tr4, tr7, tr5, tr3, tr6, tr2]` | **identical** |
| 3 · NDT-03 | `HashMapGraph.allNodesWithEdge()` → `RailwayMainAgent:101,105-106` | `leftStation` / `rightStation` | tr1 `(stA,stH)` · tr2 `(stH,stG)` · tr3 `(stG,stE)` · tr4 `(stE,stD)` · tr5 `(stD,stB)` · tr6 `(stF,stE)` · tr7 `(stC,stF)` | **identical — no production change, see below** |
| 4 · NDT-04 | `HashMapGraph.get(node)` + `Util.privatePath` | Route, hence who votes | all 56 ordered origin/destination routes, e.g. `stA→stC = [stA, tr1, stH, tr2, stG, tr3, stE, tr6, stF, tr7, stC]` | **identical** |

Supporting orders, also unchanged: candidate-edge order per station —
`stA [tr1]`, `stB [tr5]`, `stC [tr7]`, `stD [tr4, tr5]`, `stE [tr4, tr3, tr6]`, `stF [tr7, tr6]`,
`stG [tr3, tr2]`, `stH [tr1, tr2]`.

**No behavioural difference was found for the default topology.** Every one of the four orderings,
and every derived value listed above, is byte-for-byte what the pre-#19 tree produced.

**This does not generalise, and the scope matters.** On a topology containing a **cycle** the change
*does* pick a different route — e.g. with a cyclic four-station network, `stA→stD` was
`[stA, tr1, stB, tr2, stC, tr4, stD]` before and is `[stA, tr3, stC, tr4, stD]` after. That is the
intended outcome, not a regression: no golden exists for a cyclic topology, the pre-#19 choice there
was pure `HashMap` bucket order, and the whole point of this change is that such a route must stop
depending on the JDK. But do not quote "nothing differs" as a general statement — it holds for the
default `sim.topology` and for nothing else.

Two smaller pre/post differences that the four-claim table above does not cover, both on methods no
production code calls (verified by exhaustive grep), recorded so nobody diffing the trees thinks
they have found something:

* `remove(E)` returns `[stG, stE]` where it used to return `[stE, stG]`, and the returned collection
  is a `LinkedHashSet` rather than a `HashSet`. It is now in `Doubleton` order like every other
  endpoint accessor.
* `entrySet()` now goes through `orderedKeys()` like `values()`. En route it briefly returned
  insertion order; routing it through `orderedKeys()` puts it back on the pre-#19 order *and* stops
  one public accessor disagreeing with the order agents are actually created in.

### The alternative that was rejected

A plain `LinkedHashMap`/`LinkedHashSet` conversion — the obvious reading of "make it deterministic"
— is also deterministic but *changes* two of the four orders:

| | plain-`LinkedHash` result | verdict |
|---|---|---|
| NDT-01 station order | `[stA, stH, stG, stE, stD, stB, stF, stC]` | **changed** |
| NDT-02 road order | `[tr1, tr2, tr3, tr4, tr5, tr6, tr7]` | **changed** |
| NDT-04 candidate-edge order | `stE [tr3, tr4, tr6]`, `stF [tr6, tr7]`, `stG [tr2, tr3]` | changed |
| NDT-04 routes (all 56) | unchanged | — |
| NDT-03 endpoints | unchanged | — |

Agent creation order is observable in a trace, so that is a behaviour change and was not taken.
`OrderLock` fails on this variant, which is how the table above was produced.

Note *why* the routes survive either ordering: the default topology is a **tree** (8 stations,
7 tracks, connected), so between any two stations there is exactly one simple path and the DFS in
`Util.privatePath` cannot pick a different one. That is a property of the default `sim.topology`,
not of the algorithm — since #18 the topology is configurable, and a topology with a cycle *would*
let iteration order decide the route. This is precisely why claim 4 still had to be fixed even
though the default network hides it.

### Claim 4 is *two* hash sites, not one

`Util.privatePath` reached hash order twice, and a route can flip because of either. Anyone
debugging a route change must check both:

1. **Candidate-edge order** — `graph.get(start)` → `HashMapGraph.allIndicesJoinsWith` →
   `map.entrySet()` (`Util.java:173`). Decides which incident edge is tried first.
2. **Recursive-descent order** — the local `nodesToEdges` map, iterated at `Util.java:189`. Keyed on
   the **edge label**, so it re-sorts the candidates by *their own* hashes before recursing.

Site 2 is the easier one to miss and is the one that actually flipped the cyclic example above: the
candidate-edge order was *identical* before and after, and the route changed only because pre-#19
`nodesToEdges` was a `HashMap` keyed on the road label — `"tr1"` spreads to 115058 and `"tr3"` to
115060, so the DFS descended into `tr1` first even though the candidate order was `[tr3, tr1]`.
Both sites are fixed: site 1 by `orderedKeys()`, site 2 by making `nodesToEdges` a `LinkedHashMap`,
which makes the descent follow the candidate order instead of re-sorting it.

## Claim 3: why the endpoint order was *not* touched

The issue originally claimed that which endpoint becomes `leftStation` and which becomes
`rightStation` was hash-order dependent, and withdrew it. **The withdrawal is correct.** Re-verified
here:

1. `HashMapGraph.put(first, second, value)` stores `new Doubleton<N>(first, second)`. The pair keeps
   the argument order in its `first`/`second` fields; only its `hashCode`/`equals` are
   order-insensitive.
2. `allNodesWithEdge(road)` scans for `road.equals(value)`. Road ids are unique, so **exactly one**
   entry matches — iteration order decides only *when* the match is found, never *which*.
3. `collection.addAll(key)` iterates the `Doubleton`, and `DoubletonIterator` walks an explicit
   `INIT → FIRST → SECOND` state machine: `first`, then `second`. No hashing involved.

So `array[0]`/`array[1]` at `RailwayMainAgent:105-106` equal the `net.put(...)` argument order, i.e.
the `sim.topology` declaration order, on every JVM. The author's Czech comment at
`RailwayMainAgent:103` — *"pozor na prohozeni stanic — zalezi na implementaci
allNodesWithEdge(road)"* — warns about coupling to that method's implementation, which is right,
and not about hash order.

Imposing a lexicographic rule would flip 5 of the 7 roads (`tr2`, `tr3`, `tr4`, `tr5`, `tr6`) and
invert the `TRAVEL_LEFT` / `TRAVEL_RIGHT` symbol published on `ROAD.STATE`, which appears in every
trace. `OrderLock` pins all 7 endpoint pairs both as literals and as the structural rule
"endpoint order == `put()` argument order", so a future cleanup cannot do this silently.

## Coverage: these four sites are all that is left

Swept for other trace-observable hash-iteration sites. `RailwayMainAgent.trainStates` is already a
`LinkedHashMap`; `TreeMultiMap` is a `TreeMap` of `LinkedHashSet`. Every remaining `HashMap`
(`Station.pathDirs`, `Generator.openedChannels`, `Planning.trainCountDowns`,
`RailwayMainAgent.stationInfos` / `roadAgentStates` / `roadDelays` / `stationCapacities`,
`RoadAgent.invertedTimetable`) is **lookup-only — never iterated**, so its bucket order cannot reach
a trace. The four claims here cover the rest.

## Latent: serialization

`HashMapGraph.map` is declared `HashMap` so that a stream written *before* this change — which
carries a plain `HashMap` in that field — still deserializes. `readObject` then normalises the field
back to a `LinkedHashMap`, so `orderedKeys()`'s tie-break never depends on a live `HashMap`'s bucket
order. (The declared type is not about `serialVersionUID`: that is an explicit `1L` and never fed a
computed value.)

**Residual, for #27/#33:** `HashMap` does not serialize insertion order, so for a pre-#19 stream the
declaration order is already gone; what `readObject` freezes is the order the stream happened to
carry. No variant of the fix — including an explicit insertion counter, which such a stream would
not contain either — can do better, because the information is not in the stream. Streams written by
this code or later carry a `LinkedHashMap` and are unaffected. Nothing serializes agents today
(`cybelle/cybele.prop` sets `Local;NoSerialization`); this matters only if the JADE port persists
agent state built from an older stream.

## How to check

```bash
docs/probes/run.sh OrderLock    # self-verdicting; exit 1 on any drift
docs/probes/run.sh Order        # raw dump, for eyeballing / md5
```

`OrderLock` rebuilds the graph 100 times and re-checks every order each time. It compiles and passes
**unchanged on the pre-#19 tree too** (its one reference to `Util.orderRank`, which does not exist
there, is reflective and skips), so "the same probe passes before and after" is reproducible rather
than merely asserted.

The tie-break was checked independently of the one case it has to get right: re-declaring the same
7 edges in **reverse** order gives the same answer before and after, so it genuinely mirrors
`HashMap`'s chain-insertion behaviour rather than coincidentally matching a single arrangement.

## Scope: what this does *not* make deterministic

Traces are still not reproducible run to run. `Generator`'s unseeded shared `Random` (`NDT-05`) is
issue #15, and there is no stop condition yet (#17). This change makes the four **structural**
orders deterministic; it does not and cannot deliver byte-identical traces on its own.
