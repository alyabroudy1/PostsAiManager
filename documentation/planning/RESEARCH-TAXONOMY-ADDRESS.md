# Research summaries (2026-09-30), inputs for the final architecture

## A. Document taxonomy (two axes, as in Paperless-ngx: type vs tags)
FAMILY (layout/structure → field schema), with a FREE_FORM fallback:
| Family | Layout signature | Key fields | Parties |
|---|---|---|---|
| official_letter | letterhead, address block, date, subject, salutation, body, signature | sender, recipient, date, reference, subject, deadline | both |
| invoice_bill | tables, totals, IBAN, due date | issuer, customer, invoice no., issue/due date, total, currency, IBAN | both |
| receipt | narrow, items, total, POS | merchant, date, total, payment method | merchant |
| form_application | labelled boxes, checkboxes | form name/ID, applicant, authority, deadline | weak |
| statement | account/period header, transactions, balances | institution, account/policy ref, period, balances | both |
| contract_policy | clauses, parties, signatures | parties, contract/policy no., start/end, amount, notice period | parties |
| certificate_id | card/certificate, stamps | holder, doc type, number, issue/expiry, authority | issuer |
| medical | doctor header, patient, diagnosis/medication | provider, patient, date, diagnosis/prescription | both |
| ticket_booking | QR/barcode, date/time/place | provider, booking ref, date/time, place, passenger | provider |
| note_handwritten | sparse, low OCR confidence | none reliable | none |
| email_printout | From/To/Subject/Date headers | from, to, subject, date | both |
| free_form (fallback) | anything else | universal fields only | none |
(v1 may fold advertisement/manual into free_form.)
TOPIC (multi-valued domain tags): government, tax, health, insurance, bank_finance, housing_utilities, work,
school_education, vehicle, telecom, shopping, travel, legal, personal.
UNIVERSAL fields for every document: title, summary, language, dates[] (role), amounts[] (value, currency, role),
references[] (role), parties[] (optional), topics[], family, confidence.
Sources: RVL-CDIP 16 classes; the Azure Document Intelligence prebuilt models (per-type fields); Google Document AI
custom classifier; Paperless-ngx (document type / correspondent / tags; the Auto matcher learns only from
confirmed docs); German Notfallordner tabs.

## B. Layout conventions (a data registry; soft priors added as log-odds to the LLM's P(yes) per candidate)
- din5008 (DE; AT alias, ÖNORM expired): address field LEFT, 45×85 mm, 20 mm from the left. Form A top 27 mm
  (address zone 44.7), Form B top 45 mm (address zone 62.7). The return line + remarks ≤17.7 mm above. Info block
  right at 125 mm, ≤75 mm wide. The field is "≤5 small lines + ≤6 address lines", so split by font height, not by a
  line count.
- sn010130 (CH): recipient LEFT OR RIGHT (both window variants exist); try both and pick by geometry.
- uk: sender top-right, date, recipient left below. us_block: everything left, letterhead top-left.
- fr_nfz11001: sender top-left; recipient right-of-centre (a soft prior, unverified).
- rtl_generic: a mirror transform of a base convention (low-confidence priors).
Model: Convention{id, aliases, scripts, langs, countries, direction, variants[{zones:[role, bbox_norm, weight]}]},
coordinates normalised to the page. Pick the convention by the ML Kit language ID prior + the country of the
address/postcode (LLM-judged) + the geometry fit; keep the top 2; else a generic weak-prior convention.

## C. Address structure and parsing
- The structured Address: recipientNames[], salutation/title, organisation, department, careOf, attention, street,
  houseNumber, addressExtra, postcode, city, region, countryIso2, poBox, packstation. Per field: value, confidence,
  lineIdx, bbox; the raw lines are kept.
- Parse = the LLM LABELS the zone's OCR lines by index (ORG/PERSON/DEPT/CARE_OF/STREET/STREET2/POSTCODE_CITY/COUNTRY/
  POBOX); code verifies (exact substring, the postcode regex + required fields from libaddressinput country metadata:
  Apache-2.0 code, CC-BY-4.0 data, offline, with attribution).
- libpostal ≈1.8–2.2 GB: not feasible on mobile.
- Sender candidates: the return line, letterhead, info block, footer. Cluster by postcode+street+no. Preference:
  letterhead-org > return line > footer (the footer is often the registered office; keep it as an alternate).
- Pitfalls: multi-person lines; "Familie"/"An die Bewohner" = a household label; c/o / z. Hd. / Vermerke are not
  names; Postfach/Packstation have no house number; foreign addresses; RTL via geometry; window letters with the
  sender only in the footer.

## D. Review (USER DECISION: no review screen, no inbox, no cards)
- The predicted fields show in the existing "Extracted" tab with inline Confirm / Edit / Ignore.
- Patterns: confidence bands (≥90 high, 70–89 medium, <70 check); uncertain / failed-verification fields expanded
  and first; confident verified fields collapsed under one "Confirm all"; tap a field → the source highlight on the
  page (reuse the citation preview); Edit offers candidate alternatives as chips; Ignore = stored, never re-asked;
  learn only from confirmed/edited values (Paperless pattern).
- Identity: set up Me/household once (Phase 2); match silently; ask only on a genuinely new name at the user's
  address. NO per-letter "is this you?".

## E. Title + summary (ALWAYS present, AI-generated)
- Small models copy the first line and hallucinate entities.
- Title: slot-filled from verified fields: "{family label} {from sender} {subject}". The LLM fills only the subject
  (≤5 words); empty slots are dropped.
- Summary: plan-then-write (list the verified facts first, then 1–2 sentences ≤~30 words from those facts); in the
  document's language; one-shot example; anti-copy (reject n-gram overlap >70% with a single OCR line → retry once →
  fall back to a template summary from the verified fields); a verification gate (every number/date/name/amount in
  the summary must exist in the OCR text).
- Feed only the top-ranked blocks by layout role.
