---
name: create-calendar-event
description: Open the calendar with a new event filled in (title, start, end, notes), when the user asks to put something in their calendar.
---

# Create calendar event

Based on the `create_calendar_event` intent of the Google AI Edge Gallery (Apache License 2.0).

## Instructions

Use this when the user asks to add an appointment, a meeting or a date to their calendar.

1. Decide the values yourself from the conversation and the letter. Use only dates and times that the user said or that are printed in the letter. If the date or the time is missing, ask the user instead of guessing.
2. If you need today's date to work out a day such as "next Monday" or "tomorrow", first call the `run_intent` tool with intent get_current_date_and_time and parameters {}.
3. Call the `run_intent` tool with these exact parameters:
   - intent: create_calendar_event
   - parameters: A JSON string with the following fields:
     - title: a short title for the event. String.
     - description: notes for the event, for example the reference number from the letter. String, may be empty.
     - begin_time: the start, as yyyy-MM-ddTHH:mm:ss, for example 2026-11-05T09:00:00. String.
     - end_time: the end in the same format. String. Leave it out when the end is not known.
4. The app shows the user a card with the title and the times, and only after the user taps Open does the calendar open with the event filled in; the user saves it there. Tell the user that the event is ready for their check, not that it was saved.
