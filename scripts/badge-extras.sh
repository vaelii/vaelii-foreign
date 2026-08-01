# shellcheck shell=bash
# scripts/badge-extras.sh — vaelii-foreign domain badges, sourced by
# update-badges.sh between the loc and docstrings badges. Appends to the
# keys/msgs/links arrays it inherits; the count/g helpers are in scope.
#
# formats -> the foreign kinds this artifact teaches the engine to read, counted
#            from the plugin descriptor itself rather than from the source, since
#            that resource is what the engine merges and therefore what is true.
#            Counted per reader var, not per line: the first entry of the map
#            shares its line with the opening brace.
# opcodes -> CFASL opcodes the reader implements, counted from its opcode table.
#            Drops out until that table exists. The `{?` is not optional cosmetics:
#            cljfmt aligns the map's first entry onto the brace's own line, so a
#            pattern anchored on leading whitespace alone would miss it and undercount
#            by one.
formats_n=$(count g -roE 'vaelii\.foreign\.[a-z.-]+/reader' resources/vaelii/foreign.edn)
opcodes_n=0
[[ -f src/vaelii/foreign/cfasl.clj ]] &&
  opcodes_n=$(count g -roE '^\s*\{?\s*[0-9]+ +:[a-z0-9-]+' src/vaelii/foreign/cfasl.clj)
keys+=(formats); msgs+=("$formats_n"); links+=("resources/vaelii/foreign.edn")
if [[ "${opcodes_n:-0}" -gt 0 ]]; then
  keys+=(opcodes); msgs+=("$opcodes_n"); links+=("src/vaelii/foreign/cfasl.clj")
fi
