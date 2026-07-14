# README revamp plan

Status: SHIPPED (2026-07-14). README.md rewritten per the structure below with real
device captures. Remaining decisions for the repo owner:
- License: no LICENSE file exists yet, so the README has no license section. Pick one
  and add both.
- Review pass on the new README + delete this plan when satisfied.

## Reference survey
Patterns taken from haze, telephoto, Reorderable, coil:
- Motion before words: demo media directly under the title.
- Animated `.webp` in-repo (autoplays in READMEs, ~4x smaller than GIF).
- Reorderable is the closest model (no docs site, everything inline, recipe-shaped
  sections). Coil's four-bullet value proposition is the best opener pattern.
- Short sentences, benefit-first, no academic prose.

## Structure (as shipped)
1. Hero: pitch, badges, hero webp (Showcase pan/zoom, 18-48s of the recording).
2. Sample table: 2x3 webp grid; Snap-fling, Free bridge, Worst case, Rings, Flower,
   Chaos mode; with run-the-samples one-liners.
3. Feature bullets (6).
4. Quick start: module/AAR consumption + 15-line snippet.
5. Core concepts (incl. Bundle-safe keys, relation-owned margins, diagonal pairs).
6. Behaviour: placement / solver / scrolling / gestures / healing / animations.
7. Recipes: snap paging, animateToItem, RubberBandOverscroll (iOS note), placeAround
   radial layouts, perf monitor + debug logger, relation-lines overlay.
8. API tables.
9. Pitfalls: 14 entries as symptom -> why -> fix.
10. Development: targets, modules, build table, iOS team setup, release-profiling note
    (Xcode Release configuration), Worst case as perf harness.

## Media pipeline (for future re-captures)
Source: iPhone screen recordings (1170x2532 .mov) in ~/Downloads.
Convert: ffmpeg -ss S -t D -i in.mov -an \
  -vf "crop=iw:ih-140:0:140,setpts=PTS/SPEED,fps=12,scale=320:-2" \
  -c:v libwebp -q:v 55 -compression_level 6 -loop 0 docs/media/name.webp
(crop removes the iOS status bar; hero used width 400 / fps 14 / q 58.)
Files: hero-showcase, snap-fling, free-bridge, worst-case, rings, flower,
showcase-chaos (0.4-4.2MB each).
Verify webps render via headless Chrome screenshot of an <img> page (ffmpeg cannot
decode animated webp).
