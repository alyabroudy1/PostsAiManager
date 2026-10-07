---
name: send-email
description: Open the mail app with an email ready to send, when the user asks to write or send an email.
---

# Send email

1. Decide address, subject and body from the conversation. Never invent an address: use one the user wrote or one in the letter or its "What was read from this letter" section. If the address or the point is missing, ask instead of calling the tool.
2. Call `run_intent`:
   - intent: send_email
   - parameters: a JSON string with
     - extra_email: String.
     - extra_subject: String.
     - extra_text: the body. String.
3. Nothing is sent until the user taps Open on the card and presses Send. Say the email is ready for their check, not that it was sent.
