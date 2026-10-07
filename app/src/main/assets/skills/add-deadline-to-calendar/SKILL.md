---
name: add-deadline-to-calendar
description: Put the deadline or appointment of the letter being discussed into the user's calendar, when the user asks to add it to the calendar.
---

# Add the deadline to the calendar

## Instructions

Use this when the user asks you to add the letter's deadline, due date or appointment to their calendar.

1. Read the letter context you were given and find the date the user means: the deadline, the due date of a payment, or the appointment. Use the date exactly as printed in the letter. If the letter has several dates and it is not clear which one the user means, ask which.
2. If the letter prints a time for it, use that time. If it prints only a date, use 09:00 as the start and say so to the user.
3. If the letter has no such date, tell the user and ask for it. Never invent a date.
4. Write the event yourself:
   - title: short, in the letter's language, naming what is due and who sent it, for example "Frist: Stadtwerke Rechnung";
   - description: the reference of the letter exactly as printed, and the amount if one is due, exactly as printed. Add nothing else that is not in the letter.
5. Call the `run_intent` tool with these exact parameters:
   - intent: create_calendar_event
   - parameters: A JSON string with the following fields:
     - title: the title. String.
     - description: the description. String.
     - begin_time: the start, as yyyy-MM-ddTHH:mm:ss. String.
     - end_time: leave it out unless the letter prints an end time.
6. The app checks the date and the figures against the letter and shows the user a card; only after the user taps Open does the calendar open with the event. Tell the user the event is ready for their check.
