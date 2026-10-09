---
name: create-calendar-event
description: Only when the user explicitly asks to add an event or appointment to the calendar.
time-aware: true
---

# Create calendar event

1. Use only a date the user said or one printed in the letter, exactly as printed (the letter's "What was read from this letter" says which is the deadline or appointment). Date only: start 09:00. If several fit, ask which. Never invent a date. For "tomorrow" or "next Monday", work it out from the current time at the end of this text ("Now:").
2. title: short, self-contained, naming what it is and the sender or subject, without pronouns such as "it", e.g. "Due: Stadtwerke bill". description: the reference and amount, exactly as printed.
3. Call `run_intent`:
   - intent: create_calendar_event
   - parameters: a JSON string with
     - title: String.
     - description: String, may be empty.
     - begin_time: yyyy-MM-ddTHH:mm:ss, e.g. 2026-11-05T09:00:00. String.
     - end_time: same format. String. Leave out when unknown.
4. Nothing is saved until the user taps Open on the card. Say the event is ready for their check.
