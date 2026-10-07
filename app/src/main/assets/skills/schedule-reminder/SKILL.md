---
name: schedule-reminder
description: Schedule a reminder notification at a date and time, when the user asks to be reminded of something.
---

# Schedule reminder

Based on the `schedule_notification` intent of the Google AI Edge Gallery (Apache License 2.0). The reminder is scheduled by the app's own reminder system, on this phone only.

## Instructions

Use this when the user asks to be reminded of something at a certain time.

1. Decide the values yourself from the conversation. Use only dates and times the user said, or that follow from one the user said or one printed in the letter ("two days before the deadline"). If you cannot tell when, ask the user instead of guessing.
2. If the time the user asked for is relative ("in 2 minutes", "tomorrow", "next week", "on Friday"), you MUST first call the `run_intent` tool with intent get_current_date_and_time and parameters {} to get the user's local date, time and day of the week. Never guess today's date. Then write out in your response: today's exact date and day of the week, the relative time the user asked for, how many minutes or days to add, and the final date and time, rolling over to the next month or year where needed. Only after that call the action below.
3. Call the `run_intent` tool with these exact parameters:
   - intent: schedule_notification
   - parameters: A JSON string with the following fields:
     - message: what the reminder says, short and in the language the user writes in. String.
     - year: the year of the reminder. Number.
     - month: the month, 1 to 12. Number.
     - day: the day of the month. Number.
     - hour: the hour, 0 to 23. Number.
     - minute: the minute, 0 to 59. Number.
     - document_id: only when you were given the id of the letter the reminder is about. String.
4. The app shows the user a card with the text and the time, and only after the user taps Open is the reminder scheduled. Tell the user that the reminder is ready for their check, not that it is set.
