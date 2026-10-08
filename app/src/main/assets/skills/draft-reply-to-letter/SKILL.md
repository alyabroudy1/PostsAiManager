---
name: draft-reply-to-letter
description: Use only when the user explicitly asks to answer, respond to or reply to the letter being discussed. Writes a reply email to the letter's sender, in the letter's language. Never for a question about a letter.
---

# Draft a reply to the letter

1. Take from the letter context only: the sender's email address, the reference and date of the letter, and what it asks. No address: ask for it. Never invent one. Unknown what the user wants to say: ask one short question.
2. Write in the same language as the letter, whatever language the user writes in. Subject: the reference exactly as printed, e.g. "Re: Aktenzeichen 123/45". Body: greeting, the reference and date, what the user wants to say, a polite close. Add no amounts, dates or numbers of your own.
3. Call `run_intent`:
   - intent: send_email
   - parameters: a JSON string with
     - extra_email: the sender's address. String.
     - extra_subject: String.
     - extra_text: the body. String.
4. Nothing is sent until the user taps Open on the card and presses Send. Say the draft is ready for their check.
