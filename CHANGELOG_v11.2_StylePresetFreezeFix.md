# VasiliasTyper — Style/Preset Freeze & Crash Fix

## Bug: freeze → force close when using a Style or Preset

**Files:** `app/src/main/java/com/vasiliastyper/MainActivity.kt`

**Root cause:** `buildFontList()` recursively scans `assets/fonts/` +
`assets/font/` and every user-imported custom font, calling
`Typeface.createFromAsset`/`createFromFile` synchronously for each one. On a
cold cache — first use after launch, or right after a custom font import
invalidates the cache — this can block the calling thread long enough to ANR.

`CHANGELOG_v11.1` fixed exactly one call site of this (Quick Style /
`applyQuickStyleToElement`). This pass found and fixed **eight more** call
sites with the same bug, several of which are more central to "using a
Style/Preset" than the one already fixed:

1. **`placeTextElementOnArea()` / `autoPlaceTextWithStyle()`** — reached from
   the Script Panel's **"Use"** button (`setupBottomScriptPanel`'s `onUse`).
   This is the most direct "apply style/preset" action in the app: pick a
   script line, its style-rule match gets applied, text lands on the canvas.
   It called `buildFontList()` directly in a click handler — no coroutine at
   all, the worst case of the bug.
2. `placeAllPendingTipeRMatches()` — "Place All"
3. `executeAutoTypeset()` — Auto Typeset
4. `applyVasTypePreview()` — VasType preview
5. `runVasTypeWorkflow()` — VasType run
6. Bubble Translate (selection-based)
7. `runBubbleTranslationForAreas()` — Bubble translation
8. `showFontBankDialog()` / `runFontMatch()` — Font Bank (same root cause,
   not literally "style/preset" but same freeze)

Cases 2–7 were already inside `lifecycleScope.launch { }`, but since no
dispatcher was specified, they still ran on `Dispatchers.Main` — the freeze
was just hidden one layer deeper than case 1.

**Fix:**
- Every listed call site now resolves `buildFontList()` via
  `withContext(Dispatchers.Default) { buildFontList() }`, matching the
  convention `applyQuickStyleToElement()` already established in v11.1.
- `placeTextElementOnArea()` no longer builds its own font lookup; it now
  takes `fontLookup` as a required parameter, forcing the caller to resolve
  it off the main thread first.
- `autoPlaceTextWithStyle()` now launches a coroutine, resolves the font
  lookup off-thread, then performs the (fast, in-memory) canvas mutation back
  on the main thread. The whole placement step is wrapped in `runCatching`
  with the same toast-on-failure pattern used elsewhere in the style-apply
  flow, so a future edge case degrades gracefully instead of crashing.
- Added `autoPlaceInFlight` guard so a rapid double-tap on "Use" can't start
  a second placement while the first is still resolving fonts.
- `showFontBankDialog()` now fetches the font list off-thread before
  building the dialog UI at all (split into `showFontBankDialogWithFonts()`),
  instead of freezing before the dialog even appears.

## Not touched in this pass

`StyleManager.loadStyles()` / `loadStyleRules()` (SharedPreferences reads)
were left as-is — they're fast even cold and are not the reported freeze
source. `LocalFontMatcher.analyze()` inside `runFontMatch()` still runs on
the main dispatcher after the font-bank fetch, same as before this pass;
that's a separate (lower-impact) latent issue, not part of the reported bug.

No Android SDK / Gradle network access was available in the environment this
patch was written in, so it could not be compile-verified end-to-end here —
please build locally before shipping.
