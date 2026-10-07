---
name: draft-reply-to-letter
description: Write a reply email to the sender of the letter being discussed, in the letter's language, when the user asks to answer, respond to or reply to a letter.
---

# Draft a reply to the letter

## Instructions

Use this when the user asks you to reply to, answer or respond to the letter they are looking at, or to write to its sender.

1. Read the letter context you were given. Find what you need there, and only there:
   - the sender's email address, if the letter prints one (or the user gave you one);
   - the letter's language;
   - the sender's reference (file number, customer number, invoice number, case number) and the date of the letter, if they are printed;
   - what the letter asks for or says, so the reply answers it.
2. If the letter prints no email address for the sender and the user did not give one, tell the user so and ask for the address. Never invent or guess an address.
3. If you do not know what the user wants to say (accept, object, ask for more time, ask a question), ask them in one short question. Do not decide the user's position for them.
4. Write the email yourself:
   - in the same language as the letter, whatever language the user writes to you in;
   - the subject names the reference exactly as printed in the letter, for example "Re: Aktenzeichen 123/45";
   - the body greets the sender, quotes the reference and the date of the letter exactly as printed, says what the user wants to say, and ends politely with a closing line;
   - use only facts, figures and references that appear in the letter or that the user told you. Do not add amounts, dates or numbers of your own.
5. Call the `run_intent` tool with these exact parameters:
   - intent: send_email
   - parameters: A JSON string with the following fields:
     - extra_email: the sender's email address. String.
     - extra_subject: the subject. String.
     - extra_text: the body. String.
6. The app checks the address and the figures against the letter and shows the user a card; only after the user taps Open does the mail app open with the draft. Tell the user the draft is ready for their check, in their own language, and that they press Send themselves.
