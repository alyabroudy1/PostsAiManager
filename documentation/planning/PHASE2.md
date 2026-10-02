# Phase 2: "Who" (people, organisations, who a letter is for)

Starts after Phase 1 (roles come out correct from Extraction v2). Branch: feat/people, from feat/extraction-v2.
Binding directives:
- AI decides, code verifies. Nothing static decides meaning.
- Clean architecture: one owner per concept; additive migrations; Fable review gate at the end.
- Low noise: silent when certain, ≤1 question per letter, questions grouped in the Review inbox.

## Model (additive migration, next DB version)
- `profiles` gains:
  - `kind` (PERSON/ORGANIZATION; migrate AUTHORITY → ORGANIZATION);
  - `managed` (bool; USER_SELF/FAMILY_MEMBER → true);
  - `isSelf` (exactly one, enforced in the repository);
  - `relationship` (PARTNER/CHILD/PARENT/RELATIVE/FRIEND/OTHER);
  - `birthDate?`, `color`, `sensitive` (bool: hide from all-docs chat, content-free notifications).
- `profile_aliases` (profileId, alias, normalized, source USER/CONFIRMED/AI, negative bool).
  Migrate `dismissed_entities` → negative aliases.
- `document_parties` replaces `document_profile_links`:
  - PK (documentId, profileId, role);
  - role ADDRESSEE/CO_ADDRESSEE/SUBJECT/ROUTING/SENDER/SENDER_CONTACT/MENTIONED;
  - origin LEGACY_AUTO/AUTO/SUGGESTED/CONFIRMED/USER; confidence; isPrimary.
  - Migrate old links as LEGACY_AUTO; the old table is dropped after the copy, in the same migration.
- Identifiers: no new table. They are typed extracted fields (Phase 1 slots: tax_id, insurance_no, customer_no …) plus a
  resolved `subjectProfileId` on the field.

## ResolveParties (the single writer of parties/proposals; pipeline stage after Understand)
- Input: Extraction v2 parties (role, kind, relation, normalizedName, candidate/quote, confidence) plus identifier slots.
- Matching ladder:
  1. an exact identifier on an existing profile's fields → auto;
  2. exact normalized name/alias + (birth date or household address) → auto;
  3. exact normalized name/alias alone, for ADDRESSEE → auto if unique among managed profiles;
  4. otherwise a ranked suggestion.
- For ambiguous pairs only, one short "same party?" model call.
- No phonetics. Never auto-create persons. Organisations may auto-create (deduped by IBAN / normalized name / address).
- "Familie X" / household → link all managed persons as CO_ADDRESSEE (the AI marks the relation household=true).
- Learns: a confirmation adds a CONFIRMED alias; "not this person" adds a negative alias.
- Retire ProfileMatchingService (view-time matching in DocumentDetailViewModel), EntityCoverageFilter, EntityProfileLinker,
  ProfileMatcher's string rules and DI bindings. The VM becomes read-only over parties/proposals.

## UX
- Onboarding (3 steps, skippable):
  1. "Who are you?", prefilled ONLY from validated ADDRESSEE parties with confidence ≥ HIGH;
  2. "Who else do you handle mail for?" (relationship chips + name + birth date);
  3. done.
- Person chips on Home (All · Me · members · Household) filter documents, tasks (P3) and chat scope.
- Review inbox:
  - grouped by name ("3 letters for 'Erika Musterman' — is this you?"); one answer applies to all;
  - auto-expires after 30 days; max 5 visible; a badge on Home.
- Letter detail: "For" chips (role label). Tap to change or move (with undo); add a person; remove.
- Person page: letters (by date), key numbers found (typed fields with source letter, copy), settings (aliases,
  sensitive, delete with the "unlink only" / "delete letters only this person has" choice).
- Sensitive persons: excluded from the all-documents chat and retrieval unless their chip is selected; notifications
  content-free.

## DoD
- On testdocs2 N2/N3/N4/N5/N9/N10 + testdocs: 100% correct party roles vs manifest `roles`.
- 0 questions when the addressee matches a managed profile exactly; N9 (misspelling) → exactly one grouped question.
- The migration keeps all existing links (as LEGACY_AUTO). MigrationTest runs on the device.
- The old matchers are deleted; Konsist is green; Fable review passed.
