---
name: remind-me-before-deadline
description: Set a reminder notification before the deadline of the letter being discussed, when the user asks to be reminded before a deadline or due date.
---

# Remind me before the deadline

## Instructions

Use this when the user asks to be reminded before the deadline or due date of the letter they are looking at.

1. Read the letter context you were given and find the deadline the user means. Use the date exactly as printed in the letter. If the letter has no deadline, tell the user and ask for the date they want. Never invent a date.
2. Decide when to remind: if the user said how long before ("two days before", "a week before"), use that. If not, ask them in one short question, or if they asked for no particular time, use three days before the deadline at 09:00.
3. You MUST first call the `run_intent` tool with intent get_current_date_and_time and parameters {} to learn today's date, time and day of the week; never guess it. Write out today's date, the deadline, how long before it the reminder is, and the final date and time of the reminder, rolling over months and years where needed. Never schedule a time that has already passed. If the reminder time would be in the past, tell the user and offer a later time.
4. Write the reminder text yourself, short, in the language of the letter: what is due, to whom, by when, and the amount if one is due, exactly as printed in the letter.
5. Call the `run_intent` tool with these exact parameters:
   - intent: schedule_notification
   - parameters: A JSON string with the following fields:
     - message: the reminder text. String.
     - year, month, day: the date of the reminder (not the deadline). Numbers.
     - hour, minute: the time of the reminder. Numbers.
     - document_id: the id of the letter, only if you were given it. String.
6. The app checks the date against the letter and shows the user a card; only after the user taps Open is the reminder scheduled. Tell the user the reminder is ready for their check.
