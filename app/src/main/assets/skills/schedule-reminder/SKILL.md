---
name: schedule-reminder
description: Schedule a reminder notification, at a time the user names (like "in 2 minutes", "tomorrow at 9", "on Friday") or before the deadline of the letter being discussed, when the user asks to be reminded of something.
---

# Schedule reminder

Based on the `schedule_notification` intent of the Google AI Edge Gallery (Apache License 2.0). The reminder is scheduled by the app's own reminder system, on this phone only.

## Instructions

Use this when the user asks to be reminded of something, at a time they name or before the deadline of the letter.

1. Work out when. Use only a time the user said, or one that follows from a date printed in the letter ("two days before the deadline"; use the date exactly as printed; if the user gave no lead time, use three days before at 09:00). If you cannot tell when, ask the user one short question instead of guessing. Never invent a date.
2. If the time is relative ("in 2 minutes", "tomorrow", "next week", "on Friday") or must be checked against today (a reminder must not be in the past), you MUST first call the `run_intent` tool with intent get_current_date_and_time and parameters {} to get the user's local date, time and day of the week. Never guess today's date. Then write out in your response: today's exact date and day of the week, the time the user asked for, how many minutes or days to add, and the final date and time, rolling over to the next month or year where needed.
3. Write the reminder text yourself: one short line in the language the user writes in, saying what to do (for example "Pay the phone bill"). Do not copy or paraphrase the letter.
4. Call the `run_intent` tool with these exact parameters:
   - intent: schedule_notification
   - parameters: A JSON string with the following fields:
     - message: the reminder text, one short line. String.
     - year: the year of the reminder. Number.
     - month: the month, 1 to 12. Number.
     - day: the day of the month. Number.
     - hour: the hour, 0 to 23. Number.
     - minute: the minute, 0 to 59. Number.
     - document_id: only when you were given the id of the letter the reminder is about. String.
5. The app checks the time and shows the user a card; only after the user taps Open is the reminder scheduled. Tell the user in one sentence that the reminder is ready for their check, not that it is set.
