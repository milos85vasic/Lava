# scan-no-removelast-seqcoll.sh

LVA-054 JDK21 `SequencedCollection` guard — a §6.J anti-bluff source scanner.

Script: `scripts/scan-no-removelast-seqcoll.sh`

## Purpose

Enforces that no production Kotlin uses the `SequencedCollection`-shaped
`List`/`MutableList` methods that crash on Android runtimes below API 35.

## The crash class (LVA-053 / LVA-054)

Kotlin `MutableList.removeLast()` / `removeFirst()` and `List.getFirst()` /
`getLast()` compile, under JDK21 + AGP core-library-desugaring, to the JDK21
`java.util.SequencedCollection` methods (`java.util.List.removeLast`, etc.).
Those methods do NOT exist on the Android platform's `java.util.ArrayList` below
API 35, so a real user on Android 14 / 13 / 12 hits a runtime
`NoSuchMethodError`. JVM unit tests pass (desktop `ArrayList` HAS the methods) —
exactly the bluff this gate evicts: the test is green, the feature crashes for
the user.

## What it does

1. Greps tracked production Kotlin (`*.kt`, excluding test sources) for the
   forbidden `removeLast()` / `removeFirst()` / `getFirst()` / `getLast()` call
   shapes on `List`/`MutableList` receivers.
2. Exits 1 (printing the offending `file:line`) on any match; exit 0 when clean.

Use `.removeAt(lastIndex)` / `.first()` / `.last()` (the Kotlin-stdlib forms that
do NOT desugar to `SequencedCollection`) instead.

## Usage

```
bash scripts/scan-no-removelast-seqcoll.sh
```

## Wiring

Invoked by `scripts/check-constitution.sh` (pre-push gate); hermetic test at
`tests/check-constitution/test_no_removelast_seqcoll.sh`.

## Constitutional bindings

§6.J (tests/gates must guarantee the product works for real users — this gate
catches a runtime crash class invisible to JVM unit tests), §6.R-adjacent
(mechanical source scanner).
