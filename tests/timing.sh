#!/bin/sh
# Optional suite instrumentation. Integer epoch seconds are portable to Git Bash and
# macOS date; contiguous boundaries telescope, so rounding cannot inflate group totals.
# No per-case clock process: phases are a few explicit, nested measurements only.
timing_now() { date +%s; }
timing_init() {
  timing_enabled=no
  [ -n "${FLIXW_TEST_TIMINGS:-}" ] || return 0
  timing_enabled=yes
  timing_started=$(timing_now)
  timing_boundary=$timing_started
  timing_name=setup
  timing_pass=${pass:-0} timing_fail=${fail:-0} timing_skipped=${skipped:-0}
  printf 'format\tflixw-suite-timing-v1\n' > "$FLIXW_TEST_TIMINGS"
  printf 'meta\tos\t%s\nmeta\tjdk\t%s\nmeta\ttree\t%s\nmeta\tcompiler_cache\tempty-after-suite-reset\nmeta\trestored_cache\t%s\nmeta\tprecision_seconds\t1\n' \
    "$(uname -s)" "${FLIXW_TEST_JDK:-unknown}" "${FLIXW_TEST_TREE:-source}" \
    "${FLIXW_TEST_CACHE_RESTORED:-unknown}" >> "$FLIXW_TEST_TIMINGS"
}
timing_close_group() {
  printf 'group\t%s\t%s\t%s\t%s\t%s\n' "$timing_name" \
    "$((timing_boundary_end - timing_boundary))" \
    "$((${pass:-0} - timing_pass))" "$((${fail:-0} - timing_fail))" \
    "$((${skipped:-0} - timing_skipped))" >> "$FLIXW_TEST_TIMINGS"
}
timing_group() {
  [ "$timing_enabled" = yes ] || return 0
  timing_boundary_end=$(timing_now)
  timing_close_group
  timing_boundary=$timing_boundary_end timing_name=$1
  timing_pass=${pass:-0} timing_fail=${fail:-0} timing_skipped=${skipped:-0}
}
timing_phase_start() {
  [ "$timing_enabled" = yes ] || return 0
  timing_phase=$1 timing_command=$2
  timing_phase_started=$(timing_now)
}
timing_phase_end() {
  [ "$timing_enabled" = yes ] || return 0
  printf 'phase\t%s\t%s\t%s\n' "$timing_phase" \
    "$(($(timing_now) - timing_phase_started))" "$timing_command" >> "$FLIXW_TEST_TIMINGS"
}
timing_finish() {
  [ "$timing_enabled" = yes ] || return 0
  timing_boundary_end=$(timing_now)
  timing_close_group
  printf 'total\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$((timing_boundary_end - timing_started))" "${pass:-0}" "${fail:-0}" "${skipped:-0}" "$1" "$timing_started" "$timing_boundary_end" >> "$FLIXW_TEST_TIMINGS"
}
