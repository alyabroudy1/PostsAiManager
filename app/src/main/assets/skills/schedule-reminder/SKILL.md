---
name: schedule-reminder
description: Schedule a reminder notification, at a time the user names (like "in 2 minutes", "tomorrow at 9", "on Friday") or before the deadline of the letter being discussed, when the user asks to be reminded of something.
time-aware: true
---

# Schedule reminder

1. Use only a time the user said or a date printed in the letter (no lead time given: three days before at 09:00). If unclear, ask one short question. Never invent a date.
2. A time from now needs no clock call: use offsets. "in 2 minutes" = in_minutes 2; "tomorrow at 9" = in_days 1, hour 9, minute 0. Any other date: year, month, day, hour, minute, no offset. The current time is at the end of this text ("Now:").
3. message: one self-contained line in the user's language naming the sender or subject, e.g. "Call the Jobcenter about your Bürgergeld application". Never use pronouns such as "it" or "them".
4. Call `run_intent`:
   - intent: schedule_notification
   - parameters: a JSON string with
     - message: the line. String.
     - in_minutes: from now. Number.
     - in_hours: from now. Number.
     - in_days: from today, with hour and minute. Number.
     - year, month, day: the date. Numbers.
     - hour, minute: time of day. Numbers.
     - document_id: the letter's id, if given. String.
5. Nothing is set until the user taps Open on the card. Say the reminder is ready for their check.
