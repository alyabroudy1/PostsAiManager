---
name: create-calendar-event
description: Open the calendar with a new event filled in (title, start, end, notes), at a time the user names or for the deadline or appointment of the letter being discussed, when the user asks to put something in their calendar.
---

# Create calendar event

Based on the `create_calendar_event` intent of the Google AI Edge Gallery (Apache License 2.0).

## Instructions

Use this when the user asks to add an appointment, a meeting, a deadline or a due date to their calendar.

1. Work out the date. Use only a date and time the user said, or one printed in the letter (the deadline, the due date of a payment, the appointment), exactly as printed. When the letter's details have a "What was read from this letter" section, it says what each date means; a due date or deadline is the one it describes as such, not a contract start or the letter's own date. If the letter prints only a date, use 09:00 as the start and say so. If the letter has several dates and it is not clear which one the user means, ask which. If there is no such date, ask the user. Never invent a date.
2. If the time is relative ("in 2 hours", "tomorrow", "next Monday", "this Friday"), you MUST first call the `run_intent` tool with intent get_current_date_and_time and parameters {} to get the user's local date, time and day of the week. Never guess today's date. Then write out in your response: today's exact date and day of the week, the time the user asked for, how many days to add, and the final date, rolling over to the next month or year where needed.
3. Write the event yourself: a short title in the language of the letter or the user, naming what it is and who sent it (for example "Due: Stadtwerke bill"), and notes with the reference of the letter and the amount if one is due, exactly as printed. Add nothing that is not in the letter.
4. Call the `run_intent` tool with these exact parameters:
   - intent: create_calendar_event
   - parameters: A JSON string with the following fields:
     - title: a short title for the event. String.
     - description: notes for the event, for example the reference number from the letter. String, may be empty.
     - begin_time: the start, as yyyy-MM-ddTHH:mm:ss, for example 2026-11-05T09:00:00. String.
     - end_time: the end in the same format. String. Leave it out when the end is not known.
5. The app checks the date and the figures against the letter and shows the user a card; only after the user taps Open does the calendar open with the event filled in, and the user saves it there. Tell the user in one sentence that the event is ready for their check, not that it was saved.
