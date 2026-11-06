# dv-golden: MKV per-sample DV pipeline goldens

Spec 21 N2 (2026-10-30). Byte outputs of the Kotlin MKV pipeline
(`DolbyVisionMatroskaTransformer` + `HevcDvRpuStripper` + `HevcHdr10PlusStripper`) with the fake
converter below, over a synthetic corpus built by `DolbyVisionGoldenCorpusTest` (hand-built cases
plus seeded `random_*` cases: random NAL mixes with damaged lengths, added in the N3 review). The N3 native
core (`scripts/test-dv-native.sh`) must reproduce every `.out` byte for byte from its `.in`.
They pin CURRENT behaviour, quirks included (equivalence, not unification).

## Files

- `cases.tsv`: one row per case: `name config profile lfl frames rpu_drops` (tab separated, header row).
  `profile` = dvcC profile (`none` = plain HEVC, no DV config); `lfl` = NAL length-field bytes (1-4);
  `rpu_drops` = RPUs dropped on conversion failure over the whole case (`DolbyVisionConversionStats`).
- `<name>.in`, `<name>.out`: frames files. Per frame: u32 big-endian byte count, then the bytes.
  Frames of one case run in order through ONE transformer (state carries: DV5 decision, abandon).
  `.out` frame = what the extractor writes: the rewritten sample, or the input when unchanged.

## Configs (transformer constructor; converter = fake)

| config | DolbyVisionConversionConfig | stripRpuOnly | stripHdr10PlusSei |
|---|---|---|---|
| convert_auto | active | false | false |
| convert_manual | active, manualDv81 | false | false |
| convert_manual+hdr10p | active, manualDv81 | false | true |
| convert_dv5 | active, dv5Enabled, manualDv81 | false | false |
| strip | inactive | true | false |
| strip+hdr10p | inactive | true | true |
| hdr10p | inactive | false | true |

injectHdr10Sei is off everywhere: injection needs the
RPU static metadata from libdovi and stays Kotlin (N4).

Runner (mirrors the vendored MatroskaExtractor): `shouldTransform(codecs = null, dvcC)` once; if
false every frame passes through; else `transformHevcSample(frame, size, lfl, dvcC)`, output =
result[0, lastTransformedSampleLength()) or the input when null. (The extractor also keeps the
input on a RuntimeException; the golden runner fails instead, so no golden records a crash.)
dvcC = 24 bytes: `[0]=1, [2]=profile<<1, [3]=0x30`, rest 0. Mode: convert_auto -> 1, manual -> 2,
P5 (convert_dv5) -> 2.

## Per-sample order and per-stream policy (DolbyVisionMatroskaTransformer)

shouldTransform: true for any config with stripHdr10PlusSei; else strip configs when a dvcC is
present; else convert configs when the dvcC profile converts (7 always; 5 only with dv5Enabled +
manualDv81; never 8).

Per sample, in this order:

1. Strip configs: if profile 5 and the DV5 decision (below) is not STRIP, step 3 only. Else the
   RPU/EL strip walk (`HevcDvRpuStripper.stripRpuLengthDelimited`: drops type 62, type 63 and
   nuh_layer_id > 0; any NAL length 0 or past the end aborts the walk = sample unchanged; a
   tail shorter than a length field is dropped). nuh_layer_id comes from the NAL's first two
   bytes, but for a 1-byte NAL the second byte is the next byte of the SAMPLE (the next length
   field), when one exists, else 0. If it dropped a NAL (the tail alone does not count), step 3
   on its output (tail removed; an empty output, every NAL dropped, is used as a 0-byte frame,
   unlike step 2), else step 3 on the input.
2. Convert configs (profile that does not convert, reached via hdr10p, or P5 without dv5 RPU
   conversion -> step 3 only): the convert walk drops
   nuh_layer_id > 0 NALs except type 62, and every type 63; converts each type 62 through the
   converter and rewrites its length field (an output too large for `lfl` aborts the walk = sample
   unchanged); copies other NALs, including length-0 NALs. nuh_layer_id of a NAL shorter than 2
   bytes is 0 (unlike step 1). A length past the end or a tail
   shorter than a length field aborts the walk (sample unchanged); a walk that changed nothing,
   or whose output is empty, leaves the sample unchanged. Then step 3.
3. HDR10+ (when stripHdr10PlusSei; `HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited`, restated
   here; `nal_pipeline.cpp` `filter_sei_nal` is the native port of the per-NAL filter):
   a. Pre-scan: walk the NALs; a length 0 or past the end ends the scan. If no type 39/40 NAL
      contains the bytes `B5 00 3C 00 01` anywhere in it (header included), the sample is unchanged.
   "Unchanged" in step 3 means step 3 returns its input (the step 1/2 output); so a length-0 NAL
   kept by step 2 disables HDR10+ stripping for that sample.
   b. Walk: any length 0 or past the end -> the sample is unchanged (the whole result is
      discarded). Non-SEI NALs are copied. Each type 39/40 NAL goes through the filter:
      - size < 3: kept.
      - Unescape: copy the 2 header bytes; from byte 2, each `00 00 03` lying wholly inside the
        NAL becomes `00 00` (scanning resumes after the 03); other bytes copy.
      - Parse messages from offset 2 while bytes remain: if exactly one byte remains and it is
        0x80, stop. payloadType = sum of bytes read up to and including the first byte != 0xFF;
        payloadSize the same way (both stop at the end). payloadSize > remaining bytes -> the NAL
        is kept as is. A message is HDR10+ when payloadType == 4, payloadSize >= 5 and the payload
        starts `B5 00 3C 00 01`; any other message is kept verbatim (type bytes, size bytes, payload).
      - No HDR10+ message: NAL kept as is. Only the header left: NAL dropped. Else the new NAL is
        header + kept messages + `80`, re-escaped: from byte 2, write `03` before any byte <= 3 that
        follows two consecutive `00` (the zero run starts from the header bytes and restarts after
        an inserted `03`); its length field is rewritten with the new size, written as the low `lfl`
        bytes of it with no overflow check (unlike step 2).
   c. Changed only when some NAL was rewritten or dropped; then a tail shorter than a length
      field is dropped. Otherwise the sample is unchanged.

Abandon (convert path): a conversion failure (converter <= 0) drops that RPU and counts one
rpu_drop; a success resets the consecutive-failure count. These updates happen as the walk reaches
each RPU and persist even when the same walk later aborts (sample unchanged). When the count reaches 60, conversion
is abandoned for the stream: every later RPU is dropped without calling the converter and still
counts one rpu_drop.

DV5 decision (strip configs, profile 5 only): undecided at stream start. Each sample while
undecided: if any type-39 NAL whose first payload byte (byte 2 of the NAL) is 137 (MDCV) or 144
(CLLI) appears before the first malformed length (length 0 or past the end ends the scan), the
decision is STRIP from this sample on; otherwise the sample counts, and the 15th counted sample
fixes the decision at SKIP (never strip).

## Fake converter

`convert(nal, len, mode)` where `nal` includes its 2-byte header:

- `len < 3` or `nal[2] == 0xFF` -> fail (0 bytes; the pipeline drops the RPU).
- else output `len + 1` bytes: `nal[0], nal[1]`, then `nal[i] ^ 0x5A` for `i in 2..len-1`, then `mode`.

Corpus RPUs carry `nal[2] = 0x19` (converts) or `0xFF` (fails).

## Regenerating

A missing or mismatched golden writes the actual files to `app/build/dv-golden-actual/`. Review
them (per-frame NAL sequences) before copying them here.
