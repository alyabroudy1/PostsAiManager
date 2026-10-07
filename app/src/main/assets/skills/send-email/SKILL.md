---
name: send-email
description: Open the mail app with an email ready to send, when the user asks to write or send an email.
---

# Send email

Adapted from the Google AI Edge Gallery `send-email` skill (Apache License 2.0).

## Instructions

Use this when the user asks you to send, write or draft an email and tells you, or the conversation shows, who it goes to and what it says.

1. Decide the three values yourself from the conversation. Never invent an email address: use one the user wrote or one printed in the letter or stored for the person. If the address or the point of the email is missing, ask the user instead of calling the tool.
2. Call the `run_intent` tool with these exact parameters:
   - intent: send_email
   - parameters: A JSON string with the following fields:
     - extra_email: the email address to send the email to. String.
     - extra_subject: the subject of the email. String.
     - extra_text: the body of the email. String.
3. The app does not send anything by itself. It shows the user a card with the address, the subject and the body, and only after the user taps Open does the mail app open with the email filled in; the user presses Send there. Tell the user that the email is ready for their check, not that it was sent.
