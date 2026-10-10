---
name: schedule-reminder
description: Only when the user explicitly asks to be reminded or to set a reminder or notification.
time-aware: true
---

# Schedule reminder

1. Use only a time the user said or a date printed in the letter (no lead time given: three days before at 09:00). If unclear, ask one short question. Never invent a date.
2. A time from now needs no clock call: use offsets. "in 2 minutes" = in_minutes 2; "tomorrow at 9" = in_days 1, hour 9, minute 0. Any other date: year, month, day, hour, minute, no offset. The current time is at the end of this text ("Now:").
3. message: one self-contained line in the user's language. Start with the sender's or organisation's name from the letter, then what to do, e.g. "Pay Stadtwerke Musterstadt: Jahresabrechnung Strom". Never use pronouns such as "it" or "them". Never just "Reminder": name the sender or subject.
4. Call `run_intent`:
   - intent: schedule_notification
   - parameters: a JSON string with
     - message: the line. String.
     - in_minutes: from now. Number.
     - in_hours: from now. Number.
     - in_days: from today, with hour and minute. Number.
     - year, month, day: the date. Numbers.
     - hour, minute: time of day. Numbers.
5. Nothing is set until the user taps Open on the card. Say the reminder is ready for their check.
