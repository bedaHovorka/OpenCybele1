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

* `orderRank` is the *hash spread* function `HashMap` uses when it computes a bucket index. Sorting
  by it reproduces the bucket order the old code exhibited, for the topology in use.
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

**No behavioural difference was found.** Every one of the four orderings, and every derived value
listed above, is byte-for-byte what the pre-#19 tree produced.

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
make the candidate-edge order decide the route. This is precisely why claim 4 still had to be
fixed even though the default network hides it.

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

## How to check

```bash
docs/probes/run.sh OrderLock    # self-verdicting; exit 1 on any drift
docs/probes/run.sh Order        # raw dump, for eyeballing / md5
```

`OrderLock` rebuilds the graph 100 times and re-checks every order each time.

## Scope: what this does *not* make deterministic

Traces are still not reproducible run to run. `Generator`'s unseeded shared `Random` (`NDT-05`) is
issue #15, and there is no stop condition yet (#17). This change makes the four **structural**
orders deterministic; it does not and cannot deliver byte-identical traces on its own.
