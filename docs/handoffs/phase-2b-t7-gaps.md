# Handoff: phase-2b-t7-gaps

**From:** Claude (planner) → **To:** implementer subagent · **Date:** 2026-10-21 · **Branch/worktree:** `feat/phase-2b-segmentation` in `../Tarry-2b`

## Goal
Phase 2b T7 (`docs/specs/phase-2b.md`): add segmentation-owned gaps as a pure function in a new file `segmentation/src/main/kotlin/me/tarry/segmentation/Gaps.kt`.

## Constraints
- `:segmentation` only, pure Kotlin, no new dependencies.
- New files only: `Gaps.kt`, `RangeMath.kt` and their tests. Do not edit `Model.kt`, `StayDetector.kt`, `Merge.kt` or `Trips.kt`, because another task is editing them now.
- Thresholds come from `SegmentationParams`.

## API
`internal fun buildGaps(input: SegmentationInput, pings: List<Ping>, params: SegmentationParams): List<Gap>`
- `pings` are the run's pings after excluded ranges were dropped, in time order. They are passed separately because the caller filters them.
- The result is in time order, and gaps never overlap each other.

## Rules
1. **NO_SESSION:** each part of `input.windows` not covered by `input.sessions`.
2. **NO_FIX:** inside each session, every silence longer than `params.maxGapToBridgeMs`. Silences are measured between:
   - session start and the first ping in the session;
   - consecutive pings in the session;
   - the last ping and the session end.
   The gap covers the silence (from the earlier ping time to the later one). A session with no pings at all is one NO_FIX if it lasts longer than maxGapToBridge. Shorter silences produce nothing: they were bridged or are ordinary.
3. **Then subtract** from every candidate gap:
   - `input.healthGaps` (never overlap a health-monitor gap);
   - `input.frozen` (user-confirmed time);
   - everything outside `input.range` (clip to it).

   After subtraction a gap can split into pieces. Drop pieces shorter than 1 minute (`MIN_GAP_MS = 60_000L` as a private const, documented as display noise).
4. NO_SESSION and NO_FIX cannot overlap, since NO_SESSION is outside sessions and NO_FIX is inside them. Still assert it in a test.

## Helpers
Write small, reusable interval helpers in `RangeMath.kt`, because T8 will reuse them:
- `internal fun List<TimeRange>.normalized(): List<TimeRange>` sorts and merges overlapping or touching ranges.
- `internal fun TimeRange.minus(others: List<TimeRange>): List<TimeRange>`.
- `internal fun TimeRange.clip(to: TimeRange): TimeRange?`.

These use half-open semantics, as `TimeRange` defines.

## Tests (required)
- **`RangeMathTest`:** merge of overlapping/touching ranges, subtraction producing 0, 1 or 2 pieces, and clip.
- **`GapsTest`:**
  - a window fully covered by a session → no gaps;
  - a window with a session covering only the middle → two NO_SESSION gaps;
  - a session with pings every minute except one 50 min silence → one NO_FIX over exactly that silence;
  - a 40 min silence → none;
  - a session with no pings lasting 2 h → one NO_FIX;
  - a health gap covering part of a NO_FIX → the gap is clipped, and split if the health gap sits in the middle;
  - a frozen range is subtracted;
  - everything is clipped to `input.range`;
  - pieces under 1 min are dropped;
  - the output is ordered and non-overlapping.

## Verification
```
cd ../Tarry-2b && ./gradlew.bat :segmentation:test && grep -r "import android" segmentation/   # grep must be empty
```

## Commit
`feat(segmentation): NO_SESSION and NO_FIX gaps (2b T7)`, including this handoff. The message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage only your files: the main session has uncommitted edits in the same worktree, so do not `git add -A`. Do not push.
